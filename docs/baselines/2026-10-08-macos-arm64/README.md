# Memory Engine Baseline — 2026-10-08

Runtime source: `a753bed` (PR #10). The runtime artifact SHA-256 is recorded in [environment.json](environment.json); the benchmark source hash identifies the uncommitted harness used for this measurement. This is an exploratory baseline, not a comparison with another database.

Environment: macOS 26.3 ARM64, Homebrew OpenJDK 17.0.16, `-Xms64m -Xmx256m -XX:-UsePerfData`. Workload details and limitations are in the [benchmark guide](../../../benchmarks/README.md).

## Deployment and startup

| Metric | Result |
|---|---:|
| Runtime library JAR | 69,510 bytes (67.9 KiB) |
| Kotlin stdlib | 1,804,720 bytes |
| Annotations dependency | 17,536 bytes |
| Benchmark harness JAR | 5,111 bytes |
| Native entries in distribution JARs | 0 |

JDK/JVM binaries are excluded. Kotlin stdlib can be shared with a Kotlin consumer; the harness and KSP processor are not runtime-library dependencies.

Each startup mode has 15 independent fresh processes. Values below are medians of wall time and whole-process peak RSS, respectively:

| Mode | Wall time | Peak RSS |
|---|---:|---:|
| Empty JVM and harness initialization | 41.9 ms | 38.7 MiB |
| Open empty Database | 55.9 ms | 40.1 MiB |
| Create table, INSERT, and first SELECT | 84.5 ms | 49.5 MiB |

See [startup.csv](startup.csv). The processes do not have cold OS caches. Subtracting independently measured medians is not a precise database-only cost estimate.

## Workload cost

Three independent JVM forks, two warmup rounds and five measurement rounds per size in each fork. Values are medians of 15 batch averages:

| Rows | SELECT hit | UPDATE one row | UPDATE allocation |
|---:|---:|---:|---:|
| 100 | 3.3 us/op | 11.1 us/op | 8.6 KiB/op |
| 1,000 | 9.3 us/op | 31.5 us/op | 61.3 KiB/op |
| 5,000 | 34.2 us/op | 103.6 us/op | 281.1 KiB/op |

See [summary.csv](summary.csv), [all observations](crud.csv), and each `crud-fork-*.txt` log. Counts, results, primary-key rejection, and final cardinality were checked during every fork.

The executor scans predicates and builds replacement row lists; UPDATE also validates the complete primary-key set. The growing allocation per UPDATE is consistent with those code paths. This points to a future optimization target while preserving atomicity; it is not evidence that this engine outperforms an existing engine.

Transactions, persistent I/O, recovery, concurrent workloads, and generated typed mapping are outside this baseline. Add separate measurements as those paths become available.
