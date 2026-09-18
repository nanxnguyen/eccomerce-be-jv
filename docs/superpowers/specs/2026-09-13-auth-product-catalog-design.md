# Auth/User + Product/Catalog — Design Spec

Date: 2026-09-13
Status: Approved (DB design), pending final review

## 1. Overview

First two modules of a larger ecommerce backend (Spring Boot + PostgreSQL).
Scope: user registration/login with role-based access, and a product catalog
(categories, products, variants, images) that admins manage and customers browse.

## 2. Goals

- Customers can register, log in, and view their profile.
- Admins can manage categories and products (CRUD).
- Anyone (no auth) can browse categories and products.
- Products support variants (size/color, each with own SKU/price/stock) and
  multiple images.
- Foundation other modules (Cart, Order, Payment) will build on later.

## 3. Non-goals (explicitly out of scope for this spec)

- Cart, Order, Payment, Inventory beyond a simple `stock_quantity` column.
- Multi-vendor / seller role.
- Email verification, password reset flow.
- Nested/tree categories (flat for now — see Data Model note).
- Shipping addresses (belongs to Order/Cart spec later).

## 4. Data Model

### `users`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR | |
| email | VARCHAR UNIQUE | login identifier |
| password_hash | VARCHAR | BCrypt |
| phone | VARCHAR | nullable |
| role | ENUM('CUSTOMER','ADMIN') | |
| created_at / updated_at | TIMESTAMP | |

### `categories`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR | |
| slug | VARCHAR UNIQUE | URL-friendly |
| description | TEXT | nullable |

Flat, 1 level. Upgrade path to tree: add nullable `parent_id` self-FK later — no
breaking change to existing rows.

### `products`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| category_id | BIGINT FK → categories.id | |
| name | VARCHAR | |
| slug | VARCHAR UNIQUE | |
| description | TEXT | |
| status | ENUM('ACTIVE','INACTIVE') | show/hide |
| created_at / updated_at | TIMESTAMP | |

No price/stock on `products` — lives on variants.

### `product_variants`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| product_id | BIGINT FK → products.id | |
| sku | VARCHAR UNIQUE | e.g. `AOTHUN-DEN-M` |
| size | VARCHAR | nullable |
| color | VARCHAR | nullable |
| price | DECIMAL(12,2) | |
| stock_quantity | INT | |
| created_at / updated_at | TIMESTAMP | |

### `product_images`
| Column | Type | Note |
|---|---|---|
| id | BIGINT PK | |
| product_id | BIGINT FK → products.id | |
| url | VARCHAR | |
| is_primary | BOOLEAN | |
| sort_order | INT | |

### Relationships

```
categories 1───N products 1───N product_variants
                        │
                        └───N product_images
users (standalone for now — Cart/Order will FK into it later)
```

## 5. API Surface

Auth (`/api/auth`):
- `POST /register` — create CUSTOMER account
- `POST /login` — returns JWT

User (`/api/users`):
- `GET /me` — current user profile (requires token)

Category (`/api/categories`):
- `GET /` , `GET /{slug}` — public
- `POST /`, `PUT /{id}`, `DELETE /{id}` — ADMIN only

Product (`/api/products`):
- `GET /` — public, filter by category, search by name, pagination
- `GET /{slug}` — public, includes variants + images
- `POST /`, `PUT /{id}`, `DELETE /{id}` — ADMIN only
- `POST /{id}/variants` — ADMIN only
- `POST /{id}/images` — ADMIN only

## 6. Security

- Spring Security + stateless JWT (Authorization: Bearer header).
- Passwords hashed with BCrypt, never stored/logged plain.
- Role check via `@PreAuthorize("hasRole('ADMIN')")` on admin-only endpoints.

## 7. Error Handling

Global `@RestControllerAdvice` mapping exceptions (not found, validation,
duplicate email/slug, bad credentials) to a consistent JSON body:
`{ timestamp, status, error, message, path }`.

## 8. Package Structure

```
com.example.backend
├── config          (SecurityConfig)
├── security         (JwtUtil, JwtAuthFilter)
├── entity           (User, Category, Product, ProductVariant, ProductImage)
├── repository       (JpaRepository interfaces)
├── service           (business logic)
├── controller        (REST endpoints)
├── dto               (request/response objects)
└── exception         (GlobalExceptionHandler, custom exceptions)
```

## 9. Testing

- Service layer: unit tests (mocked repositories).
- Controller layer: `@SpringBootTest` + `MockMvc` integration tests for the
  main flows (register/login, product CRUD, access-control on admin routes).
- Driven test-first per module during implementation (TDD).
