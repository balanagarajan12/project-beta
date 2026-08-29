# ETP Capability Registry V1

Reference for the implemented Capability Registry in `etp-platform-service`. This document describes **what is implemented today**, not future ETP capabilities.

## Scope

### Implemented in Capability Registry V1

| Area | Status |
|------|--------|
| Capability aggregate persistence (JPA + Flyway) | Implemented |
| REST APIs under `/api/v1/capabilities` (see § REST API) | Implemented |
| Inline JSONB contract documents | Implemented |
| External contract references (`location`) | Implemented |
| JSON Schema validation (base schema) | Implemented |
| Capability and version lifecycle | Implemented |
| Single ACTIVE version enforcement (application + PostgreSQL) | Implemented |
| Lifecycle audit events (`AuditEvent` table) | Implemented |
| OpenAPI / Swagger documentation | Implemented |
| ETP Core UI — capability list and read-only details (`etp-core-ui`) | Partially implemented (read-only; see § ETP Core UI) |

### Not implemented in platform-service (future ETP work)

Do not assume these exist in **etp-platform-service** when using the registry:

- OAuth2 JWT enforcement on platform capability registry APIs (deferred)
- Application Registry APIs
- Application Initializer / Composer / Builder
- Business application runtime or generated application deployment
- Audit query APIs (events are recorded; no read API yet)
- Capability-specific JSON Schemas beyond the V1 base schema
- DELETE endpoints (deprecation, retirement, and cancellation are lifecycle operations via PATCH)

**Note:** The **ETP IAM capability** (`capabilities/etp-iam/`) is implemented separately and integrates with Keycloak for authentication. Customer UI and business application authentication are **not** implemented. See [capabilities/etp-iam/README.md](../../capabilities/etp-iam/README.md).

## Concepts

### Capability

An ETP-owned reusable technology capability (for example `etp.iam`). Identified by a unique lowercase dotted `code`, category, name, and **capability lifecycle status**.

**Capability ≠ business application.** A capability is a reusable platform building block. A business application is composed from selected capabilities and contains customer/business-specific behavior. Capabilities must not automatically become business applications.

**ETP IAM** (`etp.iam`) is a capability — not a business application. IAM can be selected during composition and consumed by multiple business applications and ETP Customer UI where appropriate.

Capabilities are created in **DRAFT** when the first version is registered via POST.

### Capability Version

A semantic version of a capability (for example `1.0.0`). Each version has its own **version lifecycle status**, contract, optional implementation, and optional dependencies.

New versions after the initial POST are created through PATCH (`newVersion`). Version lifecycle changes use PATCH (`versionStatus`).

### Contract

Defines what a capability version provides. Stored per version in `capability_contract`.

A contract must include either:

- **Inline JSONB contract document** — structured JSON persisted in `contract_document` (validated against the V1 base JSON Schema), or
- **External contract reference** — `location` (URI/path to OpenAPI, AsyncAPI, etc.) with `contractFormat` and `contractType`

Both may be present in storage, but at least one inline document or external location is required at creation.

### Implementation

Optional technology implementation metadata for a version (implementation code, name, technology, technology version). Stored in `capability_implementation`.

### Dependency

Optional version-aware dependency on another **capability version** (not merely a capability code). Stored in `capability_dependency` with dependency type and mandatory flag.

### Audit

Significant registry operations record `AuditEvent` rows:

| Action | Entity | When |
|--------|--------|------|
| `CREATE` | `CAPABILITY` | Capability created |
| `CREATE_VERSION` | `CAPABILITY_VERSION` | New version added |
| `METADATA_UPDATE` | `CAPABILITY` | Name/description changed |
| `STATUS_CHANGE` | `CAPABILITY` | Capability lifecycle transition (including automatic DRAFT→ACTIVE) |
| `STATUS_CHANGE` | `CAPABILITY_VERSION` | Version lifecycle transition (including automatic DEPRECATED on activation) |

There is no audit read API in V1.

## Lifecycle

Capability lifecycle and version lifecycle are **related but independent**. Version transitions can automatically affect the parent capability (first activation). Capability-level PATCH `status` does not replace version lifecycle rules.

### Capability lifecycle

```
DRAFT → ACTIVE → DEPRECATED → RETIRED
```

| Status | Meaning |
|--------|---------|
| `DRAFT` | Registered; no version has been activated yet |
| `ACTIVE` | At least one version has been (or is) active in the catalog |
| `DEPRECATED` | Capability is being phased out |
| `RETIRED` | Terminal |

**Automatic transition:** When the **first** capability version becomes `ACTIVE`, the parent capability transitions from `DRAFT` to `ACTIVE` in the same transaction (with `STATUS_CHANGE` audit).

Capability lifecycle does **not** include `CANCELLED` in V1.

### Capability version lifecycle

```
DRAFT
  ├── VALIDATED
  │     ├── ACTIVE → DEPRECATED → RETIRED
  │     └── CANCELLED
  └── CANCELLED
```

| Status | Terminal? | Notes |
|--------|-----------|-------|
| `DRAFT` | No | Initial state for every new version |
| `VALIDATED` | No | Ready for activation review |
| `ACTIVE` | No | Exactly one per capability at any time |
| `DEPRECATED` | No for lifecycle; terminal for **activation** | Superseded or phased out |
| `RETIRED` | Yes | Historical; queryable |
| `CANCELLED` | Yes | Only for versions that **never** were `ACTIVE` |

### Lifecycle rules (implemented)

1. **POST** creates a capability and its first version in **DRAFT**.
2. Version changes after creation use **PATCH** (`newVersion`, `versionStatus`).
3. **First `ACTIVE` version** automatically transitions the parent capability from `DRAFT` to `ACTIVE`.
4. **Only one `ACTIVE` version** per capability (enforced in service logic and by PostgreSQL partial unique index).
5. **Activating a newer validated version** automatically **DEPRECATES** the current `ACTIVE` version in the same transaction (with `STATUS_CHANGE` audit on both versions).
6. A **validated version cannot be activated** if its semantic version is **≤** the current `ACTIVE` version (semver compare on major.minor.patch).
7. **`CANCELLED`** is only for versions that have **never** been `ACTIVE`.
8. `DRAFT` and `VALIDATED` may transition to `CANCELLED`.
9. `ACTIVE`, `DEPRECATED`, and `RETIRED` **cannot** transition to `CANCELLED`.
10. `CANCELLED` cannot transition to any other state.
11. `DEPRECATED` and `RETIRED` cannot become `ACTIVE` again (no rollback through the lifecycle API).
12. **`ACTIVE` version content is immutable** — contract/implementation changes require a new version; invalid transitions return `ACTIVE_VERSION_IMMUTABLE` where applicable.
13. **Historical versions** (`DEPRECATED`, `RETIRED`, `CANCELLED`) remain queryable via GET.
14. **Invalid lifecycle transitions** return HTTP **409** with code **`INVALID_LIFECYCLE_TRANSITION`**.

### Database enforcement

Flyway migration `V13__capability_version_single_active_index.sql`:

```sql
CREATE UNIQUE INDEX uk_capability_version_one_active_per_capability
    ON capability_version (capability_id)
    WHERE status = 'ACTIVE';
```

If duplicate `ACTIVE` rows existed before migration, V13 deprecates all but the highest semantic version per capability before creating the index.

## REST API (V1)

Base path: `/api/v1/capabilities`

There is **intentionally no DELETE** endpoint. Retirement, deprecation, and cancellation are lifecycle operations through PATCH.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/capabilities` | Create capability with first version (DRAFT) |
| `GET` | `/api/v1/capabilities` | List summaries; optional `category`, `status` query filters |
| `GET` | `/api/v1/capabilities/{id}` | Full aggregate: versions, contracts, implementations, dependencies |
| `PATCH` | `/api/v1/capabilities/{id}` | Metadata, capability lifecycle, new version, version lifecycle |

**Exposed V1 contract:** GET, POST, GET by id, and PATCH only. There is **no DELETE** endpoint. **`PUT /api/v1/capabilities/{id}` is not part of the current exposed API** — capability metadata and lifecycle changes use PATCH.

Versions are **not** exposed as separate top-level resources in V1; they are nested in the capability aggregate and managed via PATCH.

### Response models

All responses use the shared `ApiResponse<T>` wrapper (`data`, `message`, `timestamp`).

#### `CapabilitySummaryResponse` (list)

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Capability ID |
| `code` | string | Unique capability code |
| `name` | string | Capability name |
| `description` | string \| null | Capability description |
| `category` | string \| null | Capability category |
| `status` | string | Capability lifecycle status |
| `version` | string \| null | Most recently **created** version semver (by `createdAt`); not necessarily `ACTIVE` |
| `createdAt` | instant | Record creation timestamp |
| `updatedAt` | instant | Record last update timestamp |

#### `CapabilityDetailResponse` (create, retrieve, patch)

Capability-level fields match the summary model (`id`, `code`, `name`, `description`, `category`, `status`, `version`, `createdAt`, `updatedAt`) plus:

| Field | Type | Description |
|-------|------|-------------|
| `versions` | array | All capability versions (newest `createdAt` first) |

Each `versions[]` entry (`CapabilityVersionDetailResponse`):

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Capability version ID |
| `version` | string | Semantic version (for example `1.0.0`) |
| `status` | string | Version lifecycle status |
| `description` | string \| null | Version description (stored as release notes) |
| `contract` | object \| null | Version contract (see below) |
| `implementation` | object \| null | Optional implementation metadata |
| `dependencies` | array | Version dependencies (may be empty) |

**Contract** (`ContractResponse`):

| Field | Type | Description |
|-------|------|-------------|
| `contractType` | string | Contract type (for example `COMPOSITE`) |
| `contractVersion` | string | Contract schema/version identifier |
| `contractFormat` | string | Contract format (for example `JSON`) |
| `document` | object \| null | Inline contract document when present |
| `location` | string \| null | External contract reference when present |

**Inline contract document** sections validated by the V1 base JSON Schema (`capability-contract-base.schema.json`):

- `functional`
- `interfaces`
- `security`
- `deployment`
- `nfr`
- `operations`
- `compatibility`
- `configuration`

Additional properties are permitted at the document root. Sections may be empty objects.

**Implementation** (`ImplementationResponse`), when present:

| Field | Type |
|-------|------|
| `code` | string |
| `name` | string |
| `technology` | string |
| `technologyVersion` | string \| null |

**Dependency** (`DependencyResponse`):

| Field | Type | Description |
|-------|------|-------------|
| `capabilityVersionId` | UUID | Depended-on capability **version** ID |
| `dependencyType` | string | Dependency type |
| `mandatory` | boolean | Whether the dependency is mandatory |

Dependencies reference capability **versions**, not capability codes alone. The API does not expand depended-on capability name, code, or version status in the dependency payload.

### POST — create capability

Creates the capability (DRAFT) and first version (DRAFT) with contract. Optional implementation and dependencies.

Returns **201** with `CapabilityDetailResponse` wrapped in `ApiResponse`.

Common errors: **400** (validation, invalid contract), **409** (`DUPLICATE_CAPABILITY_CODE`).

### GET — list / retrieve

- **List** returns lightweight summaries without full inline contract documents.
- **Retrieve** returns all versions (ordered by creation time, newest first), each with contract (including inline `document` when present), implementation, and dependencies.

### PATCH — modify capability

Partial update. Supported fields (any combination):

| Field | Purpose |
|-------|---------|
| `name` | Update capability name |
| `description` | Update capability description |
| `status` | Capability lifecycle transition (`DRAFT`→`ACTIVE`→`DEPRECATED`→`RETIRED`) |
| `newVersion` | Create additional version (starts in `DRAFT`) |
| `versionStatus` | Transition a version lifecycle (`versionId`, `status`) |

**Version lifecycle example:**

```json
{
  "versionStatus": {
    "versionId": "uuid-of-capability-version",
    "status": "VALIDATED"
  }
}
```

**New version example:**

```json
{
  "newVersion": {
    "version": "1.1.0",
    "contract": {
      "contractType": "COMPOSITE",
      "contractVersion": "1.0",
      "contractFormat": "JSON",
      "document": { ... }
    }
  }
}
```

### Error codes (lifecycle-related)

| HTTP | Code | When |
|------|------|------|
| 409 | `INVALID_LIFECYCLE_TRANSITION` | Disallowed capability or version status change |
| 409 | `ACTIVE_VERSION_IMMUTABLE` | Attempt to change immutable active version content paths |
| 409 | `DUPLICATE_CAPABILITY_VERSION` | `newVersion` semver already exists |
| 404 | `CAPABILITY_NOT_FOUND` | Unknown capability id |
| 404 | `CAPABILITY_VERSION_NOT_FOUND` | Unknown version id |

## ETP Core UI (`etp-core-ui`)

Read-only Capability Registry screens are implemented in ETP Core UI. They consume **GET** list and **GET** detail APIs only. **POST** and **PATCH** exist on the platform API but have **no** corresponding Core UI workflows yet.

### Routes

| Route | Page |
|-------|------|
| `/capabilities` | Capability list |
| `/capabilities/:capabilityId` | Capability details |

Both routes are behind Core UI authentication (`RequireAuth`) inside the ETP Core Shell layout.

### List page (`/capabilities`)

**One row = one capability** (not one row per version).

| Implemented | Notes |
|-------------|-------|
| Capability listing via `GET /api/v1/capabilities` | Full list fetched; no query params sent from UI |
| Client-side search | Name, code, description, id, category, status, version |
| Client-side category filter | Distinct values from loaded data |
| Client-side status filter | Distinct values from loaded data |
| Client-side pagination | Default 25 rows; page sizes 10, 25, 50, 100 |
| Row selection | Checkbox per row; bulk bar present with actions disabled |
| Refresh | Utilities menu reloads list |
| Capability count | Filtered count in list header |
| Loading / empty / error states | Including retry on load failure |
| Navigation to details | Name link and row action **View** |

The **Version** column shows the list API `version` field (most recently created version semver). It does not list every historical version as separate rows.

**Deferred on list UI:** Create Capability (button disabled), Edit, Clone, lifecycle row actions, bulk operations, server-side pagination, server-side filtering (API supports `category`/`status` query params but UI does not use them), export, saved views, manage columns.

### Details page (`/capabilities/:capabilityId`)

Loads `GET /api/v1/capabilities/{id}`.

| Implemented | Notes |
|-------------|-------|
| Capability identity header | Name, code, category badge, capability lifecycle status |
| Capability description | Shown once when present |
| Version selector | Dropdown from `versions[]`; label `version · status` |
| Version-driven tabs | **Overview**, **Contract**, **Dependencies** update when version changes |
| Overview | Version description when present; technical details disclosure (IDs, created/updated) |
| Contract tab | Contract metadata; structured document sections (collapsible); Structured / Raw JSON toggle |
| Dependencies tab | Empty state or table (`capabilityVersionId`, `dependencyType`, `mandatory`) |
| Loading / not found / error + retry | Preserved |
| Back to Capabilities | Link to list |

Default selected version: matches capability-level `version` when possible, else first `ACTIVE` version, else first version in the response.

**Not shown in UI today:** implementation metadata (returned by API), version lifecycle actions, edit/register workflows, POST/PATCH forms.

The details layout is functional; information architecture may continue to be refined. Do not assume deferred UX (create, edit, lifecycle, export) is available in the UI because the backend API supports related operations.

### API vs UI availability

| Capability | Platform API | Core UI |
|------------|--------------|---------|
| List capabilities | GET | Implemented |
| View capability detail | GET | Implemented |
| Create capability | POST | Deferred |
| Update metadata / lifecycle | PATCH | Deferred |
| Delete | — (no API) | Deferred |

## Deferred functionality (summary)

Verified against current `etp-platform-service` and `etp-core-ui` code:

| Item | API | UI | Notes |
|------|-----|-----|-------|
| Create / registration | POST implemented | Deferred | List Create button disabled |
| PATCH / edit workflows | PATCH implemented | Deferred | No edit forms or lifecycle UI |
| PUT metadata update | Not exposed | — | Use PATCH for metadata/lifecycle |
| Lifecycle actions (publish, deprecate, etc.) | PATCH `status` / `versionStatus` | Deferred | Row/detail menus disabled |
| Delete | No endpoint | Deferred | Cancellation via PATCH version lifecycle only |
| Clone | — | Deferred | |
| Bulk operations | — | Deferred | Selection UI only |
| Export / import | — | Deferred | |
| Saved views / manage columns | — | Deferred | |
| Server-side pagination | — | Deferred | Client-side only |
| Advanced / server-side filtering | GET query params exist | Deferred | UI filters client-side on full list |
| Implementation display | GET detail includes field | Deferred | Not rendered in details tabs |
| Capability Registry authorization | APIs permitAll | — | Platform JWT enforcement deferred |
| Audit query API | Events recorded | — | No read API |
| Operating model / entitlements (V14 tables) | — | — | Schema only; no service APIs |

## Package layout (implementation)

```
com.etp.platform.capability
├── api/                    # CapabilityController, request/response DTOs
├── domain/                 # Lifecycle enums, transition validator, semver compare
├── repository/             # Spring Data JPA repositories
├── service/                # CapabilityService, CapabilityMapper
└── validation/             # JSON Schema contract document validator

com.etp.platform.core       # Platform health/info (separate from capability registry)
com.etp.platform.common.audit # AuditService, AuditEvent persistence
```

## Related documentation

- [API_CONVENTIONS.md](API_CONVENTIONS.md) — shared REST conventions
- [ARCHITECTURE.md](../ARCHITECTURE.md) — platform architecture
- [CAPABILITY_DEFINITION_OF_DONE.md](../CAPABILITY_DEFINITION_OF_DONE.md) — capability delivery checklist
