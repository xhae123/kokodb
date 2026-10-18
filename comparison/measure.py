#!/usr/bin/env python3
"""Run forked JMH model queries and retain exact dependencies and source hashes."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", type=Path, default=Path("comparison/build/install/comparison"))
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--quick", action="store_true")
    parser.add_argument("--suite", choices=("models", "points"), default="models")
    parser.add_argument("--runtime", type=Path)
    parser.add_argument("--engines", choices=("both", "kokodb"), default="both")
    parser.add_argument("--rows", nargs="+", type=int)
    args = parser.parse_args()
    jars = sorted(args.distribution.resolve().joinpath("lib").glob("*.jar"))
    if args.runtime:
        jars = [args.runtime.resolve(), *[p for p in jars if not p.name.startswith("kokodb-")]]
    if not jars or not args.java or not all(p.is_file() for p in jars):
        parser.error("Build :comparison:installDist and provide a Java executable")
    sizes = args.rows or ([1, 100, 1000] if args.suite == "models" else [1000, 10000])
    if any(size < 1 for size in sizes) or len(set(sizes)) != len(sizes):
        parser.error("Row counts must be positive and distinct")
    engines = ["kokodb", "h2"] if args.engines == "both" else ["kokodb"]
    benchmark = "comparison.ModelQueryBenchmark" if args.suite == "models" else "comparison.PointQueryBenchmark"
    cases = ["models"] if args.suite == "models" else ["hit", "miss", "updateThenRead"]
    args.output.mkdir(parents=True, exist_ok=True)
    result = args.output.resolve() / "jmh.json"
    command = [args.java, "-cp", os.pathsep.join(str(p) for p in jars), "org.openjdk.jmh.Main",
               benchmark, "-prof", "gc", "-rf", "json", "-rff", str(result), "-foe", "true",
               "-p", "engine=" + ",".join(engines), "-p", "rows=" + ",".join(map(str, sizes))]
    if args.quick:
        command.extend(["-f", "1", "-wi", "2", "-i", "3", "-w", "200ms", "-r", "200ms"])
    metadata = {
        "platform": platform.platform(), "architecture": platform.machine(),
        "java": subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True).stderr,
        "command": command, "quick": args.quick, "suite": args.suite,
        "runtime_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "sources": {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in sorted(Path("comparison/src/main").rglob("*")) if p.is_file()},
        "jars": [],
    }
    for p in jars:
        with zipfile.ZipFile(p) as archive:
            native = [n for n in archive.namelist() if n.lower().endswith((".so", ".dll", ".dylib", ".jnilib"))]
        metadata["jars"].append({"name": p.name, "bytes": p.stat().st_size,
                                 "sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "native_files": native})
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print("Running " + ("quick diagnostic" if args.quick else "three-fork JMH measurement"), flush=True)
    with (args.output / "jmh.log").open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    records = json.loads(result.read_text())
    expected = {(case, engine, str(rows)) for case in cases for engine in engines for rows in sizes}
    if len(records) != len(expected) or {(r["benchmark"].split(".")[-1], r["params"]["engine"], r["params"]["rows"]) for r in records} != expected:
        raise RuntimeError("Incomplete JMH observations")
    summary = []
    for r in records:
        metric = r["primaryMetric"]
        allocation = r["secondaryMetrics"]["gc.alloc.rate.norm"]
        record = {"case": r["benchmark"].split(".")[-1], "engine": r["params"]["engine"], "rows": r["params"]["rows"],
                  "mean_us_per_query": metric["score"], "error_us": metric["scoreError"],
                  "allocated_bytes_per_query": allocation["score"]}
        summary.append(record)
        print(record, flush=True)
    with (args.output / "summary.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(summary[0]))
        writer.writeheader()
        writer.writerows(summary)


if __name__ == "__main__":
    main()
