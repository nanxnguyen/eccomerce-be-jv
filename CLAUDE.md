# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Maven wrapper only (no Makefile/lint config). Java 17, Spring Boot 4.1.1.

```sh
./mvnw test                                   # whole suite (H2, no Postgres/Redis needed) - what CI runs
./mvnw test -Dtest=AdminOrderControllerIT     # one class
./mvnw test -Dtest=AdminOrderControllerIT#nonAdminCannotListOrAdvanceOrders   # one method
./mvnw spring-boot:run                        # needs Postgres :5432 (db `backend`); Redis :6379 is optional (login rate limiter fails open)
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed   # also seeds sample users/categories/products (password 123456)
./mvnw spring-boot:build-image                # builds `backend:latest`, required before `docker compose up app`
docker compose up -d                          # full stack: db, redis, keycloak, app, prometheus, loki, alloy, grafana
```

- Surefire runs `*Test`, `*Tests` and `*IT` all in the `test` phase (no failsafe).
- Default datasource user is the developer's local Postgres role `nguyenanhnhut`. To use the compose DB instead: `docker compose up -d db redis`, then run with `SPRING_DATASOURCE_USERNAME=backend SPRING_DATASOURCE_PASSWORD=backend`.
- Swagger UI at `/swagger-ui.html`. Actuator (`health`, `prometheus`) is on the separate management port 8081.
- Spring Boot 4 / Jackson 3: `tools.jackson.databind.ObjectMapper`, `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` - imports differ from Boot 3 examples.

## Architecture

Single Spring Boot monolith, package-by-layer under `com.example.backend` (`controller`, `service`, `repository`, `entity`, `dto`, `event`, `scheduler`, `security`, `config`). `docs/architecture-roadmap.md` says keep it a monolith and not to add interfaces/factories with a single implementation. It is a forward-looking roadmap and partly stale (refresh tokens and the Redis rate limiter already exist), so trust the code. Per-feature design specs are in `docs/superpowers/specs/`.

**Two auth mechanisms side by side** (`config/SecurityConfig`, stateless, no CSRF/cookies):

- Legacy local JWT: `/api/auth/*` -> `JwtService`; `JwtAuthFilter` loads the user from the DB, role comes from the `users` row.
- Keycloak, opt-in via `app.keycloak.enabled` (off by default, on in compose): `KeycloakBearerTokenResolver` only claims tokens whose `iss` matches the Keycloak issuer, so every token is handled by exactly one path. `KeycloakJwtAuthenticationConverter` maps `realm_access.roles` (only `CUSTOMER`/`ADMIN`) to `ROLE_*`; `KeycloakUserProvisioner` links/creates the local `User` by `keycloak_subject` or verified email. See `docs/keycloak.md`.
- Authorization is `@PreAuthorize("hasRole('ADMIN')")` per controller/method. `/api/cms/**` is the admin backoffice (dashboard, reports incl. `.xlsx` export via POI, inventory, notifications); `/api/admin/orders` is the older admin order API.
- Login brute-force limiting: `LoginRateLimiter` interface, `RedisLoginRateLimiter` by default, `InMemoryLoginRateLimiter` when `auth.login.rate-limiter=in-memory` (tests).

**Checkout / payment** (`OrderService`, `service/payment/`):

- `checkout` takes `PESSIMISTIC_WRITE` locks on variants (`findByIdForUpdate`). COD -> `CONFIRMED` and deducts `stockQuantity` immediately; VNPay/Stripe -> `PENDING_PAYMENT`, only bumps `reservedQuantity`, with `expiresAt = now + order.payment-expiry-minutes`.
- `PaymentService` auto-collects every `PaymentGateway` bean keyed by `PaymentMethod` - a new gateway is just a new class. Webhooks (`/api/payments/webhooks/**`, public) verify signatures inside `gateway.parseWebhook`; `null` means invalid -> 400.
- `confirmPayment` is idempotent: a no-op unless the order is still `PENDING_PAYMENT`, so gateway retries are safe. `OrderExpiryScheduler` (60s) cancels overdue orders and releases reservations.

**Events -> notifications -> email (transactional outbox):**

- `OrderService` publishes `OrderPlaced/PaymentConfirmed/OrderCancelled/OrderStatusChanged` events. `NotificationService` handles them with `@TransactionalEventListener(BEFORE_COMMIT)`, so in-app `notifications` rows plus a `notification_outbox` row commit atomically with the order change. Dedup key: `event_key = order:{orderId}:{TYPE}`. It also evaluates low-stock crossings (threshold 5, state in `low_stock_alert_states`) for admin alerts. `OrderEventListener` only logs.
- `EmailOutboxWorker` polls the outbox with raw JDBC (`FOR UPDATE SKIP LOCKED`, 120s lease, exponential backoff, `FAILED` after `app.mail.max-attempts`). It is only registered when `MAIL_HOST` is set; credentials come from `MAIL_*` env vars. `subject()` and body are keyed off `event_type` and the orderId parsed from `event_key`.

**Database & profiles:**

- Schema is owned by Flyway (`src/main/resources/db/migration/V*.sql`, Postgres syntax). Dev uses `ddl-auto=validate`, so an entity change needs a new migration. Prod (`application-prod.properties`) has no defaults for secrets and fails fast at boot when an env var is missing.
- Tests (`src/test/resources/application.properties`) run on in-memory H2 in PostgreSQL mode with `ddl-auto=create-drop` and Flyway disabled. Postgres-only SQL therefore has to be avoided or branched: `NotificationService.evaluateLowStock` switches on the DB product name, and `EmailOutboxWorker` is unit-tested with a mocked `JdbcTemplate`.

**Request logging:** `HttpExchangeLoggingFilter` logs one JSON `request`/`response` event per exchange. `SecurityConfig` disables its servlet auto-registration (`FilterRegistrationBean.setEnabled(false)`) and adds it to the security chain after `BearerTokenAuthenticationFilter` so it can log the authenticated account; keep it registered only there. Bodies are off unless `HTTP_LOG_BODIES=true` and sensitive fields are redacted (`docs/observability.md`).

## Testing conventions

- ITs use `@SpringBootTest` + `@AutoConfigureMockMvc` and call `TestDataCleaner.cleanAll()` in `@BeforeEach`. The whole suite shares one H2 instance, so **adding an entity/table means adding its `deleteAll()` to `TestDataCleaner` in FK order**. Otherwise other test classes break on leftover rows.
- Auth in ITs: create a `User`, then `jwtService.generateToken(userId, role)`.

## Code style

Comments (and some docs) are mostly Vietnamese. Older files use 4-space indentation, newer notification/CMS-report code uses 2-space google-java-format style; there is no formatter plugin, so match the file you are editing.

## Code rule

Every new source file (controller, service, repository, entity, dto, event, scheduler, security, config) must be commented in the same style as the existing well-commented files. The goal: a newcomer can read the code and follow how a request or event flows through the system.

- **Language:** Vietnamese. Keep identifiers (class, method, annotation, property, table names) in English.
- **Class level:** one comment above every new class/record/interface stating its responsibility and its place in the flow: who calls or triggers it, what it delegates to (name those classes), and any non-obvious constraint. Example (`OrderController`): `// Cửa vào HTTP cho luồng đơn hàng; controller nhận request rồi giao nghiệp vụ cho OrderService.`
- **Members:** explain *why* and the flow, not what the code already says. Cover business rules, transaction/locking/idempotency choices, security decisions, why a query or mapping is shaped that way (bulk vs derived delete, `EnumType.STRING`, cascade, snapshot vs FK), and what Spring does implicitly (DI, `@Valid`, `@AuthenticationPrincipal`, event listeners).
- **Per layer:** controller = one comment per endpoint (`METHOD /path: what it does, who enforces ownership/permission`); entity = one comment per column/relationship whose mapping is a decision; dto = what is deliberately exposed or left out; event = who publishes it and who listens; scheduler = interval and why; repository = why the query is written that way.
- Do not add comments that only restate the code (`// getter`, `// set name`).
- **Style references:** `controller/OrderController`, `entity/Order`, `repository/RefreshTokenRepository`, `dto/UserResponse`, `service/OrderService`, `config/SecurityConfig`, `security/LoginRateLimiter`, `scheduler/OrderExpiryScheduler`, `event/OrderEventListener`. The recently added notification, CMS, low-stock and email-outbox files have no comments - do not copy them as a style reference.
- This rule is about comments only; indentation still follows the file you are editing (see Code style).

### Impact check before changing behavior

For every new feature or bug fix, work out what it can break **before** editing, and verify **after**:

1. **Find every consumer** of what you touch: callers, listeners of an event, queries reading a column, endpoints returning a DTO. Use `codegraph_impact` / `codegraph_callers` (index in `.codegraph/`) or grep. Do not rely on the one file you happen to have open.
2. **Check the paths in this repo that share state:**
   - Order/stock changes (`checkout`, `confirmPayment`, `expireOverdueOrders`, `cancel`, `advanceStatus`) must keep `stockQuantity`/`reservedQuantity` consistent, and they publish events that `NotificationService` turns into notifications, outbox emails and low-stock alerts. CMS dashboard/report/inventory queries read the same tables.
   - Auth: both the legacy JWT path and the Keycloak path, and both admin surfaces (`/api/admin/orders`, `/api/cms/**`). `/api/auth/*` must keep working for legacy clients.
   - Schema: an entity change needs a Flyway migration, a `TestDataCleaner` update, an H2-compatible test path, and a check of repository/aggregate queries and DTO mappers that use it.
   - API contract: do not rename or remove existing paths, status codes or response fields unless asked (frontends depend on them); add fields instead.
3. **Fix the root cause** at the shared place every caller goes through, not only the path named in the report.
4. **Bug fix = regression test:** write a test that fails on the bug first, then make it pass.
5. **Verify:** run the affected tests, then the full `./mvnw test`, before saying done. In the final report say which consumers you checked and what you could not cover (for example Postgres-only SQL that H2 tests skip, or real Keycloak/SMTP not exercised).

### Follow proven practice

Before designing anything non-trivial (payments, auth, inventory/concurrency, notifications, caching, API shape, security), look at how mature systems and official docs solve the same problem and adopt the established pattern instead of inventing one. Typical sources: official Spring Boot / Spring Security / Hibernate / Keycloak docs, Stripe's API docs (idempotency, webhook signature verification), OWASP guidance, well-known patterns (transactional outbox - already used here, pessimistic/optimistic locking, idempotent consumers).

- **Verify library APIs against current docs** (Context7 MCP or the official site), not memory. This is Spring Boot 4.1 / Jackson 3 and older tutorials are often wrong.
- **Take the pattern, not the machinery.** This is a monolith by design: no new infrastructure (message brokers, extra services) and no single-implementation interfaces/factories unless there is a concrete need (`docs/architecture-roadmap.md`).
- When the choice is non-obvious, name the practice you followed in the code comment (Vietnamese, per Comments) or in your reply.
