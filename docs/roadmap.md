# Development Roadmap

## Current boundary: a durable development alpha

KoKoDB now has a complete path from SQL parsing to relational execution, generated Kotlin result mapping, memory transactions, forced file commits, checkpoints and validated restart recovery. This is the foundation of a small embedded database; it is not a general SQL engine or a published production release.

- An optional Kotlin/JVM compiler plugin checks static SQL grammar and query/write categories at direct calls; dynamic SQL and schema/binding semantics remain runtime-validated.
- CRUD supports non-null INT/TEXT columns, one optional caller-supplied primary key, projections and single equality predicates.
- Explicit synchronous transactions provide multi-table commit/rollback, read-own-writes and rollback-only behavior after caught database errors.
- File mode uses bounded versioned snapshots and checksummed WAL frames, exclusive ownership and explicit checkpoints. Unknown commit outcomes require recovery.
- Correctness evidence includes codec corruption/truncation checks, reference-map CRUD, immutable block boundaries and snapshot isolation, generated mapping and legacy-adapter contracts, 95 injected I/O failures across six storage paths, 216 modeled crash images with four omitted-barrier negative controls, and child-JVM termination at nine commit/checkpoint boundaries. Linux/macOS CI also executes checked memory/storage/model workloads and a sample reopened in a second JVM.
- [Seeded differential qualification](differential-qualification.md) compares affected counts, contents, multi-table commit/rollback and file reopen/checkpoints against H2 and a separate map model. CI uses fixed bounded seeds; passing them does not establish exhaustive SQL correctness.
- Resource evidence includes artifact inventories, fresh-process startup/RSS, per-operation allocation, durable write volume and WAL/snapshot recovery. The row-block runtime baseline is 139.1 KiB; JVM and Kotlin dependencies are separate costs.

See the [storage contract](transactions-and-persistence.md), [memory optimization evidence](baselines/2026-10-08-constraint-validation/README.md), and [durable measurements](baselines/2026-10-08-durable-storage/README.md) for exact scope and limitations.

The product purpose explicitly concerns Kotlin applications. The [prepared H2 comparison](baselines/2026-10-08-kotlin-model-query/README.md) measures the cost of complete Kotlin model results, using direct manual construction on H2 and generated construction on KoKoDB. Generated mapping now reads validated stored values directly, eliminating per-result column maps and Row objects; its scope remains complete mapped models, not arbitrary DTOs. Optional [compile-time SQL validation](compile-time-sql-validation.md) is a separate build-time feature and does not change this runtime measurement.

## Bound durable recording and continue storage qualification

The [measured version-2 WAL](baselines/2026-10-18-wal-row-splices/README.md) reduced 1,000 individual INSERTs from 6,972,995 to 96,890 recorded bytes. Version-2 WAL frames encode row splices instead of full dirty tables. Point changes record replacement rows and table/frame metadata; diff detection remains O(N). Private recovery state validates changed keys incrementally and freezes tables only after successful validation of the complete WAL prefix. Dispersed edits may still record a larger interval. Version-1 files are validated and migrated through the checkpoint protocol during open.

- Keep statement and transaction boundaries, force-before-acknowledgment, torn-tail checks, and recovery-required behavior intact.
- [Checkpoint capacity qualification](baselines/2026-10-18-storage-capacity/README.md) reproduced accepted writes exceeding the snapshot limit. Commits now reject oversized growth before append; existing oversized version-2 catalogs retain an incremental shrinking path. Encoded-size accounting adds measured cost and does not establish a JVM heap bound.
- Preserve six legacy-migration process-crash points alongside the existing nine commit/checkpoint boundaries, and mixed reference-model recovery tests.
- [Recovery scaling qualification](baselines/2026-10-18-recovery-scaling/README.md) measures append-only replay and checks 5,000 mixed splices against the former immutable algorithm. Temporary replay key sets add working memory; this does not remove length-changing middle-splice costs. [Live INSERT qualification](baselines/2026-10-21-insert-scaling/README.md) separately evaluates bounded persistent key indexes and their memory tradeoff.
- Evaluate multiple edit ranges or stable row identities only when measured dispersed-write workloads justify the extra state and complexity.
- Define checkpoint thresholds after measuring workload growth and failure behavior; retain explicit checkpoints and never reset WAL before the corresponding snapshot is durable.

## Preserve measured lookup gains while reducing mutation costs

[Primary-key equality lookups](query-execution.md) build a compact position cache only after repeated lookups with unchanged keys and positions. Immutable row blocks reduce point-update copies, and value-only UPDATE retains the matching key-position cache. Mutation and recovery retain the original atomicity rules.

- Point-query comparisons include hits, misses, and UPDATE-then-read against the prior runtime and H2 prepared statements with direct Kotlin mapping.
- The [row-block baseline](baselines/2026-10-18-row-blocks/README.md) reduces the 10,000-row memory UPDATE/read mean from 103.148 to 1.422 us, while full 1,000-model materialization grows from 7.157 to 8.003 us. Recovery observations vary substantially even for an unchanged baseline; no recovery speedup is claimed.
- Tail deletion, diff detection, and primary-key mutation validation remain O(N). INSERT uniqueness uses a bounded immutable tree during sustained append workloads and otherwise retains its scan path. Block copying and cache reuse do not remove these costs.
- First-read/index-build latency, large-table cache bounds, and adversarial collision fallback must remain explicit when interpreting warm repeated-read results.

## Qualify a release using explicit costs and guarantees

- Define a first release's supported platforms, SQL subset, public API and cross-version storage compatibility. Migration behavior must be specified before promising older-file support.
- Extend storage fault qualification beyond process termination where practical. State filesystem/device assumptions and distinguish hardware power loss from JVM termination.
- The systematic I/O exception matrix covers append, force, replacement and directory sync, including partial appends. The [persistence-order model](baselines/2026-10-21-storage-power-loss/README.md) separately checks lost append prefixes and independently persisted snapshot/WAL replacements. A separate [lifecycle matrix](baselines/2026-10-21-storage-lifecycle/README.md) qualifies 60 read/open/truncate/close/delete failures and preserves primary errors during combined failures. Physical cache loss, reordered/torn sectors, metadata/directory-enumeration errors and native close failures that leave resources unreleased remain unqualified.
- Run a comparable established-engine workload with equivalent schema, transaction boundaries, synchronization, JVM settings and result materialization. Inventory native dependencies separately. Publish raw observations before claiming a deployment or performance advantage.
- Establish repeatable benchmark methodology for smaller effects and distributions; current batches are exploratory and do not establish request percentiles or performance thresholds.
- Complete release metadata and publishing setup. Keep unreleased examples usable from the sample checkout until artifacts are actually available.

Joins, richer expressions, nullable/additional types, foreign keys, general DTO mapping, schema migration and broader concurrency remain separate feature work. Each needs a stated use case and correctness contract rather than an expanding list of nominal SQL keywords.

The [coroutine proposal decisions](coroutine-proposals.md) reject the current integrated admission/lifecycle, Flow-first and coroutine group-writer proposals. Controlled diagnostics show useful external admission behavior, while true streaming and independent group commit remain unqualified. These closed proposals are not implemented features or release promises.
