# CMS Dashboard and Admin API Design

## Goal

Add the backend APIs needed by an authenticated CMS dashboard to manage the existing ecommerce catalog and orders, and to view useful, trustworthy sales statistics. Every CMS endpoint uses the `/api/cms/**` namespace.

## Current project behavior

- This is a Spring Boot modular monolith using PostgreSQL, JPA, Flyway, Spring Security, and roles `ADMIN`/`CUSTOMER`.
- Product CRUD, variants and image creation already exist at `/api/products`; writes require `ADMIN`. Public reads deliberately show only `ACTIVE` products.
- Category CRUD already exists at `/api/categories`; writes require `ADMIN`.
- Admin order listing and legal status advancement already exist at `/api/admin/orders`; both require `ADMIN`.
- `Order.totalAmount` is the checkout-time monetary snapshot. Order status is `PENDING_PAYMENT`, `CONFIRMED`, `SHIPPED`, `DELIVERED`, or `CANCELLED`. Payment status is `PENDING`, `SUCCESS`, or `FAILED`.
- Successful payments set `Payment.paidAt`; COD is represented as `SUCCESS` with `paidAt` at checkout, even before the parcel is delivered. Failed/expired payments do not count as revenue.
- Order items hold snapshot `productName`, `sku`, `unitPrice`, `quantity`, so later catalog price/name changes must not rewrite historical sales.
- Product price and stock are per variant. Available stock is `stockQuantity - reservedQuantity`.
- Existing admin enforcement uses `@PreAuthorize("hasRole('ADMIN')")`; maintain this behavior and endpoint compatibility.

## Scope

### Phase 1: CMS dashboard read APIs

Add `GET /api/cms/dashboard/summary` accepting optional `from` and `to` ISO-8601 instants. Return revenue, successful paid order count, average order value, order counts by existing order status, low-stock variant count, and total product count. Also return the requested interval and a consistent generated-at timestamp.

Add `GET /api/cms/dashboard/revenue?from=&to=&granularity=day|week|month`. Return chronologically ordered buckets with bucket start, successful-payment revenue, and successful payment count. Apply an inclusive start and exclusive end (`paidAt >= from AND paidAt < to`). Enforce `from < to` and a bounded maximum date range to prevent accidental unbounded aggregation. The default interval is the last 30 days through now; default granularity is day. Use UTC bucket boundaries.

Revenue definition: sum `orders.total_amount` for rows whose associated payment has `status=SUCCESS` and whose `paidAt` is within the requested interval. This matches existing payment state and supports COD as currently modeled. Label the metric as collected/paid revenue in API documentation to avoid implying net revenue after refunds, which the model does not support.

Low stock is a warning count of variants whose `stockQuantity - reservedQuantity` is at or below a small documented default threshold (default 5); it does not mutate inventory. Return paged low-stock details separately through the inventory endpoint in Phase 2.

### Phase 2: complete CMS operations and operational lists

Create canonical CMS routes below `/api/cms/**`, reusing the current services and DTOs. Keep the existing product/category/admin-order routes working as backwards-compatible aliases for clients already using them; do not remove or change their authorization or behavior in this work. Document the CMS paths as canonical and existing paths as legacy aliases.

- `/api/cms/products`: GET paged admin catalog list including both `ACTIVE` and `INACTIVE`, optional status/category/search filters; GET by id including inactive; POST create; PUT update by id; DELETE by id; POST variants/images using the same semantics and response DTOs as current `/api/products` writes. Keep public `GET /api/products` behavior unchanged.
- `/api/cms/categories`: GET admin category tree and existing POST/PUT/DELETE behavior. Public `GET /api/categories` behavior remains unchanged.
- `/api/cms/inventory/low-stock`: paged low-stock variants with product, SKU, current stock, reserved stock, available stock, and threshold.
- `/api/cms/orders`: existing paged order listing and legal status advancement. Add optional date bounds, payment status/method, and buyer/email filters only where query and indexes remain straightforward. Keep `/api/admin/orders` as a backwards-compatible alias.

Order state changes remain constrained by existing `OrderService.advanceStatus` transitions. Do not add arbitrary status setting, payment status editing, delete-order, manual stock modification, user role editing, refund, or hard delete in this scope. Any future administrative cancellation/refund requires a separate inventory/payment reconciliation design.

## API and implementation design

- Use focused `CmsDashboardController`/service and `CmsCatalogController` routes, dashboard response records, and repository aggregate queries. Do not add a separate CMS service or microservice.
- Use PostgreSQL aggregate queries through the current Spring Data repositories. Keep monetary math in `BigDecimal`/PostgreSQL `numeric`; never convert amounts to floating point.
- The revenue endpoint should return sparse/zero-filled buckets consistently (choose and document one behavior; preferred: zero-fill daily buckets within requested range for charts). Ensure stable ordering and a maximum bucket count.
- Summary metrics for an interval are based on successful `paidAt`, while status distribution should be explicitly defined as orders created during the same interval to avoid mixing event times. Include both definitions in response/docs.
- Product management phase 2 should reuse `ProductService`/DTO mapping where possible; do not fork business rules. Any admin list query must not accidentally filter inactive products.
- Keep DTO responses explicit; never serialize JPA entities.
- Use database-level filters/aggregates and page inventory/product/order lists. Avoid loading all orders/products into memory.

## Access control and compatibility

- All `/api/cms/**` endpoints require `ROLE_ADMIN`; unauthenticated requests return 401 and authenticated non-admin requests return 403.
- Keycloak-issued `ADMIN` claims and existing application `ADMIN` tokens must continue to authorize through the existing role mapping.
- Public product/category routes, existing product CRUD routes, order checkout/payment webhook behavior, and the existing order state machine remain unchanged.
- Do not log raw customer addresses or full payment/gateway details in dashboard logs.

## Data and migrations

- Phase 1 should require no migration unless query-plan evidence demonstrates a missing index. Prefer indexes on the actual filter columns (`payments.status`, `payments.paid_at`, and existing FK `payments.order_id`) only if `EXPLAIN` or realistic query tests justify them. Do not modify historical migrations.
- If adding indexes later, use a new Flyway migration and preserve existing rows.
- Phase 2 low-stock filtering may require a query over `product_variants`; do not duplicate available quantity as a stored field.

## Failure handling and validation

- Reject invalid date order, unsupported granularity, intervals above the configured hard maximum, malformed enum/status filters, page sizes above the project limit, and nonsensical negative stock thresholds with 400 responses.
- Keep response values correct when there are no matching orders (zero monetary/count values and empty or zero-filled buckets, per documented contract).
- Account for UTC, daylight-saving transitions in caller zones, exact `from`/`to` boundaries, same-timestamp payments, and payments whose order was later cancelled. Since the existing model has no refund state, successful captured payment remains included even if the order later reaches `CANCELLED`; document this limitation.

## Reports and Excel exports

All report endpoints use `/api/cms/reports/**`, require `ROLE_ADMIN`, accept bounded date filters, and return DTOs rather than entities.

- `GET /api/cms/reports/sales`: paid revenue and paid order counts by requested day/week/month, payment method, and order status. Revenue follows the successful-payment/`paidAt` definition above.
- `GET /api/cms/reports/products`: units sold and gross sales by product using `OrderItem.productName`, `sku`, `unitPrice`, and `quantity` snapshots, ordered by units/revenue. Never join mutable current price/name to calculate historic sales.
- `GET /api/cms/reports/inventory`: stock/reserved/available snapshot by variant, optionally filtered by category and low-stock threshold.
- `GET /api/cms/reports/{reportType}/export.xlsx`: exports the same filters and columns as its JSON report. Use an actual `.xlsx` workbook and numeric date/money/quantity cells. Generate rows from database paging/streaming, set a maximum rows/date range, and return 400 for unsupported report types or an export exceeding the supported cap. Do not buffer an unbounded report in heap or place export data in logs.
- Prefer Apache POI SXSSF for bounded-memory `.xlsx` output if a library is introduced; close/dispose workbooks and delete temporary files on success and failure. The report/export endpoints remain synchronous only while a documented row cap keeps response time and temporary disk use bounded. If real use exceeds that cap, switch to an async export job with expiring protected downloads as a separate iteration.
- Reports contain customer PII only where strictly necessary (e.g. order report); default reports and product exports omit email, phone, and address. Validate authorization before starting generation.

## Notifications and customer email

Deliver only transactional order notifications in this scope; no marketing/bulk campaign feature. Notification triggers are: order placed, online payment success/failure, order shipped/delivered, and cancellation/expiry. Prevent duplicate event delivery using stable event keys such as `order:{id}:PAYMENT_CONFIRMED` and a database uniqueness constraint.

- Customer-facing in-app notifications are queried only for the authenticated customer's own account at `/api/notifications`; support paged unread/all listing, unread count, mark one read, and mark all read. A customer can never read or update another customer's notification.
- CMS staff notifications are under `/api/cms/notifications`, require `ROLE_ADMIN`, and cover new orders and low-stock threshold crossings. Provide paged list, unread count, and mark-read endpoints. Avoid generating one alert per repeated poll; deduplicate stock alerts by variant and threshold window/state.
- Transactional email is sent only to the email associated with the affected order/user, with no arbitrary recipient endpoint. Send order placed/confirmation, payment result, shipping/delivery, and cancellation emails using small versioned templates. Do not include full address, payment secrets, or internal gateway payloads.
- Add a transactional outbox table in a new Flyway migration. Record the notification event/outbox row in the same database transaction as the order/payment state change so a process crash cannot silently lose the event. A scheduled worker claims pending email rows, sends via Spring `JavaMailSender` configured exclusively by environment-backed SMTP properties, marks sent, and retries with capped exponential backoff; after a configured attempt limit, mark failed and expose the failure to admins. Keep SMTP disabled unless host/credentials are configured.
- In-app notification rows and outbox event keys must have unique constraints to make application retries idempotent. SMTP delivery is at-least-once: a process may crash after provider acceptance but before marking sent, so a duplicate is possible unless the chosen email provider offers idempotency. Do not claim exactly-once delivery.
- Use transaction-bound event processing only after the order/payment state is valid. Add events for existing status transitions (shipped/delivered); preserve checkout, payment, cancellation, and stock state machines. Listener failures must not call email synchronously inside checkout/webhook transactions.
- Email is transactional and does not require marketing consent, but respect account email-verification policy where applicable and do not reveal account existence from public APIs. Add notification preferences/unsubscribe for non-transactional categories only if marketing email is introduced later.

## Notification and export schema

- `notifications`: recipient user, event key, type, title, message, optional order reference, read timestamp, created timestamp; unique `(recipient_user_id, event_key)` and index by recipient/read/created. This supports one customer notification and one per CMS admin for the same event without collisions. Apply user-delete FK behavior carefully; preserve referenced orders and never cascade-delete another user's record through an admin action.
- `notification_outbox`: unique event key per delivery, event type, recipient email/user, minimal template data, status (`PENDING`, `SENDING`, `SENT`, `FAILED`), attempts, next attempt, claim lease expiry, sent/created/updated timestamps, and a short sanitized last error. Claim work in small batches using PostgreSQL row locking (`FOR UPDATE SKIP LOCKED`); reclaim expired `SENDING` leases so a process crash cannot strand a message forever.
- CMS low-stock alert state is unique per variant and threshold. Create an alert only when available stock crosses from above to at/below threshold, and re-arm it when stock rises above threshold. This prevents duplicate alerts from repeated order events while allowing a later genuine crossing to alert again.
- No export artifact table is needed for bounded synchronous exports. Do not add a job/file store or object storage until the synchronous row cap is demonstrably insufficient.

## Verification

- Repository/service tests cover successful vs pending/failed payment, interval boundaries, COD paid-at behavior, empty intervals, and aggregate values with fractional currency.
- Controller integration tests cover admin authorization, date/granularity validation, stable ordering, pagination, and JSON response contracts.
- Regression tests verify existing customer/public access, product CRUD authorization, admin order status transition restrictions, and checkout/webhook outcomes remain unchanged.
- Test with PostgreSQL semantics (the existing integration-test database configuration) for date bucketing and aggregate query correctness; H2-only behavior is insufficient for database-specific bucketing.
- Run `./mvnw test`, `docker compose config -q`, and `git diff --check` before declaring completion.

## Not included

Frontend/admin UI, charts rendering, audit-log UI, refunds, shipping labels, supplier/purchase-order workflows, user/role administration, promotion management, bulk marketing email, and real-time analytics are separate follow-up work.
