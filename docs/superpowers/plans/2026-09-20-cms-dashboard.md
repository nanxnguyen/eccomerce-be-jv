# CMS Dashboard APIs Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` to implement task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Provide a secure `/api/cms/**` API surface for staff to manage the existing catalog/orders and inspect sales performance without breaking the current API clients.

**Architecture:** Keep the existing Spring Boot monolith, services, entities, and database. Add thin CMS controllers under `/api/cms/**` that delegate to existing business services, then add read-only PostgreSQL aggregates for dashboard metrics. Existing route shapes remain backwards-compatible aliases until clients migrate.

**Tech Stack:** Java 17, Spring Boot 4, Spring Security method authorization, Spring Data JPA, PostgreSQL, Flyway only if query-plan evidence requires a new index, JUnit/Spring MVC integration tests.

**Spec:** `docs/superpowers/specs/2026-09-20-cms-dashboard-design.md`

## Global Constraints

- Every canonical CMS route starts with `/api/cms/`.
- Every CMS endpoint requires `ROLE_ADMIN`; keep current Keycloak and legacy JWT role mapping.
- Keep `GET /api/products/**` and `GET /api/categories/**` public behavior unchanged.
- Keep current product, category, and order business rules by delegating to existing services.
- Revenue is successful payment value grouped by `Payment.paidAt`, start-inclusive/end-exclusive, calculated in UTC using PostgreSQL `numeric`/Java `BigDecimal`.
- Do not delete or rewrite historical data; do not edit applied Flyway migrations.
- Do not add dependencies, a separate service, cache, or event pipeline for dashboard queries.

## Review Focus

- A customer or anonymous caller reaching any `/api/cms/**` route must get 401/403 and no data; cover with controller integration tests.
- A legacy customer JWT and Keycloak `CUSTOMER` token must not gain admin access; cover authorization tests.
- Pending/failed payments and payment timestamps exactly at `to` must not count as paid revenue; cover aggregate tests.
- COD orders currently have successful payment status at checkout; include them by `paidAt` and explicitly name the metric `paidRevenue`.
- Editing product name/price later must not rewrite historical order revenue; aggregate using `orders.total_amount` and `order_items` snapshots where item reports are later added.

---

### Task 1: Publish canonical CMS product, category, and order routes

**Files:**
- Create: `src/main/java/com/example/backend/controller/CmsProductController.java`
- Create: `src/main/java/com/example/backend/controller/CmsCategoryController.java`
- Create: `src/main/java/com/example/backend/controller/CmsOrderController.java`
- Modify: `src/test/java/com/example/backend/controller/ProductControllerIT.java`
- Modify: `src/test/java/com/example/backend/controller/CategoryControllerIT.java`
- Modify: `src/test/java/com/example/backend/controller/AdminOrderControllerIT.java`
- Create: `src/test/java/com/example/backend/controller/CmsApiAuthorizationIT.java`
- Modify: `docs/architecture-roadmap.md` or API guide section in `docs/superpowers/specs/2026-09-20-cms-dashboard-design.md`

**Interfaces:**
- Consumes: Existing `ProductService`, `CategoryService`, `OrderService` methods and DTOs; existing `/api/products`, `/api/categories`, `/api/admin/orders` behavior.
- Produces: `/api/cms/products` list/detail/create/update/delete/variant/image routes; `/api/cms/categories` tree/detail/create/update/delete routes; `/api/cms/orders` list/status routes. All are guarded with `@PreAuthorize("hasRole('ADMIN')")`.

- [ ] **Step 1: Add integration tests for canonical route behavior**
  - Verify `GET /api/cms/products` includes active and inactive products, is paged, and requires ADMIN.
  - Verify CMS product writes produce the same response/status and validation behavior as existing product writes.
  - Verify CMS category tree and writes delegate to the existing category service behavior.
  - Verify CMS order list/status route preserves current status filtering and only permits the existing legal transitions.
  - Verify anonymous and CUSTOMER callers receive 401/403 for every CMS resource family.
  - Verify existing routes continue to return their current responses for existing integration tests.
- [ ] **Step 2: Run the focused integration tests and confirm expected failures**
  - Run: `./mvnw -q -Dtest=ProductControllerIT,CategoryControllerIT,AdminOrderControllerIT,CmsApiAuthorizationIT test`
  - Expected: new `/api/cms/**` route tests fail until controllers exist; existing route tests remain green.
- [ ] **Step 3: Add thin CMS controllers**
  - Map each canonical route to the corresponding existing service method; do not duplicate product/category/order rules.
  - For CMS product listing, add a `ProductService.listAdmin(...)` method using the existing repository and DTO mapping but without the public `ACTIVE` filter. Support status/category/search and pageable sort.
  - Use `201 Created` for resource creation, `200 OK` for reads/updates, and `204 No Content` for deletes, matching existing route semantics.
  - Keep current routes in place as legacy aliases; do not remove or redirect them.
- [ ] **Step 4: Run focused tests and document route transition**
  - Run the focused test command above.
  - Document `/api/cms/**` as canonical and mark the existing write/admin paths as compatibility routes.

### Task 2: Add dashboard summary metrics

**Files:**
- Create: `src/main/java/com/example/backend/controller/CmsDashboardController.java`
- Create: `src/main/java/com/example/backend/service/CmsDashboardService.java`
- Create: dashboard response records under `src/main/java/com/example/backend/dto/`
- Modify: `src/main/java/com/example/backend/repository/OrderRepository.java`
- Modify: product/inventory repositories only for focused count queries
- Create: `src/test/java/com/example/backend/controller/CmsDashboardControllerIT.java`
- Create: `src/test/java/com/example/backend/repository/CmsDashboardRepositoryIT.java`

**Interfaces:**
- Produces: `GET /api/cms/dashboard/summary?from=&to=`; response fields `from`, `to`, `generatedAt`, `paidRevenue`, `paidOrderCount`, `averagePaidOrderValue`, `ordersByStatus`, `productCount`, and `lowStockVariantCount`.
- Uses a shared interval validator: default `[now - 30 days, now)`, require `from < to`, and reject ranges over 366 days.
- Order status counts represent orders created in the same `[from,to)` interval; paid metrics use `Payment.paidAt` in that interval.

- [ ] **Step 1: Write PostgreSQL-backed aggregate tests**
  - Seed successful, pending, and failed payments with known timestamps and monetary totals; include fractional amounts and a payment exactly on each interval boundary.
  - Assert only successful payments with `paidAt >= from AND paidAt < to` contribute to revenue/count/average.
  - Assert status counts use `orders.created_at` interval, empty interval yields zero values, and COD paid at checkout is counted.
- [ ] **Step 2: Run tests and confirm expected failures**
  - Run the new repository/service tests with the project PostgreSQL test profile.
  - Expected: compile/test failure until aggregate projections and service exist.
- [ ] **Step 3: Add aggregate projections and service**
  - Add small typed projection(s) and PostgreSQL aggregate queries; return `BigDecimal` for money and `long` for counts.
  - Avoid loading order entities or payments into memory; keep the metrics read-only and in one transaction snapshot where possible.
  - Calculate average as `paidRevenue / paidOrderCount`, returning zero when count is zero; define currency scale/rounding explicitly in DTO documentation.
  - Count products and low-stock variants with count queries, not entity loads.
- [ ] **Step 4: Add secured controller and request validation**
  - Apply shared interval defaults and reject reversed, malformed, or overlong ranges with the standard 400 response.
  - Require `ROLE_ADMIN`; include `from`, `to`, and one `generatedAt` in the response.
- [ ] **Step 5: Run focused tests**
  - Run: `./mvnw -q -Dtest=CmsDashboardControllerIT,CmsDashboardRepositoryIT test`
  - Expected: all summary, edge-boundary, empty-result, and access-control assertions pass.

### Task 3: Add revenue timeline for dashboard charts

**Files:**
- Modify: `src/main/java/com/example/backend/controller/CmsDashboardController.java`
- Modify: `src/main/java/com/example/backend/service/CmsDashboardService.java`
- Create/modify: revenue bucket DTO and PostgreSQL projection
- Modify: `src/main/java/com/example/backend/repository/OrderRepository.java`
- Modify: `src/test/java/com/example/backend/controller/CmsDashboardControllerIT.java`

**Interfaces:**
- Produces: `GET /api/cms/dashboard/revenue?from=&to=&granularity=day|week|month`.
- Response: `from`, `to`, `granularity`, ordered `buckets[]`; each bucket has UTC `bucketStart`, `paidRevenue`, and `paidOrderCount`.
- Default granularity is `day`; input is a closed enum. Return zero-filled buckets, capped at 366 buckets.

- [ ] **Step 1: Write tests for each granularity and range boundary**
  - Verify daily/weekly/monthly UTC bucket boundaries, ascending order, zero-filled intervals, payment inclusion rules, invalid granularity 400, and rejection when output would exceed 366 buckets.
  - Include first instant in range and payment exactly at exclusive `to`.
- [ ] **Step 2: Run tests and confirm expected failures**
  - Run: `./mvnw -q -Dtest=CmsDashboardControllerIT test`
  - Expected: new revenue route tests fail until timeline aggregation is implemented.
- [ ] **Step 3: Implement bounded PostgreSQL bucketing**
  - Whitelist granularity with an enum and pass only validated values to `date_trunc`; do not concatenate arbitrary user input into SQL.
  - Aggregate successful payments by UTC bucket in SQL and fill missing buckets with PostgreSQL `generate_series` or a bounded Java merge over the aggregate result.
  - Keep money arithmetic in PostgreSQL numeric/Java `BigDecimal`; cap buckets before running the query.
- [ ] **Step 4: Run chart API tests**
  - Run the focused dashboard integration tests; ensure the assertions cover PostgreSQL rather than H2-only date behavior.

### Task 4: Complete CMS inventory warnings and order filters

**Files:**
- Create: `src/main/java/com/example/backend/controller/CmsInventoryController.java`
- Create: inventory response DTO under `src/main/java/com/example/backend/dto/`
- Modify: `src/main/java/com/example/backend/repository/ProductVariantRepository.java`
- Modify: `src/main/java/com/example/backend/controller/CmsOrderController.java`
- Modify: `src/main/java/com/example/backend/repository/OrderRepository.java`
- Create/modify: inventory and order controller integration tests

**Interfaces:**
- Produces: `GET /api/cms/inventory/low-stock?threshold=5&page=&size=` with product id/name, variant id, SKU, stock, reserved, available, threshold.
- Enhances `/api/cms/orders` with validated optional `from`, `to`, `paymentStatus`, `paymentMethod`, buyer email, and existing order status filters.
- Existing `/api/admin/orders` stays behavior-compatible; if mirrored filters are added to the legacy alias, delegate to the same service query.

- [ ] **Step 1: Test stock reservation and filter correctness**
  - Verify available stock is `stockQuantity - reservedQuantity`; include below/at/above threshold and paging.
  - Verify order filters combine with status and time bounds without including rows at exclusive `to`; preserve current defaults when filters are absent.
  - Verify CMS authorization remains ADMIN-only.
- [ ] **Step 2: Implement database-paged queries**
  - Use a repository projection and database-side filtering/order; do not load the catalog or orders into memory.
  - Validate nonnegative threshold (default 5) and page size cap.
  - Do not add a stored `availableQuantity` field or mutate stock from a read endpoint.
- [ ] **Step 3: Verify complete regression suite**
  - Run: `./mvnw -q test`
  - Run: `docker compose config -q`
  - Run: `git diff --check`
  - Confirm checkout, payment webhook, product public reads, and existing admin route tests remain green.

### Task 5: Add CMS reports and bounded Excel exports

**Files:**
- Create: report DTOs under `src/main/java/com/example/backend/dto/`
- Create: `src/main/java/com/example/backend/controller/CmsReportController.java`
- Create: `src/main/java/com/example/backend/service/CmsReportService.java`
- Modify: `src/main/java/com/example/backend/repository/OrderRepository.java`
- Modify: `src/main/java/com/example/backend/repository/ProductVariantRepository.java`
- Modify: `pom.xml` only for Apache POI if no existing dependency provides `.xlsx`
- Create: `src/test/java/com/example/backend/controller/CmsReportControllerIT.java`
- Create: `src/test/java/com/example/backend/repository/CmsReportRepositoryIT.java`

**Interfaces:**
- Produces `GET /api/cms/reports/sales?from=&to=&granularity=day|week|month`, `GET /api/cms/reports/products?from=&to=&sort=units|revenue`, `GET /api/cms/reports/inventory?categoryId=&threshold=`, and `GET /api/cms/reports/{reportType}/export.xlsx` with the matching report filters.
- All routes require `ROLE_ADMIN`. All report range semantics match dashboard: `[from,to)`, UTC, capped at 366 days. Exports are capped at 50,000 rows and emitted as `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` with a safe attachment filename.
- Product sales use order item snapshots; inventory uses `available = stockQuantity - reservedQuantity`.

- [ ] **Step 1: Write PostgreSQL report and workbook tests**
  - Seed orders with different payment states and `paidAt` times, and items whose snapshot names/prices differ from current product values.
  - Assert sales totals include only successful payments in interval, product sales use order-item snapshots, inventory reports reserved/available quantities accurately, and pagination order is stable.
  - Call each export route as ADMIN; assert workbook bytes open as XLSX, expected sheet/column headers and numeric money/quantity cells exist, filename is safe, and over-limit exports return 400.
  - Assert anonymous/CUSTOMER requests are rejected before report execution.
- [ ] **Step 2: Run tests and confirm expected failures**
  - Run: `./mvnw -q -Dtest=CmsReportControllerIT,CmsReportRepositoryIT test`
  - Expected: report/export route tests fail until the controller, queries, and workbook generator exist.
- [ ] **Step 3: Implement typed report queries and DTOs**
  - Aggregate sales in PostgreSQL and return `BigDecimal`/integer projections.
  - Group product sales from `OrderItem` snapshots and tie items to qualifying paid orders within `[from,to)`.
  - Query inventory with a database-side threshold and stable page/order; do not load all variants.
- [ ] **Step 4: Add bounded streaming XLSX output**
  - Use Apache POI `SXSSFWorkbook` with a fixed row window; stream database pages/chunks into rows rather than building an in-memory list.
  - Set numeric/date cell types and fixed sensible column widths/styles; do not create formulas from user input.
  - Enforce the 50,000-row and date-window limits before writing any response bytes. Close/dispose workbook and temporary files in `finally`/try-with-resources, including on client disconnect.
- [ ] **Step 5: Verify reports and export caps**
  - Run: `./mvnw -q -Dtest=CmsReportControllerIT,CmsReportRepositoryIT test`
  - Inspect generated workbook in the test with Apache POI; do not add checked-in generated exports.

### Task 6: Persist customer/CMS notifications and email outbox

**Files:**
- Create: Flyway migration `src/main/resources/db/migration/V5__notifications_and_email_outbox.sql`
- Create: `Notification`, `NotificationOutbox`, and `LowStockAlertState` entities/repositories under current `entity/` and `repository/` packages
- Create: `NotificationService`, `EmailOutboxWorker`, and mail template sender under `src/main/java/com/example/backend/service/`
- Modify: `OrderService` to publish shipped/delivered state events and preserve current event publication points
- Modify: existing order/payment event handling or add a transaction-bound event listener
- Modify: `pom.xml` to add Spring Boot mail starter if absent
- Modify: `application.properties` and `docker-compose.yml` with disabled-by-default SMTP environment configuration and retry settings
- Create: `src/test/java/com/example/backend/service/NotificationServiceTest.java`
- Create: `src/test/java/com/example/backend/service/EmailOutboxWorkerTest.java`
- Modify: existing order service/event tests

**Interfaces:**
- Order event types: placed, payment confirmed, payment failed/cancelled/expired, shipped, delivered. Use a stable unique key `order:{id}:{EVENT_TYPE}` for all persisted deliveries.
- `NotificationService.recordOrderEvent(event)` executes as a transaction-bound BEFORE_COMMIT listener and writes recipient in-app notification plus email-outbox row in the same transaction as the order/payment change.
- `LowStockAlertState` stores each variant/threshold active state; a downward crossing creates one CMS notification and an upward crossing re-arms it.
- `EmailOutboxWorker.processBatch()` claims a small batch of pending/retry rows with PostgreSQL `FOR UPDATE SKIP LOCKED`, attempts `JavaMailSender` delivery outside the claim transaction, and marks sent/retry/failed with capped exponential backoff.
- Claims use a lease expiry so rows left `SENDING` by a crashed process can be reclaimed; SMTP remains at-least-once across ambiguous failures.
- SMTP values come from `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`; no secrets in source or Compose literals. Mail sending is disabled if SMTP host is not configured.

- [ ] **Step 1: Write migration and repository tests**
  - Assert V5 creates recipient/created/read indexes, unique event keys, outbox attempts/status/lease timestamps, low-stock alert state, and valid foreign keys without modifying existing order/user rows.
  - Assert the same event key cannot create duplicate notification or email-outbox rows.
- [ ] **Step 2: Write event listener and outbox worker tests**
  - For each order event, assert correct recipient, event type, minimal template data, unique key, and in-app/outbox rows are saved.
  - Assert transaction rollback creates no notification/outbox records; duplicate delivery has no duplicate records.
  - Assert successful send marks SENT; transient error schedules retry and increments attempts; max attempts marks FAILED; worker never holds a DB row lock while making SMTP network calls.
  - Assert an expired SENDING lease is reclaimed, and an active lease is not sent by a second worker.
  - Assert CMS notification rows are generated per admin recipient with uniqueness on `(recipient_user_id,event_key)`.
  - Assert no email sender bean/network action exists when SMTP is disabled.
- [ ] **Step 3: Apply migration and confirm expected failures**
  - Run the migration-focused tests and confirm new entities/repositories work against PostgreSQL.
  - Expected: listener/worker behavior tests fail until implementation exists.
- [ ] **Step 4: Persist notifications atomically with business events**
  - Use `@TransactionalEventListener(phase = BEFORE_COMMIT)` for event-to-row persistence, with default no-op outside a transaction.
  - Store short template keys and minimal data; look up current recipient email at dispatch only if identity policy permits, otherwise capture the recipient address in the outbox in the original transaction.
  - Add shipped/delivered events only after the existing status transition is validated and saved.
  - Do not change stock or payment state logic and do not send mail from checkout/webhook threads.
- [ ] **Step 5: Implement retrying SMTP worker and environment settings**
  - Configure Spring `JavaMailSender` only when `MAIL_HOST` is nonblank; keep local Compose mail disabled by default.
  - Claim bounded batches, release the claim transaction before SMTP, retry with exponential backoff and a finite maximum, sanitize/store provider errors, and never log message body/recipient credentials.
  - Build escaped, versioned plain-text/HTML transactional templates for order placed, payment result, shipped/delivered, and cancellation/expiry; omit gateway secrets and full delivery address.
- [ ] **Step 6: Verify durable event-to-email path**
  - Run: `./mvnw -q -Dtest=NotificationServiceTest,EmailOutboxWorkerTest,OrderServiceTest,OrderEventListenerTest test`
  - Verify failed SMTP does not roll back checkout/payment and later retries operate on the durable outbox.

### Task 7: Expose customer and CMS notification APIs

**Files:**
- Create: notification response DTOs under `src/main/java/com/example/backend/dto/`
- Create: `src/main/java/com/example/backend/controller/CmsNotificationController.java`
- Create: `src/main/java/com/example/backend/controller/CustomerNotificationController.java`
- Modify: `NotificationService` and notification repository
- Create: `src/test/java/com/example/backend/controller/CmsNotificationControllerIT.java`
- Create: `src/test/java/com/example/backend/controller/CustomerNotificationControllerIT.java`

**Interfaces:**
- CMS routes: `GET /api/cms/notifications?read=&page=&size=`, `GET /api/cms/notifications/unread-count`, `PUT /api/cms/notifications/{id}/read`.
- Customer routes: `GET /api/notifications?read=&page=&size=`, `GET /api/notifications/unread-count`, `PUT /api/notifications/{id}/read`, `PUT /api/notifications/read-all`.
- CMS routes require `ROLE_ADMIN`; customer routes derive recipient from authenticated principal and never accept a target user id.
- CMS notifications cover new order and low-stock threshold crossing. Customer notifications cover their own order lifecycle. Store/read events are paged and ordered newest first.

- [ ] **Step 1: Write API ownership and paging tests**
  - Assert ADMIN reads CMS alerts; CUSTOMER/anonymous cannot access `/api/cms/notifications`.
  - Assert each customer only sees/updates own notifications; attempts to mark another user's row read return 404.
  - Assert unread count/read-all, read filter, page bounds, newest-first order, and stable tie-break by id.
- [ ] **Step 2: Implement recipient-scoped queries and controllers**
  - Include authenticated user id in every customer repository query; never fetch by notification id alone before ownership check.
  - Use ADMIN-only CMS routes for staff alerts; never expose staff-only notifications through customer APIs.
  - Deduplicate low-stock alerts by variant and threshold state/window so polling or repeated updates do not flood CMS.
- [ ] **Step 3: Run notification API tests and full regressions**
  - Run: `./mvnw -q -Dtest=CmsNotificationControllerIT,CustomerNotificationControllerIT test`
  - Then run: `./mvnw -q test`, `docker compose config -q`, and `git diff --check`.
  - Verify with a mail test server or injected fake sender; never send test emails to real customers.

## Completion Criteria

- Every new CMS route begins with `/api/cms/` and is ADMIN-only.
- CMS catalog and order management reuse existing service rules; previous endpoints remain functional.
- Revenue figures consistently mean successful payments grouped by `paidAt`, with documented UTC and interval semantics.
- Reports and XLSX exports are bounded, ADMIN-only, snapshot-correct, and generated without loading unbounded data into heap.
- Notification rows/outbox records commit atomically with order events; customer notification access is recipient-scoped; SMTP retries cannot block checkout/payment.
- Dashboard queries are bounded, paged/aggregated in the database, and tested on PostgreSQL.
- Existing checkout, payment, stock, public browsing, and authentication behavior passes the complete regression suite.
