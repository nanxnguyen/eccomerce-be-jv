# Cart / Order / Payment / Inventory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Address, Cart, Order/Checkout, Payment (COD + VNPay + Stripe), stock reservation, an order-expiry scheduler, and in-process domain events on top of the existing Auth/User + Product/Catalog modules.

**Architecture:** Same layered Spring Boot style as the existing code — `entity` → `repository` → `service` → `controller`, DTOs as records. Checkout locks `ProductVariant` rows (`SELECT ... FOR UPDATE`) to reserve/commit stock atomically inside one `@Transactional` method. Payment gateways sit behind a `PaymentGateway` strategy interface dispatched by `PaymentService`. Domain events go through Spring's in-process `ApplicationEventPublisher` (no message broker — see spec §3 non-goals).

**Tech Stack:** Spring Boot 4.1.1, Java 17, Spring Data JPA, Spring Security 7, PostgreSQL (dev) / H2 (test), JJWT 0.12.x, Lombok, Bean Validation, `com.stripe:stripe-java` (new).

**Spec:** [docs/superpowers/specs/2026-09-13-cart-order-payment-design.md](../specs/2026-09-13-cart-order-payment-design.md)

## Global Constraints

- Java 17, Spring Boot 4.1.1 (already pinned in `pom.xml`).
- This codebase's test stack uses **Spring Boot 4.1.1's renamed test-autoconfigure packages** and **Jackson 3**, not the classic Spring Boot 3.x paths — copy imports exactly as shown in this plan's code blocks:
  - `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` (not `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest`)
  - `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`
  - `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
  - `tools.jackson.databind.ObjectMapper` (Jackson 3 package, not `com.fasterxml.jackson.databind`)
  - `org.springframework.boot.test.context.SpringBootTest` is unchanged.
- All new classes live under `com.example.backend` (root package), organized by layer (`entity/`, `repository/`, `service/`, `controller/`, `dto/`, `exception/`), plus two new layer packages: `service/payment/` (gateway strategy classes) and `scheduler/`, and `event/` (domain events + listener).
- Every monetary field is `java.math.BigDecimal`, never `double`/`float`. `orders.total_amount` is `DECIMAL(14,2)`; per-item prices stay `DECIMAL(12,2)` (matches `product_variants.price`). See spec §4 Money handling.
- **Stripe + VND:** VND is a zero-decimal currency in Stripe — send `order.getTotalAmount().longValueExact()` as-is, never multiply by 100. **VNPay is the opposite convention** — VNPay always multiplies the amount by 100 as its own internal unit, regardless of currency. Do not confuse the two.
- Every write path that touches `product_variants.stock_quantity` or `reserved_quantity` must first lock the row via `ProductVariantRepository.findByIdForUpdate`, never mutate a lazily-loaded/cached reference.
- Ownership checks (address/cart/order belongs to the caller) happen in the service layer via repository methods scoped by the authenticated user's id (e.g. `findByIdAndUserId`) — never trust a client-supplied user id, and never look up by id alone for an owner-scoped resource.
- Current-user resolution follows the existing `UserController` pattern: controllers pass `@AuthenticationPrincipal UserDetails userDetails` → `userDetails.getUsername()` (the email) into the service; the service resolves the full `User` via `UserRepository.findByEmail`.
- Every error response stays JSON via the existing single `GlobalExceptionHandler` — new exception types get new handler methods there, not ad-hoc try/catch in controllers.
- Dev DB stays PostgreSQL; tests run against H2 (`src/test/resources/application.properties`) so `./mvnw test` never touches the real dev database.
- Driven test-first per task (TDD), matching the existing modules' approach.
- **Every new `@SpringBootTest` IT class must clean up in FK order in `@BeforeEach`**, not just `userRepository.deleteAll()`: `orderRepository.deleteAll()` (cascades items+payment) → `cartItemRepository.deleteAll()` → `cartRepository.deleteAll()` → `addressRepository.deleteAll()` → `productRepository.deleteAll()` (cascades variants+images) → `userRepository.deleteAll()` → `categoryRepository.deleteAll()`. All `@SpringBootTest` classes in this project share one cached context and one H2 instance (`DB_CLOSE_DELAY=-1`) — skipping a table here causes `DataIntegrityViolationException` on the *second* `@Test` method in the class (or the next IT class that runs), not the first, which is why this is easy to miss task-by-task. `AddressControllerIT` (Task 5) needs this too even though it never touches orders/carts — it still creates addresses that FK into the users `@BeforeEach` deletes.
- **From Task 5 onward, run the full suite (`./mvnw test`) before committing, not just the task's own test class.** A class-scoped `-Dtest=...` run cannot catch cross-test-class state leakage (shared H2 instance) — the FK-order rule above is exactly the kind of bug that passes in isolation and fails only in the full run.

---

### Task 1: Dependencies and configuration

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`
- Modify: `src/main/java/com/example/backend/BackendApplication.java`

**Interfaces:**
- Produces: `order.payment-expiry-minutes`, `vnpay.merchant-code`, `vnpay.hash-secret`, `vnpay.pay-url`, `vnpay.return-url`, `stripe.secret-key`, `stripe.webhook-secret` properties consumed by Tasks 12–15. `@EnableScheduling` consumed by Task 19's `@Scheduled` job.

- [ ] **Step 1: Add the Stripe SDK dependency to `pom.xml`**

Add inside the existing `<dependencies>` block, after the `jjwt-jackson` dependency:

```xml
		<dependency>
			<groupId>com.stripe</groupId>
			<artifactId>stripe-java</artifactId>
			<version>28.5.0</version>
		</dependency>
```

- [ ] **Step 2: Add checkout/payment config to the dev `application.properties`**

Append to `src/main/resources/application.properties`:

```properties
order.payment-expiry-minutes=15

# VNPay sandbox — replace with your own test merchant credentials from
# https://sandbox.vnpayment.vn (Merchant Admin > Account) before testing real payments.
vnpay.merchant-code=CHANGE_ME
vnpay.hash-secret=CHANGE_ME
vnpay.pay-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
vnpay.return-url=http://localhost:8080/api/payments/webhooks/vnpay

# Stripe test keys — replace with your own from https://dashboard.stripe.com/test/apikeys
# (webhook secret comes from `stripe listen --forward-to localhost:8080/api/payments/webhooks/stripe`
# when testing locally, or from the Dashboard > Developers > Webhooks once deployed).
stripe.secret-key=sk_test_CHANGE_ME
stripe.webhook-secret=whsec_CHANGE_ME
```

- [ ] **Step 3: Raise the H2 lock timeout so the concurrency test gets a real 409, not a timeout error**

H2's default `LOCK_TIMEOUT` is short (a couple seconds). Task 16's required concurrency test has
two real threads racing `SELECT ... FOR UPDATE` on the same row — if the second thread's wait for
the first's lock outlasts the default timeout, it gets `JdbcSQLTimeoutException` → 500, not the 409
the test expects (a false failure that looks like a broken lock but isn't). Change the existing
datasource URL line in `src/test/resources/application.properties` from:

```properties
spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
```

to:

```properties
spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000
```

- [ ] **Step 4: Add dummy config to the test `application.properties`**

Append to `src/test/resources/application.properties` (dummy values — tests never call the real network; VNPay tests use `new VnPayGateway(...)` directly with their own literals, and Stripe tests mock the SDK's static calls, so these only need to exist so Spring context loads):

```properties
order.payment-expiry-minutes=15
vnpay.merchant-code=TEST_MERCHANT
vnpay.hash-secret=TEST_HASH_SECRET_AT_LEAST_THIS_LONG
vnpay.pay-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
vnpay.return-url=http://localhost:8080/api/payments/webhooks/vnpay
stripe.secret-key=sk_test_dummy
stripe.webhook-secret=whsec_dummy
```

- [ ] **Step 5: Enable Spring's scheduler**

Modify `src/main/java/com/example/backend/BackendApplication.java`:

```java
package com.example.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
```

- [ ] **Step 6: Verify the build still compiles**

Run: `./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/resources/application.properties \
        src/main/java/com/example/backend/BackendApplication.java
git commit -m "build: add Stripe SDK, checkout/payment config, enable scheduling, raise H2 lock timeout"
```

---

### Task 2: New exceptions — InsufficientStockException, InvalidOrderStateException

**Files:**
- Create: `src/main/java/com/example/backend/exception/InsufficientStockException.java`
- Create: `src/main/java/com/example/backend/exception/InvalidOrderStateException.java`
- Modify: `src/main/java/com/example/backend/exception/GlobalExceptionHandler.java`
- Modify: `src/test/java/com/example/backend/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `InsufficientStockException(String)`, `InvalidOrderStateException(String)` (both `RuntimeException`, both mapped to 409 Conflict). Consumed by `OrderService` (Task 17) and `PaymentGateway`/`OrderService` stock-checking code (Task 3, 17).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/example/backend/exception/GlobalExceptionHandlerTest.java` (inside the existing `GlobalExceptionHandlerTest` class, alongside the other `@Test` methods):

```java
    @Test
    void mapsInsufficientStockTo409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/orders/checkout");

        ResponseEntity<ErrorResponse> response =
                handler.handleInsufficientStock(new InsufficientStockException("Insufficient stock for SKU TSHIRT-M"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo("Insufficient stock for SKU TSHIRT-M");
    }

    @Test
    void mapsInvalidOrderStateTo409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/orders/1/cancel");

        ResponseEntity<ErrorResponse> response =
                handler.handleInvalidOrderState(new InvalidOrderStateException("Order cannot be cancelled in status SHIPPED"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL to compile — `InsufficientStockException`, `InvalidOrderStateException`, and the two handler methods don't exist yet.

- [ ] **Step 3: Create the exception classes**

Create `src/main/java/com/example/backend/exception/InsufficientStockException.java`:

```java
package com.example.backend.exception;

// Ném khi checkout không đủ hàng (available = stock_quantity - reserved_quantity < số lượng đặt).
// GlobalExceptionHandler bắt lỗi này và trả về 409 Conflict.
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
```

Create `src/main/java/com/example/backend/exception/InvalidOrderStateException.java`:

```java
package com.example.backend.exception;

// Ném khi thao tác trên Order không hợp lệ với status hiện tại (huỷ đơn đã SHIPPED, nhảy cóc
// trạng thái CONFIRMED -> DELIVERED bỏ qua SHIPPED, v.v.). GlobalExceptionHandler trả về 409 Conflict.
public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Add handlers to `GlobalExceptionHandler`**

Add imports and these two methods to `src/main/java/com/example/backend/exception/GlobalExceptionHandler.java` (place them next to `handleDuplicate`, before the generic `Exception.class` handler):

```java
    // 409 Conflict: không đủ hàng lúc checkout.
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 409 Conflict: thao tác Order không hợp lệ với status hiện tại.
    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrderState(InvalidOrderStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS (all tests, including the two new ones).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/exception/InsufficientStockException.java \
        src/main/java/com/example/backend/exception/InvalidOrderStateException.java \
        src/main/java/com/example/backend/exception/GlobalExceptionHandler.java \
        src/test/java/com/example/backend/exception/GlobalExceptionHandlerTest.java
git commit -m "feat: add InsufficientStockException and InvalidOrderStateException (409)"
```

---

### Task 3: Stock reservation support on ProductVariant

**Files:**
- Modify: `src/main/java/com/example/backend/entity/ProductVariant.java`
- Modify: `src/main/java/com/example/backend/repository/ProductVariantRepository.java`
- Test: `src/test/java/com/example/backend/repository/ProductVariantRepositoryTest.java`

**Interfaces:**
- Produces: `ProductVariant.getReservedQuantity()/setReservedQuantity(Integer)`, `ProductVariant.getAvailableQuantity(): int` (= `stockQuantity - reservedQuantity`). `ProductVariantRepository.findByIdForUpdate(Long): Optional<ProductVariant>` — locks the row (`PESSIMISTIC_WRITE`) for the duration of the caller's transaction. Consumed by `OrderService` (Task 17, 18, 19) for every stock mutation.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/repository/ProductVariantRepositoryTest.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductVariantRepositoryTest {

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void defaultsReservedQuantityToZeroAndComputesAvailable() {
        Long variantId = saveProductWithVariant(50);

        ProductVariant variant = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(variant.getReservedQuantity()).isZero();
        assertThat(variant.getAvailableQuantity()).isEqualTo(50);

        variant.setReservedQuantity(20);
        assertThat(variant.getAvailableQuantity()).isEqualTo(30);
    }

    @Test
    @Transactional
    void findByIdForUpdateLocksAndReturnsTheVariant() {
        Long variantId = saveProductWithVariant(50);

        ProductVariant locked = productVariantRepository.findByIdForUpdate(variantId).orElseThrow();
        assertThat(locked.getId()).isEqualTo(variantId);

        assertThat(productVariantRepository.findByIdForUpdate(999_999L)).isEmpty();
    }

    private Long saveProductWithVariant(int stockQuantity) {
        Category category = categoryRepository.save(
                Category.builder().name("Fashion").slug("fashion").description("Clothes").build());

        Product product = Product.builder()
                .category(category)
                .name("T-Shirt")
                .slug("t-shirt")
                .description("Basic tee")
                .status(ProductStatus.ACTIVE)
                .build();

        product.addVariant(ProductVariant.builder()
                .sku("TSHIRT-BLK-M")
                .size("M")
                .color("Black")
                .price(new BigDecimal("19.99"))
                .stockQuantity(stockQuantity)
                .build());

        productRepository.saveAndFlush(product);
        return product.getVariants().get(0).getId();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ProductVariantRepositoryTest`
Expected: FAIL to compile — `getReservedQuantity`, `getAvailableQuantity`, `findByIdForUpdate` don't exist yet.

- [ ] **Step 3: Add `reservedQuantity` and `getAvailableQuantity()` to `ProductVariant`**

Modify `src/main/java/com/example/backend/entity/ProductVariant.java` — add this field right after `stockQuantity`, and this method at the end of the class (before the closing `}`):

```java
    // Số lượng đang bị "giữ chỗ" cho các Order status=PENDING_PAYMENT (VNPay/Stripe) - CHƯA trừ
    // khỏi stockQuantity thật. Chỉ trừ thật (stockQuantity -= qty) khi webhook xác nhận thanh toán
    // thành công (OrderService.confirmPayment, Task 18) hoặc ngay lúc tạo Order với COD (không có
    // bước chờ gateway). Xem spec §6 Checkout & Payment Flow.
    @Column(nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;
```

```java
    public int getAvailableQuantity() {
        return stockQuantity - reservedQuantity;
    }
```

- [ ] **Step 4: Add `findByIdForUpdate` to `ProductVariantRepository`**

Replace the full contents of `src/main/java/com/example/backend/repository/ProductVariantRepository.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    boolean existsBySku(String sku);

    // PESSIMISTIC_WRITE: dịch ra SELECT ... FOR UPDATE, chặn mọi transaction khác đang cố lock
    // CÙNG dòng này cho tới khi transaction hiện tại commit/rollback. Bắt buộc dùng method này
    // (không dùng findById thường) ở BẤT KỲ chỗ nào đọc rồi ghi lại stockQuantity/reservedQuantity -
    // nếu không, 2 request checkout đồng thời cùng đọc availableQuantity=1 rồi cùng trừ, bán vượt
    // tồn kho (race condition kinh điển). Dùng @Query thủ công vì @Lock không áp được lên
    // findById() kế thừa từ JpaRepository, phải khai báo lại bằng 1 query rõ ràng.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id = :id")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") Long id);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=ProductVariantRepositoryTest`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/entity/ProductVariant.java \
        src/main/java/com/example/backend/repository/ProductVariantRepository.java \
        src/test/java/com/example/backend/repository/ProductVariantRepositoryTest.java
git commit -m "feat: add stock reservation (reservedQuantity) and pessimistic-lock lookup to ProductVariant"
```

---

### Task 4: Address entity and repository

**Files:**
- Create: `src/main/java/com/example/backend/entity/Address.java`
- Create: `src/main/java/com/example/backend/repository/AddressRepository.java`
- Test: `src/test/java/com/example/backend/repository/AddressRepositoryTest.java`

**Interfaces:**
- Consumes: `User` (existing).
- Produces: `Address` (fields: `Long id`, `User user`, `String recipientName`, `String phone`, `String addressLine`, `String ward`, `String district`, `String province`, `boolean isDefault`, `Instant createdAt`, `Instant updatedAt`). `AddressRepository.findByUserId(Long): List<Address>`, `.findByIdAndUserId(Long, Long): Optional<Address>`, `.findDefaultByUserId(Long): Optional<Address>`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/repository/AddressRepositoryTest.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.Address;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AddressRepositoryTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesFindsByUserAndTracksDefault() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());
        User other = userRepository.save(User.builder()
                .name("Other").email("other@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());

        Address home = addressRepository.save(Address.builder()
                .user(user).recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .ward("Ward 1").district("District 1").province("HCMC").isDefault(true).build());
        addressRepository.save(Address.builder()
                .user(user).recipientName("Nhut").phone("0900000000").addressLine("456 Side St")
                .isDefault(false).build());
        addressRepository.save(Address.builder()
                .user(other).recipientName("Other").phone("0911111111").addressLine("789 Other St")
                .isDefault(true).build());

        assertThat(addressRepository.findByUserId(user.getId())).hasSize(2);
        assertThat(addressRepository.findByIdAndUserId(home.getId(), user.getId())).isPresent();
        assertThat(addressRepository.findByIdAndUserId(home.getId(), other.getId())).isEmpty();
        assertThat(addressRepository.findDefaultByUserId(user.getId())).contains(home);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AddressRepositoryTest`
Expected: FAIL to compile — `Address`, `AddressRepository` don't exist yet.

- [ ] **Step 3: Create `Address`**

Create `src/main/java/com/example/backend/entity/Address.java`:

```java
package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String addressLine;

    private String ward;
    private String district;
    private String province;

    // KHÔNG tự chặn "chỉ 1 default/user" bằng unique constraint ở DB: mọi dòng còn lại đều có
    // isDefault=false, trùng giá trị nhau -> DB không thể coi false là "phải duy nhất". Ràng buộc
    // này nằm ở tầng service (AddressService.setDefault, Task 5).
    @Column(nullable = false)
    private boolean isDefault;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

- [ ] **Step 4: Create `AddressRepository`**

Create `src/main/java/com/example/backend/repository/AddressRepository.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {
    List<Address> findByUserId(Long userId);

    // Owner-scoped lookup: dùng cho MỌI thao tác update/delete/setDefault - nếu addressId có thật
    // nhưng thuộc user khác, trả Optional.empty() (giống không tồn tại), KHÔNG được ném 403 (sẽ lộ
    // thông tin "address này có tồn tại, chỉ là không phải của bạn" cho kẻ dò id).
    Optional<Address> findByIdAndUserId(Long id, Long userId);

    // @Query rõ ràng thay vì derived method findByUserIdAndIsDefaultTrue(): Spring Data phải suy
    // "IsDefault" ra field isDefault lúc parse tên method ở STARTUP - nếu suy sai (field boolean đặt
    // tên kiểu "isXxx" đôi khi bị resolver hiểu nhầm), lỗi là PropertyReferenceException làm SẬP
    // context của MỌI @SpringBootTest trong project, không chỉ test của riêng Address. @Query loại
    // bỏ rủi ro đó hoàn toàn.
    @Query("select a from Address a where a.user.id = :userId and a.isDefault = true")
    Optional<Address> findDefaultByUserId(@Param("userId") Long userId);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=AddressRepositoryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/entity/Address.java \
        src/main/java/com/example/backend/repository/AddressRepository.java \
        src/test/java/com/example/backend/repository/AddressRepositoryTest.java
git commit -m "feat: add Address entity and repository"
```

---

### Task 5: Address DTOs, AddressService, AddressController

**Files:**
- Create: `src/main/java/com/example/backend/dto/AddressRequest.java`
- Create: `src/main/java/com/example/backend/dto/AddressResponse.java`
- Create: `src/main/java/com/example/backend/service/AddressService.java`
- Create: `src/main/java/com/example/backend/controller/AddressController.java`
- Test: `src/test/java/com/example/backend/controller/AddressControllerIT.java`

**Interfaces:**
- Consumes: `Address`, `AddressRepository` (Task 4), `User`/`UserRepository` (existing), `ResourceNotFoundException` (existing).
- Produces: `AddressRequest(String recipientName, String phone, String addressLine, String ward, String district, String province)`, `AddressResponse(Long id, String recipientName, String phone, String addressLine, String ward, String district, String province, boolean isDefault)` with `.from(Address)`. `AddressService.list(String email): List<AddressResponse>`, `.create(String email, AddressRequest): AddressResponse`, `.update(String email, Long id, AddressRequest): AddressResponse`, `.delete(String email, Long id): void`, `.setDefault(String email, Long id): AddressResponse`. Routes: `GET/POST /api/addresses`, `PUT/DELETE /api/addresses/{id}`, `PUT /api/addresses/{id}/default` — all auth required (default `SecurityConfig` rule already covers this, no change needed).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/controller/AddressControllerIT.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AddressControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AddressRepository addressRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String token;

    // addressRepository ĐẦU TIÊN, rồi mới userRepository: addresses.user_id là FK NOT NULL vào
    // users - xoá users trước sẽ vi phạm ràng buộc FK nếu addresses của lượt test trước còn sót lại
    // (mỗi @Test method chạy lại @BeforeEach này, không chỉ lần đầu của cả class).
    @BeforeEach
    void setUp() {
        addressRepository.deleteAll();
        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.CUSTOMER).build());
        token = jwtService.generateToken(user.getEmail(), user.getRole().name());
    }

    @Test
    void firstAddressBecomesDefaultAutomatically() throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Nhut", "0900000000", "123 Main St", "Ward 1", "District 1", "HCMC"));

        mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(true));

        String secondBody = objectMapper.writeValueAsString(
                new AddressRequest("Nhut", "0900000000", "456 Side St", null, null, null));

        mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false));

        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void settingNewDefaultUnsetsThePreviousOne() throws Exception {
        Long first = createAddress("123 Main St");
        Long second = createAddress("456 Side St");

        mockMvc.perform(put("/api/addresses/{id}/default", second)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[?(@.id == " + first + ")].isDefault").value(false))
                .andExpect(jsonPath("$[?(@.id == " + second + ")].isDefault").value(true));
    }

    @Test
    void cannotUpdateOrDeleteAnotherUsersAddress() throws Exception {
        Long addressId = createAddress("123 Main St");

        User other = userRepository.save(User.builder()
                .name("Other").email("other@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.CUSTOMER).build());
        String otherToken = jwtService.generateToken(other.getEmail(), other.getRole().name());

        String body = objectMapper.writeValueAsString(
                new AddressRequest("Hacker", "0999999999", "999 Nowhere", null, null, null));

        mockMvc.perform(put("/api/addresses/{id}", addressId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/addresses/{id}", addressId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAddress() throws Exception {
        Long addressId = createAddress("123 Main St");

        mockMvc.perform(delete("/api/addresses/{id}", addressId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/addresses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private Long createAddress(String addressLine) throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Nhut", "0900000000", addressLine, null, null, null));
        String response = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AddressControllerIT`
Expected: FAIL to compile — none of `AddressRequest`/`AddressResponse`/`AddressService`/`AddressController` exist yet.

- [ ] **Step 3: Create the DTOs**

Create `src/main/java/com/example/backend/dto/AddressRequest.java`:

```java
package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressRequest(
        @NotBlank String recipientName,
        @NotBlank String phone,
        @NotBlank String addressLine,
        String ward,
        String district,
        String province
) {}
```

Create `src/main/java/com/example/backend/dto/AddressResponse.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.Address;

public record AddressResponse(
        Long id,
        String recipientName,
        String phone,
        String addressLine,
        String ward,
        String district,
        String province,
        boolean isDefault
) {
    public static AddressResponse from(Address address) {
        return new AddressResponse(
                address.getId(), address.getRecipientName(), address.getPhone(), address.getAddressLine(),
                address.getWard(), address.getDistrict(), address.getProvince(), address.isDefault()
        );
    }
}
```

- [ ] **Step 4: Implement `AddressService`**

Create `src/main/java/com/example/backend/service/AddressService.java`:

```java
package com.example.backend.service;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.AddressResponse;
import com.example.backend.entity.Address;
import com.example.backend.entity.User;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    public AddressService(AddressRepository addressRepository, UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(String email) {
        User user = resolveUser(email);
        return addressRepository.findByUserId(user.getId()).stream().map(AddressResponse::from).toList();
    }

    // Địa chỉ đầu tiên của user tự động là default - user không cần thêm 1 lượt gọi setDefault()
    // riêng chỉ để đánh dấu địa chỉ duy nhất họ có.
    @Transactional
    public AddressResponse create(String email, AddressRequest request) {
        User user = resolveUser(email);
        boolean isFirstAddress = addressRepository.findByUserId(user.getId()).isEmpty();

        Address address = Address.builder()
                .user(user)
                .recipientName(request.recipientName())
                .phone(request.phone())
                .addressLine(request.addressLine())
                .ward(request.ward())
                .district(request.district())
                .province(request.province())
                .isDefault(isFirstAddress)
                .build();

        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse update(String email, Long addressId, AddressRequest request) {
        Address address = findOwned(email, addressId);
        address.setRecipientName(request.recipientName());
        address.setPhone(request.phone());
        address.setAddressLine(request.addressLine());
        address.setWard(request.ward());
        address.setDistrict(request.district());
        address.setProvince(request.province());
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public void delete(String email, Long addressId) {
        addressRepository.delete(findOwned(email, addressId));
    }

    @Transactional
    public AddressResponse setDefault(String email, Long addressId) {
        User user = resolveUser(email);
        Address target = findOwned(email, addressId);

        addressRepository.findDefaultByUserId(user.getId())
                .filter(current -> !current.getId().equals(target.getId()))
                .ifPresent(current -> {
                    current.setDefault(false);
                    addressRepository.save(current);
                });

        target.setDefault(true);
        return AddressResponse.from(addressRepository.save(target));
    }

    private User resolveUser(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Address findOwned(String email, Long addressId) {
        User user = resolveUser(email);
        return addressRepository.findByIdAndUserId(addressId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found: " + addressId));
    }
}
```

Note: Lombok's `@Setter` on a `boolean isDefault` field generates `setDefault(boolean)` (it strips the `is` prefix for the setter, unlike the getter which keeps it as `isDefault()`) — use `setDefault(...)` as shown above, not `setIsDefault(...)`.

- [ ] **Step 5: Implement `AddressController`**

Create `src/main/java/com/example/backend/controller/AddressController.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.AddressResponse;
import com.example.backend.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public List<AddressResponse> list(@AuthenticationPrincipal UserDetails userDetails) {
        return addressService.list(userDetails.getUsername());
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(@AuthenticationPrincipal UserDetails userDetails,
                                                    @Valid @RequestBody AddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(addressService.create(userDetails.getUsername(), request));
    }

    @PutMapping("/{id}")
    public AddressResponse update(@AuthenticationPrincipal UserDetails userDetails,
                                   @PathVariable Long id, @Valid @RequestBody AddressRequest request) {
        return addressService.update(userDetails.getUsername(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        addressService.delete(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    public AddressResponse setDefault(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return addressService.setDefault(userDetails.getUsername(), id);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=AddressControllerIT`
Expected: PASS (all four tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/backend/dto/AddressRequest.java \
        src/main/java/com/example/backend/dto/AddressResponse.java \
        src/main/java/com/example/backend/service/AddressService.java \
        src/main/java/com/example/backend/controller/AddressController.java \
        src/test/java/com/example/backend/controller/AddressControllerIT.java
git commit -m "feat: add Address CRUD with default-address selection"
```

---

### Task 6: Cart and CartItem entities and repositories

**Files:**
- Create: `src/main/java/com/example/backend/entity/Cart.java`
- Create: `src/main/java/com/example/backend/entity/CartItem.java`
- Create: `src/main/java/com/example/backend/repository/CartRepository.java`
- Create: `src/main/java/com/example/backend/repository/CartItemRepository.java`
- Test: `src/test/java/com/example/backend/repository/CartRepositoryTest.java`

**Interfaces:**
- Consumes: `User`, `ProductVariant` (existing).
- Produces: `Cart` (`Long id`, `User user`), `CartItem` (`Long id`, `Cart cart`, `ProductVariant variant`, `Integer quantity`). `CartRepository.findByUserId(Long): Optional<Cart>`. `CartItemRepository.findByCartId(Long): List<CartItem>`, `.findByCartIdAndVariantId(Long, Long): Optional<CartItem>`, `.findByIdAndCartId(Long, Long): Optional<CartItem>`, `.findByIdInAndCartId(List<Long>, Long): List<CartItem>`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/repository/CartRepositoryTest.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;
import com.example.backend.entity.Category;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CartRepositoryTest {

    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    void savesCartWithItemsAndFindsByUser() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());
        ProductVariant variant = saveVariant();

        Cart cart = cartRepository.save(Cart.builder().user(user).build());
        CartItem item = cartItemRepository.save(CartItem.builder().cart(cart).variant(variant).quantity(2).build());

        assertThat(cartRepository.findByUserId(user.getId())).contains(cart);
        assertThat(cartItemRepository.findByCartId(cart.getId())).containsExactly(item);
        assertThat(cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId())).contains(item);
        assertThat(cartItemRepository.findByIdAndCartId(item.getId(), cart.getId())).isPresent();
        assertThat(cartItemRepository.findByIdInAndCartId(List.of(item.getId()), cart.getId())).hasSize(1);
        assertThat(cartRepository.findByUserId(999_999L)).isEmpty();
    }

    private ProductVariant saveVariant() {
        Category category = categoryRepository.save(
                Category.builder().name("Fashion").slug("fashion").description("Clothes").build());
        Product product = Product.builder()
                .category(category).name("T-Shirt").slug("t-shirt").description("Basic tee")
                .status(ProductStatus.ACTIVE).build();
        product.addVariant(ProductVariant.builder()
                .sku("TSHIRT-BLK-M").size("M").color("Black").price(new BigDecimal("19.99")).stockQuantity(50).build());
        productRepository.saveAndFlush(product);
        return product.getVariants().get(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CartRepositoryTest`
Expected: FAIL to compile — `Cart`, `CartItem`, `CartRepository`, `CartItemRepository` don't exist yet.

- [ ] **Step 3: Create `Cart`**

Create `src/main/java/com/example/backend/entity/Cart.java`:

```java
package com.example.backend.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Cart không có price snapshot / timestamp: đây là trạng thái tạm, luôn phản ánh giá/tồn kho live
// của variant (khác Order - đã snapshot mọi thứ tại thời điểm mua). Xem spec §4.
@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
}
```

- [ ] **Step 4: Create `CartItem`**

Create `src/main/java/com/example/backend/entity/CartItem.java`:

```java
package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "cart_items", uniqueConstraints = @UniqueConstraint(columnNames = {"cart_id", "variant_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false)
    private Integer quantity;
}
```

- [ ] **Step 5: Create the repositories**

Create `src/main/java/com/example/backend/repository/CartRepository.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUserId(Long userId);
}
```

Create `src/main/java/com/example/backend/repository/CartItemRepository.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByCartId(Long cartId);
    Optional<CartItem> findByCartIdAndVariantId(Long cartId, Long variantId);
    Optional<CartItem> findByIdAndCartId(Long id, Long cartId);
    List<CartItem> findByIdInAndCartId(List<Long> ids, Long cartId);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=CartRepositoryTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/backend/entity/Cart.java \
        src/main/java/com/example/backend/entity/CartItem.java \
        src/main/java/com/example/backend/repository/CartRepository.java \
        src/main/java/com/example/backend/repository/CartItemRepository.java \
        src/test/java/com/example/backend/repository/CartRepositoryTest.java
git commit -m "feat: add Cart and CartItem entities and repositories"
```

---

### Task 7: Cart DTOs, CartService, CartController

**Files:**
- Create: `src/main/java/com/example/backend/dto/CartItemAddRequest.java`
- Create: `src/main/java/com/example/backend/dto/CartItemQuantityRequest.java`
- Create: `src/main/java/com/example/backend/dto/CartItemResponse.java`
- Create: `src/main/java/com/example/backend/dto/CartResponse.java`
- Create: `src/main/java/com/example/backend/service/CartService.java`
- Create: `src/main/java/com/example/backend/controller/CartController.java`
- Test: `src/test/java/com/example/backend/controller/CartControllerIT.java`

**Interfaces:**
- Consumes: `Cart`, `CartItem`, `CartRepository`, `CartItemRepository` (Task 6), `ProductVariantRepository` (existing), `User`/`UserRepository`, `ResourceNotFoundException`.
- Produces: `CartItemAddRequest(Long variantId, Integer quantity)`, `CartItemQuantityRequest(Integer quantity)`, `CartItemResponse(Long id, Long variantId, String productName, String sku, BigDecimal price, Integer quantity, BigDecimal subtotal)`, `CartResponse(Long id, List<CartItemResponse> items, BigDecimal totalAmount)`. `CartService.getCart(String email): CartResponse`, `.addItem(String email, CartItemAddRequest): CartResponse`, `.updateItem(String email, Long itemId, CartItemQuantityRequest): CartResponse`, `.removeItem(String email, Long itemId): CartResponse`. Consumed by `OrderService.checkout` (Task 17) via `CartRepository`/`CartItemRepository` directly (not through `CartService`, since checkout needs the raw entities to lock/snapshot, not the DTO).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/controller/CartControllerIT.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CartItemQuantityRequest;
import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CartControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String token;
    private String adminToken;
    private Long variantId;

    // Thứ tự xoá theo FK: cart_items -> variant_id/cart_id, product_repository.deleteAll() cascade
    // xoá variants/images. Không có bước này, @Test method THỨ 2 trong class này sẽ ném
    // DataIntegrityViolationException ngay ở userRepository.deleteAll() (users vẫn còn cart/cart_item
    // tham chiếu từ lượt test trước) - xem Global Constraints.
    @BeforeEach
    void setUp() throws Exception {
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
        User customer = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.CUSTOMER).build());
        token = jwtService.generateToken(customer.getEmail(), customer.getRole().name());

        User admin = userRepository.save(User.builder()
                .name("Admin").email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getEmail(), admin.getRole().name());

        variantId = seedVariant();
    }

    @Test
    void addingSameVariantTwiceIncrementsQuantityInsteadOfDuplicating() throws Exception {
        addItem(variantId, 2);
        addItem(variantId, 3);

        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                // Literal 99.95, KHÔNG viết "5 * 19.99": phép nhân double trong Java cho ra
                // 99.94999999999999 (lỗi làm tròn số thực dấu phẩy động) trong khi server tính bằng
                // BigDecimal ra đúng 99.95 - jsonPath so khớp double nên phải viết literal đã tính sẵn.
                // (Phát hiện khi chạy thật lúc thực thi plan - RED thật, không phải lý thuyết.)
                .andExpect(jsonPath("$.totalAmount").value(99.95));
    }

    @Test
    void updatesAndRemovesItem() throws Exception {
        Long itemId = addItem(variantId, 2);

        String updateBody = objectMapper.writeValueAsString(new CartItemQuantityRequest(5));
        mockMvc.perform(put("/api/cart/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(5));

        mockMvc.perform(delete("/api/cart/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void addingUnknownVariantReturns404() throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(999_999L, 1));
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    private Long addItem(Long variantId, int quantity) throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(variantId, quantity));
        String response = mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("items").get(0).get("id").asLong();
    }

    private Long seedVariant() throws Exception {
        String categoryBody = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion", "Clothes"));
        String categoryResponse = mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = objectMapper.readTree(categoryResponse).get("id").asLong();

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt", "Basic tee", null));
        String productResponse = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).get("id").asLong();

        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest("TSHIRT-BLK-M", "M", "Black", new BigDecimal("19.99"), 50));
        String variantResponse = mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(variantResponse).get("variants").get(0).get("id").asLong();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CartControllerIT`
Expected: FAIL to compile — none of the new Cart classes exist yet.

- [ ] **Step 3: Create the DTOs**

Create `src/main/java/com/example/backend/dto/CartItemAddRequest.java`:

```java
package com.example.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemAddRequest(@NotNull Long variantId, @NotNull @Min(1) Integer quantity) {}
```

Create `src/main/java/com/example/backend/dto/CartItemQuantityRequest.java`:

```java
package com.example.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemQuantityRequest(@NotNull @Min(1) Integer quantity) {}
```

Create `src/main/java/com/example/backend/dto/CartItemResponse.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.CartItem;
import com.example.backend.entity.ProductVariant;

import java.math.BigDecimal;

// price/subtotal đọc LIVE từ ProductVariant (không snapshot) - khác OrderItemResponse. Xem spec §4.
public record CartItemResponse(Long id, Long variantId, String productName, String sku, BigDecimal price, Integer quantity, BigDecimal subtotal) {
    public static CartItemResponse from(CartItem item) {
        ProductVariant variant = item.getVariant();
        BigDecimal subtotal = variant.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(item.getId(), variant.getId(), variant.getProduct().getName(), variant.getSku(),
                variant.getPrice(), item.getQuantity(), subtotal);
    }
}
```

Create `src/main/java/com/example/backend/dto/CartResponse.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(Long id, List<CartItemResponse> items, BigDecimal totalAmount) {
    public static CartResponse from(Cart cart, List<CartItem> items) {
        List<CartItemResponse> itemResponses = items.stream().map(CartItemResponse::from).toList();
        BigDecimal total = itemResponses.stream().map(CartItemResponse::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(cart.getId(), itemResponses, total);
    }
}
```

- [ ] **Step 4: Implement `CartService`**

Create `src/main/java/com/example/backend/service/CartService.java`:

```java
package com.example.backend.service;

import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CartItemQuantityRequest;
import com.example.backend.dto.CartResponse;
import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.User;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;

    public CartService(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        ProductVariantRepository productVariantRepository,
                        UserRepository userRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productVariantRepository = productVariantRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public CartResponse getCart(String email) {
        Cart cart = getOrCreateCart(email);
        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    // Thêm variant đã có trong cart -> TĂNG quantity thay vì tạo dòng mới (UNIQUE(cart_id,
    // variant_id) ở Task 6 cũng chặn việc tạo dòng trùng nếu code này có bug).
    @Transactional
    public CartResponse addItem(String email, CartItemAddRequest request) {
        Cart cart = getOrCreateCart(email);
        ProductVariant variant = productVariantRepository.findById(request.variantId())
                .orElseThrow(() -> new ResourceNotFoundException("Product variant not found: " + request.variantId()));

        CartItem item = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId())
                .orElseGet(() -> CartItem.builder().cart(cart).variant(variant).quantity(0).build());
        item.setQuantity(item.getQuantity() + request.quantity());
        cartItemRepository.save(item);

        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Transactional
    public CartResponse updateItem(String email, Long itemId, CartItemQuantityRequest request) {
        Cart cart = getOrCreateCart(email);
        CartItem item = cartItemRepository.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + itemId));
        item.setQuantity(request.quantity());
        cartItemRepository.save(item);
        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Transactional
    public CartResponse removeItem(String email, Long itemId) {
        Cart cart = getOrCreateCart(email);
        CartItem item = cartItemRepository.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + itemId));
        cartItemRepository.delete(item);
        return CartResponse.from(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    private Cart getOrCreateCart(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return cartRepository.findByUserId(user.getId())
                .orElseGet(() -> cartRepository.save(Cart.builder().user(user).build()));
    }
}
```

- [ ] **Step 5: Implement `CartController`**

Create `src/main/java/com/example/backend/controller/CartController.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CartItemQuantityRequest;
import com.example.backend.dto.CartResponse;
import com.example.backend.service.CartService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal UserDetails userDetails) {
        return cartService.getCart(userDetails.getUsername());
    }

    @PostMapping("/items")
    public CartResponse addItem(@AuthenticationPrincipal UserDetails userDetails,
                                 @Valid @RequestBody CartItemAddRequest request) {
        return cartService.addItem(userDetails.getUsername(), request);
    }

    @PutMapping("/items/{itemId}")
    public CartResponse updateItem(@AuthenticationPrincipal UserDetails userDetails,
                                    @PathVariable Long itemId, @Valid @RequestBody CartItemQuantityRequest request) {
        return cartService.updateItem(userDetails.getUsername(), itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    public CartResponse removeItem(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long itemId) {
        return cartService.removeItem(userDetails.getUsername(), itemId);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=CartControllerIT`
Expected: PASS (all three tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/backend/dto/CartItemAddRequest.java \
        src/main/java/com/example/backend/dto/CartItemQuantityRequest.java \
        src/main/java/com/example/backend/dto/CartItemResponse.java \
        src/main/java/com/example/backend/dto/CartResponse.java \
        src/main/java/com/example/backend/service/CartService.java \
        src/main/java/com/example/backend/controller/CartController.java \
        src/test/java/com/example/backend/controller/CartControllerIT.java
git commit -m "feat: add Cart get/add/update/remove endpoints"
```

---

### Task 8: Order domain enums, entities, and repository

**Files:**
- Create: `src/main/java/com/example/backend/entity/OrderStatus.java`
- Create: `src/main/java/com/example/backend/entity/PaymentMethod.java`
- Create: `src/main/java/com/example/backend/entity/PaymentStatus.java`
- Create: `src/main/java/com/example/backend/entity/Order.java`
- Create: `src/main/java/com/example/backend/entity/OrderItem.java`
- Create: `src/main/java/com/example/backend/entity/Payment.java`
- Create: `src/main/java/com/example/backend/repository/OrderRepository.java`
- Test: `src/test/java/com/example/backend/repository/OrderRepositoryTest.java`

**Interfaces:**
- Consumes: `User`, `ProductVariant` (existing).
- Produces: `OrderStatus{PENDING_PAYMENT,CONFIRMED,SHIPPED,DELIVERED,CANCELLED}`, `PaymentMethod{COD,VNPAY,STRIPE}`, `PaymentStatus{PENDING,SUCCESS,FAILED}`. `Order` (fields per spec §4, plus `List<OrderItem> items`, `Payment payment`, helpers `addItem(OrderItem)` and `assignPayment(Payment)` mirroring `Product.addVariant`/`addImage`). `OrderItem` (fields per spec §4, `ManyToOne Order order`). `Payment` (fields per spec §4, `OneToOne Order order` — owning side). `OrderRepository.findByIdAndUserId(Long, Long): Optional<Order>`, `.findByUserId(Long, Pageable): Page<Order>`, `.findByStatus(OrderStatus, Pageable): Page<Order>`, `.findByStatusAndExpiresAtBefore(OrderStatus, Instant): List<Order>`.
- **Deviation from spec §12:** no standalone `OrderItemRepository`/`PaymentRepository`. Both `OrderItem` and `Payment` are only ever reached through their parent `Order` (`order.getItems()` / `order.getPayment()`) — every task in this plan that touches them already has the `Order` loaded first. A repository nobody queries independently is dead code; add one later if a feature needs to query items/payments without their order.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/repository/OrderRepositoryTest.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.Category;
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderItem;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.Payment;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OrderRepositoryTest {

    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    void savesOrderWithItemsAndPaymentCascaded() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());
        ProductVariant variant = saveVariant();

        Order order = Order.builder()
                .user(user)
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(new BigDecimal("39.98"))
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build();
        order.addItem(OrderItem.builder()
                .variant(variant).productName("T-Shirt").sku(variant.getSku())
                .unitPrice(variant.getPrice()).quantity(2).build());
        order.assignPayment(Payment.builder().gateway(PaymentMethod.VNPAY).status(PaymentStatus.PENDING).build());

        Order saved = orderRepository.save(order);

        assertThat(orderRepository.findByIdAndUserId(saved.getId(), user.getId())).isPresent();
        assertThat(orderRepository.findByIdAndUserId(saved.getId(), 999_999L)).isEmpty();
        assertThat(orderRepository.findByUserId(user.getId(), PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(orderRepository.findByStatus(OrderStatus.PENDING_PAYMENT, PageRequest.of(0, 10)).getContent()).hasSize(1);

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getPayment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void findsOverdueOrdersByStatusAndExpiry() {
        User user = userRepository.save(User.builder()
                .name("Nhut").email("nhut@example.com").passwordHash("hashed").role(Role.CUSTOMER).build());

        Order overdue = orderRepository.save(Order.builder()
                .user(user).status(OrderStatus.PENDING_PAYMENT).paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(BigDecimal.TEN).expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)).build());
        orderRepository.save(Order.builder()
                .user(user).status(OrderStatus.PENDING_PAYMENT).paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(BigDecimal.TEN).expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES)).build());

        var overdueOrders = orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, Instant.now());
        assertThat(overdueOrders).extracting(Order::getId).containsExactly(overdue.getId());
    }

    private ProductVariant saveVariant() {
        Category category = categoryRepository.save(
                Category.builder().name("Fashion").slug("fashion").description("Clothes").build());
        Product product = Product.builder()
                .category(category).name("T-Shirt").slug("t-shirt").description("Basic tee")
                .status(ProductStatus.ACTIVE).build();
        product.addVariant(ProductVariant.builder()
                .sku("TSHIRT-BLK-M").size("M").color("Black").price(new BigDecimal("19.99")).stockQuantity(50).build());
        productRepository.saveAndFlush(product);
        return product.getVariants().get(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderRepositoryTest`
Expected: FAIL to compile — none of the new entities/enums/repository exist yet.

- [ ] **Step 3: Create the enums**

Create `src/main/java/com/example/backend/entity/OrderStatus.java`:

```java
package com.example.backend.entity;

public enum OrderStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
```

Create `src/main/java/com/example/backend/entity/PaymentMethod.java`:

```java
package com.example.backend.entity;

public enum PaymentMethod {
    COD,
    VNPAY,
    STRIPE
}
```

Create `src/main/java/com/example/backend/entity/PaymentStatus.java`:

```java
package com.example.backend.entity;

public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED
}
```

- [ ] **Step 4: Create `Order`**

Create `src/main/java/com/example/backend/entity/Order.java`:

```java
package com.example.backend.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    // Snapshot copy từ Address lúc checkout - KHÔNG FK vào addresses, sửa/xoá address sau không
    // ảnh hưởng order cũ. Xem spec §4/§6.
    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String addressLine;

    private String ward;
    private String district;
    private String province;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    // null cho COD (không có gì để expire); set = now + order.payment-expiry-minutes cho VNPay/Stripe.
    private Instant expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private Payment payment;

    // Cùng lý do addVariant()/addImage() trên Product (Task 10 của module trước): owning side của
    // quan hệ (order_id) nằm ở OrderItem/Payment - chỉ add vào list/field trong bộ nhớ KHÔNG đủ,
    // phải set cả chiều ngược lại để Hibernate lưu đúng khoá ngoại.
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void assignPayment(Payment payment) {
        this.payment = payment;
        payment.setOrder(this);
    }
}
```

- [ ] **Step 5: Create `OrderItem`**

Create `src/main/java/com/example/backend/entity/OrderItem.java`:

```java
package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // Giữ để tra cứu variant hiện tại, KHÔNG authoritative cho giá/tên - dùng productName/sku/
    // unitPrice snapshot bên dưới. Variant sau đổi giá hay bị xoá không ảnh hưởng order cũ. Xoá
    // 1 variant đã từng nằm trong order_items sẽ bị FK chặn (DataIntegrityViolationException -> 409,
    // xem GlobalExceptionHandler) - hành vi này tự nhiên có sẵn, không cần code thêm.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private Integer quantity;
}
```

- [ ] **Step 6: Create `Payment`**

Create `src/main/java/com/example/backend/entity/Payment.java`:

```java
package com.example.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Owning side (giữ cột order_id) - Order.payment là mappedBy, xem Order.assignPayment().
    @OneToOne
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod gateway;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    // null cho COD.
    private String gatewayTransactionRef;

    private Instant paidAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

- [ ] **Step 7: Create `OrderRepository`**

Create `src/main/java/com/example/backend/repository/OrderRepository.java`:

```java
package com.example.backend.repository;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    Page<Order> findByUserId(Long userId, Pageable pageable);
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant time);
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderRepositoryTest`
Expected: PASS (both tests).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/backend/entity/OrderStatus.java \
        src/main/java/com/example/backend/entity/PaymentMethod.java \
        src/main/java/com/example/backend/entity/PaymentStatus.java \
        src/main/java/com/example/backend/entity/Order.java \
        src/main/java/com/example/backend/entity/OrderItem.java \
        src/main/java/com/example/backend/entity/Payment.java \
        src/main/java/com/example/backend/repository/OrderRepository.java \
        src/test/java/com/example/backend/repository/OrderRepositoryTest.java
git commit -m "feat: add Order, OrderItem, Payment entities and OrderRepository"
```

---

### Task 9: Domain events and listener

**Files:**
- Create: `src/main/java/com/example/backend/event/OrderPlacedEvent.java`
- Create: `src/main/java/com/example/backend/event/PaymentConfirmedEvent.java`
- Create: `src/main/java/com/example/backend/event/OrderCancelledEvent.java`
- Create: `src/main/java/com/example/backend/event/OrderEventListener.java`
- Test: `src/test/java/com/example/backend/event/OrderEventListenerTest.java`

**Interfaces:**
- Produces: `OrderPlacedEvent(Long orderId)`, `PaymentConfirmedEvent(Long orderId)`, `OrderCancelledEvent(Long orderId, String reason)` — plain records, published via Spring's `ApplicationEventPublisher`. `OrderEventListener` — `@Component` with `@EventListener` methods, currently just logs; the seam for a future notification service or Kafka producer (spec §3, §8). Consumed by `OrderService` (Tasks 15, 17, 18, 20), which injects `ApplicationEventPublisher` (Spring-provided bean, no new wiring needed) and calls `publishEvent(...)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/event/OrderEventListenerTest.java`:

```java
package com.example.backend.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class OrderEventListenerTest {

    private final OrderEventListener listener = new OrderEventListener();

    @Test
    void handlesEachEventTypeWithoutThrowing() {
        assertThatCode(() -> listener.onOrderPlaced(new OrderPlacedEvent(1L))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onPaymentConfirmed(new PaymentConfirmedEvent(1L))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onOrderCancelled(new OrderCancelledEvent(1L, "EXPIRED"))).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderEventListenerTest`
Expected: FAIL to compile — none of the event classes or the listener exist yet.

- [ ] **Step 3: Create the event records**

Create `src/main/java/com/example/backend/event/OrderPlacedEvent.java`:

```java
package com.example.backend.event;

public record OrderPlacedEvent(Long orderId) {}
```

Create `src/main/java/com/example/backend/event/PaymentConfirmedEvent.java`:

```java
package com.example.backend.event;

public record PaymentConfirmedEvent(Long orderId) {}
```

Create `src/main/java/com/example/backend/event/OrderCancelledEvent.java`:

```java
package com.example.backend.event;

public record OrderCancelledEvent(Long orderId, String reason) {}
```

- [ ] **Step 4: Implement `OrderEventListener`**

Create `src/main/java/com/example/backend/event/OrderEventListener.java`:

```java
package com.example.backend.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Điểm nối cho consumer bất đồng bộ trong tương lai (email xác nhận, Kafka producer, v.v.) - xem
// spec §3 non-goals + §8. Hiện tại chỉ log; đổi THÂN của các method này (không đổi chỗ nào publish
// event trong OrderService) là đủ để chuyển sang xử lý thật sau này.
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("Order placed: orderId={}", event.orderId());
    }

    @EventListener
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        log.info("Payment confirmed: orderId={}", event.orderId());
    }

    @EventListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        log.info("Order cancelled: orderId={}, reason={}", event.orderId(), event.reason());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderEventListenerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/event/ src/test/java/com/example/backend/event/
git commit -m "feat: add OrderPlaced/PaymentConfirmed/OrderCancelled domain events and listener"
```

---

### Task 10: PaymentGateway interface, result types, and CodGateway

**Files:**
- Create: `src/main/java/com/example/backend/service/payment/PaymentGateway.java`
- Create: `src/main/java/com/example/backend/service/payment/PaymentInitResult.java`
- Create: `src/main/java/com/example/backend/service/payment/PaymentWebhookResult.java`
- Create: `src/main/java/com/example/backend/service/payment/CodGateway.java`
- Test: `src/test/java/com/example/backend/service/payment/CodGatewayTest.java`

**Interfaces:**
- Consumes: `Order`, `PaymentMethod` (Task 8).
- Produces: `PaymentGateway` interface (`getMethod(): PaymentMethod`, `initiate(Order): PaymentInitResult`, `parseWebhook(HttpServletRequest, String rawBody): PaymentWebhookResult`). `PaymentInitResult(String redirectUrl, String clientSecret)` with statics `none()`, `redirect(String)`, `clientSecret(String)`. `PaymentWebhookResult(Long orderId, boolean success, String gatewayTransactionRef)` — `null` return from `parseWebhook` means "invalid signature". `CodGateway` — `@Component`, `getMethod()` returns `COD`, `initiate()` returns `PaymentInitResult.none()`, `parseWebhook()` throws `UnsupportedOperationException` (COD has no gateway callback). Consumed by `PaymentService` (Task 13), `OrderService.checkout` (Task 15).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/service/payment/CodGatewayTest.java`:

```java
package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodGatewayTest {

    private final CodGateway gateway = new CodGateway();

    @Test
    void identifiesAsCodAndReturnsEmptyInitResult() {
        Order order = Order.builder().status(OrderStatus.CONFIRMED).paymentMethod(PaymentMethod.COD).build();

        assertThat(gateway.getMethod()).isEqualTo(PaymentMethod.COD);
        PaymentInitResult result = gateway.initiate(order);
        assertThat(result.redirectUrl()).isNull();
        assertThat(result.clientSecret()).isNull();
    }

    @Test
    void hasNoWebhook() {
        assertThatThrownBy(() -> gateway.parseWebhook(null, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CodGatewayTest`
Expected: FAIL to compile — none of these classes exist yet.

- [ ] **Step 3: Create `PaymentInitResult`**

Create `src/main/java/com/example/backend/service/payment/PaymentInitResult.java`:

```java
package com.example.backend.service.payment;

public record PaymentInitResult(String redirectUrl, String clientSecret) {
    public static PaymentInitResult none() {
        return new PaymentInitResult(null, null);
    }

    public static PaymentInitResult redirect(String url) {
        return new PaymentInitResult(url, null);
    }

    public static PaymentInitResult clientSecret(String secret) {
        return new PaymentInitResult(null, secret);
    }
}
```

- [ ] **Step 4: Create `PaymentWebhookResult`**

Create `src/main/java/com/example/backend/service/payment/PaymentWebhookResult.java`:

```java
package com.example.backend.service.payment;

public record PaymentWebhookResult(Long orderId, boolean success, String gatewayTransactionRef) {}
```

- [ ] **Step 5: Create `PaymentGateway`**

Create `src/main/java/com/example/backend/service/payment/PaymentGateway.java`:

```java
package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import jakarta.servlet.http.HttpServletRequest;

public interface PaymentGateway {
    PaymentMethod getMethod();

    PaymentInitResult initiate(Order order);

    // Trả về null nếu chữ ký/signature không hợp lệ - PaymentService/controller coi đó là 400,
    // KHÔNG động tới order nào cả. Xem từng implementation (VnPayGateway, StripeGateway) để biết
    // cách verify chữ ký cụ thể của mỗi gateway.
    PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody);
}
```

- [ ] **Step 6: Implement `CodGateway`**

Create `src/main/java/com/example/backend/service/payment/CodGateway.java`:

```java
package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

// COD không có gateway ngoài để chờ webhook - OrderService.checkout xác nhận (CONFIRMED) và trừ
// stock thật ngay lúc tạo Order cho COD, KHÔNG gọi initiate() ở nhánh đó (xem spec §6). Class này
// vẫn implement PaymentGateway đầy đủ để PaymentService.gatewayOf(COD) có gì đó trả về, và để test
// theo cùng interface với VnPayGateway/StripeGateway.
@Component
public class CodGateway implements PaymentGateway {

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.COD;
    }

    @Override
    public PaymentInitResult initiate(Order order) {
        return PaymentInitResult.none();
    }

    @Override
    public PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody) {
        throw new UnsupportedOperationException("COD has no webhook");
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw test -Dtest=CodGatewayTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/backend/service/payment/PaymentGateway.java \
        src/main/java/com/example/backend/service/payment/PaymentInitResult.java \
        src/main/java/com/example/backend/service/payment/PaymentWebhookResult.java \
        src/main/java/com/example/backend/service/payment/CodGateway.java \
        src/test/java/com/example/backend/service/payment/CodGatewayTest.java
git commit -m "feat: add PaymentGateway strategy interface and CodGateway"
```

---

### Task 11: VnPayGateway

**Files:**
- Create: `src/main/java/com/example/backend/service/payment/VnPayGateway.java`
- Test: `src/test/java/com/example/backend/service/payment/VnPayGatewayTest.java`

**Interfaces:**
- Consumes: `PaymentGateway`, `PaymentInitResult`, `PaymentWebhookResult` (Task 10), `vnpay.*` properties (Task 1).
- Produces: `VnPayGateway` (`@Component`, constructor `(String merchantCode, String hashSecret, String payUrl, String returnUrl)` — same constructor works both Spring-wired via `@Value` and instantiated directly in tests). Package-private static helpers `buildQuery(Map<String,String>)` and `hmacSha512(String key, String data)` — visible to the test in the same package so it can build a validly-signed fixture request without duplicating the signing algorithm.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/service/payment/VnPayGatewayTest.java`:

```java
package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class VnPayGatewayTest {

    private static final String HASH_SECRET = "TEST_HASH_SECRET_AT_LEAST_THIS_LONG";

    private final VnPayGateway gateway = new VnPayGateway(
            "TEST_MERCHANT", HASH_SECRET,
            "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
            "http://localhost:8080/api/payments/webhooks/vnpay");

    @Test
    void initiateBuildsSignedPayUrlWithAmountTimes100() {
        Order order = Order.builder().id(42L).status(OrderStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.VNPAY).totalAmount(new BigDecimal("150000")).build();

        PaymentInitResult result = gateway.initiate(order);

        // VNPay nhân amount x100 theo quy ước riêng của nó: 150000 -> "15000000".
        assertThat(result.redirectUrl())
                .contains("vnp_TmnCode=TEST_MERCHANT")
                .contains("vnp_Amount=15000000")
                .contains("vnp_TxnRef=42")
                .contains("vnp_SecureHash=");
        assertThat(result.clientSecret()).isNull();
    }

    @Test
    void parseWebhookAcceptsValidSignatureAndRejectsTampering() {
        MockHttpServletRequest valid = new MockHttpServletRequest();
        valid.setParameter("vnp_TxnRef", "42");
        valid.setParameter("vnp_ResponseCode", "00");
        valid.setParameter("vnp_TransactionNo", "VNP123");
        valid.setParameter("vnp_Amount", "15000000");
        valid.setParameter("vnp_SecureHash", signParamsForTest(valid));

        PaymentWebhookResult result = gateway.parseWebhook(valid, null);
        assertThat(result).isNotNull();
        assertThat(result.orderId()).isEqualTo(42L);
        assertThat(result.success()).isTrue();
        assertThat(result.gatewayTransactionRef()).isEqualTo("VNP123");

        // Ký xong mới đổi vnp_Amount -> hash không còn khớp -> phải bị từ chối (trả null), không
        // được đọc nhầm order/số tiền theo params đã bị sửa.
        MockHttpServletRequest tampered = new MockHttpServletRequest();
        tampered.setParameter("vnp_TxnRef", "42");
        tampered.setParameter("vnp_ResponseCode", "00");
        tampered.setParameter("vnp_Amount", "99999999");
        tampered.setParameter("vnp_SecureHash", signParamsForTest(valid));

        assertThat(gateway.parseWebhook(tampered, null)).isNull();
    }

    // Ký lại bằng ĐÚNG helper mà VnPayGateway.parseWebhook() dùng (package-private, xem Step 3) để
    // tạo 1 request "hợp lệ" - test không mock hay tự viết lại thuật toán ký riêng.
    private String signParamsForTest(MockHttpServletRequest request) {
        Map<String, String> params = new TreeMap<>();
        for (String name : Collections.list(request.getParameterNames())) {
            params.put(name, request.getParameter(name));
        }
        return VnPayGateway.hmacSha512(HASH_SECRET, VnPayGateway.buildQuery(params));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=VnPayGatewayTest`
Expected: FAIL to compile — `VnPayGateway` doesn't exist yet.

- [ ] **Step 3: Implement `VnPayGateway`**

Create `src/main/java/com/example/backend/service/payment/VnPayGateway.java`:

```java
package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;

// VNPay không có SDK Java chính thức - build query string + ký HMAC-SHA512 tay theo tài liệu của
// VNPay (https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).
@Component
public class VnPayGateway implements PaymentGateway {

    private static final DateTimeFormatter CREATE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final String merchantCode;
    private final String hashSecret;
    private final String payUrl;
    private final String returnUrl;

    public VnPayGateway(@Value("${vnpay.merchant-code}") String merchantCode,
                         @Value("${vnpay.hash-secret}") String hashSecret,
                         @Value("${vnpay.pay-url}") String payUrl,
                         @Value("${vnpay.return-url}") String returnUrl) {
        this.merchantCode = merchantCode;
        this.hashSecret = hashSecret;
        this.payUrl = payUrl;
        this.returnUrl = returnUrl;
    }

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.VNPAY;
    }

    @Override
    public PaymentInitResult initiate(Order order) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", merchantCode);
        // VNPay LUÔN nhân amount x100 - quy ước nội bộ riêng của VNPay, KHÔNG liên quan tới quy ước
        // zero-decimal-currency của Stripe cho VND (xem StripeGateway.toStripeAmount, Task 12).
        // Đừng lấy nhầm 1 trong 2 chỗ nhân/không nhân 100 này.
        params.put("vnp_Amount", order.getTotalAmount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", order.getId().toString());
        params.put("vnp_OrderInfo", "Thanh toan don hang " + order.getId());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", "127.0.0.1");
        params.put("vnp_CreateDate", ZonedDateTime.now().format(CREATE_DATE_FORMAT));

        String query = buildQuery(params);
        String secureHash = hmacSha512(hashSecret, query);
        return PaymentInitResult.redirect(payUrl + "?" + query + "&vnp_SecureHash=" + secureHash);
    }

    @Override
    public PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody) {
        Map<String, String> params = new TreeMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!"vnp_SecureHash".equals(name) && !"vnp_SecureHashType".equals(name)) {
                params.put(name, request.getParameter(name));
            }
        }

        String receivedHash = request.getParameter("vnp_SecureHash");
        String expectedHash = hmacSha512(hashSecret, buildQuery(params));
        // MessageDigest.isEqual: so sánh constant-time, tránh timing attack dò ký tự đúng của hash
        // (khác String.equals() thường, dừng sớm ngay ký tự sai đầu tiên).
        if (receivedHash == null || !MessageDigest.isEqual(
                receivedHash.getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        Long orderId = Long.parseLong(params.get("vnp_TxnRef"));
        boolean success = "00".equals(params.get("vnp_ResponseCode"));
        return new PaymentWebhookResult(orderId, success, params.get("vnp_TransactionNo"));
    }

    // Package-private (không private): VnPayGatewayTest cần build 1 request "hợp lệ" bằng ĐÚNG
    // thuật toán này, không lặp lại code ký ở 2 chỗ.
    static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static String hmacSha512(String key, String data) {
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            hmac512.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] result = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute VNPay secure hash", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=VnPayGatewayTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/backend/service/payment/VnPayGateway.java \
        src/test/java/com/example/backend/service/payment/VnPayGatewayTest.java
git commit -m "feat: add VnPayGateway (manual HMAC-SHA512 signing/verification)"
```

---

### Task 12: StripeGateway

**Files:**
- Create: `src/main/java/com/example/backend/service/payment/PaymentGatewayException.java`
- Create: `src/main/java/com/example/backend/service/payment/StripeGateway.java`
- Test: `src/test/java/com/example/backend/service/payment/StripeGatewayTest.java`

**Interfaces:**
- Consumes: `PaymentGateway`, `PaymentInitResult`, `PaymentWebhookResult` (Task 10), `stripe.*` properties (Task 1), `com.stripe:stripe-java` (Task 1).
- Produces: `PaymentGatewayException(String, Throwable)` (unchecked — an unexpected Stripe API failure during checkout falls through to the existing generic 500 handler; no new `GlobalExceptionHandler` entry needed). `StripeGateway` (`@Component`, constructor `(String secretKey, String webhookSecret)`). Package-private static `toStripeAmount(BigDecimal): long`.
- This test statically mocks `com.stripe.model.PaymentIntent` and `com.stripe.net.Webhook` — no real network call, no real Stripe key needed to pass. **Prerequisite:** confirm `Mockito.mockStatic(...)` resolves (Mockito 5+ bundles the inline mock maker by default, which is what `spring-boot-starter-webmvc-test` on Spring Boot 4.1.1 pulls in). If Step 2 fails with `MockitoException: ... inline mock maker not available` instead of a normal compile/assertion failure, run `./mvnw dependency:tree | grep mockito` to check the resolved version and add `org.mockito:mockito-inline` (test scope) to `pom.xml` if it's below 5.0.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/service/payment/StripeGatewayTest.java`:

```java
package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class StripeGatewayTest {

    private final StripeGateway gateway = new StripeGateway("sk_test_dummy", "whsec_dummy");

    @Test
    void toStripeAmountSendsWholeVndValueNotTimes100() {
        // VND là zero-decimal currency trong Stripe - KHÔNG x100 như USD cents. Đây là quy ước
        // NGƯỢC với VNPay (luôn x100) - xem VnPayGateway.initiate() ở Task 11.
        assertThat(StripeGateway.toStripeAmount(new BigDecimal("150000"))).isEqualTo(150000L);
    }

    @Test
    void toStripeAmountRoundsRealisticFractionalTotalsInsteadOfThrowing() {
        // Order.totalAmount là price.multiply(quantity) - luôn có .00-.99, KHÔNG phải số nguyên như
        // test ở trên. longValueExact() trên 1 BigDecimal có phần lẻ (39.98) ném ArithmeticException
        // - bug này lọt qua nếu chỉ test với số nguyên tròn. VND không có đơn vị lẻ hơn 1 đồng nên
        // HALF_UP về số nguyên là lựa chọn đúng (không mất phần trăm-nghìn đồng nào đáng kể).
        assertThat(StripeGateway.toStripeAmount(new BigDecimal("39.98"))).isEqualTo(40L);
        assertThat(StripeGateway.toStripeAmount(new BigDecimal("39.49"))).isEqualTo(39L);
    }

    @Test
    void initiateCreatesPaymentIntentWithWholeVndAmountAndReturnsClientSecret() {
        Order order = Order.builder().id(7L).status(OrderStatus.PENDING_PAYMENT)
                .paymentMethod(PaymentMethod.STRIPE).totalAmount(new BigDecimal("150000")).build();

        PaymentIntent fakeIntent = mock(PaymentIntent.class);
        when(fakeIntent.getClientSecret()).thenReturn("pi_fake_secret");

        try (MockedStatic<PaymentIntent> stripeStatic = mockStatic(PaymentIntent.class)) {
            stripeStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenReturn(fakeIntent);

            PaymentInitResult result = gateway.initiate(order);

            assertThat(result.clientSecret()).isEqualTo("pi_fake_secret");
            assertThat(result.redirectUrl()).isNull();
        }
    }

    @Test
    void parseWebhookMapsSucceededEventToSuccessResult() throws SignatureVerificationException {
        Event event = mock(Event.class);
        PaymentIntent intent = mock(PaymentIntent.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);

        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));
        when(intent.getMetadata()).thenReturn(Map.of("orderId", "7"));
        when(intent.getId()).thenReturn("pi_123");

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString())).thenReturn(event);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Stripe-Signature", "t=1,v1=fake");

            PaymentWebhookResult result = gateway.parseWebhook(request, "{}");

            assertThat(result).isNotNull();
            assertThat(result.orderId()).isEqualTo(7L);
            assertThat(result.success()).isTrue();
            assertThat(result.gatewayTransactionRef()).isEqualTo("pi_123");
        }
    }

    @Test
    void parseWebhookReturnsNullOnInvalidSignature() throws SignatureVerificationException {
        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenThrow(mock(SignatureVerificationException.class));

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Stripe-Signature", "t=1,v1=bad");

            assertThat(gateway.parseWebhook(request, "{}")).isNull();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=StripeGatewayTest`
Expected: FAIL to compile — `StripeGateway` doesn't exist yet. (If instead you get a `MockitoException` about the inline mock maker, see the Prerequisite note above before continuing.)

- [ ] **Step 3: Create `PaymentGatewayException`**

Create `src/main/java/com/example/backend/service/payment/PaymentGatewayException.java`:

```java
package com.example.backend.service.payment;

// Lỗi bất ngờ khi gọi API của gateway thật (Stripe/VNPay) lúc initiate() - không tự bắt riêng, rơi
// vào handler Exception.class chung của GlobalExceptionHandler (500) như mọi lỗi hạ tầng khác.
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Implement `StripeGateway`**

Create `src/main/java/com/example/backend/service/payment/StripeGateway.java`:

```java
package com.example.backend.service.payment;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class StripeGateway implements PaymentGateway {

    private final String webhookSecret;

    public StripeGateway(@Value("${stripe.secret-key}") String secretKey,
                          @Value("${stripe.webhook-secret}") String webhookSecret) {
        Stripe.apiKey = secretKey;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.STRIPE;
    }

    @Override
    public PaymentInitResult initiate(Order order) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toStripeAmount(order.getTotalAmount()))
                .setCurrency("vnd")
                .putMetadata("orderId", order.getId().toString())
                .build();
        try {
            PaymentIntent intent = PaymentIntent.create(params);
            return PaymentInitResult.clientSecret(intent.getClientSecret());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe PaymentIntent creation failed for order " + order.getId(), e);
        }
    }

    @Override
    public PaymentWebhookResult parseWebhook(HttpServletRequest request, String rawBody) {
        String signatureHeader = request.getHeader("Stripe-Signature");
        Event event;
        try {
            event = Webhook.constructEvent(rawBody, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            return null;
        }

        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElseThrow();
        Long orderId = Long.parseLong(intent.getMetadata().get("orderId"));
        boolean success = "payment_intent.succeeded".equals(event.getType());
        return new PaymentWebhookResult(orderId, success, intent.getId());
    }

    // VND là zero-decimal currency trong Stripe: amount gửi lên API là số VND NGUYÊN, KHÔNG x100
    // như USD cents. Xem Global Constraints + spec §4 Money handling - nhầm chỗ này khiến MỌI giao
    // dịch Stripe bị tính sai gấp 100 lần (150.000đ bị gửi thành 15.000.000đ).
    // setScale(0, HALF_UP) TRƯỚC longValueExact(): Order.totalAmount luôn có 2 chữ số lẻ
    // (price.multiply(quantity), ví dụ 39.98) - gọi longValueExact() thẳng trên số có phần lẻ ném
    // ArithmeticException ngay tại checkout. VND không có đơn vị nhỏ hơn 1 đồng nên làm tròn về số
    // nguyên là đúng, không mất giá trị đáng kể.
    static long toStripeAmount(BigDecimal totalAmount) {
        return totalAmount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=StripeGatewayTest`
Expected: PASS (all four tests). If `EventDataObjectDeserializer` fails to resolve at the import in the test, the pinned `stripe-java` version's API differs from what's documented here — open its sources jar (`~/.m2/repository/com/stripe/stripe-java/<version>/`) or IDE-navigate `Event.getDataObjectDeserializer()` to find the actual return type for that version and adjust the import/cast accordingly; the signing/dispatch logic in `parseWebhook` stays the same either way.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/service/payment/PaymentGatewayException.java \
        src/main/java/com/example/backend/service/payment/StripeGateway.java \
        src/test/java/com/example/backend/service/payment/StripeGatewayTest.java
git commit -m "feat: add StripeGateway (PaymentIntent + webhook signature verification)"
```

---

### Task 13: PaymentService (gateway dispatch)

**Files:**
- Create: `src/main/java/com/example/backend/service/PaymentService.java`
- Test: `src/test/java/com/example/backend/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: `PaymentGateway`, `PaymentInitResult`, `PaymentWebhookResult` (Task 10); Spring auto-injects every `@Component` implementing `PaymentGateway` (`CodGateway`, `VnPayGateway`, `StripeGateway` — Tasks 10–12) as the constructor's `List<PaymentGateway>`.
- Produces: `PaymentService.initiate(Order): PaymentInitResult`, `.parseWebhook(PaymentMethod, HttpServletRequest, String rawBody): PaymentWebhookResult`. Consumed by `OrderService` (Tasks 15, 17).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/service/PaymentServiceTest.java`:

```java
package com.example.backend.service;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.service.payment.PaymentGateway;
import com.example.backend.service.payment.PaymentInitResult;
import com.example.backend.service.payment.PaymentWebhookResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    @Test
    void dispatchesInitiateToTheGatewayMatchingPaymentMethod() {
        PaymentGateway cod = fakeGateway(PaymentMethod.COD);
        PaymentGateway stripe = fakeGateway(PaymentMethod.STRIPE);
        Order order = Order.builder().paymentMethod(PaymentMethod.STRIPE).build();
        PaymentInitResult expected = PaymentInitResult.clientSecret("secret");
        when(stripe.initiate(order)).thenReturn(expected);

        PaymentService service = new PaymentService(List.of(cod, stripe));

        assertThat(service.initiate(order)).isEqualTo(expected);
    }

    @Test
    void dispatchesWebhookToTheRequestedGateway() {
        PaymentGateway vnpay = fakeGateway(PaymentMethod.VNPAY);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PaymentWebhookResult expected = new PaymentWebhookResult(1L, true, "ref");
        when(vnpay.parseWebhook(request, "body")).thenReturn(expected);

        PaymentService service = new PaymentService(List.of(vnpay));

        assertThat(service.parseWebhook(PaymentMethod.VNPAY, request, "body")).isEqualTo(expected);
    }

    @Test
    void throwsWhenNoGatewayRegisteredForMethod() {
        PaymentService service = new PaymentService(List.of());

        assertThatThrownBy(() -> service.initiate(Order.builder().paymentMethod(PaymentMethod.COD).build()))
                .isInstanceOf(IllegalStateException.class);
    }

    private PaymentGateway fakeGateway(PaymentMethod method) {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.getMethod()).thenReturn(method);
        return gateway;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PaymentServiceTest`
Expected: FAIL to compile — `PaymentService` doesn't exist yet.

- [ ] **Step 3: Implement `PaymentService`**

Create `src/main/java/com/example/backend/service/PaymentService.java`:

```java
package com.example.backend.service;

import com.example.backend.entity.Order;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.service.payment.PaymentGateway;
import com.example.backend.service.payment.PaymentInitResult;
import com.example.backend.service.payment.PaymentWebhookResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    private final Map<PaymentMethod, PaymentGateway> gateways;

    // Spring tự inject MỌI @Component implement PaymentGateway (CodGateway, VnPayGateway,
    // StripeGateway) vào đây dưới dạng List - thêm 1 gateway mới trong tương lai chỉ cần thêm class
    // implement interface này, KHÔNG cần sửa PaymentService.
    public PaymentService(List<PaymentGateway> gatewayList) {
        this.gateways = gatewayList.stream().collect(Collectors.toMap(PaymentGateway::getMethod, Function.identity()));
    }

    public PaymentInitResult initiate(Order order) {
        return gatewayOf(order.getPaymentMethod()).initiate(order);
    }

    public PaymentWebhookResult parseWebhook(PaymentMethod method, HttpServletRequest request, String rawBody) {
        return gatewayOf(method).parseWebhook(request, rawBody);
    }

    private PaymentGateway gatewayOf(PaymentMethod method) {
        PaymentGateway gateway = gateways.get(method);
        if (gateway == null) {
            throw new IllegalStateException("No PaymentGateway registered for " + method);
        }
        return gateway;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PaymentServiceTest`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/backend/service/PaymentService.java \
        src/test/java/com/example/backend/service/PaymentServiceTest.java
git commit -m "feat: add PaymentService dispatching by PaymentMethod"
```

---

### Task 14: Order and Payment DTOs

**Files:**
- Create: `src/main/java/com/example/backend/dto/CheckoutRequest.java`
- Create: `src/main/java/com/example/backend/dto/OrderItemResponse.java`
- Create: `src/main/java/com/example/backend/dto/PaymentInitResponse.java`
- Create: `src/main/java/com/example/backend/dto/OrderResponse.java`
- Create: `src/main/java/com/example/backend/dto/PaymentResponse.java`
- Create: `src/main/java/com/example/backend/dto/OrderStatusUpdateRequest.java`

**Interfaces:**
- Consumes: `Order`, `OrderItem`, `Payment`, `OrderStatus`, `PaymentMethod` (Task 8), `PaymentInitResult` (Task 10).
- Produces: `CheckoutRequest(List<Long> cartItemIds, Long addressId, PaymentMethod paymentMethod)`. `OrderItemResponse(Long id, String productName, String sku, BigDecimal unitPrice, Integer quantity, BigDecimal lineTotal)` with `.from(OrderItem)`. `PaymentInitResponse(String redirectUrl, String clientSecret)` with `.from(PaymentInitResult)`. `OrderResponse(Long id, String status, String paymentMethod, List<OrderItemResponse> items, String recipientName, String phone, String addressLine, String ward, String district, String province, BigDecimal totalAmount, Instant expiresAt, Instant createdAt, PaymentInitResponse paymentInit)` with `.from(Order, PaymentInitResult)` — pass `null` for `PaymentInitResult` on every path except right after checkout. `PaymentResponse(String gateway, String status, String gatewayTransactionRef, Instant paidAt)` with `.from(Payment)`. `OrderStatusUpdateRequest(OrderStatus status)`. No isolated test — plain records with a mapping method, exercised by the controller integration tests in Tasks 16, 17, 19, 20, 21 (same "DTOs have no behavior of their own to unit test" call as the existing `ProductResponse`/`CategoryResponse`).

- [ ] **Step 1: Create `CheckoutRequest`**

Create `src/main/java/com/example/backend/dto/CheckoutRequest.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.PaymentMethod;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CheckoutRequest(
        @NotEmpty List<Long> cartItemIds,
        @NotNull Long addressId,
        @NotNull PaymentMethod paymentMethod
) {}
```

- [ ] **Step 2: Create `OrderItemResponse`**

Create `src/main/java/com/example/backend/dto/OrderItemResponse.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(Long id, String productName, String sku, BigDecimal unitPrice, Integer quantity, BigDecimal lineTotal) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getId(), item.getProductName(), item.getSku(), item.getUnitPrice(), item.getQuantity(),
                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
        );
    }
}
```

- [ ] **Step 3: Create `PaymentInitResponse`**

Create `src/main/java/com/example/backend/dto/PaymentInitResponse.java`:

```java
package com.example.backend.dto;

import com.example.backend.service.payment.PaymentInitResult;

public record PaymentInitResponse(String redirectUrl, String clientSecret) {
    public static PaymentInitResponse from(PaymentInitResult result) {
        return new PaymentInitResponse(result.redirectUrl(), result.clientSecret());
    }
}
```

- [ ] **Step 4: Create `OrderResponse`**

Create `src/main/java/com/example/backend/dto/OrderResponse.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.Order;
import com.example.backend.service.payment.PaymentInitResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String status,
        String paymentMethod,
        List<OrderItemResponse> items,
        String recipientName,
        String phone,
        String addressLine,
        String ward,
        String district,
        String province,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant createdAt,
        PaymentInitResponse paymentInit
) {
    // paymentInit chỉ có giá trị ngay sau checkout (redirectUrl/clientSecret) - mọi lần đọc lại
    // Order sau đó (GET list/detail) truyền initResult = null, client dùng field status để biết
    // trạng thái thanh toán hiện tại thay vì đọc lại paymentInit.
    public static OrderResponse from(Order order, PaymentInitResult initResult) {
        return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getPaymentMethod().name(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getRecipientName(),
                order.getPhone(),
                order.getAddressLine(),
                order.getWard(),
                order.getDistrict(),
                order.getProvince(),
                order.getTotalAmount(),
                order.getExpiresAt(),
                order.getCreatedAt(),
                initResult == null ? null : PaymentInitResponse.from(initResult)
        );
    }
}
```

- [ ] **Step 5: Create `PaymentResponse`**

Create `src/main/java/com/example/backend/dto/PaymentResponse.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.Payment;

import java.time.Instant;

public record PaymentResponse(String gateway, String status, String gatewayTransactionRef, Instant paidAt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getGateway().name(), payment.getStatus().name(),
                payment.getGatewayTransactionRef(), payment.getPaidAt());
    }
}
```

- [ ] **Step 6: Create `OrderStatusUpdateRequest`**

Create `src/main/java/com/example/backend/dto/OrderStatusUpdateRequest.java`:

```java
package com.example.backend.dto;

import com.example.backend.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record OrderStatusUpdateRequest(@NotNull OrderStatus status) {}
```

- [ ] **Step 7: Verify it compiles**

Run: `./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/backend/dto/CheckoutRequest.java \
        src/main/java/com/example/backend/dto/OrderItemResponse.java \
        src/main/java/com/example/backend/dto/PaymentInitResponse.java \
        src/main/java/com/example/backend/dto/OrderResponse.java \
        src/main/java/com/example/backend/dto/PaymentResponse.java \
        src/main/java/com/example/backend/dto/OrderStatusUpdateRequest.java
git commit -m "feat: add Order/Payment DTOs"
```

---

### Task 15: OrderService.checkout()

**Files:**
- Create: `src/main/java/com/example/backend/service/OrderService.java`
- Test: `src/test/java/com/example/backend/service/OrderServiceTest.java`

**Interfaces:**
- Consumes: everything from Tasks 4, 6, 8, 10, 13, 14 (`Address`/`AddressRepository`, `Cart`/`CartItem`/`CartRepository`/`CartItemRepository`, `Order`/`OrderItem`/`Payment`/enums/`OrderRepository`, `PaymentInitResult`, `PaymentService`, `CheckoutRequest`/`OrderResponse`), `ProductVariantRepository.findByIdForUpdate` (Task 3), `UserRepository` (existing), `InsufficientStockException`/`ResourceNotFoundException`.
- Produces: `OrderService` constructor `(OrderRepository, CartRepository, CartItemRepository, AddressRepository, ProductVariantRepository, UserRepository, PaymentService, ApplicationEventPublisher, int paymentExpiryMinutes)`. `OrderService.checkout(String email, CheckoutRequest): OrderResponse`. This constructor signature is what every later task (17, 18, 19, 20, 21) modifies the class around — they add methods, not new constructor parameters.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/service/OrderServiceTest.java`:

```java
package com.example.backend.service;

import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderResponse;
import com.example.backend.entity.Address;
import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;
import com.example.backend.entity.Category;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.Product;
import com.example.backend.entity.ProductStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.exception.InsufficientStockException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.payment.PaymentInitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final CartRepository cartRepository = mock(CartRepository.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final AddressRepository addressRepository = mock(AddressRepository.class);
    private final ProductVariantRepository productVariantRepository = mock(ProductVariantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final OrderService orderService = new OrderService(
            orderRepository, cartRepository, cartItemRepository, addressRepository,
            productVariantRepository, userRepository, paymentService, eventPublisher, 15);

    private User user;
    private Cart cart;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).name("Nhut").email("nhut@example.com").role(Role.CUSTOMER).build();
        Address address = Address.builder().id(10L).user(user).recipientName("Nhut").phone("0900000000")
                .addressLine("123 Main St").isDefault(true).build();
        cart = Cart.builder().id(100L).user(user).build();

        when(userRepository.findByEmail("nhut@example.com")).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(addressRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(address));
    }

    @Test
    void codCheckoutConfirmsImmediatelyAndDeductsStockDirectly() {
        ProductVariant variant = variant(200L, 50, 0);
        CartItem item = cartItem(1000L, variant, 2);
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L), 100L)).thenReturn(List.of(item));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L), 10L, PaymentMethod.COD);
        OrderResponse response = orderService.checkout("nhut@example.com", request);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.totalAmount()).isEqualTo(new BigDecimal("39.98"));
        assertThat(variant.getStockQuantity()).isEqualTo(48);
        assertThat(variant.getReservedQuantity()).isZero();
        verify(cartItemRepository).deleteAll(List.of(item));
        verify(paymentService, never()).initiate(any());
    }

    @Test
    void onlinePaymentCheckoutReservesStockAndCallsGateway() {
        ProductVariant variant = variant(200L, 50, 0);
        CartItem item = cartItem(1000L, variant, 2);
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L), 100L)).thenReturn(List.of(item));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentService.initiate(any())).thenReturn(PaymentInitResult.clientSecret("secret"));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L), 10L, PaymentMethod.STRIPE);
        OrderResponse response = orderService.checkout("nhut@example.com", request);

        assertThat(response.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(response.paymentInit().clientSecret()).isEqualTo("secret");
        assertThat(variant.getStockQuantity()).isEqualTo(50);
        assertThat(variant.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    void insufficientStockThrowsAndNeverSavesOrder() {
        ProductVariant variant = variant(200L, 1, 0);
        CartItem item = cartItem(1000L, variant, 2);
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L), 100L)).thenReturn(List.of(item));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L), 10L, PaymentMethod.COD);

        assertThatThrownBy(() -> orderService.checkout("nhut@example.com", request))
                .isInstanceOf(InsufficientStockException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void requestingCartItemsNotInTheCartThrows404() {
        when(cartItemRepository.findByIdInAndCartId(List.of(1000L, 2000L), 100L))
                .thenReturn(List.of(cartItem(1000L, variant(200L, 50, 0), 1)));

        CheckoutRequest request = new CheckoutRequest(List.of(1000L, 2000L), 10L, PaymentMethod.COD);

        assertThatThrownBy(() -> orderService.checkout("nhut@example.com", request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private ProductVariant variant(Long id, int stock, int reserved) {
        Category category = Category.builder().id(1L).name("Fashion").slug("fashion").build();
        Product product = Product.builder().id(1L).category(category).name("T-Shirt").slug("t-shirt")
                .status(ProductStatus.ACTIVE).build();
        return ProductVariant.builder().id(id).product(product).sku("TSHIRT-M")
                .price(new BigDecimal("19.99")).stockQuantity(stock).reservedQuantity(reserved).build();
    }

    private CartItem cartItem(Long id, ProductVariant variant, int quantity) {
        return CartItem.builder().id(id).cart(cart).variant(variant).quantity(quantity).build();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderServiceTest`
Expected: FAIL to compile — `OrderService` doesn't exist yet.

- [ ] **Step 3: Implement `OrderService.checkout()`**

Create `src/main/java/com/example/backend/service/OrderService.java`:

```java
package com.example.backend.service;

import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderResponse;
import com.example.backend.entity.Address;
import com.example.backend.entity.Cart;
import com.example.backend.entity.CartItem;
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderItem;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.Payment;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.User;
import com.example.backend.event.OrderPlacedEvent;
import com.example.backend.exception.InsufficientStockException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.payment.PaymentInitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final AddressRepository addressRepository;
    private final ProductVariantRepository productVariantRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;
    private final int paymentExpiryMinutes;

    public OrderService(OrderRepository orderRepository,
                         CartRepository cartRepository,
                         CartItemRepository cartItemRepository,
                         AddressRepository addressRepository,
                         ProductVariantRepository productVariantRepository,
                         UserRepository userRepository,
                         PaymentService paymentService,
                         ApplicationEventPublisher eventPublisher,
                         @Value("${order.payment-expiry-minutes}") int paymentExpiryMinutes) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.addressRepository = addressRepository;
        this.productVariantRepository = productVariantRepository;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
        this.eventPublisher = eventPublisher;
        this.paymentExpiryMinutes = paymentExpiryMinutes;
    }

    @Transactional
    public OrderResponse checkout(String email, CheckoutRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Cart cart = cartRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart is empty"));

        List<CartItem> items = cartItemRepository.findByIdInAndCartId(request.cartItemIds(), cart.getId());
        if (items.size() != request.cartItemIds().size()) {
            throw new ResourceNotFoundException("One or more cart items not found");
        }

        Address address = addressRepository.findByIdAndUserId(request.addressId(), user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found: " + request.addressId()));

        // Lock từng variant TRƯỚC khi tạo order: lấy lại dòng mới nhất có PESSIMISTIC_WRITE, tránh
        // đọc bản lazy cached từ CartItem.getVariant() (có thể stale nếu 1 request khác vừa sửa
        // stock giữa lúc user load cart và lúc bấm checkout). Xem Global Constraints.
        List<ProductVariant> lockedVariants = new ArrayList<>();
        for (CartItem item : items) {
            ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
            if (variant.getAvailableQuantity() < item.getQuantity()) {
                throw new InsufficientStockException("Insufficient stock for SKU " + variant.getSku());
            }
            lockedVariants.add(variant);
        }

        boolean isCod = request.paymentMethod() == PaymentMethod.COD;

        Order order = Order.builder()
                .user(user)
                .status(isCod ? OrderStatus.CONFIRMED : OrderStatus.PENDING_PAYMENT)
                .paymentMethod(request.paymentMethod())
                .recipientName(address.getRecipientName())
                .phone(address.getPhone())
                .addressLine(address.getAddressLine())
                .ward(address.getWard())
                .district(address.getDistrict())
                .province(address.getProvince())
                .totalAmount(BigDecimal.ZERO)
                .expiresAt(isCod ? null : Instant.now().plus(paymentExpiryMinutes, ChronoUnit.MINUTES))
                .build();

        // COD xác nhận (CONFIRMED) và trừ stockQuantity THẬT ngay - không có gateway ngoài để chờ.
        // VNPay/Stripe chỉ RESERVE (reservedQuantity) - trừ thật xảy ra ở confirmPayment() khi
        // webhook báo thành công (Task 17). Xem spec §6.
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            CartItem cartItem = items.get(i);
            ProductVariant variant = lockedVariants.get(i);

            order.addItem(OrderItem.builder()
                    .variant(variant)
                    .productName(variant.getProduct().getName())
                    .sku(variant.getSku())
                    .unitPrice(variant.getPrice())
                    .quantity(cartItem.getQuantity())
                    .build());
            total = total.add(variant.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));

            if (isCod) {
                variant.setStockQuantity(variant.getStockQuantity() - cartItem.getQuantity());
            } else {
                variant.setReservedQuantity(variant.getReservedQuantity() + cartItem.getQuantity());
            }
            productVariantRepository.save(variant);
        }
        order.setTotalAmount(total);

        order.assignPayment(Payment.builder()
                .gateway(request.paymentMethod())
                .status(isCod ? PaymentStatus.SUCCESS : PaymentStatus.PENDING)
                .paidAt(isCod ? Instant.now() : null)
                .build());

        orderRepository.save(order);
        cartItemRepository.deleteAll(items);

        // null cho COD (không phải PaymentInitResult.none()): OrderResponse.from() chỉ ẩn hẳn field
        // paymentInit khỏi JSON (null) khi initResult == null - truyền none() sẽ serialize thành
        // {"redirectUrl":null,"clientSecret":null}, một OBJECT không null, khác ý định "COD không
        // có gì để trả". (Phát hiện khi chạy thật OrderControllerIT lúc thực thi plan.)
        PaymentInitResult initResult = isCod ? null : paymentService.initiate(order);

        eventPublisher.publishEvent(new OrderPlacedEvent(order.getId()));

        return OrderResponse.from(order, initResult);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderServiceTest`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/backend/service/OrderService.java \
        src/test/java/com/example/backend/service/OrderServiceTest.java
git commit -m "feat: add OrderService.checkout() with stock lock/reserve and gateway dispatch"
```

---

### Task 16: OrderController checkout endpoint + end-to-end checkout tests

**Files:**
- Create: `src/main/java/com/example/backend/controller/OrderController.java`
- Test: `src/test/java/com/example/backend/controller/OrderControllerIT.java`

**Interfaces:**
- Consumes: `OrderService.checkout` (Task 15), `CheckoutRequest`/`OrderResponse` (Task 14).
- Produces: `POST /api/orders/checkout` → 201 `OrderResponse` (auth required — already covered by the existing default-authenticated `SecurityConfig` rule, no security change needed here). Later tasks (19, 20) add more mappings to this same controller.
- **Scope note:** this task's integration tests exercise COD and VNPay through the real HTTP stack (both are network-free — VNPay's `initiate()` only builds a signed URL locally). STRIPE is deliberately **not** given a full controller IT here: `StripeGateway.initiate()` makes a real call to `PaymentIntent.create()`, and the dummy test key (`sk_test_dummy`) would either hang or fail against the real Stripe API in CI. STRIPE's dispatch logic is already covered by `OrderServiceTest.onlinePaymentCheckoutReservesStockAndCallsGateway` (Task 15, mocked `PaymentService`) and its signing/parsing logic by `StripeGatewayTest` (Task 12, mocked SDK statics) — that's full coverage without a live network dependency.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/controller/OrderControllerIT.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;

    // Thứ tự xoá theo FK - xem Global Constraints. orderRepository.deleteAll() cascade xoá
    // order_items/payments; productRepository.deleteAll() cascade xoá variants/images. Bắt buộc có
    // đủ 7 dòng này, KHÔNG chỉ userRepository.deleteAll(), vì class này có nhiều @Test method chạy
    // lại @BeforeEach mỗi lần - thiếu 1 dòng là @Test method thứ 2 ném DataIntegrityViolationException.
    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        categoryRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .name("Admin").email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getEmail(), admin.getRole().name());
    }

    @Test
    void codCheckoutCreatesConfirmedOrderAndDeductsStockDirectly() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 2);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentMethod").value("COD"))
                .andExpect(jsonPath("$.totalAmount").value(2 * 19.99))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.paymentInit").isEmpty());
    }

    @Test
    void vnpayCheckoutReservesStockAndReturnsRedirectUrl() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer2@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 3);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.VNPAY));

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.paymentInit.redirectUrl").value(org.hamcrest.Matchers.containsString("vnp_TxnRef=")));
    }

    @Test
    void checkoutWithInsufficientStockReturns409() throws Exception {
        Long variantId = seedVariant(1);
        String token = registerUser("buyer3@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 5);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));

        mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    // Bài test BẮT BUỘC theo spec §13: 2 user cùng giành mua đơn vị hàng CUỐI CÙNG (stock=1) của
    // CÙNG 1 variant, bắn request gần như đồng thời bằng 2 thread thật. Đây là bài test duy nhất
    // thực sự "vận hành" cơ chế PESSIMISTIC_WRITE (findByIdForUpdate, Task 3) - không có nó,
    // insufficientStockThrows... (Task 15) chỉ chứng minh logic đúng tuần tự, KHÔNG chứng minh khoá
    // chống race thật khi 2 request chạy song song.
    @Test
    void concurrentCheckoutOnLastUnitOnlyOneSucceeds() throws Exception {
        Long variantId = seedVariant(1);

        String tokenA = registerUser("racer-a@example.com");
        String tokenB = registerUser("racer-b@example.com");
        Long addressA = createAddress(tokenA);
        Long addressB = createAddress(tokenB);
        Long cartItemA = addToCart(tokenA, variantId, 1);
        Long cartItemB = addToCart(tokenB, variantId, 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Integer> checkoutA = () -> performCheckout(tokenA, cartItemA, addressA);
        Callable<Integer> checkoutB = () -> performCheckout(tokenB, cartItemB, addressB);

        List<Future<Integer>> futures = executor.invokeAll(List.of(checkoutA, checkoutB));
        executor.shutdown();

        List<Integer> statusCodes = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statusCodes.add(future.get());
        }

        assertThat(statusCodes).containsExactlyInAnyOrder(201, 409);
    }

    private int performCheckout(String token, Long cartItemId, Long addressId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        return mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private String registerUser(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                new com.example.backend.dto.RegisterRequest("Buyer", email, "password123", null));
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private Long createAddress(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Buyer", "0900000000", "123 Main St", null, null, null));
        String response = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private Long addToCart(String token, Long variantId, int quantity) throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(variantId, quantity));
        String response = mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("items").get(0).get("id").asLong();
    }

    private Long seedVariant(int stock) throws Exception {
        String categoryBody = objectMapper.writeValueAsString(new CategoryRequest("Fashion", "fashion-" + stock + "-" + System.nanoTime(), "Clothes"));
        String categoryResponse = mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = objectMapper.readTree(categoryResponse).get("id").asLong();

        String slug = "t-shirt-" + System.nanoTime();
        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", slug, "Basic tee", null));
        String productResponse = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).get("id").asLong();

        String sku = "TSHIRT-" + System.nanoTime();
        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest(sku, "M", "Black", new BigDecimal("19.99"), stock));
        String variantResponse = mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(variantResponse).get("variants").get(0).get("id").asLong();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderControllerIT`
Expected: FAIL — `OrderController` doesn't exist yet (404 on `/api/orders/checkout` instead of 201/409).

- [ ] **Step 3: Implement `OrderController`**

Create `src/main/java/com/example/backend/controller/OrderController.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderResponse;
import com.example.backend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal UserDetails userDetails,
                                                    @Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.checkout(userDetails.getUsername(), request));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderControllerIT`
Expected: PASS (all four tests). The concurrency test is inherently timing-sensitive but deterministic in outcome — with `stock=1` and a pessimistic lock, it is mathematically impossible for both requests to succeed; if it ever flakes, it means the lock isn't actually being acquired (check `findByIdForUpdate` is being called, not a plain `findById`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/backend/controller/OrderController.java \
        src/test/java/com/example/backend/controller/OrderControllerIT.java
git commit -m "feat: add checkout endpoint with COD/VNPay end-to-end tests and a concurrency test"
```

---

### Task 17: Payment webhook handling (OrderService.confirmPayment + PaymentWebhookController)

**Files:**
- Modify: `src/main/java/com/example/backend/service/OrderService.java`
- Create: `src/main/java/com/example/backend/controller/PaymentWebhookController.java`
- Modify: `src/main/java/com/example/backend/config/SecurityConfig.java`
- Test: `src/test/java/com/example/backend/controller/PaymentWebhookControllerIT.java`

**Interfaces:**
- Consumes: `PaymentService.parseWebhook` (Task 13), `PaymentWebhookResult` (Task 10), `OrderRepository`/`ProductVariantRepository` (already fields on `OrderService`), `PaymentConfirmedEvent`/`OrderCancelledEvent` (Task 9).
- Produces: `OrderService.confirmPayment(Long orderId, boolean success, String gatewayTransactionRef): void` and private helper `releaseReservation(Order)` (releases `reservedQuantity` for every item of a still-`PENDING_PAYMENT` order — reused as-is by Task 18's expiry scheduler, since an overdue order is always `PENDING_PAYMENT` too). `POST /api/payments/webhooks/vnpay`, `POST /api/payments/webhooks/stripe` (both public — added to `SecurityConfig`'s permit-all matcher; authenticity comes from signature verification inside `PaymentService`/`PaymentGateway`, not Spring Security).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/controller/PaymentWebhookControllerIT.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.PaymentStatus;
import com.example.backend.entity.ProductVariant;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.ProductVariantRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentWebhookControllerIT {

    // Trùng vnpay.hash-secret trong src/test/resources/application.properties (Task 1). Test ký
    // request IPN giả lập bằng thuật toán ĐỘC LẬP (không gọi lại VnPayGateway.buildQuery/hmacSha512
    // - 2 method đó package-private ở package khác) để verify hành vi đúng từ góc nhìn 1 client
    // ngoài thật (VNPay sandbox) sẽ gọi, không phải verify implementation detail nội bộ.
    private static final String VNPAY_HASH_SECRET = "TEST_HASH_SECRET_AT_LEAST_THIS_LONG";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductVariantRepository productVariantRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;

    // Thứ tự xoá theo FK - xem Global Constraints.
    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        categoryRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .name("Admin").email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getEmail(), admin.getRole().name());
    }

    @Test
    void validWebhookConfirmsOrderAndCommitsStockThenIgnoresSecondCall() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 3);
        Long orderId = checkoutVnPay(token, cartItemId, addressId);

        ProductVariant beforeWebhook = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(beforeWebhook.getReservedQuantity()).isEqualTo(3);
        assertThat(beforeWebhook.getStockQuantity()).isEqualTo(50);

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TxnRef", orderId.toString());
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionNo", "VNP123");
        String hash = sign(params);

        mockMvc.perform(post("/api/payments/webhooks/vnpay")
                        .param("vnp_TxnRef", orderId.toString())
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_TransactionNo", "VNP123")
                        .param("vnp_SecureHash", hash))
                .andExpect(status().isOk());

        Order confirmed = orderRepository.findById(orderId).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPayment().getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        ProductVariant afterWebhook = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(afterWebhook.getStockQuantity()).isEqualTo(47);
        assertThat(afterWebhook.getReservedQuantity()).isZero();

        // VNPay có thể retry IPN - order đã CONFIRMED nên lần gọi thứ 2 phải no-op, KHÔNG trừ thêm
        // stock lần 2 (đây chính là hành vi idempotent bắt buộc theo spec §6).
        mockMvc.perform(post("/api/payments/webhooks/vnpay")
                        .param("vnp_TxnRef", orderId.toString())
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_TransactionNo", "VNP123")
                        .param("vnp_SecureHash", hash))
                .andExpect(status().isOk());

        ProductVariant afterSecondCall = productVariantRepository.findById(variantId).orElseThrow();
        assertThat(afterSecondCall.getStockQuantity()).isEqualTo(47);
    }

    @Test
    void webhookWithInvalidSignatureReturns400AndDoesNotTouchOrder() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("buyer2@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 1);
        Long orderId = checkoutVnPay(token, cartItemId, addressId);

        mockMvc.perform(post("/api/payments/webhooks/vnpay")
                        .param("vnp_TxnRef", orderId.toString())
                        .param("vnp_ResponseCode", "00")
                        .param("vnp_SecureHash", "not-a-real-hash"))
                .andExpect(status().isBadRequest());

        Order stillPending = orderRepository.findById(orderId).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    private Long checkoutVnPay(String token, Long cartItemId, Long addressId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.VNPAY));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private String sign(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                 .append('=')
                 .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        try {
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            hmac512.init(new SecretKeySpec(VNPAY_HASH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] result = hmac512.doFinal(query.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String registerUser(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("Buyer", email, "password123", null));
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private Long createAddress(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Buyer", "0900000000", "123 Main St", null, null, null));
        String response = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private Long addToCart(String token, Long variantId, int quantity) throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(variantId, quantity));
        String response = mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("items").get(0).get("id").asLong();
    }

    private Long seedVariant(int stock) throws Exception {
        String categoryBody = objectMapper.writeValueAsString(
                new CategoryRequest("Fashion", "fashion-" + System.nanoTime(), "Clothes"));
        String categoryResponse = mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = objectMapper.readTree(categoryResponse).get("id").asLong();

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt-" + System.nanoTime(), "Basic tee", null));
        String productResponse = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).get("id").asLong();

        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest("TSHIRT-" + System.nanoTime(), "M", "Black", new BigDecimal("19.99"), stock));
        String variantResponse = mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(variantResponse).get("variants").get(0).get("id").asLong();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PaymentWebhookControllerIT`
Expected: FAIL — `/api/payments/webhooks/vnpay` doesn't exist yet (404), and it isn't public yet either.

- [ ] **Step 3: Add `confirmPayment` to `OrderService`**

Add these imports to `src/main/java/com/example/backend/service/OrderService.java` (alongside the existing ones):

```java
import com.example.backend.entity.OrderItem;
import com.example.backend.event.PaymentConfirmedEvent;
import com.example.backend.event.OrderCancelledEvent;
```

Add this method to the class (after `checkout`):

```java
    // Idempotent theo thiết kế: order != PENDING_PAYMENT nghĩa là webhook này đã được xử lý rồi
    // (gọi 2 lần) hoặc order đã bị expire (Task 18) - no-op, KHÔNG ném lỗi, để controller luôn trả
    // 200 cho gateway (gateway có retry thêm cũng không đổi được gì). Xem spec §6.
    @Transactional
    public void confirmPayment(Long orderId, boolean success, String gatewayTransactionRef) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return;
        }

        if (success) {
            for (OrderItem item : order.getItems()) {
                ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
                variant.setStockQuantity(variant.getStockQuantity() - item.getQuantity());
                variant.setReservedQuantity(variant.getReservedQuantity() - item.getQuantity());
                productVariantRepository.save(variant);
            }
            order.setStatus(OrderStatus.CONFIRMED);
            order.getPayment().setStatus(PaymentStatus.SUCCESS);
            order.getPayment().setPaidAt(Instant.now());
            order.getPayment().setGatewayTransactionRef(gatewayTransactionRef);
            orderRepository.save(order);
            eventPublisher.publishEvent(new PaymentConfirmedEvent(order.getId()));
        } else {
            releaseReservation(order);
            order.setStatus(OrderStatus.CANCELLED);
            order.getPayment().setStatus(PaymentStatus.FAILED);
            order.getPayment().setGatewayTransactionRef(gatewayTransactionRef);
            orderRepository.save(order);
            eventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), "PAYMENT_FAILED"));
        }
    }

    // Hoàn reservedQuantity cho 1 order CÒN Ở TRẠNG THÁI PENDING_PAYMENT (chưa từng trừ
    // stockQuantity thật). Dùng ở đây (webhook báo fail) và ở Task 18 (expiry scheduler) - cả 2 chỗ
    // đều CHỈ xử lý order đang PENDING_PAYMENT nên dùng chung được, không cần phân nhánh CONFIRMED
    // (đó là việc của cancel() ở Task 20, có helper riêng vì phải hoàn stockQuantity thay vì reserved).
    private void releaseReservation(Order order) {
        for (OrderItem item : order.getItems()) {
            ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
            variant.setReservedQuantity(variant.getReservedQuantity() - item.getQuantity());
            productVariantRepository.save(variant);
        }
    }
```

- [ ] **Step 4: Implement `PaymentWebhookController`**

Create `src/main/java/com/example/backend/controller/PaymentWebhookController.java`:

```java
package com.example.backend.controller;

import com.example.backend.entity.PaymentMethod;
import com.example.backend.service.OrderService;
import com.example.backend.service.PaymentService;
import com.example.backend.service.payment.PaymentWebhookResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Public theo SecurityConfig (Step 5) - KHÔNG qua JWT. Xác thực request đến từ đúng gateway (VNPay/
// Stripe) nằm ở tầng chữ ký (PaymentService.parseWebhook -> PaymentGateway.parseWebhook), không
// phải ở tầng Spring Security.
@RestController
@RequestMapping("/api/payments/webhooks")
public class PaymentWebhookController {

    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentWebhookController(PaymentService paymentService, OrderService orderService) {
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @PostMapping("/vnpay")
    public ResponseEntity<String> vnpayWebhook(HttpServletRequest request) {
        return handle(PaymentMethod.VNPAY, request, null);
    }

    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(HttpServletRequest request, @RequestBody String rawBody) {
        return handle(PaymentMethod.STRIPE, request, rawBody);
    }

    private ResponseEntity<String> handle(PaymentMethod method, HttpServletRequest request, String rawBody) {
        PaymentWebhookResult result = paymentService.parseWebhook(method, request, rawBody);
        if (result == null) {
            return ResponseEntity.badRequest().body("invalid signature");
        }
        orderService.confirmPayment(result.orderId(), result.success(), result.gatewayTransactionRef());
        return ResponseEntity.ok("OK");
    }
}
```

- [ ] **Step 5: Make the webhook paths public in `SecurityConfig`**

In `src/main/java/com/example/backend/config/SecurityConfig.java`, change:

```java
                        .requestMatchers("/api/auth/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
```

to:

```java
                        .requestMatchers("/api/auth/**", "/api/payments/webhooks/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=PaymentWebhookControllerIT`
Expected: PASS (both tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/backend/service/OrderService.java \
        src/main/java/com/example/backend/controller/PaymentWebhookController.java \
        src/main/java/com/example/backend/config/SecurityConfig.java \
        src/test/java/com/example/backend/controller/PaymentWebhookControllerIT.java
git commit -m "feat: add idempotent payment webhook handling for VNPay/Stripe"
```

---

### Task 18: Order expiry scheduler

**Files:**
- Modify: `src/main/java/com/example/backend/service/OrderService.java`
- Modify: `src/test/java/com/example/backend/service/OrderServiceTest.java`
- Create: `src/main/java/com/example/backend/scheduler/OrderExpiryScheduler.java`
- Test: `src/test/java/com/example/backend/scheduler/OrderExpirySchedulerTest.java`

**Interfaces:**
- Consumes: `OrderRepository.findByStatusAndExpiresAtBefore` (Task 8), `releaseReservation` (Task 17, reused as-is).
- Produces: `OrderService.expireOverdueOrders(): void`. `OrderExpiryScheduler` (`@Component`, `@Scheduled(fixedRate = 60_000)` method `expirePendingOrders()` delegating to it) — runs because `@EnableScheduling` is already on `BackendApplication` (Task 1).

- [ ] **Step 1: Write the failing tests**

Add this test method to `src/test/java/com/example/backend/service/OrderServiceTest.java` (add the extra imports at the top, then the method alongside the existing `@Test` methods):

```java
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderItem;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.Payment;
import com.example.backend.entity.PaymentStatus;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
```

```java
    @Test
    void expireOverdueOrdersReleasesReservationAndCancelsOrder() {
        ProductVariant variant = variant(200L, 50, 3);
        Order order = Order.builder().id(500L).status(OrderStatus.PENDING_PAYMENT).paymentMethod(PaymentMethod.VNPAY)
                .recipientName("Nhut").phone("0900000000").addressLine("123 Main St")
                .totalAmount(new BigDecimal("59.97")).expiresAt(Instant.now().minusSeconds(60)).build();
        order.addItem(OrderItem.builder().variant(variant).productName("T-Shirt").sku("TSHIRT-M")
                .unitPrice(new BigDecimal("19.99")).quantity(3).build());
        order.assignPayment(Payment.builder().gateway(PaymentMethod.VNPAY).status(PaymentStatus.PENDING).build());

        when(orderRepository.findByStatusAndExpiresAtBefore(eq(OrderStatus.PENDING_PAYMENT), any())).thenReturn(List.of(order));
        when(productVariantRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(variant));

        orderService.expireOverdueOrders();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(variant.getReservedQuantity()).isZero();
        verify(orderRepository).save(order);
    }
```

Note: the field is named `orderService` in this test class (from Task 15) — reuse it, don't redeclare.

Create `src/test/java/com/example/backend/scheduler/OrderExpirySchedulerTest.java`:

```java
package com.example.backend.scheduler;

import com.example.backend.service.OrderService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderExpirySchedulerTest {

    @Test
    void delegatesToOrderServiceExpireOverdueOrders() {
        OrderService orderService = mock(OrderService.class);
        OrderExpiryScheduler scheduler = new OrderExpiryScheduler(orderService);

        scheduler.expirePendingOrders();

        verify(orderService).expireOverdueOrders();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=OrderServiceTest,OrderExpirySchedulerTest`
Expected: FAIL to compile — `expireOverdueOrders()` and `OrderExpiryScheduler` don't exist yet.

- [ ] **Step 3: Add `expireOverdueOrders()` to `OrderService`**

Add this method to `src/main/java/com/example/backend/service/OrderService.java` (after `confirmPayment`/`releaseReservation` from Task 17):

```java
    // Quét bởi OrderExpiryScheduler mỗi 60s (Task 18). Mọi order trả về ở đây LUÔN đang
    // PENDING_PAYMENT (điều kiện của query) nên tái dùng releaseReservation() y hệt nhánh "payment
    // failed" của confirmPayment() - không cần phân nhánh CONFIRMED (đó là việc của cancel(), Task 20).
    @Transactional
    public void expireOverdueOrders() {
        List<Order> overdue = orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, Instant.now());
        for (Order order : overdue) {
            releaseReservation(order);
            order.setStatus(OrderStatus.CANCELLED);
            order.getPayment().setStatus(PaymentStatus.FAILED);
            orderRepository.save(order);
            eventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), "EXPIRED"));
        }
    }
```

- [ ] **Step 4: Implement `OrderExpiryScheduler`**

Create `src/main/java/com/example/backend/scheduler/OrderExpiryScheduler.java`:

```java
package com.example.backend.scheduler;

import com.example.backend.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// @EnableScheduling đã bật ở BackendApplication (Task 1). fixedRate=60_000: quét mỗi 60s - đủ nhanh
// so với order.payment-expiry-minutes=15 (Task 1), không cần chính xác tới giây.
@Component
public class OrderExpiryScheduler {

    private final OrderService orderService;

    public OrderExpiryScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedRate = 60_000)
    public void expirePendingOrders() {
        orderService.expireOverdueOrders();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=OrderServiceTest,OrderExpirySchedulerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/service/OrderService.java \
        src/test/java/com/example/backend/service/OrderServiceTest.java \
        src/main/java/com/example/backend/scheduler/OrderExpiryScheduler.java \
        src/test/java/com/example/backend/scheduler/OrderExpirySchedulerTest.java
git commit -m "feat: auto-cancel overdue PENDING_PAYMENT orders and release their reservation"
```

---

### Task 19: List/detail/payment-status endpoints

**Files:**
- Modify: `src/main/java/com/example/backend/service/OrderService.java`
- Modify: `src/main/java/com/example/backend/controller/OrderController.java`
- Modify: `src/test/java/com/example/backend/controller/OrderControllerIT.java`

**Interfaces:**
- Consumes: `OrderRepository.findByUserId`/`findByIdAndUserId` (Task 8), `PaymentResponse` (Task 14).
- Produces: `OrderService.getOrders(String email, Pageable): Page<OrderResponse>`, `.getOrder(String email, Long): OrderResponse`, `.getPayment(String email, Long): PaymentResponse`. Routes: `GET /api/orders` (paginated, own orders only), `GET /api/orders/{id}` (own order detail, 404 if not owner), `GET /api/orders/{id}/payment` (own order's payment status, for client-side polling after checkout — see spec §5).

- [ ] **Step 1: Write the failing test**

Add this test method to `src/test/java/com/example/backend/controller/OrderControllerIT.java` (add `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;` alongside the existing static imports):

```java
    @Test
    void listsOnlyOwnOrdersAndGetsDetailAndPayment() throws Exception {
        Long variantId = seedVariant(50);
        String tokenA = registerUser("owner@example.com");
        String tokenB = registerUser("stranger@example.com");
        Long addressA = createAddress(tokenA);
        Long cartItemA = addToCart(tokenA, variantId, 1);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemA), addressA, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));

        mockMvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/orders/{id}/payment", orderId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway").value("COD"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderControllerIT`
Expected: FAIL — `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/payment` don't exist yet (404).

- [ ] **Step 3: Add the read methods to `OrderService`**

Add these imports to `src/main/java/com/example/backend/service/OrderService.java`:

```java
import com.example.backend.dto.PaymentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

Add these methods (after `checkout`):

```java
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(String email, Pageable pageable) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return orderRepository.findByUserId(user.getId(), pageable).map(order -> OrderResponse.from(order, null));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String email, Long orderId) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return OrderResponse.from(order, null);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String email, Long orderId) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return PaymentResponse.from(order.getPayment());
    }
```

- [ ] **Step 4: Add the routes to `OrderController`**

Add these imports to `src/main/java/com/example/backend/controller/OrderController.java`:

```java
import com.example.backend.dto.PaymentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
```

Add these methods (after the constructor, before `checkout`):

```java
    @GetMapping
    public Page<OrderResponse> list(@AuthenticationPrincipal UserDetails userDetails,
                                     @PageableDefault(size = 20) Pageable pageable) {
        return orderService.getOrders(userDetails.getUsername(), pageable);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return orderService.getOrder(userDetails.getUsername(), id);
    }

    @GetMapping("/{id}/payment")
    public PaymentResponse getPayment(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return orderService.getPayment(userDetails.getUsername(), id);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderControllerIT`
Expected: PASS (all tests in the class).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/service/OrderService.java \
        src/main/java/com/example/backend/controller/OrderController.java \
        src/test/java/com/example/backend/controller/OrderControllerIT.java
git commit -m "feat: add order list/detail/payment-status endpoints scoped to the owner"
```

---

### Task 20: User-initiated order cancellation

**Files:**
- Modify: `src/main/java/com/example/backend/service/OrderService.java`
- Modify: `src/main/java/com/example/backend/controller/OrderController.java`
- Modify: `src/test/java/com/example/backend/controller/OrderControllerIT.java`

**Interfaces:**
- Consumes: `InvalidOrderStateException` (Task 2), `releaseReservation` (Task 17, reused for the `PENDING_PAYMENT` branch).
- Produces: `OrderService.cancel(String email, Long orderId): OrderResponse` and private helper `restoreStock(Order)` (adds `quantity` back onto `stockQuantity` — the `CONFIRMED` branch, where stock was already committed for real, unlike `releaseReservation`'s `reservedQuantity`-only rollback). Route: `POST /api/orders/{id}/cancel` — allowed while status is `PENDING_PAYMENT` or `CONFIRMED`, 409 otherwise (already `SHIPPED`/`DELIVERED`/`CANCELLED`).

- [ ] **Step 1: Write the failing tests**

Add `@Autowired private ProductVariantRepository productVariantRepository;` as a field to `src/test/java/com/example/backend/controller/OrderControllerIT.java` (`orderRepository` is already a field there from Task 16's cleanup fix — don't redeclare it), plus these imports:

```java
import com.example.backend.entity.Order;
import com.example.backend.entity.OrderStatus;
import com.example.backend.repository.ProductVariantRepository;

import static org.assertj.core.api.Assertions.assertThat;
```

Then add these three test methods:

```java
    @Test
    void cancelsPendingPaymentOrderAndReleasesReservation() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("canceler@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 3);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.VNPAY));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getReservedQuantity()).isEqualTo(3);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getReservedQuantity()).isZero();
        assertThat(productVariantRepository.findById(variantId).orElseThrow().getStockQuantity()).isEqualTo(50);
    }

    @Test
    void cancelsConfirmedCodOrderAndRestoresStock() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("canceler2@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 4);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getStockQuantity()).isEqualTo(46);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(productVariantRepository.findById(variantId).orElseThrow().getStockQuantity()).isEqualTo(50);
    }

    @Test
    void cannotCancelShippedOrder() throws Exception {
        Long variantId = seedVariant(50);
        String token = registerUser("canceler3@example.com");
        Long addressId = createAddress(token);
        Long cartItemId = addToCart(token, variantId, 1);

        String body = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        // Nhảy thẳng status = SHIPPED bằng repository (endpoint admin advanceStatus tới ở Task 21,
        // chưa tồn tại lúc này) - test này chỉ quan tâm hành vi cancel(), không quan tâm advance().
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.SHIPPED);
        orderRepository.save(order);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderControllerIT`
Expected: FAIL — `POST /api/orders/{id}/cancel` doesn't exist yet (404).

- [ ] **Step 3: Add `cancel()` to `OrderService`**

Add this import to `src/main/java/com/example/backend/service/OrderService.java`:

```java
import com.example.backend.exception.InvalidOrderStateException;
```

Add these methods (after `getPayment`, from Task 19):

```java
    @Transactional
    public OrderResponse cancel(String email, Long orderId) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Order order = orderRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException("Order cannot be cancelled in status " + order.getStatus());
        }

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            releaseReservation(order);
        } else {
            restoreStock(order);
        }

        order.setStatus(OrderStatus.CANCELLED);
        if (order.getPayment().getStatus() == PaymentStatus.PENDING) {
            order.getPayment().setStatus(PaymentStatus.FAILED);
        }
        orderRepository.save(order);

        eventPublisher.publishEvent(new OrderCancelledEvent(order.getId(), "USER_CANCELLED"));
        return OrderResponse.from(order, null);
    }

    // CONFIRMED nghĩa là stockQuantity đã bị trừ THẬT (COD lúc checkout, hoặc online sau khi
    // confirmPayment) - hoàn ngược lại stockQuantity, KHÁC với releaseReservation() (chỉ hoàn
    // reservedQuantity cho order còn PENDING_PAYMENT, chưa từng trừ thật).
    private void restoreStock(Order order) {
        for (OrderItem item : order.getItems()) {
            ProductVariant variant = productVariantRepository.findByIdForUpdate(item.getVariant().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product variant not found"));
            variant.setStockQuantity(variant.getStockQuantity() + item.getQuantity());
            productVariantRepository.save(variant);
        }
    }
```

- [ ] **Step 4: Add the route to `OrderController`**

Add this method to `src/main/java/com/example/backend/controller/OrderController.java` (after `checkout`):

```java
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return orderService.cancel(userDetails.getUsername(), id);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderControllerIT`
Expected: PASS (all tests in the class).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/backend/service/OrderService.java \
        src/main/java/com/example/backend/controller/OrderController.java \
        src/test/java/com/example/backend/controller/OrderControllerIT.java
git commit -m "feat: allow customers to cancel their own PENDING_PAYMENT/CONFIRMED orders"
```

---

### Task 21: Admin order listing and status advancement

**Files:**
- Modify: `src/main/java/com/example/backend/service/OrderService.java`
- Create: `src/main/java/com/example/backend/controller/AdminOrderController.java`
- Test: `src/test/java/com/example/backend/controller/AdminOrderControllerIT.java`

**Interfaces:**
- Consumes: `OrderRepository.findByStatus`/`findAll` (Task 8), `InvalidOrderStateException` (Task 2), `OrderStatusUpdateRequest` (Task 14).
- Produces: `OrderService.listAllOrders(OrderStatus status, Pageable): Page<OrderResponse>` (`status` nullable — same "branch instead of dynamic query" style as `ProductService.list`), `.advanceStatus(Long orderId, OrderStatus target): OrderResponse` (only `CONFIRMED→SHIPPED` and `SHIPPED→DELIVERED` are valid; anything else is 409 — no skipping stages, per spec §5). Routes: `GET /api/admin/orders?status=`, `PUT /api/admin/orders/{id}/status` — both `ADMIN`-only via `@PreAuthorize`, same pattern as `ProductController`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/backend/controller/AdminOrderControllerIT.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.CartItemAddRequest;
import com.example.backend.dto.CategoryRequest;
import com.example.backend.dto.CheckoutRequest;
import com.example.backend.dto.OrderStatusUpdateRequest;
import com.example.backend.dto.ProductRequest;
import com.example.backend.dto.ProductVariantRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.entity.OrderStatus;
import com.example.backend.entity.PaymentMethod;
import com.example.backend.entity.Role;
import com.example.backend.entity.User;
import com.example.backend.repository.AddressRepository;
import com.example.backend.repository.CartItemRepository;
import com.example.backend.repository.CartRepository;
import com.example.backend.repository.CategoryRepository;
import com.example.backend.repository.OrderRepository;
import com.example.backend.repository.ProductRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.JwtService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminOrderControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;

    // Thứ tự xoá theo FK - xem Global Constraints. Đặc biệt quan trọng ở class này: test
    // adminListsFiltersByStatus... assert $.content.length() == 1 trên 1 query KHÔNG lọc theo user
    // (admin xem toàn bộ order) - còn sót 1 order CONFIRMED nào từ lượt test trước là assertion sai
    // ngay, không phải lỗi logic advanceStatus/listAllOrders.
    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        categoryRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .name("Admin").email("admin@example.com")
                .passwordHash(passwordEncoder.encode("password123")).role(Role.ADMIN).build());
        adminToken = jwtService.generateToken(admin.getEmail(), admin.getRole().name());
    }

    @Test
    void nonAdminCannotListOrAdvanceOrders() throws Exception {
        String customerToken = registerUser("cust@example.com");

        mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        String body = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.SHIPPED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", 1L)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListsFiltersByStatusAndAdvancesConfirmedToShippedToDelivered() throws Exception {
        Long variantId = seedVariant(50);
        String customerToken = registerUser("cust2@example.com");
        Long addressId = createAddress(customerToken);
        Long cartItemId = addToCart(customerToken, variantId, 1);

        String checkoutBody = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(get("/api/admin/orders").param("status", "CONFIRMED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));

        String toShipped = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.SHIPPED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toShipped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        String toDelivered = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.DELIVERED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toDelivered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void adminCannotSkipShippedStage() throws Exception {
        Long variantId = seedVariant(50);
        String customerToken = registerUser("cust3@example.com");
        Long addressId = createAddress(customerToken);
        Long cartItemId = addToCart(customerToken, variantId, 1);

        String checkoutBody = objectMapper.writeValueAsString(new CheckoutRequest(List.of(cartItemId), addressId, PaymentMethod.COD));
        String response = mockMvc.perform(post("/api/orders/checkout")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody))
                .andReturn().getResponse().getContentAsString();
        Long orderId = objectMapper.readTree(response).get("id").asLong();

        String toDelivered = objectMapper.writeValueAsString(new OrderStatusUpdateRequest(OrderStatus.DELIVERED));
        mockMvc.perform(put("/api/admin/orders/{id}/status", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toDelivered))
                .andExpect(status().isConflict());
    }

    private String registerUser(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("Buyer", email, "password123", null));
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private Long createAddress(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddressRequest("Buyer", "0900000000", "123 Main St", null, null, null));
        String response = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private Long addToCart(String token, Long variantId, int quantity) throws Exception {
        String body = objectMapper.writeValueAsString(new CartItemAddRequest(variantId, quantity));
        String response = mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("items").get(0).get("id").asLong();
    }

    private Long seedVariant(int stock) throws Exception {
        String categoryBody = objectMapper.writeValueAsString(
                new CategoryRequest("Fashion", "fashion-" + System.nanoTime(), "Clothes"));
        String categoryResponse = mockMvc.perform(post("/api/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = objectMapper.readTree(categoryResponse).get("id").asLong();

        String productBody = objectMapper.writeValueAsString(
                new ProductRequest(categoryId, "T-Shirt", "t-shirt-" + System.nanoTime(), "Basic tee", null));
        String productResponse = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody))
                .andReturn().getResponse().getContentAsString();
        Long productId = objectMapper.readTree(productResponse).get("id").asLong();

        String variantBody = objectMapper.writeValueAsString(
                new ProductVariantRequest("TSHIRT-" + System.nanoTime(), "M", "Black", new BigDecimal("19.99"), stock));
        String variantResponse = mockMvc.perform(post("/api/products/{id}/variants", productId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(variantBody))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(variantResponse).get("variants").get(0).get("id").asLong();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AdminOrderControllerIT`
Expected: FAIL — `/api/admin/orders` doesn't exist yet (404, not 403/200).

- [ ] **Step 3: Add `listAllOrders`/`advanceStatus` to `OrderService`**

Add these methods to `src/main/java/com/example/backend/service/OrderService.java` (after `cancel`, from Task 20):

```java
    @Transactional(readOnly = true)
    public Page<OrderResponse> listAllOrders(OrderStatus status, Pageable pageable) {
        Page<Order> page = status != null ? orderRepository.findByStatus(status, pageable) : orderRepository.findAll(pageable);
        return page.map(order -> OrderResponse.from(order, null));
    }

    @Transactional
    public OrderResponse advanceStatus(Long orderId, OrderStatus targetStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        boolean validTransition = (order.getStatus() == OrderStatus.CONFIRMED && targetStatus == OrderStatus.SHIPPED)
                || (order.getStatus() == OrderStatus.SHIPPED && targetStatus == OrderStatus.DELIVERED);
        if (!validTransition) {
            throw new InvalidOrderStateException("Cannot move order from " + order.getStatus() + " to " + targetStatus);
        }

        order.setStatus(targetStatus);
        return OrderResponse.from(orderRepository.save(order), null);
    }
```

- [ ] **Step 4: Implement `AdminOrderController`**

Create `src/main/java/com/example/backend/controller/AdminOrderController.java`:

```java
package com.example.backend.controller;

import com.example.backend.dto.OrderResponse;
import com.example.backend.dto.OrderStatusUpdateRequest;
import com.example.backend.entity.OrderStatus;
import com.example.backend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponse> list(@RequestParam(required = false) OrderStatus status,
                                     @PageableDefault(size = 20) Pageable pageable) {
        return orderService.listAllOrders(status, pageable);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse advanceStatus(@PathVariable Long id, @Valid @RequestBody OrderStatusUpdateRequest request) {
        return orderService.advanceStatus(id, request.status());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=AdminOrderControllerIT`
Expected: PASS (all three tests).

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS — every test across all 21 tasks passes together (this is the first time everything runs as one suite; a passing individual task can still collide with another over shared state like `System.nanoTime()`-suffixed slugs already handles uniqueness, but re-run once to confirm no cross-test leakage).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/backend/service/OrderService.java \
        src/main/java/com/example/backend/controller/AdminOrderController.java \
        src/test/java/com/example/backend/controller/AdminOrderControllerIT.java
git commit -m "feat: add admin order listing and CONFIRMED->SHIPPED->DELIVERED status advancement"
```

---

## Plan Self-Review

**Spec coverage:** §4 data model — every table has an entity + repository (Tasks 3, 4, 6, 8; `OrderItemRepository`/`PaymentRepository` deliberately omitted, see Task 8's deviation note). §5 API surface — every listed route exists: Address CRUD (Task 5), Cart (Task 7), checkout/list/detail/cancel (Tasks 16, 19, 20), admin orders (Task 21), webhooks (Task 17). §6 flow — COD/online branch, reservation, webhook idempotency, expiry, user cancel all implemented with the exact mechanics described (Tasks 15, 17, 18, 20). §7 gateway abstraction — interface + 3 implementations + dispatcher (Tasks 10–13). §8 events — implemented (Task 9). §9 security — webhook public path, admin `@PreAuthorize`, ownership checks throughout. §10 error handling — both new exceptions wired (Task 2). §11 config — all properties added (Task 1). §13 testing — the required concurrency test is in Task 16; webhook idempotency in Task 17; expiry in Task 18.

**Placeholder scan:** no TBD/TODO in any task. The two explicit `CHANGE_ME` config values (Task 1) are intentional per the spec's own decision (no sandbox credentials yet) and are documented with exactly where to get real ones, not left vague.

**Type consistency:** `OrderService`'s constructor signature is fixed in Task 15 and never changes — Tasks 17–21 only add methods/private helpers, verified against each other's field names (`orderRepository`, `productVariantRepository`, `eventPublisher`, etc. — same names throughout). `PaymentInitResult`/`PaymentWebhookResult`/`OrderResponse`/`PaymentResponse` field names match between their `dto`/`service.payment` definitions and every call site. `releaseReservation` (Task 17) is reused verbatim by Task 18 and partially by Task 20 (which adds its own `restoreStock` for the `CONFIRMED` branch) — no drift between them.

**Scope:** this is one cohesive checkout flow (Cart → Address → Order → Payment → Inventory), not an arbitrary bundle of unrelated features — matches the brainstorming session's decision to keep it as a single spec/plan rather than decomposing further.

