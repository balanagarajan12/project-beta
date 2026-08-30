# ETP IAM — Capability Readiness

Assessment and onboarding record for the **ETP IAM** implementation (`capabilities/etp-iam/`) as a formal ETP capability in the Platform Capability Registry.

**Current state:** `etp.iam` **1.0.0** is **registered and ACTIVE** in the ETP Capability Registry.

**Sources of truth:** repository code, `ETP_CURRENT_DATA_MODEL.md`, `ETP_IAM_CURRENT_IMPLEMENTATION.md`, Capability Registry in `etp-platform-service`, and the live registry record in `etp_platform`.

---

# Final Capability Status

| Field | Value |
|-------|-------|
| **Capability code** | `etp.iam` |
| **Capability name** | ETP IAM |
| **Capability ID** | `d8c29697-1a31-4318-bcd7-665e684915e9` |
| **Capability version** | `1.0.0` |
| **Capability version ID** | `e8f1af6c-0d53-4fc3-bc94-5e2aea3781d3` |
| **Capability status** | `ACTIVE` |
| **Version status** | `ACTIVE` |
| **Registration** | Completed (`POST /api/v1/capabilities` → `201 Created`) |
| **Lifecycle** | `DRAFT` → `VALIDATED` → `ACTIVE` (first activation also transitioned parent capability `DRAFT` → `ACTIVE`) |

| Timestamp | Value | Meaning |
|-----------|-------|---------|
| Capability / version created | `2026-08-29T11:34:42Z` | Registry `created_at` on capability and version rows |
| Version activated | `2026-08-29T11:42:37Z` | Registry `effective_from` when version became `ACTIVE` |

Registration payload: `capabilities/etp-iam/etp-iam-capability-registration.json`

---

# Capability Registry Record

```
etp.iam
  │
  └── 1.0.0  (ACTIVE)
        ├── Contract        — inline JSON (`COMPOSITE`, contractVersion `1.0`) + OpenAPI `location` `/v3/api-docs`
        ├── Implementation  — `etp-iam` / ETP IAM Service / Spring Boot 4.1.0
        └── Dependencies    — none (registry `dependencies[]` is empty)
```

**Registry `dependencies[]` is empty** because no other ETP capability versions are required or registered for IAM runtime today. `DependencyRequest` entries require an existing `capabilityVersionId` in `etp_platform`; PostgreSQL and Keycloak are **not** registered ETP capabilities.

PostgreSQL and Keycloak remain **external infrastructure** dependencies. They are documented inside the inline contract (`contract.document.dependencies.external`, `deployment`, `configuration`) — not as registry capability dependencies.

Retrieve the live record: `GET /api/v1/capabilities/d8c29697-1a31-4318-bcd7-665e684915e9`

---

# Reference Capability

ETP-IAM is the **first capability onboarded** through the current ETP capability lifecycle and serves as the **reference pattern** for subsequent capabilities.

```
Existing implementation
      ↓
Capability readiness assessment
      ↓
Capability hardening
      ↓
Capability contract
      ↓
Contract validation
      ↓
Capability Registry registration
      ↓
DRAFT
      ↓
VALIDATED
      ↓
ACTIVE
```

Future capabilities should follow this **lifecycle and process**; implementation details (technology, APIs, external dependencies) remain capability-specific.

---

# Onboarding Lifecycle (ETP-IAM)

| Step | Action | Status |
|------|--------|--------|
| 1 | Assess existing implementation | **COMPLETE** |
| 2 | Harden implementation for ETP capability readiness | **COMPLETE** |
| 3 | Prepare capability contract | **COMPLETE** |
| 4 | Validate capability contract | **COMPLETE** |
| 5 | Register capability (`POST /api/v1/capabilities`) | **COMPLETE** |
| 6 | Transition version `DRAFT` → `VALIDATED` | **COMPLETE** |
| 7 | Transition version `VALIDATED` → `ACTIVE` | **COMPLETE** |
| 8 | Verify capability and version are `ACTIVE` | **COMPLETE** |

**Next step:** Proceed to the next ETP capability using ETP-IAM as the reference capability onboarding pattern.

---

# Capability Readiness Summary (post-registration)

| Area | Status |
|------|--------|
| Capability registry registration | **COMPLETE** |
| Capability activation | **COMPLETE** |
| Inline contract registered | **COMPLETE** |
| Implementation metadata registered | **COMPLETE** |
| Dependency metadata (`dependencies: []`) | **COMPLETE** |
| Service hardening (health, Flyway, deployment packaging) | **COMPLETE** (Step 1 implementation) |

### Non-blocking future enhancements

These do **not** prevent `etp.iam` from being **ACTIVE** in the registry:

| Topic | Notes |
|-------|-------|
| Principal `external_id` alignment | Manual step after Keycloak user creation (`REPLACE_WITH_KEYCLOAK_SUB` in dev seed) |
| Production Swagger/SpringDoc hardening | Profile-gate or disable `/v3/api-docs` and Swagger UI in production |
| Metrics / Prometheus | Not in IAM `pom.xml` today |
| Distributed tracing | Not implemented |
| IAM audit log for login/authorization decisions | No audit table in IAM service |
| Token refresh, logout, `/me`, OIDC PKCE | Phase 2+ product scope |
| IAM administration APIs / UAM UI | Out of scope for v1.0.0 |

---

# 1. Current Implementation

*Pre-registration assessment snapshot (implementation at readiness review). Operational hardening (actuator, Flyway, Dockerfile) was completed in Step 1 before registry registration. See [ETP_IAM_CAPABILITY_DEPLOYMENT.md](ETP_IAM_CAPABILITY_DEPLOYMENT.md) for current deployment behavior.*

## Authentication

| Item | Current state | Evidence |
|------|---------------|----------|
| Human login | `POST /api/v1/auth/login` — username/password brokered to Keycloak password grant via confidential server-side client | `AuthController`, `AuthService.login()`, `KeycloakTokenClient.requestPasswordToken()` |
| Service login | `POST /api/v1/auth/token` — `client_credentials` only | `AuthService.requestToken()`, `validateGrantType()` |
| Password storage | None in IAM DB; Keycloak validates credentials | `schema_updated.sql` (no users table); README |
| OIDC browser redirect / PKCE | Not implemented | README; no controller routes |
| Refresh / logout / `/me` | Not implemented | README; `OpenApiDocumentationTest` |

## Authorization

| Item | Current state | Evidence |
|------|---------------|----------|
| Authorization API | `POST /api/v1/authorization/check` → `{ authorized: boolean }` | `AuthorizationController`, `AuthorizationService.isAuthorized()` |
| Model | Principal → groups → roles → `role_application_permissions` → IAM `applications` + `permissions` + `resources` | `AuthorizationService`, repository JPQL |
| Tenant gate | Optional `tenant` on check request; `group_tenant_assignments` | `AuthorizationCheckRequest`, `GroupTenantAssignmentRepository` |
| Keycloak realm roles | Returned on login only; **not** used for `/authorization/check` | `AccessTokenUserMapper` vs `AuthorizationService` |
| Admin / write APIs | None; IAM service does not call `repository.save()` | `ETP_IAM_CURRENT_IMPLEMENTATION.md` §9 |

## JWT validation

| Item | Current state | Evidence |
|------|---------------|----------|
| On IAM protected routes | Spring Security OAuth2 Resource Server JWT | `SecurityConfig.oauth2ResourceServer().jwt()` |
| Issuer | `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `application.properties` |
| Token introspection | Not implemented | No introspection client in codebase |
| Platform service validation | Not implemented for capability APIs | `etp-platform-service` `SecurityConfig` `permitAll` on `/api/v1/capabilities/**` |

## Service-to-service authentication

| Item | Current state | Evidence |
|------|---------------|----------|
| Client credentials | `POST /auth/token` with `grant_type=client_credentials` | `AuthController`, `KeycloakTokenClient` |
| Service principal type | `PrincipalType.SERVICE` in schema | `PrincipalType.java`, `schema_updated.sql` |
| Auto-link client → IAM principal | Not implemented; principal row required for authorization | `AuthorizationService.resolvePrincipal()` |

## Tenant authorization

| Item | Current state | Evidence |
|------|---------------|----------|
| Tenant data | `companies`, `tenants`, `scopes`, `group_tenant_assignments` | `schema_updated.sql`, JPA entities |
| Enforcement | Only when `tenant` provided on `/authorization/check` | `AuthorizationService.isAuthorized()` lines 78–80 |
| JWT tenant claim | Not extracted automatically | No tenant claim logic in `AuthorizationService` |
| Full tenant isolation | Not enforced globally | Omitting `tenant` allows permission match without tenant scope |

## IAM data model

| Item | Current state | Evidence |
|------|---------------|----------|
| Database | Separate PostgreSQL `etp_iam` | `application.properties`, `docker-compose.yml` |
| Tables | 13 tables (companies through group_tenant_assignments) | `schema_updated.sql` |
| Migrations | Manual SQL apply; no Flyway in IAM module | README; no Flyway in `pom.xml` |
| Platform DB | Not used | No `etp_platform` JDBC URL in IAM |

## Keycloak integration

| Item | Current state | Evidence |
|------|---------------|----------|
| Token endpoint | `{base}/realms/{realm}/protocol/openid-connect/token` | `keycloak.token-url` in `application.properties` |
| Realm | Configurable; default `etp` | `ETP_IAM_KEYCLOAK_REALM` |
| Login client | Confidential client via env `keycloak.login-client-id` / secret | `AuthService`, `.env.example` |
| Admin API | Not used in code | No Admin Client dependency |
| Provisioning | Manual Keycloak admin; no realm export in repo | README |

## REST APIs

| Endpoint | Auth | Evidence |
|----------|------|----------|
| `POST /api/v1/auth/login` | Public | `SecurityConfig` |
| `POST /api/v1/auth/token` | Public | `SecurityConfig` |
| `POST /api/v1/authorization/check` | Bearer JWT | `SecurityConfig`, `AuthorizationController` |
| OpenAPI | `/v3/api-docs`, `/swagger-ui` | `application.properties`, `OpenApiConfig` |

## Configuration

| Area | Evidence |
|------|----------|
| Datasource | `ETP_IAM_DB_URL`, username, password | `application.properties`, `.env.example` |
| Keycloak | `ETP_IAM_KEYCLOAK_*`, `keycloak.*` | `application.properties`, `.env.example` |
| CORS | `etp.iam.cors.allowed-origins` (auth paths only) | `CorsConfig`, `application.properties` |
| Port | `ETP_IAM_SERVER_PORT` default 8081 | `application.properties` |

## Deployment

| Item | Evidence |
|------|----------|
| Artifact | Maven `etp-iam` `0.0.1-SNAPSHOT` Spring Boot jar | `pom.xml`, `spring-boot-maven-plugin` |
| Runtime | Java 21, Spring Boot 4.1.0 parent | `pom.xml` |
| Local stack | `docker-compose.yml` — Keycloak, Postgres, pgAdmin | `capabilities/etp-iam/docker-compose.yml` |
| Consumer | ETP Core UI Phase 1 login | `etp-core-ui/src/features/auth/iamAuthClient.ts` |

---

# 2. ETP Capability Requirements

Assessment against the Capability Registry aggregate model (`CreateCapabilityRequest`, lifecycle, contract validation). Status: **YES** / **PARTIAL** / **NO**.

| Requirement | Status | Evidence |
|-------------|--------|----------|
| **Capability identity** | **PARTIAL** | Documented as `etp.iam` in README/`OpenApiConfig`; **not** registered in `etp_platform.capability` in repo migrations/seeds |
| **Capability code** | **YES** | `etp.iam` — matches `CreateCapabilityRequest` pattern example (`CreateCapabilityRequest.java` line 19) |
| **Capability name** | **YES** | `ETP IAM Capability` — `pom.xml` `<name>`, README |
| **Description** | **YES** | README, `pom.xml` `<description>`, `OpenApiConfig` |
| **Category** | **PARTIAL** | Documented as security/IAM conceptually; **no fixed enum** in IAM code — registry expects `category` string (e.g. `SECURITY` in registry docs) |
| **Version** | **PARTIAL** | Maven `0.0.1-SNAPSHOT`; registry requires semver on `VersionRequest` (e.g. `1.0.0`) — align for registration |
| **Contract** | **PARTIAL** | OpenAPI at `/v3/api-docs` exists; **no** inline `contract_document` prepared for `POST /api/v1/capabilities` |
| **Implementation** | **PARTIAL** | Deployable `etp-iam` service exists; **no** `ImplementationRequest` payload prepared for registry |
| **Dependencies** | **PARTIAL** | Runtime deps (Keycloak, Postgres) documented; **no** registry `DependencyRequest` rows (requires other capability version UUIDs) |
| **Configuration** | **YES** | `.env.example`, `application.properties` — env-driven |
| **Runtime** | **YES** | Spring Boot service, independent process, port 8081 |
| **Security** | **PARTIAL** | Auth/authz APIs work; no health hardening doc; password grant Phase 1; no audit |
| **Observability** | **NO** | No actuator dependency; `show-sql=true` default; no metrics/tracing |
| **Health/readiness** | **NO** | `SecurityConfig` permits `/actuator/health` but `spring-boot-starter-actuator` absent from `pom.xml` |
| **NFRs** | **NO** | Not documented in code or contract artifact |
| **Deployment model** | **PARTIAL** | `docker-compose.yml` for dependencies; no container image/build for IAM service itself in repo |

---

# 3. Capability Contract

*Pre-registration planning shape. The registered contract is stored in `etp_platform.capability_contract` for version `e8f1af6c-0d53-4fc3-bc94-5e2aea3781d3`. Source payload: `etp-iam-capability-registration.json`.*

Registry shape (registered):
```
etp.iam                          ← capability.code (CreateCapabilityRequest)
  |
  +-- version: "1.0.0"           ← VersionRequest.version (semver)
  +-- contract                   ← ContractRequest
  |     contractType: COMPOSITE
  |     contractVersion: "1.0"
  |     contractFormat: JSON
  |     document: { functional, interfaces, security, deployment, nfr, operations, compatibility, configuration, dependencies }
  |     location: optional OpenAPI URL (e.g. http://host:8081/v3/api-docs) OR inline only
  +-- implementation             ← ImplementationRequest (optional but recommended)
  |     code: etp-iam
  |     name: ETP IAM Service
  |     technology: Spring Boot
  |     technologyVersion: "4.1.0"
  +-- dependencies: []           ← likely empty at registry level; Keycloak/Postgres documented in contract.dependencies (external)
```

**Registry fields ETP-IAM should supply** (mapped to `CreateCapabilityRequest`):

| Field | Proposed value / source |
|-------|-------------------------|
| `code` | `etp.iam` |
| `name` | `Identity & Access Management` (or `ETP IAM`) |
| `description` | From README capability purpose paragraph |
| `category` | `SECURITY` (consistent with registry examples) |
| `version.version` | `1.0.0` (first registered semver; align with capability contract version strategy) |
| `version.description` | Phase 1 scope: login, client_credentials, authorization check |
| `contract` | See §4 — inline JSON document + optional `location` pointing to OpenAPI |
| `implementation` | See §5 |
| `dependencies` | Empty array **or** future ETP capability version IDs if Postgres/Keycloak registered separately (not in repo today) |

Validation: inline `document` must pass `CapabilityContractDocumentValidator` against `capability-contract-base.schema.json` (`etp-platform-service/src/main/resources/schemas/`).

---

# 4. Contract Content

Functional contract content to embed in `contract.document` (registry JSONB sections). Reflects **implemented** behavior only.

## Authentication

| Capability | Detail |
|------------|--------|
| Human authentication | `POST /api/v1/auth/login` — JSON `{ username, password }`; returns JWT + user profile |
| Service authentication | `POST /api/v1/auth/token` — JSON OAuth2 client credentials body |
| Token acquisition | Keycloak-issued JWT `access_token`; IAM brokers token endpoint |
| Supported grant types (via IAM) | `password` (human, server-side only), `client_credentials` (service). Other grant types rejected on `/auth/token` |

**Not in contract (not implemented):** `authorization_code`, PKCE, `refresh_token` grant, logout, introspection.

## Authorization

| Capability | Detail |
|------------|--------|
| Authorization check | `POST /api/v1/authorization/check` — `{ application, permission, tenant? }` |
| Roles | IAM DB `roles` via group mappings (not Keycloak roles for decision) |
| Permissions | IAM DB `permissions` linked to `resources` and IAM `applications` |
| Resources | Types `API`, `FEATURE` per schema check constraint |
| Scopes | IAM DB `scopes`; used in `group_tenant_assignments` |
| Tenant context | Optional string matching `tenant_code` or `tenant_name`; enforced via group tenant assignments |

## Security

| Topic | Detail |
|-------|--------|
| JWT | Keycloak-issued; validated on IAM protected routes via issuer URI |
| Issuer | Configurable `ETP_IAM_KEYCLOAK_ISSUER_URI` (default `http://localhost:8180/realms/etp`) |
| Authentication requirements | `/auth/login` and `/auth/token` public; `/authorization/check` requires `Authorization: Bearer` |
| Authorization requirements | Valid JWT + ACTIVE `principals` row matching `iss` + `sub` |
| Passwords / secrets | Not stored in IAM DB; login client secret server-side only |

## API contract (implemented endpoints only)

### `POST /api/v1/auth/login`

- **Request:** `LoginRequest` — `username`, `password` (required)
- **Response:** `LoginResponse` — `user` (`id`, `username`, `displayName`, `email`, `roles`), `accessToken`, `expiresAt`
- **Errors:** 400, 401, 502
- **Class:** `AuthController.login`

### `POST /api/v1/auth/token`

- **Request:** `TokenRequest` — `client_id`, `client_secret`, `grant_type` (`client_credentials`)
- **Response:** `TokenResponse` — OAuth2 token fields
- **Errors:** 400, 401, 502
- **Class:** `AuthController.requestToken`

### `POST /api/v1/authorization/check`

- **Request:** `AuthorizationCheckRequest` — `application`, `permission` (required), `tenant` (optional)
- **Response:** `AuthorizationCheckResponse` — `authorized` boolean
- **Auth:** Bearer JWT
- **Errors:** 400, 401, 403
- **Class:** `AuthorizationController.check`

**External contract reference option:** `contract.location` = `{iamBaseUrl}/v3/api-docs` (SpringDoc path from `application.properties`).

---

# 5. Implementation

Current implementation metadata for `ImplementationRequest` (registry):

| Field | Value | Source |
|-------|-------|--------|
| `code` | `etp-iam` | README implementation code; `ImplementationRequest` example |
| `name` | `ETP IAM Service` | `pom.xml` artifactId context / README |
| `technology` | `Spring Boot` | `pom.xml` parent `spring-boot-starter-parent` 4.1.0 |
| `technologyVersion` | `4.1.0` | `pom.xml` parent version |
| `implementation_type` | Not in `ImplementationRequest` API | DB column exists on platform side for capability_implementation; optional service metadata |
| `configuration_reference` | Env file + properties | e.g. `capabilities/etp-iam/.env.example`, `application.properties` |

**Note:** Keycloak is an **external dependency** of the implementation, not the implementation technology. Identity provider should appear in `contract.dependencies` / deployment sections, not as `technology: Keycloak` for the IAM service artifact.

---

# 6. Dependencies

## ETP capabilities (registry `dependencies[]`)

| Dependency | ETP capability in registry? | Registry `DependencyRequest`? |
|------------|---------------------------|-------------------------------|
| PostgreSQL | **NO** — not registered as ETP capability in repo | **NO** — document as external in contract |
| Keycloak | **NO** | **NO** |
| Other ETP capabilities | None required for IAM runtime today | `dependencies: []` at registration unless policy requires registering infra capabilities first |

`DependencyRequest` requires `capabilityVersionId` (UUID of an **existing** capability version in `etp_platform`). No such IDs exist for Keycloak/Postgres in this repository.

## External infrastructure / platform components

| Component | Role | Config |
|-----------|------|--------|
| **PostgreSQL 17** | IAM authorization metadata (`etp_iam`) | `ETP_IAM_DB_URL`, docker-compose postgres |
| **Keycloak** | Authentication, JWT issuance | `ETP_IAM_KEYCLOAK_*`, docker-compose keycloak |

## Framework / runtime libraries (not ETP capabilities)

| Library | Role | Evidence |
|---------|------|----------|
| Spring Boot 4.1 | Application framework | `pom.xml` |
| Spring Security + OAuth2 Resource Server | JWT validation, route security | `pom.xml`, `SecurityConfig` |
| Spring Data JPA / Hibernate | IAM DB access | `pom.xml`, entities |
| SpringDoc OpenAPI 3.1.0 | API docs | `pom.xml` |
| PostgreSQL JDBC driver | DB connectivity | `pom.xml` |

---

# 7. Required Changes (pre-registration gap analysis)

*Historical gap analysis from readiness review. Items marked P1/P2 for registry onboarding are **COMPLETE** — see **Capability Readiness Summary** and **Onboarding Lifecycle** above.*

Changes identified for ETP capability readiness (registration + reusable consumption). Excludes admin UI, UAM, IAM model redesign, speculative architecture.

| Area | Pre-registration state | Required for ETP Capability | Status |
|------|------------------------|-----------------------------|--------|
| Capability registry row | Not in DB | `POST /api/v1/capabilities` for `etp.iam` | **COMPLETE** |
| Contract document | OpenAPI only | Inline `contract.document` + `location` | **COMPLETE** |
| Semver alignment | Maven `0.0.1-SNAPSHOT` | Registry version `1.0.0` + contractVersion `1.0` | **COMPLETE** (registry); Maven artifact remains `0.0.1-SNAPSHOT` |
| Implementation metadata | In code/README only | `ImplementationRequest` in registration payload | **COMPLETE** |
| Health endpoint | Permitted but no actuator | `/actuator/health` + readiness probes | **COMPLETE** |
| Readiness checks | None | DB + Keycloak reachability | **COMPLETE** |
| IAM schema provisioning | Manual `schema_updated.sql` | Flyway migrations on startup | **COMPLETE** |
| Secrets documentation | `.env.example` | Contract `configuration` section | **COMPLETE** |
| Keycloak realm/client | Manual | Documented in contract `deployment` | **COMPLETE** |
| Principal seed data | Manual SQL | Flyway V2 dev seed + deployment doc | **COMPLETE** (dev seed; manual `sub` alignment still operational) |
| Observability (metrics/tracing) | None | Enhancement | **Future** (non-blocking) |
| Audit events (IAM service) | None | Enhancement | **Future** (non-blocking) |
| Token refresh / OIDC PKCE | Not implemented | Not required for v1.0.0 | **Out of scope** |
| IAM admin APIs | None | Out of scope | **Out of scope** |
| UAM UI | None | Out of scope | **Out of scope** |
| Redesign 13-table model | Stable | Do not change | **No change** |
| Platform JWT enforcement | Not on capability APIs | Separate platform work | **Separate platform work** |
| `show-sql=true` default | Dev noise | Profile-gate for deploy | **COMPLETE** (`dev` profile) |
| CORS for `/authorization/check` | Auth paths only | Documented | **COMPLETE** (documented) |
| Container image for `etp-iam` | Jar only | Dockerfile | **COMPLETE** |
| NFR section in contract | Missing | Contract `nfr` section | **COMPLETE** |
---

# 8. Production Readiness

## Capability onboarding (registry + deployable service) — COMPLETE

| Topic | Status |
|-------|--------|
| **Health** | **COMPLETE** — actuator; `/actuator/health`, liveness/readiness |
| **Readiness** | **COMPLETE** — DB + Keycloak indicators |
| **Contract artifact** | **COMPLETE** — inline contract + OpenAPI `location` registered |
| **Registry registration** | **COMPLETE** — `etp.iam` v1.0.0 `ACTIVE` |
| **Configuration** | **COMPLETE** — env-driven; documented in contract |
| **Schema apply** | **COMPLETE** — Flyway on service startup |
| **Keycloak bootstrap** | **COMPLETE** — documented in deployment guide and contract |
| **Deployment packaging** | **COMPLETE** — Dockerfile + deployment guide |
| **Logging** | **COMPLETE** — `show-sql` profile-gated to `dev` |

## FUTURE ENHANCEMENT (not blocking ACTIVE registry status)
| Topic | Notes |
|-------|-------|
| Metrics / Prometheus | Not in `pom.xml` |
| Distributed tracing | Not implemented |
| Structured correlation IDs | Not in auth/authorization flow |
| Audit log for login and authorization decisions | No audit table in IAM |
| Token refresh, logout, `/me` | Phase 2+ |
| OIDC Authorization Code + PKCE | Phase 2+ |
| Principal auto-provisioning on login | Operational convenience |
| Keycloak Admin API automation | Realm export in repo |
| Rate limiting / resilience patterns | Not implemented |
| IAM write/admin APIs | UAM future |

---

# 9. Data Model

**Do not redesign** the 13-table IAM authorization model (`schema_updated.sql`).

| Question | Answer |
|----------|--------|
| Changes required for capability readiness? | **NO schema redesign** |
| Flyway / versioning | **PARTIAL** — repeatable schema apply recommended (tooling/docs), not necessarily new tables |
| Link to `etp_platform` | **NO FK required** — capability registry references IAM via contract/implementation metadata only |
| Seed data | **YES (operational)** — principals and RBAC rows required for authorization to work; document/script, not schema change |
| `group_tenant_assignments.scope_id` JPA vs SQL nullability | Verify at deploy; fix only if Hibernate validate fails — **not** a capability identity change |

Platform registry stores capability metadata in `etp_platform`; IAM data stays in `etp_iam` — **intentional separation** (`ETP_CURRENT_DATA_MODEL.md`).

---

# 10. Deployment

## Deployable artifact

| Item | Detail |
|------|--------|
| Build | `mvn clean package` → `etp-iam-0.0.1-SNAPSHOT.jar` | `pom.xml` |
| Main class | `com.etp.iam.IamServiceApplication` | `IamServiceApplication.java` |
| Java | 21 | `pom.xml` `java.version` |

## Runtime

| Item | Default |
|------|---------|
| HTTP port | 8081 (`ETP_IAM_SERVER_PORT`) |
| Process | Single Spring Boot JVM |

## Configuration (required env)

| Variable | Purpose |
|----------|---------|
| `ETP_IAM_DB_URL` / username / password | PostgreSQL `etp_iam` |
| `ETP_IAM_KEYCLOAK_ISSUER_URI` | JWT validation |
| `ETP_IAM_KEYCLOAK_BASE_URL`, `ETP_IAM_KEYCLOAK_REALM` | Token URL construction |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID` | Human login broker |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET` | [REDACTED] server-side |
| `ETP_IAM_CORS_ALLOWED_ORIGINS` | Browser login from Core UI |

## External dependencies (startup order)

1. PostgreSQL `etp_iam` with schema applied (`schema_updated.sql`)
2. Keycloak realm `etp` with login client configured
3. IAM service JVM

## Health endpoint

| Item | Current | Target for capability |
|------|---------|------------------------|
| Path | `/actuator/health` permitted in security | Implement with actuator dependency |
| Liveness | Not available | JVM up |
| Readiness | Not available | DB (+ optional Keycloak) |

## Ports (local docker-compose)

| Service | Host port |
|---------|-----------|
| IAM | 8081 |
| Keycloak | 8180 |
| Postgres | 55432 |
| pgAdmin | 5050 |

---

# 11. Final Gap Matrix (historical)

*Snapshot at readiness review. Registry and hardening gaps below are **COMPLETE** for `etp.iam` v1.0.0 — see **Final Capability Status**.*

| Requirement | Existing | Reusable | Build/Change | No Change |
|-------------|----------|----------|--------------|-----------|
| Human authentication API | ✓ | ✓ | | |
| Service authentication API | ✓ | ✓ | | |
| Authorization check API | ✓ | ✓ | | |
| JWT validation (IAM) | ✓ | ✓ | | |
| IAM RBAC data model (13 tables) | ✓ | ✓ | | |
| Keycloak integration | ✓ | ✓ | | |
| OpenAPI description | ✓ | ✓ | | |
| Core UI consumer | ✓ | ✓ | | |
| Capability code `etp.iam` (documented) | ✓ | ✓ | | |
| Registry registration payload | | | ~~Build~~ **COMPLETE** | |
| Inline capability contract document | | | ~~Build~~ **COMPLETE** | |
| Implementation registry metadata | | | ~~Build~~ **COMPLETE** | |
| Health/readiness endpoint | | | ~~Build~~ **COMPLETE** | |
| Repeatable DB schema install | partial manual | | ~~Build~~ **COMPLETE** (Flyway) | |
| Keycloak bootstrap documentation in contract | partial README | | ~~Build~~ **COMPLETE** | |
| Principal/RBAC seed for consumers | manual | | ~~Build~~ **COMPLETE** (dev seed) | |
| Container/deploy artifact | jar only | | ~~Build~~ **COMPLETE** (Dockerfile) | |
| NFR / timeout documentation | | | ~~Build~~ **COMPLETE** | |
| IAM admin UI | | | | **Out of scope** |
| UAM functionality | | | | **Out of scope** |
| 13-table authorization redesign | | | | **No change** |
| OIDC PKCE / refresh / logout | | | | **Future** |
| Metrics/tracing/audit (IAM) | | | | **Future** |
| Platform capability API JWT enforcement | | | | **Separate platform work** |

---

# 12. Final Recommendation

## Bottom line

**ETP-IAM (`etp.iam` v1.0.0) is an active, registered ETP capability** in the Platform Capability Registry. Capability and version lifecycle are **ACTIVE**. Onboarding hardening, contract preparation, registration, and activation are **COMPLETE**.

## A. What we have (registered ACTIVE capability)

- **ETP IAM capability service** — human and service authentication (Keycloak broker), JWT validation, PostgreSQL RBAC authorization, OpenAPI, health/readiness, Flyway schema, Dockerfile
- **Capability Registry record** — `etp.iam` / `1.0.0` with inline contract, implementation metadata, empty registry dependencies
- **ETP Core UI** Phase 1 login consumer

## B. Completed onboarding work

1. Capability readiness assessment and Step 1 hardening
2. Inline `contract.document` validated against `capability-contract-base.schema.json`
3. Registration via `POST /api/v1/capabilities` (`etp-iam-capability-registration.json`)
4. Lifecycle: `DRAFT` → `VALIDATED` → `ACTIVE` (capability auto-activated on first version activation)

## C. What we should NOT change (unchanged)

- IAM authorization model (13 tables)
- v1.0.0 API surface (`/auth/login`, `/auth/token`, `/authorization/check`)
- Keycloak as IdP; IAM broker for Phase 1 login
- Separate `etp_iam` database
- No IAM administration UI / UAM in v1.0.0

## D. Reference for next capabilities

Use the **Onboarding Lifecycle** and **Reference Capability** sections above. Next ETP capabilities follow the same registry process; implementation details remain capability-specific.

## E. Non-blocking enhancements (future)

| Topic | Notes |
|-------|-------|
| Principal `external_id` manual alignment after Keycloak user creation | Operational |
| Production SpringDoc/Swagger UI hardening | Configuration |
| Metrics, tracing, IAM audit events | Operations |
| OIDC PKCE, refresh, logout, admin APIs | Product Phase 2+ |

*The recommended pre-registration sequence in earlier revisions of this document has been superseded by the completed **Onboarding Lifecycle** table.*
---

# Source References

| Topic | Path |
|-------|------|
| IAM implementation analysis | `capabilities/etp-iam/ETP_IAM_CURRENT_IMPLEMENTATION.md` |
| Platform data model | `ETP_CURRENT_DATA_MODEL.md` |
| Registry create payload | `etp-platform-service/.../dto/CreateCapabilityRequest.java` |
| Contract JSON Schema | `etp-platform-service/src/main/resources/schemas/capability-contract-base.schema.json` |
| IAM auth | `capabilities/etp-iam/src/main/java/com/etp/iam/authentication/` |
| IAM authorization | `capabilities/etp-iam/src/main/java/com/etp/iam/authorization/` |
| Security | `capabilities/etp-iam/src/main/java/com/etp/iam/config/SecurityConfig.java` |
| IAM schema | `capabilities/etp-iam/schema_updated.sql` |
| Config | `capabilities/etp-iam/src/main/resources/application.properties`, `.env.example` |
| Registry API docs | `etp-platform-service/docs/CAPABILITY_REGISTRY.md` |
| Registration payload | `capabilities/etp-iam/etp-iam-capability-registration.json` |
| Deployment guide | `capabilities/etp-iam/ETP_IAM_CAPABILITY_DEPLOYMENT.md` |

---

*Includes pre-registration assessment (§1–§6, historical tables) and post-registration status (Final Capability Status, Onboarding Lifecycle). `etp.iam` v1.0.0 is registered and ACTIVE in the ETP Capability Registry.*
