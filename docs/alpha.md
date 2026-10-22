# Alpha Scope and Acceptance

KoKoDB's software alpha is a small synchronous Kotlin/JVM relational database with tested memory transactions, experimental file storage and generated model results. It is ready for application experiments within the scope below. It is not a general production database recommendation, a published artifact or a promise of stable cross-alpha storage compatibility.

## What an alpha application can use

- `KoKoDB<Model>(sql, params)` for complete mapped results, `KoKoDB(sql, params)` for writes, and `KoKoDB.query()` for raw rows. Raw SQL needs no model processor.
- CREATE TABLE, INSERT, SELECT, UPDATE and DELETE; non-null INT/TEXT columns, one optional caller-supplied primary key, projections and one equality predicate.
- Atomic statements and explicit synchronous multi-table transactions, read-own-writes, rollback-only after caught database errors, and serialized shared calls. Independent Database handles require sequential caller access.
- KSP-generated table/model mapping and an optional static-SQL compiler check, qualified on Kotlin 2.3.20, KSP 2.3.10 and JDK 17 with Gradle 9.2.1.
- Explicit file open, forced WAL commits, checkpoints, restart recovery and a verified closed-file backup/restore procedure on the gated Linux/macOS local-filesystem path.

Models remain detached. Joins, nullable/additional types, general expressions, automatic keys, general DTO mapping, schema migration, coroutine transaction scopes, live backups and concurrent independent transactions are outside the alpha API.

## Acceptance evidence

| Gate | Evidence |
|---|---|
| Runtime regression and bounded reference comparison | 161 JUnit tests; configured H2/independent-map checks cover 3,718 statement attempts and 40,740 query comparisons across memory/file modes. |
| Failure and crash behavior | Existing 95 operation failures, 60 lifecycle failures, combined-error regressions, 216 modeled crash images, four omitted-barrier controls and child-process termination/recovery remain required CI gates. |
| Ownership correction | [Overlapping lock sidecars](baselines/2026-10-22-ownership/README.md) reproduced loss of pathname exclusion. Nonempty lock files are now rejected; foreign data and ordinary lock behavior are preserved. |
| Kotlin consumers | Located compiler diagnostics and plugin-input invalidation; 15 incremental multi-module model states, including deletion/rename, duplicate detection and recovery. |
| Operational recovery | [Closed-store consumer](baselines/2026-10-22-restore/README.md) restores three storage histories in fresh JVMs, rejects five incomplete/damaged archives and four invalid file/schema combinations, and checks a deliberate unverified-tail control. |
| Reproducible resource evidence | Current artifact identity below; earlier INSERT/recovery/query/startup studies retain their own measured binaries, raw observations and stated costs. |
| Operating contract | [File database guide](operating-file-databases.md) covers naming, ownership, explicit checkpoint policy, closed backups, restore verification, unknown commit outcomes and version evaluation. |

The current runtime is **162,685 bytes (158.9 KiB)**, SHA-256 `0a4030e622112e128fc93b223f09e9046e553c5c56a81b96e5293841816f7ee7`. The ownership guard adds 66 bytes over the prior assessment and no runtime dependency. Kotlin stdlib and annotations remain separate consumer costs. The guard performs one channel-size check during file open; no new performance advantage is claimed.

The [prior extended qualification](baselines/2026-10-21-software-readiness/README.md) retains 76,188 statement attempts, 952,340 comparisons, 900 reopens and 450 checkpoints for its recorded earlier artifact. Its numbers are not relabeled as a rerun of this revision. Warm INSERT and query results likewise remain scoped to their [measured workloads and allocation tradeoffs](baselines/2026-10-21-insert-scaling/README.md).

## Conditions and remaining boundaries

- File mode assumes successful file/directory synchronization is honored. Physical power loss, reordered sectors, garbage in unforced tails and native resource leaks are not comprehensively qualified. Complete corruption fails opening; no salvage/availability guarantee is provided.
- Acknowledged commits survive under the stated storage assumptions. Failed writes may have an unknown outcome; recover before deciding whether to retry. External callback effects are outside the transaction.
- The whole catalog and query results reside in memory. Encoded snapshot/frame limits do not bound heap. Index limits, O(N) mutation/diff paths and tail-repacking costs remain documented; no large-table scaling claim is made.
- Checkpoints are explicit and synchronous. A file application must choose and measure its maintenance schedule; closing alone does not shrink WAL history.
- Cross-alpha format changes and model-schema changes require evaluation on a verified copy. The specific snapshot 1.0/WAL 2.0 development migration does not imply automatic future upgrades.
- Keep important irreplaceable data on an established, appropriately operated database while adoption remains experimental. Broader production suitability requires operational and compatibility evidence beyond this alpha gate.

These limits define the alpha contract; they do not excuse known defects within it. A reproduced atomicity, ownership or recovery violation blocks acceptance until repaired and covered by a regression.

## Run the acceptance gates

```sh
./gradlew build :sample:run :benchmarks:run --args=smoke :benchmarks:storageSmoke :comparison:smoke :comparison:differential
python3 compiler-plugin/check_gradle.py
python3 processor/check_gradle.py
./gradlew :sample:installDist
python3 sample/check_backup.py
```

CI runs these on Linux and macOS and reopens the persistent sample in a second JVM. Finite checks do not prove every input or schedule. The local improvement record preserves exact commands, initial failures, decisions, raw outputs, artifacts and CI/merge metadata. Completing this software gate does not publish, deploy, tag a version or change licensing.
