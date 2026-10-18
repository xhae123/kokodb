# Checkpoint Capacity Correctness

Previously, individually valid WAL commits could grow the catalog beyond the 256 MiB snapshot limit. Those writes succeeded, but checkpoint could no longer compact the WAL. This change rejects unrepresentable growth before append and leaves the prior committed state usable.

## Reproduction and correction

- Baseline: `c69f6fea5113c1ad8cafc000a968a82e448854f0`, runtime 142,467 bytes. Corrected implementation: `00a1683`, runtime 146,270 bytes. Exact JAR hashes and commands are in the environment JSON files.
- `CapacityProbe.java` uses the public API to commit up to 65 INT/TEXT rows, each containing the same 4 MiB ASCII value, in separate transactions. It then attempts a checkpoint. JDK 17, macOS APFS, 1 GiB maximum heap.
- The 256 MiB limit includes schema, headers and row encoding, so 64 such rows already exceed it. Memory-mode behavior is unchanged.

| Observation | Before | After |
|---|---:|---:|
| Accepted / visible rows | 65 / 65 | 63 / 63 |
| First rejected INSERT | None | Row 64, before append |
| WAL bytes | 272,635,996 | 264,247,200 |
| Checkpoint | Size-limit error | Success |
| Snapshot bytes | 56 | 264,241,750 |

`ReopenProbe.java` subsequently opens the corrected snapshot in a separate JVM and checks the 63-row count, first TEXT length (4,194,304 characters) and last accepted ID (62). The raw output is retained. Probe elapsed times are not performance comparisons: the corrected probe overlapped other correctness validation, and the outcomes perform different checkpoint work.

## Size accounting and regression coverage

- Count the recovered catalog's encoded bytes once. Adjust that count from removed/inserted splice rows on each commit, updating it only after successful WAL force.
- Reject oversized final transaction states without altering WAL or publishing rows, including multi-table changes. Intermediate transaction states may exceed the bound when the final state fits.
- Preserve incremental repair of oversized version-2 stores written by older code: strictly shrinking commits remain allowed, even before the catalog is small enough to checkpoint. Legacy version-1 migration still requires a fitting snapshot.
- Six added tests cover exact/one-byte-over boundaries, Unicode and malformed surrogate handling, 600 seeded codec-versus-accounting comparisons, multi-table rejection, reopen tracking and older oversized catalog repair. The full suite passes 140 tests. The existing memory/file H2 differential smoke and compiler consumer checks also pass.
- Snapshot/WAL formats and commit-force ordering are unchanged. The lower capacity used by boundary tests is internal; the public probe exercises the actual 256 MiB bound.

## Measured cost, including limitations

The unchanged durable mutation harness ran sequentially against both runtime JARs, three fresh forks each. It checks recovered values. At 10,000 rows, medians of batch means were:

| Operation | Before ms/op | After ms/op | Before allocated B/op | After allocated B/op | WAL B/op, both |
|---|---:|---:|---:|---:|---:|
| Value UPDATE | 3.299 | 3.446 | 9,350.8 | 9,406.8 | 95.5 |
| Primary-key move | 3.334 | 3.479 | 537,386.4 | 537,444.0 | 96.0 |
| Tail DELETE | 3.883 | 3.868 | 4,077.2 | 4,135.6 | 83.0 |

The accounting adds about 56–58 allocated bytes per operation in this fixture and scans encoded TEXT lengths in changed rows. Recovery now includes a full size-counting pass. The UPDATE fixture's median reopen time was 5.858 ms before and 7.720 ms after, with overlapping ranges (5.662–8.829 and 6.850–8.117 ms). These finite observations do not establish a universal latency difference. Raw results include all table sizes and ranges; no throughput improvement is claimed.

The benchmark metadata records the checkout's base commit because measurement preceded the implementation commit. The corrected JAR hash matches the subsequently committed source; two unrelated original user whitespace edits were left outside the commit. No build ran during the mutation measurements.

Encoded file size is not a JVM heap budget. The complete working catalog, Kotlin objects and encoding buffers require additional memory. This correction does not add automatic checkpoint scheduling, device power-loss qualification or wider SQL support, and it is not deployment work.

## Reproduce

Build each revision with JDK 17 and retain its runtime JAR. Use the same current benchmark distribution for both:

```sh
./gradlew build :benchmarks:installDist
python3 benchmarks/measure_mutations.py --runtime /path/to/before.jar --output /tmp/capacity-before
python3 benchmarks/measure_mutations.py --runtime /path/to/after.jar --output /tmp/capacity-after
```

For the public-limit probe, set a classpath containing the selected runtime and Kotlin stdlib, compile the included Java files, and use an empty database directory:

```sh
javac -cp "$KOKO_CLASSPATH" CapacityProbe.java ReopenProbe.java
mkdir /tmp/capacity-probe
java -Xms64m -Xmx1g -XX:-UsePerfData -cp ".:$KOKO_CLASSPATH" CapacityProbe /tmp/capacity-probe/app.koko
java -Xms64m -Xmx1g -XX:-UsePerfData -cp ".:$KOKO_CLASSPATH" ReopenProbe /tmp/capacity-probe/app.koko
```

The probe creates roughly 0.5 GiB of snapshot/WAL files across the two revisions. Run it separately from performance measurements.
