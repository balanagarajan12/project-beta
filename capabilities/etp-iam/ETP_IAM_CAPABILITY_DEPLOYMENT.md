# ETP IAM — Capability Deployment Guide

Operational guide for deploying the **ETP IAM** capability service (`etp.iam` **1.0.0**, **ACTIVE** in the ETP Capability Registry).

**Related:** [ETP_IAM_CAPABILITY_READINESS.md](ETP_IAM_CAPABILITY_READINESS.md), [ETP_IAM_CURRENT_IMPLEMENTATION.md](ETP_IAM_CURRENT_IMPLEMENTATION.md), [README.md](README.md)

---

## ETP Capability Registry

ETP-IAM is **registered and ACTIVE** in the ETP Platform Capability Registry:

| Field | Value |
|-------|-------|
| Capability code | `etp.iam` |
| Capability version | `1.0.0` |
| Capability status | `ACTIVE` |
| Version status | `ACTIVE` |
| Capability ID | `d8c29697-1a31-4318-bcd7-665e684915e9` |
| Capability version ID | `e8f1af6c-0d53-4fc3-bc94-5e2aea3781d3` |

**Service deployment vs capability registration** are separate concerns:

| Concern | What it is |
|---------|------------|
| **Service deployment** | Run the ETP-IAM Spring Boot JVM (`etp-iam` artifact), PostgreSQL `etp_iam`, Keycloak, env configuration |
| **Capability registration** | Metadata in `etp_platform` — contract, implementation, lifecycle (`POST /api/v1/capabilities`, then `VALIDATED` → `ACTIVE`) |

The Capability Registry **does not deploy** the IAM service. It stores the reusable capability contract, implementation metadata, and lifecycle state for catalog and composition. Operators deploy the IAM service independently using this guide.

Retrieve registry record: `GET /api/v1/capabilities/d8c29697-1a31-4318-bcd7-665e684915e9`

Registration payload reference: `etp-iam-capability-registration.json`

---

## Architecture

```
ETP Core UI (or other consumer)
        |
        |  POST /api/v1/auth/login  (human)
        |  POST /api/v1/auth/token  (service)
        |  POST /api/v1/authorization/check  (Bearer JWT)
        v
ETP IAM Service (Spring Boot, port 8081)
        |
        +---> PostgreSQL (etp_iam)     authorization metadata
        |
        +---> Keycloak                 authentication, JWT issuance
```

ETP IAM does **not** store passwords. Keycloak validates credentials. IAM PostgreSQL holds principals, groups, roles, permissions, and tenant assignments.

---

## Prerequisites

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 | Build and local run |
| PostgreSQL | 17 recommended | Database `etp_iam` |
| Keycloak | Compatible with OIDC/OAuth2 token endpoint | Realm `etp` (configurable) |
| Maven | 3.9+ | Build (`mvnw` included) |

Optional for local stack: Docker (see `docker-compose.yml`).

---

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ETP_IAM_DB_URL` | Yes | JDBC URL for `etp_iam` |
| `ETP_IAM_DB_USERNAME` | Yes | Database user |
| `ETP_IAM_DB_PASSWORD` | Yes | Database password — **[REDACTED]** in config |
| `ETP_IAM_KEYCLOAK_ISSUER_URI` | Yes | JWT issuer, e.g. `http://localhost:8180/realms/etp` |
| `ETP_IAM_KEYCLOAK_BASE_URL` | Yes | Keycloak base URL, e.g. `http://localhost:8180` |
| `ETP_IAM_KEYCLOAK_REALM` | Yes | Realm name, default `etp` |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID` | Yes | Confidential client for human login broker |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET` | Yes | Client secret — **[REDACTED]**; server-side only |
| `ETP_IAM_SERVER_PORT` | No | HTTP port (default `8081`) |
| `ETP_IAM_CORS_ALLOWED_ORIGINS` | No | Comma-separated origins for `/api/v1/auth/**` |

Copy `.env.example` to `.env` for local Docker Compose. **Do not commit** `.env` or real secrets.

---

## Database setup

### Flyway (recommended)

On startup, the IAM service runs Flyway migrations from `classpath:db/migration`:

| Migration | Purpose |
|-----------|---------|
| `V1__iam_schema.sql` | Full IAM schema (13 tables) — same content as legacy `schema_updated.sql` |
| `V2__platform_dev_seed.sql` | Optional development RBAC seed for ETP Core UI |

**Clean database:**

1. Create database `etp_iam` and user with DDL privileges.
2. Set `ETP_IAM_DB_*` environment variables.
3. Start IAM service — Flyway applies V1 and V2 automatically.

### Legacy manual apply

`schema_updated.sql` remains as a reference copy. For new deployments, prefer Flyway via service startup.

### Hibernate validation

`spring.jpa.hibernate.ddl-auto=validate` — schema must match entities after Flyway runs.

---

## Keycloak setup

Minimum configuration for the **existing** IAM service (no Admin API automation in this repository).

### Realm

| Setting | Value |
|---------|-------|
| Realm name | `etp` (or match `ETP_IAM_KEYCLOAK_REALM`) |
| Issuer | `{ETP_IAM_KEYCLOAK_BASE_URL}/realms/{realm}` — must match `ETP_IAM_KEYCLOAK_ISSUER_URI` |

### Human login client (IAM broker)

| Setting | Value |
|---------|-------|
| Client ID | Match `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID` (example: `etp-core-ui-login`) |
| Client type | **Confidential** (client authentication ON) |
| Direct access grants | **ON** (Phase 1 development — password grant via IAM server only) |
| Client secret | Set in `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET` — **[REDACTED]** |
| Expose to browser | **NO** — secret must not be in Core UI |

### Service clients

Services obtain tokens via `POST /api/v1/auth/token` with `grant_type=client_credentials`. Register clients in Keycloak with client credentials enabled. Pass `client_id` and `client_secret` in the token request.

### Token endpoint

```
{ETP_IAM_KEYCLOAK_BASE_URL}/realms/{ETP_IAM_KEYCLOAK_REALM}/protocol/openid-connect/token
```

Configured in IAM as `keycloak.token-url`.

### Test user

Create a realm user for Core UI Sign In. After first login, note the user's JWT `sub` claim and align the IAM `principals.external_id` row (see Seed requirements).

---

## Startup order

1. **PostgreSQL** — database `etp_iam` reachable.
2. **Keycloak** — realm and clients configured; issuer URL reachable from IAM.
3. **ETP IAM service** — Flyway migrations, then application listens on configured port.

---

## Health and readiness

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `GET /actuator/health` | Public (`permitAll`) | Aggregate health |
| `GET /actuator/health/liveness` | Public | Kubernetes liveness (when probes enabled) |
| `GET /actuator/health/readiness` | Public | Readiness (DB + Keycloak indicators) |

Health contributors:

| Indicator | Checks |
|-----------|--------|
| `databaseConnectivity` | PostgreSQL `connection.isValid(2)` |
| `keycloakRealm` | HTTP GET `{base}/realms/{realm}` |
| Spring Boot `db` | Datasource health (when enabled) |

`management.endpoint.health.show-details=never` — unauthenticated callers see status only, no sensitive details.

---

## Seed requirements (ETP Core UI Phase 1)

### Login (`POST /auth/login`)

Requires Keycloak user + login client only. **No IAM PostgreSQL principal required** for login to succeed.

### Authorization (`POST /authorization/check`)

Requires:

1. Keycloak JWT with `iss` and `sub` matching an IAM `principals` row (`issuer` + `external_id`).
2. Principal `status = ACTIVE`.
3. RBAC chain: principal → group → role → `role_application_permissions` → IAM `application` + `permission`.

### Flyway dev seed (`V2__platform_dev_seed.sql`)

Seeds:

| Item | Values |
|------|--------|
| IAM application | code `etp-core-ui`, name `Identity & Access Management` |
| Permission | code `CREATE_IAM`, name `Create IAM` |
| Role | `platform-admin` |
| Group | `platform-admins` |
| Principal | `external_id = REPLACE_WITH_KEYCLOAK_SUB` — **must be updated** |

After creating a Keycloak user:

```sql
UPDATE principals
SET external_id = '<keycloak-user-sub>'
WHERE principal_id = 'a1000008-0000-4000-8000-000000000008';
```

Authorization check example (matches tests):

```json
{
  "application": "Identity & Access Management",
  "permission": "Create IAM"
}
```

Application and permission match by **code or name** (`RoleApplicationPermissionRepository`).

---

## Deployment

### Maven artifact

```bash
cd capabilities/etp-iam
./mvnw clean package -DskipTests
```

Artifact: `target/etp-iam-0.0.1-SNAPSHOT.jar`

### Run locally

```bash
export ETP_IAM_DB_PASSWORD=[REDACTED]
export ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID=etp-core-ui-login
export ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET=[REDACTED]
./mvnw spring-boot:run
```

Optional SQL logging: `SPRING_PROFILES_ACTIVE=dev`

### Docker image

```bash
./mvnw clean package -DskipTests
docker build -t etp-iam:local .
docker run --rm -p 8081:8081 \
  -e ETP_IAM_DB_URL=jdbc:postgresql://host.docker.internal:55432/etp_iam \
  -e ETP_IAM_DB_USERNAME=etp_admin \
  -e ETP_IAM_DB_PASSWORD=[REDACTED] \
  -e ETP_IAM_KEYCLOAK_ISSUER_URI=http://host.docker.internal:8180/realms/etp \
  -e ETP_IAM_KEYCLOAK_BASE_URL=http://host.docker.internal:8180 \
  -e ETP_IAM_KEYCLOAK_REALM=etp \
  -e ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID=etp-core-ui-login \
  -e ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET=[REDACTED] \
  etp-iam:local
```

Configuration remains environment-driven; no secrets in the image.

### Local infrastructure (Docker Compose)

From `capabilities/etp-iam/`:

```bash
cp .env.example .env
docker compose --env-file .env up -d
```

Default ports: Keycloak **8180**, Postgres **55432**, pgAdmin **5050**.

---

## Smoke tests

Replace host/port and credentials as needed.

### Health

```bash
curl -s http://localhost:8081/actuator/health
```

Expected: `"status":"UP"` (when Postgres and Keycloak are reachable).

### Human login

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<user>","password":"<password>"}'
```

Expected: `accessToken`, `user`, `expiresAt`. Password **[REDACTED]** in commands/logs.

### Service token

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"client_id":"<client>","client_secret":"[REDACTED]","grant_type":"client_credentials"}'
```

### Authorization check

```bash
curl -s -X POST http://localhost:8081/api/v1/authorization/check \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"application":"Identity & Access Management","permission":"Create IAM"}'
```

Expected: `{"authorized":true}` when principal and RBAC seed align with JWT.

### OpenAPI

```bash
curl -s http://localhost:8081/v3/api-docs | head
```

---

## Production configuration notes

| Topic | Recommendation |
|-------|----------------|
| SQL logging | Default off; use `spring.profiles.active=dev` for local SQL |
| Secrets | Environment or secret manager only |
| HTTPS | Required for login/token in deployed environments |
| Dev seed | Update or replace `REPLACE_WITH_KEYCLOAK_SUB` before relying on authorization |
| Capability registry | `etp.iam` v1.0.0 registered and **ACTIVE** — see **ETP Capability Registry** section above |

---

*Deployment guide for the ETP IAM service. Capability registry metadata is maintained separately in `etp_platform`.*
