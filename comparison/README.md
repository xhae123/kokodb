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
