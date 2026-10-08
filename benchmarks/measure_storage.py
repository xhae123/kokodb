#!/usr/bin/env python3
"""Measure checked durable batches, WAL volume, checkpoints and reopen cost."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import tempfile
from measure import run, write_csv


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", type=Path, default=Path("benchmarks/build/install/benchmarks"))
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--forks", type=int, default=3)
    args = parser.parse_args()
    jars = sorted(args.distribution.resolve().joinpath("lib").glob("*.jar"))
    if args.forks < 1 or not jars or not args.java:
        parser.error("Build :benchmarks:installDist, provide Java, and use positive forks")
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.java, "-XX:-UsePerfData", "-Xms64m", "-Xmx256m", "-cp",
               os.pathsep.join(str(p) for p in jars), "benchmark.DurabilityBenchmark"]
    metadata = {
        "platform": platform.platform(), "architecture": platform.machine(),
        "java": subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True).stderr,
        "command": command, "forks": args.forks,
        "runtime_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "benchmark_source_sha256": hashlib.sha256(Path("benchmarks/src/main/java/benchmark/DurabilityBenchmark.java").read_bytes()).hexdigest(),
        "jars": [{"name": p.name, "bytes": p.stat().st_size,
                  "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars],
        "processes": [],
    }
    observations = []
    for fork in range(args.forks):
        with tempfile.TemporaryDirectory(prefix="kokodb-storage-") as directory:
            elapsed, rss, output = run(command + [directory, "full"])
        (args.output / f"storage-fork-{fork + 1}.txt").write_text(output)
        rows = list(csv.DictReader(line for line in output.splitlines() if line and not line.startswith("#")))
        expected = {(mode, size, str(round_)) for mode in ("autocommit", "batch")
                    for size in ("100", "1000") for round_ in range(1, 4)}
        if len(rows) != 12 or {(r["mode"], r["rows"], r["round"]) for r in rows} != expected:
            raise RuntimeError("Incomplete storage observations")
        observations.extend({"fork": fork + 1, **row} for row in rows)
        metadata["processes"].append({"fork": fork + 1, "wall_ms": elapsed, "max_rss_bytes": rss,
                                      "configuration": output.splitlines()[0]})
        print(f"storage fork {fork + 1}: {elapsed / 1000:.2f} s, RSS {rss}", flush=True)
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    write_csv(args.output / "storage.csv", observations)
    metrics = ("commits", "insert_ns_per_row", "allocated_bytes_per_row", "wal_bytes",
               "wal_reopen_ms", "checkpoint_ms", "snapshot_bytes", "snapshot_reopen_ms")
    summary = []
    for mode, size in ((m, s) for s in ("100", "1000") for m in ("autocommit", "batch")):
        group = [r for r in observations if r["mode"] == mode and r["rows"] == size]
        record = {"mode": mode, "rows": size, "batches": len(group)}
        for metric in metrics:
            values = [float(r[metric]) for r in group]
            for label, operation in (("median", statistics.median), ("min", min), ("max", max)):
                record[f"{label}_{metric}"] = operation(values)
        summary.append(record)
    write_csv(args.output / "summary.csv", summary)


if __name__ == "__main__":
    main()
