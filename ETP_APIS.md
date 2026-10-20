# ETP APIs

HTTP APIs implemented in this repository. This file lives only at the repository root.

Architecture: [ETP-ARCHITECTURE.md](ETP-ARCHITECTURE.md). Capability lifecycle rules: [ETP_CAPABILITY_DEFINITION.md](ETP_CAPABILITY_DEFINITION.md). Request-body detail for migration: `capabilities/etp-migration/docs/API_GUIDE.md`.

---

## 1. Conventions

Public business APIs use `/api/v1`. Actuator stays under `/actuator` and is not part of the business API.

| Method | Use |
|--------|-----|
| GET | Read. Idempotent |
| POST | Create a resource or run a command |
| PUT | Full replacement of metadata where a controller exposes it |
| PATCH | Partial update |
| DELETE | Not used for the Capability Registry. Removal is a lifecycle transition |

Platform success body (`com.etp.platform.common.api.ApiResponse`):

```json
{
  "data": {},
  "message": null,
  "timestamp": "2026-08-16T08:00:00Z"
}
```

Platform errors (`ErrorResponse`): `code`, `message`, `timestamp`, optional field `details`. Validation is HTTP 400 / `VALIDATION_ERROR`. Invalid capability lifecycle is HTTP 409 / `INVALID_LIFECYCLE_TRANSITION`. Duplicate capability code or version is HTTP 409.

Migration errors use `ApiErrorResponse`: `timestamp`, `status`, `error`, `message`, `path`, optional `code`.

Exports errors use RFC 7807 `ProblemDetail`.

---

## 2. Platform service

Process: `etp-platform-service`. Default port **8080** (`ETP_SERVER_PORT`).

OpenAPI: `GET /api/v1/openapi`. Swagger UI: `GET /api/v1/swagger-ui`.

Security today: the filter chain is stateless and CSRF is off. These paths are `permitAll`: `/api/v1/health`, `/api/v1/platform/info`, `/api/v1/capabilities/**`, `/actuator/health`, and the OpenAPI paths. Every other path is `authenticated`. `spring-boot-starter-security-oauth2-resource-server` is on the classpath, but `SecurityConfig` does not enable a JWT resource server, so a Bearer token is not checked. CORS allows `GET`, `POST`, `PUT`, `PATCH`, and `OPTIONS` from `http://localhost:5173` by default. `DELETE` is not a CORS method.

Registry error codes from `CapabilityService` and `GlobalExceptionHandler`:

| HTTP | Code | When |
|------|------|------|
| 400 | `VALIDATION_ERROR` | Jakarta validation failed. `details` lists fields |
| 400 | `INVALID_REQUEST_BODY` | Body could not be read |
| 400 | `INVALID_LIFECYCLE_STATUS` | Status string is not a known enum |
| 400 | `INVALID_CAPABILITY_CONTRACT` | Contract has neither an inline document nor a location |
| 404 | `CAPABILITY_VERSION_NOT_FOUND` | `versionStatus.versionId` does not exist |
| 409 | `INVALID_LIFECYCLE_TRANSITION` | Transition is not allowed, or the semver is not higher than the current ACTIVE version |
| 409 | `ACTIVE_VERSION_IMMUTABLE` | A change was requested on content that is already ACTIVE, DEPRECATED, or RETIRED |
| 409 | `CAPABILITY_VERSION_MISMATCH` | The version id belongs to a different capability |
| 409 | `DATA_INTEGRITY_VIOLATION` | A database unique constraint failed |
| 500 | `INTERNAL_ERROR` | Unhandled exception |

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/health` | `{ status, service }` inside `ApiResponse` |
| GET | `/api/v1/platform/info` | Platform name, version `0.1.0-SNAPSHOT`, description |
| GET | `/actuator/health` | Actuator health, including database connectivity |
| POST | `/api/v1/capabilities` | Create capability + first version in `DRAFT`. HTTP 201 |
| GET | `/api/v1/capabilities` | List summaries. Optional query: `category`, `status` |
| GET | `/api/v1/capabilities/{id}` | Full aggregate: versions, contracts, implementations, dependencies |
| PUT | `/api/v1/capabilities/{id}` | Replace capability metadata (`name`, `description`, `category`, `status`). `code` is immutable |
| PATCH | `/api/v1/capabilities/{id}` | Partial metadata, capability status, new version, or version status |

There is no DELETE. There is no `/capabilities/{id}/versions` collection. There is no Application Registry API and no audit read API.

`POST` body (`CreateCapabilityRequest`):

| Field | Required | Rule |
|-------|----------|------|
| `code` | yes | Lowercase dotted id, for example `etp.iam`. Pattern: `^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$` |
| `name` | yes | Max 200 |
| `description` | no | Max 5000 |
| `category` | yes | Max 50, for example `SECURITY` |
| `version` | yes | `version` (semver), optional `description`. Created as `DRAFT` |
| `contract` | yes | See contract fields below |
| `implementation` | no | `code`, `name`, `technology`, optional `technologyVersion` |
| `dependencies` | no | Each item: `capabilityVersionId` (UUID of another **version**), `dependencyType`, `mandatory` |

Contract (`ContractRequest`): `contractType`, `contractVersion`, `contractFormat`, and at least one of `document` (inline JSON, stored as JSONB and checked against the V1 base schema) or `location` (external artifact). Both may be sent.

`PATCH` body (`PatchCapabilityRequest`). Send only the parts that should change:

| Field | Effect |
|-------|--------|
| `name`, `description` | Metadata update. Audited as `METADATA_UPDATE` |
| `status` | Capability lifecycle transition |
| `newVersion` | Adds a version: semver, description, contract, optional implementation and dependencies |
| `versionStatus` | `{ versionId, status }` transitions that version |

`PUT` replaces `name`, `description`, `category`, and `status` together. It does not create a version and does not change `code`.

List and detail `version` is the most recently created version (`createdAt` descending), which is not always the `ACTIVE` version. On a version object, `description` is the row’s `release_notes`. Contract and implementation rows are stored with `status = ACTIVE` at insert time; that status is not the capability-version lifecycle.

---

## 3. IAM

Process: `capabilities/etp-iam`. Default port **8081** (`ETP_IAM_SERVER_PORT`).

OpenAPI: `GET /v3/api-docs`. Swagger UI: `GET /swagger-ui/index.html`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/v1/auth/login` | Public | Human login. Body: `username`, `password`. IAM calls Keycloak with the confidential client. Response: `user`, `accessToken`, `expiresAt`. Password is not stored and not returned |
| POST | `/api/v1/auth/token` | Public | Service login. Body: `client_id`, `client_secret`, `grant_type` = `client_credentials` only. Response is the Keycloak token payload (`access_token`, `expires_in`, `token_type`, `scope`) |
| POST | `/api/v1/authorization/check` | Bearer JWT | Permission check for an application, with optional tenant |

Login failures: 400 validation, 401 bad credentials, 502 token service unavailable.

Not implemented: `/auth/logout`, `/auth/me`, `/auth/refresh`, browser OIDC redirect, Authorization Code + PKCE.

Core UI calls login only. It does not call `/auth/token`.

---

## 4. Migration

Process: `capabilities/etp-migration`. Default port **8082** (`ETP_MIGRATION_SERVER_PORT`).

OpenAPI (`/v3/api-docs` and Swagger UI) is **off** unless profile `dev` is active or `ETP_MIGRATION_OPENAPI_ENABLED=true`.

Authentication: JWT on `/api/v1/**` unless profile `dev` is active (`permitAll`). No role or scope is enforced inside this module. Issuer default: `http://localhost:8180/realms/etp`.

Pagination on list endpoints: `page` default 0, `size` default 20, maximum 100.

Commands are POST. Queries are GET. Nothing here auto-promotes.

### Configuration

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/migration-configurations` | One transaction: mapping, rules, DRAFT definition, one source. Does not activate or run |
| POST | `/api/v1/migrations` | Create a definition |
| GET | `/api/v1/migrations` | List definitions |
| GET | `/api/v1/migrations/{id}` | Get one definition |
| POST | `/api/v1/migrations/{migrationId}/activate` | Move a definition to ACTIVE |
| POST | `/api/v1/migrations/{migrationId}/sources` | Add a source |
| GET | `/api/v1/migrations/{migrationId}/sources` | List sources |
| GET | `/api/v1/migrations/{migrationId}/sources/{sourceId}` | Get one source |
| POST | `/api/v1/mappings` | Create a DRAFT mapping |
| GET | `/api/v1/mappings` | List mappings |
| GET | `/api/v1/mappings/{id}` | Get a mapping without its rules |
| POST | `/api/v1/mappings/{mappingId}/rules` | Add one rule |
| GET | `/api/v1/mappings/{mappingId}/rules` | List rules in `sequenceNo` order |

Composite body accepts `mapping` or `entityMappings`, and `rules` or `fieldMappings`. SFTP source configuration uses `credentialRef`. Do not send `password`, `privateKey`, or `passphrase`.

`modelType` omitted means a single entity and `definition.targetEntity` is required. `modelType` `ENTITY_RELATIONSHIP` uses `rootEntity`, relationships, and optional canonical `target.mode` `UAM`.

### Runs

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/migrations/{migrationId}/execute` | Create a run for an ACTIVE definition, prepare the source, and process eligible batches in the request. Does not reconcile or promote. HTTP 201 |
| POST | `/api/v1/migrations/{migrationId}/runs` | Create a QUEUED run only. Does not read the file |
| GET | `/api/v1/migration-runs` | All runs. Optional `migrationDefinitionId`, `page`, `size` |
| GET | `/api/v1/migrations/{migrationId}/runs` | Runs for one definition |
| GET | `/api/v1/migration-runs/{runId}` | One run |
| POST | `/api/v1/migration-runs/{runId}/cancel` | Cancel |
| POST | `/api/v1/migration-runs/{runId}/retry` | Retry |
| POST | `/api/v1/migration-runs/{runId}/prepare` | Inspect the source and build batches |
| POST | `/api/v1/migration-runs/{runId}/process-next-batch` | Process one claimed batch |
| GET | `/api/v1/migration-runs/{runId}/batches` | Batches for the run |
| GET | `/api/v1/migration-runs/{runId}/errors` | Paginated `migration_error` rows, including `errorDetails` |
| POST | `/api/v1/migration-runs/{runId}/reconcile` | Reconcile staging (or business target when `verificationScope=BUSINESS_TARGET`) |
| GET | `/api/v1/migration-runs/{runId}/reconciliation` | Read the reconciliation result |

Execute and run creation return 409 when the definition is not ACTIVE.

### Promotion

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/migration-runs/{runId}/promotion/prepare` | Find eligible staged batches. Does not write business tables. HTTP 200 |
| POST | `/api/v1/migration-runs/{runId}/promote` | Write a READY promotion into the configured PostgreSQL target |
| GET | `/api/v1/migration-runs/{runId}/promotion` | Read promotion state. Does not change rows |

There is no `GET /api/v1/promotions/{promotionId}` and no PUT, PATCH, or DELETE for definitions.

Order that matches the engine: configure → activate → execute (or create/prepare/process) → reconcile → prepare promotion → promote. A completed run means staging finished, not that business tables were loaded.

---

## 5. Exports

Process: `capabilities/etp-exports/etp-exports`. Port **8095**.

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/exports` | Call an external HTTP API and return the `data` field as a file download |

Body:

| Field | Required | Notes |
|-------|----------|-------|
| `path` | yes | Absolute `http(s)` URL, unless `etp.export.default-base-url` is set |
| `httpMethod` | yes | GET, POST, PUT, PATCH, or DELETE. `body` is forwarded only for POST, PUT, and PATCH |
| `fileType` | yes | `CSV` or `XLSX` |
| `body` | no | JSON passed through to the upstream API |
| `headers` | no | Forwarded, including `Authorization` |
| `fileName`, `sheetName` | no | Defaults: `export_yyyyMMdd_HHmmss`, `Data` |

Only the upstream `data` field is tabulated. A response with no `data` field fails with 502. Hosts outside `etp.export.allowed-hosts` fail with 403 when that list is non-empty. An empty allow-list allows any host (local default).

---

## 6. Core UI

`etp-core-ui` does not expose an API. React 19, Vite 8, React Router 7. It calls only:

| Caller | API |
|--------|-----|
| Login | IAM `POST /api/v1/auth/login` via `VITE_IAM_BASE_URL`. Body is `username` and `password`. Remember Me is not sent to IAM |
| Capability list | Platform `GET /api/v1/capabilities` with no query string |
| Capability details | Platform `GET /api/v1/capabilities/{id}` |

The shared API client attaches `Authorization: Bearer <access token>` on platform calls. The platform service ignores that header today.

The UI does not call POST, PUT, or PATCH on the registry.

### Session

| Behavior | What the code does |
|----------|-------------------|
| Remember Me checked | Session JSON is stored in `localStorage` under `etp.auth.session` |
| Remember Me cleared | Same key in `sessionStorage` |
| Expiry | The IAM `expiresAt` value. There is no refresh call |
| Logout | Clears both storages. Does not call IAM or Keycloak |
| Mock login | `VITE_MOCK_AUTH_ENABLED=true` checks `VITE_MOCK_AUTH_USERNAME` and `VITE_MOCK_AUTH_PASSWORD` in the browser and never calls IAM |

### Capability screens

The list loads every summary, then filters and pages in the browser. Search, category, and status are not sent to the API. Page size choices are 10, 25, 50, and 100. The default is 25.

The details page selects a version in this order: the summary `version` string (newest by `createdAt`), otherwise the `ACTIVE` version, otherwise the first version returned. Tabs are Overview, Contract, and Dependencies.

| Shown | Not shown |
|-------|-----------|
| Name, code, category, capability status, description | Implementation (`code`, `technology`) even though the API returns it |
| Version description, ids, created and updated times | Capability code of a dependency. The table shows a shortened version UUID, type, and mandatory flag |
| Contract type, format, version, location | The contract document’s `dependencies` section in the structured view. Raw JSON still shows the whole document |
| Structured sections: functional, interfaces, security, deployment, NFR, operations, compatibility, configuration | |

Create, Edit, Clone, lifecycle actions, Delete, Import, and Export are visible and disabled. Refresh and View are the actions that run.

### Shell routes with no API

`/dashboard`, `/customers`, `/applications`, `/platform/configuration`, `/platform/version`, and `/audit` render a title and a description. They do not call the platform service. The sidebar has no entitlements or operating-model entries. `customer`, `application`, `audit_event`, `platform_version`, and the V14 tables are not read by this UI.
