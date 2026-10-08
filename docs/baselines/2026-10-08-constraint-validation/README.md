# Primary-Key Validation Cost — 2026-10-08

Memory-mode CRUD measurements before and after avoiding redundant key-set construction. Both versions include the same transaction and persistent-storage implementation; disk I/O is not exercised here. The unchanged Java harness source hash, compiled artifact hashes, exact runtime commits and JVM commands are recorded in [before/environment.json](before/environment.json) and [after/environment.json](after/environment.json).

- Before runtime: `26a250a`, the verified PR #14 head (squash-merged as `87671e7`).
- After runtime: `c737b10`, the executor optimization with its correctness tests.
- Environment: macOS 26.3 ARM64, Homebrew OpenJDK 17.0.16, `-Xms64m -Xmx256m -XX:-UsePerfData`.
- Each version: 15 fresh startup processes per mode, three independent CRUD JVMs, two warmup rounds and five measured rounds per table size. Values are medians of 15 batch averages, not request percentiles.

## Why the change reduces allocation

Every stored row snapshot already satisfies primary-key uniqueness. Changing only a TEXT name cannot invalidate that property, so UPDATE no longer builds a complete key set when no primary-key column is assigned. INSERT checks the incoming key against existing rows before constructing its replacement snapshot. UPDATE that assigns a primary key still validates the complete candidate before publication.

Both operations still scan rows and copy row lists: the change reduces temporary work without changing their O(N) complexity or introducing indexes. A further 500-step reference-map test includes key changes, collisions, unconditional key updates and a primary key in the last column; existing tests retain multi-row atomicity and TEXT-key coverage.

## UPDATE one non-key value

| Rows | Before / after time (us/op) | Before / after allocation (KiB/op) |
|---:|---:|---:|
| 100 | 11.4 / 9.1 | 8.7 / 2.6 |
| 1,000 | 32.7 / 17.8 | 61.3 / 6.2 |
| 5,000 | 99.8 / 42.5 | 281.1 / 21.7 |

At 5,000 rows, median allocation fell from 287,838 to 22,262 bytes per UPDATE (92.3% lower). Median batch time fell from 99.8 to 42.5 us/op (57.4% lower in this run). Key-collision UPDATE retained full validation and measured 43.6 / 43.7 us/op. Growing-table INSERT averaged 33.1 / 11.2 us/op and 156.6 / 20.9 KiB/op at N = 5,000; that workload spans sizes 0 through N-1.

Raw data: [before observations](before/crud.csv), [after observations](after/crud.csv), their `summary.csv` files, and all six fork logs. Correctness assertions ran inside every workload.

## Deployment and startup after the change

| Metric | Result |
|---|---:|
| Runtime JAR | 115,406 bytes (112.7 KiB) |
| Native archive entries | 0 |
| Empty JVM wall time / peak RSS | 39.3 ms / 38.6 MiB |
| Open empty memory database wall time / peak RSS | 44.2 ms / 40.2 MiB |
| First-query wall time / peak RSS | 70.2 ms / 50.5 MiB |

Kotlin stdlib adds 1,804,720 bytes if not already supplied by the consuming application; annotations add 17,536 bytes. JVM binaries and the benchmark harness are excluded from library size. Full deployment inventory is in the environment JSON files.

## Limits

These are exploratory measurements, not JMH results or evidence of superiority to another database. Allocation changes align with removed code paths; timings still depend on JIT, GC, batch size and workload order. For example, the independently launched empty JVM changed from 50.0 to 39.3 ms without a relevant database code path, demonstrating run-to-run startup variation. Do not attribute that change to the optimization or subtract independently varying startup medians to claim an exact database overhead.

RSS includes the whole JVM and is not retained database heap. Processes reuse warm operating-system caches. Transaction batching, durable commit latency, WAL growth and checkpoint/recovery need separate measurements. Reproduction instructions and general limitations are in the [benchmark guide](../../../benchmarks/README.md).
