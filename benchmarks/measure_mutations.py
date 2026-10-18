#!/usr/bin/env python3
"""Compare durable point mutations using a selectable runtime and the same harness."""

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
               'benchmark.MutationBenchmark']
    metadata = {'command': command, 'java': subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr,
                'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'harness_sha256': hashlib.sha256(Path('benchmarks/src/main/java/benchmark/MutationBenchmark.java').read_bytes()).hexdigest(),
                'jars': [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in jars],
                'processes': []}
    observations = []
    for fork in range(1, args.forks + 1):
        with tempfile.TemporaryDirectory(prefix='kokodb-mutations-') as directory:
            elapsed, rss, output = run(command + [directory])
        (args.output / f'fork-{fork}.txt').write_text(output)
        rows = list(csv.DictReader(line for line in output.splitlines() if line and not line.startswith('#')))
        expected = {(str(size), operation) for size in (100, 1000, 10000) for operation in ('update', 'key_move', 'delete')}
        if len(rows) != 9 or {(r['rows'], r['operation']) for r in rows} != expected:
            raise RuntimeError('Incomplete mutation observations')
        observations.extend({'fork': fork, **row} for row in rows)
        metadata['processes'].append({'fork': fork, 'wall_ms': elapsed, 'max_rss_bytes': rss, 'configuration': output.splitlines()[0]})
        print(f'fork {fork}: {elapsed / 1000:.2f} s, RSS {rss}', flush=True)
    (args.output / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    write_csv(args.output / 'mutations.csv', observations)
    summary = []
    for size, operation in sorted(expected):
        group = [r for r in observations if r['rows'] == size and r['operation'] == operation]
        record = {'rows': size, 'operation': operation, 'forks': len(group)}
        for metric in ('ns_per_operation', 'allocated_bytes_per_operation', 'wal_bytes_per_operation', 'reopen_ms'):
            values = [float(r[metric]) for r in group]
            for label, aggregate in (('median', statistics.median), ('min', min), ('max', max)):
                record[f'{label}_{metric}'] = aggregate(values)
        summary.append(record)
    write_csv(args.output / 'summary.csv', summary)


if __name__ == '__main__':
    main()
