# Seeded SQL and Recovery Qualification

Targeted tests can share assumptions with the implementation they test. This checker runs deterministic operations through KoKoDB, H2 prepared statements and a separate map model, so affected counts and complete data contents have two independent reference paths.

## Compared behavior

- Two non-null tables use INT and TEXT primary keys, String values and an INT grouping column. Initial population puts 300 rows in each table, crossing immutable block boundaries.
- Generated operations cover INSERT, point and grouped UPDATE/DELETE, primary-key changes, whole-table updates/deletes, empty results and duplicate-key failures. Values include empty strings, apostrophes, Unicode, NUL and parameter-like text.
- After successful statements, compare full contents, reordered projections, repeated point reads, non-key predicates and missing keys. Compare keys and values without assuming SELECT order, and also compare row counts to reject accidental duplicates.
- Explicit transactions cover both tables, read their own writes, commit normally, roll back on a deliberate callback exception, or roll back after a constraint failure. A separate autocommit path checks statement atomicity.
- File runs reopen through WAL and checkpoint paths repeatedly. Full recovered contents must equal the committed model and the H2 connection's contents.

H2 is an independent SQL semantics reference in memory mode. KoKoDB runs separately in memory and file modes. This is **not a durability-performance comparison** between the engines. Transactional DDL and caught-error continuation differ between their contracts, so schema setup occurs outside compared data transactions and a failed data statement aborts the whole compared transaction. KoKoDB's rollback-only rule is covered by dedicated tests.

## Reproduce and diagnose

```sh
./gradlew :comparison:differential :comparison:installDist
python3 comparison/qualify.py --java "$JAVA_HOME/bin/java" \
  --steps 1000 --seeds 17 31 20261018 --output .local/qualification/current
```

The Gradle task runs seeds 17 and 31 with 150 generated steps in each storage mode. Linux/macOS CI runs the same bounded set. The Python runner defaults to three seeds and 1,000 steps in both modes; a step is either one autocommit operation or a transaction of up to five operations. Counts include initial population and explicit rollback cases.

The runner requires an empty output directory and retains environment, source/JAR hashes, exact commands, process logs, JSON counts and file-mode data. Failures retain the seed, mode, step and last 128 operations in `failure.txt` and stderr. Reproduce a single run with the same seed/steps using a fresh directory. Seed reproducibility depends on the recorded generator source version.

## Limits

Passing seeds are finite evidence, not a proof for all SQL or schedules. This checker does not cover concurrent access, arbitrary types, live queries, hardware power loss, or interrupted commits. Existing corruption, injected-I/O and child-process crash tests remain necessary. Qualification elapsed time measures the entire checked workload and must not be reported as query latency or engine throughput.
