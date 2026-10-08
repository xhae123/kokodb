# Kotlin Model Query Cost — 2026-10-08

## Why measure the model boundary

Our product goal is to reduce unnecessary resource and complexity costs for Kotlin applications using relational data. H2 is already a pure-Java embedded database, so a server-free JVM implementation is not an H2 differentiator. Owning generated Kotlin mapping and execution gives us an opportunity to remove intermediate representations, but the opportunity must be measured.

The comparison deliberately gives H2 an efficient path: a reused prepared statement, numeric column getters, a pre-sized ArrayList, and direct calls to the same Kotlin data-class constructor. Neither reflection nor an ORM is used. KoKoDB uses its ordinary generated model query API. Both return complete detached lists with the same schema and row content from independent in-memory databases.

## Before optimization

Runtime source: `1f60a44`; runtime JAR SHA-256 `32444116eda2426c1fb43c7b4877a8e06f85b2ba32e5771eb6856aac6a2afcba`. Benchmark source and every artifact are hashed in [before/environment.json](before/environment.json).

Environment: macOS 26.3 ARM64, Homebrew OpenJDK 17.0.16; JMH 1.37, one thread, three forks per parameter combination, five one-second warmups and five one-second measurements per fork, `-Xms64m -Xmx256m -XX:-UsePerfData`, GC allocation profiler. The values below are JMH means per complete query, not request percentiles.

| Models per query | KoKoDB time (us) | H2 time (us) | KoKoDB allocation (KiB) | H2 allocation (KiB) |
|---:|---:|---:|---:|---:|
| 1 | 0.577 | 0.498 | 2.30 | 1.73 |
| 100 | 8.108 | 3.278 | 45.23 | 4.44 |
| 1,000 | 70.958 | 19.495 | 430.69 | 29.07 |

At 1,000 rows, KoKoDB took approximately 3.6 times as long and allocated 14.8 times as much as the H2 path in this fixture. The executor builds a column map for each row, then Row copies that map before the generated adapter calls the Kotlin constructor. This identifies avoidable work; it is not evidence that writing the engine in Kotlin already provides an advantage.

Raw evidence: [JMH JSON with iteration observations and confidence intervals](before/jmh.json), [complete log](before/jmh.log), and [summary CSV](before/summary.csv). Setup and teardown verified every model's values, cardinality and key uniqueness. The timing-free comparison fixture also passed for both engines at all three sizes.

## Deployment inventory

| Runtime | JAR bytes | Native archive entries |
|---|---:|---:|
| KoKoDB | 115,406 | 0 |
| H2 2.5.252 | 2,693,360 | 0 |

Kotlin stdlib/annotations are shared consumer costs and listed separately in metadata. JMH, the comparison fixture and the build-time processor are not KoKoDB runtime dependencies. A smaller runtime artifact accompanies a much smaller SQL/concurrency feature set; size alone does not establish a better product.

## Interpretation and reproduction

See the [comparison guide](../../../comparison/README.md) for the exact operation, build commands and limitations. This is a warmed full-table SELECT/model-materialization comparison, not a measurement of writes, durable commits, point lookups, concurrency, retained heap or fresh-process startup. H2 defaults including prepared/result caching remain enabled. No claim that H2 can never achieve efficient Kotlin mapping is made.

The before data remains immutable as optimization results are added. Use the same harness, result contract, dependency version, fork settings and machine when assessing a change.
