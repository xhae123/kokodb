#!/usr/bin/env python3
"""Qualify closed-file backups using built sample JARs and fresh JVM consumers."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile


FILES = {"snapshot": "", "wal": ".wal"}


def fingerprint(path):
    return {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def closed_backup(source, archive):
    archive.mkdir()
    manifest = {}
    for name, suffix in FILES.items():
        origin = Path(str(source) + suffix)
        expected = fingerprint(origin)
        shutil.copyfile(origin, archive / name)
        if fingerprint(archive / name) != expected:
            raise AssertionError("Backup copy differs from the closed source")
        manifest[name] = expected
    # An interrupted copy has no completed manifest; engine recovery alone cannot detect every short backup.
    (archive / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def restore(archive, destination):
    manifest = json.loads((archive / "manifest.json").read_text())
    if set(manifest) != set(FILES):
        raise ValueError("Incomplete backup manifest")
    for name in FILES:
        if fingerprint(archive / name) != manifest[name]:
            raise ValueError(f"Backup content does not match manifest: {name}")
    destination.parent.mkdir()
    for name, suffix in FILES.items():
        target = Path(str(destination) + suffix)
        shutil.copyfile(archive / name, target)
        if fingerprint(target) != manifest[name]:
            raise ValueError(f"Restored copy does not match manifest: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", type=Path, help="Retain fixture files, logs and exact artifact identity")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    jars = sorted((root / "sample/build/install/sample/lib").glob("*.jar"))
    if not args.java or not jars:
        parser.error("Build :sample:installDist and provide a Java executable")
    with tempfile.TemporaryDirectory(prefix="kokodb-backup-") as temporary:
        output = args.output.resolve() if args.output else Path(temporary) / "results"
        output.mkdir(parents=True, exist_ok=False)
        command = [args.java, "-XX:-UsePerfData", "-cp", os.pathsep.join(map(str, jars)), "sample.BackupFixture"]
        environment = {
            "platform": platform.platform(),
            "java": subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True).stderr,
            "base_command": command,
            "jars": [{"path": str(path), **fingerprint(path)} for path in jars],
            "sources": {str(p.relative_to(root)): fingerprint(p)["sha256"] for p in
                        [Path(__file__), root / "sample/src/main/kotlin/sample/BackupFixture.kt"]},
        }
        (output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
        results = []

        def run(label, operation, path, *extra):
            invocation = command + [operation, str(path), *extra]
            result = subprocess.run(invocation, capture_output=True, text=True, timeout=30)
            text = result.stdout + result.stderr
            (output / f"{label}.log").write_text(text)
            expected = {"create": "SOURCE_CLOSED", "incompatible": "SOURCE_CLOSED",
                        "restore": "RESTORE_VERIFIED", "reject": "OPEN_REJECTED",
                        "prefix": "PREFIX_RECOVERED"}[operation]
            if result.returncode or expected not in text:
                raise AssertionError(f"{label}: {text}")
            results.append({"case": label, "command": invocation, "result": expected})
            print(f"PASS {label}: {expected}", flush=True)

        def pair(path):
            return {suffix: fingerprint(Path(str(path) + suffix)) for suffix in FILES.values()
                    if Path(str(path) + suffix).exists()}

        archives = []
        sources = []
        for mode in ("wal", "snapshot", "mixed"):
            folder = output / mode
            folder.mkdir()
            source = folder / "original.koko"
            run(f"create-{mode}", "create", source, mode)
            before = pair(source)
            assert (before[".wal"]["bytes"] == 40) == (mode == "snapshot")
            archive = folder / "archive"
            closed_backup(source, archive)
            archived = {p.name: fingerprint(p) for p in archive.iterdir()}
            target = folder / "restored" / "renamed.koko"
            restore(archive, target)
            assert not Path(str(target) + ".lock").exists()
            run(f"restore-{mode}", "restore", target)
            assert pair(source) == before
            assert {p.name: fingerprint(p) for p in archive.iterdir()} == archived
            assert Path(str(target) + ".lock").stat().st_size == 0
            sources.append(source)
            archives.append(archive)

        for damage in ("missing-manifest", "missing-wal", "short-wal", "corrupt-snapshot", "missing-entry"):
            archive = output / damage
            shutil.copytree(archives[0], archive)
            if damage.startswith("missing-") and damage != "missing-entry":
                (archive / ("manifest.json" if damage == "missing-manifest" else "wal")).unlink()
            elif damage == "short-wal":
                path = archive / "wal"
                path.write_bytes(path.read_bytes()[:-1])
            elif damage == "corrupt-snapshot":
                path = archive / "snapshot"
                data = bytearray(path.read_bytes())
                data[-1] ^= 1
                path.write_bytes(data)
            else:
                path = archive / "manifest.json"
                manifest = json.loads(path.read_text())
                del manifest["wal"]
                path.write_text(json.dumps(manifest))
            target = output / f"rejected-{damage}" / "data.koko"
            try:
                restore(archive, target)
            except (FileNotFoundError, ValueError):
                assert not target.parent.exists()
            else:
                raise AssertionError(f"Accepted {damage}")
            results.append({"case": damage, "result": "MANIFEST_REJECTED"})
            print(f"PASS {damage}: MANIFEST_REJECTED", flush=True)

        for case, message in (
            ("missing-wal", "Required WAL is missing"),
            ("missing-snapshot", "Required snapshot is missing"),
            ("mixed-identity", "Snapshot and WAL identity/history mismatch"),
        ):
            folder = output / f"engine-{case}"
            folder.mkdir()
            target = folder / "data.koko"
            if case != "missing-snapshot":
                shutil.copyfile(sources[0], target)
            if case != "missing-wal":
                origin = sources[2] if case == "mixed-identity" else sources[0]
                shutil.copyfile(Path(str(origin) + ".wal"), Path(str(target) + ".wal"))
            before = pair(target)
            run(f"engine-{case}", "reject", target, message)
            assert pair(target) == before

        folder = output / "unverified-tail-control"
        folder.mkdir()
        target = folder / "data.koko"
        shutil.copyfile(sources[0], target)
        Path(str(target) + ".wal").write_bytes(Path(str(sources[0]) + ".wal").read_bytes()[:-1])
        run("unverified-tail-control", "prefix", target)

        folder = output / "schema"
        folder.mkdir()
        source = folder / "old.koko"
        run("create-incompatible-schema", "incompatible", source)
        archive = folder / "archive"
        closed_backup(source, archive)
        target = folder / "restored" / "data.koko"
        restore(archive, target)
        before = pair(target)
        run("reject-incompatible-schema", "reject", target, "Generated model does not match stored schema 'users'")
        assert pair(target) == before
        (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
        print("Qualified 3 restored histories, 5 archive rejections, 4 engine rejections and 1 unverified-tail control", flush=True)


if __name__ == "__main__":
    main()
