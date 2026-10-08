# Durable Commit and Recovery Cost — 2026-10-08

Runtime source: `62452c5`, which adds the storage harness on top of PR #15's engine. The runtime JAR remains byte-identical to the optimized memory baseline: 115,406 bytes, SHA-256 `32444116eda2426c1fb43c7b4877a8e06f85b2ba32e5771eb6856aac6a2afcba`. Full artifact inventory, Java command, source hash and fork metadata are in [environment.json](environment.json).

Environment: macOS 26.3 ARM64, APFS, Homebrew OpenJDK 17.0.16, `-Xms64m -Xmx256m -XX:-UsePerfData`. Three independent JVM forks, one warmup batch and three measured batches per mode/size in each fork. Values are medians of nine batch averages.

## Commit boundaries and write volume

Both modes insert the same INT primary key / TEXT name rows into an empty checkpointed table. Autocommit acknowledges each INSERT separately; batch acknowledges all INSERTs as one atomic transaction. These are different transaction contracts within the same engine, not interchangeable durability settings or an engine-to-engine comparison.

| Rows | Mode | Commit records | Time (us/row) | Allocation (KiB/row) | WAL bytes |
|---:|---|---:|---:|---:|---:|
| 100 | autocommit | 100 | 4009.3 | 20.5 | 71,795 |
| 100 | batch | 1 | 47.9 | 2.4 | 1,361 |
| 1,000 | autocommit | 1,000 | 4302.5 | 169.5 | 6,972,995 |
| 1,000 | batch | 1 | 7.9 | 5.8 | 13,961 |

WAL bytes exclude the 40-byte header. Each record's framing, CRC32C and consecutive sequence, along with the expected number of commits, were independently checked by the harness. Insert intervals include SQL parsing, parameter maps, candidate state, serialization and WAL synchronization; table setup and its initial checkpoint are excluded.

At 1,000 rows, autocommit wrote 6,972,995 WAL bytes; the single transaction wrote 13,961 bytes, approximately 499 times less. Final snapshot size was 13,981 bytes in both cases. The write volume follows the current full-table after-image design: individual INSERTs repeatedly encode a growing table, whereas a batch records its final state once. That observation makes row/page deltas a concrete optimization target, while batching is already available when one group commit matches the application's contract.

The total measured INSERT batch was approximately 4.30 s for 1,000 autocommits and 7.85 ms for one transaction. Do not interpret the per-row batch value as latency for 1,000 individually acknowledged durable writes. It represents one acknowledgment after the entire callback.

## WAL recovery and checkpoints

| Rows | Mode | WAL reopen (ms) | Checkpoint (ms) | Snapshot reopen (ms) |
|---:|---|---:|---:|---:|
| 100 | autocommit | 15.62 | 19.31 | 9.38 |
| 100 | batch | 6.66 | 18.97 | 9.39 |
| 1,000 | autocommit | 83.33 | 17.84 | 8.87 |
| 1,000 | batch | 0.78 | 18.45 | 9.44 |

WAL reopen and snapshot reopen both use the already-running JVM, not fresh processes, and include file ownership/open/model-discovery work. Every row is verified after each reopen outside the measured interval. Checkpoints must reduce the WAL to its header without losing any rows. Recovery of 1,000 separate after-images decodes repeated table history, explaining the need to measure write volume and replay together.

Read [all 36 observations](storage.csv), [summary.csv](summary.csv) for minima/maxima, and the three `storage-fork-*.txt` logs. Reproduction and workload details are in the [benchmark guide](../../../benchmarks/README.md).

## Limits

- Exploratory fixed-order batches, not JMH results, request percentiles, steady-state storage performance or superiority claims. Small intervals are sensitive to JIT, GC and scheduling. Reopen timings use warm filesystem caches and vary substantially across rounds.
- Java `force(true)` and directory synchronization rely on the filesystem/device honoring their contract. No hardware power-loss simulation was performed.
- Temporary files use APFS in this run; results are not extrapolated to other devices or filesystems. Linux/macOS correctness CI runs timing-free storage fixtures rather than performance thresholds.
- Peak RSS in metadata includes JVM overhead and the harness's WAL-reading buffer. It is not retained database heap. The runtime does not contain the harness or processor.
- Grouping transactions changes acknowledgment and rollback boundaries. Applications requiring independent commits retain the individual-write cost until the storage representation is improved.
