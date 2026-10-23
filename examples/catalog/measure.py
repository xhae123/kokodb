#!/usr/bin/env python3
"""Verify and measure the complete catalog CLI in isolated fresh JVMs."""

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

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_csv(path, records):
    with path.open('w', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)


def launch(command):
    with tempfile.TemporaryFile() as output:
        start = time.perf_counter_ns()
        child = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        _, status, usage = os.wait4(child.pid, 0)
        child.returncode = os.waitstatus_to_exitcode(status)
        wall = (time.perf_counter_ns() - start) / 1e6
        output.seek(0)
        return child.returncode, output.read().decode(), wall, usage.ru_maxrss * (1 if platform.system() == 'Darwin' else 1024)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runs', type=int, default=20)
    parser.add_argument('--rows', type=int, default=1000)
    args = parser.parse_args()
    if min(args.runs, args.rows) < 1 or platform.system() not in ('Darwin', 'Linux'):
        parser.error('Positive rows/runs and macOS or Linux are required')
    lib = ROOT / 'examples/catalog/build/install/catalog-example/lib'

    def one(pattern):
        matches = list(lib.glob(pattern))
        if len(matches) != 1:
            parser.error(f'Build :catalog-example:installDist; expected one {pattern} in {lib}')
        return matches[0]

    shared = [one('catalog-example*.jar'), one('kotlin-stdlib-*.jar'), one('annotations-*.jar')]
    paths = {engine: [*shared, one(pattern)] for engine, pattern in
             (('kokodb', 'kokodb-*.jar'), ('h2', 'h2-*.jar'))}
    commands = {engine: [args.java, '-Xms64m', '-Xmx256m', '-XX:-UsePerfData', '-cp',
                         os.pathsep.join(map(str, jars)), 'catalog.MainKt', engine]
                for engine, jars in paths.items()}
    args.output.mkdir(parents=True, exist_ok=False)
    out = args.output.resolve()
    records = []
    for i in range(args.rows):
        kind = ('source', 'source', 'resource', 'resource', 'generated')[i % 5]
        path = f'src/File{i:04}.kt' if kind == 'source' else f'{kind}/item{i:04}.txt'
        records.append((i, path, kind))
    header = 'id\tpath\tkind\n'
    fixture = header + ''.join(f'{i}\t{path}\t{kind}\n' for i, path, kind in records)
    expected = header + ''.join(f'{i}\t{"src/renamed.kt" if i == 0 else path}\t{kind}\n'
                                for i, path, kind in records if kind != 'generated')
    (out / 'input.tsv').write_text(fixture)
    (out / 'expected.tsv').write_text(expected)
    result_count = sum(kind != 'generated' for _, _, kind in records)
    metadata = {
        'platform': platform.platform(), 'architecture': platform.machine(),
        'java': subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr,
        'runtime_commit': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
        'runs_per_engine': args.runs, 'input_rows': args.rows, 'output_rows': result_count,
        'order_seed': 20261024, 'commands': commands,
        'input_sha256': digest(out / 'input.tsv'), 'expected_sha256': digest(out / 'expected.tsv'),
        'sources': {str(p.relative_to(ROOT)): digest(p) for p in
                    [Path(__file__), ROOT / 'examples/catalog/build.gradle.kts', *sorted((ROOT / 'examples/catalog/src').rglob('*.kt'))]},
        'jars': {engine: [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': digest(p)} for p in jars]
                 for engine, jars in paths.items()},
    }
    (out / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
    observations = []
    rng = random.Random(metadata['order_seed'])
    for pair in range(1, args.runs + 1):
        engines = ['kokodb', 'h2']
        rng.shuffle(engines)
        for engine in engines:
            report = out / f'{engine}-report.tsv'
            report.unlink(missing_ok=True)
            command = [*commands[engine], str(out / 'input.tsv'), str(report)]
            code, text, wall, rss = launch(command)
            (out / f'{engine}-{pair}.log').write_text(text)
            if code or text.strip() != f'Wrote {result_count} catalog entries from {args.rows} input entries' or report.read_bytes() != expected.encode():
                raise RuntimeError(f'Unverified {engine} run {pair}: {text}')
            observations.append({'order': len(observations) + 1, 'pair': pair, 'engine': engine,
                                 'wall_ms': wall, 'max_rss_bytes': rss, 'report_sha256': digest(report)})
        write_csv(out / 'observations.csv', observations)
        print(f'Verified pair {pair}/{args.runs}', flush=True)
    summaries = []
    for engine in ('kokodb', 'h2'):
        group = [r for r in observations if r['engine'] == engine]
        summary = {'engine': engine, 'runs': len(group), 'runtime_jars_bytes': sum(p.stat().st_size for p in paths[engine])}
        for metric in ('wall_ms', 'max_rss_bytes'):
            values = [r[metric] for r in group]
            summary.update({f'{stat}_{metric}': fn(values) for stat, fn in
                            (('median', statistics.median), ('min', min), ('max', max))})
        summaries.append(summary)
        print(summary, flush=True)
    write_csv(out / 'summary.csv', summaries)
    # A duplicate late in the input must not produce a successful report.
    (out / 'duplicate.tsv').write_text(fixture + '0\tduplicate.kt\tsource\n')
    for engine in ('kokodb', 'h2'):
        report = out / f'{engine}-invalid-report.tsv'
        code, text, _, _ = launch([*commands[engine], str(out / 'duplicate.tsv'), str(report)])
        (out / f'{engine}-duplicate.log').write_text(text)
        if code == 0 or report.exists():
            raise RuntimeError(f'{engine} accepted the duplicate input')
    print('Both engines rejected duplicate input without producing a report', flush=True)


if __name__ == '__main__':
    main()
