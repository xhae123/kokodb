# Seeded Differential Qualification

The checker completed three seeds (17, 31, 20261018), each with 1,000 generated steps in memory and file modes, using H2 2.5.252 and a separate map model as references.

| Observed check | Total across six runs |
|---|---:|
| attempted statements | 17,244 |
| query comparisons | 209,950 |
| committed transactions | 3,238 |
| rolled back transactions | 1,280 |
| constraint failures | 1,074 |
| reopens | 183 |
| checkpoints | 93 |

Every affected count and checked row set agreed. Constraint failures were expected duplicate-key outcomes; no unexpected mismatch occurred. A query comparison checks one query's rows against both the reference map and H2, and is not a count of individual scalar assertions. Statement attempts include initial population, successful operations and expected failures.

The smaller CI fixture also passed locally: seeds 17/31, 150 steps each in memory/file modes (3,718 attempted statements and 40,740 query comparisons). CI runs the same bounded fixture on Linux and macOS.

See [the coverage and reproduction contract](../../differential-qualification.md). `environment.json` retains exact commands, source/JAR hashes, JVM and platform; `results.json` and per-process logs retain every run's counters. File-mode databases and detailed decisions remain under `.local/improvements/2026-10-18-release-qualification/`.

The harness implementation was committed as `0637b37`; runtime contents match the qualified immutable-row-block implementation. Elapsed qualification seconds include all checks, setup, file work and possibly unrelated compilation; they are not database latency or performance evidence. These six successful seeds do not establish exhaustive correctness, arbitrary concurrency or hardware power-loss guarantees.
