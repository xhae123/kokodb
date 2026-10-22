# Reject Overlapping Database Ownership Files

Opening `app.koko.lock` as a database and then opening `app.koko` could succeed. The second handle used the first database's snapshot as its lock file. A checkpoint of the first database atomically replaced that inode, allowing another channel to acquire a lock at the pathname while the second database remained open.

The standalone `CollisionProbe.java` uses only public database APIs and JDK file locks to reproduce this on the prior runtime. `before-probe.txt` records the acquired extra lock. Two regression tests also failed before the change: arbitrary nonempty ownership files and valid sibling snapshots were both accepted. This is an ownership defect; the probe does not claim to have observed data loss.

## Fix and scope

- Require an ownership sidecar to be empty after acquiring its lock and before initializing or forcing store files.
- Reject foreign bytes without changing them, creating snapshot/WAL files or silently selecting another path.
- Keep database names ending in `.lock` usable when the complete snapshot/WAL/lock namespaces do not overlap.
- Preserve normal empty lock files, failed-open cleanup and ordinary cross-process exclusion.

An empty-file requirement is preferable to truncating foreign data or banning filename suffixes. The extra size check runs only during open. It changes no format, commit-path work or durability barrier. File aliases/hard links and external replacement of an active store remain outside the supported contract.

## Validation and reproduction

```sh
./gradlew :test --tests kokodb.storage.StorageOwnershipTest --tests kokodb.storage.CrashRecoveryTest --tests kokodb.storage.StorageIOFailureTest --tests kokodb.storage.StorageLifecycleFailureTest --tests kokodb.storage.PersistenceOrderingTest
```

The new tests verify rejection, unchanged bytes, absence of collateral store creation, ownership cleanup on retry, and the other database's checkpoint/write/reopen results. Existing fault matrices and subprocess exclusion retain their original contracts. `environment.json` identifies the before/after artifacts and source hashes. The probe can be compiled/run with the runtime and Kotlin stdlib on its classpath; its pre-fix success message is expected only for the vulnerable runtime. Detailed commands, intentionally failed test logs and final validation are retained in `.local/improvements/2026-10-22-alpha-ownership/`.

The full local build passes 161 JUnit tests and the memory/storage/H2/reference smoke gates. The runtime grows by 66 bytes to 162,685 bytes; runtime dependencies remain unchanged.
