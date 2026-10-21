#!/usr/bin/env python3
"""Measure INSERT batch scaling with a selectable runtime and identical fixtures."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile
from measure import run, write_csv


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--distribution', type=Path, default=Path('benchmarks/build/install/benchmarks'))
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--forks', type=int, default=3)
    args = parser.parse_args()
    jars = sorted(args.distribution.resolve().joinpath('lib').glob('*.jar'))
    jars = [args.runtime.resolve(), *[p for p in jars if not p.name.startswith('kokodb-')]]
    if args.forks < 1 or len(jars) < 2 or not all(p.is_file() for p in jars) or not args.java:
        parser.error('Build :benchmarks:installDist and provide an existing runtime JAR, Java, and positive forks')
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.java, '-XX:-UsePerfData', '-Xms64m', '-Xmx256m', '-cp', os.pathsep.join(map(str, jars)),
               'benchmark.InsertBenchmark']
    metadata = {'command': command, 'java': subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr,
                'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'harness_sha256': hashlib.sha256(Path('benchmarks/src/main/java/benchmark/InsertBenchmark.java').read_bytes()).hexdigest(),
                'jars': [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars],
                'processes': []}
    observations = []
    for fork in range(1, args.forks + 1):
        with tempfile.TemporaryDirectory(prefix='kokodb-inserts-') as directory:
            elapsed, rss, output = run(command + [directory])
        (args.output / f'fork-{fork}.txt').write_text(output)
        rows = list(csv.DictReader(line for line in output.splitlines() if line and not line.startswith('#')))
        expected = {(mode, key, str(size), str(round)) for mode in ('memory', 'file')
                    for key in ('int', 'text') for size in (1000, 4000, 16000) for round in (1, 2, 3)}
        if len(rows) != 36 or {(r['mode'], r['key_type'], r['rows'], r['round']) for r in rows} != expected:
            raise RuntimeError('Incomplete INSERT observations')
        observations.extend({'fork': fork, **row} for row in rows)
        metadata['processes'].append({'fork': fork, 'wall_ms': elapsed, 'max_rss_bytes': rss, 'configuration': output.splitlines()[0]})
        print(f'fork {fork}: {elapsed / 1000:.2f} s, RSS {rss}', flush=True)
    (args.output / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    write_csv(args.output / 'inserts.csv', observations)
    summary = []
    for mode, key, size in sorted({(mode, key, size) for mode, key, size, _ in expected}):
        group = [r for r in observations if r['rows'] == size and r['mode'] == mode and r['key_type'] == key]
        record = {'mode': mode, 'key_type': key, 'rows': size, 'forks': args.forks, 'observations': len(group)}
        for metric in ('elapsed_ms', 'allocated_bytes', 'retained_heap_delta', 'wal_bytes'):
            values = [float(r[metric]) for r in group]
            for label, aggregate in (('median', statistics.median), ('min', min), ('max', max)):
                record[f'{label}_{metric}'] = aggregate(values)
        summary.append(record)
    write_csv(args.output / 'summary.csv', summary)


if __name__ == '__main__':
    main()
