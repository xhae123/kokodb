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

Future evidence must cover explicit transactions, file/WAL write volume, restart recovery, and subprocess crash points as those features land. Keep correctness gates separate from environment-dependent performance observations.
