#!/usr/bin/env python3
"""Measure compiled JVM processes and preserve raw observations alongside summaries."""

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
import time
import zipfile


def run(command):
    with tempfile.TemporaryFile() as output:
        start = time.perf_counter_ns()
        child = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        rss = None
        if hasattr(os, "wait4"):
            _, status, usage = os.wait4(child.pid, 0)
            child.returncode = os.waitstatus_to_exitcode(status)
            rss = usage.ru_maxrss * (1 if platform.system() == "Darwin" else 1024)
        else:
            child.wait()
        elapsed = (time.perf_counter_ns() - start) / 1e6
        output.seek(0)
        text = output.read().decode()
        if child.returncode:
            raise RuntimeError(f"Process exited {child.returncode}: {text}")
        return elapsed, rss, text


def write_csv(path, rows):
    with path.open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", type=Path, default=Path("benchmarks/build/install/benchmarks"))
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=15)
    parser.add_argument("--forks", type=int, default=3)
    args = parser.parse_args()
    if args.runs < 1 or args.forks < 1:
        parser.error("runs and forks must be positive")
    jars = sorted(args.distribution.resolve().joinpath("lib").glob("*.jar"))
    if not jars or not args.java:
        parser.error("Build :benchmarks:installDist and provide a Java executable")
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.java, "-XX:-UsePerfData", "-Xms64m", "-Xmx256m", "-cp",
               os.pathsep.join(str(p) for p in jars), "benchmark.Benchmark"]
    environment = {
        "platform": platform.platform(), "architecture": platform.machine(),
        "java": subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True).stderr,
        "command": command, "startup_runs_per_mode": args.runs, "crud_forks": args.forks,
        "runtime_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "benchmark_source_sha256": hashlib.sha256(Path("benchmarks/src/main/java/benchmark/Benchmark.java").read_bytes()).hexdigest(),
        "jars": [],
    }
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            native = [name for name in archive.namelist()
                      if name.lower().endswith((".so", ".dll", ".dylib", ".jnilib"))]
        environment["jars"].append({"name": jar.name, "bytes": jar.stat().st_size,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "native_files": native})
    (args.output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    startup = []
    for mode in ("empty", "open", "first-query"):
        for iteration in range(args.runs):
            elapsed, rss, _ = run(command + [mode])
            startup.append({"mode": mode, "run": iteration + 1, "wall_ms": elapsed, "max_rss_bytes": rss})
        print(f"{mode}: median {statistics.median(r['wall_ms'] for r in startup if r['mode'] == mode):.2f} ms", flush=True)
    write_csv(args.output / "startup.csv", startup)
    observations = []
    for fork in range(args.forks):
        elapsed, rss, output = run(command + ["crud"])
        (args.output / f"crud-fork-{fork + 1}.txt").write_text(output)
        lines = [line for line in output.splitlines() if line and not line.startswith("#")]
        rows = list(csv.DictReader(lines))
        if len(rows) != 90 or {row["rows"] for row in rows} != {"100", "1000", "5000"}:
            raise RuntimeError("Incomplete benchmark observations")
        for row in rows:
            observations.append({"fork": fork + 1, **row})
        print(f"crud fork {fork + 1}: {elapsed / 1000:.2f} s, RSS {rss}", flush=True)
    write_csv(args.output / "crud.csv", observations)
    groups = {}
    for row in observations:
        groups.setdefault((row["workload"], row["rows"]), []).append(row)
    summary = []
    for (workload, count), rows in groups.items():
        times = [float(row["ns_per_op"]) for row in rows]
        allocations = [float(row["allocated_bytes_per_op"]) for row in rows]
        summary.append({"workload": workload, "rows": count, "batches": len(rows),
            "median_ns_per_op": statistics.median(times), "min_ns_per_op": min(times),
            "max_ns_per_op": max(times), "median_allocated_bytes_per_op": statistics.median(allocations)})
    write_csv(args.output / "summary.csv", summary)


if __name__ == "__main__":
    main()
