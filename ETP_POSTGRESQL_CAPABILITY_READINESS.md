# ETP PostgreSQL — Capability Readiness

Step 1 (ETP/UAM program): analysis of **current PostgreSQL usage** in `etp-foundation` and what is required to expose PostgreSQL as a reusable ETP capability (`etp.postgresql`).

**Status:** Analysis only — no code, database, or Capability Registry changes.

**Reference onboarding pattern:** [capabilities/etp-iam/ETP_IAM_CAPABILITY_READINESS.md](capabilities/etp-iam/ETP_IAM_CAPABILITY_READINESS.md) (`etp.iam` v1.0.0 registered and **ACTIVE**).

**Sources of truth:** repository code, Docker Compose, Flyway migrations, Spring configuration, `ETP_CURRENT_DATA_MODEL.md`, Capability Registry model in `etp-platform-service`.

---

# 1. Current PostgreSQL Inventory

## PostgreSQL containers (Docker Compose)

| Container | Image | Compose file | Host port (default) | Database | User |
|-----------|-------|--------------|---------------------|----------|------|
| `etp-platform-postgres` | `postgres:17` | `etp-platform-service/docker-compose.yml` | `5434` (`ETP_DB_PORT`) | `etp_platform` | `etp_admin` |
| `etp-iam-postgres` | `postgres:17` | `capabilities/etp-iam/docker-compose.yml` | `55432` (`ETP_IAM_DB_PORT`) | `etp_iam` | `etp_admin` (default) |

**Not PostgreSQL:** Keycloak in IAM compose uses `start-dev` with volume `etp-keycloak-data` (embedded/dev storage), not the IAM Postgres container.

**Optional admin UI:** `etp-iam-pgadmin` (`dpage/pgadmin4`) in IAM compose only — not part of platform compose.

## Kubernetes

No Kubernetes manifests, Helm charts, or k8s configuration found in the repository.

## JDBC / Spring Data / JPA

| Service | Config file | Driver | JPA `ddl-auto` | Pool |
|---------|-------------|--------|----------------|------|
| ETP Platform Service | `etp-platform-service/src/main/resources/application.yml` | `org.postgresql.Driver` | `validate` | Spring Boot default HikariCP (no explicit pool settings) |
| ETP IAM | `capabilities/etp-iam/src/main/resources/application.properties` | `org.postgresql.Driver` | `validate` | Spring Boot default HikariCP |
| Platform `local` profile | `etp-platform-service/src/main/resources/application-local.yml` | H2 (not PostgreSQL) | `none` | — |

## Flyway migrations

| Service | Location | Count | Enabled |
|---------|----------|-------|---------|
| ETP Platform | `etp-platform-service/src/main/resources/db/migration/` | V1–V14 (14 files) | `spring.flyway.enabled=true` (default profile) |
| ETP IAM | `capabilities/etp-iam/src/main/resources/db/migration/` | V1 (schema), V2 (dev seed) | `spring.flyway.enabled=true` |
| Platform `local` profile | — | — | Flyway **disabled** |

## Manual / reference SQL (IAM)

| File | Role |
|------|------|
| `capabilities/etp-iam/schema_updated.sql` | Human-readable reference copy of IAM schema (Flyway V1 source) |

## Environment variables

| File | Scope |
|------|-------|
| `etp-platform-service/.env.example` | `ETP_DB_URL`, `ETP_DB_USERNAME`, `ETP_DB_PASSWORD`, `ETP_DB_PORT` |
| `capabilities/etp-iam/.env.example` | `ETP_IAM_DB_*`, ports, Keycloak, pgAdmin |

## Health checks

| Component | Mechanism | Path / check |
|-----------|-----------|----------------|
| Platform Postgres container | Docker `pg_isready` | `docker-compose.yml` healthcheck |
| IAM Postgres container | No Docker healthcheck in compose | — |
| Platform Service | Spring Actuator `db` + custom `DatabaseConnectivityHealthIndicator` | `/actuator/health` |
| IAM Service | Spring Actuator `db` + custom `DatabaseConnectivityHealthIndicator` | `/actuator/health` |
| Platform app health API | `HealthController` (JVM up only, **not** DB-specific) | `/api/v1/health` |

## Test database configuration

| Test | Approach | Path |
|------|----------|------|
| Platform integration tests | Testcontainers `postgres:17`, DB `etp_platform` | `etp-platform-service/src/test/java/com/etp/platform/AbstractPostgreSqlIntegrationTest.java` |
| Platform local Postgres tests | Fixed JDBC `localhost:5434/etp_platform` | `CapabilityPostLocalPostgreSQLTest.java`, `CapabilityVersionLifecyclePostgreSQLTest.java` |
| IAM `JpaSchemaValidationTest` | Live Postgres `localhost:55432/etp_iam` | `capabilities/etp-iam/src/test/resources/application.properties` |
| Platform `local` profile tests | H2 in-memory | `application-local.yml` |

## Documentation

| Document | PostgreSQL content |
|----------|-------------------|
| `ETP_CURRENT_DATA_MODEL.md` | Platform + IAM database overview (note: IAM Flyway section may lag implementation) |
| `etp-platform-service/README.md` | `etp_platform` setup, env vars, Docker Compose |
| `ETP-ARCHITECTURE.md` | Metadata store, Flyway, isolation principles |
| `capabilities/etp-iam/README.md`, `ETP_IAM_CAPABILITY_DEPLOYMENT.md` | `etp_iam` setup |
| `capabilities/etp-iam/etp-iam-capability-registration.json` | PostgreSQL listed as **external** dependency of IAM |
| `ETP_APIS.md` and `ETP_CAPABILITY_DEFINITION.md` | Capability model (no Postgres capability registered today) |

## Relevant file paths (complete list)

### Infrastructure

- `etp-platform-service/docker-compose.yml`
- `capabilities/etp-iam/docker-compose.yml`
- `etp-platform-service/.env.example`
- `capabilities/etp-iam/.env.example`

### Platform service

- `etp-platform-service/src/main/resources/application.yml`
- `etp-platform-service/src/main/resources/application-local.yml`
- `etp-platform-service/src/main/resources/db/migration/V1__create_customer.sql` … `V14__create_operating_model_entitlements.sql`
- `etp-platform-service/pom.xml` (PostgreSQL JDBC, Flyway, JPA, Testcontainers)
- `etp-platform-service/src/main/java/com/etp/platform/environment/DatabaseConnectivityHealthIndicator.java`
- `etp-platform-service/src/main/java/com/etp/platform/core/HealthController.java`
- `etp-platform-service/src/test/java/com/etp/platform/AbstractPostgreSqlIntegrationTest.java`
- `etp-platform-service/src/test/java/com/etp/platform/PostgreSQLSchemaIntegrationTest.java`
- `etp-platform-service/src/test/java/com/etp/platform/capability/CapabilityPostLocalPostgreSQLTest.java`
- `etp-platform-service/src/test/java/com/etp/platform/capability/CapabilityVersionLifecyclePostgreSQLTest.java`

### IAM capability

- `capabilities/etp-iam/src/main/resources/application.properties`
- `capabilities/etp-iam/src/main/resources/application-dev.properties`
- `capabilities/etp-iam/src/main/resources/db/migration/V1__iam_schema.sql`
- `capabilities/etp-iam/src/main/resources/db/migration/V2__platform_dev_seed.sql`
- `capabilities/etp-iam/schema_updated.sql`
- `capabilities/etp-iam/pom.xml`
- `capabilities/etp-iam/src/main/java/com/etp/iam/environment/DatabaseConnectivityHealthIndicator.java`
- `capabilities/etp-iam/src/test/resources/application.properties`

### Registry / tests (PostgreSQL as example code only)

- `etp-platform-service/src/test/java/com/etp/platform/capability/CapabilityApiIntegrationTest.java` (uses code `etp.postgresql` in **tests only** — not a registered capability)

### Data model reference

- `ETP_CURRENT_DATA_MODEL.md`

**No** `capabilities/etp-postgresql/` module, **no** PostgreSQL Spring Boot service, **no** shared root `docker-compose.yml` for all ETP databases.

---

# 2. Current Databases

Verified from compose files, JDBC URLs, and migrations. **Two application PostgreSQL databases** plus **Keycloak internal storage** (not an ETP-managed SQL database).

| Database name | Purpose | Owning service | Schema | Initialization | Migrations | Credentials source |
|---------------|---------|----------------|--------|----------------|------------|-------------------|
| `etp_platform` | ETP platform metadata (customers, applications, capability catalog, audit, V14 entitlement schema) | `etp-platform-service` | `public` (default) | Docker `POSTGRES_DB` on first container start | Flyway V1–V14 in platform service | `ETP_DB_*` env / `.env` |
| `etp_iam` | IAM authorization metadata (13 tables) | `capabilities/etp-iam` | `public` (default) | Docker `POSTGRES_DB` on first container start | Flyway V1–V2 on IAM service startup | `ETP_IAM_DB_*` env / `.env` |
| Keycloak dev data | Keycloak realm/users/clients (IdP state) | Keycloak container | Keycloak internal (not `etp_*`) | Keycloak `start-dev` + volume | Not Flyway; not ETP-managed | `ETP_IAM_KEYCLOAK_*` |

**No** `etp_uam`, shared multi-tenant business database, or third ETP application database exists in the repository today.

**Isolation model today:** **separate PostgreSQL instances** (two containers) with **one database per service** — not one shared cluster with multiple databases provisioned by a platform layer (though each container could host additional databases manually; nothing in repo automates that).

---

# 3. Current PostgreSQL Usage Map

```
ETP Core UI
    → HTTP only (no direct PostgreSQL)
    → etp-platform-service APIs, etp-iam auth APIs

ETP Platform Service (Spring Boot, port 8080)
    → JDBC (HikariCP)
    → PostgreSQL container etp-platform-postgres (port 5434)
    → database etp_platform
    → Flyway migrations (application-owned)
    → Spring Data JPA / Hibernate

ETP IAM Service (Spring Boot, port 8081)
    → JDBC (HikariCP)
    → PostgreSQL container etp-iam-postgres (port 55432)
    → database etp_iam
    → Flyway migrations (application-owned)
    → Spring Data JPA / Hibernate

Keycloak (IAM compose)
    → own dev storage volume (not etp_iam / etp_platform)
```

**No other Java services** in the repository connect to PostgreSQL.

---

# 4. ETP PostgreSQL Capability Boundary

## Conceptual capability: `etp.postgresql`

PostgreSQL in ETP today is **infrastructure consumed by capabilities and the platform service**, not a deployable Spring Boot “database service” with REST APIs.

### What `etp.postgresql` should provide (ETP responsibility)

A **catalogued, versioned contract** for how ETP components obtain and operate against PostgreSQL:

| Concern | ETP capability scope (conceptual) |
|---------|-----------------------------------|
| Supported PostgreSQL version | e.g. 17 (matches current images) |
| Connectivity contract | JDBC URL pattern, driver, timezone (`UTC`), env var naming conventions |
| Database-per-application isolation pattern | Dedicated DB (and optionally dedicated instance) per service/application |
| Migration ownership | Application/capability owns Flyway scripts; Postgres capability does not run app migrations |
| Health/readiness expectations | `pg_isready` / JDBC `isValid` patterns |
| Local development provisioning | Docker Compose reference, ports, volumes |
| Security baseline | Secrets via env; no credentials in registry contract; least-privilege user per database |
| Operational dependencies | Startup order: Postgres before consumers |

### PostgreSQL software responsibility

| Concern | Owner |
|---------|--------|
| SQL engine, replication, backups, patching | PostgreSQL / operator (or managed cloud RDS) |
| Raw `CREATE DATABASE`, extensions, tuning | Postgres operator or provisioning automation |
| pgAdmin / DBA tooling | Optional ops tooling (IAM compose includes pgAdmin for dev only) |

### Application / capability responsibility (current and expected)

| Concern | Owner |
|---------|--------|
| Schema design and Flyway migrations | Each service (platform, IAM, future UAM) |
| JPA entities and repositories | Each service |
| Connection pool sizing / tuning | Each service (Spring Boot defaults today) |
| Business data | Application database — **not** `etp_platform` |

**Do not** assume `etp.postgresql` exposes raw PostgreSQL administration APIs (create user, run arbitrary SQL) unless explicitly built later. The `etp.iam` pattern is a **capability contract + implementation metadata**, not necessarily a new JVM service.

---

# 5. Application Consumption Model

## Target (UAM program)

```
UAM Application Service
    ↓
ETP PostgreSQL Capability (contract + provisioning pattern)
    ↓
UAM PostgreSQL Database (e.g. etp_uam)
```

## Current ETP architecture support

| Aspect | Supported today? | Evidence |
|--------|------------------|----------|
| Registered `etp.postgresql` capability | **No** | Only test fixture code `etp.postgresql` in `CapabilityApiIntegrationTest` |
| Platform API to provision application databases | **No** | No provisioning service or REST API |
| Application Registry wiring DB to apps | **No** | `application_definition.definition_document` JSONB exists; no initializer service |
| Documented pattern: dedicated DB per service | **Yes** | Platform README, ETP-ARCHITECTURE.md, IAM deployment guide |
| Consumer uses Spring Data JPA directly | **Yes** | Platform + IAM |
| Consumer uses Flyway for schema | **Yes** | Platform + IAM |
| Shared database across services | **No** | Explicit separation `etp_platform` vs `etp_iam` |
| Schema-only isolation within one DB | **Not used** | All tables in `public`; isolation is **database-level** (separate DBs/containers) |
| Connection info via env vars | **Yes** | `ETP_DB_*`, `ETP_IAM_DB_*` |
| Connection info via Capability Registry | **No** | Registry stores capability contracts, not live JDBC secrets for apps |

**Conclusion:** UAM must **today** follow the same practical pattern as IAM: own Postgres container (or shared cluster + new database), own Flyway migrations, own Spring datasource config, env-driven credentials. The **target consumption model is architectural intent**; the **platform does not yet automate** database provisioning for business applications.

---

# 6. Capability Implementation Model

## Options evaluated

| Model | Description | Fit with current ETP |
|-------|-------------|----------------------|
| **A. Standalone ETP runtime service** | Spring Boot “postgres capability” with APIs | **Poor** — no precedent; Postgres is not an app-layer service in ETP |
| **B. Infrastructure capability (configuration/provisioning)** | Registry contract + compose/ops docs + optional provisioning scripts | **Strong** — matches how IAM documents external Postgres today |
| **C. Framework/library only** | Shared JDBC/Flyway starter library | **Partial** — could help UAM but nothing exists; duplicates Spring Boot defaults |
| **D. Infrastructure + ETP management layer** | B plus future Application Initializer provisioning DBs | **Target direction** — V14 entitlement schema hints at future governance; **not implemented** |

## Recommendation

**Primary: B (infrastructure capability represented through contract, deployment documentation, and reference provisioning).**

**Secondary (future): D** — when Application Initializer / Composer exists, add provisioning hooks that create databases/users per application definition. That is **not** required to register a first `etp.postgresql` v1.0.0 contract mirroring `etp.iam` onboarding.

**Do not** build a standalone PostgreSQL Spring Boot microservice for v1.0.0 unless product requirements explicitly require a database broker API.

Suggested implementation metadata (analogous to IAM):

| Field | Proposed value |
|-------|----------------|
| `code` | `etp-postgresql` or `postgres-17` |
| `technology` | `PostgreSQL` |
| `technologyVersion` | `17` |
| Artifact | Docker image `postgres:17` + reference Compose fragment (not a JVM jar) |

---

# 7. Security

## Current state

| Topic | Current state | Notes |
|-------|---------------|-------|
| Credentials | Env vars (`ETP_DB_PASSWORD`, `ETP_IAM_DB_PASSWORD`) | `.env.example` uses `change_me` placeholders |
| Secrets in repo | Not committed (`.env` local) | Good |
| TLS/SSL to Postgres | **Not configured** | JDBC URLs are plain `jdbc:postgresql://host:port/db` |
| Network | Local dev: published Docker ports | No network policies in repo |
| Application isolation | Separate DB + separate containers (platform vs IAM) | Same username name `etp_admin` on both — isolation by instance/password/DB |
| User privilege model | Single superuser-style app user per database | No read-only roles, no migration vs runtime user split in config |
| pgAdmin | IAM compose only | Separate credentials; dev convenience |

## MUST FIX FOR CAPABILITY READINESS (registration + documented reuse)

| Item | Gap |
|------|-----|
| Capability contract documenting security baseline | No `etp.postgresql` contract yet |
| Secret handling documented in contract `configuration` | Pattern exists in IAM external deps; not formalized for Postgres capability |
| Explicit “no credentials in registry” rule | Already practiced for `etp.iam`; must repeat for Postgres capability |
| Database-per-application isolation pattern documented | Partially in ETP-ARCHITECTURE.md; needs capability contract section |
| Dev default passwords | Document as **dev-only**; production must use secret manager |

## FUTURE ENHANCEMENT

| Item | Notes |
|------|-------|
| TLS for JDBC (`sslmode`) | Not in current URLs |
| Separate migration vs application DB users | Not configured |
| Network segmentation / private subnets | Deployment-specific |
| Automated secret rotation | No implementation |
| Central shared Postgres with RBAC | Would change provisioning model |

---

# 8. Operations

| Area | Current state | Notes |
|------|---------------|-------|
| **Health** | Platform Postgres: Docker healthcheck; consumers: Actuator + JDBC `isValid(2)` | IAM Postgres container has no compose healthcheck |
| **Readiness** | Consumer services gate readiness on DB connectivity | Postgres server itself not registered as a service with `/actuator/health` |
| **Connection pool** | HikariCP defaults | No custom `maximum-pool-size` in yaml/properties |
| **Startup order** | Documented for IAM (Postgres → Keycloak → IAM) | Platform: Postgres before platform service (implicit) |
| **Migrations** | Per-application Flyway on startup | No shared migration runner |
| **Backup/restore** | Docker volumes only (`etp_platform_pgdata`, `etp-postgres-data`) | No documented backup procedures in repo |
| **Monitoring** | Actuator `db` health on consumers | No Postgres-exporter / metrics |
| **Logging** | Spring / Postgres logs default | No centralized logging doc |
| **Failure handling** | Consumers fail startup if Flyway/validate fails | Expected for Spring Boot |

No numeric SLOs are defined in the repository.

---

# 9. ETP Capability Contract (conceptual — not registered)

Aligned with `CreateCapabilityRequest` and `capability-contract-base.schema.json` (same model as `etp.iam`).

## Registry payload (top level)

| Field | Proposed |
|-------|----------|
| `code` | `etp.postgresql` |
| `name` | `ETP PostgreSQL` |
| `category` | `DATA` (matches `CapabilityApiIntegrationTest` examples) |
| `version.version` | `1.0.0` (first semver) |
| `contract.contractType` | `COMPOSITE` |
| `contract.contractVersion` | `1.0` |
| `contract.contractFormat` | `JSON` |
| `implementation.code` | `etp-postgresql` |
| `implementation.name` | `ETP PostgreSQL 17` |
| `implementation.technology` | `PostgreSQL` |
| `implementation.technologyVersion` | `17` |
| `dependencies` | `[]` at registry level (no other ETP capability versions required) |

## `contract.document` sections (implemented concepts only)

### `functional`

- Provide PostgreSQL 17 as the **standard ETP relational datastore** for platform metadata and capability/application databases.
- Support **database-per-service/application** isolation.
- Support JDBC connectivity from Spring Boot services.
- **Does not** include: application schema design, business migrations, SQL query APIs, DBA administration APIs.

### `interfaces`

- **No REST API** in current implementation (infrastructure capability).
- Optional `location` could reference documentation path or operator runbook URL — not OpenAPI.
- Consumer integration: **JDBC URL + credentials via environment variables**.

### `security`

- Credentials via environment / secret manager — never in registry document.
- Recommended: dedicated database and application user per consumer.
- TLS: document as deployment responsibility (not configured in repo defaults).
- Network access: restrict Postgres port exposure in non-dev environments.

### `deployment`

- Docker image: `postgres:17`.
- Reference Compose service pattern (from existing compose files).
- Persistent volume for data directory.
- Optional `pg_isready` healthcheck (platform compose example).
- Default dev ports: `5434` (platform), `55432` (IAM) — document as **examples**, not hard requirements.
- Timezone: consumers set JDBC `TimeZone=UTC` (platform Hikari option; IAM URL option).

### `nfr`

- Descriptive only: consumers depend on Postgres availability for startup and readiness.
- Flyway validate mode requires DB reachable before app serves traffic.
- No fabricated latency/availability SLOs in v1.0.0.

### `operations`

- Startup: PostgreSQL before Flyway-enabled consumers.
- Health: `pg_isready` (container); JDBC validation in consumer services.
- Migrations: **consumer-owned** Flyway (not Postgres capability).
- Backup/restore: operator responsibility; document volume-based dev persistence only.
- Failure: consumer services report DOWN when DB unavailable.

### `compatibility`

- Capability version `1.0.0`; PostgreSQL major version 17.
- JDBC driver via Spring Boot dependency management.
- Explicitly **not** including: managed RDS automation, multi-region replication, pooler (PgBouncer) in v1.0.0.

### `configuration`

| Parameter (conceptual) | Purpose |
|------------------------|---------|
| `POSTGRES_DB` / database name | Per-deployment database name |
| `POSTGRES_USER` / JDBC username | Application user |
| `POSTGRES_PASSWORD` / JDBC password | Secret |
| `host`, `port` | JDBC URL components |
| JDBC URL template | `jdbc:postgresql://{host}:{port}/{database}?options=-c%20TimeZone%3DUTC` |

### `dependencies`

```json
{
  "capabilities": [],
  "external": [],
  "runtime": [
    { "name": "PostgreSQL", "version": "17" },
    { "name": "Docker", "role": "optional local provisioning" }
  ]
}
```

PostgreSQL has **no** dependency on `etp.iam` or other registered capabilities. Future consumers (platform, IAM, UAM) may list `etp.postgresql` in **application** composition metadata; registry `DependencyRequest` would only apply if another capability version must be registered first (policy decision — **not** technically required today).

---

# 10. Dependencies

## ETP capability dependencies (registry `dependencies[]`)

**None required** for PostgreSQL as infrastructure.

Optional future policy: register `etp.postgresql` before application capabilities that document it as a standard — still `capabilityVersionId` reference only when registering **other** capabilities, not on Postgres itself.

## External infrastructure

PostgreSQL **is** the external infrastructure for other capabilities. It does not depend on Keycloak, Kafka, etc.

## Consumers referencing PostgreSQL today (not registry dependencies)

| Consumer | Database | Documented as |
|----------|----------|---------------|
| `etp-platform-service` | `etp_platform` | Direct JDBC + Flyway |
| `etp-iam` | `etp_iam` | Direct JDBC + Flyway; contract `dependencies.external` |

After `etp.postgresql` registration, IAM contract could eventually reference registry dependency on `etp.postgresql` v1.0.0 — **optional documentation alignment**, not a runtime change.

---

# 11. Readiness Gap Analysis

| Area | Existing | Reusable | Missing | Required change | Priority |
|------|----------|----------|---------|-----------------|----------|
| Capability identity `etp.postgresql` | Test code name only | Pattern from `etp.iam` | Registry row | Author + register contract | **P1** |
| Capability contract | None | `capability-contract-base.schema.json` | Inline `contract.document` | Draft + validate JSON | **P1** |
| Implementation metadata | Docker image in compose | `postgres:17` in two composes | `ImplementationRequest` payload | Document image/version | **P1** |
| Version semver | — | — | `1.0.0` registration | Register first version | **P1** |
| Deployment packaging | Two separate compose Postgres services | Compose patterns | Unified **reference** compose fragment or doc | Publish canonical dev pattern (optional merge) | **P2** |
| Configuration contract | Env vars per service | `ETP_DB_*`, `ETP_IAM_DB_*` | Standard naming for UAM (`ETP_UAM_DB_*`?) | Document convention in capability contract | **P2** |
| Health (server) | Platform container healthcheck | `pg_isready` pattern | IAM Postgres healthcheck; registered capability ops section | Add compose healthcheck to IAM; document | **P2** |
| Readiness | Consumer JDBC checks | Actuator + custom indicator | Postgres-as-capability has no JVM readiness endpoint | Document consumer-side readiness only | **P2** |
| Security baseline | Env secrets; DB isolation | Per-service DBs | TLS, least-privilege users, prod secret policy | Document in contract; optional JDBC SSL later | **P2** |
| Isolation model | DB-per-service (2 instances) | Architecture guardrails | Automated provisioning for UAM DB | Application Initializer (future) | **P3** |
| Provisioning API | None | — | Create database/user for UAM | Future platform service | **P3** |
| Migrations | Per-app Flyway | Platform V1–14, IAM V1–V2 | UAM Flyway module | UAM implementation task | **UAM** |
| Observability | Consumer `db` health | — | Postgres metrics, backup runbooks | Future enhancement | **P3** |
| Documentation | README/ARCHITECTURE partial | ETP_CURRENT_DATA_MODEL | Dedicated Postgres capability deployment doc | This readiness doc + future deployment guide | **P1** |

---

# 12. Recommendation

1. **Does PostgreSQL already exist in ETP?**  
   **Yes** as **operational infrastructure** (two Docker Postgres 17 instances, JDBC consumers). **No** as a registered ETP capability (`etp.postgresql`).

2. **What exactly is reusable?**  
   Docker image choice (`postgres:17`), database-per-service pattern, Flyway-on-consumer pattern, env-driven JDBC config, HikariCP + JPA `validate`, UTC timezone option, Actuator DB health, platform compose healthcheck, Testcontainers test pattern.

3. **What needs to be built?**  
   **For capability registration:** contract JSON, implementation metadata, deployment/operations documentation, validation against schema, registry POST + lifecycle activation.  
   **Not required for v1.0.0:** new JVM service, SQL admin APIs, central provisioning API.

4. **Should we create an ETP PostgreSQL service/framework?**  
   **No standalone Spring Boot service** for v1.0.0. Optional future: shared library for datasource/Flyway conventions; optional future: provisioning in Application Initializer.

5. **What should UAM consume?**  
   **Pattern:** dedicated `etp_uam` database, JDBC env vars, Flyway migrations in UAM service, Spring Data JPA — same as IAM.  
   **Capability contract:** UAM application definition should **reference** `etp.postgresql` v1.0.0 when registered (composition metadata).  
   **Runtime:** UAM service connects directly to Postgres (no postgres broker today).

6. **What should remain infrastructure-specific?**  
   Postgres engine patching, backups, volume/storage, network placement, TLS certificates, RDS/operator tooling, pgAdmin in production.

7. **Minimum scope for `etp.postgresql` v1.0.0 capability**  
   - Register capability with inline contract (functional, deployment, security, configuration, operations, compatibility, dependencies).  
   - Implementation metadata: PostgreSQL 17 / Docker image reference.  
   - Reference deployment documentation (compose fragment, env vars, health).  
   - Empty registry `dependencies[]`.  
   - Lifecycle activation to **ACTIVE**.  
   - **No** new Java module required for minimum scope.

8. **Explicitly out of scope for v1.0.0**  
   - PostgreSQL REST administration API  
   - Automated database provisioning for applications  
   - Replacing per-service Flyway with central migration service  
   - Registering Keycloak's internal DB as ETP PostgreSQL  
   - Merging platform and IAM into one Postgres instance (could be ops choice but not mandated)  
   - Production backup/restore automation in repo  
   - PgBouncer / HA replication

---

# 13. Next Implementation Steps (for follow-on task)

Follow the **etp.iam** reference lifecycle:

| Step | Action |
|------|--------|
| 1 | Draft `etp.postgresql` contract JSON from §9 |
| 2 | Validate against `capability-contract-base.schema.json` |
| 3 | Create `etp-postgresql-capability-registration.json` |
| 4 | `POST /api/v1/capabilities` |
| 5 | `PATCH` version `DRAFT` → `VALIDATED` → `ACTIVE` |
| 6 | Optional: add `ETP_POSTGRESQL_CAPABILITY_DEPLOYMENT.md` under a new `capabilities/etp-postgresql/` or `docs/` folder |
| 7 | Optional: align IAM contract registry dependency to `etp.postgresql` in a **future** IAM version (not required for Postgres v1.0.0) |

---

# Source References

| Topic | Path |
|-------|------|
| Platform datasource / Flyway | `etp-platform-service/src/main/resources/application.yml` |
| IAM datasource / Flyway | `capabilities/etp-iam/src/main/resources/application.properties` |
| Platform Postgres compose | `etp-platform-service/docker-compose.yml` |
| IAM Postgres compose | `capabilities/etp-iam/docker-compose.yml` |
| Data model | `ETP_CURRENT_DATA_MODEL.md` |
| IAM onboarding reference | `capabilities/etp-iam/ETP_IAM_CAPABILITY_READINESS.md` |
| Registry model | `ETP_CAPABILITY_DEFINITION.md` |
| Contract schema | `etp-platform-service/src/main/resources/schemas/capability-contract-base.schema.json` |
| Architecture guardrails | `ETP-ARCHITECTURE.md` |

---

*Analysis-only document. No code, database, or Capability Registry changes performed.*
