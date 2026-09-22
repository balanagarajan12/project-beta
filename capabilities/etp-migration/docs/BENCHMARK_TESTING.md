# ETP Migration — Benchmark testing (Phase 12)

**Implementation status:** Local measurement tooling and a recorded workstation run. **This is not a production-scale capacity claim.**

**30 million records are NOT claimed as supported.** 10M and 30M end-to-end runs were **not executed**.

Authoritative measured write-up: repository `docs/performance/PHASE_12_PERFORMANCE_RESULTS.md` (2026-09-20). This file summarizes that document and the classes still in `com.etp.migration.performance`.

Related: [ARCHITECTURE.md](ARCHITECTURE.md).

---

## What Phase 12 is

A **tooling and measurement** slice. It did **not** change production worker/COPY/checksum behavior to “optimize for 30M”. Default application batch size remains **10000** (`etp.migration.batch-size`). No Kafka, Spark, Airflow, Kubernetes, Redis, scheduler, or GUI was added.

---

## Classes

| Class | Role |
|-------|------|
| `BenchmarkDataGenerator` | Streams synthetic address-like CSV to disk (bounded memory, LF newlines, deterministic). |
| `BenchmarkRunner` | CLI `main` that calls the generator. **Not** a Spring `CommandLineRunner`; app startup does not generate files. |
| `BenchmarkConfiguration` | recordCount, batchSize, workers, output path, seed (`DEFAULT_BATCH_SIZE=10000`). |
| `BenchmarkMetrics` / `BenchmarkResult` | Timing helpers used by tests. |
| `PerformanceBenchmarkTest` | JUnit `@Tag("performance")` e2e and micro measurements. |
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

## LOCAL BENCHMARK RESULT (workstation, 2026-09-20)

**Label: LOCAL BENCHMARK RESULT.** Environment: Windows 11, Intel i5-1235U, ~16 GB RAM, PostgreSQL 17.10 on `localhost:55432` **at the time of measurement**, JDK 21, Spring Boot 4.1.0. These numbers are **workstation measurements**, not a production SLA. The application default demo port is now **55433**; later `mvn test -Pperformance` runs against Testcontainers PostgreSQL 17 (`etp_migration_test`).

### Headline 1,000,000-row e2e (single worker)

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

Phase 12 recorded `mvn clean test` at **145** tests on 2026-09-20. The default suite has grown since (later phases, OpenAPI security tests, Testcontainers isolation). Performance tests remain excluded unless `-Pperformance`.
