# INSERT Scaling With Immutable Primary-Key Indexes

Repeated INSERTs scanned every existing primary key. A batch growing to N rows therefore repeated quadratic validation work even though each statement only added one row. The replacement keeps an immutable AVL key-to-position index during sustained append runs, sharing unchanged nodes across transaction snapshots and copying the changed path.

The index is optional: it starts after two prior appends and at least 32 rows, retains at most 65,536 entries, and is invalidated by deletion or primary-key changes. Value-only UPDATE can retain it. Cold construction sorts positions and costs O(N log N); row-block append copying and WAL diff detection are separate costs. The [execution contract](../../query-execution.md) documents the fallback and memory boundaries.

## Warm memory INSERT time

One operation inserts all requested rows in one explicit transaction. Both runtimes use the identical SQL, deterministic non-monotonic keys, harness and JVM settings. Each parameter combination has three fresh JMH forks, three one-second warmups and three one-second measurement iterations. Table initialization and complete result verification are outside timing.

| Key type | Rows | Before, ms/transaction | After, ms/transaction |
|---|---:|---:|---:|
| INT | 1,000 | 2.434 ± 0.302 | 0.638 ± 0.034 |
| INT | 16,000 | 533.159 ± 41.628 | 12.897 ± 1.624 |
| TEXT | 1,000 | 3.916 ± 0.130 | 1.017 ± 0.314 |
| TEXT | 16,000 | 925.837 ± 163.515 | 17.018 ± 1.065 |

The errors are JMH-reported 99.9% confidence intervals, not request percentiles. This is a KoKoDB before/after comparison, not an H2 INSERT comparison, cold startup measurement or durable-write claim. Raw observations are in `jmh-insert-before.json` and `jmh-insert-after.json`.

## Allocation, retained heap and durable batches

The separate INSERT harness records three fresh JVMs and three observations per configuration, with four 1,000-row memory warmups per key type. It measures thread allocation inside the transaction and an approximate forced-GC heap delta while the database is live. Every row is verified, and file workloads reopen and verify the same rows. These fixed-order observations have less controlled warmup than JMH.

| 16,000-row workload | Median allocated bytes, before → after | Median approximate retained-heap delta, before → after | WAL bytes, both |
|---|---:|---:|---:|
| Memory INT | 30,750,696 → 39,213,200 | 1,602,544 → 2,115,256 | — |
| Memory TEXT | 31,390,704 → 39,374,064 | 2,498,536 → 3,010,552 | — |
| File INT | 31,783,088 → 39,882,096 | 1,602,544 → 2,114,976 | 128,208 |
| File TEXT | 36,984,952 → 45,096,336 | 2,498,536 → 3,010,712 | 351,923 |

The tradeoff is explicit: path-copy allocation increases roughly 25–30%, and retained nodes add approximately 32 bytes per row on this JVM. The bound limits entry count rather than all heap/key bytes. Across these whole-process fixtures, median peak RSS grows from 90,849,280 to 119,865,344 bytes; that includes JIT, verification, recovery and all configurations, not only database state.

File time includes one commit force for the entire batch. Its exploratory 16,000-row medians are 975.023 → 98.266 ms for INT and 2,393.993 → 132.808 ms for TEXT. WAL sizes include initialization and the batch frame and are identical across runtimes. This does not remove synchronization from commit or compare different transaction boundaries.

The exploratory 1,000-row memory INT timing regressed from 5.761 to 10.086 ms. We retained that result and ran the separately warmed JMH experiment rather than discarding it or tuning an admission threshold to hide it. The warmed comparison improves at that size; the two experiments measure different warmup conditions. No cold-path latency guarantee follows. CSV files retain every raw observation, range and summary.

## Point-query regression check

The unchanged point-query harness uses 10,000 rows and three forks, returning complete Kotlin models. H2 uses reused prepared statements and direct constructor mapping. All paths are sequential and in memory. The H2 results provide context for these point operations, not for the INSERT speedup.

| Operation | KoKoDB before, us/op | KoKoDB after, us/op | H2, us/op |
|---|---:|---:|---:|
| Hit | 0.838 ± 0.053 | 0.976 ± 0.376 | 0.778 ± 0.037 |
| Miss | 1.066 ± 0.346 | 0.758 ± 0.042 | 0.670 ± 0.113 |
| UPDATE then read | 1.500 ± 0.122 | 1.668 ± 0.669 | 2.825 ± 0.419 |

Hit and UPDATE/read means rise; their intervals overlap substantially. This is not evidence of universal read improvement or exact equality. We accept possible modest point-operation cost and the measured memory increase in exchange for the sustained-INSERT improvement. H2 remains faster in the measured hit/miss means. JMH JSON and CSV include allocation and individual observations.

## Artifacts and correctness

- Before: main `4d9e83c9136f3f115bc5ff2a4382d4a3a07b62df`, 152,703-byte runtime, SHA-256 `0a0e7b041fcb47152c2078e038cb1ec7704ebc1eaaafe91cb59bdf5fb67bfa00`.
- After: 160,704-byte runtime, SHA-256 `b432aebbd106f3ca6765b8ae44e3353e27d2c41a43049e5fd640e7a08deaec33`. The increase is 8,001 bytes; runtime dependencies are unchanged.
- Measurements preceded the implementation commit; the artifact and source hashes identify the measured revision. Exact runtime/harness JARs and original logs are retained in the local workstream. Environment JSON retains measured dependencies, source hashes and commands.
- 155 tests pass, including new ordered/reversed/shuffled insertion, persistent-version isolation, fork separation, duplicate/key-mutation/rollback, TEXT collisions/Unicode, and cache-bound checks.
- The larger H2/reference qualification passes 17,244 statement attempts, 209,950 query comparisons, 183 reopens and 93 checkpoints. These are semantic checks, not performance comparisons.
- Existing 95 injected I/O failures, 216 modeled persistence images, omitted-barrier controls and child-process crash/recovery tests remain in the full suite. WAL representation and force ordering are unchanged.

## Reproduction

```sh
./gradlew build :benchmarks:installDist :comparison:installDist
python3 benchmarks/measure_inserts.py --java "$JAVA_HOME/bin/java" --runtime /path/to/runtime.jar --output /path/to/insert-results
comparison/build/install/comparison/bin/comparison comparison.InsertBatchBenchmark -foe true -rf json -rff /path/to/jmh-inserts.json
python3 comparison/measure.py --suite points --rows 10000 --runtime /path/to/runtime.jar --java "$JAVA_HOME/bin/java" --output /path/to/point-results
```

For an old runtime in the INSERT JMH workload, launch `org.openjdk.jmh.Main comparison.InsertBatchBenchmark` with the installed distribution classpath, replacing its KoKoDB JAR with the selected runtime. Exact classpaths are recorded in `environment.json`; never put both runtimes on one classpath.

JVM flags are `-Xms64m -Xmx256m -XX:-UsePerfData`, JDK 17.0.20.1, macOS/APFS. Benchmarks ran sequentially without concurrent local builds/tests. Results are finite workload observations, not throughput promises, worst-case bounds or evidence that all remaining release questions are solved.
