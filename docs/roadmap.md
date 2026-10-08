# Development Roadmap

## Current boundary: a durable development alpha

KoKoDB now has a complete path from SQL parsing to relational execution, generated Kotlin result mapping, memory transactions, forced file commits, checkpoints and validated restart recovery. This is the foundation of a small embedded database; it is not a general SQL engine or a published production release.

- An optional Kotlin/JVM compiler plugin checks static SQL grammar and query/write categories at direct calls; dynamic SQL and schema/binding semantics remain runtime-validated.
- CRUD supports non-null INT/TEXT columns, one optional caller-supplied primary key, projections and single equality predicates.
- Explicit synchronous transactions provide multi-table commit/rollback, read-own-writes and rollback-only behavior after caught database errors.
- File mode uses bounded versioned snapshots and checksummed WAL frames, exclusive ownership and explicit checkpoints. Unknown commit outcomes require recovery.
- Correctness evidence includes 111 tests, codec corruption/truncation checks, reference-map CRUD, generated mapping and legacy-adapter contracts, injected I/O errors and child-JVM termination at nine commit/checkpoint boundaries. Linux/macOS CI also executes checked memory/storage/model workloads and a sample reopened in a second JVM.
- Resource evidence includes artifact inventories, fresh-process startup/RSS, per-operation allocation, durable write volume and WAL/snapshot recovery. The runtime JAR is 119.1 KiB and contains no native archive entries; JVM and Kotlin dependencies are separate costs.

See the [storage contract](transactions-and-persistence.md), [memory optimization evidence](baselines/2026-10-08-constraint-validation/README.md), and [durable measurements](baselines/2026-10-08-durable-storage/README.md) for exact scope and limitations.

The product purpose explicitly concerns Kotlin applications. The [prepared H2 comparison](baselines/2026-10-08-kotlin-model-query/README.md) measures the cost of complete Kotlin model results, using direct manual construction on H2 and generated construction on KoKoDB. Generated mapping now reads validated stored values directly, eliminating per-result column maps and Row objects; its scope remains complete mapped models, not arbitrary DTOs. Optional [compile-time SQL validation](compile-time-sql-validation.md) is a separate build-time feature and does not change this runtime measurement.

## Reduce repeated table recording before expanding SQL breadth

The 1,000-row durable fixture wrote 6,972,995 WAL bytes under individual commits, compared with a 13,981-byte final snapshot. One transaction wrote only 13,961 WAL bytes, but requires a different acknowledgment/rollback boundary. The table after-image representation repeats state during both recording and recovery.

- Evaluate row or page deltas with stable identities and an explicit format/version transition. Keep SQL replay and runtime object serialization out of the log.
- Preserve full transaction recovery, schema validation, torn-tail handling and unknown-outcome behavior. Carry forward the same fault/termination matrix before accepting a new representation.
- Measure WAL bytes, allocation, commit latency and replay time for both individual commits and grouped writes. Include UPDATE/DELETE and key changes rather than optimizing only append workloads.
- Define checkpoint thresholds only after measuring workload growth and failure behavior; retain explicit checkpoints and never reset WAL before the corresponding snapshot is durable.

## Reduce scans while preserving atomic publication

The current executor still scans rows and copies replacement lists. Skipping redundant primary-key sets reduced non-key UPDATE allocation by 92.3% at 5,000 rows, but did not change O(N) complexity.

- Evaluate a primary-key lookup structure rebuilt from validated stored rows, with transaction-private updates and atomic publication.
- Verify lookup results against scans after inserts, key moves, deletes, rollbacks, checkpoints and recovery. Detached results must retain their current behavior.
- Measure lookup/update cost and index memory across sizes; do not trade a small runtime artifact for unmeasured retained heap.

## Qualify a release using explicit costs and guarantees

- Define a first release's supported platforms, SQL subset, public API and cross-version storage compatibility. Migration behavior must be specified before promising older-file support.
- Extend storage fault qualification beyond process termination where practical. State filesystem/device assumptions and distinguish hardware power loss from JVM termination.
- Run a comparable established-engine workload with equivalent schema, transaction boundaries, synchronization, JVM settings and result materialization. Inventory native dependencies separately. Publish raw observations before claiming a deployment or performance advantage.
- Establish repeatable benchmark methodology for smaller effects and distributions; current batches are exploratory and do not establish request percentiles or performance thresholds.
- Complete release metadata and publishing setup. Keep unreleased examples usable from the sample checkout until artifacts are actually available.

Joins, richer expressions, nullable/additional types, foreign keys, general DTO mapping, schema migration and broader concurrency remain separate feature work. Each needs a stated use case and correctness contract rather than an expanding list of nominal SQL keywords.
