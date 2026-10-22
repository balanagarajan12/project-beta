# ETP Migration — User guide

Audience: migration/integration developers, operators, and local demo users.

**Implementation status:** Commands below match the current HTTP APIs. The current local end-to-end demonstration is recorded in [ARCHITECTURE.md](ARCHITECTURE.md) §28. The flat `uam_migration_test.address` example later in this guide is an older illustration. Neither is a production UAM deployment.

Related: [API_GUIDE.md](API_GUIDE.md), [ARCHITECTURE.md](ARCHITECTURE.md), [migration-e2e-test-guide.md](migration-e2e-test-guide.md).

---

## 1. Prerequisites

- JDK 21
- Maven
- PostgreSQL 17 for the control plane at `localhost:55433` (container `etp-migration-postgres` from `capabilities/etp-migration/docker-compose.yml`), database **`etp_migration`**
- For the local canonical demonstration, a second PostgreSQL database **`etp_migration`** at `localhost:55432` holding `migration_staging.uam_entity_stage` and schema `uam`. That second instance is a local arrangement, not a production UAM deployment.
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
ETP_MIGRATION_TARGET_POSTGRESQL_URL=jdbc:postgresql://localhost:55432/etp_migration?options=-c%20TimeZone%3DUTC
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

The repository contains Flyway V1–V11. Starting the application applies pending scripts to the database in `ETP_MIGRATION_DB_URL`. V4 and V5 are the control-plane identity tables. V11 allows `migration_error.stage` `PROMOTE`. This closure did not query `flyway_schema_history` on the workstation control plane, so it does not claim that database already has V11. The local canonical demonstration uses port **55432** as the target database for staging and schema `uam`. That is a workstation arrangement, not the production UAM deployment.

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

That unique key is for flat CSV staging. An `ENTITY_RELATIONSHIP` staging table should use `UNIQUE (migration_run_id, idempotency_key)` instead. The entity key is `entity-v1:` plus a SHA-256 of the run id, entity name, and source identity. Both tables are operator SQL. Flyway does not create them. See [DATABASE_MODEL.md](DATABASE_MODEL.md).

Flat promotion writes the business table named in `configuration.promotion`. The older local illustration used schema **`uam_migration_test`** table **`address`**. Canonical promotion (`target.mode=UAM`) writes the tables named in `configuration.tables`. The completed local demonstration used schema **`uam`** on port 55432. That schema is a local stand-in, not the UAM product. See [ARCHITECTURE.md](ARCHITECTURE.md) §28.

---

## 4. Starting etp-migration

```powershell
cd capabilities\etp-migration
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Profile `dev` permits `/api/v1/migrations/**`, `/migration-configurations/**`, `/mappings/**`, `/migration-runs/**` without JWT (see `DevMigrationApiSecurityConfig`). Without `dev`, send a Keycloak bearer token (`Authorization: Bearer <JWT>`). Keycloak/ETP IAM issues the token; this capability only validates it.

To restrict the API to tokens issued for this service, set `ETP_MIGRATION_JWT_AUDIENCES` (comma-separated, for example `etp-migration`). Tokens whose `aud` claim contains none of the values are rejected with 401. In Keycloak, add an Audience mapper to the calling client first; by default the access token's `aud` does not include custom clients. When the variable is unset, any valid token from the issuer is accepted.

---

## 5. Health check

`GET http://localhost:8082/actuator/health` → `{"status":"UP"}` when the process and DB probe succeed. Details are not exposed (`show-details=never`).

For orchestrators, use the probe groups. They are public and return only the status.

| Probe | URL | Checks |
|-------|-----|--------|
| Liveness | `/actuator/health/liveness` | The application's liveness state only. Stays `UP` (200) during a database outage, so the process is not restarted for it. |
| Readiness | `/actuator/health/readiness` | Readiness state and the control-plane database. Returns `DOWN` (503) when the control-plane database is unreachable; the answer can take up to the 10 s connection timeout, so set the probe timeout above that. The target PostgreSQL database is not part of readiness. |

### Shutdown

The application shuts down gracefully. After SIGTERM it stops accepting requests and gives running requests up to `ETP_MIGRATION_SHUTDOWN_GRACE_PERIOD` (default `30s`) to finish. Set your orchestrator's termination grace period longer than this value.

- `execute` finishes the batch in progress, claims no more batches, and returns the run with the remaining batches still pending. Continue with `process-next-batch`.
- Nothing resumes a run after a restart. Queued batches wait until a client calls `process-next-batch`.
- A batch cut off when the grace period expires stays `CLAIMED` or `PROCESSING`. It can be claimed again with `process-next-batch` after its lease expires, 5 minutes after the last claim or renewal.

### Database connection pools

| Variable | Default | Meaning |
|----------|---------|---------|
| `ETP_MIGRATION_DB_POOL_MAX_SIZE` | `10` | Control-plane pool size |
| `ETP_MIGRATION_DB_POOL_MIN_IDLE` | same as max size | Idle connections kept open |
| `ETP_MIGRATION_DB_POOL_CONNECTION_TIMEOUT_MS` | `10000` | Longest wait for a free connection before the request fails |
| `ETP_MIGRATION_DB_POOL_VALIDATION_TIMEOUT_MS` | `5000` | Longest connection liveness check |
| `ETP_MIGRATION_DB_POOL_IDLE_TIMEOUT_MS` | `600000` | Idle connection retirement; only applies when min idle is below max size |
| `ETP_MIGRATION_DB_POOL_MAX_LIFETIME_MS` | `1800000` | Connection retirement age |
| `ETP_MIGRATION_DB_POOL_KEEPALIVE_TIME_MS` | `120000` | Keepalive interval for idle connections |
| `ETP_MIGRATION_TARGET_POSTGRESQL_MAX_POOL_SIZE` | `4` | Target pool size |
| `ETP_MIGRATION_TARGET_POSTGRESQL_POOL_CONNECTION_TIMEOUT` | `10s` | Target pool connection wait |
| `ETP_MIGRATION_TARGET_POSTGRESQL_POOL_VALIDATION_TIMEOUT` | `5s` | Target pool connection check |
| `ETP_MIGRATION_TARGET_POSTGRESQL_POOL_IDLE_TIMEOUT` | `10m` | Target pool idle retirement |
| `ETP_MIGRATION_TARGET_POSTGRESQL_POOL_MAX_LIFETIME` | `30m` | Target pool connection retirement age |
| `ETP_MIGRATION_TARGET_POSTGRESQL_POOL_KEEPALIVE_TIME` | `2m` | Target pool keepalive interval |

Startup fails with a message naming the property if the values are inconsistent: a connection timeout of 0 or longer than 5 minutes, a validation timeout not shorter than the connection timeout, or an idle timeout or keepalive not shorter than the maximum lifetime. Keep `max-lifetime` shorter than any idle-connection limit enforced by the database, firewall, or proxy.

### Log correlation

Worker and retry log lines carry `run=<migrationRunId> batch=<migrationBatchId>` before the logger name, for example:

```text
... [nio-8082-exec-3] run=6f1c... batch=42 c.e.m.processing.MigrationBatchWorker : Batch processing failed runId=6f1c... batchId=42 attempt=1 errorCode=MIGRATION_SOURCE_READ_ERROR exceptionType=...
```

Search the logs for a run ID to follow its batches. The failure line gives the error code only; read the batch `errorMessage` or `GET /api/v1/migration-runs/{runId}/errors` for the detail. That response redacts secret-bearing messages, source keys, and nested details. It does not rewrite the stored row. A later successful retry does not delete the earlier error row. Reconciliation example source identities are redacted on `GET .../reconciliation` the same way. See [API_GUIDE.md](API_GUIDE.md).

---

## 6. Swagger

- UI: http://localhost:8082/swagger-ui/index.html
- OpenAPI: http://localhost:8082/v3/api-docs
- Configured path prefix: `springdoc.swagger-ui.path=/swagger-ui`

Swagger UI and the OpenAPI document are **off** for a packaged run. The `dev` profile turns them on, and so does `mvn spring-boot:run` (local process only). In any other launch, set `ETP_MIGRATION_OPENAPI_ENABLED=true` (property `etp.migration.openapi.enabled`). When they are on, `/v3/api-docs`, `/v3/api-docs.yaml`, `/swagger-ui`, `/swagger-ui/**`, and `/swagger-ui.html` are public. When they are off, those paths need a JWT and then return 404.

OpenAPI declares scheme **`bearer-jwt`** and a security requirement on operations. After **Authorize** with a Keycloak JWT, Execute sends `Authorization: Bearer <JWT>` (and generated curl includes that header). Health probes are always public. No roles/scopes are defined in this module.

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

The body above is the flat illustration. An entity-relationship body uses `entityMappings` or `mapping`, `fieldMappings` or `rules`, `modelType` `ENTITY_RELATIONSHIP`, and canonical `target.mode` `UAM`. `definition.configuration` contains `target` and `promotion` only. Tables, foreign keys, and load order come from the entity mapping. Column types and timestamps come from the target catalog. See [API_GUIDE.md](API_GUIDE.md) and [ARCHITECTURE.md](ARCHITECTURE.md) §29. The procedure for that path is [migration-e2e-test-guide.md](migration-e2e-test-guide.md).

Stepwise APIs still work: `POST /api/v1/mappings`, `POST /api/v1/mappings/{id}/rules`, `POST /api/v1/migrations`, `POST /api/v1/migrations/{id}/sources`.

---

## 8. Defining a source

CSV header rules apply to both the filesystem and SFTP sources:

- A UTF-8 byte-order mark at the start of the file is ignored. The first column is named `id`, not `\uFEFFid`.
- Header names must be unique. A repeated name fails `prepare` with HTTP 400 and `CSV header contains duplicate column name '<name>'`.
- Comparison is exact. It is case-sensitive and does not trim spaces, so `id`, `ID` and ` id` are three different columns. Mapping rules must use the same spelling as the header.

If not using composite create:

`POST /api/v1/migrations/{migrationId}/sources`

`sourceType` is a free-form string. The documented filesystem value is **`FILE_SYSTEM`**. Connector selection uses **`configuration.provider`** and **`fileFormat`**.

Filesystem CSV: copy the file under `{storage.root}` and set `provider` to `FILE_SYSTEM`, `fileFormat` to `CSV`, and `objectKey` to the path relative to that root. Default root is `data/migration`. Repository sample file: `demo/data/addresses.csv` at the etp-foundation repo root.

SFTP CSV uses the same parsing and the same migration pipeline. The source JSON names the server and the remote file. It does not contain the password.

```json
{
  "name": "sftp-source",
  "sourceType": "FILE",
  "configuration": {
    "provider": "SFTP",
    "fileFormat": "CSV",
    "host": "sftp.example",
    "port": 22,
    "credentialRef": "local-sftp",
    "remotePath": "/inbound/rows.csv",
    "hasHeader": true,
    "delimiter": ","
  }
}
```

Set the login outside the migration definition:

```text
etp.migration.source.sftp.credentials.local-sftp.username
etp.migration.source.sftp.credentials.local-sftp.password
```

The SFTP server's host key must be trusted before any file is read. Configure one of these:

```text
# OpenSSH known_hosts file. Read only; the application never writes to it.
etp.migration.source.sftp.known-hosts-file=/etc/etp-migration/known_hosts

# Or pin one or more SHA256 fingerprints for the connection profile.
etp.migration.source.sftp.credentials.local-sftp.host-key-fingerprints=SHA256:<base64>
```

For a non-standard port the known_hosts entry is `[host]:port <key-type> <base64-key>`, which is the form `ssh-keyscan -p <port> <host>` prints. Get a fingerprint with `ssh-keygen -lf <file>` and confirm it with the SFTP administrator over a separate channel. When fingerprints are pinned for a `credentialRef`, they decide and the known_hosts file is not consulted for that connection.

| Situation | Result |
|-----------|--------|
| Key matches a pin or a known_hosts entry | Connects |
| Host has no trusted entry | Rejected with `MIGRATION_SFTP_HOST_KEY_REJECTED` |
| Key differs from the known_hosts entry or the pins | Rejected with `MIGRATION_SFTP_HOST_KEY_REJECTED` |
| Nothing configured, known_hosts file missing or unparseable, or a pin that is not `SHA256:<base64>` | Rejected with `MIGRATION_SFTP_HOST_KEY_CONFIGURATION_INVALID` before connecting |

The rejection message names the endpoint, the presented fingerprint and the property to set. It does not include the known_hosts path, passwords or keys; the path is in the application log.

`etp.migration.source.sftp.accept-unknown-host-keys` defaults to false. Setting it to true admits hosts that have no trusted entry and logs a warning on startup and on every such connection. A key that contradicts a known_hosts entry or a pin is still rejected. Use it only for a local development server.

SFTP timeouts:

| Property | Default | What it bounds |
|----------|---------|----------------|
| `etp.migration.source.sftp.connect-timeout` | `15s` | TCP connect and password authentication |
| `etp.migration.source.sftp.idle-timeout` | `10m` | The wait for each SFTP response (stat, open, every read chunk), and how long the SSH session may carry no traffic before it is closed |

`idle-timeout` must be positive; `0s` or a negative value stops the application at startup. It does not limit the total transfer time. A large file that keeps arriving runs to completion. A server that stops answering fails the read with `MIGRATION_SFTP_READ_FAILED` and the message `SFTP read timed out waiting for the server (etp.migration.source.sftp.idle-timeout)`. A flat batch that stops reading for longer than the idle timeout loses the session and fails the same way. An entity-relationship batch copies the remote file once and then reads the local copy, so a slow target load does not hold the SFTP session open. Raise the value rather than disabling it.

Create the source through the API, activate, execute, reconcile, prepare promotion, and promote. SFTP does not need a database edit. The remote file is streamed. It is not loaded into memory. The entity-relationship pipeline keeps one local copy and a row-offset index under `etp-source-cache` inside `etp.migration.storage.root`. That directory is safe to delete when no migration is running; the next run downloads the file again. `layoutType=MULTI_FILE` remains a filesystem layout.

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

The call commits a QUEUED run and returns **202 Accepted** with `runId`, `status` `QUEUED`, `message` `Migration execution accepted.`, and `statusUrl`. Preparation and batch processing continue in the background. Poll `GET /api/v1/migration-runs/{runId}` until `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, or `CANCELLED`. The 202 body is acceptance, not completion.

One background run is the default (`etp.migration.execution.max-concurrent-runs=1`). Up to 32 accepted runs wait (`etp.migration.execution.queue-capacity`). A full queue keeps the run QUEUED and a recovery pass every 15 seconds tries again. After a restart, runs accepted by execute resume when their lease is empty or expired (`etp.migration.execution.lease-duration`, default 5 minutes). A run created only by `POST .../runs` is not resumed.

Does **not** reconcile or promote.

Equivalent stepwise:

1. `POST /api/v1/migrations/{id}/runs`
2. `POST /api/v1/migration-runs/{runId}/prepare`
3. Repeat `POST /api/v1/migration-runs/{runId}/process-next-batch` until 409 (no eligible batch)

---

## 12. Checking the run

`GET /api/v1/migration-runs/{runId}`

Useful fields: `status`, `totalRecords`, `successRecords`, `validationFailures`, `completedBatches`, `failedBatches`. For an entity-relationship run, `totalRecords` is the number of logical work items. One CSV row can produce several. `source.sourceRowCount` on the run snapshot is the physical data-row count when the source is one positioned CSV.

`COMPLETED` / `COMPLETED_WITH_ERRORS` = staging finished. List batches: `GET /api/v1/migration-runs/{runId}/batches?page=0&size=20`.

---

## 13. Running reconciliation (staging)

After all batches are terminal:

`POST /api/v1/migration-runs/{runId}/reconcile`

Returns HTTP 202 with a job id. Default scope is **STAGING**. The checks then store COUNT, CHECKSUM, TARGET, and FULL. Checksum comparison to staging is **not** performed (`comparisonPerformed: false` → typically PASSED_WITH_WARNINGS). Repeat after the job finishes overwrites the same four rows. Does not change run or promotion status. A finished reconciliation does not prepare promotion.

Read: `GET /api/v1/migration-runs/{runId}/reconciliation` (404 if never submitted). `jobStatus` is the background job. `status` is the reconciliation outcome. `GET /api/v1/migration-runs/{runId}/phase-jobs/{jobId}` reads one job.

---

## 14. Preparing promotion

`POST /api/v1/migration-runs/{runId}/promotion/prepare`
The body is optional. A `requestedBy` field is still accepted but ignored: the promotion records the authenticated caller (see "Audit identity" in [ARCHITECTURE.md](ARCHITECTURE.md) §15).

Returns HTTP 202. Requires PostgreSQL target, `promotion.enabled`, completed run, no active batches, existing reconciliation rows. When the job completes, GET promotion shows READY and the eligible staging counts. Does not write the business table. A queued or running preparation is not started twice. READY and PROMOTED promotions are not replaced.

---

## 15. Checking promotion status

`GET /api/v1/migration-runs/{runId}/promotion`

Read-only. Includes `jobId` and `jobStatus` when a preparation or promotion job exists. 404 if no promotion exists (does **not** create one), including while preparation is still queued. Use the phase-job GET until the promotion row exists. 404 if run missing.

---

## 16. Promoting

`POST /api/v1/migration-runs/{runId}/promote`

Returns HTTP 202. Requires a READY promotion and does not prepare one. When `target.mode` is `UAM`, promotion allocates mapping-level target identities and loads the configured canonical tables in one transaction per eligible batch. A failure rolls that UAM transaction back. Identity allocations remain for a retry. An already `PROMOTED` promotion is not loaded again. Flat mode, used when canonical mode is not selected, writes INSERT/UPSERT into `promotion.schema.table`. A failed flat batch does not roll back batches already committed (`PARTIALLY_PROMOTED`). Poll GET promotion for the result. A second call while the job is queued or running returns that job.

---

## 17. Business-target reconciliation

`POST /api/v1/migration-runs/{runId}/reconcile?verificationScope=BUSINESS_TARGET`

PostgreSQL only. Expected rows are those from **successfully promoted** batches. Identity is `promotion.conflictKeys`; other mapped `promotion.columns` are compared in the database. Without `conflictKeys`, verification reports `MIGRATION_TARGET_IDENTITY_NOT_CONFIGURED`. This is **not** a UAM API call. Does not change run or promotion status.

---

## 18. Troubleshooting

| Symptom | Check |
|---------|--------|
| 400 unsupported source / no connector | Filesystem: `provider=FILE_SYSTEM`, `fileFormat=CSV`, `objectKey` present. SFTP: `provider=SFTP`, `fileFormat=CSV`, `host`, `port`, `credentialRef`, `remotePath`. Other providers remain unsupported |
| 409 definition not ACTIVE | Activate first |
| COPY / datasource errors | Target URL set; staging table exists; identifiers match JSON |
| Promotion 409 PROMOTION_NOT_READY | Call prepare first |
| Retry 409 RETRY_BLOCKED_BY_PROMOTION | The run is being promoted or was promoted. Start a new run instead of retrying |
| Promotion 409 PROMOTION_RECONCILIATION_REQUIRED | Staging reconcile first |
| GET promotion 404 | Prepare (or promote) has not created a row |
| CHECKSUM warnings | Expected for staging; comparison is not implemented |
| Readiness 503, liveness 200 | Control-plane database unreachable; check `ETP_MIGRATION_DB_URL` and the database. The process does not need a restart |
| Batch stuck in `PROCESSING` after a restart | Interrupted during shutdown. Wait for the 5-minute lease to expire, then `process-next-batch` |
| Startup fails naming a `pool` or `hikari` property | Inconsistent pool timeouts; see §5 Database connection pools |

---

## 19. Common errors

JSON error body (`ApiErrorResponse`): `timestamp`, `status`, `error`, `message`, `path`, optional `code`.

| HTTP | Typical cause |
|------|----------------|
| 400 | Validation, invalid source/promotion JSON, unsupported format/provider |
| 401/403 | JWT required (non-`dev`); unauthenticated WebMvc tests may see 403. 403 `MIGRATION_AUDIT_IDENTITY_INVALID`: the token has no `sub` or client ID, or its identity is too long or reserved |
| 404 | Unknown definition/run/mapping/source; no reconciliation; no promotion |
| 409 | Duplicate name/version; not ACTIVE; run not QUEUED for prepare; no batch; promotion preconditions |
| 500 | Inspection/target infrastructure failures. Target failures return `Target system operation failed (reference <uuid>)` with the specific `code`. Search the application log for `reference=<uuid>` to find the sanitized cause: SQLState, table, column and constraint. Stored batch and promotion-batch error messages use the same sanitized form. |

Promotion `code` values include `MIGRATION_PROMOTION_*` constants in `MigrationErrorCodes`. `PARTIALLY_PROMOTED` uses `MIGRATION_PROMOTION_PARTIAL_FAILURE`. `PROMOTION_FAILED` after one or more failed batches uses `MIGRATION_PROMOTION_BATCH_FAILED`. A clean `PROMOTED` promotion clears that summary. The phase job can still be `COMPLETED`. Failed promotion batches stay failed until a new attempt is started; historical `migration_error` rows stay.

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

## Current local end-to-end demonstration

Status: historical workstation record. Authoritative write-up: [ARCHITECTURE.md](ARCHITECTURE.md) §28. The current integration test of the ten-sub-address promotion failed on 2026-10-17. See [migration-e2e-test-guide.md](migration-e2e-test-guide.md).

Definition `migrate_geographicAddress_test01` version **1.2** (`59fc4cc1-85cf-44bd-870b-dd56ae3ca1ae`) is ACTIVE. Run `f9e9fd4f-cc61-4d0f-8e71-e3333f09b431` (run number 2) completed with 515 logical entity records from 110 physical CSV rows. Promotion `2994bce6-6033-4b9a-94ba-f5b2c3e1a561` is `PROMOTED` with 515 records. Staging reconciliation is `PASSED_WITH_WARNINGS` because checksum comparison is unavailable. That warning is not a failed migration, and it checks staging rather than the UAM tables. Local UAM row counts are `location` 202, `geographic_address` 101, `geographic_location` 101, `location_geometry` 101, and `geographic_sub_address` 10.

## Historical flat CSV illustration

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
GET  /api/v1/migration-runs/{runId}
POST /api/v1/migration-runs/{runId}/reconcile
POST /api/v1/migration-runs/{runId}/promotion/prepare
GET  /api/v1/migration-runs/{runId}/promotion
POST /api/v1/migration-runs/{runId}/promote
POST /api/v1/migration-runs/{runId}/reconcile?verificationScope=BUSINESS_TARGET
```

`{id}` and `{runId}` are created by those calls. Do not hard-code workstation UUIDs.

GET promotion returns `ExecutePromotionResponse` (`promotionId`, `runId`, `status`, batch/record counters, `batchCounts`). A fully successful demo promotion of seven staged rows is `PROMOTED` with `promotedRecords: 7`. Individual batches are on `GET /api/v1/migration-runs/{runId}/promotion/batches`.

**`uam_migration_test.address` is a local simulated business target, not production UAM.**

---

## Tests vs demo database

`mvn clean test` uses a PostgreSQL **17** Testcontainer (database `etp_migration_test`, ephemeral host port). It must **not** insert rows into the local demo database `localhost:55433/etp_migration`. `@WebMvcTest` and plain unit tests do not start the container. Isolation is the container, not TRUNCATE/DELETE. See [ARCHITECTURE.md](ARCHITECTURE.md) §20.
