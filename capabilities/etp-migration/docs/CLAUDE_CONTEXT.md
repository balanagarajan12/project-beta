# ETP Migration — AI / Claude context

Compact handoff for assistants. Inspect code before changing it. Details: [ARCHITECTURE.md](ARCHITECTURE.md), [API_GUIDE.md](API_GUIDE.md), [DATABASE_MODEL.md](DATABASE_MODEL.md), [USER_GUIDE.md](USER_GUIDE.md), [BENCHMARK_TESTING.md](BENCHMARK_TESTING.md).

---

## 1. Project identity

- Maven artifact: `com.etp:etp-migration:0.0.1-SNAPSHOT`
- Path: `capabilities/etp-migration`
- Name: **ETP Migration Capability**

---

## 2. Purpose

Configuration-driven, batch-oriented **data migration** from filesystem CSV into **PostgreSQL staging**, then optional **explicit** promotion into a configured PostgreSQL business table, with **explicit** reconciliation. Generic engine — not a UAM product module.

---

## 3. Technology stack

Java 21, Spring Boot 4.1.0, Spring Web MVC, Data JPA, Security OAuth2 resource server (JWT), Validation, Actuator (health), Flyway PostgreSQL, springdoc-openapi 3.1.0 (scheme `bearer-jwt` + operation `SecurityRequirement`), Apache Commons CSV, PostgreSQL JDBC COPY. Test: Testcontainers **1.21.4**, image `postgres:17`.

---

## 4. Module structure

```
capabilities/etp-migration/
  pom.xml
  src/main/java/com/etp/migration/   # application
  src/main/resources/db/migration/   # V1, V2
  src/test/java/                     # Surefire *Test.java
  docs/                              # this documentation set
```

---

## 5. Current architecture (one paragraph)

HTTP control plane persists definitions/mappings/sources/runs/batches. `MigrationExecutionService.execute` is **synchronous and not** one transaction: create ACTIVE-only run → inspect CSV → loop claim/process. Worker transforms/validates then `PostgreSqlTargetConnector` COPY to staging. Reconciliation and promotion are separate POSTs. GET promotion is read-only. Swagger sends `Authorization: Bearer` when operations list `bearer-jwt`. Integration tests use Testcontainers `etp_migration_test`, not localhost:55433.

---

## 6. Domain concepts

Definition, mapping, mapping rule, source, run, batch, artifact metadata, error, reconciliation (COUNT/CHECKSUM/TARGET/FULL), promotion + promotion batch. Staging ≠ business table. Run COMPLETED = staging done.

---

## 7. Implementation status

**Implemented:** composite DRAFT config (`@Transactional` mapping → rules → definition → source), activate, execute facade, stepwise run/prepare/process-next-batch, cancel/retry, staging + business-target reconcile, promotion prepare/execute/GET, filesystem CSV, PostgreSQL BULK COPY, UAM REST **placeholder** SINGLE, JWT + OpenAPI `bearer-jwt` requirement, `dev` permitAll on APIs, Phase 12 benchmark tests (opt-in profile), Testcontainers IT isolation.

**Not implemented:** enrichment engine, dedup engine, Kafka, scheduler, `@Async` worker, GUI, object-storage/SFTP/S3 sources, LOOKUP/CUSTOM transforms at runtime, `migration_record` success lineage, GET `/promotions/{id}`, deactivate INACTIVE API, production UAM-specific connector, automatic environment promotion/deployment governance, 10M/30M e2e proof.

---

## 8. Completed phases (as built in this repo)

There is **no** Phase 13/14 markdown besides Flyway V2 comments and this docs set. In code/tests:

- Control plane + CSV + COPY + retry/leases  
- Reconciliation  
- **Phase 12** local performance tooling (`docs/performance/PHASE_12_PERFORMANCE_RESULTS.md` at **repo root**)  
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
- `POST /api/v1/migrations/{migrationId}/execute`
- `GET /api/v1/migration-runs/{runId}`
- `POST /api/v1/migration-runs/{runId}/reconcile` (+ `verificationScope`)
- `GET /api/v1/migration-runs/{runId}/reconciliation`
- `POST /api/v1/migration-runs/{runId}/promotion/prepare`
- `GET /api/v1/migration-runs/{runId}/promotion`
- `POST /api/v1/migration-runs/{runId}/promote`

Also: mappings/rules, sources, runs list/create, prepare, process-next-batch, cancel, retry, batches, actuator health.

---

## 10. Database summary

Flyway **V2**. Control tables `migration_*` including `migration_promotion` / `migration_promotion_batch`. Staging/business tables **not** in Flyway. Do not call `uam_migration_test.address` a platform table.

---

## 11. Configuration model

JSONB on definition: `target.schema`, `stagingTable`, `mergeStrategy=NONE`; optional `promotion.enabled/schema/table/strategy/columns/conflictKeys`. Source JSON: `provider=FILE_SYSTEM`, `fileFormat=CSV`, `objectKey`, `hasHeader`. Documented demo `sourceType` is **`FILE_SYSTEM`** (free-form string; connector uses configuration, not that label). Properties: `etp.migration.*` in `application.properties`. Local JDBC default **localhost:55433**.

---

## 12. Source connectors

Only `CsvSourceConnector`. Registry uses `supports()` → `CsvFileSourceConfiguration.from`. `sourceType` must be non-blank; `FILE` vs `FILE_SYSTEM` **labels** both work if JSON is valid FILE_SYSTEM+CSV.

---

## 13. Target connectors

- `POSTGRESQL`: BULK COPY staging.  
- `UAM`: SINGLE HTTP POST placeholder — **not** production UAM. Promotion is PostgreSQL-only (generic SQL). Local business stand-in: `uam_migration_test.address`.

---

## 14. Mapping / validation

`TransformationEngine` implements DIRECT, TRIM, UPPERCASE, LOWERCASE, DEFAULT, CONCAT, SPLIT, REGEX, CONDITIONAL, DATE_FORMAT. LOOKUP/CUSTOM throw. Validation = mandatory target fields non-blank.

---

## 15. Reconciliation

User-invoked. Default **STAGING**. Checksum **not** compared to staging (`comparisonPerformed=false`). `verificationScope=BUSINESS_TARGET` uses `conflictKeys` + **promoted** batches only; no `conflictKeys` → `MIGRATION_TARGET_IDENTITY_NOT_CONFIGURED`. Does not change run/promotion status.

---

## 16. Promotion

Prepare READY from staging counts (no business writes); promote per-batch TX (INSERT parameterized; UPSERT ON CONFLICT; identifiers quoted); failed batch does not roll back others (`PARTIALLY_PROMOTED`); GET maps persisted `ExecutePromotionResponse`. Missing promotion on GET = 404; missing promotion on POST promote = 409 `PROMOTION_NOT_READY`.

---

## 17. Execution lifecycle

DRAFT → ACTIVE → execute → inspect → plan → SKIP LOCKED claim → transform/validate → COPY → batch complete → run complete. Then optional recon → prepare → promote → business recon.

---

## 18–19. Demo (local only)

CSV runtime: `{storage.root}/demo/addresses.csv` → default `data/migration/demo/addresses.csv`. Git sample: repo `demo/data/addresses.csv`. **8** rows; DEMO-004 empty `address_line_1` → **7** valid, **1** validation failure, `batchSize` 3, **3** batches, execute **`COMPLETED_WITH_ERRORS`**. Staging `migration_staging.address_load`. Simulated target **`uam_migration_test.address`**, not real UAM.

Do **not** hard-code demonstration UUIDs.

---

## 20. Limitations

Enrichment/dedup/Kafka/scheduler/GUI absent. Multi-worker run counters can under-count. Per-batch full-file SHA-256. `dev` security is permitAll on APIs. Flyway V1 comment “30M+” is not a measurement. Integration tests must not use the demo JDBC URL.

---

## 21. Explicit non-claims

- **Do not claim 30M support.** Local 1M @ 10k batch / 1 worker / ~103.6 s / ~9650 rps / 246 ms recon. 10M/30M **NOT EXECUTED**.
- **Do not claim production UAM integration.**
- **Do not claim a GUI.**
- **Do not claim Kafka or a scheduler.**
- **Do not claim DRAFT execute** — create/execute require ACTIVE.

---

## 22. Architectural decisions (preserve)

- Cloud-agnostic configuration; filesystem implemented.
- Generic migration engine; **no UAM-specific Java** in promotion/staging SQL builders.
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
