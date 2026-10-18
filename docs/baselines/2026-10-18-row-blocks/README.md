# Immutable Row Blocks and Point Writes

## Problem and decision

The primary-key index sped up reads, but a non-key UPDATE still scanned/copied the full row list and discarded an index whose keys and positions had not changed. At 10,000 rows the before runtime takes about 103 us per UPDATE followed by a complete Kotlin model lookup.

The final runtime stores rows in immutable blocks of up to 128 references, uses the primary-key position path for point writes, and preserves the position index across proven value-only updates. Point UPDATE copies one block plus the outer block references. Replay applies row splices directly to blocks and validates candidate keys before publication.

This retains atomic snapshots without an undo journal, per-row tree nodes, or a collection dependency. It is a workload tradeoff: full-table traversal can cost more, and primary-key assignments, tail deletion, diff detection and recovery key validation still do linear work.

## Memory queries and updates

| Operation / rows | Before us/op | Final us/op | H2 us/op | Before → final allocation B/op |
|---|---:|---:|---:|---:|
| hit / 1000 | 0.834 ± 0.222 | 0.695 ± 0.062 | 0.708 ± 0.077 | 2,473.3 → 2,441.2 |
| miss / 1000 | 0.728 ± 0.048 | 0.688 ± 0.075 | 0.549 ± 0.017 | 2,339.9 → 2,339.9 |
| updateThenRead / 1000 | 12.418 ± 2.213 | 1.204 ± 0.048 | 2.145 ± 0.068 | 8,599.0 → 5,146.9 |
| hit / 10000 | 0.778 ± 0.138 | 0.785 ± 0.045 | 0.707 ± 0.028 | 2,486.0 → 2,486.2 |
| miss / 10000 | 0.816 ± 0.162 | 0.766 ± 0.027 | 0.594 ± 0.046 | 2,358.7 → 2,342.1 |
| updateThenRead / 10000 | 103.148 ± 14.664 | 1.422 ± 0.133 | 2.572 ± 0.398 | 45,558.6 → 5,517.7 |

Scores are JMH mean ± reported error (99.9% confidence), three forks, three one-second warmups and three measured iterations, one caller. Both engines return the same complete Kotlin models. H2 2.5.252 uses prepared SELECT/UPDATE and direct constructor mapping; KoKoDB uses ordinary validated SQL calls. This is memory mode, with no durable acknowledgment comparison.

For 1,000 full models, before is **7.157 ± 0.130 us**, final **8.003 ± 0.134 us** (+11.8% mean), and H2 **20.060 ± 1.071 us**. This separate suite uses five warmup/measurement iterations per fork. Allocation is 29,829.6 → 29,853.6 B/query. Preserve this scan tradeoff when interpreting the point-write gain; neither case establishes general engine superiority or request p95/p99.

## Durable writes and recovery timing uncertainty

The first block prototype rebuilt a flat row list before converting it into blocks during every recovered frame. In the 1,000-autocommit fixture, median WAL reopen rose from **22.492 to 48.657 ms**. The final implementation shares unchanged blocks during replay; its measured median is **47.960 ms**. However, an unchanged-before-runtime recheck measured **50.937 ms** (48.916–53.424 ms), so the earlier 22 → 48 ms difference does not establish a causal regression. An attempted iterator specialization did not establish a recovery gain and was removed. All initial and recheck observations remain in `initial-prototype/`. No recovery speedup is claimed; these timings include variable JVM/I/O effects.

For 1,000 separately acknowledged INSERTs, median allocation is **8,624.0 → 4,781.7 B/INSERT**. Recorded WAL volume remains **96,890 bytes** and the snapshot remains **13,981 bytes**. Median batch-average insert time is 3.963 → 4.097 ms; every transaction still forces its WAL.

| Durable operation / 10,000 rows | Before → final allocation B/op | WAL B/op (both) | Before → final reopen ms |
|---|---:|---:|---:|
| update | 45,232.0 → 9,350.8 | 95.5 | 7.538 → 5.887 |
| key_move | 576,526.4 → 537,388.0 | 96.0 | 9.021 → 8.398 |
| delete | 172,552.0 → 4,076.0 | 83.0 | 19.675 → 14.429 |

Storage insertion/reopen summaries use nine batches across three fresh JVMs; mutation summaries use three JVMs with 20 separately committed operations per case. Reopen timers run inside those JVMs and include file/replay work, not fresh-JVM startup. These exploratory medians and ranges are not latency distributions; raw timing ranges, process RSS and all observations remain in the retained files. Tail DELETE in this fixture removes rows at the end and does not establish arbitrary-position deletion cost.

## Artifact and memory costs

- Before: `fbff917`, core JAR 135,526 bytes, SHA-256 `f2f130bfe5aa82f020a2ce5b033de334c263cd4df045958a773b06497b5028fb`.
- Final implementation: `ef7400ed2e3feb82fe8b5080c52ebd3b46b6934b`, core JAR **142,467 bytes**, SHA-256 `857d16c8f37fbc202ae025091c2a5b7a5173e2bc50a514d774150ef5367f944d`. No new runtime dependency.
- At 10,000 rows, row storage has 79 inner arrays and one outer array, with 10,000 row references plus 79 block references and array headers. A point update copies at most 128 row references and 79 block references, excluding the new row and query objects. This is representation accounting, not a measured retained-heap total.
- The optional key-position cache still uses 65,536 bytes of integer payload at 10,000 rows. Both blocks and index arrays are shared only where their immutability/position invariants allow it.

## Validation and reproduction

- 134 tests passed, including randomized block/splice operations, append after a shortened recovered tail, old snapshots, key collisions, rollback, storage corruption and process-crash/migration boundaries.
- Full build, sample, memory/storage smokes and both-engine query/mutation smokes passed. CI results are recorded in the associated PR.
- Runs used macOS/APFS, aarch64, JDK 17.0.20.1 and the same benchmark source hashes. JAR/source hashes, JVM flags, full JMH observations and durable batch data are retained beside this document.

```sh
./gradlew build :sample:run :benchmarks:run --args=smoke :benchmarks:storageSmoke :comparison:smoke :benchmarks:installDist :comparison:installDist
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite points --output .local/points
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite models --rows 1000 --output .local/models
python3 benchmarks/measure_storage.py --java "$JAVA_HOME/bin/java" --output .local/storage
python3 benchmarks/measure_mutations.py --java "$JAVA_HOME/bin/java" --runtime build/libs/kokodb-0.1.0-SNAPSHOT.jar --output .local/mutations
```

For the before runtime, use `comparison/measure.py --runtime <before.jar> --engines kokodb` and `measure_mutations.py --runtime <before.jar>`. For storage, copy the installed benchmark distribution and replace only its core JAR, then pass `--distribution`. The older-runtime environment's checkout commit identifies the harness checkout; the retained runtime JAR hash identifies the engine actually loaded. Never rebuild measured artifacts or run another CPU-heavy workload during a measurement.
