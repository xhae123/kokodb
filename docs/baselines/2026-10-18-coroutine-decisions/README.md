# Coroutine Proposal Diagnostics

These observations support the [proposal decisions](../../coroutine-proposals.md). They do not benchmark an integrated asynchronous engine, true snapshot streaming or independent group commit.

- `coroutine-study.json`: 16 controlled admission cases (two engines, four caller counts, direct/external-adapter paths), cancellation before admission and after a durable commit, and a materialized-List-to-Flow negative control. All assertions passed.
- `commit-cost-study.json`: 12 checked file workloads (100/1,000 rows, separate commits/one transaction, three rounds). Every recovered row matched. The table name is `entries`, so encoded bytes differ from the earlier `bench` fixture's table metadata; do not mix byte totals between fixtures.
- `environment.json`: actual source/JAR hashes, JDK/platform and commands. Diagnostic source was uncommitted during the run; source hashes identify the exact files retained in this PR. These sources add no public runtime API.

Reproduce on a supported local filesystem with JDK 17:

```sh
./gradlew :comparison:coroutineStudy
./gradlew commitCostStudy
```

The core runtime remains 142,467 bytes with the same qualified row-block JAR hash. The development comparison distribution adds kotlinx-coroutines-core-jvm 1.10.2 (1,479,317 bytes); a consumer already using coroutines would not necessarily pay that as an incremental dependency. Root runtime dependency resolution remains unchanged.

The admission probe deliberately stalls a transaction and uses a 100 ms heartbeat window. It does not establish real-world latency percentiles, throughput or producer-memory bounds. The force attribution is sequential, single-JVM, three-round exploratory data. Its explicit-transaction comparison changes atomicity/acknowledgment boundaries and must not be called group commit. Detailed logs, source drafts and decisions remain under `.local/improvements/2026-10-18-issue-resolution/`.
