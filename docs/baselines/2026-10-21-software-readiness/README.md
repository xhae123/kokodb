# Software Qualification at the First Developer Release Boundary

The implemented subset is suitable for a first developer preview for small Kotlin/JVM applications. That judgment covers the documented synchronous API and ordinary memory workloads; file storage remains experimental under its explicit filesystem and recovery assumptions. It is not a recommendation to replace an established production database holding the sole copy of important data.

No artifact was published, version tagged, license changed or deployment performed. This assessment concerns software behavior and measured costs only.

## Why this boundary is useful

An application can declare models once, use SQL for typed results or raw rows, perform atomic CRUD and synchronous multi-table transactions, and explicitly checkpoint/reopen a file store. The current grammar supports non-null INT/TEXT, a single optional caller-supplied primary key, complete-model projections and one equality predicate. This is usable for small local datasets whose queries fit that subset. Joins, nullable values, general expressions, automatic migrations and concurrent independent transactions are not part of it.

The release decision follows actual repaired defects and checked consumer workflows, rather than test counts alone:

- Earlier accepted durable growth could exceed checkpoint capacity; commits now reject such growth before touching WAL.
- Repeated key validation made replay and live INSERT quadratic. Private replay validation and bounded immutable append indexes address the measured workloads while retaining atomic snapshots.
- Failed explicit shared startup could silently fall back to a different memory database; subsequent calls now remain blocked until an explicit retry succeeds.
- Temporary cleanup and ownership-close errors could hide the original failure; primary errors now survive with later failures suppressed.
- Packaged model discovery now has a 15-state multi-module incremental consumer gate, including direct last-source deletion and recovery after duplicate declarations.

The individual [INSERT](../2026-10-21-insert-scaling/README.md), [storage lifecycle](../2026-10-21-storage-lifecycle/README.md), [recovery](../2026-10-18-recovery-scaling/README.md), [startup](https://github.com/xhae123/kokodb/pull/43) and [consumer](../2026-10-21-processor-consumer/README.md) records preserve their evidence and tradeoffs. Older measurements retain their own artifact identities; they are not relabeled as measurements of the final runtime.

## Final correctness qualification

The final runtime is 162,619 bytes (158.8 KiB), SHA-256 `9b71923b86a01bd784f972125614ecb8eb21f486c8d48dadaa60b2d025f4b1b7`. Main `a143627af03801fa423f29532c0ec49a0d5947e1` contains the assessed behavior. The final local full build used the equivalent pre-squash consumer commit; its runtime bytes are identical.

| Gate | Recorded result |
|---|---|
| JUnit regression suite | 159 tests; zero failures, errors or skips |
| Extended H2 plus independent-map comparison | 12 runs, 76,188 statement attempts, 952,340 query comparisons |
| File histories in that comparison | 900 reopens, 450 checkpoints |
| Explicit transaction outcomes in that comparison | 16,440 committed, 6,096 rolled back; 4,918 expected constraint failures |
| Existing storage fault gates in the suite | 95 operation failures plus 60 lifecycle failures, three combined-failure regressions, 216 persistence-model images, four omitted-barrier controls, process termination/recovery |
| Consumer gates | SQL compiler diagnostics/invalidation; 15 multi-module model states; persistent sample in two separate JVMs |

The extended checker runs 2,500 generated steps for each of six seeds in both memory and file modes. A step may contain multiple statements. H2 is an independent semantic reference in memory, not a hardware durability oracle. Exact counts, source hashes, commands and artifacts are in `differential/`; complete logs and resulting file stores are retained locally. Each implementation PR passed Linux/macOS CI before merging.

These are finite checks of the documented contracts. They do not prove correctness for all inputs, all schedules or all devices. The [storage contract](../../transactions-and-persistence.md) distinguishes exceptions, process termination and modeled persistence from physical power loss.

## Current process and workload costs

Fifteen new JVMs per startup mode use JDK 17.0.20.1 on macOS arm64 with `-Xms64m -Xmx256m -XX:-UsePerfData`. Measurement ran after local builds/tests completed. New JVMs do not imply cold filesystem caches. Wall time includes launch/exit; RSS includes JVM infrastructure, and independently observed modes must not be subtracted as exact database-only overhead.

| Process | Median wall time | Observed range | Median peak RSS |
|---|---:|---:|---:|
| Empty harness, no database | 44.29 ms | 39.99–57.82 ms | 40,992,768 bytes |
| Open empty memory database | 47.21 ms | 44.37–66.78 ms | 42,270,720 bytes |
| Create table, INSERT, checked SELECT | 73.77 ms | 71.60–77.07 ms | 53,346,304 bytes |

The runtime also requires Kotlin stdlib (1,804,720 bytes) and annotations (17,536 bytes), which may already be present in a Kotlin app. The 18,950-byte benchmark JAR is not a runtime dependency. No listed JAR contains native library entries. These are inventory observations, not an H2 startup/RSS advantage or a claim that the JVM costs only the runtime JAR size.

Three independent CRUD JVMs produced 270 batch observations at 100, 1,000 and 5,000 rows. At 5,000 rows, exploratory median batch averages are 2.900 us/INSERT, 2.317 us/raw point hit, 3.148 us/non-key UPDATE and 22.061 us/DELETE. INSERT averages the table's growth; DELETE removes different keys from a shrinking table. The fixed-order harness includes parsing, binding, result construction and assertions. These are not individual-request percentiles, warmed JMH equivalents or durable operation times.

The DELETE batch range at 5,000 rows is 15.881–78.708 us/op, with 21,708 allocated bytes/op at the median. That variability and tail-repacking allocation remain visible. The warm JMH INSERT improvement in its separate study costs approximately 32 extra retained bytes/row and 25–30% more transaction allocation in the measured 16,000-row fixtures. Resource tradeoffs are accepted for the measured small workloads, not hidden as universal wins.

`resources/` retains all startup rows, CRUD observations/summaries, hashes and JVM settings. Measurement logs retain process RSS for each CRUD fork. No timing threshold is added to CI from these machine-specific observations.

## Deliberate limits and conditions for revisiting

| Area | Current decision | Consequence / next evidence needed |
|---|---|---|
| Memory size | Entire catalog and query result lists remain in memory | Encoded file bounds are not heap admission. Measure the application's real rows/results; no out-of-core or bounded-heap promise. |
| Large or mutation-heavy tables | Keep documented scans, tail repacking and O(N) WAL diffing | The optional append index ends at 65,536 entries. Larger/dispersed workloads need separate profiling and design work before a scaling claim. |
| Checkpoints | Caller controls explicit synchronous checkpoints | Long-lived file workloads must schedule them between transactions. Automatic policy needs acknowledgment/failure semantics and latency evidence. |
| Persistence failures | Fail closed on complete corruption; preserve acknowledged state under stated synchronization assumptions | Reordered/torn sectors, unforced garbage, false barriers, native close leaks and hardware power loss remain unqualified. No salvage or availability promise. |
| Compatibility | Document snapshot 1.0/WAL 2.0 and the specific development migration | No stable cross-release upgrade or automatic model-schema migration promise; retain closed-file backups or rebuildable data. |
| Kotlin integration | Qualify the documented Kotlin 2.3.20/KSP 2.3.10/JDK 17 JVM path | Other versions, Android, shading and arbitrary processor combinations need their own consumer evidence. |
| Adoption | Recommend a first developer preview within this scope | A general production recommendation requires broader operational and compatibility qualification, including field experience. |

These are scoped engineering decisions, not abandoned bug reports. No known reproduced state-loss or atomicity defect remains from the qualified workstreams. The recommendation would be withdrawn on a counterexample; preserve its seed/files and add a regression before widening the claim.

## Reproduce

```sh
./gradlew build :sample:run :benchmarks:run --args=smoke :benchmarks:storageSmoke :comparison:smoke :comparison:differential :benchmarks:installDist :comparison:installDist
python3 compiler-plugin/check_gradle.py
python3 processor/check_gradle.py
python3 comparison/qualify.py --java "$JAVA_HOME/bin/java" --steps 2500 --seeds 0 1 -1 2147483647 -2147483648 20261021 --output /path/to/new-qualification
python3 benchmarks/measure.py --java "$JAVA_HOME/bin/java" --runs 15 --forks 3 --output /path/to/new-resource-results
```

Run performance measurements after all local builds and correctness checks finish. The repository CI additionally runs the persistent sample twice against the same temporary database in separate JVMs. `.local/improvements/2026-10-21-software-readiness/` retains the detailed final build/test logs, decisions, raw data and exact runtime JAR.
