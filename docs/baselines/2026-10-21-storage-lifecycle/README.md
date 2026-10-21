# Storage Failure Causes and Resource Lifecycles

Bootstrap/checkpoint cleanup could overwrite the original append error if deleting the temporary file also failed. Closing the WAL and ownership channels had the same problem: a second close error replaced the first. Three regression tests reproduced these diagnostic failures before the correction.

Scoped cleanup now retains the first exception and attaches later failures as suppressed exceptions. The internal StoreIO boundary also delegates reads, close and deletion; existing open and truncate seams are exercised by a separate matrix. Storage ordering, formats and public SQL APIs are unchanged.

## Single-failure matrix

| Path | Injected cases |
|---|---:|
| Bootstrap | 12 |
| Ordinary recovery | 10 |
| Torn-tail repair | 14 |
| Checkpoint | 10 |
| Close | 2 |
| Stale temporary cleanup | 12 |
| **Total** | **60** |

Each path also has a successful baseline. Cases check acknowledged rows, failed-handle behavior, ownership reacquisition, continued writes, checkpoint, another reopen and eventual temporary-file cleanup. These augment the existing 95 append/force/replace/directory-sync failures, 216 modeled persistence images and subprocess crash tests.

- Open fails before acquiring a channel.
- File/channel reads, truncation and deletion fail before or after their effect.
- Close fails after actually releasing its channel. This models an error reported by cleanup, not an inaccessible native descriptor leak.
- Once a failure is injected, cleanup runs normally so each single-failure case isolates its selected boundary. Separate combined-failure tests verify error suppression.

## Combined failures

| Trigger | Before | After |
|---|---|---|
| Bootstrap append fails, then temporary deletion fails | Cleanup error hides append error | Append error remains the cause; cleanup error is suppressed |
| Checkpoint append fails, then temporary deletion fails | Cleanup error hides append error | Append error remains the cause; cleanup error is suppressed |
| WAL close fails, then ownership-channel close fails | Ownership error hides WAL error | WAL error is thrown; ownership error is suppressed |

The new lifecycle test initially ran four methods: the single-failure matrix passed while all three combined-failure methods failed. Keeping both forms of qualification matters: passing individual fault injection did not establish preservation of the original diagnostic.

## Reproduction and limits

```sh
./gradlew :test --tests kokodb.storage.StorageLifecycleFailureTest --tests kokodb.storage.StorageIOFailureTest --tests kokodb.storage.PersistenceOrderingTest
```

Raw focused output is retained in `matrix-output.txt`; the root `.local/improvements/2026-10-21-storage-lifecycle/` workstream retains the failing XML, corrected/full build logs, exact sources/artifact, decisions and environment.

This finite matrix does not qualify physical power loss, directory-channel lifecycle inside synchronization, temporary-file creation, filesystem metadata/directory-enumeration failures, or native close failures that leave resources unreleased. The existing platform and synchronization assumptions remain in the [storage contract](../../transactions-and-persistence.md). Test duration is not a performance benchmark.
