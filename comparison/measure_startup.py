#!/usr/bin/env python3
"""Compare verified fresh JVM workloads with isolated engine classpaths."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import shutil
import statistics
import subprocess
import tempfile
import time


def run(command, marker):
    with tempfile.TemporaryFile() as output:
        start = time.perf_counter_ns()
        child = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        _, status, usage = os.wait4(child.pid, 0)
        child.returncode = os.waitstatus_to_exitcode(status)
        elapsed = (time.perf_counter_ns() - start) / 1e6
        output.seek(0)
        text = output.read().decode()
        if child.returncode or text.strip() != marker:
            raise RuntimeError(f"Unverified process ({child.returncode}): {text}")
        return elapsed, usage.ru_maxrss * (1 if platform.system() == "Darwin" else 1024)


def write_csv(path, records):
    with path.open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", type=Path, default=Path("comparison/build/install/comparison"))
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=20)
    args = parser.parse_args()
    if args.runs < 1 or not hasattr(os, "wait4") or platform.system() not in ("Darwin", "Linux"):
        parser.error("Positive runs and Linux/macOS wait4 accounting are required")
    lib = args.distribution.resolve() / "lib"

    def one(pattern):
        matches = list(lib.glob(pattern))
        if len(matches) != 1:
            parser.error(f"Expected one {pattern} in {lib}; build :comparison:installDist")
        return matches[0]

    shared = [one("comparison*.jar"), one("kotlin-stdlib-*.jar"), one("annotations-*.jar")]
    classpaths = {engine: [*shared, one(pattern)] for engine, pattern in
                  (("kokodb", "kokodb-*.jar"), ("h2", "h2-*.jar"))}
    commands = {engine: [args.java, "-Xms64m", "-Xmx256m", "-XX:-UsePerfData", "-cp",
                        os.pathsep.join(map(str, jars)), "comparison.StartupProbe", engine]
                for engine, jars in classpaths.items()}
    args.output.mkdir(parents=True, exist_ok=False)
    metadata = {
        "platform": platform.platform(), "architecture": platform.machine(),
        "java": subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True).stderr,
        "runs_per_engine_mode": args.runs, "order_seed": 20261023, "commands": commands,
        "runtime_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "sources": {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in sorted(Path("comparison/src/main").rglob("*")) if p.is_file()},
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "jars": {engine: [{"name": p.name, "bytes": p.stat().st_size,
                            "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars]
                 for engine, jars in classpaths.items()},
    }
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    rng = random.Random(metadata["order_seed"])
    records = []
    for iteration in range(args.runs):
        modes = ["empty", "ready", "first", "load1000"]
        rng.shuffle(modes)
        for mode in modes:
            engines = ["kokodb", "h2"]
            rng.shuffle(engines)
            for engine in engines:
                wall, rss = run(commands[engine] + [mode], f"VERIFIED {engine} {mode}")
                records.append({"order": len(records) + 1, "pair": iteration + 1,
                                "engine": engine, "mode": mode, "wall_ms": wall, "max_rss_bytes": rss})
        write_csv(args.output / "observations.csv", records)
        print(f"Verified round {iteration + 1}/{args.runs}", flush=True)
    summaries = []
    for mode in ("empty", "ready", "first", "load1000"):
        for engine in ("kokodb", "h2"):
            group = [r for r in records if r["mode"] == mode and r["engine"] == engine]
            summary = {"engine": engine, "mode": mode, "runs": len(group)}
            for metric in ("wall_ms", "max_rss_bytes"):
                values = [r[metric] for r in group]
                summary.update({f"median_{metric}": statistics.median(values),
                                f"min_{metric}": min(values), f"max_{metric}": max(values)})
            summaries.append(summary)
            print(summary, flush=True)
    write_csv(args.output / "summary.csv", summaries)


if __name__ == "__main__":
    main()
