# ETP IAM — Current Implementation

Analysis of the **implemented** ETP IAM capability (`capabilities/etp-iam/`) in this repository. Source of truth: Java code, `schema_updated.sql`, configuration, and tests. No code was modified to produce this document.

**There is currently no IAM administration or configuration UI.**

---

# 1. ETP-IAM Overview

## What ETP-IAM is responsible for

| Responsibility | Implementation evidence |
|----------------|-------------------------|
| **Human authentication broker** | `POST /api/v1/auth/login` — accepts username/password, calls Keycloak token endpoint on behalf of the client (`AuthService`, `KeycloakTokenClient`) |
| **Service authentication broker** | `POST /api/v1/auth/token` — `client_credentials` grant proxied to Keycloak |
| **JWT validation** on IAM-protected endpoints | Spring Security OAuth2 Resource Server (`SecurityConfig`, `issuer-uri`) |
| **Authorization decisions** | `POST /api/v1/authorization/check` — PostgreSQL RBAC evaluation (`AuthorizationService`) |
| **Identity mapping** | JWT `iss` + `sub` → IAM `principals` row |
| **OpenAPI documentation** | SpringDoc (`OpenApiConfig`) |

Capability code (documented): `etp.iam`. Implementation artifact: Spring Boot service `etp-iam` on port **8081** (default).

## What ETP-IAM is NOT responsible for

| Not responsible | Notes |
|-----------------|-------|
| **Business application logic** | IAM is a reusable capability service, not a business app |
| **ETP Core UI** | Core UI is a separate consumer (`etp-core-ui`) |
| **Platform Capability Registry APIs** | `etp-platform-service` permits `/api/v1/capabilities/**` without JWT validation today |
| **Password storage** | No passwords in `etp_iam` database; Keycloak holds credentials |
| **User self-registration / admin CRUD APIs** | No REST APIs to create principals, groups, roles, or permissions |
| **OIDC browser redirect / PKCE** | Not implemented |
| **Token refresh / logout / `/me`** | Endpoints not implemented |
| **Keycloak realm provisioning** | Manual Keycloak admin console; no realm export in repo |
| **IAM data writes via service** | No `repository.save()` calls in application code — DB is read-only from the service |

## Architectural position

```
ETP Core UI (or other consumer)
        |
        |  HTTP (login: username/password)
        v
   ETP IAM Service (etp-iam :8081)
        |
        +---> Keycloak (:8180)     token issuance, credential validation
        |
        +---> PostgreSQL etp_iam   authorization metadata (read-only from service)
```

ETP IAM is an **independently deployable** Spring Boot service. It does **not** depend on ETP Core UI at runtime.

## Keycloak integration model

| Layer | Role |
|-------|------|
| **Keycloak** | Identity provider: users, credentials, OAuth2/OIDC clients, JWT issuance, realm roles in token |
| **ETP IAM** | Protocol broker for token acquisition; JWT validation for its APIs; authorization policy evaluation in PostgreSQL |

## Is Keycloak exposed directly to applications?

**For Phase 1 human login: No** — ETP Core UI calls **ETP IAM** (`POST /api/v1/auth/login`), not Keycloak directly. IAM uses a **confidential server-side client** (`keycloak.login-client-id` / `keycloak.login-client-secret`) to perform the password grant against Keycloak.

**For service-to-service:** Callers may use `POST /api/v1/auth/token` on IAM (which forwards to Keycloak), or could call Keycloak token URL directly — the repository documents IAM as the integration point; direct Keycloak usage by apps is not implemented in Core UI.

**JWT consumption:** After login, consumers hold the Keycloak-issued JWT. IAM validates JWTs on `/api/v1/authorization/check`. Other services (e.g. platform-service) do **not** validate IAM JWTs in the current codebase.

## ETP owns vs Keycloak owns

| ETP owns (`etp_iam` + IAM service) | Keycloak owns |
|-----------------------------------|---------------|
| Principals (`issuer` + `external_id` mapping) | User credentials (passwords) |
| Groups, roles, permissions (IAM schema) | OAuth2 clients and client secrets (configured in Keycloak) |
| IAM `applications`, `resources`, `permissions` (authz registry) | Realm, realm roles (surfaced in JWT `realm_access.roles` on login) |
| Companies, tenants, scopes | Token format, signing keys, session lifecycle |
| Group–tenant assignments | Admin API / user federation (not used by IAM code) |
| Authorization check API | OIDC/OAuth2 protocol endpoints |

**Important:** IAM authorization checks use **PostgreSQL RBAC**, not Keycloak realm roles. Realm roles appear in `LoginResponse.user.roles` for display only (`AccessTokenUserMapper`).

---

# 2. Repository Structure

Module root: `capabilities/etp-iam/`

## Controllers

| Class | Path | Package |
|-------|------|---------|
| `AuthController` | `/api/v1/auth` | `com.etp.iam.authentication` |
| `AuthorizationController` | `/api/v1/authorization` | `com.etp.iam.authorization` |

## Services

| Class | Package | Role |
|-------|---------|------|
| `AuthService` | `authentication` | Login and token orchestration |
| `AuthorizationService` | `authorization` | JWT → principal → RBAC + tenant check |
| `KeycloakTokenClient` | `authentication` | HTTP client to Keycloak token endpoint |

## Domain / model (JPA entities)

| Entity | Table | Package |
|--------|-------|---------|
| `Company` | `companies` | `entity` |
| `Tenant` | `tenants` | `entity` |
| `Application` | `applications` | `entity` |
| `Resource` | `resources` | `entity` |
| `Permission` | `permissions` | `entity` |
| `Principal` | `principals` | `entity` |
| `Group` | `groups` | `entity` |
| `Role` | `roles` | `entity` |
| `Scope` | `scopes` | `entity` |
| `PrincipalGroupMapping` | `principal_group_mappings` | `entity` |
| `GroupRoleMapping` | `group_role_mappings` | `entity` |
| `RoleApplicationPermission` | `role_application_permissions` | `entity` |
| `GroupTenantAssignment` | `group_tenant_assignments` | `entity` |

Enums: `PrincipalType` (`USER`, `SERVICE`), `GroupType`, `RoleType`, `ResourceType`.

## Repositories

All under `com.etp.iam.repository`:

`CompanyRepository`, `TenantRepository`, `ApplicationRepository`, `ResourceRepository`, `PermissionRepository`, `PrincipalRepository`, `GroupRepository`, `RoleRepository`, `ScopeRepository`, `PrincipalGroupMappingRepository`, `GroupRoleMappingRepository`, `RoleApplicationPermissionRepository`, `GroupTenantAssignmentRepository`.

## Clients

| Class | Package | Role |
|-------|---------|------|
| `KeycloakTokenClient` | `authentication` | `RestClient` POST to `keycloak.token-url` |
| `RestClient` bean | `config.RestClientConfig` | Default `RestClient.create()` |

No Keycloak Admin REST client in the repository.

## Configuration

| Class / file | Role |
|--------------|------|
| `config.SecurityConfig` | Spring Security filter chain, JWT resource server |
| `config.CorsConfig` | CORS for `/api/v1/auth/**` only |
| `config.OpenApiConfig` | OpenAPI info + bearer-jwt scheme |
| `config.RestClientConfig` | RestClient bean |
| `src/main/resources/application.properties` | Datasource, Keycloak URLs, CORS |
| `.env.example` | Local env variable template |
| `docker-compose.yml` | Keycloak, Postgres, pgAdmin |

## Security / JWT

- `SecurityConfig` — stateless sessions; `permitAll` on auth + swagger + `/actuator/health`; `oauth2ResourceServer().jwt()` for all other routes
- JWT issuer: `spring.security.oauth2.resourceserver.jwt.issuer-uri`
- No custom JWT filters beyond Spring defaults

## Exception handling

| Class | Scope |
|-------|-------|
| `AuthExceptionHandler` | `AuthController` — validation, grant type, Keycloak auth/token errors |
| `AuthorizationExceptionHandler` | `AuthorizationController` — validation, `AuthorizationException` |
| `KeycloakAuthenticationException`, `KeycloakTokenException`, `InvalidGrantTypeException` | `authentication` |
| `AuthorizationException` | `authorization` |

## DTOs

| Record | Package |
|--------|---------|
| `LoginRequest`, `LoginResponse`, `TokenRequest`, `TokenResponse`, `UserResponse` | `authentication.dto` |
| `AuthorizationCheckRequest`, `AuthorizationCheckResponse` | `authorization.dto` |
| `AuthApiErrorResponse` | `openapi` |

## Helpers

| Class | Role |
|-------|------|
| `AccessTokenUserMapper` | Decode JWT payload (Base64) for login user profile; extract `realm_access.roles` |

## Tests

| Test class | Focus |
|------------|-------|
| `AuthControllerTest` | Auth HTTP layer |
| `AuthServiceTest` | Login/token service |
| `KeycloakTokenClientTest` | Keycloak HTTP client |
| `AuthorizationControllerTest` | Authorization HTTP layer |
| `AuthorizationServiceTest` | RBAC + tenant logic |
| `OpenApiDocumentationTest` | OpenAPI paths |
| `JpaSchemaValidationTest` | Hibernate validate against live Postgres |
| `IamServiceApplicationTests` | Context load |

Path: `capabilities/etp-iam/src/test/java/com/etp/iam/`

## Schema (not Flyway)

`capabilities/etp-iam/schema_updated.sql` — manual DDL for `etp_iam` (13 tables).

---

# 3. Authentication

## Implemented flows

### Human login (Phase 1)

```
Client (e.g. ETP Core UI)
   |  POST /api/v1/auth/login  { username, password }
   v
ETP IAM (AuthController → AuthService)
   |  POST Keycloak token URL (form: grant_type=password, client_id, client_secret, username, password)
   v
Keycloak
   |  JWT access_token (+ expires_in)
   v
ETP IAM
   |  LoginResponse { user, accessToken, expiresAt }
   v
Client stores accessToken (Core UI: AuthSession / localStorage or sessionStorage)
```

- **Password grant** uses IAM-configured confidential client (`keycloak.login-client-id`, `keycloak.login-client-secret`) — **not** exposed to the browser.
- **User profile** built by decoding JWT payload locally (`AccessTokenUserMapper`) — not a separate Keycloak user API call.
- **Evidence:** `AuthService.login()`, `KeycloakTokenClient.requestPasswordToken()`, `etp-core-ui/src/features/auth/iamAuthClient.ts`

### Service-to-system (`client_credentials`)

```
Service client
   |  POST /api/v1/auth/token  { client_id, client_secret, grant_type: "client_credentials" }
   v
ETP IAM (AuthService → KeycloakTokenClient)
   |  POST Keycloak token URL
   v
Keycloak → TokenResponse (OAuth2 shape)
```

- Only `grant_type=client_credentials` accepted; others → `InvalidGrantTypeException` (400).
- **Evidence:** `AuthService.requestToken()`, `AuthService.validateGrantType()`

### JWT validation (on IAM protected routes)

```
Client
   |  Authorization: Bearer <JWT>
   v
ETP IAM Spring Security OAuth2 Resource Server
   |  Validates JWT against issuer-uri (JWKS from Keycloak)
   v
Controller receives @AuthenticationPrincipal Jwt
```

- Used on `POST /api/v1/authorization/check`.
- **Evidence:** `SecurityConfig`, `AuthorizationController.check()`

## Not implemented

| Feature | Status |
|---------|--------|
| Token introspection endpoint | **NO** — validation via JWT signature/issuer only |
| Refresh token handling / `/auth/refresh` | **NO** — `TokenResponse.refreshExpiresIn` may be parsed from Keycloak but no refresh API |
| OIDC Authorization Code + PKCE | **NO** |
| `/auth/logout`, `/auth/me` | **NO** |
| Keycloak end-session logout | **NO** |
| Automatic principal provisioning on first login | **NO** — principal must exist in DB for authorization |

---

# 4. Authorization

## Model (IAM PostgreSQL — enforced in `AuthorizationService`)

```
JWT (iss + sub)
   → principals (USER or SERVICE, status ACTIVE)
   → principal_group_mappings
   → groups (status ACTIVE)
   → group_role_mappings
   → roles (status ACTIVE)
   → role_application_permissions
   → applications (match application code/name) + permissions (match permission code/name)
   → resources (status ACTIVE)
```

**Optional tenant gate** (when `tenant` provided in check request):

```
groups
   → group_tenant_assignments (status ACTIVE, date range, tenant ACTIVE, scope ACTIVE if present)
   → tenants (match tenant code or name)
   → optional scopes
```

## Concepts and storage

| Concept | Stored in | Enforced by | Notes |
|---------|-----------|-------------|-------|
| **Roles** | IAM `roles` | `GroupRoleMappingRepository`, `RoleApplicationPermissionRepository` | Not Keycloak realm roles |
| **Permissions** | IAM `permissions` → `resources` | `existsActivePermissionForRoles()` | Matched by code or name |
| **Scopes** | IAM `scopes` | `GroupTenantAssignment` effective check | Optional on tenant assignment |
| **Resources** | IAM `resources` | Permission query requires `res.status = ACTIVE` | Types: `API`, `FEATURE` (DB check) |
| **Policies** | — | **Not implemented** | No policy engine or policy table |
| **Tenant context** | IAM `tenants` under `companies` | Optional parameter on `/authorization/check` | Not automatic from JWT |
| **Application context** | IAM `applications` | Required field on check request | Platform-level IAM app registry |

## Keycloak authorization concepts (not used for `/authorization/check`)

Keycloak stores users, groups, realm roles, clients. IAM login response may include **realm roles** from JWT (`AccessTokenUserMapper.extractRealmRoles`), but **`AuthorizationService` does not read JWT roles for authorization decisions**.

---

# 5. Tenant / Customer Security

## IAM database

| Concept | Table | Notes |
|---------|-------|-------|
| **Company** | `companies` | Org root for tenants |
| **Tenant** | `tenants` | `company_id` FK; optional `environment` column |
| **User → tenant** | Indirect | Principal → groups → `group_tenant_assignments` → tenant |
| **Application → tenant** | **Not modeled** | IAM `applications` are platform-level; no tenant FK on applications |
| **Service → tenant** | Indirect | `PrincipalType.SERVICE` principal same group/tenant path as USER |

## Platform database (`etp_platform`)

| Concept | Present? |
|---------|----------|
| **Customer** | `customer` table — **separate** from IAM `companies` |
| **Tenant** | **Not in platform DB** |
| Link IAM tenant ↔ platform customer | **Not in repository** |

## Tenant isolation

**PARTIAL** — Tenant constraints apply only when:

1. Caller invokes `POST /api/v1/authorization/check` with a non-blank `tenant` field, and
2. Principal has an effective `group_tenant_assignment` for that tenant (code or name match).

If `tenant` is omitted, authorization succeeds when permission matches, **without tenant scoping**.

**Tenant isolation is not enforced** on:

- Login endpoints (public)
- JWT validation itself
- ETP Platform Service APIs (no JWT enforcement today)
- Automatic tenant extraction from JWT claims

There is **no** multi-tenant row-level isolation in the IAM service layer beyond the optional check above.

**Customer** (platform) vs **company/tenant** (IAM) are **distinct models** with no bridging code in this repository.

There is **no** `business unit` entity in IAM or platform schemas analyzed.

---

# 6. Service-to-Service Security

| Capability | Status | Evidence |
|------------|--------|----------|
| **Service identity** | **PARTIAL** | `PrincipalType.SERVICE` in schema/entity; must exist as `principals` row with Keycloak `iss`+`sub` |
| **Client credentials** | **YES** | `POST /api/v1/auth/token`, grant_type `client_credentials` only |
| **Machine-to-machine auth** | **YES** | Via Keycloak client credentials through IAM broker |
| **JWT service tokens** | **YES** | Keycloak-issued JWT returned from token endpoint |
| **API/application identity** | **PARTIAL** | IAM `applications` table names logical apps for permission checks; OAuth client identity is Keycloak `client_id` |

Service clients obtaining tokens via `/auth/token` are **not** automatically linked to IAM `principals` unless a matching `principals` row exists for the JWT subject issued to that client.

---

# 7. Keycloak Integration

## Configuration properties

From `application.properties` and `.env.example` (secrets shown as **[REDACTED]**):

| Property | Purpose | Example default |
|----------|---------|-----------------|
| `ETP_IAM_KEYCLOAK_BASE_URL` | Keycloak base URL | `http://localhost:8180` |
| `ETP_IAM_KEYCLOAK_REALM` | Realm name | `etp` |
| `ETP_IAM_KEYCLOAK_ISSUER_URI` | JWT issuer for resource server | `http://localhost:8180/realms/etp` |
| `keycloak.token-url` | Token endpoint (derived) | `{base}/realms/{realm}/protocol/openid-connect/token` |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID` | Confidential client for password grant | `etp-core-ui-login` (example) |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET` | Client secret | **[REDACTED]** |
| `ETP_IAM_KEYCLOAK_ADMIN_USERNAME` | Keycloak bootstrap admin (docker) | `admin` |
| `ETP_IAM_KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin password | **[REDACTED]** |

## Realm / clients / roles

| Item | Repository state |
|------|------------------|
| **Realm** | Documented as `etp`; must be created manually in Keycloak |
| **Login client** | Confidential client with Direct Access Grant enabled (manual setup per README) |
| **Service clients** | Any Keycloak client supporting `client_credentials`; passed per `/auth/token` request |
| **Client roles** | **Not used** by IAM authorization code |
| **Realm roles** | Exposed in login `user.roles` from JWT only |
| **Groups** | Keycloak groups **not** synced; IAM `groups` table is separate |
| **Scopes** | OAuth `scope` in token response; IAM `scopes` table is separate authorization concept |

## OIDC endpoints used

| Endpoint | Used by IAM? |
|----------|--------------|
| `{realm}/protocol/openid-connect/token` | **YES** — `KeycloakTokenClient` |
| Authorization / logout / introspection / JWKS | JWKS implied by resource server for JWT validation; no direct Admin API calls |

## Admin API

**Not used** in application code. Realm and users configured via Keycloak admin UI (or external automation not in this repo).

## Docker

`docker-compose.yml`: Keycloak `start-dev` on host port **8180**, persistent volume `etp-keycloak-data`.

---

# 8. ETP IAM APIs

All endpoints are in package `com.etp.iam`. Base URL default: `http://localhost:8081`.

| Method | Path | Auth | Authorization | Purpose | Controller |
|--------|------|------|---------------|---------|------------|
| `POST` | `/api/v1/auth/login` | **Public** (`permitAll`) | None | Human username/password login → JWT | `AuthController.login` |
| `POST` | `/api/v1/auth/token` | **Public** | None | Service `client_credentials` token | `AuthController.requestToken` |
| `POST` | `/api/v1/authorization/check` | **Bearer JWT required** | Evaluates RBAC for JWT principal | Permission check (+ optional tenant) | `AuthorizationController.check` |

### `POST /api/v1/auth/login`

**Request:** `LoginRequest` — `username` (required), `password` (required)

**Response:** `LoginResponse` — `user` (`UserResponse`), `accessToken`, `expiresAt` (ISO-8601 UTC string)

**Errors:** 400 validation; 401 invalid credentials; 502 token service failure

### `POST /api/v1/auth/token`

**Request:** `TokenRequest` — `client_id`, `client_secret`, `grant_type` (must be `client_credentials`)

**Response:** `TokenResponse` — `access_token`, `expires_in`, `token_type`, `scope`, optional `refresh_expires_in`

**Errors:** 400 unsupported grant; 401 invalid client; 502 Keycloak unavailable

### `POST /api/v1/authorization/check`

**Request:** `AuthorizationCheckRequest` — `application` (required), `permission` (required), `tenant` (optional)

**Response:** `AuthorizationCheckResponse` — `{ "authorized": boolean }`

**Auth:** Valid Keycloak JWT; principal must exist in IAM DB and be ACTIVE

**Errors:** 401 invalid identity / principal not found; 403 inactive principal; 400 validation

### Documentation / infra endpoints

| Path | Auth | Notes |
|------|------|-------|
| `/v3/api-docs`, `/swagger-ui/**` | Public | SpringDoc |
| `/actuator/health` | Public in `SecurityConfig` | **No** `spring-boot-starter-actuator` in `pom.xml` — endpoint may not be active |

---

# 9. Database Usage

## Which database?

| Store | Used? | Purpose |
|-------|-------|---------|
| **ETP Platform DB** (`etp_platform`) | **NO** | IAM does not connect to platform database |
| **IAM DB** (`etp_iam`) | **YES** | Authorization metadata |
| **Keycloak DB** | **YES** (Keycloak internal) | Inside Keycloak container volume — not accessed by IAM Java code |
| **No database** | — | Authentication delegated to Keycloak |

## IAM tables (13)

From `schema_updated.sql`:

`companies`, `tenants`, `applications`, `resources`, `permissions`, `principals`, `groups`, `principal_group_mappings`, `roles`, `group_role_mappings`, `role_application_permissions`, `scopes`, `group_tenant_assignments`

## What is stored where

| Data | Location |
|------|----------|
| Passwords | Keycloak only |
| JWT signing keys | Keycloak |
| OAuth clients / secrets | Keycloak (+ IAM env for login client secret) |
| Principal identity mapping | `etp_iam.principals` (`issuer`, `external_id`, `principal_type`) |
| RBAC graph | `groups`, `roles`, `permissions`, `resources`, mappings |
| Tenant assignments | `group_tenant_assignments`, `tenants`, `companies`, `scopes` |
| IAM application registry | `applications` (authorization app codes — not platform customer apps) |

## Service write behavior

The IAM Spring Boot application performs **no database writes** (`grep` finds no `save(` in `src/main/java`). All authorization metadata is expected to be inserted/updated **outside** the service (SQL, scripts, future admin UI).

---

# 10. Security Enforcement

## Components in request path

| Component | Role in repo |
|-----------|--------------|
| **API Gateway** | **Not present** |
| **Spring Security (ETP IAM)** | JWT validation + route `permitAll` vs authenticated |
| **ETP IAM authorization layer** | `AuthorizationService` on `/authorization/check` only |
| **Keycloak** | Token issuance and credential validation |
| **Platform Spring Security** | Separate service; capability APIs `permitAll` |
| **Business application layer** | Not applicable inside IAM module |

## Typical flows

**Login:**

```
Browser → ETP IAM /auth/login (CORS on auth paths) → Keycloak token → response to browser
```

**Authorization check:**

```
Client → ETP IAM /authorization/check + Bearer JWT
         → Spring Security JWT validation (Keycloak JWKS)
         → AuthorizationService → PostgreSQL read queries → boolean
```

**Platform capability API (current):**

```
Core UI → etp-platform-service /api/v1/capabilities
         → SecurityConfig permitAll (Bearer may be sent but not validated)
```

---

# 11. Current IAM Architecture

```
+------------------+       +------------------+       +------------------+
|  ETP Core UI     |       |  Other clients   |       |  Backend service |
|  (browser)       |       |  (future)        |       |  (M2M)           |
+--------+---------+       +--------+---------+       +--------+---------+
         |                          |                          |
         | POST /auth/login         |                          | POST /auth/token
         v                          v                          v
+============================================================================+
|                        ETP IAM Service (Spring Boot :8081)                |
|  AuthController | AuthorizationController | SecurityConfig (JWT RS)       |
|  AuthService | AuthorizationService | KeycloakTokenClient                  |
+========+===============================+==================================+
         |                               |
         | password / client_credentials |  SELECT (read-only)
         v                               v
+------------------+             +------------------+
|    Keycloak      |             |  PostgreSQL      |
|    (:8180)       |             |  etp_iam         |
|  users, clients, |             |  principals,     |
|  JWT issuance    |             |  groups, roles,  |
+------------------+             |  permissions,    |
                                 |  tenants, ...    |
                                 +------------------+

JWT returned to client -----> (optional) POST /authorization/check on IAM
                              (other services: platform APIs not JWT-gated today)
```

---

# 12. Current Capabilities

| Capability | Implemented? | Evidence |
|------------|--------------|----------|
| User authentication | **YES** | `POST /auth/login`, `AuthService.login` |
| Service authentication | **YES** | `POST /auth/token`, `client_credentials` only |
| JWT validation | **YES** (IAM APIs) | `SecurityConfig` oauth2ResourceServer JWT |
| Authorization | **YES** | `POST /authorization/check`, `AuthorizationService` |
| Roles | **YES** (IAM DB) | `roles`, `group_role_mappings`; not Keycloak roles |
| Permissions | **YES** | `permissions`, `role_application_permissions` |
| Scopes | **PARTIAL** | `scopes` + tenant assignments; not OAuth scopes in authz logic |
| Tenant context | **PARTIAL** | Optional on check API; group_tenant_assignments |
| Service identity | **PARTIAL** | `PrincipalType.SERVICE`; manual principal rows |
| Client management | **NO** (IAM API) | Keycloak admin only |
| User management | **NO** (IAM API) | Keycloak admin + manual SQL for principals |
| Role management | **NO** (IAM API) | Manual SQL / DB tools |
| Permission management | **NO** (IAM API) | Manual SQL / DB tools |
| Audit | **NO** | No audit tables or events in IAM service |
| Token refresh | **NO** | No endpoint |
| Logout / end session | **NO** | No endpoint |
| OIDC PKCE / browser redirect | **NO** | Password grant via IAM only |
| Principal auto-provisioning | **NO** | Principal must pre-exist for authorization |
| IAM metadata write APIs | **NO** | No repository saves in service code |
| Health actuator | **NO** | Permitted in security but dependency missing |
| Admin UI | **NO** | Explicitly out of scope |

---

# 13. Missing IAM Administration UI

Future UI would administer data currently managed **manually** (SQL / Keycloak console). Grouped by what the **current schema and APIs** support.

## Platform Admin (IAM operators)

Capabilities aligned with existing tables and Keycloak setup:

| Area | Current state | Future UI would manage |
|------|---------------|------------------------|
| IAM applications | DB table, no API | `applications` registry (codes, names, status) |
| Resources | DB table | `resources` per application (API/FEATURE) |
| Permissions | DB table | `permissions` per resource |
| Roles | DB table | `roles`, `group_role_mappings` |
| Groups | DB table | `groups`, `principal_group_mappings` |
| Principals | DB table | Map Keycloak users/services to `principals` |
| Scopes | DB table | `scopes` definitions |
| Companies | DB table | `companies` |
| Tenants | DB table | `tenants` under companies |
| Group–tenant access | DB table | `group_tenant_assignments` |
| Keycloak clients | Keycloak only | Client registration (likely Keycloak admin or sync) |
| Realm / login client config | Env + Keycloak | Confidential client, grant types, redirect URIs (Phase 2+) |

Not supported by current schema (do not plan UI without schema work):

- IAM **policies** as a distinct object
- **Customer** (`etp_platform.customer`) — different database

## Tenant Admin (tenant-scoped operators)

Based on **group + tenant assignment** model (not direct principal–tenant):

| Area | Future UI concept |
|------|-------------------|
| Tenant users | Principals in groups with tenant assignments |
| Tenant-scoped group membership | `principal_group_mappings` within tenant-administered groups |
| Tenant roles | Groups ↔ roles affecting tenant assignments |
| Application access | Roles ↔ `role_application_permissions` for IAM applications |
| Tenant access windows | `group_tenant_assignments` start/end dates, scope |

**Tenant admin UI** would only make sense once **write APIs or admin services** exist; today all mutations are out-of-band.

---

# 14. IAM Capability Contract Readiness

Evaluation for registering `etp.iam` in the ETP Capability Registry as a formal capability.

## Already have (implementation artifacts)

| Contract area | Current state |
|---------------|---------------|
| **Capability identity** | Code `etp.iam`, name/description in README and `pom.xml` |
| **Version** | Maven `0.0.1-SNAPSHOT` — not semver-published as capability version in registry |
| **APIs** | 3 REST endpoints; OpenAPI at `/v3/api-docs` |
| **Configuration** | Env-driven Keycloak, DB, CORS (`application.properties`, `.env.example`) |
| **Dependencies** | Runtime: PostgreSQL, Keycloak; Spring Boot stack in `pom.xml` |
| **Security** | Auth + authz flows documented; JWT resource server |
| **Deployment** | Spring Boot jar; `docker-compose.yml` for dependencies |
| **Implementation** | Deployable `etp-iam` service; technology Java 21 / Spring Boot 4.1 |

## Partial / informal

| Area | Gap |
|------|-----|
| **NFR** | No documented SLOs, rate limits, or HA guidance in code |
| **Observability** | No actuator dependency; `show-sql=true` in default properties |
| **Contract document** | No `capability_contract` JSON in platform registry for `etp.iam` verified in this analysis |
| **Inline API contract** | OpenAPI only — not linked to Capability Registry document sections |

## Missing for formal ETP IAM capability contract

| Item | Notes |
|------|-------|
| Registered capability row in `etp_platform` | Not verified as seeded in repo migrations |
| Versioned capability contract in registry | Structured functional/interfaces/security/NFR sections |
| Lifecycle/version in Capability Registry | IAM service version vs capability version alignment |
| Dependency declaration in registry | e.g. dependency on Keycloak as external system |
| External contract references | OpenAPI URL as `location` if not inline |
| Health/readiness contract | Actuator endpoints |
| Authorization API as part of published contract | `/authorization/check` request/response in registry document |
| Tenant model documentation in contract | How consumers pass `tenant` |
| Phase 2 auth flows | OIDC/PKCE — explicitly future |

---

# 15. Architecture Risks / Findings

1. **Keycloak coupling** — All tokens from Keycloak; password grant is a dev-phase choice; production SSO path not implemented.

2. **Dual role systems** — Keycloak realm roles vs IAM DB roles; login shows realm roles but authorization ignores them — confusion risk for operators.

3. **No principal auto-provisioning** — Login succeeds without IAM `principals` row; authorization fails until manual SQL.

4. **IAM DB read-only from service** — No admin APIs; operational burden and drift risk between Keycloak users and IAM principals.

5. **Tenant isolation optional** — Omitting `tenant` on check allows cross-tenant permission match if RBAC grants permission globally.

6. **Two `application` concepts** — IAM `applications` vs `etp_platform.application` vs Keycloak clients — naming collision.

7. **Platform APIs not JWT-protected** — Core UI token not validated by platform-service; IAM authz not applied to Capability Registry.

8. **Secrets in environment** — Login client secret in IAM env (correct for server-side); must not leak to UI.

9. **JWT payload parsing** — `AccessTokenUserMapper` manual Base64/JSON parse instead of verified claim API — fragile if token format changes.

10. **No audit trail** — No IAM audit events for login or authorization decisions.

11. **Limited observability** — No metrics/tracing; SQL logging enabled in default `application.properties`.

12. **CORS partial** — Only `/api/v1/auth/**`; browser calls to `/authorization/check` may need separate CORS if used from SPA directly.

13. **Actuator mismatch** — `SecurityConfig` permits `/actuator/health` but actuator not in dependencies.

14. **Schema/JPA nuance** — `GroupTenantAssignment.scope` JPA mapping is non-nullable; SQL allows NULL `scope_id` — potential validate mismatch.

15. **No Flyway for IAM** — Manual `schema_updated.sql` only; environment drift risk.

16. **Keycloak not provisioned from repo** — Realm/clients/users are manual; onboarding is undocumented beyond README bullets.

17. **SERVICE principals** — Enum exists but no distinct service-authz path beyond same principal/group model.

18. **Before UAM (admin UI) integration** — UAM will need write APIs or approved batch tooling; current service cannot persist admin changes.

---

# 16. Final Summary

## A. What ETP-IAM already provides

- Standalone Spring Boot authentication and authorization service (`etp.iam`)
- Human login API brokering Keycloak password grant (Phase 1)
- Service token API brokering `client_credentials`
- JWT validation on IAM protected routes
- PostgreSQL-backed RBAC authorization check with optional tenant scoping
- Full authorization schema (13 tables) and JPA entities
- OpenAPI/Swagger documentation
- Docker Compose for Keycloak + Postgres + pgAdmin
- Integration with ETP Core UI login (`iamAuthClient.ts`)

## B. What Keycloak provides

- User credential storage and validation
- OAuth2/OIDC token issuance (JWT)
- Client registry and client secrets for login and M2M clients
- Realm roles embedded in JWT (informational in login response)
- Admin console for users/clients/realm config

## C. What ETP owns

- IAM service code and API contract
- `etp_iam` authorization model (principals, groups, roles, permissions, tenants, scopes)
- Identity mapping policy (`issuer` + `sub` → principal)
- Authorization decision logic (`AuthorizationService`)
- Deployment configuration (env vars, docker-compose for local stack)
- Phase 1 integration pattern (Core UI → IAM, not browser → Keycloak)

## D. What is missing

- IAM administration UI and write APIs
- OIDC Authorization Code + PKCE, refresh, logout, `/me`
- Principal auto-provisioning and Keycloak↔IAM sync
- Audit, metrics, health actuator
- Flyway-managed IAM schema migrations
- Platform-service JWT enforcement and IAM permission integration
- Formal `etp.iam` entry in Capability Registry with versioned contract document
- Keycloak realm/client automation in repository
- Bridge between platform `customer` and IAM `company`/`tenant`

## E. What should NOT be built (already exists)

- New password store in ETP — Keycloak retains credentials
- Duplicate RBAC in Keycloak for IAM check path — IAM DB is the authorization source for `/authorization/check`
- Second login screen on Keycloak for Core UI Phase 1 — IAM login API is the intended path
- IAM metadata in `etp_platform` database — separate `etp_iam` DB is intentional
- Keycloak Admin API integration in IAM service — not present today; manual admin is current process

## F. What needs to be built before UAM integration

(UAM = future IAM administration / user access management UI consuming ETP-IAM)

1. **Write APIs or admin service layer** for principals, groups, roles, permissions, tenants, assignments (currently read-only from IAM app).
2. **Operational sync strategy** — Keycloak user/client lifecycle ↔ IAM `principals` (create/update/deactivate).
3. **Consistent tenant model documentation and APIs** — how UAM assigns `group_tenant_assignments`.
4. **Authorization for admin APIs** — protect future admin endpoints (likely same JWT + elevated permissions).
5. **Audit events** for administrative changes and sensitive auth operations.
6. **CORS and API surface** for browser-based UAM if it runs in Core UI shell.
7. **Health/readiness** endpoints for operations.
8. **Clarify IAM `applications` registry** — seed data for Core UI / platform admin permissions UAM will manage.

---

# Source References (index)

| Topic | Primary sources |
|-------|-----------------|
| Auth HTTP API | `authentication/AuthController.java` |
| Auth logic | `authentication/AuthService.java`, `KeycloakTokenClient.java` |
| Authorization | `authorization/AuthorizationController.java`, `AuthorizationService.java` |
| Security | `config/SecurityConfig.java`, `config/CorsConfig.java` |
| Schema | `schema_updated.sql` |
| Config | `application.properties`, `.env.example` |
| Core UI consumer | `etp-core-ui/src/features/auth/iamAuthClient.ts` |
| Platform security gap | `etp-platform-service/.../security/SecurityConfig.java` |
| Data model cross-ref | `ETP_CURRENT_DATA_MODEL.md` (repository root) |

---

*Documentation-only analysis. Repository state at time of writing. No secrets included.*
