# ETP IAM Capability

**Capability code:** `etp.iam`  
**Implementation code:** `etp-iam`

ETP IAM is a **reusable ETP capability** that provides authentication and authorization platform services. It is **not** a business application.

Consumers (for example ETP Core UI, business applications, ETP Customer UI) integrate with ETP IAM through its APIs and OAuth2/JWT contracts — they do not embed IAM implementation logic.

**ETP IAM is not ETP Core UI.** ETP IAM does **not** depend on ETP Core UI at runtime.

## Human vs system authentication

These are **distinct APIs** — do not use `POST /api/v1/auth/token` for human login.

| API | Purpose | Caller | Credential |
|-----|---------|--------|------------|
| `POST /api/v1/auth/login` | **Human** user authentication | ETP Core UI (Phase 1) | `username` + `password` |
| `POST /api/v1/auth/token` | **System-to-system** authentication | Backend/services | `client_id` + `client_secret` (`client_credentials`) |

Human login flow (Phase 1):

```
Core UI → IAM (POST /auth/login) → Keycloak → IAM → Core UI
```

One login screen on Core UI — **no** browser redirect to a Keycloak login page.

## Classification

| Aspect | Detail |
|--------|--------|
| Type | ETP-owned reusable capability |
| Deployable | Independent Spring Boot service |
| Database | Separate `etp_iam` PostgreSQL database |
| Identity provider | Keycloak (realm `etp`) |

## Technology stack

- Java 21
- Spring Boot 4.1
- Spring Web MVC
- Spring Security + OAuth2 Resource Server (JWT)
- Spring Data JPA / Hibernate
- PostgreSQL 17
- Keycloak (OIDC token issuer)
- SpringDoc OpenAPI 3 / Swagger UI

## API documentation (OpenAPI / Swagger)

Default IAM port: **8081** (`ETP_IAM_SERVER_PORT` / `server.port`).

| Resource | URL |
|----------|-----|
| Swagger UI | http://localhost:8081/swagger-ui/index.html |
| OpenAPI JSON | http://localhost:8081/v3/api-docs |

**Human login (ETP Core UI Phase 1):** `POST http://localhost:8081/api/v1/auth/login`

```json
{
  "username": "<test-user>",
  "password": "<test-password>"
}
```

**System authentication (services only):** `POST http://localhost:8081/api/v1/auth/token`

```json
{
  "client_id": "<client-id>",
  "client_secret": "<client-secret>",
  "grant_type": "client_credentials"
}
```

Do not use `/auth/token` for human login. Swagger examples use placeholders only — never commit real credentials.

## Current capabilities

| Area | Status |
|------|--------|
| Service-to-service token acquisition (`client_credentials`) | Implemented |
| JWT validation on protected APIs | Implemented |
| DB-backed RBAC authorization | Implemented |
| Tenant-aware authorization checks | Implemented |
| Principals, groups, roles, permissions model | Implemented |
| Browser username/password login via IAM | Implemented (`POST /api/v1/auth/login`) |
| OIDC Authorization Code + PKCE | **Not implemented** (Phase 2) |
| `/auth/logout`, `/auth/me`, `/auth/refresh` | **Not implemented** |

## Current APIs

Base path: `/api/v1`

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/auth/login` | Public | Username/password login (server-side Keycloak password grant) |
| `POST` | `/auth/token` | Public | Obtain access token via Keycloak (`client_credentials` only) |
| `POST` | `/authorization/check` | Bearer JWT | Check permission for application (+ optional tenant) |

### `POST /api/v1/auth/login`

- **Purpose:** ETP Core UI and other consumers submit username/password to IAM; IAM authenticates with Keycloak using a **confidential server-side client** (password grant / Direct Access Grant).
- **Request:** JSON with `username`, `password` (password is never stored or logged by IAM)
- **Response:** `{ user, accessToken, expiresAt }` — user profile derived from JWT claims; `expiresAt` is ISO-8601 UTC
- **Errors:** 400 validation; 401 invalid credentials; 502 token service unavailable

### `POST /api/v1/auth/token`

- **Grant type:** `client_credentials` only (other grant types are rejected)
- **Request:** JSON with `client_id`, `client_secret`, `grant_type`
- **Response:** OAuth2 token fields (`access_token`, `expires_in`, `token_type`, `scope`, optional `refresh_expires_in`)

### `POST /api/v1/authorization/check`

- **Request:** JSON with `application`, `permission`, optional `tenant`
- **Response:** `{ "authorized": true | false }`
- Resolves JWT issuer + subject to IAM `principals`, then evaluates group → role → permission mappings

**Browser login uses IAM `POST /api/v1/auth/login` — credentials are validated by Keycloak; IAM does not store passwords.**

## Keycloak development setup (username/password login)

1. Create realm `etp` (or use existing).
2. Create a **confidential** client for IAM backend login (example: `etp-core-ui-login`):
   - Client authentication: **ON**
   - Direct access grants: **ON** (development phase only)
   - Standard flow: optional
   - Copy client secret to `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET`
3. Create a test user with credentials for Sign In.
4. Set `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID` to match the client id.

Do **not** expose the client secret to ETP Core UI or any browser code.

## Authentication model

1. **Human login:** Core UI → `POST /api/v1/auth/login` → IAM → Keycloak password grant → JWT access token returned to Core UI.
2. **Service login:** Clients call `POST /api/v1/auth/token` with Keycloak client credentials.
3. Keycloak issues JWT access tokens.
4. Protected IAM endpoints validate JWTs via OAuth2 Resource Server (`issuer-uri`).

- Authentication identity comes from Keycloak JWT (`issuer` + `subject`).
- IAM PostgreSQL stores authorization metadata: principals, groups, roles, permissions, tenant assignments.
- `POST /api/v1/authorization/check` returns a boolean authorization decision.

Schema design: authentication is delegated to Keycloak; IAM DB does not store user passwords.

## Identity and authorization model (IAM schema)

Authentication identity is established by Keycloak. IAM maps JWT claims to authorization metadata in PostgreSQL (`etp_iam`). There is **no** `users` table — human users are represented as `principals` keyed by Keycloak issuer + subject (`external_id` holds Keycloak `sub`).

Conceptual chain:

```
Keycloak user
    → JWT iss + sub
    → principals (USER or SERVICE)
    → principal_group_mappings
    → groups
    → group_role_mappings
    → roles
    → role_application_permissions
    → applications + permissions (via resources)
```

Tenant scope is assigned to **groups**, not principals directly:

```
groups
    → group_tenant_assignments
    → tenants (under companies)
    → optional scopes
```

`POST /api/v1/authorization/check` resolves the caller JWT to a `principal`, evaluates group → role → `role_application_permissions`, and applies tenant constraints from `group_tenant_assignments` when a tenant is supplied.

Source: `schema_updated.sql`.

## Deployment principle

**Same capability, same authentication contract, different deployment / configuration.**

| Environment (example) | Consumers | IAM + Keycloak |
|------------------------|-----------|----------------|
| ETP internal (Phase 1) | ETP Core UI | ETP IAM → Keycloak |
| Customer A AWS (future) | ETP Customer UI, business applications | Customer ETP IAM → Keycloak |
| Customer B AWS (future) | Separate deployment/configuration | No runtime dependency on ETP Core UI or ETP internal IAM |

## Future customer deployment (not implemented)

Future customer environments deploy **their own** IAM and Keycloak in the customer's AWS account:

```
ETP Customer UI → ETP IAM → Keycloak
Business Application → ETP IAM → Keycloak
```

Customer IAM/Keycloak have **no** runtime dependency on ETP Core UI. ETP Core UI is **not** part of the customer authentication flow.

## Phase 1 scope

**In scope (Phase 1):**

- Development user and IAM hierarchy (principals, groups, roles, permissions)
- Username/password authentication (Core UI → IAM → Keycloak)
- Authentication session in Core UI (Remember Me, logout, protected routes)
- Basic authorization foundation (`/authorization/check`)
- Development validation

**Out of scope (Phase 2+):**

- SSO, cross-application SSO, Customer UI auth, business application auth
- MFA, external IdPs, federation, passwordless authentication
- OIDC Authorization Code + PKCE (browser redirect flow)
- Token refresh, `/auth/me`, `/auth/refresh`, Keycloak end-session logout
- Advanced production session management

Phase 1 human login uses **Direct Access Grant / password grant on the IAM server** only. This is a **development-phase choice**, not the final Phase 2+ SSO architecture.

## Session and security (Phase 1)

| Topic | Phase 1 behavior |
|-------|----------------|
| Password in Core UI | Never persisted; cleared after submit; not stored in `localStorage` / `sessionStorage` |
| Password in IAM | Not stored in IAM database; not logged; not returned in login response |
| Keycloak client secret | Server-side only (IAM confidential client); never exposed to Core UI |
| Access token in Core UI | Stored in `AuthSession` per Remember Me (`localStorage` vs `sessionStorage`) |
| Session expiry | Aligned with Keycloak access token expiry (`expiresAt` in login response) |
| Token refresh | **Not implemented** in Phase 1 |
| Logout | Clears local application session; Keycloak end-session logout **deferred** |

## Local prerequisites

- Docker (for PostgreSQL, Keycloak, optional pgAdmin)
- Java 21
- Maven (wrapper included: `mvnw`)

## Local ports (default)

| Service | Host port | Notes |
|---------|-----------|--------|
| Keycloak | **8180** | Mapped from container 8080; avoids conflict with `etp-platform-service` on 8080 |
| ETP IAM service | **8081** | Spring Boot `server.port` |
| PostgreSQL (`etp_iam`) | **55432** | Host port → container 5432 |
| pgAdmin | **5050** | Optional |

## Local setup

### 1. Environment

```bash
cp .env.example .env
# Edit .env — set ETP_IAM_DB_PASSWORD, ETP_IAM_KEYCLOAK_ADMIN_PASSWORD, ETP_IAM_PGADMIN_PASSWORD
```

### 2. Start infrastructure

From this directory:

```bash
docker compose --env-file .env up -d
```

### 3. Apply database schema

Apply `schema_updated.sql` to the `etp_iam` database (one-time or when schema changes):

```bash
# Example using psql against the Docker Postgres host port
psql -h localhost -p 55432 -U etp_admin -d etp_iam -f schema_updated.sql
```

There is no Flyway migration in this step — schema is applied manually from `schema_updated.sql`.

### 4. Configure Keycloak

- Realm: `etp` (create/configure in Keycloak admin console)
- Create clients, users, and roles as required for your environment
- Ensure JWT issuer matches `ETP_IAM_KEYCLOAK_ISSUER_URI` (default `http://localhost:8180/realms/etp`)

Keycloak realm export and client definitions are not bundled in this repository.

### 5. Start IAM service

Set `ETP_IAM_DB_PASSWORD` (and other variables from `.env.example`) in your shell or IDE run configuration:

```bash
./mvnw spring-boot:run
# Windows: mvnw.cmd spring-boot:run
```

Service listens on `http://localhost:8081` by default.

## Build and test

```bash
./mvnw clean test
./mvnw clean package
```

`JpaSchemaValidationTest` requires a running PostgreSQL instance with the schema applied and `ETP_IAM_DB_PASSWORD` set.

## Known limitations

- OIDC Authorization Code + PKCE browser flow **not implemented** (Phase 2+)
- No `/auth/logout`, `/auth/me`, `/auth/refresh` endpoints
- No token refresh in Phase 1
- No Flyway migrations (manual `schema_updated.sql`)
- No health/actuator endpoint yet
- Keycloak realm/clients are not auto-provisioned from this repo
- Principals must exist in IAM DB and match Keycloak JWT issuer/subject for authorization checks
- Direct Access Grant on IAM server is a Phase 1 development choice — not the production SSO target architecture

## Related documentation

- [ETP Architecture](../../etp-platform-service/ARCHITECTURE.md)
- [Capability Registry](../../etp-platform-service/docs/CAPABILITY_REGISTRY.md)
