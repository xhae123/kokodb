# Complete Kotlin Catalog CLI Comparison

The example completes the same 1,000-entry catalog transformation and writes the same 800-entry report with either KoKoDB or H2. On this host, KoKoDB has a lower median launch-to-exit time, peak process RSS and runtime JAR payload. This is evidence for evaluating short-lived Kotlin tools with a modest in-memory SQL workload, not a measured customer adoption case or general engine ranking.

## Work performed

The [runnable CLI](../../../examples/catalog/README.md) reads a TSV manifest into Kotlin `FileEntry` objects, creates an in-memory table, imports all entries in one transaction, renames id 0, deletes the 200 generated entries, selects all retained entries as Kotlin objects, sorts by id and writes a UTF-8 report. The database closes before process exit. Every retained row and the rename are visible in the output; comparing the entire report also detects missing or extra rows after DELETE.

Both paths share input parsing, the public data class, sorting, output and process entry point. KoKoDB uses generated schema discovery and ordinary SQL in a shared transaction; H2 uses matching explicit DDL, a prepared INSERT batch, prepared updates/deletes/selects and direct constructor mapping. Both use the same non-null INT primary key and String data. H2 is not burdened with an ORM or reflection.

The fixture has 1,000 entries: 400 source, 400 resource and 200 generated. This controlled mix and one rename represent a small catalog-cleanup workflow. It is a constructed application example, not a real repository scan, production trace or statistical sample of user applications. A Kotlin collection implementation could also implement this task; this study addresses applications that already want SQL, and does not establish that SQL is necessary.

## Results

Twenty fresh JVMs per engine, paired rounds with shuffled engine order, seed 20261024. Median **[minimum, maximum]**; no outliers removed. Time includes process launch, input parsing, schema setup, transaction, typed query, Kotlin sort, report write, close and exit. It excludes Gradle compilation and the Python verifier. RSS is child-process peak resident memory, including JVM/native overhead.

| Engine | Complete CLI time (ms) | Peak RSS (MiB) | Example + runtime JARs (MiB) |
|---|---:|---:|---:|
| kokodb | 119.57 [113.99, 168.75] | 56.38 [56.05, 57.41] | 1.906 |
| h2 | 207.06 [196.29, 289.98] | 74.58 [74.22, 74.91] | 4.320 |

The median time difference is 87.49 ms (42.3% lower for KoKoDB in this fixture), and the median peak-RSS difference is 18.20 MiB. These savings matter only if this kind of task and resource boundary matter to an application. They do not establish service-request latency, steady-state retained heap, durable-write speed or cloud cost savings. The observed ranges are not confidence intervals or latency percentiles.

All 40 reports match an independently generated Python reference byte-for-byte. Both engines also reject a duplicate id appended at the end of the input without creating a report; those two negative runs are outside the performance summary. A separate one-round smoke preceded the final run and is retained locally, not pooled into these results.

[Ordered observations](observations.csv) · [Summary](summary.csv) · [Expected report](expected.tsv) · [Input fixture](../../../examples/catalog/fixtures/files.tsv)

## Deployment payload boundary

The same example JAR contains both adapters and its generated mapping. Each measured classpath has this common JAR, Kotlin stdlib and annotations, plus **only the selected engine**. The unused adapter does not require loading the competing engine; every run verifies the isolated path. JMH, coroutines, KSP processor and SQL compiler plugin are excluded. H2 is also a server-free JVM engine; that is not KoKoDB's distinction.

| JAR | Bytes | Role |
|---|---:|---|
| catalog-example.jar | 13,967 | Common example/runtime |
| kotlin-stdlib-2.3.20.jar | 1,804,720 | Common example/runtime |
| annotations-13.0.jar | 17,536 | Common example/runtime |
| kokodb-0.1.0-SNAPSHOT.jar | 162,685 | KoKoDB engine |
| h2-2.5.252.jar | 2,693,360 | H2 engine |

The totals are 1,998,908 bytes for the KoKoDB path and 4,529,583 for the H2 path. Both exclude the JDK, source/input/report files, launch scripts and container packaging. A Kotlin application may already carry Kotlin stdlib; it must not be counted twice when evaluating incremental dependencies. These totals are not minimized per-engine distributions, since the shared example JAR includes both adapters. No build-time or source-line-count productivity claim is made.

## Recorded environment and scope

- Apple M1, 8 GiB physical RAM, macOS 26.3 arm64, Homebrew OpenJDK 17.0.20.1, Kotlin 2.3.20, H2 2.5.252.
- JVM flags: `-Xms64m -Xmx256m -XX:-UsePerfData`. Both databases are sequential and in memory. The ordinary report file is written without a forced-durability contract.
- Runtime baseline `92e62f5a823bee7290e252dce7b459d37f005db5`, runtime JAR SHA-256 `0a4030e622112e128fc93b223f09e9046e553c5c56a81b96e5293841816f7ee7`. The example is new branch code; its source and compiled artifact identities are in the environment record.
- Shared interactive host; power/thermal state and external background activity are not isolated. No builds, test suites or image rendering were started by this experiment during the final measurements. New JVMs use ordinary OS caches, not cold filesystems.
- `wait4` peak RSS uses macOS bytes; the runner converts Linux KiB to bytes. Wall time uses a monotonic parent-process clock around child launch/wait.
- Output checks verify this application's result, not every database invariant. Existing atomicity, recovery and compiler qualifications remain separate gates. There is no concurrent or file-database comparison here.

[Exact commands, input/output/source/JAR hashes and environment](environment.json). Published checkout/JDK paths are normalized to `$CHECKOUT`/`$JAVA_HOME`, CSV newlines to LF; numeric observations are unchanged. Original logs, smoke output, all decisions and report copies remain under `.local/improvements/2026-10-23-application-readme/`.

## Reproduce

```sh
./gradlew :catalog-example:installDist
python3 examples/catalog/measure.py --java "$JAVA_HOME/bin/java" --output .local/catalog-measurement
```

Requires JDK 17, Python 3.9+ and Linux/macOS. Run sequentially on a quiet host. The output directory must be new. The runner creates the fixture and independent reference, retains each process observation, checks every report and rejects duplicate-input acceptance. `--runs 1` is a functional diagnostic, not benchmark evidence. The configured CI smoke does not assert performance thresholds.

The broader [engine comparison](../2026-10-23-engine-comparison/README.md) retains the single-model and point-query results favoring H2 alongside KoKoDB's full-list results. This application example does not replace those outcomes. Evaluate another application's distributions, lifetime, concurrency and durability requirements before adopting either engine on performance grounds.
