# Keycloak local setup

The API accepts both existing application JWTs and Keycloak access tokens. Existing
`/api/auth/login`, `/register`, `/refresh`, and `/logout` clients keep working while
clients migrate. Keycloak tokens are validated against the configured issuer, signing
keys, and `ecommerce-api` audience.

## Start

Copy `.env.example` to `.env`, set `JWT_SECRET` to a random value of at least 32
characters, and replace the local Keycloak admin password. Then start the stack:

```sh
docker compose up -d
```

- Keycloak: <http://localhost:8180>
- Admin console: <http://localhost:8180/admin> (the configured bootstrap admin)
- Realm: `ecommerce`
- Browser client: `ecommerce-web` (Authorization Code + PKCE S256)
- API audience: `ecommerce-api`

The realm import defines `CUSTOMER` and `ADMIN` realm roles. Create users in the
Keycloak admin console, mark their email verified, then assign one of those roles.
Do not use the bootstrap admin as an application account.

## Account linking

On the first request with a valid Keycloak access token, the API finds the local user
by Keycloak subject or links the user with the same verified email. If there is no
local account, it creates a `CUSTOMER` profile. Existing numeric user IDs and their
orders, carts, and addresses remain unchanged. An email already linked to another
Keycloak subject is rejected.

Keycloak roles in `realm_access.roles` become Spring `ROLE_*` authorities. Only
`CUSTOMER` and `ADMIN` are accepted. The existing local JWT flow continues to use the
role stored on the local user.

## Client use

Configure the frontend's OIDC library with issuer
`http://localhost:8180/realms/ecommerce`, client ID `ecommerce-web`, and PKCE method
`S256`. Send its access token as `Authorization: Bearer <access-token>` to the API.
The API does not receive or store Keycloak refresh tokens; the OIDC client refreshes
and logs out through Keycloak. Local `/api/auth/*` token endpoints remain available
for legacy clients during migration.

For a non-local environment, set `KEYCLOAK_ENABLED=true`,
`KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, and `KEYCLOAK_AUDIENCE` to that
environment's values. Do not expose Keycloak's development mode or its bootstrap
admin credentials to the internet.
