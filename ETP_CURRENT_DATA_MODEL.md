# ETP Current Data Model

Repository snapshot of **actual** database schemas, JPA entities, and persistence usage in `etp-foundation`. This document does not describe target/future ETP architecture except where explicitly contrasted in §10.

**Canonical data-model document** for this repository. It lives only at the repository root.

**Last derived from:** Flyway V1–V14 in `etp-platform-service`, Flyway V1–V2 in `capabilities/etp-iam`, Flyway V1–V11 in `capabilities/etp-migration`, and the export service (no database). A read-only check of container `etp-migration-postgres` (host port 55446, database `etp_migration`) shows migration V1–V9 successful. V10 and V11 are in the repository and are not in that history. Port 55433 was not inspected.

---

# 1. Current Database Overview

## ETP Platform (`etp-platform-service`)

| Aspect | Current state |
|--------|----------------|
| **Database technology** | PostgreSQL 17 |
| **Database name** | `etp_platform` (see `etp-platform-service/docker-compose.yml`, default JDBC URL in `application.yml`) |
| **Schema name(s)** | Default PostgreSQL `public` schema (no explicit schema qualifier in migrations) |
| **Migration framework** | Flyway |
| **Migration location** | `etp-platform-service/src/main/resources/db/migration/` |
| **Migration files** | `V1` … `V14` (14 versioned SQL files; see §2 inventory) |
| **Migration execution** | Spring Boot auto-configures Flyway on startup (`spring.flyway.enabled=true`, `locations=classpath:db/migration` in `etp-platform-service/src/main/resources/application.yml`) |
| **JPA** | Hibernate `ddl-auto=validate` (default profile) — schema is owned by Flyway, not Hibernate |
| **Local alternative** | `local` profile uses in-memory H2 with Flyway disabled (`Not determinable from current source` for full local profile YAML without reading test config; documented in `etp-platform-service/README.md`) |

## ETP IAM (`capabilities/etp-iam`)

| Aspect | Current state |
|--------|----------------|
| **Database technology** | PostgreSQL 17 |
| **Database name** | `etp_iam` (see `capabilities/etp-iam/docker-compose.yml`, `application.properties`) |
| **Schema name(s)** | Default `public` |
| **Migration framework** | Flyway, `classpath:db/migration` (`spring.flyway.enabled=true` in `application.properties`) |
| **Migration files** | `V1__iam_schema.sql` (tables), `V2__platform_dev_seed.sql` (seed). `schema_updated.sql` in the IAM module is not what Spring applies on startup |
| **JPA** | Hibernate `ddl-auto=validate` — schema is owned by Flyway |

## ETP Migration (`capabilities/etp-migration`)

| Aspect | Current state |
|--------|----------------|
| **Database technology** | PostgreSQL 17 |
| **Database name** | `etp_migration` |
| **Migration framework** | Flyway V1–V11 in the repository, `classpath:db/migration`. Startup applies pending versions only |
| **What Flyway owns** | Control-plane tables only (definitions, sources, mappings, runs, batches, reconciliation, promotion, identity allocation) |
| **What Flyway does not own** | Staging tables and business-target tables. Those names come from definition JSON. Default staging schema is `migration_staging`. A local `uam` schema is operator-created |
| **JPA** | Hibernate `ddl-auto=validate` on the control-plane datasource |

## ETP Exports (`capabilities/etp-exports`)

No database. The service calls an external HTTP API and streams CSV or XLSX. It does not persist export jobs.

## Keycloak (external)

| Aspect | Current state |
|--------|----------------|
| **Storage** | Keycloak's own database (container volume `etp-keycloak-data` in IAM docker-compose) |
| **Role** | Human credentials, OAuth2/OIDC clients, JWT issuance — **not** stored in `etp_platform` |

---

# 2. Complete Table Inventory

All tables below are defined in Flyway migrations under `etp-platform-service/src/main/resources/db/migration/`. Unless noted, **no tenant/customer isolation** applies at the row level for capability catalog data.

---

## `customer`

| | |
|---|---|
| **Purpose** | Enterprise customer for whom ETP builds applications (platform metadata). |
| **Primary key** | `customer_id` (UUID) |
| **Foreign keys** | None |
| **Unique constraints** | `uk_customer_customer_code` on `customer_code` |
| **Check constraints** | None |
| **Indexes** | None beyond PK/unique |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by` |
| **Tenant/customer** | Root customer entity; referenced by `application`, entitlement tables |

**Source:** `V1__create_customer.sql`, entity `com.etp.platform.application.Customer`

---

## `capability`

| | |
|---|---|
| **Purpose** | ETP-owned reusable technology capability catalog entry (identity + capability lifecycle). |
| **Primary key** | `capability_id` (UUID) |
| **Foreign keys** | None |
| **Unique constraints** | `uk_capability_capability_code` on `capability_code` |
| **Check constraints** | None |
| **Indexes** | None beyond PK/unique |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by` |
| **Tenant/customer** | **Platform-owned** — no `customer_id`; not customer-scoped |

**Source:** `V2__create_capability.sql`, entity `com.etp.platform.capability.Capability`

---

## `platform_version`

| | |
|---|---|
| **Purpose** | Version metadata for the ETP platform itself. |
| **Primary key** | `platform_version_id` (UUID) |
| **Foreign keys** | None |
| **Unique constraints** | `uk_platform_version_version` on `version` |
| **Check constraints** | None |
| **Indexes** | None beyond PK/unique |
| **Audit columns** | `created_at` only (no `created_by` / `updated_at` / `updated_by`) |
| **Tenant/customer** | None |

**Source:** `V3__create_platform_version.sql`, entity `com.etp.platform.core.PlatformVersion`

---

## `capability_version`

| | |
|---|---|
| **Purpose** | Semantic version of a capability with version lifecycle status. |
| **Primary key** | `capability_version_id` (UUID) |
| **Foreign keys** | `fk_capability_version_capability_id` → `capability(capability_id)` |
| **Unique constraints** | `uk_capability_version_capability_id_version` on (`capability_id`, `version`) |
| **Check constraints** | None |
| **Indexes** | `idx_capability_version_capability_id`; partial unique `uk_capability_version_one_active_per_capability` on (`capability_id`) WHERE `status = 'ACTIVE'` (`V13`) |
| **Audit columns** | `created_at`, `created_by` (no `updated_at` / `updated_by` on version row) |
| **Tenant/customer** | None — child of platform capability |

**Source:** `V4__create_capability_version.sql`, `V13__capability_version_single_active_index.sql`, entity `com.etp.platform.capability.CapabilityVersion`

---

## `capability_contract`

| | |
|---|---|
| **Purpose** | Contract artifacts for a capability version (inline JSONB and/or external location). |
| **Primary key** | `contract_id` (UUID) |
| **Foreign keys** | `fk_capability_contract_capability_version_id` → `capability_version(capability_version_id)` |
| **Unique constraints** | `uk_capability_contract_version_type_version` on (`capability_version_id`, `contract_type`, `contract_version`) |
| **Check constraints** | None |
| **Indexes** | `idx_capability_contract_capability_version_id` |
| **Audit columns** | `created_at`, `updated_at` (no `created_by` / `updated_by`) |
| **Tenant/customer** | None |

**Additional column (V12):** `contract_document` JSONB NULL — inline structured contract document.

**Source:** `V5__create_capability_contract.sql`, `V12__add_capability_contract_document.sql`, entity `com.etp.platform.capability.CapabilityContract`

---

## `capability_implementation`

| | |
|---|---|
| **Purpose** | Technology implementation metadata for a capability version. |
| **Primary key** | `implementation_id` (UUID) |
| **Foreign keys** | `fk_capability_implementation_capability_version_id` → `capability_version` |
| **Unique constraints** | `uk_capability_implementation_version_code` on (`capability_version_id`, `implementation_code`) |
| **Check constraints** | None |
| **Indexes** | `idx_capability_implementation_capability_version_id` |
| **Audit columns** | `created_at` only |
| **Tenant/customer** | None |

**Source:** `V6__create_capability_implementation.sql`, entity `com.etp.platform.capability.CapabilityImplementation`

---

## `capability_dependency`

| | |
|---|---|
| **Purpose** | Version-aware dependency from one capability version to another. |
| **Primary key** | `dependency_id` (UUID) |
| **Foreign keys** | `fk_capability_dependency_capability_version_id` → `capability_version`; `fk_capability_dependency_depends_on_version_id` → `capability_version` |
| **Unique constraints** | `uk_capability_dependency_versions` on (`capability_version_id`, `depends_on_capability_version_id`) |
| **Check constraints** | `chk_capability_dependency_no_self` — `capability_version_id <> depends_on_capability_version_id` |
| **Indexes** | `idx_capability_dependency_capability_version_id`, `idx_capability_dependency_depends_on_version_id` |
| **Audit columns** | `created_at` only |
| **Tenant/customer** | None |

**Source:** `V7__create_capability_dependency.sql`, entity `com.etp.platform.capability.CapabilityDependency`

---

## `application`

| | |
|---|---|
| **Purpose** | Customer-owned business application metadata record. |
| **Primary key** | `application_id` (UUID) |
| **Foreign keys** | `fk_application_customer_id` → `customer(customer_id)` |
| **Unique constraints** | `uk_application_customer_id_application_code` on (`customer_id`, `application_code`) |
| **Check constraints** | None |
| **Indexes** | `idx_application_customer_id` |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by` |
| **Tenant/customer** | **`customer_id` required** — application belongs to one customer |

**Source:** `V8__create_application.sql`, entity `com.etp.platform.application.Application`

---

## `application_definition`

| | |
|---|---|
| **Purpose** | Versioned technology composition definition for an application (not a separate `application_version` table). |
| **Primary key** | `application_definition_id` (UUID) |
| **Foreign keys** | `fk_application_definition_application_id` → `application(application_id)` |
| **Unique constraints** | `uk_application_definition_application_id_version` on (`application_id`, `version`) |
| **Check constraints** | None |
| **Indexes** | `idx_application_definition_application_id` |
| **Audit columns** | `created_at`, `created_by`; approval: `approved_at`, `approved_by` |
| **Tenant/customer** | Indirect via `application` → `customer` |

**Source:** `V9__create_application_definition.sql`, entity `com.etp.platform.application.ApplicationDefinition`

---

## `application_capability`

| | |
|---|---|
| **Purpose** | Links an application definition to a specific capability version (relational composition link). |
| **Primary key** | `application_capability_id` (UUID) |
| **Foreign keys** | `fk_application_capability_application_definition_id` → `application_definition`; `fk_application_capability_capability_version_id` → `capability_version` |
| **Unique constraints** | `uk_application_capability_definition_version` on (`application_definition_id`, `capability_version_id`) |
| **Check constraints** | None |
| **Indexes** | `idx_application_capability_application_definition_id`, `idx_application_capability_capability_version_id` |
| **Audit columns** | `created_at`, `created_by` |
| **Tenant/customer** | Indirect via application definition → application → customer |

**Source:** `V10__create_application_capability.sql`, entity `com.etp.platform.application.ApplicationCapability`

---

## `audit_event`

| | |
|---|---|
| **Purpose** | Platform metadata change audit log (capability registry operations today). |
| **Primary key** | `audit_event_id` (UUID) |
| **Foreign keys** | None (logical reference via `entity_type` + `entity_id`) |
| **Unique constraints** | None |
| **Check constraints** | None |
| **Indexes** | `idx_audit_event_entity_type_entity_id`, `idx_audit_event_performed_at` |
| **Audit columns** | `performed_at`, `performed_by`; optional `correlation_id` |
| **Tenant/customer** | None |

**Source:** `V11__create_audit_event.sql`, entity `com.etp.platform.common.audit.AuditEvent`

---

## `operating_model`

| | |
|---|---|
| **Purpose** | Operating model catalog (e.g. AAS, CAP, ENT). |
| **Primary key** | `operating_model_id` (UUID) |
| **Foreign keys** | None |
| **Unique constraints** | `uk_operating_model_operating_model_code` on `operating_model_code` |
| **Check constraints** | None |
| **Indexes** | None beyond PK/unique |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by` |
| **Tenant/customer** | Platform-level catalog |

**Seed data:** Three rows inserted in `V14` (AAS, CAP, ENT).

**JPA entity:** **None** — schema only.

**Source:** `V14__create_operating_model_entitlements.sql`

---

## `customer_operating_access`

| | |
|---|---|
| **Purpose** | Customer access to an operating model over an effective time range. |
| **Primary key** | `customer_operating_access_id` (UUID) |
| **Foreign keys** | `customer_id` → `customer`; `operating_model_id` → `operating_model` |
| **Unique constraints** | `uk_customer_operating_access_customer_model_from` on (`customer_id`, `operating_model_id`, `effective_from`) |
| **Check constraints** | `chk_customer_operating_access_effective_range` — `effective_to IS NULL OR effective_to > effective_from` |
| **Indexes** | `idx_customer_operating_access_customer_id`, `idx_customer_operating_access_operating_model_id` |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by`; effective dating: `effective_from`, `effective_to` |
| **Tenant/customer** | **`customer_id` required** |

**JPA entity:** **None**.

**Source:** `V14__create_operating_model_entitlements.sql`

---

## `capability_entitlement`

| | |
|---|---|
| **Purpose** | Customer entitlement to a specific capability version (quantity / max applications optional). |
| **Primary key** | `capability_entitlement_id` (UUID) |
| **Foreign keys** | `customer_id` → `customer`; `capability_version_id` → `capability_version` |
| **Unique constraints** | `uk_capability_entitlement_customer_version_from` on (`customer_id`, `capability_version_id`, `effective_from`) |
| **Check constraints** | `chk_capability_entitlement_effective_range`; `chk_capability_entitlement_quantity_positive`; `chk_capability_entitlement_max_applications_positive` |
| **Indexes** | `idx_capability_entitlement_customer_id`, `idx_capability_entitlement_capability_version_id` |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by`; `effective_from`, `effective_to`; `source`, `quantity`, `max_applications` |
| **Tenant/customer** | **`customer_id` required** |

**JPA entity:** **None**.

**Source:** `V14__create_operating_model_entitlements.sql`

---

## `application_entitlement`

| | |
|---|---|
| **Purpose** | Customer entitlement to a specific application. |
| **Primary key** | `application_entitlement_id` (UUID) |
| **Foreign keys** | `customer_id` → `customer`; `application_id` → `application` |
| **Unique constraints** | `uk_application_entitlement_customer_application_from` on (`customer_id`, `application_id`, `effective_from`) |
| **Check constraints** | `chk_application_entitlement_effective_range` |
| **Indexes** | `idx_application_entitlement_customer_id`, `idx_application_entitlement_application_id` |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by`; `effective_from`, `effective_to`; `source` |
| **Tenant/customer** | **`customer_id` required** |

**JPA entity:** **None**.

**Source:** `V14__create_operating_model_entitlements.sql`

---

## `platform_configuration`

| | |
|---|---|
| **Purpose** | Platform-level configuration entries (JSONB value). |
| **Primary key** | `configuration_id` (UUID) |
| **Foreign keys** | None |
| **Unique constraints** | `uk_platform_configuration_configuration_code` on `configuration_code` |
| **Check constraints** | None |
| **Indexes** | None beyond PK/unique |
| **Audit columns** | `created_at`, `created_by`, `updated_at`, `updated_by` |
| **Tenant/customer** | Platform-level |

**JPA entity:** **None**.

**Source:** `V14__create_operating_model_entitlements.sql`

---

# 3. Entity Relationship Model

Platform database (`etp_platform`) — relationships defined by FK migrations:

```
customer
   |
   +---- application
   |         |
   |         +---- application_definition
   |         |         |
   |         |         +---- application_capability ----> capability_version
   |         |
   |         +---- application_entitlement (also FK customer_id)
   |
   +---- customer_operating_access ----> operating_model
   |
   +---- capability_entitlement ----> capability_version

capability (platform-owned, no customer FK)
   |
   +---- capability_version
            |
            +---- capability_contract
            +---- capability_implementation
            +---- capability_dependency ----> capability_version (depends_on)

platform_version          (standalone)
platform_configuration    (standalone)
operating_model           (standalone catalog; seed data in V14)

audit_event               (standalone; logical refs via entity_type + entity_id)
```

IAM database (`etp_iam`) — from `schema_updated.sql`:

```
companies
   |
   +---- tenants

applications (platform-level IAM registry — NOT etp_platform.application)
   |
   +---- resources
   |         |
   |         +---- permissions
   |
   +---- role_application_permissions ----> roles, permissions

principals
   |
   +---- principal_group_mappings ----> groups
                                          |
                                          +---- group_role_mappings ----> roles
                                          +---- group_tenant_assignments ----> tenants, scopes (optional)

scopes (standalone)

roles
   |
   +---- role_application_permissions
```

Keycloak (external): users, credentials, OAuth2 clients — not in either PostgreSQL schema above.

---

# 4. Capability Registry Data Model

## Tables

### `capability`

| Column | Type | Notes |
|--------|------|-------|
| `capability_id` | UUID | PK |
| `capability_code` | VARCHAR(100) | Unique; immutable in API updates |
| `capability_name` | VARCHAR(200) | |
| `description` | TEXT | Nullable |
| `category` | VARCHAR(50) | Nullable |
| `lifecycle_status` | VARCHAR(30) | Capability lifecycle (e.g. `DRAFT`, `ACTIVE`) |
| `criticality` | VARCHAR(30) | Nullable; **not exposed** in current Capability Registry REST DTOs |
| `created_at`, `created_by`, `updated_at`, `updated_by` | | Audit |

**Ownership:** Platform catalog — no `customer_id`. `created_by` / `updated_by` are VARCHAR actor strings (default `etp-platform-system` in service), not FKs to a user table.

**JPA:** `Capability.java` — **Repository:** `CapabilityRepository`

### `capability_version`

| Column | Type | Notes |
|--------|------|-------|
| `capability_version_id` | UUID | PK |
| `capability_id` | UUID | FK → `capability` |
| `version` | VARCHAR(50) | Semantic version string; unique per capability |
| `status` | VARCHAR(30) | Version lifecycle |
| `contract_version` | VARCHAR(50) | Nullable; duplicated concept with `capability_contract.contract_version` |
| `min_platform_version` | VARCHAR(50) | Nullable |
| `release_notes` | TEXT | Mapped to API field `description` on version |
| `effective_from` | TIMESTAMPTZ | Set on activation |
| `deprecated_at` | TIMESTAMPTZ | Set on deprecation |
| `created_at`, `created_by` | | No row-level `updated_at` |

**Lifecycle field:** `status` — enforced in `CapabilityService` + `LifecycleTransitionValidator`; single `ACTIVE` per capability via partial unique index (V13).

**JPA:** `CapabilityVersion.java` — **Repository:** `CapabilityVersionRepository`

### `capability_contract`

| Column | Type | Notes |
|--------|------|-------|
| `contract_id` | UUID | PK |
| `capability_version_id` | UUID | FK |
| `contract_type` | VARCHAR(50) | |
| `contract_version` | VARCHAR(50) | |
| `contract_format` | VARCHAR(30) | |
| `contract_location` | VARCHAR(500) | External reference; nullable |
| `contract_document` | JSONB | Inline document (V12); nullable |
| `description` | TEXT | Nullable |
| `status` | VARCHAR(30) | Set to `ACTIVE` on create in service |
| `created_at`, `updated_at` | | |

**JPA:** `CapabilityContract.java` — **Repository:** `CapabilityContractRepository`

### `capability_implementation`

| Column | Type | Notes |
|--------|------|-------|
| `implementation_id` | UUID | PK |
| `capability_version_id` | UUID | FK |
| `implementation_code` | VARCHAR(100) | Unique per version |
| `implementation_name` | VARCHAR(200) | |
| `technology_name` | VARCHAR(200) | |
| `technology_version` | VARCHAR(100) | Nullable |
| `implementation_type` | VARCHAR(50) | Nullable |
| `configuration_reference` | VARCHAR(500) | Nullable |
| `status` | VARCHAR(30) | |
| `created_at` | TIMESTAMPTZ | |

**JPA:** `CapabilityImplementation.java` — **Repository:** `CapabilityImplementationRepository`

### `capability_dependency`

| Column | Type | Notes |
|--------|------|-------|
| `dependency_id` | UUID | PK |
| `capability_version_id` | UUID | FK — dependent version |
| `depends_on_capability_version_id` | UUID | FK — required version |
| `dependency_type` | VARCHAR(30) | |
| `mandatory` | BOOLEAN | |
| `created_at` | TIMESTAMPTZ | |

**JPA:** `CapabilityDependency.java` — **Repository:** `CapabilityDependencyRepository`

## Creation through versioning (current service behavior)

Source: `CapabilityService.java`

1. **POST create** — Inserts `capability` with `lifecycle_status = DRAFT`, audit `created_by/updated_by = etp-platform-system`.
2. **First version** — `createVersionAggregate()` inserts `capability_version` with `status = DRAFT`, optional `release_notes` from request, copies `contract_version` from contract request onto version row.
3. **Contract** — One `capability_contract` row per version at creation; inline `contract_document` validated against base JSON Schema; `status = ACTIVE`.
4. **Implementation** — Optional single implementation row if provided in POST/PATCH `newVersion`.
5. **Dependencies** — Optional rows referencing other `capability_version_id` values; self-dependency rejected.
6. **Audit** — `audit_event` row: `entity_type = CAPABILITY`, `action = CREATE`.
7. **Further versions** — PATCH `newVersion` repeats version aggregate creation (starts `DRAFT`).
8. **Lifecycle** — PATCH `status` (capability) or `versionStatus` (version) updates status fields; may auto-transition parent capability `DRAFT→ACTIVE` on first version activation; may auto-deprecate prior `ACTIVE` version; audit `STATUS_CHANGE` / `METADATA_UPDATE` / `CREATE_VERSION`.
9. **API aggregate** — `CapabilityMapper.toDetail()` loads all versions for a capability ordered by `createdAt` DESC; exposes `version` on detail/summary as **first row in that ordering** (most recently created), not necessarily the `ACTIVE` version.

**No separate version API resource** — versions are nested in capability aggregate responses and PATCH body.

---

# 5. Application Data Model

## Tables (all in `etp_platform`)

| Table | Role |
|-------|------|
| `application` | Customer-owned application identity |
| `application_definition` | Versioned composition definition per application |
| `application_capability` | Capability version selections for a definition |

## Relationship chain (exists in schema)

```
customer
   ↓
application
   ↓
application_definition   (column `version` — NOT a separate application_version table)
   ↓
application_capability
   ↓
capability_version
   ↓
capability
```

## JPA vs services

| Entity | JPA entity exists | Repository | REST API in platform-service |
|--------|-------------------|------------|----------------------------|
| `Customer` | Yes | **No** | **No** |
| `Application` | Yes | **No** | **No** |
| `ApplicationDefinition` | Yes | **No** | **No** |
| `ApplicationCapability` | Yes | **No** | **No** |

Application tables are **schema + JPA-mapped entities only** for Hibernate validation. No application-layer repositories or controllers exist in the current codebase.

`application_definition.definition_document` (JSONB) can hold supplementary composition JSON; `application_capability` is the authoritative relational link to `capability_version` per migration design comments in architecture docs.

---

# 6. Customer / Tenant Model

## ETP Platform (`etp_platform`)

| Concept | Representation |
|---------|----------------|
| **Customer** | Table `customer` — `customer_id`, `customer_code`, `customer_name`, `status` |
| **Tenant** | **Not present** in platform database |
| **Business unit** | **Not present** |
| **Owner** | No `owner` column on capability or customer. `created_by` / `updated_by` / `approved_by` / `performed_by` are free-text VARCHAR fields, not FKs to principals or users |
| **Application ownership** | `application.customer_id` → `customer` |
| **Capability ownership** | Platform-global catalog — capabilities are **not** customer-owned |
| **Isolation boundary** | Customer data isolated via `application.customer_id` and entitlement tables' `customer_id`; capability catalog is shared |

## ETP IAM (`etp_iam`)

| Concept | Representation |
|---------|----------------|
| **Company** | Table `companies` — parent of tenants |
| **Tenant** | Table `tenants` — `company_id` FK; optional `environment` column (VARCHAR) |
| **Customer (platform)** | **No** `customer` table — IAM uses **company** / **tenant**, not `etp_platform.customer` |
| **Principal** | `principals` — `USER` or `SERVICE`; mapped from JWT `issuer` + `external_id` (Keycloak subject) |
| **Owner** | `applications.owner` VARCHAR on IAM `applications` table only |

**No linkage** between `etp_platform.customer` and `etp_iam.companies` / `tenants` exists in the repository (no shared FK, no bridge table).

---

# 7. IAM / Security Data Model

## Storage split

| Concern | Where stored |
|---------|----------------|
| Human passwords, OAuth2 clients, realm users | **Keycloak** (external IdP) |
| Principals, groups, roles, permissions, tenant assignments | **`etp_iam` PostgreSQL** |
| Capability registry authorization | **Not in IAM DB** for platform APIs — platform `SecurityConfig` permits `/api/v1/capabilities/**` without JWT validation |
| Platform customer/capability metadata | **`etp_platform`** — separate database |

## IAM tables (`etp_iam` — Flyway `V1__iam_schema.sql`)

| Table | Purpose |
|-------|---------|
| `companies` | Company / org root |
| `tenants` | Tenant under company; optional `environment` |
| `applications` | **IAM authorization application registry** (platform-level; not customer app metadata) |
| `resources` | API/FEATURE resources per IAM application |
| `permissions` | Permissions on resources |
| `principals` | USER or SERVICE identity (Keycloak JWT mapping) |
| `groups` | HUMAN / SERVICE / MIXED groups |
| `principal_group_mappings` | Principal ↔ group |
| `roles` | Role definitions |
| `group_role_mappings` | Group ↔ role |
| `role_application_permissions` | Role ↔ IAM application ↔ permission |
| `scopes` | Scope definitions for tenant assignments |
| `group_tenant_assignments` | Group ↔ tenant ↔ optional scope |

**Not in IAM PostgreSQL:** Keycloak user credentials, client secrets (server-side config in IAM app), OAuth2 client registry tables (Keycloak).

**JPA entities:** `com.etp.iam.entity.*` (Company, Tenant, Application, Resource, Permission, Principal, Group, Role, Scope, mapping entities).

**Repositories:** `CompanyRepository`, `TenantRepository`, `ApplicationRepository`, `ResourceRepository`, `PermissionRepository`, `PrincipalRepository`, `GroupRepository`, `RoleRepository`, `ScopeRepository`, mapping repositories.

**Configuration:** `capabilities/etp-iam/src/main/resources/application.properties` — DB URL, Keycloak issuer, JWT resource server.

---

# 8. Audit Model

## Table: `audit_event` (`etp_platform`)

| Column | Purpose |
|--------|---------|
| `audit_event_id` | PK |
| `entity_type` | Logical entity name (string) |
| `entity_id` | UUID of audited entity |
| `action` | Action code (string) |
| `old_value` | JSONB — prior state snapshot |
| `new_value` | JSONB — new state snapshot |
| `performed_by` | Actor string (VARCHAR 200) |
| `performed_at` | Timestamp |
| `correlation_id` | Optional VARCHAR 100 — **column exists but `AuditService.record()` does not set it** |

## What is audited today

From `AuditService` constants and `CapabilityService` usage:

| `entity_type` | `action` values used | When |
|---------------|----------------------|------|
| `CAPABILITY` | `CREATE`, `METADATA_UPDATE`, `STATUS_CHANGE` | Create capability; metadata PATCH; capability lifecycle |
| `CAPABILITY_VERSION` | `CREATE_VERSION`, `STATUS_CHANGE` | New version; version lifecycle |

`performed_by` is always `etp-platform-system` (`AuditService.PERFORMED_BY_SYSTEM`) in current `AuditService.record()` — not the end-user from Core UI or IAM.

**No audit read API** — `AuditEventRepository` exists but is only used for writes.

Application, customer, entitlement, and IAM tables are **not** written to `audit_event` by current platform service code.

---

# 9. Platform Metadata

| Table | Use | JPA / service usage |
|-------|-----|---------------------|
| `platform_version` | ETP platform release versions | Entity `PlatformVersion`; **no repository or API** located |
| `platform_configuration` | Platform config JSONB by code | **Schema only (V14)** |
| `operating_model` | Operating model catalog | **Schema only (V14)**; seed data |
| `customer_operating_access` | Customer ↔ operating model | **Schema only (V14)** |
| `capability_entitlement` | Customer ↔ capability version entitlement | **Schema only (V14)** |
| `application_entitlement` | Customer ↔ application entitlement | **Schema only (V14)** |
| `capability` + children | Capability catalog metadata | **Full JPA + Capability Registry APIs** |
| `customer`, `application`, `application_definition`, `application_capability` | Application/customer metadata | JPA entities only; **no APIs** |
| `audit_event` | Change audit | JPA + write-only via `AuditService` |

**Not present:** `environment`, `deployment`, `application_instance` tables in `etp_platform`.

IAM `tenants.environment` is the only `environment` column found in repository schemas.

---

# 10. Current Data Model vs ETP Target Architecture

## Already Exists

**In `etp_platform` schema (Flyway):**

- `customer`
- `application` (customer-scoped)
- `application_definition` (versioned definition — not a separate `application_version` table name)
- `application_capability`
- `capability`, `capability_version`, `capability_contract`, `capability_implementation`, `capability_dependency`
- `platform_version`
- `audit_event`
- `operating_model` (with seed rows)
- `customer_operating_access`
- `capability_entitlement`
- `application_entitlement`
- `platform_configuration`

**Implemented application behavior:**

- Capability Registry CRUD-ish APIs (GET, POST, GET by id, PATCH) with lifecycle and audit for capabilities/versions
- Hibernate entity mapping for customer/application stack (validation only)

**In `etp_iam` schema (Flyway V1, seed V2):**

- Full RBAC model: companies, tenants, IAM applications, resources, permissions, principals, groups, roles, scopes, mappings, tenant assignments

**In `etp_migration` (Flyway V1–V11 in the repository, control plane only):**

- `migration_definition`, `migration_source`, `migration_mapping`, `migration_mapping_rule`
- `migration_run`, `migration_batch`, `migration_artifact`, `migration_error`, `migration_record`
- `migration_reconciliation`, `migration_phase_job`
- `migration_promotion`, `migration_promotion_batch`
- `migration_identity_allocation`, `target_identity_allocator`

**External:**

- Keycloak authentication store

## Missing / Not Yet Implemented

Concepts **without tables** in this repository:

- `customer_contract`
- `subscription` (as a table/entity)
- `application_version` (as a separate table — composition versioning uses `application_definition.version`)
- `application_instance`
- `deployment`
- `environment` (platform DB)
- `capability_instance`

Concepts **with tables but no Java service/API** in platform-service:

- `operating_model`, `customer_operating_access`, `capability_entitlement`, `application_entitlement`, `platform_configuration` (V14 — schema foundation only)
- `customer`, `application`, `application_definition`, `application_capability` (no repositories/controllers)
- `platform_version` (entity only)

Other gaps:

- Audit query API
- Link between `etp_platform.customer` and `etp_iam` company/tenant
- Platform API JWT authorization / capability-specific IAM permissions
- `correlation_id` population in audit writes
- `criticality` exposure in Capability Registry API

---

# 11. Important Findings

1. **Two different `application` concepts** — `etp_platform.application` (customer business app metadata) vs `etp_iam.applications` (authorization registry). Same word, different databases, no FK relationship.

2. **Duplicate version semantics** — `capability_version.contract_version` column coexists with `capability_contract.contract_version`; service copies contract request version onto version row at create.

3. **Summary `version` field semantics** — API `version` on list/detail reflects **most recently created** capability version (`createdAt` DESC), not necessarily `ACTIVE`.

4. **V14 entitlement tables without application layer** — Flyway creates and can enforce FK integrity, but no JPA entities, repositories, or services reference them; risk of schema drift if applied without follow-up code.

5. **JPA entities without repositories** — Customer, Application, ApplicationDefinition, ApplicationCapability, PlatformVersion exist for `ddl-auto=validate` but cannot be persisted through platform-service repositories today.

6. **Audit actor is always system** — `performed_by` does not capture human users from Core UI/IAM; `correlation_id` column unused in `AuditService.record()`.

7. **No owner FK model** — `created_by` / `updated_by` are opaque strings; no referential integrity to IAM `principals`.

8. **Capability catalog is global** — No customer_id on capability tables; entitlements (V14) are the only customer↔capability_version link in schema, but unused by code.

9. **Single ACTIVE enforcement** — Application logic plus partial unique index `uk_capability_version_one_active_per_capability` (V13); V13 migration may mutate existing data before creating index.

10. **IAM vs platform tenant model** — IAM `tenants` under `companies`; platform `customer` is a separate concept with no mapped bridge.

11. **No DELETE anywhere** — Lifecycle terminal states only; no soft-delete columns identified.

12. **contract_version on capability_version** — Nullable and not updated in all code paths visible in `CapabilityService` after initial create (Not determinable from current source whether PATCH updates it).

13. **IAM schema is Flyway-owned** — `application.properties` enables Flyway. `schema_updated.sql` is not the startup path.

14. **Migration staging is not a Flyway table** — Control-plane rows live in `etp_migration`. Staged payloads and promoted business rows live in schemas named by the definition (`migration_staging`, and for the local demonstration `uam`).

15. **Core UI does not read `etp_platform` directly** — `etp-core-ui` calls `GET /api/v1/capabilities` and `GET /api/v1/capabilities/{id}` only. `customer`, `application`, `application_definition`, `application_capability`, `audit_event`, `platform_version`, and the V14 entitlement tables have no UI and, except the capability catalog and audit writes, no service API. Contract and implementation `status` columns are set to `ACTIVE` when the registry inserts the row. That value is not the capability or version lifecycle.

---

# 12. Migration control plane

Database `etp_migration`. Flyway location: `capabilities/etp-migration/src/main/resources/db/migration/`.

| Table | Purpose |
|-------|---------|
| `migration_definition` | Named migration: source type, target type, load mode, batch size, JSON configuration (including promotion and UAM target settings), lifecycle |
| `migration_source` | One source bound to a definition (filesystem CSV or SFTP CSV configuration). Credentials are referenced, not stored as passwords |
| `migration_mapping` | Reusable mapping definition |
| `migration_mapping_rule` | Ordered field rules for a mapping (`V3` adds entity name) |
| `migration_run` | One execution of an ACTIVE definition. V6 adds `background_execution`, `execution_worker_id`, and `execution_lease_until` for accepted background runs |
| `migration_batch` | Claimed work unit inside a run, including lease columns for workers |
| `migration_artifact` | Metadata for source artifacts associated with a run |
| `migration_error` | Recorded failure for a run, execution batch, promotion batch, or phase job. `V11` allows stage `PROMOTE` |
| `migration_record` | Per-record lineage table. Success lineage is not fully used by the current pipeline |
| `migration_reconciliation` | Reconciliation result for a run (count, checksum, target, full). Outcome is independent of the phase job status |
| `migration_phase_job` | Background reconciliation, promotion preparation, or promotion (`V7`). One queued or running job per run, phase, and scope |
| `migration_promotion` | Explicit promotion of a run. Created by prepare, executed by promote |
| `migration_promotion_batch` | Per-batch promotion outcome. `started_at` and `completed_at` are the latest attempt. Duration is computed by the batch API and is not a column |
| `migration_run.control_state` | `RUNNING`, `PAUSE_REQUESTED`, `PAUSED`, or `RESUMING` (`V8`). Independent of lifecycle status |
| `migration_promotion.control_state` | Same control values for promotion (`V8`). Independent of run control and of promotion status |
| `migration_identity_allocation` | Target identities already handed out, so a retry can reuse them (`V4`) |
| `target_identity_allocator` | PostgreSQL sequence ranges for `PREFIX_SEQUENCE` identities (`V5`) |

Default JDBC in `application.properties` is `localhost:55433/etp_migration`. Copy `capabilities/etp-migration/.env.example` before Compose. The Compose file’s fallback host port is `55446` when `ETP_MIGRATION_DB_PORT` is unset, which does not match the application default.

---

# 13. Source References

## Migration control plane

- `capabilities/etp-migration/src/main/resources/db/migration/V1__initial_migration_schema.sql` through `V8__execution_control_state.sql`, Java migration `V9__StagingBatchLookupIndex`, `V10__one_open_promotion_per_run.sql`, and `V11__promotion_error_stage.sql`. V9 does not alter control-plane tables. It creates `idx_uam_entity_stage_run_batch` only when `migration_staging.uam_entity_stage` is in the database being migrated. V10 adds partial unique index `uk_migration_promotion_open` on `migration_promotion (migration_run_id)` where status is `NOT_STARTED`, `READY`, or `PROMOTING`. V11 adds `PROMOTE` to the `migration_error.stage` check.
- Column-level detail: `capabilities/etp-migration/docs/DATABASE_MODEL.md`

## Platform migrations

| File | Creates / alters |
|------|------------------|
| `etp-platform-service/src/main/resources/db/migration/V1__create_customer.sql` | `customer` |
| `etp-platform-service/src/main/resources/db/migration/V2__create_capability.sql` | `capability` |
| `etp-platform-service/src/main/resources/db/migration/V3__create_platform_version.sql` | `platform_version` |
| `etp-platform-service/src/main/resources/db/migration/V4__create_capability_version.sql` | `capability_version` |
| `etp-platform-service/src/main/resources/db/migration/V5__create_capability_contract.sql` | `capability_contract` |
| `etp-platform-service/src/main/resources/db/migration/V6__create_capability_implementation.sql` | `capability_implementation` |
| `etp-platform-service/src/main/resources/db/migration/V7__create_capability_dependency.sql` | `capability_dependency` |
| `etp-platform-service/src/main/resources/db/migration/V8__create_application.sql` | `application` |
| `etp-platform-service/src/main/resources/db/migration/V9__create_application_definition.sql` | `application_definition` |
| `etp-platform-service/src/main/resources/db/migration/V10__create_application_capability.sql` | `application_capability` |
| `etp-platform-service/src/main/resources/db/migration/V11__create_audit_event.sql` | `audit_event` |
| `etp-platform-service/src/main/resources/db/migration/V12__add_capability_contract_document.sql` | `capability_contract.contract_document` |
| `etp-platform-service/src/main/resources/db/migration/V13__capability_version_single_active_index.sql` | partial unique index on `capability_version` |
| `etp-platform-service/src/main/resources/db/migration/V14__create_operating_model_entitlements.sql` | `operating_model`, `customer_operating_access`, `capability_entitlement`, `application_entitlement`, `platform_configuration` |

## Platform configuration

| File | Relevance |
|------|-----------|
| `etp-platform-service/src/main/resources/application.yml` | Flyway, datasource `etp_platform`, JPA validate |
| `etp-platform-service/docker-compose.yml` | PostgreSQL 17, database name |

## Platform JPA entities

| Class | Table |
|-------|-------|
| `com.etp.platform.application.Customer` | `customer` |
| `com.etp.platform.application.Application` | `application` |
| `com.etp.platform.application.ApplicationDefinition` | `application_definition` |
| `com.etp.platform.application.ApplicationCapability` | `application_capability` |
| `com.etp.platform.capability.Capability` | `capability` |
| `com.etp.platform.capability.CapabilityVersion` | `capability_version` |
| `com.etp.platform.capability.CapabilityContract` | `capability_contract` |
| `com.etp.platform.capability.CapabilityImplementation` | `capability_implementation` |
| `com.etp.platform.capability.CapabilityDependency` | `capability_dependency` |
| `com.etp.platform.core.PlatformVersion` | `platform_version` |
| `com.etp.platform.common.audit.AuditEvent` | `audit_event` |

## Platform repositories (only these exist)

| Interface | Entity |
|-----------|--------|
| `com.etp.platform.capability.repository.CapabilityRepository` | `Capability` |
| `com.etp.platform.capability.repository.CapabilityVersionRepository` | `CapabilityVersion` |
| `com.etp.platform.capability.repository.CapabilityContractRepository` | `CapabilityContract` |
| `com.etp.platform.capability.repository.CapabilityImplementationRepository` | `CapabilityImplementation` |
| `com.etp.platform.capability.repository.CapabilityDependencyRepository` | `CapabilityDependency` |
| `com.etp.platform.common.audit.AuditEventRepository` | `AuditEvent` |

## Platform services

| Class | Role |
|-------|------|
| `com.etp.platform.capability.service.CapabilityService` | Create, read, patch capabilities/versions; audit writes |
| `com.etp.platform.capability.service.CapabilityMapper` | Entity → API DTO mapping |
| `com.etp.platform.common.audit.AuditService` | `audit_event` inserts |

## Contract validation schema

| File | Role |
|------|------|
| `etp-platform-service/src/main/resources/schemas/capability-contract-base.schema.json` | Inline `contract_document` JSON Schema sections |

## IAM schema and config

| File | Role |
|------|------|
| `capabilities/etp-iam/schema_updated.sql` | Full `etp_iam` DDL (13 tables) |
| `capabilities/etp-iam/src/main/resources/application.properties` | `etp_iam` JDBC, Keycloak, port 8081 |
| `capabilities/etp-iam/docker-compose.yml` | `etp_iam` Postgres, Keycloak |
| `capabilities/etp-iam/src/main/java/com/etp/iam/entity/*` | JPA mappings for IAM tables |

## Security (API vs data)

| File | Role |
|------|------|
| `etp-platform-service/src/main/java/com/etp/platform/security/SecurityConfig.java` | `permitAll` on `/api/v1/capabilities/**` |
| `capabilities/etp-iam/src/main/java/com/etp/iam/config/SecurityConfig.java` | IAM JWT resource server (not platform) |

---

*This document reflects repository state only. It is not a migration plan or target architecture specification.*
