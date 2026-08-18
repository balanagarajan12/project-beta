# ETP IAM Capability

**Capability code:** `etp.iam`  
**Implementation code:** `etp-iam`

ETP IAM is a **reusable ETP capability** that provides authentication and authorization platform services. It is **not** a business application.

Consumers (for example ETP Core UI, business applications, ETP Customer UI) integrate with ETP IAM through its APIs and OAuth2/JWT contracts — they do not embed IAM implementation logic.

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

## Current capabilities

| Area | Status |
|------|--------|
| Service-to-service token acquisition (`client_credentials`) | Implemented |
| JWT validation on protected APIs | Implemented |
| DB-backed RBAC authorization | Implemented |
| Tenant-aware authorization checks | Implemented |
| Principals, groups, roles, permissions model | Implemented |
| Browser username/password login | **Not implemented** |
| OIDC Authorization Code + PKCE | **Not implemented** |
| User-facing `/auth/login`, `/logout`, `/me`, `/refresh` | **Not implemented** |

## Current APIs

Base path: `/api/v1`

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/auth/token` | Public | Obtain access token via Keycloak (`client_credentials` only) |
| `POST` | `/authorization/check` | Bearer JWT | Check permission for application (+ optional tenant) |

### `POST /api/v1/auth/token`

- **Grant type:** `client_credentials` only (other grant types are rejected)
- **Request:** JSON with `client_id`, `client_secret`, `grant_type`
- **Response:** OAuth2 token fields (`access_token`, `expires_in`, `token_type`, `scope`, optional `refresh_expires_in`)

### `POST /api/v1/authorization/check`

- **Request:** JSON with `application`, `permission`, optional `tenant`
- **Response:** `{ "authorized": true | false }`
- Resolves JWT issuer + subject to IAM `principals`, then evaluates group → role → permission mappings

## Authentication model

1. Clients call `POST /api/v1/auth/token` with Keycloak client credentials.
2. ETP IAM proxies the request to Keycloak OIDC token endpoint.
3. Keycloak issues JWT access tokens.
4. Protected IAM endpoints validate JWTs via OAuth2 Resource Server (`issuer-uri`).

**Browser login, password grant, and OIDC redirect flows are not implemented in this capability yet.**

## Authorization model

- Authentication identity comes from Keycloak JWT (`issuer` + `subject`).
- IAM PostgreSQL stores authorization metadata: principals, groups, roles, permissions, tenant assignments.
- `POST /api/v1/authorization/check` returns a boolean authorization decision.

Schema design: authentication is delegated to Keycloak; IAM DB does not store user passwords.

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

- No browser username/password login API
- No OIDC Authorization Code + PKCE flow
- No `/auth/login`, `/auth/logout`, `/auth/me`, `/auth/refresh` endpoints
- No Flyway migrations (manual `schema_updated.sql`)
- No health/actuator endpoint yet
- Keycloak realm/clients are not auto-provisioned from this repo
- Principals must exist in IAM DB and match Keycloak JWT issuer/subject for authorization checks

## Related documentation

- [ETP Architecture](../../etp-platform-service/ARCHITECTURE.md)
- [Capability Registry](../../etp-platform-service/docs/CAPABILITY_REGISTRY.md)
