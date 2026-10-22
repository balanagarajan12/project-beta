# ETP Migration — AI / Claude context

Compact handoff for assistants. Inspect code before changing it. Details: [ARCHITECTURE.md](ARCHITECTURE.md), [API_GUIDE.md](API_GUIDE.md), [DATABASE_MODEL.md](DATABASE_MODEL.md), [USER_GUIDE.md](USER_GUIDE.md), [migration-e2e-test-guide.md](migration-e2e-test-guide.md), [BENCHMARK_TESTING.md](BENCHMARK_TESTING.md).

---

## 1. Project identity

- Maven artifact: `com.etp:etp-migration:0.0.1-SNAPSHOT`
- Path: `capabilities/etp-migration`
- Name: **ETP Migration Capability**

---

## 2. Purpose

Configuration-driven, batch-oriented **data migration** from filesystem CSV into **PostgreSQL staging**, then explicit reconciliation and explicit promotion. The demonstrated business target is a local canonical UAM schema loaded from staging (source → staging → promotion → UAM). Generic engine: entities are configuration, not entity-specific production branches. Not a production UAM deployment. See [ARCHITECTURE.md](ARCHITECTURE.md) §28.

---

## 3. Technology stack

Java 21, Spring Boot 4.1.0, Spring Web MVC, Data JPA, Security OAuth2 resource server (JWT), Validation, Actuator (health), Flyway PostgreSQL, springdoc-openapi 3.1.0 (scheme `bearer-jwt` + operation `SecurityRequirement`), Apache Commons CSV, PostgreSQL JDBC COPY. Test: Testcontainers **1.21.4**, image `postgres:17`.

---

## 4. Module structure

```
capabilities/etp-migration/
  pom.xml
  src/main/java/com/etp/migration/   # application
  src/main/resources/db/migration/   # V1–V5
  src/test/java/                     # Surefire *Test.java
  docs/                              # this documentation set
```

---

## 5. Current architecture (one paragraph)

HTTP control plane persists definitions/mappings/sources/runs/batches. `POST /execute` stores a QUEUED run and returns HTTP 202. A background worker then inspects the CSV and processes batches. An `ENTITY_RELATIONSHIP` worker extracts entities, resolves relationships, maps and validates, then `PostgreSqlTargetConnector` COPY to staging with null target identities. Reconciliation, promotion preparation, and promotion are separate POSTs. Each stores a `migration_phase_job` and returns HTTP 202. Job status (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`) is separate from the reconciliation outcome and the promotion status. Completing one phase does not start the next. Canonical promotion (`target.mode=UAM`) allocates `mapping.entities[].targetIdentity` and loads configured UAM tables in one transaction per batch. GET promotion is read-only. The local demonstration used control-plane `localhost:55433` and target `localhost:55432`. Integration tests use Testcontainers `etp_migration_test`, not those ports.

---

## 6. Domain concepts

Definition, mapping, mapping rule, source, run, batch, artifact metadata, error, reconciliation (COUNT/CHECKSUM/TARGET/FULL), promotion + promotion batch. Staging ≠ business table. Run COMPLETED = staging done.

---

## 7. Implementation status

**Implemented:** Phases 0–10 plus later control-plane work. Composite DRAFT config (`entityMappings`/`fieldMappings` and legacy `mapping`/`rules`), activate, execute, prepare/process-next-batch, cancel/retry, flat and entity-aware reconciliation, promotion, filesystem CSV and SFTP CSV, entity/relationship layouts, PostgreSQL BULK COPY, entity staging idempotency (`entity-v1:` key), in-memory scope and relationship parent indexes retained after Phase 10 measurement, per-entity `targetIdentity` format (`PREFIX_SEQUENCE`, `PREFIX_SOURCE_VALUE`) with PostgreSQL range allocation (`target_identity_allocator`) and retry reuse (`migration_identity_allocation`), canonical `UAM_POSTGRESQL` load with `information_schema` conversion, UAM REST **placeholder** SINGLE, JWT, `dev` permitAll, opt-in benchmark tests, Testcontainers isolation. Background execute, reconcile, promotion preparation, and promotion return HTTP 202. Do not restate an old suite count as the current result. Catalog-load results are in [migration-e2e-test-guide.md](migration-e2e-test-guide.md). Repository Flyway is **V1–V11**. Column and error-redaction detail is in [DATABASE_MODEL.md](DATABASE_MODEL.md) and [API_GUIDE.md](API_GUIDE.md).

**Demonstrated locally:** CSV → staging → reconciliation → promotion → schema `uam`, 110 physical rows expanded to 515 logical records, six entities, eight relationships including ONE_TO_MANY, promotion-time identity allocation, rollback/retry on an earlier attempt, and a clean PROMOTED run. See [ARCHITECTURE.md](ARCHITECTURE.md) §28. That demonstration is not 30M support and not a production UAM deployment.

**Not implemented:** XLSX (roadmap Phases 11 and 12), enrichment, record deduplication, Kafka, an external job scheduler, Spring `@Async`, a migration GUI, S3 and other object stores, multi-file SFTP, LOOKUP/CUSTOM at runtime, `migration_record` success lineage, a distributed id service, 10M/30M/50M proof, complete PostgreSQL type conversion (arrays, enums, domains, `bytea`, and other unrecognized `udt_name` values pass through). Accepted work is recovered in-process by `@Scheduled` methods on `MigrationExecutionCoordinator` and `MigrationPhaseCoordinator` (`etp.migration.execution.recovery-interval-ms`, default 15000). Default concurrent runs is 1 (`etp.migration.execution.max-concurrent-runs`). SFTP for one remote CSV is implemented. The UAM REST connector remains a SINGLE HTTP placeholder. The verified UAM tables are `varchar`, `text`, `timestamptz`, `numeric`, and `jsonb`. See [ARCHITECTURE.md](ARCHITECTURE.md) §29. Core UI has no migration screens.

---

## 8. Completed phases (as built in this repo)

Roadmap Phases 0–10 are complete. Phases 11 and 12 (XLSX) are future. In code/tests:

- Control plane + CSV + COPY + retry/leases
- Reconciliation
- **Phases 0–10** complete. Phase 10 local CSV baseline: `docs/performance/PHASE_10_PERFORMANCE_RESULTS.md`. The file named Phase 12 under `docs/performance/` is a **historical** flat-pipeline measurement (~9,650 records/sec). It is not roadmap Phase 12 and not the Phase 10 result.
- Promotion control plane + PostgreSQL INSERT/UPSERT
- Composite configuration, execute facade, activation
- Promotion GET
- OpenAPI JWT security requirement
- Integration-test PostgreSQL Testcontainers isolation

Treat git history/tests as finer source than phase numbers.

---

## 9. API surface (all implemented)

See [API_GUIDE.md](API_GUIDE.md). Product path:

- `POST /api/v1/migration-configurations`
- `GET /api/v1/migrations/{migrationId}`
- `POST /api/v1/migrations/{migrationId}/activate`
- `POST /api/v1/migrations/{migrationId}/execute` — **202** after the QUEUED run commits. Background prepare and batches follow. Poll `GET /api/v1/migration-runs/{runId}`. `POST .../runs` stays stepwise and is not recovered.
- `GET /api/v1/migration-runs` (optional `migrationDefinitionId`; keeps recency order. Nested `GET /migrations/{id}/runs` stays `createdAt` order)
- `GET /api/v1/migration-runs/{runId}`
- `POST /api/v1/migration-runs/{runId}/reconcile` (+ `verificationScope`) — **202**. Poll the reconciliation GET or the phase-job GET.
- `GET /api/v1/migration-runs/{runId}/reconciliation` — stored details, with identity and message redaction on the response
- `POST /api/v1/migration-runs/{runId}/promotion/prepare` — **202**
- `GET /api/v1/migration-runs/{runId}/promotion`
- `POST /api/v1/migration-runs/{runId}/promote` — **202**
- `GET /api/v1/migration-runs/{runId}/errors` — paginated `migration_error`, redacted on read
- `GET /api/v1/migration-runs/{runId}/phase-jobs/{jobId}`

Also: mappings/rules, sources, runs list/create, prepare, process-next-batch, cancel, retry, batches, actuator health.

---

## 10. Database summary

Repository Flyway is **V1–V11**. V6 adds `migration_run.background_execution`, `execution_worker_id`, and `execution_lease_until` so an accepted execute survives restart without a second queue. V7 adds `migration_phase_job`. V8 adds run and promotion `control_state`. V9 creates the staging batch index only when that table is in the database Flyway is migrating. V10 allows one open promotion per run. V11 allows `migration_error.stage` `PROMOTE`. Startup applies pending versions only. A read-only check of container `etp-migration-postgres` (host port 55446, database `etp_migration`) shows V1–V9 successful. V10 and V11 are not in that history. Port 55433 was not inspected. That check did not apply migrations. V4 `migration_identity_allocation` stores `(run, entity, source identity)` → formatted target id so a retry reuses it. V5 `target_identity_allocator` reserves numeric ranges by prefix. `prefix` and `sequenceLength` are configuration; `current_value` is runtime state. Control tables `migration_*` including `migration_promotion` / `migration_promotion_batch` and nullable `migration_mapping_rule.entity_name`. Staging/business tables **not** in Flyway. Flat staging uniqueness is `(migration_run_id, migration_batch_id, source_record_number)`. Entity staging uniqueness is `(migration_run_id, idempotency_key)`. Do not call `uam_migration_test.address` a platform table. Do not treat `migration_record` as an identity store. `migration_record.error_id` is unused. Application-level error dedup is not a unique index, and a successful retry does not delete the earlier row. See [DATABASE_MODEL.md](DATABASE_MODEL.md).

---

## 11. Configuration model

JSONB on definition accepts only `target` (`mode`, `schema`, `stagingSchema`, `stagingTable`, `mergeStrategy`) and `promotion.enabled`. New requests that also send `tables`, `loadOrder`, `foreignKeys`, `loadTimestampColumns`, or `columnTypes` are rejected. Tables come from `entities[].targetEntity`. Foreign keys and load order come from `relationships`. Column types and required timestamps come from the target catalog. Flat promotion still uses `promotion.schema/table/strategy/columns/conflictKeys`. Identity format is `mapping.entities[].targetIdentity` (`PREFIX_SEQUENCE` / prefix / `sequenceLength`). `definition.configuration.identity` is not read. Filesystem source JSON: `provider=FILE_SYSTEM`, `fileFormat=CSV`, `objectKey`, `hasHeader`. SFTP source JSON: `provider=SFTP`, `fileFormat=CSV`, `host`, `port`, `credentialRef`, `remotePath`, `hasHeader`, `delimiter`. The SFTP password is application configuration keyed by `credentialRef`, not a field on the migration definition. Local control-plane JDBC is **localhost:55433**. The demonstrated target JDBC is **localhost:55432**.

---

## 12. Source connectors

`CsvSourceConnector` (`FILE_SYSTEM` + CSV) and `SftpCsvSourceConnector` (`SFTP` + CSV) are the source-connector beans. Both stream records through `CsvRecordReader`. Registry uses the first `supports()` match. `sourceType` must be non-blank; `FILE` vs `FILE_SYSTEM` labels both work if the JSON is valid. `MultiFileEntitySource` opens configured per-entity filesystem CSVs through `CsvSourceConnector`. SFTP is a single remote CSV and does not use the multi-file layout. Entity-relationship runs copy that remote file once (`SftpSourceCache`) and later batches seek to the stored source record numbers. A later process reuses the copy only when remote size, modification time, and the local SHA-256 still match. `migration_run.total_records` for this mode is the logical work-item count. `source.sourceRowCount` is the physical CSV row count. See [ARCHITECTURE.md](ARCHITECTURE.md) §8.

---

## 13. Target connectors

- `PostgreSqlTargetConnector` (`POSTGRESQL`): BULK COPY into staging. It does not load canonical UAM tables. Definition `targetType` stays `POSTGRESQL` for the canonical demonstration.
- `UamPostgreSqlTargetConnector`: canonical PostgreSQL UAM load used by promotion when `target.mode=UAM`. `UamCanonicalRows` plans rows from staged `targetEntity` and `targetForeignKey`. `UamTargetColumns` reads `information_schema` and converts. A required timestamp with no default and no mapped value uses one UTC instant per load. Local schema `uam` on port 55432. Not a production UAM deployment. See [ARCHITECTURE.md](ARCHITECTURE.md) §29.
- `UamTargetConnector` (`UAM`): REST SINGLE HTTP POST. Retained. Promotion does not use it.
- Older flat stand-in: `uam_migration_test.address`.

---

## 14. Mapping / validation

`TransformationEngine` implements DIRECT, TRIM, UPPERCASE, LOWERCASE, DEFAULT, CONCAT, SPLIT, REGEX, CONDITIONAL, DATE_FORMAT. LOOKUP/CUSTOM throw. Validation = mandatory target fields non-blank.

---

## 15. Reconciliation

User-invoked. Returns HTTP 202 and finishes in the background. Default **STAGING**. Checksum **not** compared to staging (`comparisonPerformed=false`). `verificationScope=BUSINESS_TARGET` uses `conflictKeys` + **promoted** batches only; no `conflictKeys` → `MIGRATION_TARGET_IDENTITY_NOT_CONFIGURED`. Does not change run/promotion status. A `FAILED` reconciliation outcome stays on `migration_reconciliation` and does not insert `migration_error`. The GET redacts secret-bearing example identities. It does not rewrite the stored details.

---

## 16. Promotion

Prepare and promote each return HTTP 202. Prepare sets READY from staging counts (no business writes). Canonical promote allocates identities, then inserts configured UAM tables in one transaction per eligible batch. Failure rolls that UAM transaction back and keeps `migration_identity_allocation` for retry. Staging rows stay. `ON CONFLICT (<targetKey>) DO NOTHING` skips an existing key. UAM fixtures use `id`. Omitting that first column fails before the statement is built. Flat promote remains INSERT/UPSERT; a failed flat batch does not roll back earlier batches (`PARTIALLY_PROMOTED`, code `MIGRATION_PROMOTION_PARTIAL_FAILURE`). All failed batches use `PROMOTION_FAILED` and `MIGRATION_PROMOTION_BATCH_FAILED`. A clean `PROMOTED` promotion clears that summary. The phase job can still be `COMPLETED`. Already `PROMOTED` returns the stored result. Missing promotion on GET = 404; missing promotion on POST promote = 409 `PROMOTION_NOT_READY`. Historical `migration_error` rows are not deleted when a later attempt succeeds.

---

## 17. Execution lifecycle

DRAFT → ACTIVE → execute → inspect → plan → SKIP LOCKED claim → transform/validate → COPY → batch complete → run complete. Then optional recon → prepare → promote → business recon.

---

## 18–19. Demonstrations

**Historical local end-to-end demonstration** is [ARCHITECTURE.md](ARCHITECTURE.md) §28. Definition `59fc4cc1-85cf-44bd-870b-dd56ae3ca1ae` version 1.2. Run `f9e9fd4f-cc61-4d0f-8e71-e3333f09b431` (run 2): 110 physical CSV rows, 515 logical records, staging reconciliation `PASSED_WITH_WARNINGS`, promotion `2994bce6-6033-4b9a-94ba-f5b2c3e1a561` `PROMOTED` 515. Local `uam` counts: location 202, geographic_address 101, geographic_location 101, location_geometry 101, geographic_sub_address 10. `private_street_name` and `private_street_number` are nullable in `local/uam-target.sql`. See [migration-e2e-test-guide.md](migration-e2e-test-guide.md).

**Historical flat illustration:** git `demo/data/addresses.csv`, **8** rows; DEMO-004 empty `address_line_1` → **7** valid, **1** validation failure, `batchSize` 3, **3** batches, execute **`COMPLETED_WITH_ERRORS`**. Staging `migration_staging.address_load`. Stand-in **`uam_migration_test.address`**. That illustration is not the current demonstration.

---

## 20. Limitations

Enrichment, record deduplication, Kafka, an external scheduler, and a migration GUI are absent. In-process recovery scheduling is present. Multi-worker run counters can under-count. Per-batch full-file SHA-256. `dev` security is permitAll on the migration APIs; other profiles require a JWT and do not check a role or a run. There is no tenant isolation in this module. Error redaction is pattern-based, not general secret detection. Application error dedup can miss a concurrent insert and can collapse a later promotion attempt that repeats the same message. Flyway V1 comment “30M+” is not a measurement. Integration tests must not use the demo JDBC URL. Do not claim V11 is applied on a database without reading that database's `flyway_schema_history`. The 55446 container does not have it yet.

---

## 21. Explicit non-claims

- **Do not present Phase 10 as production capacity.** About 1.01 million generated entities were measured locally on the prepare path only. The historical flat end-to-end figure (~103.6 s, ~9,650 records/sec) is a different pipeline. 10M, 30M, and 50M were **not executed**.
- **Do not claim a production UAM deployment.** A local canonical load into schema `uam` was demonstrated (§28 of the architecture doc). The REST connector is still a placeholder.
- **Do not claim a GUI.**
- **Do not claim Kafka or a scheduler.**
- **Do not claim DRAFT execute** — create/execute require ACTIVE.

---

## 22. Architectural decisions (preserve)

- Cloud-agnostic configuration; filesystem implemented.
- Generic migration engine. Staging SQL is generic. Canonical promotion is configured tables, keys, and `targetIdentity`, not an entity-specific Java branch per business entity. The demonstrated GeographicSubAddress case stayed on that configuration path.
- Staging separated from business-target promotion.
- Promotion explicit; reconciliation explicit.
- DRAFT → ACTIVE exists; **ACTIVE is lifecycle/certification, not environment security**. No PROD/DEV checks in migration logic. Deployment/release owns environment governance (who is allowed to activate in which environment).
- APIs still require ACTIVE to start a run (409 if DRAFT). Lower environments are expected to activate, then execute — not a special DRAFT-execute code path.
- Execute is **not** one database transaction.
- Default batch size 10000 unless definition overrides.

---

## 23. AI working rules

1. Read existing controllers/services/Flyway/tests before proposing work.
2. Preserve architecture (staging vs promotion, explicit recon, generic engine).
3. Do not invent APIs or document planned features as done.
4. Do not add UAM domain logic to the generic engine; configure table/schema in JSON.
5. Do not change schemas without justification; prefer existing columns.
6. Do not claim unsupported scale.
7. Keep existing HTTP paths/DTOs compatible unless the user asks otherwise.
8. Run `mvn clean test` in `capabilities/etp-migration` after **code** changes (`performance` group excluded by default). Docs-only changes still run the suite when the user asks. Tests use Testcontainers, not demo Postgres.
9. Prefer small, targeted diffs.
10. Do not auto-implement “next phases”.

---

## 24. Possible next steps (evaluation only)

Not a backlog to execute automatically: GET promotion-by-id, object storage, checksum-once, atomic run counters, enrichment, scheduler, real UAM contract, GUI. Decide from **actual requirements**.
