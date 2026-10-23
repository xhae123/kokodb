# Measured Reasons to Try KoKoDB

The current alpha has a smaller engine JAR and lower fresh-process cost than H2 in this small Kotlin memory workload. Its warmed 1,000-model query is faster, but H2 wins the one-model query and uses less allocation in several workloads. These results support a focused experiment in small Kotlin applications, not a general H2 replacement claim.

## The decision this study informs

A short-lived Kotlin tool or a small in-process dataset may spend more of its life initializing a database and materializing objects than running complex SQL. The useful question is whether reducing that fixed cost compensates for a narrower SQL and operational scope. Those applications are **inferred candidates**, not measured customer deployments. We do not claim cloud-bill savings, production request latency, lower long-lived retained heap, or a unique capability unavailable to H2.

The README can defend these observations on this host:

- Engine payload: **162,685 bytes versus 2,693,360 bytes**. This saves 2,530,675 bytes of engine JAR, not 94% of an entire Kotlin application.
- First result: **68.15 ms versus 140.08 ms** median for launching a JVM, creating a memory database, inserting one row, reading/validating its Kotlin model, closing and exiting. The reduction is 51.3% for this lifecycle.
- Peak process RSS in that lifecycle: **49.77 MiB versus 63.20 MiB**, including JVM/native memory. This is not the incremental heap occupied by the database.
- Warm full-list query at 1,000 rows: **7.56 versus 18.86 µs**, or 2.49 times the query rate implied by those mean operation times in a sequential fixture. Allocation remains comparable, about 29.9 versus 29.8 KB/query. This is not an application throughput or latency-percentile claim.
- One-row full-model query: **0.543 versus 0.475 µs**, favoring H2. A faster 1,000-row list does not establish a faster point-query engine.

[H2's official feature list](https://h2database.com/html/features.html) already includes pure-Java embedded operation, richer SQL/types and multi-version concurrency. The comparison therefore measures efficient H2 use rather than assigning an ORM, reflection or server requirement to the competitor. H2's broader scope is a reason to choose it when KoKoDB's alpha subset is insufficient. The pinned [2.5.252 release](https://github.com/h2database/h2database/releases/tag/version-2.5.252) was rechecked on 2026-10-23.

## Environment and artifact boundary

- Run date: 2026-10-23; Apple M1, 8 GiB RAM, macOS 26.3 arm64, Homebrew OpenJDK 17.0.20.1. One interactive host; external background activity and power/thermal state are not controlled. No competing builds/tests/rendering were started by this experiment during timing.
- Runtime source baseline: `92e62f5a823bee7290e252dce7b459d37f005db5`. The development harness was added on the research branch; its exact source hashes and compiled JAR are recorded. Runtime JAR SHA-256: `0a4030e622112e128fc93b223f09e9046e553c5c56a81b96e5293841816f7ee7`, identical to the accepted alpha artifact.
- H2 2.5.252, JMH 1.37, Kotlin stdlib 2.3.20. JVM flags: `-Xms64m -Xmx256m -XX:-UsePerfData`. Single sequential worker, memory mode, no disk durability setting to relax on either engine.
- Engine JARs are unshaded artifacts, not installed application or container sizes. The ordinary core consumer also has Kotlin stdlib (1,804,720 bytes) and annotations 13.0 (17,536 bytes): together with KoKoDB, **1,984,941 bytes**, before application code/JDK. A Kotlin application may already carry the stdlib; subtracting it twice would be wrong.
- The isolated comparison uses the same stdlib and annotations 23.0.0 (29,371 bytes, resolved by the comparison module) for both engines, plus the same 145,374-byte harness. These common costs are included in measured startup/RSS, excluded from engine-JAR comparison. No JMH, coroutine, processor/compiler JAR or competing engine is on a fresh-process classpath.
- Warm JMH runs include both engine JARs and benchmark dependencies on the classpath; they are not consumer footprint measurements. Inventories include native-library entry scans; neither engine JAR contains a native library entry.

[Artifact inventory](artifacts.json) and each suite's environment file retain bytes, SHA-256, source identities and launch commands. Published paths use `$CHECKOUT`/`$JAVA_HOME` and LF line endings; numeric observations are unchanged. Full original logs and decisions are retained in the ignored local improvement record.

## Equivalent work and timing boundaries

Both engines use `BenchmarkUser(id: Int, name: String)`, a single INT primary key and non-null text. KoKoDB uses ordinary parameterized SQL plus generated model mapping. H2 reuses prepared statements, numeric column access and direct constructor calls, with pre-sized result lists. Default H2 prepared/result-cache behavior remains enabled. No manual tuning selects an artificially weak H2 path.

**Fresh processes:** 20 paired rounds per mode, shuffled mode/engine order using seed 20261023, 160 JVMs total. `empty` prints a completion marker without opening a database. `ready` opens the memory database and model table; H2 executes matching DDL, prepares INSERT/SELECT and disables autocommit. `first` adds one committed row and a verified complete-model query. `load1000` performs that lifecycle for 1,000 rows. KoKoDB loops ordinary SQL inside one transaction; H2 uses a prepared batch and one commit. Launch-to-exit wall time includes validation and close. `wait4` records peak child RSS with platform unit conversion. These are fresh JVMs with ordinary OS caches, not cold-disk measurements.

**Warm queries:** three forks per engine/cardinality; full-model SELECT uses five one-second warmup and five measurement iterations, point SELECT/UPDATE-read three of each. Fixture population and complete content verification happen outside timing. Returned lists are consumed by JMH. Point keys cycle; UPDATE-read performs an autocommit name change and then reads it, with no concurrent observer. Point verification warms the lookup path before measurement; this is not first-index-admission latency.

**Warm INSERT:** three forks, three one-second warmups and measurements, one transaction for 1,000 or 16,000 rows. Schema creation/statement preparation and final full-content verification/close use invocation fixtures. This prevents an ever-growing table from changing the operation, but fixture effects remain. JMH [documents per-invocation timing and omission risks](https://raw.githubusercontent.com/openjdk/jmh/master/jmh-core/src/main/java/org/openjdk/jmh/annotations/Level.java), particularly below one millisecond. Consequently the sub-millisecond 1,000-row results are diagnostic and no write-speed headline is selected. GC-profiler allocation includes invocation fixture work; it cannot attribute bytes only to INSERT.

All 22 warm parameter cases (66 independent forks) are retained, including unfavorable cases. Engine/parameter order in JMH is fixed, not randomized. JMH's [profiler documentation](https://raw.githubusercontent.com/openjdk/jmh/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java) distinguishes normalized allocation from time; neither is retained heap. Reported JMH intervals describe run sampling, not uncertainty across hardware, workloads or operating conditions.

## Fresh-process results

All 160 launches passed exact row/value verification. Values are medians **[minimum, maximum]**, with every observation retained; no outlier removal. RSS is peak whole-process memory, not retained database heap.

| Mode | Engine | Wall time (ms) | Peak RSS (MiB) |
|---|---|---:|---:|
| empty | kokodb | 36.90 [35.45, 49.24] | 37.36 [37.14, 38.58] |
| empty | h2 | 36.34 [35.44, 49.36] | 37.43 [35.34, 38.67] |
| ready | kokodb | 54.82 [54.24, 68.20] | 46.04 [45.92, 46.19] |
| ready | h2 | 138.25 [135.45, 139.44] | 62.69 [61.70, 62.84] |
| first | kokodb | 68.15 [67.34, 83.59] | 49.77 [49.55, 49.88] |
| first | h2 | 140.08 [139.23, 142.03] | 63.20 [63.02, 63.30] |
| load1000 | kokodb | 89.84 [88.85, 105.85] | 53.80 [53.55, 54.16] |
| load1000 | h2 | 169.59 [168.34, 175.53] | 66.72 [66.42, 66.78] |

Raw [ordered observations](startup/observations.csv), [summary](startup/summary.csv), and [exact classpaths/artifacts](startup/environment.json). Empty-process runs are context, not a quantity subtracted from other modes.

## Warmed query results

Mean microseconds per operation ± JMH 99.9% confidence interval half-width. B/op is allocation, not retained heap. Each case uses three forks; full-model queries have 15 measured iterations in total, point workloads nine. Complete per-fork samples and profiler metrics are in the JSON files.

### Models

| Case | Rows | Engine | Mean ± CI (µs/op) | B/op |
|---|---:|---|---:|---:|
| models | 1 | kokodb | 0.543 ± 0.008 | 1,837.3 |
| models | 100 | kokodb | 1.029 ± 0.005 | 4,549.3 |
| models | 1,000 | kokodb | 7.560 ± 0.155 | 29,858.9 |
| models | 1 | h2 | 0.475 ± 0.003 | 1,776.0 |
| models | 100 | h2 | 3.373 ± 0.180 | 4,544.0 |
| models | 1,000 | h2 | 18.859 ± 0.040 | 29,758.1 |

[models JMH observations](models/jmh.json) · [summary](models/summary.csv) · [environment](models/environment.json).

### Points

| Case | Rows | Engine | Mean ± CI (µs/op) | B/op |
|---|---:|---|---:|---:|
| hit | 1,000 | kokodb | 0.790 ± 0.006 | 2,494.6 |
| hit | 10,000 | kokodb | 0.773 ± 0.061 | 2,496.9 |
| hit | 1,000 | h2 | 0.689 ± 0.026 | 2,179.0 |
| hit | 10,000 | h2 | 0.707 ± 0.009 | 2,140.2 |
| miss | 1,000 | kokodb | 0.736 ± 0.049 | 2,350.6 |
| miss | 10,000 | kokodb | 0.753 ± 0.050 | 2,363.3 |
| miss | 1,000 | h2 | 0.542 ± 0.004 | 2,010.2 |
| miss | 10,000 | h2 | 0.599 ± 0.055 | 2,091.8 |
| updateThenRead | 1,000 | kokodb | 1.378 ± 0.247 | 5,099.0 |
| updateThenRead | 10,000 | kokodb | 1.338 ± 0.017 | 5,535.4 |
| updateThenRead | 1,000 | h2 | 2.120 ± 0.070 | 7,783.9 |
| updateThenRead | 10,000 | h2 | 2.368 ± 0.081 | 8,217.7 |

[points JMH observations](points/jmh.json) · [summary](points/summary.csv) · [environment](points/environment.json).

## Warmed INSERT results

One operation is one complete memory transaction into an empty table. Mean microseconds ± JMH 99.9% confidence interval half-width. **The 1,000-row operations are below the recommended duration for invocation fixtures; treat these rows as diagnostic, not headline speedups.** Invocation setup/verification is excluded from timing, but influences GC/caches; B/op includes fixture activity and is not INSERT-only allocation.

### Inserts

| Case | Rows | Engine | Mean ± CI (µs/op) | B/op |
|---|---:|---|---:|---:|
| insert | 1,000 | kokodb | 549.937 ± 6.530 | 2,212,134.7 |
| insert | 16,000 | kokodb | 9158.729 ± 104.290 | 40,931,874.6 |
| insert | 1,000 | h2 | 527.487 ± 39.980 | 2,063,456.3 |
| insert | 16,000 | h2 | 14916.675 ± 5496.107 | 36,106,012.2 |

[inserts JMH observations](inserts/jmh.json) · [summary](inserts/summary.csv) · [environment](inserts/environment.json).


The 16,000-row H2 INSERT estimate has a wide interval (14.92 ± 5.50 ms); do not present its mean ratio as a stable general write advantage. The 1,000-row diagnostic has overlapping intervals. The allocation figures include setup and verification, and favor H2 in this combined activity.

## What this changes in the README

Lead with a small runtime and a quick first result for Kotlin applications. Show actual engine bytes and end-to-end fresh-process costs, then the full-model win beside the one-model loss. Keep alpha limitations adjacent to the choice, and retain the complete result matrix here. Do not turn the best cardinality into a universal speed claim.

The generated mapper calls constructors without per-result column maps/Row intermediates, but these experiments compare complete paths; they do not isolate the mapper's causal contribution. A generated JDBC mapper could be built too. Kotlin implementation alone is neither a moat nor evidence of savings.

The remaining adoption evidence is application-specific: real schemas/data distributions, longer lifetimes, allocation/retained-heap profiles, concurrent access expectations, another CPU/JVM/OS and equivalent durable-write workloads. None is silently replaced with these memory results. SQL richness, operations, storage compatibility and maturity remain material tradeoffs.

## Reproduce

From this repository with JDK 17 and Python 3.9+:

```sh
./gradlew build :comparison:smoke :comparison:installDist
python3 comparison/measure_startup.py --java "$JAVA_HOME/bin/java" --output comparison/results/startup
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite models --output comparison/results/models
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite points --output comparison/results/points
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite inserts --output comparison/results/inserts
```

Use new output directories; run sequentially on an otherwise quiet host. `--quick` and `--runs 1` only verify fixtures, and their diagnostic results are excluded from this dataset. CI verifies both engine fixtures and isolated classpaths on Linux/macOS without enforcing noisy performance thresholds. [The comparison guide](../../../comparison/README.md) describes each runner and contract.

The README's [editable SVG figures and renderer](../../assets/README.md) use these checked-in observations. Bars start at zero; startup whiskers show the complete observed range, query whiskers JMH 99.9% confidence intervals. The decorative cover has no data encoding. Shopify Engineering's [YJIT article](https://shopify.engineering/ruby-yjit-is-production-ready) is a presentation reference for separating benchmarks from application evidence and naming the baseline next to each chart; its data and artwork are not reused.
