# Resource Baselines

This module measures the cost of the existing memory engine. It is a development tool and is not a runtime dependency. The harness uses Java for JVM instrumentation; the database engine remains Kotlin.

## Reproduce

Requires JDK 17 and Python 3.9 or newer. From the repository root:

```sh
./gradlew build :sample:run :benchmarks:run --args=smoke :benchmarks:installDist
python3 benchmarks/measure.py --java "$JAVA_HOME/bin/java" --output benchmarks/results/current
```

Set JAVA_HOME to a JDK 17 installation. Defaults are 15 fresh processes per startup mode and 3 independent CRUD JVMs. `--runs` and `--forks` must be positive. Raw logs, artifact hashes, environment metadata, observations, and summaries are retained in the output directory. Ad hoc results are ignored by Git; selected baselines belong in `docs/baselines/`.

## What is measured

| Metric | Scope |
|---|---|
| JAR bytes and hashes | Every JAR in the compiled benchmark distribution, with native archive entries listed |
| Empty process | JVM startup and benchmark class initialization; no database opened |
| Open process | Fresh JVM plus model discovery and an empty memory database |
| First-query process | Fresh JVM plus table creation, one INSERT, and a checked SELECT |
| Peak RSS | Whole-process maximum resident memory, including JVM/compiler/GC infrastructure |
| CRUD batches | Raw SQL against an independent Database with two warmup rounds and five recorded rounds per table size |
| Allocation | Bytes allocated by the calling thread per operation, when supported by the JVM |

Rows have caller-supplied INT primary keys and TEXT names. SELECT hits the middle key or misses; UPDATE changes one TEXT value; collision UPDATE tries to duplicate a key; DELETE removes up to 20 distinct keys. INSERT grows an empty table to N rows, so its reported average spans table sizes 0 through N-1. SQL parsing, parameter-map creation, result materialization, and correctness checks are included. Setup for each round is represented by the INSERT workload, not charged to subsequent workloads.

The CSV summaries are medians/minima/maxima of batch averages, not individual-request percentiles. The harness consumes and checks results, affected counts, key rejection, and final cardinality. `smoke` runs a small correctness fixture in CI without timing thresholds.

## Interpretation limits

- These are exploratory workload baselines, not JMH results or performance promises. Small batches and fixed workload order can retain JIT/GC effects even after warmup; raw observations and separate forks expose some of that variation.
- New JVM processes do not imply cold filesystem caches. Timings include process launch/exit; use the empty mode as context, not a precise subtraction of independently varying costs.
- RSS is not retained database heap. It is unavailable on platforms without wait4; unsupported allocation instrumentation reports NaN. No GC-based retained-heap estimate is advertised.
- JVM options are fixed to `-Xms64m -Xmx256m -XX:-UsePerfData`. Record different options as a separate baseline. JDK/JVM binaries are excluded from JAR totals.
- Runtime JAR bytes are incremental library payload. Kotlin stdlib may already be present in the consuming application. Benchmark/sample/processor bytes are separate costs; the processor is build-time only.
- There is no transaction or disk-durability cost in the initial baseline. Comparing these figures with durable writes in another engine would be misleading.
- No conclusion about being smaller/faster than another database is justified until an equivalent engine comparison is measured.

## Durable storage measurements

Build the distribution and run the separate storage harness:

```sh
./gradlew :benchmarks:installDist :benchmarks:storageSmoke
python3 benchmarks/measure_storage.py --java "$JAVA_HOME/bin/java" --output benchmarks/results/storage
```

The default is three independent JVM forks. Each fork measures 100 and 1,000 single-row INSERTs in two modes: one durable transaction per INSERT, or all INSERTs in one durable transaction. Each mode/size receives one warmup batch and three measured batches. This is a comparison of transaction boundaries within KoKoDB, with different group atomicity, rather than an equivalent-workload comparison between engines.

Each batch starts with an empty table in a checkpointed database. Insert time/allocation includes parsing, parameters, row snapshots, WAL serialization and commit synchronization; schema creation and the initial checkpoint are outside that interval. WAL bytes exclude the initial 40-byte header. The harness independently checks commit counts, framing, sequence continuity and CRC32C checksums in the resulting WAL.

The harness closes and reopens the database to time WAL replay, verifies every recovered row, times an explicit checkpoint, and closes/reopens again to time snapshot restoration. Both reopen intervals are in the already-running benchmark JVM and exclude OS-process launch. Verification is outside the reopen and checkpoint timing intervals. After checkpoint, the WAL must contain only its 40-byte header and every row must survive a further reopen.

Raw observations, medians/minima/maxima, fork logs, environment details and artifact/source hashes are retained. Files are created on the filesystem backing the operating system's temporary directory, reported in each fork log, and removed after that fork. Peak RSS includes the JVM and benchmark's WAL-reading buffers; it is not retained database heap.

The fixed workload order and warmed filesystem/JVM affect timings. Storage synchronization depends on the OS/filesystem/device contract, and these measurements do not simulate hardware power loss. `storageSmoke` runs the same content/commit/checkpoint assertions at 10 rows in CI without timing thresholds. Fault injection and child-JVM termination remain separate correctness tests.
