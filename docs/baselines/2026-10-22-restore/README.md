# Closed-Store Restore Qualification

The sample consumer verifies that a closed snapshot/WAL pair can be archived, restored under a different basename and used in a fresh JVM. It uses the built runtime, generated `User` mapper and shared `KoKoDB` facade, with no internal storage API or test-only recovery hook.

## Qualified scenarios

- Three histories: commits only in WAL, all commits checkpointed, and commits split between snapshot and WAL. The runner checks the WAL-header-only case explicitly.
- Each source JVM exits before copying. The archive records both files' sizes and SHA-256 and is verified before the destination directory is created.
- Each restored JVM reads the expected generated models and raw audit rows, commits another multi-table transaction, checkpoints, closes and reopens. Source and archive fingerprints remain unchanged; the restored copy creates its own empty lock sidecar.
- Five archive failures: missing completion manifest, missing WAL, truncated WAL, damaged snapshot and missing manifest entry. All are rejected before restored files are created.
- Four engine failures: missing WAL, missing snapshot, mixed database identities and incompatible generated schema. Complete authoritative bytes remain unchanged on these failed opens.
- One control bypasses manifest verification and truncates the last WAL byte. Normal recovery opens the earlier complete prefix, proving that successful open alone cannot establish backup completeness. This is expected crash-tail behavior, not a runtime defect.

All scenarios pass. No runtime change was needed for these restore paths. The prior ownership correction remains part of the measured runtime. Detailed commands, initial and final runs, per-process logs, archives and manifests are retained under `.local/improvements/2026-10-22-alpha-restore/`.

## Reproduction

```sh
./gradlew :sample:installDist
python3 sample/check_backup.py --java "$JAVA_HOME/bin/java" --output /path/to/new-results-directory
```

The output directory must not already exist. `environment.json` records JVM/platform, exact JAR identities and fixture source hashes. `results.json` records every consumer/manifest case and the child commands. `consumer-output.txt` summarizes the run. The [operating guide](../../operating-file-databases.md) turns these checks into a supported closed-copy procedure.

## Limits

This is a finite operational qualification on the documented JVM/filesystem path. It does not provide concurrent live copying, crash-atomic archive publication, authenticated manifests, hardware archive durability, automatic migration or arbitrary toolchain/platform compatibility. The sample helper assumes quiescent source/archive files; its hashes do not make concurrent copying safe. Test elapsed time is not a backup/recovery performance claim.
