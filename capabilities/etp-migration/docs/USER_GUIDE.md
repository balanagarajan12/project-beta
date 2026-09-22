# ETP Migration — User guide

Audience: migration/integration developers, operators, and local demo users.

**Implementation status:** Commands below match the current HTTP APIs and local defaults. Do not treat the demo PostgreSQL table as real UAM.

Related: [API_GUIDE.md](API_GUIDE.md), [ARCHITECTURE.md](ARCHITECTURE.md).

---

## 1. Prerequisites

- JDK 21
- Maven
- PostgreSQL 17 reachable at `localhost:55433` (container `etp-migration-postgres` from `capabilities/etp-migration/docker-compose.yml`)
- Database **`etp_migration`** (created by that compose service)
- Staging table created (Flyway does **not** create it)
- Source CSV under `etp.migration.storage.root`
- Optional: Keycloak on 8180 if you do **not** use Spring profile `dev`

---

## 2. Local environment setup

From `capabilities/etp-migration`, Spring loads `optional:file:.env[.properties]`. Typical keys (values are yours; do not commit secrets):

```properties
ETP_MIGRATION_DB_URL=jdbc:postgresql://localhost:55433/etp_migration?options=-c%20TimeZone%3DUTC
ETP_MIGRATION_DB_USERNAME=etp_admin
ETP_MIGRATION_DB_PASSWORD=<password>
ETP_MIGRATION_TARGET_POSTGRESQL_URL=jdbc:postgresql://localhost:55433/etp_migration?options=-c%20TimeZone%3DUTC
ETP_MIGRATION_TARGET_POSTGRESQL_USERNAME=etp_admin
ETP_MIGRATION_TARGET_POSTGRESQL_PASSWORD=<password>
ETP_MIGRATION_TARGET_POSTGRESQL_SCHEMA=migration_staging
ETP_MIGRATION_STORAGE_ROOT=./data/migration
ETP_MIGRATION_SERVER_PORT=8082
```

If `ETP_MIGRATION_TARGET_POSTGRESQL_URL` is blank, PostgreSQL COPY is not configured and BULK load fails.

---

## 3. Starting dependencies

```powershell
cd capabilities\etp-migration
docker compose up -d
```

Flyway V1+V2 apply on application start. IAM Postgres on **55432** is a different database and is not used by this capability’s local defaults.

Create staging (identifiers must match definition `configuration.target`):

```sql
CREATE SCHEMA IF NOT EXISTS migration_staging;
CREATE TABLE IF NOT EXISTS migration_staging.address_load (
  migration_run_id UUID NOT NULL,
  migration_batch_id BIGINT NOT NULL,
  source_record_number BIGINT NOT NULL,
  target_payload JSONB NOT NULL,
  idempotency_key VARCHAR(500),
  loaded_at TIMESTAMPTZ NOT NULL,
  UNIQUE (migration_run_id, migration_batch_id, source_record_number)
);
```

For promotion, create the **business** table named in `configuration.promotion` (schema, columns, unique keys). The local demo used schema **`uam_migration_test`** table **`address`**. That schema is a **simulated business target**, not the UAM product.

---

## 4. Starting etp-migration

```powershell
cd capabilities\etp-migration
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Profile `dev` permits `/api/v1/migrations/**`, `/migration-configurations/**`, `/mappings/**`, `/migration-runs/**` without JWT (see `DevMigrationApiSecurityConfig`). Without `dev`, send a Keycloak bearer token (`Authorization: Bearer <JWT>`). Keycloak/ETP IAM issues the token; this capability only validates it.

---

## 5. Health check

`GET http://localhost:8082/actuator/health` → `{"status":"UP"}` when the process and DB probe succeed. Details are not exposed (`show-details=never`).

---

## 6. Swagger

- UI: http://localhost:8082/swagger-ui/index.html  
- OpenAPI: http://localhost:8082/v3/api-docs  
- Configured path prefix: `springdoc.swagger-ui.path=/swagger-ui`

OpenAPI declares scheme **`bearer-jwt`** and a security requirement on operations. After **Authorize** with a Keycloak JWT, Execute sends `Authorization: Bearer <JWT>` (and generated curl includes that header). Health and `/v3/api-docs/**` remain public. No roles/scopes are defined in this module.

---

## 7. Creating a migration configuration

Preferred product path: **one** composite create (mapping + rules + DRAFT definition + source) in a single transaction.

`POST /api/v1/migration-configurations`

Does **not** activate, execute, reconcile, or promote.

Example (adjust names so they are unique):

```http
POST http://localhost:8082/api/v1/migration-configurations
Content-Type: application/json

{
  "definition": {
    "name": "demo-address-migration",
    "version": "1.0.0",
    "sourceType": "FILE_SYSTEM",
    "targetType": "POSTGRESQL",
    "targetEntity": "address",
    "batchSize": 3,
    "loadMode": "BULK",
    "configuration": {
      "target": {
        "schema": "migration_staging",
        "stagingTable": "address_load",
        "mergeStrategy": "NONE"
      },
      "promotion": {
        "enabled": true,
        "schema": "uam_migration_test",
        "table": "address",
        "strategy": "INSERT",
        "columns": {
          "external_id": "external_id",
          "address_line_1": "address_line_1",
          "city": "city"
        },
        "conflictKeys": ["external_id"]
      }
    }
  },
  "mapping": {
    "name": "demo-address-mapping",
    "version": "1.0.0",
    "sourceType": "FILE_SYSTEM",
    "targetType": "POSTGRESQL"
  },
  "rules": [
    {
      "sequenceNo": 1,
      "sourceField": "external_id",
      "targetField": "external_id",
      "transformationType": "DIRECT",
      "mandatory": true,
      "enabled": true
    },
    {
      "sequenceNo": 2,
      "sourceField": "address_line_1",
      "targetField": "address_line_1",
      "transformationType": "TRIM",
      "mandatory": true,
      "enabled": true
    },
    {
      "sequenceNo": 3,
      "sourceField": "city",
      "targetField": "city",
      "transformationType": "DIRECT",
      "mandatory": false,
      "enabled": true
    }
  ],
  "source": {
    "name": "demo-csv",
    "sourceType": "FILE_SYSTEM",
    "configuration": {
      "provider": "FILE_SYSTEM",
      "fileFormat": "CSV",
      "objectKey": "demo/addresses.csv",
      "hasHeader": true
    }
  }
}
```

**201** body: `CompositeMigrationConfigurationResponse` with `definition` (status **DRAFT**), `mapping`, `rules`, `sources`. Location header: `/api/v1/migrations/{definitionId}`.

Stepwise APIs still work: `POST /api/v1/mappings`, `POST /api/v1/mappings/{id}/rules`, `POST /api/v1/migrations`, `POST /api/v1/migrations/{id}/sources`.

---

## 8. Defining a source

If not using composite create:

`POST /api/v1/migrations/{migrationId}/sources`

`sourceType` is a free-form string. The documented demo value is **`FILE_SYSTEM`**. Connector selection uses **`configuration.provider` + `fileFormat` + `objectKey`**, not a Java enum of source types. Copy CSV to `{storage.root}/demo/addresses.csv` (default `data/migration/demo/addresses.csv`).

Repository sample file: `demo/data/addresses.csv` at the etp-foundation repo root (copy into the storage path above).

---

## 9. Defining mapping rules

`POST /api/v1/mappings/{mappingId}/rules`

Implemented types: DIRECT, TRIM, UPPERCASE, LOWERCASE, DEFAULT, CONCAT, SPLIT, REGEX, CONDITIONAL, DATE_FORMAT. LOOKUP and CUSTOM persist but **fail at process time**.

Mapping `status` may remain DRAFT; the worker uses enabled rules.

---

## 10. Activating a migration

`POST /api/v1/migrations/{migrationId}/activate`

DRAFT → ACTIVE after structural checks (batchSize, loadMode, mapping with ≥1 enabled rule, exactly one enabled source, connector `require()`, promotion JSON parse). Already ACTIVE returns 200 unchanged. Does **not** execute. ACTIVE is lifecycle/certification, not environment security.

Execute and `POST .../runs` **require ACTIVE** (409 if DRAFT).

---

## 11. Executing a migration

`POST /api/v1/migrations/{migrationId}/execute`  
Optional body `CreateMigrationRunRequest`: `idempotencyKey`, snapshots.

Synchronous (same request thread): create QUEUED run → prepare (inspect + batches) → process every eligible batch → **201** `MigrationRunResponse` with Location `/api/v1/migration-runs/{runId}`. There is no scheduler, Kafka trigger, or `@Async` worker.

Does **not** reconcile or promote.

Equivalent stepwise:

1. `POST /api/v1/migrations/{id}/runs`
2. `POST /api/v1/migration-runs/{runId}/prepare`
3. Repeat `POST /api/v1/migration-runs/{runId}/process-next-batch` until 409 (no eligible batch)

---

## 12. Checking the run

`GET /api/v1/migration-runs/{runId}`

Useful fields: `status`, `totalRecords`, `successRecords`, `validationFailures`, `completedBatches`, `failedBatches`.

`COMPLETED` / `COMPLETED_WITH_ERRORS` = staging finished. List batches: `GET /api/v1/migration-runs/{runId}/batches?page=0&size=20`.

---

## 13. Running reconciliation (staging)

After all batches are terminal:

`POST /api/v1/migration-runs/{runId}/reconcile`

Default scope is **STAGING**. Returns COUNT, CHECKSUM, TARGET, FULL. Checksum comparison to staging is **not** performed (`comparisonPerformed: false` → typically PASSED_WITH_WARNINGS). Repeat overwrites the same four rows. Does not change run or promotion status.

Read: `GET /api/v1/migration-runs/{runId}/reconciliation` (404 if never reconciled).

---

## 14. Preparing promotion

`POST /api/v1/migration-runs/{runId}/promotion/prepare`  
Optional JSON `{ "requestedBy": "operator" }`.

Requires PostgreSQL target, `promotion.enabled`, completed run, no active batches, existing reconciliation rows. Returns READY with eligible staging counts. Does not write the business table.

---

## 15. Checking promotion status

`GET /api/v1/migration-runs/{runId}/promotion`

Read-only. 404 if no promotion exists (does **not** create one). 404 if run missing.

---

## 16. Promoting

`POST /api/v1/migration-runs/{runId}/promote`

Requires READY promotion. Writes INSERT/UPSERT into `promotion.schema.table` using parameterized SQL. Each eligible batch is independently transactional. A failed batch does not roll back already promoted batches (`PARTIALLY_PROMOTED`). UPSERT is retry-safe when `conflictKeys` represent identity; INSERT is not inherently idempotent. Idempotent HTTP result if promotion is already PROMOTED.

---

## 17. Business-target reconciliation

`POST /api/v1/migration-runs/{runId}/reconcile?verificationScope=BUSINESS_TARGET`

PostgreSQL only. Expected rows are those from **successfully promoted** batches. Identity is `promotion.conflictKeys`; other mapped `promotion.columns` are compared in the database. Without `conflictKeys`, verification reports `MIGRATION_TARGET_IDENTITY_NOT_CONFIGURED`. This is **not** a UAM API call. Does not change run or promotion status.

---

## 18. Troubleshooting

| Symptom | Check |
|---------|--------|
| 400 unsupported source / no connector | `provider=FILE_SYSTEM`, `fileFormat=CSV`, `objectKey` present; file exists under storage root |
| 409 definition not ACTIVE | Activate first |
| COPY / datasource errors | Target URL set; staging table exists; identifiers match JSON |
| Promotion 409 PROMOTION_NOT_READY | Call prepare first |
| Promotion 409 PROMOTION_RECONCILIATION_REQUIRED | Staging reconcile first |
| GET promotion 404 | Prepare (or promote) has not created a row |
| CHECKSUM warnings | Expected for staging; comparison is not implemented |

---

## 19. Common errors

JSON error body (`ApiErrorResponse`): `timestamp`, `status`, `error`, `message`, `path`, optional `code`.

| HTTP | Typical cause |
|------|----------------|
| 400 | Validation, invalid source/promotion JSON, unsupported format/provider |
| 401/403 | JWT required (non-`dev`); unauthenticated WebMvc tests may see 403 |
| 404 | Unknown definition/run/mapping/source; no reconciliation; no promotion |
| 409 | Duplicate name/version; not ACTIVE; run not QUEUED for prepare; no batch; promotion preconditions |
| 500 | Inspection/target infrastructure failures |

Promotion `code` values include `MIGRATION_PROMOTION_*` constants in `MigrationErrorCodes`.

---

## 20. Operational sequence

1. Composite configuration (DRAFT)  
2. Activate  
3. Execute  
4. Staging reconcile  
5. Promotion prepare  
6. GET promotion (optional)  
7. Promote  
8. Business-target reconcile  

---

## Demo flow (local illustration only)

Repository CSV `demo/data/addresses.csv`: **8** source rows. **DEMO-004** has empty `address_line_1`. With mandatory TRIM on that field, expect **7** staged successes and **1** validation failure. With `batchSize` **3**, prepare creates **3** batches. Execute status: **`COMPLETED_WITH_ERRORS`**.

Staging table: `migration_staging.address_load`.  
Business-target stand-in: `uam_migration_test.address` (**not** production UAM).

Copy:

```powershell
New-Item -ItemType Directory -Force -Path capabilities\etp-migration\data\migration\demo | Out-Null
Copy-Item -Force demo\data\addresses.csv capabilities\etp-migration\data\migration\demo\addresses.csv
```

Then:

```text
POST /api/v1/migration-configurations
POST /api/v1/migrations/{id}/activate
POST /api/v1/migrations/{id}/execute
POST /api/v1/migration-runs/{runId}/reconcile
POST /api/v1/migration-runs/{runId}/promotion/prepare
GET  /api/v1/migration-runs/{runId}/promotion
POST /api/v1/migration-runs/{runId}/promote
POST /api/v1/migration-runs/{runId}/reconcile?verificationScope=BUSINESS_TARGET
```

`{id}` and `{runId}` are created by those calls. Do not hard-code workstation UUIDs.

GET promotion returns `ExecutePromotionResponse` (`promotionId`, `runId`, `status`, batch/record counters, `batches[]`). A fully successful demo promotion of seven staged rows is `PROMOTED` with `promotedRecords: 7`.

**`uam_migration_test.address` is a local simulated business target, not production UAM.**

---

## Tests vs demo database

`mvn clean test` uses a PostgreSQL **17** Testcontainer (database `etp_migration_test`, ephemeral host port). It must **not** insert rows into the local demo database `localhost:55433/etp_migration`. `@WebMvcTest` and plain unit tests do not start the container. Isolation is the container, not TRUNCATE/DELETE. See [ARCHITECTURE.md](ARCHITECTURE.md) §20.
