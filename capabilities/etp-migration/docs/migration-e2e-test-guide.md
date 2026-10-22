# ETP Migration — End-to-end test guide

This guide is the procedure for checking the current `etp-migration` engine before a manual end-to-end run. It documents the implementation that is in the repository. It does not add requirements.

Related: [ARCHITECTURE.md](ARCHITECTURE.md) §4, §8, §12, §25, §28, and §29, [API_GUIDE.md](API_GUIDE.md), [USER_GUIDE.md](USER_GUIDE.md), [DATABASE_MODEL.md](DATABASE_MODEL.md).

---

## 1. What the path is

```text
Source connector
  → PostgreSQL staging (COPY)
  → validation and transformation (already applied before COPY)
  → staging reconciliation (separate POST)
  → promotion preparation
  → identity allocation
  → canonical PostgreSQL load
```

`PostgreSqlTargetConnector` writes staging. `UamPostgreSqlTargetConnector` writes the business tables later, when promotion runs and `target.mode` is `UAM`. A completed run means staging finished. Enrichment and deduplication are not implemented. Staging reconciliation does not verify the UAM tables. Checksum comparison is not performed.

---

## 2. Prerequisites

| Item | Value in this repository |
|------|--------------------------|
| Java | 21 (`pom.xml` `java.version`) |
| Spring Boot | 4.1.0 |
| Maven | No wrapper and no pinned Maven version. Use a Maven release that can build the Spring Boot 4.1.0 parent. |
| Automated database | Testcontainers 1.21.4, image `postgres:17`. The 2026-10-17 run reported PostgreSQL **17.10**. Database name `etp_migration_test`. Docker is required. |
| Local control plane | `capabilities/etp-migration/docker-compose.yml` starts `postgres:17` as `etp-migration-postgres`, host port **55433**, database `etp_migration`. `ETP_MIGRATION_DB_PASSWORD` is required. The repository contains Flyway V1–V11. Startup applies pending versions. This guide does not claim the workstation database already has V11. |
| Local target | Not created by that compose file. The workstation notes use `localhost:55432`, database `etp_migration`, staging `migration_staging.uam_entity_stage`, schema `uam`. That split is a local arrangement. |
| Storage | `etp.migration.storage.root`, default `./data/migration` |
| HTTP | `ETP_MIGRATION_SERVER_PORT` default **8082** |
| Auth | Profile `dev` permits the migration APIs without a JWT. Otherwise send `Authorization: Bearer <JWT>`. Do not put SFTP passwords, private keys, or passphrases in the migration JSON. |

Automated tests must not write to `localhost:55433` or `localhost:55432`. They override the datasource with the Testcontainers URL.

---

## 3. Scenario

The verified UAM stand-in is the schema created in `UamCanonicalLoadIntegrationTest.createCanonicalTables`. Entities and tables:

| Entity | Table | Notes |
|--------|-------|-------|
| AddressLocation | `location` | Shares the table with GeographicPointLocation |
| GeographicPointLocation | `location` | |
| GeographicAddress | `geographic_address` | |
| GeographicLocation | `geographic_location` | |
| LocationGeometry | `location_geometry` | `x`, `y`, `z` are `numeric`. `raw_geojson` is `jsonb` |
| GeographicSubAddress | `geographic_sub_address` | |

There is no `external_identifier` entity, table, or test in this module. Do not add one to the scenario.

Relationships configured by `promotionConfiguration()`:

| Child column | Parent |
|--------------|--------|
| `geographic_address.location_id` | AddressLocation / `location.id` (`NOT NULL`) |
| `geographic_address.geographic_location_id` | GeographicLocation (`nullable`) |
| `geographic_location.location_id` | GeographicPointLocation / `location.id` (`NOT NULL`) |
| `location_geometry.location_id` | GeographicLocation (`NOT NULL`) |
| `geographic_sub_address.address_id` | GeographicAddress (`NOT NULL`) |
| `geographic_sub_address.geographic_location_id` | GeographicLocation (`nullable`) |

`private_street_name` and `private_street_number` are nullable `varchar` columns with no database default. The fixture's field mappings use `DEFAULT` with `defaultValue` `""`. Blank text is bound as SQL `NULL` and stored.

CSV header used by the fixture:

```text
address_id,geo_id,address_name,street_number,street_name,street_type,city,state,postcode,country,country_code,latitude,longitude
```

The sub-address file adds `sub_address_id,sub_unit_type,sub_unit_number,sub_unit_name,level_type,level_number,building_name`. Place the file under `etp.migration.storage.root` and set `objectKey` to that relative path. `provider` is `FILE_SYSTEM`, `fileFormat` is `CSV`.

SFTP, when used instead, is one remote CSV: `provider` `SFTP`, `host`, `port`, `credentialRef`, `remotePath`, `hasHeader`, `delimiter`. The password is `etp.migration.source.sftp.credentials.<credentialRef>.password` in application configuration.

The SFTP host key must be trusted before the run can prepare. Before an SFTP scenario:

1. Capture the key: `ssh-keyscan -p <port> <host> > known_hosts`, then `ssh-keygen -lf known_hosts` and compare the SHA256 fingerprint with the one the SFTP administrator gives you.
2. Set `etp.migration.source.sftp.known-hosts-file=<path to known_hosts>` or `etp.migration.source.sftp.credentials.<credentialRef>.host-key-fingerprints=SHA256:<base64>`. Keep `accept-unknown-host-keys` false.
3. Restart the application.

Host-key checks to run with SFTP:

| Check | Setup | Expected |
|-------|-------|----------|
| Trusted key | known_hosts or pin matches the server | `prepare` returns 200 and batches are planned |
| Unknown host | known_hosts has no entry for `[host]:port`, accept-unknown false | `prepare` returns 400, `code` `MIGRATION_SFTP_HOST_KEY_REJECTED`, message contains the presented `SHA256:` fingerprint |
| Changed key | known_hosts entry for `[host]:port` holds a different key | 400, `MIGRATION_SFTP_HOST_KEY_REJECTED`, message says the key changed; the known_hosts file is unchanged |
| Pin mismatch | pin a different fingerprint | 400, `MIGRATION_SFTP_HOST_KEY_REJECTED` |
| No trust configured | neither property set, accept-unknown false | 400, `MIGRATION_SFTP_HOST_KEY_CONFIGURATION_INVALID`; the message names both properties |
| Missing known_hosts file | point `known-hosts-file` at a path that does not exist | 400, `MIGRATION_SFTP_HOST_KEY_CONFIGURATION_INVALID`; the response does not contain the path |

In every case the response and the application log must not contain the SFTP password.

`etp.migration.source.sftp.idle-timeout` (default `10m`) bounds the wait for each SFTP response and SSH session inactivity. It does not bound the total transfer time. Keep the default for the core scenarios.

CSV header checks (filesystem or SFTP):

| Check | File | Expected |
|-------|------|----------|
| UTF-8 BOM | Save the fixture as "UTF-8 with BOM" | `prepare` returns 200; the first column in the run's inspected columns has no leading `\uFEFF`; mapped values for the first column are populated, not null |
| Duplicate header | Repeat one header name exactly, for example `address_id,...,address_id` | `prepare` returns 400 with `CSV header contains duplicate column name 'address_id'`; no batches are planned |
| Case difference | Headers `code` and `CODE` | Accepted as two columns |

A payload example is in [API_GUIDE.md](API_GUIDE.md). The full six-entity body is `UamCanonicalLoadIntegrationTest.configuration`.

---

## 4. Automated execution

From `capabilities/etp-migration`, PowerShell requires quotes around `-Dtest` because the comma is an argument separator:

```powershell
mvn -q "-Dtest=UamTargetColumnsTest,UamCanonicalRowsTest,UamCanonicalLoadIntegrationTest" test
```

Docker must be running. The command starts `postgres:17`, applies the Flyway versions on the classpath (V1–V11) to `etp_migration_test`, creates schema `uam` and `migration_staging.uam_entity_stage` in `@BeforeEach`, and drops `uam` with `DROP SCHEMA IF EXISTS uam CASCADE` at the start of each test. It does not clean the developer databases.

### Result recorded on 2026-10-17

After `private_street_name` and `private_street_number` were made nullable, this command was run:

```powershell
mvn -q "-Dtest=UamCanonicalLoadIntegrationTest" test
```

Database: PostgreSQL **17.10**, Testcontainers image `postgres:17`, database `etp_migration_test`.  
`UamCanonicalLoadIntegrationTest`: **9 tests, 0 failures, 0 errors, 0 skipped.** Maven exit code 0. `repeatedParentsPromoteTenGeographicSubAddresses` passed and asserted both private-street columns are SQL `NULL`.

An earlier run the same day, before that schema change, used the three-class command below and failed that one method. `UamTargetColumnsTest` (9) and `UamCanonicalRowsTest` (4) passed in that earlier run and were not repeated after the schema change.

`UamTargetColumnsTest` covers catalog conversion, generated and defaulted omission, blank required values, invalid numeric messages, per-row defaults, identity and serial keep-or-omit, `GENERATED ALWAYS AS IDENTITY` rejection, insert-key omission, and aliases (`int8`, `float8`, `bpchar`, `date`, `time`, `timetz`, `timestamptz`, `bool`, JSON object).

`UamCanonicalRowsTest` covers configured parent identity, `referencesColumn` (not assumed to be the parent primary key), derived load order, and preservation of a mapped `created_at`.

`UamCanonicalLoadIntegrationTest` methods:

| Method | This run |
|--------|----------|
| `oneAddressIsStagedWithoutTargetIdentities` | passed |
| `oneHundredAddressesStageFiveHundredEntitiesWithoutTargetIdentities` | passed |
| `oneHundredAddressesPromoteIntoCanonicalTables` | passed |
| `repeatedParentsPromoteTenGeographicSubAddresses` | passed |
| `failedPromotionReusesTheSameTargetIdentities` | passed |
| `retryDoesNotDuplicateCanonicalRows` | passed |
| `failedBatchRollsBackAndRetryInsertsOnce` | passed |
| `catalogConvertsSchemaTypesAndAppliesDefaultsPerRow` | passed |
| `stagingLoadDoesNotWriteCanonicalTables` | passed |

`catalogConvertsSchemaTypesAndAppliesDefaultsPerRow` checks the UAM catalog types, per-row defaults (`PLANNED` / `1.0` beside `ACTIVE` / `2.0`), source timestamp `2020-01-02T03:04:05Z`, a blank `created_at` filled by the required-timestamp rule, parent ids on `geographic_location.location_id` and `location_geometry.location_id`, numeric and JSON conversion from the catalog, an invalid numeric naming column `x`, and a probe table for `bpchar`, `int4`, `int8`, `float8`, `bool`, `uuid`, `date`, `timestamp`, `json`, `serial`, identity `BY DEFAULT`, identity `ALWAYS`, and a stored generated column. The probe table is not part of the UAM schema.

The full Surefire suite was not run for this documentation update. Do not treat an older 542-test figure as this run.

---

## 5. Manual procedure

Use an isolated target schema. Do not point this procedure at a shared database and then drop it.

1. **Database.** Start the control-plane container (`docker compose up -d` from `capabilities/etp-migration`) only after `ETP_MIGRATION_DB_PASSWORD` is set. Create the target staging table and `uam` tables from `UamCanonicalLoadIntegrationTest.createCanonicalTables`, or an equivalent isolated schema. Flyway does not create them.
2. **Confirm constraints.** `location.created_at` is `timestamptz NOT NULL`. `location.status` defaults to `PLANNED`. `location.version` defaults to `1.0`. `location_geometry.x` and `y` are `numeric`. Foreign keys are the columns in §3. `private_street_name` and `private_street_number` are nullable and have no default.
3. **Source.** Copy the CSV under `etp.migration.storage.root`. For SFTP, configure `credentialRef` outside the definition.
4. **Configuration.** `POST /api/v1/migration-configurations` with the body shape in [API_GUIDE.md](API_GUIDE.md). `targetType` on the definition stays `POSTGRESQL`. Mapping `targetType` in the fixture is `UAM_POSTGRESQL`. `definition.configuration` contains `target` and `promotion` only. `promotion.enabled` is true. The live load converts from `information_schema`.
5. **Activate and execute.** `POST /api/v1/migrations/{id}/activate`, then `POST /api/v1/migrations/{id}/execute` with `{}`. The response is **202** and a QUEUED `runId`. Poll `GET /api/v1/migration-runs/{runId}` until the status is terminal. The stepwise equivalent is `POST /api/v1/migrations/{id}/runs`, `POST /api/v1/migration-runs/{runId}/prepare`, then `POST /api/v1/migration-runs/{runId}/process-next-batch` until 409. A stepwise run is not started by execute recovery.
6. **Staging.** `GET /api/v1/migration-runs/{runId}` is `COMPLETED` when every entity validates. Query `migration_staging.uam_entity_stage`. `targetIdentity` and `parentTargetIdentity` are null. One hundred address rows stage 500 entity rows and zero sub-addresses. The mixed file stages 515 entity rows. Canonical tables are still empty.
7. **Validation and transformation.** Mandatory rules reject a blank target field before COPY. The flat illustration `DEMO-004` is that behavior on `address_line_1`. Entity rules in the UAM fixture are listed in `configuration()`. LOOKUP and CUSTOM fail at process time. There is no enrichment or deduplication step to verify.
8. **Relationships and order.** Parents are ordered before children from the relationship graph: AddressLocation and GeographicPointLocation before GeographicLocation, then GeographicAddress, LocationGeometry, and GeographicSubAddress. Independent names sort by name. A cycle is rejected. `targetForeignKey.referencesColumn` copies the named parent column and is checked against `information_schema` when a constraint already exists.
9. **Promote.** `POST /api/v1/migration-runs/{runId}/reconcile`, then `POST /api/v1/migration-runs/{runId}/promotion/prepare`, then `POST /api/v1/migration-runs/{runId}/promote`. Each returns **202**. Poll `GET /api/v1/migration-runs/{runId}/phase-jobs/{jobId}` until the job is `COMPLETED`, then read the business GET. For the 100-address file the passing test expects location 200, geographic_address 100, geographic_location 100, location_geometry 100, geographic_sub_address 0, and the foreign-key patterns in `oneHundredAddressesPromoteIntoCanonicalTables`. Preparation reads batch ids, statuses, and one grouped staging count. It does not load `migration_batch.source_locator`. `PromotionPreparationIntegrationTest.largeSourceLocatorIsNotLoadedDuringPreparation` keeps a 2 MB locator out of the Hibernate session and still marks the batch eligible. A failed prepare leaves no promotion row; retry the same POST after the job is `FAILED`. Do not reset the run. A `READY` or `PROMOTED` promotion is returned as stored.
10. **Defaults, timestamps, types.** A blank `location.status` becomes `PLANNED` and a blank `version` becomes `1.0` when those columns are omitted. An explicit `ACTIVE` stays `ACTIVE`. A required `location.created_at` with no mapped value receives one UTC timestamp for the batch. A source value `2020-01-02T03:04:05Z` is stored. Compare it with `AT TIME ZONE 'UTC'` because `timestamptz` display follows the session time zone. The catalog converts `numeric` and `jsonb`.
11. **Invalid records.** An invalid numeric such as `x = north` fails before insert with a message that names column `x` and type `numeric`. The canonical transaction rolls back. Staging rows remain.
12. **Retry.** `failedBatchRollsBackAndRetryInsertsOnce` shows a check-constraint failure leaving zero rows, then a later load inserting once. A second load of the same keys does not increase the count (`ON CONFLICT` on the payload `targetKey`, which the fixtures set to `id`). Identity allocations are kept across the target rollback. `POST /api/v1/migration-runs/{runId}/retry` retries execution batches. It is not the promotion retry. A promotion that is already `PROMOTED` returns the stored result. After promote, `POST .../retry` returns 409 `MIGRATION_RETRY_BLOCKED_BY_PROMOTION` and the run status does not change.
13. **Counts.** Use run counters, `GET /api/v1/migration-runs/{runId}/reconciliation`, promotion counters, and SQL counts. Staging reconciliation can be `PASSED_WITH_WARNINGS` because checksum comparison is unavailable. `extractedEntityCount` is `unavailable`. `verificationScope=BUSINESS_TARGET` compares the flat promotion contract (`conflictKeys` and promoted batches). It is not a canonical UAM checksum. Do not treat a missing business-target total as zero.
14. **Operational checks (optional).** Use the local control-plane container only.
    - `GET /actuator/health/liveness` and `/actuator/health/readiness` both return 200 `{"status":"UP"}` and no other fields.
    - `docker stop etp-migration-postgres` (stop only; do not remove the container or its volume). Readiness returns 503 `{"status":"DOWN"}`, possibly after up to 10 s (the connection timeout), and liveness stays 200. `docker start etp-migration-postgres`, and readiness returns to 200.
    - During a `process-next-batch` or `execute` on a file large enough to take several seconds, send SIGTERM (Ctrl+C in the `mvn spring-boot:run` window). `execute` returns after the batch in progress, with the remaining batches `PENDING`, and the log has `Stopped claiming batches because the application is shutting down migrationRunId=...`. After restarting, `process-next-batch` continues the run. A batch left in `PROCESSING` can be claimed again after its 5-minute lease.
    - The worker log lines for the run show `run=<runId> batch=<batchId>`. Failure lines show `errorCode` and the exception type, not source values.
15. **Cleanup.** On the isolated test schema only: `DROP SCHEMA IF EXISTS uam CASCADE` and drop the staging table you created. Do not drop `public` control-plane tables to remove UAM rows. Do not run that drop against a shared database.

---

## 6. Expected checks and current gaps

| Check | Current evidence |
|-------|------------------|
| 100 addresses stage 500 entities and leave UAM empty until promote | Passing integration test |
| 100-address promote counts and foreign keys | Passing integration test |
| Staging COPY does not write UAM | `stagingLoadDoesNotWriteCanonicalTables` passed |
| Source timestamp preserved and blank `created_at` filled | `catalogConvertsSchemaTypesAndAppliesDefaultsPerRow` passed |
| Per-row defaults and catalog types | Same method passed |
| Duplicate load does not double UAM rows | `retryDoesNotDuplicateCanonicalRows` passed |
| Failed insert rolls back that target transaction | `failedBatchRollsBackAndRetryInsertsOnce` passed |
| Ten sub-addresses promoted, with blank `private_street_name` and `private_street_number` stored as NULL | `repeatedParentsPromoteTenGeographicSubAddresses` |
| `external_identifier` | Not in the schema or tests |
| Enrichment, deduplication, source-to-staging checksum, canonical UAM reconciliation | Not implemented |
| Readiness includes the control-plane database; liveness does not | `OperationalReadinessIntegrationTest`, `ReadinessDuringDatabaseOutageIntegrationTest` (simulated outage) |
| `execute` stops claiming batches on shutdown | `MigrationExecutionServiceTest`, `ShutdownSignalTest` |
| Batch cut off by forced shutdown is reclaimed after its lease | Not tested end to end; lease reclaim and fencing are in `MigrationBatchConcurrencyIntegrationTest` |
| Pool timeouts bound and validated | `ConnectionPoolSettingsTest`, `OperationalReadinessIntegrationTest` |
| Worker and retry logs carry run and batch IDs | `MigrationBatchWorkerIntegrationTest.workerAndRetryLogsCarryTheRunAndBatchIdentifiers`, `MigrationBatchWorkerLogCorrelationTest`, `MigrationLogContextTest` |
| Real database stop, SIGTERM during a run | Manual (step 14); not executed |
| Full Surefire suite | Not run for this update |

The six-entity file can store a sub-address whose private street name and number are null. The 100-address file does not instantiate `GeographicSubAddress`.

---

## 7. Reading order

1. [ARCHITECTURE.md](ARCHITECTURE.md) §4 and §29
2. [API_GUIDE.md](API_GUIDE.md) composite configuration
3. This guide §4 before a manual six-entity promote
4. [USER_GUIDE.md](USER_GUIDE.md) for local ports and the `dev` profile
5. [DATABASE_MODEL.md](DATABASE_MODEL.md) §18 for which tables Flyway does not create
