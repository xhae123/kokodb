#!/usr/bin/env python3
"""Run finite seeded correctness checks; elapsed time is not database latency."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", type=Path, default=Path("comparison/build/install/comparison"))
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=1000)
    parser.add_argument("--seeds", nargs="+", type=int, default=[17, 31, 20261018])
    parser.add_argument("--modes", nargs="+", choices=["memory", "file"], default=["memory", "file"])
    args = parser.parse_args()
    jars = sorted(args.distribution.resolve().joinpath("lib").glob("*.jar"))
    if not jars or not args.java or args.steps < 1:
        parser.error("Build :comparison:installDist, provide Java and positive steps")
    if len(set(args.seeds)) != len(args.seeds) or len(set(args.modes)) != len(args.modes):
        parser.error("Seeds and modes must be distinct")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        parser.error("Output directory must be empty to preserve earlier evidence")
    command = [args.java, "-Xms64m", "-Xmx256m", "-XX:-UsePerfData", "-cp", os.pathsep.join(map(str, jars)), "comparison.DifferentialCheck"]
    metadata = {
        "purpose": "Finite correctness qualification, not a performance comparison",
        "platform": platform.platform(), "architecture": platform.machine(),
        "java": subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True).stderr,
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "steps": args.steps, "seeds": args.seeds, "modes": args.modes,
        "sources": {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in [Path("comparison/src/main/kotlin/comparison/DifferentialCheck.kt"), Path(__file__)]},
        "jars": [{"name": p.name, "bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars],
        "commands": [command + [mode, str(seed), str(args.steps), str((args.output / f"{mode}-{seed}").resolve())]
                     for mode in args.modes for seed in args.seeds],
    }
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    results = []
    for invocation in metadata["commands"]:
        started = time.perf_counter()
        result = subprocess.run(invocation, capture_output=True, text=True)
        directory = Path(invocation[-1])
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "process.log").write_text(result.stdout + result.stderr)
        result.check_returncode()
        observation = json.loads((directory / "result.json").read_text())
        observation["qualification_seconds"] = time.perf_counter() - started
        results.append(observation)
        (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
        print(json.dumps(observation), flush=True)


if __name__ == "__main__":
    main()
