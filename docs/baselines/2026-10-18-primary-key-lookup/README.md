# Lazy Primary-Key Lookup Measurements

The lazy position cache removes repeated full-table scans for primary-key equality queries. It does not eliminate SQL preparation or whole-table mutation costs. H2 remains substantially faster in the measured UPDATE-then-read workload.

## Equivalent paths and method

- Before: `15844fe` (row-splice WAL baseline), core JAR 131,379 bytes.
- After: `424e969` (lazy snapshot cache), core JAR 135,526 bytes; **4,147 additional bytes**, no new runtime dependency or file-format change.
- The same comparison harness and generated Kotlin model run against both KoKoDB runtime JARs and pinned H2 2.5.252. Every trial verifies all model contents and missing-key results outside timing.
- Independent sequential memory databases, cycling keys, complete `List<BenchmarkUser>` results. H2 reuses prepared SELECT/UPDATE statements and constructs models directly by numeric column position. KoKoDB uses ordinary SQL calls with runtime parsing/validation.
- JMH 1.37, three forks per case/engine/size, three one-second warmups plus three one-second measured iterations for points, GC allocation profiler. Full-table regression uses the existing five-iteration settings at 1,000 rows.
- Same macOS/ARM64 machine, JDK 17.0.20.1, fixed `-Xms64m -Xmx256m -XX:-UsePerfData`. Runs are sequential, not randomized. Raw per-iteration data, 99.9% confidence intervals, commands, and all JAR/source hashes are retained in each directory.

## Point results at 10,000 rows

| Operation | KoKoDB before, us/op | KoKoDB after, us/op | H2, us/op |
|---|---:|---:|---:|
| Hit | 34.974 +/- 1.078 | 0.749 +/- 0.117 | 0.712 +/- 0.031 |
| Miss | 33.795 +/- 2.345 | 0.734 +/- 0.050 | 0.644 +/- 0.177 |
| UPDATE then read | 107.162 +/- 18.877 | 96.416 +/- 3.073 | 2.654 +/- 0.745 |

Repeated hits improve approximately **46.7x** against the prior KoKoDB scan path. They do not establish an H2 lookup advantage: confidence intervals overlap, and H2 allocates less in this workload. These are warmed mean operation times, not first-read time, request percentiles, concurrency results, or durability comparisons.

| Operation | KoKoDB allocation before, bytes/op | KoKoDB after | H2 |
|---|---:|---:|---:|
| Hit | 2,656.7 | 2,432.4 | 2,140.3 |
| Miss | 2,589.8 | 2,357.9 | 2,105.7 |
| UPDATE then read | 45,393.5 | 45,536.0 | 8,270.0 |

UPDATE still scans and copies the row list. The lazy admission policy avoids rebuilding a 64 KiB index for every write/read pair, but cannot solve the underlying mutation cost. This remains a major limitation, rather than a hidden exception to an overall H2 superiority claim.

## Full-table regression and retained cache cost

At 1,000 complete models, KoKoDB measured **7.145 +/- 0.330 us before** and **7.598 +/- 0.993 us after**, with allocation stable at about **29,830 bytes/query**. The intervals overlap; this run does not resolve a small full-scan performance change. H2 measured **19.526 +/- 0.713 us** and about **29,787 bytes/query** under the direct-constructor baseline.

The 10,000-row position array contains 16,384 integers: **65,536 bytes of payload**, excluding object headers, the fixed snapshot wrapper and stored rows. The per-table payload ceiling is 4 MiB; tables exceeding the admitted size use scans. Tiny tables and first reads scan, and collision-heavy builds can fall back to scans. This payload count is not total retained heap or process RSS.

The first lookup and index-building lookup are O(N); the warmed JMH suite amortizes them during fixture verification/warmup. The separate `IndexLifecycleProbe` retains first/build/cached observations under `lifecycle/`. Across three probe processes, allocation was 2,688 bytes on the first scan, 68,208 on the build, and 2,656 on a cached query. The extra build allocation matches a 65,536-byte integer payload plus a 16-byte array header on this JVM. Its short, insufficiently warmed timings are diagnostic, not tuned-threshold evidence or substitutes for the forked measurements.

## Reproduce

```sh
./gradlew build :comparison:smoke :comparison:installDist
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite points --output comparison/results/points
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite models --rows 1000 --output comparison/results/models
"$JAVA_HOME/bin/java" -Xms64m -Xmx256m -XX:-UsePerfData -cp "comparison/build/install/comparison/lib/*" comparison.IndexLifecycleProbe
```

Build the before runtime separately and add `--runtime /path/to/old-kokodb.jar --engines kokodb` to run it through the same harness. `points-before/`, `points-after/`, `models-before/`, and `models-after/` retain raw data. `--quick` is diagnostic only.

The local record `.local/improvements/2026-10-18-primary-key-lookup/` also includes alternatives, test/profiler logs, raw JFR allocation samples, and acceptance rationale. JFR timing is excluded from comparison scores. This experiment does not qualify a production release, establish hardware durability, or demonstrate coroutine benefits.
