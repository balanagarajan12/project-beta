# Phase 10 — CSV performance, scalability, and hardening

**Label: local benchmark measurement.** This is the authoritative Phase 10 result. It is not a production-capacity claim.

1,011,000 entities were successfully measured locally during the prepare benchmark. Phase 10 established a local benchmark baseline at approximately 1.01 million generated entities for the prepare path. The figure is that local prepare measurement. It is not a production capacity figure, and it is not a linear extrapolation.

The historical flat end-to-end figure of about 9,650 records/sec (1,000,000 rows, about 103.6 seconds, reconciliation 246 ms) is a different pipeline, recorded on 2026-09-20. It is not this result.

## 1. Objective

Measure the current CSV architecture, record where memory is held, harden entity staging idempotency, and keep a repeatable local baseline. The functional model was not redesigned. The in-memory scope index and relationship parent index were kept after measurement.

## 2. Test environment

Windows workstation, JDK 21, Maven Surefire, one worker. PostgreSQL staging used Testcontainers PostgreSQL 17 (`etp_migration_test`). No production-like environment was used.

Observed heap is one `MemoryMXBean` sample of used heap. It is not an exact peak, and it is not process RSS.

## 3. Benchmark methodology

Batch size 10,000. Generated files are created at benchmark time and are not committed.

| Clock | Included | Excluded |
|-------|----------|----------|
| Prepare | Source read, entity extraction, relationship resolution, scope construction, execution planning | PostgreSQL COPY, HTTP reconcile |
| Process-batch | One source reread per batch, mapping, validation, load into a recording target | PostgreSQL. Measured only at 10,000 and 100,000 leaves |
| Reconciliation fold | In-memory aggregation of planned work items | `POST /api/v1/migration-runs/{runId}/reconcile` |
| Staging copy | PostgreSQL COPY of 10,000 rows | Reconciliation |

`PipelineTiming` keeps ten stage totals and a small set of counters for one pass. It does not store a timing event per row. Stage timings overlap. Relationship-resolution time includes source reading and extraction. Planning time includes walking scopes. Do not add the stage timings and present that sum as elapsed time.

## 4. Dataset model

Opaque names EntityA, EntityB, and EntityC. Not a production dataset.

| Role | Count |
|------|-------|
| EntityA (root) | leaves / 1000, at least 1 |
| EntityB (middle) | leaves / 100, at least the root count |
| EntityC (leaf) | the requested leaf count |

Relationship: EntityB references EntityA, EntityC references EntityB. Single-file layout writes one CSV. Multi-file layout writes one CSV per entity.

## 5. Prepare results

One worker. Batch size 10,000. Rates for entity rows are entities per second. Flat rows are source records per second.

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

Prepare file opens were 5 for a single file and 7 for the three-file layout. That count follows the number of entity types, not a cartesian product of the files.

The 1,904 MB and 1,739 MB figures are total used-heap samples. They are not the scope index.

## 6. Process-batch results

Recording target. Each batch rereads the source. Not included in the prepare times above. A 1,000,000-leaf process-batch was not run.

| Layout | Entities | Batches | Process | Entities/s | Identity lookups |
|--------|----------|---------|---------|------------|------------------|
| Single-file | 10,110 | 2 | 1,070 ms | 9,449 | 104 |
| Multi-file | 10,110 | 2 | 2,021 ms | 5,002 | 104 |
| Single-file | 101,100 | 11 | 11,855 ms | 8,528 | 1,032 |
| Multi-file | 101,100 | 11 | 6,406 ms | 15,782 | 1,032 |

Lookups are chunked at 100 source identities.

## 7. PostgreSQL staging results

10,000 rows. Reconciliation is not included. A 1,000,000-row staging copy was not measured.

| Layout | Table | Copy | Rows/s | Retry | Rows after retry |
|--------|-------|------|--------|-------|------------------|
| Flat | `migration_staging.flat_benchmark` | 773 ms | 12,937 | — | 10,000 |
| Entity | `migration_staging.entity_identity_benchmark` | 841 ms | 11,891 | 469 ms | 10,000 |

The entity retry loaded the same identities under another batch id. The row count stayed 10,000.

## 8. Memory findings

Four figures must stay separate:

| Figure | What it is | At about 1.01M entities |
|--------|------------|-------------------------|
| Scope index estimate | Identities and child links held until the scope stream closes | about 198 MB |
| Relationship parent index estimate | Parent name, source identity, and source record number, keyed by relationship keys | about 1.9 MB (11,000 parents) |
| Resolved relationship list | One entry per child link, reachable until prepare finishes | about 1,010,000 entries |
| Observed JVM heap | One used-heap sample for the whole prepare run | about 1.7–1.9 GB |

The observed heap is not the scope index.

## 9. Relationship-index findings

The parent index stores parent entity name, parent source identity, and parent source record number. It is keyed by the configured relationship keys. Its size follows parent cardinality, not leaf cardinality. At 1,000,000 leaves it held about 11,000 parents and about 1.9 MB. A unit test indexed 1,000, 10,000, and 100,000 distinct parents; the estimate grew about tenfold at each step. The index is discarded when relationship resolution returns.

The resolved relationship list is a separate structure. It grows with child links and stays reachable until prepare completes.

## 10. Scope-index findings

Approximate footprint: about 196 bytes per identity. The estimate does not include source field maps.

| Identities | Estimated scope index | Largest scope in that prepare run |
|------------|----------------------|-----------------------------------|
| 10,110 | about 1.9 MB | 1,011 members |
| 101,100 | about 19.6 MB | 1,011 members |
| 1,011,000 | about 198 MB | 1,011 members |

The prepare benchmark spreads leaves across parents, so one scope stayed near 1,011 members. A unit test also built one scope of 1,000, 10,000, and 100,000 members. A single scope of 1,000,000 members was not built.

Phase 10 kept this in-memory index. No persisted scope index was added.

## 11. Entity idempotency design

Flat key, unchanged: `runId:batchId:sourceRecordNumber`.

Entity key: `entity-v1:` plus the hex SHA-256 of run id, entity name, and source identity. Batch id is not part of the logical entity identity. The same entity in another batch has the same key. The latest load is the one staging row. A second copy inside one batch is counted as a duplicate and is not loaded twice.

Operator DDL, not Flyway:

- Flat: `UNIQUE (migration_run_id, migration_batch_id, source_record_number)`
- Entity: `UNIQUE (migration_run_id, idempotency_key)`

Flyway remains V1–V3. V4 was not introduced. A JSONB configuration cannot enforce uniqueness across staging rows.

## 12. Retry behavior

The existing batch lifecycle is unchanged. For an entity load the connector:

1. removes the current batch’s rows
2. removes earlier rows in the same run whose entity keys are being loaded
3. COPY-loads the current entity rows

A successful entity is not duplicated. An entity can move between batches without becoming a second logical entity. An entity that was not loaded can be inserted on retry. The source identity and the hashed key stay the same.

## 13. Flat compatibility

Flat source processing, mapping, target behavior, run lifecycle, reconciliation types, and retry semantics were left in place. Flat keys do not use the `entity-v1:` prefix, so the entity-key delete does not apply to them.

## 14. Tests

Default suite after Phase 10: **516** tests, 0 failures, 0 errors, 0 skipped. `mvn test` does not run `@Tag("performance")`.

Structural coverage includes bounded batches, chunked identity lookup, payload release, bounded relationship and scope footprints through 100,000, deterministic entity keys, unchanged flat keys, multi-file open counts, bounded reconciliation examples, and PostgreSQL behavior when two entities share a source record number.

## 15. Commands

```powershell
cd capabilities\etp-migration
mvn test
$env:ETP_MIGRATION_ENTITY_SCALE = "1m"
mvn test -Pperformance "-Dtest=EntityPipelineBenchmarkTest,EntityStagingBenchmarkTest"
```

Without `ETP_MIGRATION_ENTITY_SCALE=1m`, the prepare benchmark stops at 100,000 leaves.

```text
java -cp ... com.etp.migration.performance.BenchmarkRunner --layout flat --records 100000 --output ./data/benchmark/addresses.csv
java -cp ... com.etp.migration.performance.BenchmarkRunner --layout single --records 100000 --output ./data/benchmark/entity-single.csv
java -cp ... com.etp.migration.performance.BenchmarkRunner --layout multi --records 100000 --output ./data/benchmark/entity-multi
```

## 16. Limitations

1. Process-next-batch rereads the source once per batch.
2. The scope index and the resolved relationship list are retained through prepare.
3. A production target-identity store is not implemented.
4. Benchmarks use a recording target or PostgreSQL staging, not canonical UAM loading.
5. A 1,000,000-leaf process-batch was not measured.
6. A 1,000,000-row staging copy was not measured.
7. A full 1,000,000-entity migration through staging and HTTP reconciliation was not measured.
8. A single scope of 1,000,000 members was not measured.
9. 10M, 30M, and 50M datasets were not demonstrated.
10. Observed heap is a sample, not an exact peak-memory measurement.
11. Pipeline stage timings overlap.

## 17. Measured vs not demonstrated

**Measured**

- Flat CSV source read at 10,000 and 100,000 rows
- Entity single-file prepare at 10,000, 100,000, and 1,000,000 leaves
- Entity multi-file prepare at 10,000, 100,000, and 1,000,000 leaves
- Entity process-batch at 10,000 and 100,000 leaves
- PostgreSQL staging at 10,000 rows
- Entity staging retry at 10,000 rows
- Scope footprint through 100,000 members
- About 1,011,000 identities across many scopes
- Relationship parent-index behavior through the 100,000-parent unit test

**Not demonstrated**

- 1,000,000-leaf process-batch
- 1,000,000-row staging copy
- 1,000,000-entity end-to-end migration through staging and HTTP reconciliation
- One logical scope of 1,000,000 members
- 10M
- 30M
- 50M

## 18. Conclusion

Phase 10 is complete. The selected strategy is to keep the in-memory scope identity index and the in-memory relationship parent index. No persisted identity index was added. Flyway remains V3. XLSX was not started.
