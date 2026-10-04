# ETP Migration — Benchmark testing

**Implementation status:** Local measurement tooling. **This is not a production-scale capacity claim.**

Authoritative Phase 10 result: [PHASE_10_PERFORMANCE_RESULTS.md](performance/PHASE_10_PERFORMANCE_RESULTS.md). Architecture summary: [ARCHITECTURE.md](ARCHITECTURE.md) §27.

The 2026-09-20 flat end-to-end figure of about **9,650 records/sec** is a **historical** measurement of an earlier flat pipeline. It is not the Phase 10 result.

**10M, 30M, and 50M were not demonstrated.**

---

## Phase 10 — local CSV baseline

One worker. Batch size 10,000. Generated data, not a production dataset.

| Category | What the clock includes | What it does not include |
|----------|-------------------------|--------------------------|
| Flat source read | Reading the generated CSV | Mapping, staging, reconciliation |
| Entity prepare | Read, extract, relationship resolution, scope build, execution planning | PostgreSQL staging, HTTP reconcile |
| Entity process-batch | One source reread per batch, mapping, validation, recording target | PostgreSQL staging. Run only at 10,000 and 100,000 leaves |
| Reconciliation fold | In-memory aggregation of planned work items | `POST /reconcile` |
| PostgreSQL staging copy | COPY of 10,000 rows into Testcontainers PostgreSQL 17 | Reconciliation |

Stage timings inside prepare overlap. Relationship-resolution time includes source reading and extraction. Planning time includes walking scopes. Do not add stage timings and call the sum elapsed time.

Observed heap is one used-heap sample, not an exact peak and not process RSS. On the 1,011,000-entity prepare runs that sample was about 1.7–1.9 GB of total used heap. The scope-index estimate at that size is about 198 MB. Those are not the same number.

Entity files use opaque names EntityA, EntityB, and EntityC. Roots = leaves/1000, middles = leaves/100, leaves = the requested leaf count, with at least one root. `--records` for `single` and `multi` is the leaf count.

1,011,000 entities were successfully measured locally during the prepare benchmark. That is a local prepare baseline of about 1.01 million generated entities. It is not a capacity claim.

| Scenario | Size | Prepare | Rate | Reconcile fold | Observed heap |
|----------|------|---------|------|----------------|---------------|
| Flat source read | 10,000 rows | 246 ms | 40,650 rows/s | — | 59 MB |
| Flat source read | 100,000 rows | 266 ms | 375,940 rows/s | — | 107 MB |
| Single-file | 10,110 entities | 4,009 ms | 2,522 entities/s | 77 ms | 113 MB |
| Multi-file | 10,110 entities | 796 ms | 12,701 entities/s | 183 ms | 152 MB |
| Single-file | 101,100 entities | 6,290 ms | 16,073 entities/s | 99 ms | 340 MB |
| Multi-file | 101,100 entities | 1,852 ms | 54,590 entities/s | 58 ms | 505 MB |
| Single-file | 1,011,000 entities | 47,468 ms | 21,299 entities/s | 277 ms | 1,904 MB |
| Multi-file | 1,011,000 entities | 20,590 ms | 49,102 entities/s | 300 ms | 1,739 MB |

Process-batch, recording target, 10,000 and 100,000 leaves only:

| Layout | Entities | Batches | Process | Entities/s |
|--------|----------|---------|---------|------------|
| Single-file | 10,110 | 2 | 1,070 ms | 9,449 |
| Multi-file | 10,110 | 2 | 2,021 ms | 5,002 |
| Single-file | 101,100 | 11 | 11,855 ms | 8,528 |
| Multi-file | 101,100 | 11 | 6,406 ms | 15,782 |

PostgreSQL staging, 10,000 rows, reconciliation not included:

| Layout | Copy | Rows/s | Retry | Rows after retry |
|--------|------|--------|-------|------------------|
| Flat | 773 ms | 12,937 | — | 10,000 |
| Entity | 841 ms | 11,891 | 469 ms | 10,000 |

**Measured:** the rows above; a single scope through 100,000 members; about 1,011,000 identities across many scopes; relationship parent-index behavior through 100,000 parents.

**Not demonstrated:** 1,000,000-leaf process-batch, 1,000,000-row staging copy, a 1,000,000-entity run through staging and HTTP reconciliation, one logical scope of 1,000,000 members, 10M, 30M, 50M.

```powershell
cd capabilities\etp-migration
mvn test
$env:ETP_MIGRATION_ENTITY_SCALE = "1m"
mvn test -Pperformance "-Dtest=EntityPipelineBenchmarkTest,EntityStagingBenchmarkTest"
```

Without `ETP_MIGRATION_ENTITY_SCALE=1m`, prepare stops at 100,000 leaves. Normal `mvn test` excludes `@Tag("performance")`.

```text
java -cp ... com.etp.migration.performance.BenchmarkRunner --layout flat --records 100000 --output ./data/benchmark/addresses.csv
java -cp ... com.etp.migration.performance.BenchmarkRunner --layout single --records 100000 --output ./data/benchmark/entity-single.csv
java -cp ... com.etp.migration.performance.BenchmarkRunner --layout multi --records 100000 --output ./data/benchmark/entity-multi
```

Generated CSV is not committed.

---

## Historical flat-pipeline measurement (2026-09-20)

Authoritative write-up: [PHASE_12_PERFORMANCE_RESULTS.md](performance/PHASE_12_PERFORMANCE_RESULTS.md). The “Phase 12” name in that file is the historical performance note. It is not roadmap Phase 12 (XLSX multiple sheets), which is future and not implemented.

## What that historical measurement is

A **tooling and measurement** slice. It did **not** change production worker/COPY/checksum behavior to “optimize for 30M”. Default application batch size remains **10000** (`etp.migration.batch-size`). No Kafka, Spark, Airflow, Kubernetes, Redis, scheduler, or GUI was added.

---

## Classes

| Class | Role |
|-------|------|
| `BenchmarkDataGenerator` | Streams synthetic address-like CSV to disk (bounded memory, LF newlines, deterministic). `main` also accepts `--layout single` and `--layout multi`. |
| `EntityDatasetGenerator` | Streams a deterministic EntityA / EntityB / EntityC dataset for single-file or multi-file benchmarks. |
| `BenchmarkRunner` | CLI `main` that calls the generator. **Not** a Spring `CommandLineRunner`; app startup does not generate files. |
| `BenchmarkConfiguration` | recordCount, batchSize, workers, output path, seed (`DEFAULT_BATCH_SIZE=10000`). |
| `BenchmarkMetrics` / `BenchmarkResult` | Timing helpers used by tests. |
| `PerformanceBenchmarkTest` | JUnit `@Tag("performance")` e2e and micro measurements for the flat pipeline. |
| `EntityPipelineBenchmarkTest` | `@Tag("performance")` local prepare measurement for flat source read and entity layouts. |
| `EntityStagingBenchmarkTest` | `@Tag("performance")` PostgreSQL staging copy of 10,000 flat rows and 10,000 entity rows. |
| `BenchmarkPipeline` | Test-only helper imported by that test. |

Surefire **default** `mvn test` sets `<excludedGroups>performance</excludedGroups>`, so these tests do **not** run in the normal suite.

Maven profile **`performance`** runs `@Tag("performance")` and sets `etp.migration.performance.scale=1m` unless overridden.

---

## Configuration properties

From `application.properties` (not applied as a startup job; `etp.migration.performance.enabled` defaults **false**):

| Property | Default |
|----------|---------|
| `etp.migration.performance.enabled` | `false` |
| `etp.migration.performance.record-count` | `1000000` |
| `etp.migration.performance.batch-size` | `10000` |
| `etp.migration.performance.output-file` | `./data/benchmark/addresses.csv` |

Scale flags used by the performance test (see Phase 12 doc): `etp.migration.performance.scale` values `1m` (profile default), optional `10m` / `30m` **gates** that were not run for the published table.

Application processing still uses `etp.migration.batch-size` (10000) unless a definition overrides `batchSize`.

---

## Dataset

Generator columns (Phase 12):

`record_id,house_number,street_name,street_suffix,city,state,postal_code,country,address_line_1,address_line_2,opened_on`

Mapping in that test exercised TRIM, DIRECT, UPPERCASE, LOWERCASE, CONCAT, SPLIT, REGEX, CONDITIONAL, DATE_FORMAT.

Files are gitignored under `capabilities/etp-migration/data/`.

---

## How to reproduce

Normal tests (performance **excluded**):

```powershell
cd capabilities\etp-migration
mvn clean test
```

Performance profile (requires Docker for Testcontainers; `@SpringBootTest` performance tests use `etp_migration_test`, not the demo JDBC URL):

```powershell
mvn test -Pperformance
```

Optional larger e2e (**not** part of the published 1M result; may be skipped unless the test’s scale assumption passes):

```powershell
mvn test -Pperformance "-Detp.migration.performance.scale=10m"
mvn test -Pperformance "-Detp.migration.performance.scale=30m"
```

CSV generation only (no Spring):

```text
java -cp ... com.etp.migration.performance.BenchmarkRunner --records 1000000 --output ./data/benchmark/addresses.csv
```

---

## Historical local result (workstation, 2026-09-20)

**Historical flat-pipeline measurement.** This is not the Phase 10 entity baseline. Environment: Windows 11, Intel i5-1235U, ~16 GB RAM, PostgreSQL 17.10 on `localhost:55432` **at the time of measurement**, JDK 21, Spring Boot 4.1.0. These numbers are **workstation measurements**, not a production SLA. The application default demo port is now **55433**; later `mvn test -Pperformance` runs against Testcontainers PostgreSQL 17 (`etp_migration_test`).

### Historical 1,000,000-row flat e2e (single worker)

| Metric | Value |
|--------|--------|
| Records | **1,000,000** |
| Batch size | **10,000** |
| Workers | **1** |
| Duration | **103.627 s** (~**103.6 s**) |
| Throughput | **9,650 records/sec** |
| Successful / staged | **1,000,000 / 1,000,000** |
| Reconciliation | **246 ms** |
| Run status | COMPLETED |
| Peak heap (sampled) | ~305 MB |

```
recordCount=1000000 batchSize=10000 workers=1 batchCount=100
durationSeconds=103.627 recordsPerSecond=9650
successCount=1000000 targetCount=1000000
reconciliationMs=246
runStatus=COMPLETED internallyConsistent=true
```

Checksum of the **entire file once per batch** was estimated ~11.3 s (~10.9% of that e2e). That cost was **not** redesigned in Phase 12.

### Other **executed** rows (same report)

| Records | Batch | Workers | Duration | Records/sec | Reconcile | Notes |
|---------|-------|---------|----------|-------------|-----------|--------|
| 50k | 10k | 1 | 2.113 s | 23,663 | 156 ms | PASSED (staging); checksum warnings |
| 50k | 25k | 1 | 1.790 s | 27,933 | 129 ms | |
| 50k | 50k | 1 | 1.234 s | 40,519 | 66 ms | |
| 20k | 10k | 2 | 0.390 s | 51,282 | 66 ms | Later rerun slower; possible under-count |
| 40k | 10k | 4 | 0.873 s | 45,819 | 142 ms | Staging 40k; run `successRecords` **under-counted** (20k) |

Isolated COPY (same `CopyManager` path): 10k rows ~75k rows/s; 50k ~104k rows/s; 100k ~88k rows/s (separate 100k test ~52k rows/s on cold context).

In-process transform of 20k records ~51k records/s (no I/O).

CSV stream 10k rows ~85k records/s.

---

## What has **not** been tested / executed

| Item | Status |
|------|--------|
| 10M e2e | **NOT EXECUTED** |
| 30M e2e | **NOT EXECUTED** |
| 50M | **NOT EXECUTED** |
| 1M with 50k batch | **NOT EXECUTED** |
| 100k batch-size e2e | **NOT EXECUTED** |
| OS JVM CPU % / PostgreSQL IO histograms | **NOT EXECUTED** |
| Process kill/restart | Lease expiry simulated in-process only |
| Separate JVM workers | Multi-worker tests shared **one** Spring context and one `WorkerIdentity` |

Do **not** extrapolate 9,650 records/s × time to “30M supported”.

---

## Methodology notes

- Throughput = records / wall duration of process-all-batches.
- Peak heap is sampled around batch completion, not MXBean high-water guaranteed.
- Reconciliation COUNT uses SQL `COUNT(*)` on staging, not fetching all rows into Java.
- Checksum reconciliation does not compare business checksums (`comparisonPerformed=false`).
- Retry still DELETE + COPY for that run/batch.
- **No production optimizations** were shipped in Phase 12 (no checksum cache, no COPY→INSERT, no pool resize, no Kafka).

---

## Known correctness issue (measured)

Four in-process workers copied 40k staging rows but persisted `successRecords=20000` (lost updates on `migration_run`). Reconciliation against staging then fails. **Not fixed** in Phase 12 (control-plane locking, not a throughput tweak).

---

## Relation to current default test suite

The 2026-09-20 note recorded `mvn clean test` at **145** tests. The default suite after Phases 0–10 is **516** tests, 0 failures, 0 errors, 0 skipped. Performance tests remain excluded unless `-Pperformance`.
