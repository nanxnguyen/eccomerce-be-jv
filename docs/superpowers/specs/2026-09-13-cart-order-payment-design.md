# Cart / Order / Payment / Inventory — Design Spec

Date: 2026-09-13
Status: Approved (design), pending final review

## 1. Overview

Third module set of the ecommerce backend (Spring Boot + PostgreSQL), building
on Auth/User + Product/Catalog (see
[2026-09-13-auth-product-catalog-design.md](2026-09-13-auth-product-catalog-design.md)).
Scope: shipping addresses, a per-user cart, checkout into an order, payment via
COD/VNPay/Stripe, and oversell-safe stock reservation.

## 2. Goals

- Logged-in customers manage shipping addresses (CRUD, one default).
- Logged-in customers add/update/remove items in a persistent cart.
- Customers select specific cart items to check out (partial checkout, like
  Shopee) into an order with a snapshotted shipping address and item prices.
- Payment via COD (confirmed immediately) or online gateway (VNPay, Stripe),
  each behind a common `PaymentGateway` abstraction.
- Stock is never oversold: online-payment orders reserve stock at checkout
  and only commit it on payment confirmation; unpaid orders auto-expire and
  release their reservation.
- Customers can cancel their own order before it ships; admins can advance
  order status (CONFIRMED → SHIPPED → DELIVERED).
- Domain events (`OrderPlaced`, `PaymentConfirmed`, `OrderCancelled`) are
  published in-process so a future async consumer (email, analytics, or a
  Kafka producer) can be added without touching checkout logic.

## 3. Non-goals (explicitly out of scope for this spec)

- Kafka/RabbitMQ, Kubernetes, CI/CD, observability infra — these are
  longer-term platform goals (see project README) with no payoff yet: this is
  a single Spring Boot monolith with no second service to decouple from.
  Domain events are published via Spring's in-process
  `ApplicationEventPublisher` so swapping a listener for a Kafka producer
  later is additive, not a rewrite.
- Guest cart / cart merge-on-login — cart requires login (matches the rest of
  the app: every write action already requires auth).
- Multi-vendor / seller-specific order splitting.
- Refunds, returns, partial refunds.
- Product reviews, coupons/discounts, wishlist — separate future specs.
- Admin analytics/reporting on orders.
- Real merchant/production credentials — VNPay and Stripe integrate against
  sandbox/test keys; the user supplies real keys later via
  `application.properties`, out of band from this implementation.

## 4. Data Model

### `addresses`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT FK → users.id | |
| recipient_name | VARCHAR | |
| phone | VARCHAR | |
| address_line | VARCHAR | |
| ward / district / province | VARCHAR | |
| is_default | BOOLEAN | exactly one default per user, enforced in service layer |
| created_at / updated_at | TIMESTAMP | |

### `carts`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT FK, UNIQUE | one cart per user, created lazily on first add |

### `cart_items`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| cart_id | BIGINT FK → carts.id | |
| variant_id | BIGINT FK → product_variants.id | |
| quantity | INT | |
| UNIQUE(cart_id, variant_id) | | adding an existing variant increments quantity instead of a new row |

No price snapshot — cart always reflects the live variant price/availability.

### `orders`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT FK → users.id | |
| status | ENUM | `PENDING_PAYMENT, CONFIRMED, SHIPPED, DELIVERED, CANCELLED` |
| payment_method | ENUM | `COD, VNPAY, STRIPE` |
| recipient_name / phone / address_line / ward / district / province | VARCHAR | snapshot copied from the chosen `Address` at checkout |
| total_amount | DECIMAL(14,2) | sum of order_items snapshot prices × quantity — wider than `product_variants.price`'s DECIMAL(12,2) because a total is a sum across items/quantity and can exceed any single item's range |
| expires_at | TIMESTAMP, nullable | set for VNPAY/STRIPE only; null for COD (nothing to expire) |
| created_at / updated_at | TIMESTAMP | |

### `order_items`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| order_id | BIGINT FK → orders.id | |
| variant_id | BIGINT FK → product_variants.id | kept for lookup; not authoritative for price/name |
| product_name | VARCHAR | snapshot |
| sku | VARCHAR | snapshot |
| unit_price | DECIMAL(12,2) | snapshot |
| quantity | INT | |

### `payments`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| order_id | BIGINT FK → orders.id, UNIQUE | one payment record per order, including COD |
| gateway | ENUM | `COD, VNPAY, STRIPE` |
| status | ENUM | `PENDING, SUCCESS, FAILED` |
| gateway_transaction_ref | VARCHAR, nullable | gateway's own transaction id; null for COD |
| paid_at | TIMESTAMP, nullable | |
| created_at / updated_at | TIMESTAMP | |

### `product_variants` (modify existing table)
Add `reserved_quantity INT NOT NULL DEFAULT 0`.
`available = stock_quantity - reserved_quantity`. Checkout validates against
`available`, never against raw `stock_quantity`.

### Money handling

- Every monetary field is `java.math.BigDecimal` end to end (entity, DTO,
  service arithmetic) — never `double`/`float`. `Order.totalAmount` is
  computed as `order_items.unitPrice.multiply(BigDecimal.valueOf(quantity))`
  summed with `BigDecimal.add`, no floating-point intermediate.
- `total_amount` is `DECIMAL(14,2)`, two digits wider than
  `product_variants.price`'s `DECIMAL(12,2)`, because a total sums
  price × quantity across every item in the order and can exceed any single
  item's range even though each `order_items.unit_price` itself stays
  `DECIMAL(12,2)` (it's a straight copy of one variant's price).
- **Stripe + VND gotcha:** Stripe treats VND as a **zero-decimal currency** —
  the `amount` sent to the Payment Intent API is the whole VND amount as an
  integer (e.g. `150000` for 150,000₫), *not* multiplied by 100 like USD
  cents. `StripeGateway.initiate()` must pass
  `order.getTotalAmount().longValueExact()` directly, never `*100`. Get this
  wrong and every Stripe charge is 100x the real amount.

### Relationships

```
users
 ├──1:1── carts ──1:N── cart_items ──N:1── product_variants
 ├──1:N── addresses                 (no FK from orders — value is copied at checkout)
 └──1:N── orders
             ├──1:N── order_items ──N:1── product_variants
             └──1:1── payments

product_variants ──N:1── products ──N:1── categories   (existing, unchanged)
```

`orders` does not FK into `addresses`: the recipient fields are copied at
checkout so editing or deleting an address never changes a past order.
`order_items` keeps `variant_id` for lookup but snapshots name/sku/price so a
later price change or product deletion never changes a past order's total.

## 5. API Surface

**Address** (`/api/addresses`, auth required):
- `GET /` — list current user's addresses
- `POST /` — create
- `PUT /{id}` — update
- `DELETE /{id}`
- `PUT /{id}/default` — set as default (unsets previous default)

**Cart** (`/api/cart`, auth required):
- `GET /` — current cart with items (variant name/price resolved live, subtotal computed)
- `POST /items` — `{variantId, quantity}`, upserts (adds to existing quantity if variant already in cart)
- `PUT /items/{itemId}` — `{quantity}`
- `DELETE /items/{itemId}`

**Order / Checkout** (`/api/orders`, auth required):
- `POST /checkout` — `{cartItemIds: [long], addressId, paymentMethod}` → creates the order from the selected cart items only (unselected items stay in the cart), removes checked-out items from the cart, returns `OrderResponse` plus payment init data (`redirectUrl` for VNPay, `clientSecret` for Stripe, nothing extra for COD)
- `GET /` — current user's orders, paginated
- `GET /{id}` — order detail (owner only)
- `POST /{id}/cancel` — owner only, allowed while status is `PENDING_PAYMENT` or `CONFIRMED`

Admin (`/api/admin/orders`, `ADMIN` only):
- `GET /` — all orders, paginated, filterable by status
- `PUT /{id}/status` — advance `CONFIRMED → SHIPPED → DELIVERED` (no skipping stages)

**Payment webhooks** (`/api/payments`, public — authenticated by gateway signature, not JWT):
- `POST /webhooks/vnpay` — VNPay IPN callback
- `POST /webhooks/stripe` — Stripe webhook event
- `GET /orders/{id}/payment` — payment status (auth, owner only — for client-side polling)

## 6. Checkout & Payment Flow

**Checkout, COD:**
1. Validate every `cartItemId` belongs to the caller's cart and `addressId` belongs to the caller (404 otherwise).
2. Per item, lock the variant row (`@Lock(PESSIMISTIC_WRITE)`), check `available >= quantity`; else throw `InsufficientStockException` (409) and roll back the whole checkout.
3. Decrement `stock_quantity` directly (no reservation step — COD has no external gateway to wait on). Create `Order(status=CONFIRMED, paymentMethod=COD, expiresAt=null)`, snapshot `order_items`, create `Payment(gateway=COD, status=SUCCESS, paidAt=now)`.
4. Remove the checked-out items from the cart.
5. Publish `OrderPlacedEvent`.
6. Commit; return `OrderResponse`.

**Checkout, VNPay/Stripe:**
1–2. Same validation and lock as COD.
3. Increment `reserved_quantity` by the ordered quantity (`stock_quantity` untouched). Create `Order(status=PENDING_PAYMENT, expiresAt=now + order.payment-expiry-minutes)`, snapshot `order_items`, create `Payment(gateway=X, status=PENDING)`.
4. Remove the checked-out items from the cart.
5. Call `PaymentGateway.initiate(order)` → gateway-specific init payload.
6. Commit; return `OrderResponse` + payment init payload.

**Webhook confirmation (VNPay/Stripe):**
1. Verify the request's signature using that gateway's secret; reject with 400 on failure (log a warning, do not touch any order).
2. Resolve the target `Order` from the payload's reference.
3. If `Order.status != PENDING_PAYMENT`, this webhook was already processed (or the order already expired) — return 200 no-op. This is what makes the endpoint safe to call twice.
4. On success: lock the variant rows again, commit the reservation (`stock_quantity -= qty; reserved_quantity -= qty` for each item), set `Order.status = CONFIRMED`, `Payment.status = SUCCESS`, `paidAt = now`. Publish `PaymentConfirmedEvent`.
5. On failure reported by the gateway: release the reservation (`reserved_quantity -= qty`, `stock_quantity` untouched), set `Order.status = CANCELLED`, `Payment.status = FAILED`. Publish `OrderCancelledEvent`.

**Expiry job** (`@Scheduled(fixedRate = 60_000)`, needs `@EnableScheduling`):
1. Find all `Order` where `status = PENDING_PAYMENT` and `expiresAt < now`.
2. Per order: lock its variants, release `reserved_quantity`, set `status = CANCELLED`, `Payment.status = FAILED`.
3. Publish `OrderCancelledEvent` per order.

**User-initiated cancel** (`POST /{id}/cancel`):
- Allowed only while `status` is `PENDING_PAYMENT` or `CONFIRMED` (not yet `SHIPPED`).
- If `PENDING_PAYMENT`: release `reserved_quantity`.
- If `CONFIRMED`: restore `stock_quantity` (covers both COD and a paid online order — stock was already committed for both).
- Set `status = CANCELLED`; publish `OrderCancelledEvent`.

## 7. Payment Gateway Abstraction

```java
public interface PaymentGateway {
    PaymentMethod getMethod();
    PaymentInitResult initiate(Order order);
    PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody);
}
```

- `PaymentInitResult` — sealed-ish DTO carrying whichever fields the method needs (`redirectUrl` for VNPay, `clientSecret` for Stripe, empty for COD).
- `PaymentWebhookResult` — `{orderId, success: boolean, gatewayTransactionRef}`, or a "invalid signature" sentinel the controller maps to 400.
- Three implementations: `CodGateway` (trivial — `initiate` never called from the online-payment branch, kept for symmetry/tests), `VnPayGateway`, `StripeGateway`.
- `PaymentService` receives `List<PaymentGateway>` via constructor injection and builds a `Map<PaymentMethod, PaymentGateway>` — dispatch by `order.getPaymentMethod()`, no `switch` on gateway logic outside this map lookup.
- `VnPayGateway`: builds the VNPay pay URL and HMAC-SHA512 secure hash manually (no official Java SDK) from `vnpay.merchant-code`, `vnpay.hash-secret`, `vnpay.pay-url`, `vnpay.return-url` properties (placeholders — user fills real sandbox values later).
- `StripeGateway`: uses `com.stripe:stripe-java` — creates a `PaymentIntent`, returns its `clientSecret`; webhook verified via `Webhook.constructEvent` with `stripe.secret-key` / `stripe.webhook-secret` properties (placeholders). Amount sent to Stripe is `order.getTotalAmount().longValueExact()` — see §4 Money handling: VND is zero-decimal in Stripe, do not multiply by 100.

## 8. Domain Events

Three event records published via `ApplicationEventPublisher`:
`OrderPlacedEvent(orderId)`, `PaymentConfirmedEvent(orderId)`,
`OrderCancelledEvent(orderId, reason)`.

A single `@Component` listener (`OrderEventListener`, `@EventListener` methods)
logs each event for now — the seam where a future notification service or
Kafka producer attaches without changing `OrderService`/`PaymentService`.

## 9. Security

- All new endpoints under `/api/addresses/**`, `/api/cart/**`, `/api/orders/**` require a valid JWT (existing `SecurityConfig` default — only explicitly listed paths are public).
- `/api/payments/webhooks/**` must be added to the public matcher list (gateway calls it directly, no JWT) — authenticity comes from signature verification inside the controller/service, not Spring Security.
- `/api/admin/orders/**` gated by `@PreAuthorize("hasRole('ADMIN')")`.
- Ownership checks (address/cart/order belongs to the caller) done in the service layer using the authenticated principal — never trust a client-supplied user id.

## 10. Error Handling

New `InsufficientStockException` (409) added to the existing
`GlobalExceptionHandler`. Reused from the existing spec:
`ResourceNotFoundException` (404 — address/order/cart-item not found or not
owned by caller), `MethodArgumentNotValidException` (400 — invalid checkout
body). Webhook signature failure → controller returns 400 directly (not
routed through `GlobalExceptionHandler`, since the gateway — not a browser
client — reads this response).

## 11. Configuration

New `application.properties` entries (dev values are placeholders):
```properties
order.payment-expiry-minutes=15
vnpay.merchant-code=CHANGE_ME
vnpay.hash-secret=CHANGE_ME
vnpay.pay-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
vnpay.return-url=http://localhost:8080/api/payments/webhooks/vnpay
stripe.secret-key=sk_test_CHANGE_ME
stripe.webhook-secret=whsec_CHANGE_ME
```
Test `application.properties` (H2) gets its own dummy values so
`./mvnw test` never depends on real network access to either gateway.

## 12. Package Structure

Follows the existing layer-based convention (not feature packages):

```
com.example.backend
├── entity/       Address, Cart, CartItem, Order, OrderStatus, OrderItem,
│                 PaymentMethod, Payment, PaymentStatus
├── repository/    AddressRepository, CartRepository, CartItemRepository,
│                 OrderRepository, OrderItemRepository, PaymentRepository
├── service/       AddressService, CartService, OrderService, PaymentService
├── service/payment/  PaymentGateway, PaymentInitResult, PaymentWebhookResult,
│                     CodGateway, VnPayGateway, StripeGateway
├── scheduler/     OrderExpiryScheduler
├── event/         OrderPlacedEvent, PaymentConfirmedEvent, OrderCancelledEvent,
│                 OrderEventListener
├── controller/    AddressController, CartController, OrderController,
│                 AdminOrderController, PaymentWebhookController
├── dto/           AddressRequest/Response, CartResponse, CartItemRequest/Response,
│                 CheckoutRequest, OrderResponse, OrderItemResponse,
│                 PaymentInitResponse
└── exception/     InsufficientStockException (added to existing package)
```

## 13. Testing

- Unit (mocked repositories): `OrderService` checkout — sufficient/insufficient
  stock, COD branch vs online branch, cancel from each cancellable status.
- Unit: each `PaymentGateway` implementation — signature build/verify logic
  in isolation (no real network call).
- Integration (H2, `@SpringBootTest` + `MockMvc`): full checkout flow per
  payment method; webhook called twice → processed once (idempotency);
  expiry job invoked directly → order cancelled and stock released.
- **Required concurrency test**: two threads checking out the last unit of
  the same variant concurrently — exactly one succeeds, the other gets 409.
  This is the test that actually exercises the pessimistic lock; without it
  the lock's correctness is unverified.
- Driven test-first per task during implementation (TDD), matching the
  existing module's approach.
