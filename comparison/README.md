# Kotlin Model Query Comparison

This development-only module compares the cost of returning Kotlin models from KoKoDB and H2. It does not add H2, JMH or JDBC to the runtime library.

## Product hypothesis

Kotlin applications need relational querying and detached domain objects. KoKoDB owns its generated mapping and execution path, so it can eliminate intermediate representations instead of adding a mapping layer over a general JDBC result interface. That is a hypothesis to test, not an assumption that a Kotlin implementation is faster.

The comparison uses [H2 2.5.252](https://github.com/h2database/h2database/releases/tag/version-2.5.252), verified against the official release and Maven artifact metadata, and [OpenJDK JMH 1.37](https://github.com/openjdk/jmh). H2 is already a pure-Java embedded database. Neither native-binary absence nor server-free operation is an H2 differentiator.

## Equivalent result contract

- Both return a complete `List<BenchmarkUser>` with the same public Kotlin data class, INT primary keys, non-null String names and cardinalities 1, 100 and 1,000.
- Both use independent, sequential, in-memory databases. No durability or concurrency advantage is claimed from these measurements.
- SQL is `SELECT id, name FROM benchmark_users`. KoKoDB uses the ordinary generated model API with per-call validation/parsing. H2 reuses a prepared statement and maps columns by numeric position directly into the same Kotlin constructor.
- H2 uses neither reflection nor an ORM. Its ArrayList is pre-sized using the known fixture cardinality, giving it an efficient manual mapping baseline. Default H2 prepared/result-cache behavior remains enabled.
- Fixture population is outside timing. Every model's content and unique key is verified before/after each JMH trial. The returned list is consumed by JMH to prevent unused-result elimination.
- The measured operation includes SQL execution and full list/model materialization. It is not a standalone mapper benchmark, a write test, or a point-lookup comparison.

## Reproduce

Requires JDK 17 and Python 3.9 or later:

```sh
./gradlew build :comparison:smoke :comparison:installDist
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --output comparison/results/current
```

Default JMH settings: one thread, three independent forks per engine/size, five one-second warmup iterations and five one-second measurement iterations per fork, AverageTime in us/query, and the GC allocation profiler. Forks use `-Xms64m -Xmx256m -XX:-UsePerfData`. Six parameter combinations take approximately three minutes plus setup.

`--quick` uses one fork and short iterations for diagnostics; it must not be used as final comparison evidence. The timing-free `:comparison:smoke` fixture verifies both engines at all sizes in CI.

The runner retains full JMH logs/JSON, iteration observations and confidence intervals, summary CSV, source/artifact hashes and JVM/environment metadata. Ad hoc output is ignored; selected results belong in `docs/baselines/`.

## Claims this cannot establish

- A single warmed full-table model query does not prove that KoKoDB is a better general SQL engine or substitute for H2's wider feature set, optimizer and concurrency capabilities.
- JMH AverageTime scores are mean time per query, not request p95/p99. GC-profiler bytes are allocation, not retained database heap or whole-process RSS.
- Both engines and the harness are on the measurement classpath. Artifact inventories list them separately; the entire benchmark distribution is not a KoKoDB consumer dependency set.
- A generated mapper can also be built over JDBC. Any measured difference belongs to these execution paths and contracts, not an assertion that H2 can never serve Kotlin applications efficiently.
- Compiler SQL validation, offline synchronization and other proposed directions are not implemented by this experiment. A differentiation claim needs reproducible results and a relevant application workload, not just language branding.

## Point queries and mixed reads/writes

```sh
python3 comparison/measure.py --java "$JAVA_HOME/bin/java" --suite points --output comparison/results/points
```

The point suite uses 1,000/10,000 rows, cycling hit/miss keys and a separate UPDATE-then-read operation. H2 reuses prepared SELECT/UPDATE statements and maps by column position into the same Kotlin class. KoKoDB uses its ordinary SQL calls. Both are independent sequential memory databases; each update is its own atomic statement. Population and full-content verification are outside timing. Each case uses three forks, three one-second warmups, and three one-second measured iterations with GC allocation profiling.

Use `--runtime /path/to/older-kokodb.jar --engines kokodb` for an API-compatible old runtime with the exact same harness. `--rows 1000` narrows a run; it does not change the result contract. The default `--suite models` retains the full-table benchmark and its original five-iteration settings. `--quick` remains diagnostic only.

`comparison.IndexLifecycleProbe` separately records the first, second and third lookups on a populated 10,000-row database, with thread allocation and content checks. The append index may already exist after population; those phases do not imply a scan or an index build. Run it from the installed comparison classpath. It discards two setup rounds and records three rounds, but its individual timing observations are exploratory and cannot replace forked JMH results or establish a tuned admission threshold.

## Independent correctness qualification

The [seeded differential checker](../docs/differential-qualification.md) compares KoKoDB, H2 and an independent map model. It checks SQL results, affected counts, transaction outcomes and KoKoDB file reopen/checkpoints. It is separate from these performance comparisons and adds no runtime dependency.

```sh
./gradlew :comparison:differential :comparison:installDist
python3 comparison/qualify.py --java "$JAVA_HOME/bin/java" --output .local/qualification/current
```

## Coroutine contract diagnostics

`./gradlew :comparison:coroutineStudy` runs controlled worker-admission, cancellation and eager-Flow checks. `./gradlew commitCostStudy` attributes append/force calls in the existing synchronous file path and verifies reopened contents. These optional studies support the [coroutine proposal decisions](../docs/coroutine-proposals.md); they are not integrated coroutine, snapshot-streaming or group-commit benchmarks.

The comparison module includes kotlinx-coroutines-core 1.10.2 only for these diagnostics. The core dependency graph and public API are unchanged. Artificial scheduling windows are not CI performance gates; normal builds compile the study sources.

## Warm INSERT transactions

```sh
./gradlew :comparison:installDist
comparison/build/install/comparison/bin/comparison comparison.InsertBatchBenchmark -foe true -rf json -rff comparison/results/inserts.json
```

This KoKoDB-only JMH workload checks the warm memory INSERT path at 1,000 and 16,000 rows with INT/TEXT primary keys. It uses three JVM forks, three one-second warmups and three one-second measurement iterations. One measured operation is one transaction inserting the requested rows; table initialization and complete row verification occur outside timing on each invocation. It does not compare engine durability or claim H2 superiority. [The INSERT study](../docs/baselines/2026-10-21-insert-scaling/README.md) uses the same harness with before/after runtime JAR substitution and retains the artifacts and metadata. Thread allocation and approximate retained heap are recorded by the separate benchmark-module harness.
