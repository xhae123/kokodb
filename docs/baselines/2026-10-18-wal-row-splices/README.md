# WAL Row-Splice Measurements

Version-2 row splices reduce durable recording and allocation for point mutations while retaining one force and acknowledgment per committed transaction. They do not remove the in-memory O(N) row-list copy, key validation, or synchronization cost.

## Revisions and method

- Before: `2d4cedebf45e7c7b19e96dd3ba3748312626710f`, version-1 full-table WAL, runtime JAR 121,928 bytes.
- After: `b6aad6e309da2a78f4f4ba55d65883ac751b0c37`, version-2 splices, runtime JAR 131,379 bytes. The additional 9,451 bytes include migration and validation code; no runtime dependency was added.
- Same macOS/ARM64/APFS machine and JDK 17.0.20.1. Three independent JVM forks per variant, `-Xms64m -Xmx256m -XX:-UsePerfData`. Environment files contain exact source/artifact hashes and command lines.
- INSERT uses the existing storage harness: one warmup plus three recorded batches per size/mode in each fork. Point mutations use the same new harness JAR against both runtime JARs: 20 separate transactions per operation at 100, 1,000, and 10,000 initially populated rows, with full-content verification after each reopen.
- Before/after runs are sequential, not randomized or simultaneous. Metrics below are medians of batch averages, not request percentiles. The point-mutation harness has no separate per-operation warmup. Treat latency changes as exploratory.

## 1,000 separately committed INSERTs

| Metric | Before | After |
|---|---:|---:|
| WAL bytes excluding header | 6,972,995 | 96,890 |
| Calling-thread allocation, bytes/INSERT | 173,581.5 | 8,565.2 |
| Batch-average ms/INSERT | 4.0069 | 4.0046 |
| WAL reopen, ms | 100.099 | 38.685 |
| Final snapshot bytes | 13,981 | 13,981 |

WAL volume fell **98.61%** and allocation fell **95.07%**. Durable INSERT latency was effectively unchanged: each transaction still forces the WAL. The observed reopen improvement includes file-open/synchronization and replay in an already-running JVM, not fresh-process startup. Recovery still copies row lists and validates keys.

One transaction containing all 1,000 INSERTs recorded 13,961 -> 13,973 bytes: the splice format adds 12 metadata bytes when no unchanged rows can be omitted. Its different acknowledgment/rollback boundary must not be presented as an equivalent per-transaction speedup.

## Point mutations at 10,000 initial rows

| Operation | WAL bytes/op before | WAL bytes/op after | Allocation bytes/op before | Allocation bytes/op after |
|---|---:|---:|---:|---:|
| Non-key UPDATE | 148,961 | 95.5 | 3,374,481.6 | 45,176 |
| Primary-key move | 148,961 | 96 | 3,717,980 | 576,469.6 |
| DELETE | 148,803.5 | 83 | 3,311,530.4 | 172,496 |

UPDATE names vary in encoded length, giving fractional batch-average bytes. DELETE acts on a shrinking table. Key moves still validate the complete candidate key set. Dispersed edits within a transaction may include unchanged middle rows in their splice and are not represented by this point-mutation table.

## Reproduction and retained evidence

```sh
./gradlew build :benchmarks:installDist
python3 benchmarks/measure_storage.py --java "$JAVA_HOME/bin/java" --output benchmarks/results/storage
python3 benchmarks/measure_mutations.py --java "$JAVA_HOME/bin/java" --runtime build/libs/kokodb-0.1.0-SNAPSHOT.jar --output benchmarks/results/mutations
```

To reproduce the comparison, build the before runtime at its revision separately and pass its JAR to the same point-mutation runner. Build the corresponding revision's storage harness for its frame version. Raw INSERT observations/logs are in `before/` and `after/`; point mutations are in `mutations-before/` and `mutations-after/`. The detailed local decision and failure record is `.local/improvements/2026-10-18-wal-row-splices/` in the development workspace.

Process RSS in the raw metadata is whole-harness peak RSS, not retained database heap. No power-loss test, H2 durability comparison, request-percentile claim, or coroutine benefit is established by this experiment. Correctness qualification separately includes 122 tests, all-byte corruption/truncation checks, randomized transactions, nine commit/checkpoint process-crash points, and six legacy-migration process-crash points.
