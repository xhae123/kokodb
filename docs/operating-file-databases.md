# Operating an Alpha File Database

Use file mode for small local datasets within the [storage contract](transactions-and-persistence.md). The complete catalog and query results live in JVM memory. The initial alpha does not provide live backups, automatic schema migration, automatic checkpoints or a stable cross-release compatibility promise.

## Open one owner and keep the files together

Open explicitly with `KoKoDB.open(path)` before SQL calls. The parent directory must exist. Supported file mode is gated to Linux/macOS and APFS/ext4/overlay filesystems with successful file/directory synchronization and atomic replacement; network filesystems are outside the claim.

A store consists of `app.koko` and `app.koko.wal`. Its empty `app.koko.lock` sidecar coordinates ownership. Choose names so one database's snapshot, WAL or lock path cannot overlap another database's files. A nonempty ownership sidecar is rejected; do not delete it to force an open. Do not rename, replace or remove active store files or their lock. Independent handles require sequential use; shared `KoKoDB` calls are serialized.

Close the owner before moving or backing up a store. A normal close preserves acknowledged file commits but does not checkpoint. Memory-mode close has a different meaning: it discards that in-memory database.

## Schedule explicit checkpoints

Call `KoKoDB.checkpoint()` between transactions at a point where the application can tolerate a synchronous pause. It writes the complete catalog, synchronizes replacement files/directories and resets the WAL. Until then the WAL grows, and reopen must validate/replay its history.

Choose a maintenance schedule from the application's write rate, WAL growth, available disk and measured checkpoint/reopen time. No universal row/time threshold is qualified. Do not delete the WAL to reclaim space. The 256 MiB encoded snapshot and 64 MiB transaction-frame limits do not bound JVM heap, disk usage between checkpoints or result-list allocation.

Checkpoint failure does not undo previously acknowledged writes. A recovery-required handle must be closed and reopened before use. Preserve diagnostic causes and suppressed cleanup errors when reporting a failure.

## Back up a closed pair and verify completeness

1. Stop writers and close every handle. Keep the source closed until copying and verification finish; the two files are not an atomic live-copy unit.
2. Copy both snapshot and WAL into a new archive directory. Checkpointing before close is optional; even a checkpointed database requires its WAL header.
3. Record each closed source file's byte length and SHA-256. Verify each copy matches, then write a completion manifest containing both entries. A failed/interrupted copy must not be presented as a complete backup.
4. Keep the source and any earlier verified backup unchanged. A manifest detects accidental missing/changed bytes; it is not authentication, a synchronization protocol or proof of physical archive durability.

A successful `Database.open()` is insufficient to prove backup completeness. Recovery intentionally accepts a valid complete WAL prefix followed by an incomplete terminal frame. If copying loses the end of the last acknowledged frame, that copy can look like an interrupted commit and expose an earlier prefix. The [backup qualification](baselines/2026-10-22-restore/README.md) reproduces this control and rejects that archive through its manifest before restore.

## Restore into a new location

1. Verify the completion manifest, both files' lengths and hashes before creating the restored pair. If the archive is incomplete or inconsistent, retain it for diagnosis and choose an intact backup.
2. Use a fresh directory and a basename whose sidecars cannot collide. Copy the snapshot to the chosen path and WAL to that path plus `.wal`. Do not copy the old `.lock` file; opening creates an empty ownership sidecar.
3. Open the copy with the intended runtime and generated models. The engine independently validates file identity/history, checksums, schemas and keys. A valid byte manifest cannot make mismatched files or model schemas valid.
4. Verify application data, perform a representative transaction, checkpoint and reopen. Keep the original source and archive unchanged until the restored copy is accepted.

Never overwrite an active database to restore it. Restoring at a new path lets verification fail without damaging the original. Copying only one file, pairing different database identities or changing a model's stored schema is rejected; this is not automatic conversion or schema migration.

## Resolve an unknown commit outcome

`CommitOutcomeUnknownException` means append or synchronization failed after writing began. The transaction may have committed; an exception does not prove rollback. Close the poisoned handle and explicitly reopen it for recovery. If reopening fails, preserve the authoritative pair rather than clearing files or silently switching to memory mode.

Applications that retry operations should store an operation identifier in the same transaction as their data changes. After recovery, query that identifier to decide whether the operation already committed. Callback side effects outside the database are not rolled back, and KoKoDB never retries callbacks automatically.

## Evaluate a different runtime or model version

Retain a verified closed-file backup and test the new version on a copy first. The documented snapshot 1.0 / WAL 2.0 development transition is a specific migration; it is not a promise that every future runtime can read every old store. A changed generated schema fails explicitly. Renaming a model/table is not a data migration.

Broader filesystem/device failures, physical power loss, native close leaks and operational field experience remain outside the finite alpha qualification. Keep important irreplaceable data on an established, appropriately operated database until a stronger adoption claim is supported.

## Run the consumer qualification

```sh
./gradlew :sample:installDist
python3 sample/check_backup.py --java "$JAVA_HOME/bin/java" --output /path/to/new-results-directory
```

This runner creates isolated source processes, copied archives and restored stores; it is a test fixture rather than a live-backup utility for existing application files. It retains per-process logs, manifests, runtime/sample JAR hashes and results. Linux/macOS CI runs it with temporary output directories.
