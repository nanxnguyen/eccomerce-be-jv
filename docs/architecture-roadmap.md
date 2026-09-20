# Backend Architecture Roadmap

## 1. Current state

The project is currently a Spring Boot monolith with:

- Spring Security and JWT authentication.
- PostgreSQL, JPA, and Flyway migrations.
- Role-based authorization with `CUSTOMER` and `ADMIN`.
- BCrypt password hashing.
- Login rate limiting.
- Order/payment domain events.
- Multiple payment gateways.
- Docker Compose.
- Integration tests for the main controllers.

The current structure is suitable for continued development. Do not split into microservices yet.
First organize the monolith by domain and add the missing production safeguards.

## 2. Main gaps

- Authentication only returns one JWT token.
- No refresh token, logout, token revocation, or token rotation.
- Login rate limiting is in-memory and only works correctly on one application instance.
- No Redis integration.
- No explicit login/session audit trail.
- Stock updates need stronger protection against concurrent checkouts.
- Payment webhook and checkout operations need idempotency protection.
- Product/catalog caching is not implemented.
- Access token currently uses email as the subject; a stable user ID is preferable.

## 3. Target architecture

Keep a modular monolith:

```text
com.example.backend
├── auth
│   ├── controller
│   ├── service
│   ├── security
│   ├── token
│   └── dto
├── user
├── catalog
├── cart
├── order
├── inventory
├── payment
├── notification
├── common
│   ├── exception
│   ├── response
│   ├── security
│   └── config
└── infrastructure
    ├── redis
    ├── persistence
    └── events
```

Do not create factories, interfaces, or abstractions with only one implementation. Add them only when a second implementation or a real integration boundary exists.

## 4. Authentication design

### 4.1 Token response

Replace the current response:

```json
{
  "token": "..."
}
```

with:

```json
{
  "accessToken": "jwt-access-token",
  "refreshToken": "opaque-refresh-token",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 2592000,
  "user": {
    "id": 1,
    "name": "Nguyen Van A",
    "email": "user@example.com",
    "role": "CUSTOMER"
  }
}
```

Recommended defaults:

- Access token: 15 minutes.
- Refresh token: 30 days.
- `expiresIn`: seconds until access token expiry.
- `refreshExpiresIn`: seconds until refresh token expiry.
- `tokenType`: `Bearer`.

### 4.2 Access token

The access token should contain only the data required for authorization:

```text
sub = userId
role
iat
exp
jti
```

Use `userId` as `sub` instead of email because email may change.

The access token should be short-lived and should not be stored in Redis for every request. JWT signature and expiry validation remain stateless.

### 4.3 Refresh token

Use a cryptographically random opaque refresh token instead of another JWT.

Rules:

- Never store the raw refresh token.
- Hash the refresh token before storing it.
- Associate it with a user, device, IP, and user agent where needed.
- Rotate it every time `/refresh` is called.
- Revoke the old token immediately after successful rotation.
- If an old refresh token is reused, revoke the related session or all user sessions.

### 4.4 Authentication endpoints

```text
POST /api/auth/register
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
POST /api/auth/logout-all
GET  /api/auth/me
```

`register` and `login` return both access and refresh tokens.

`logout` revokes the current refresh session.

`logout-all` revokes all refresh sessions belonging to the current user.

## 5. Redis usage

Add Spring Data Redis and a Redis service to Docker Compose.

Redis should be used for distributed, short-lived state:

```text
auth:refresh:{tokenHash}
auth:user-sessions:{userId}
auth:blacklist:{jti}
rate-limit:login:{ip}
rate-limit:api:{userIdOrIp}
cache:product:{id}
cache:category:list
lock:checkout:{userId}
```

### 5.1 Rate limiting

Move `LoginRateLimiter` from in-memory storage to Redis:

```text
INCR rate-limit:login:{ip}
EXPIRE rate-limit:login:{ip} 900
```

This makes login protection consistent across multiple application instances.

### 5.2 Refresh sessions

Store the refresh session in Redis with a TTL matching the refresh token expiry.

```text
auth:refresh:{tokenHash} -> userId, sessionId, createdAt, deviceId
```

A database-backed audit table can be added later if long-term session history or compliance reporting is required.

### 5.3 Caching

Start with read-heavy catalog data only:

- Product detail.
- Category list.
- Product listing when measurements show a real benefit.

Do not cache carts, orders, stock, or payments without an explicit invalidation strategy.

## 6. Database changes

Create new Flyway migrations. Do not modify `V1__init.sql`.

### 6.1 `refresh_tokens`

Recommended columns:

```text
id
user_id
token_hash unique
session_id
device_id
ip_address
user_agent
expires_at
revoked_at
created_at
last_used_at
```

Recommended indexes:

```text
token_hash unique
user_id
expires_at
```

### 6.2 `users`

Consider adding:

```text
enabled
email_verified
failed_login_count
locked_until
last_login_at
created_at
updated_at
version
```

`version` can be used for optimistic locking.

### 6.3 Email uniqueness

Enforce email uniqueness at the database level in addition to the application-level check. The application check improves the error message; the database constraint prevents races between concurrent registrations.

## 7. Order, inventory, and payment reliability

### 7.1 Inventory

- Add optimistic locking to product variants.
- Validate stock inside the transaction.
- Update stock atomically.
- Never rely only on a pre-check before entering the transaction.

### 7.2 Checkout

- Keep checkout in a transaction.
- Add an idempotency key for client retries.
- Prevent duplicate orders when the same request is submitted more than once.

### 7.3 Payment webhooks

- Verify the gateway signature before changing state.
- Store the gateway transaction reference with a unique constraint where appropriate.
- Make webhook handling idempotent.
- Replaying the same webhook must not create duplicate payment state changes.

### 7.4 Events

Keep the current domain events for local in-process communication.

Add an outbox pattern only when event delivery must survive application crashes or when events are consumed asynchronously by another process.

## 8. API and security standards

Standardize error responses:

```json
{
  "timestamp": "2026-09-19T10:00:00Z",
  "status": 401,
  "code": "AUTH_TOKEN_EXPIRED",
  "message": "Access token expired",
  "path": "/api/orders"
}
```

Security requirements:

- Never log passwords, access tokens, refresh tokens, or payment secrets.
- Keep production secrets in environment variables or a secret manager.
- Do not use development JWT secrets in production.
- Keep access tokens short-lived.
- Revoke refresh sessions on logout and suspicious reuse.
- Keep public endpoints explicit.
- Add validation at every external input boundary.

## 9. Implementation phases

### Phase 1 — Production-ready authentication

1. Replace `AuthResponse` with access/refresh token fields.
2. Add `jti` and use `userId` as JWT subject.
3. Add `POST /api/auth/refresh`.
4. Add refresh token rotation.
5. Add logout and logout-all.
6. Reduce access token expiry to 15 minutes.
7. Add tests for expiry, rotation, reuse, and logout.

### Phase 2 — Redis

1. Add Spring Data Redis.
2. Add Redis to Docker Compose.
3. Move login rate limiting to Redis using `INCR` and `EXPIRE`.
4. Store refresh sessions in Redis.
5. Add Redis health checking.
6. Fail closed for invalid or revoked authentication state.

### Phase 3 — Order and payment correctness

1. Add optimistic locking for stock.
2. Make checkout transactional.
3. Add checkout idempotency keys.
4. Make payment webhooks idempotent.
5. Add unique constraints for gateway references.

### Phase 4 — Performance and observability

1. Add catalog caching after measuring query load.
2. Add pagination, filtering, and sorting for large collections.
3. Add correlation IDs to requests and logs.
4. Add metrics for login failures, refresh failures, checkout conflicts, and payment failures.
5. Add Testcontainers for PostgreSQL and Redis.

## 10. Recommended priority

```text
Refresh/revoke authentication
        ↓
Redis rate limiting and refresh sessions
        ↓
Inventory concurrency protection
        ↓
Payment idempotency
        ↓
Measured catalog caching
        ↓
Observability
        ↓
Microservice extraction only when scale requires it
```

## 11. Explicitly deferred

The following should not be added yet:

- Microservices.
- Kafka or RabbitMQ.
- Distributed tracing infrastructure.
- Cache for every entity.
- Multiple abstraction layers with one implementation.
- Full event sourcing.

Add them only when traffic, team ownership, deployment boundaries, or reliability requirements justify the operational cost.

## 12. CMS reporting and notifications

Canonical staff endpoints live under `/api/cms/**` and require `ROLE_ADMIN`:

- `/api/cms/dashboard/summary` and `/api/cms/dashboard/revenue` provide paid revenue and order metrics. Revenue uses successful payments by `paidAt`, UTC, and `[from,to)` ranges (maximum 366 days).
- `/api/cms/reports/sales`, `/products`, and `/inventory` provide paged reports; `/api/cms/reports/{sales|products|inventory}/export.xlsx` exports at most 50,000 rows.
- `/api/cms/notifications` lists CMS new-order and low-stock alerts. Customer lifecycle notifications are recipient-scoped under `/api/notifications`.
- Flyway V5 stores notifications and transactional email outbox rows. `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, and `MAIL_MAX_ATTEMPTS` configure SMTP. Delivery stays disabled when `MAIL_HOST` is empty; the default `MAIL_FROM` is `nguyenanhnhut0101.99@gmail.com`.

Email delivery is at-least-once: a process can stop after the SMTP server accepts a message and before the outbox row is marked sent. Do not use this channel for marketing campaigns or arbitrary recipients.
