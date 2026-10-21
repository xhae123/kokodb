# Query Execution and Primary-Key Lookups

A primary-key equality query returns at most one row, but repeatedly scanning a table still makes its cost grow with stored row count. KoKoDB uses an immutable key index for sustained append workloads and an optional position cache for other repeated lookups with unchanged keys and row positions. It preserves the direct SQL API and adds no runtime dependency.

## Validation and candidate selection

The engine parses SQL and validates the table, requested projection, generated model schema, parameter presence, and parameter types before choosing rows. An empty result or missing indexed key cannot hide an invalid projection or binding.

For equality on the declared primary key, candidate selection uses the snapshot lookup path. Other predicates and full-table queries continue scanning. Typed queries use the same candidate selection and generated constructor mapping as raw queries. Results stay detached and have no ordering guarantee.

## Immutable index for append workloads

Repeated INSERTs formerly compared each new key with every existing row, making a growing batch quadratic. A table now builds an AVL key-to-position index after at least two prior appends without key/position invalidation, once it has at least 32 rows. Subsequent appends copy only the affected tree path; earlier transaction snapshots retain their original roots. INT keys compare numerically and TEXT keys compare exact Kotlin strings, so hash collisions do not affect this index.

- Duplicate-key validation and lookups use O(log N) tree traversal. No index is persisted in snapshots or WALs.
- Candidate rows and the candidate index are constructed before publication. A rejected statement or rolled-back transaction cannot add keys to an earlier snapshot.
- Value-only updates retain the index. Deletion and primary-key changes invalidate it; isolated inserts after such changes scan instead of immediately rebuilding it.
- Rebuilding after recovery or invalidation sorts existing positions and constructs a balanced tree. This is an O(N log N) cold-build cost, distinct from warm appends.
- The optional tree is capped at 65,536 entries. An append crossing that bound drops it; larger tables retain the scan and lazy read-cache paths. This bounds entry count, not total JVM heap or key-string bytes.
- The measured retained-heap increase is approximately 32 bytes per row on the qualified JVM. Path copying adds allocation. These costs buy faster sustained insertion; row-block copying and WAL diff detection remain separate growing costs.

## Lazy snapshot cache without an append index

- The first primary-key lookup scans. A second lookup with unchanged keys and positions builds an open-addressed integer array if the table has at least 32 rows. Primary-key predicates in UPDATE and DELETE use the same lookup path after validation.
- Slots contain row positions plus one; zero denotes an empty slot. Actual keys remain in stored rows and are compared after hashing. INT and TEXT primary keys use the same mechanism.
- Capacity is a power of two, sized for at most 75% occupancy. A 10,000-row index has 16,384 slots, or **65,536 bytes of integer payload**. This excludes JVM object headers, the fixed snapshot wrapper, row storage, and result objects; it is not total retained heap.
- Each optional index is bounded to 1,048,576 slots (4 MiB payload). Larger tables retain scans. A build encountering more than 128 probes for an insertion is abandoned and disabled for that snapshot, avoiding quadratic construction for adversarial hash collisions.
- Tiny tables, tables using the append index, full-table queries, and non-key queries allocate no position array. INSERT, DELETE, and key assignments invalidate the changed snapshot's cache; value-only UPDATE preserves it.

The size/admission thresholds are conservative implementation policies, not a claim that every workload's optimum has been measured. A first lookup and index construction remain O(N); subsequent successful cache lookups are expected O(1), with collisions resolved by probing and value comparison.

## Atomicity and lifecycle

A table fork shares its immutable row snapshot and any derived index. A value-only UPDATE creates a new snapshot with the same index because keys and positions are unchanged. The index itself is immutable after construction. Rollback preserves the original rows. Key changes and deletes invalidate the changed table's indexes; no-op mutations may retain the original snapshot.

Rows are stored in immutable arrays of up to 128 row references, with an outer array of block references. A point UPDATE copies the affected block and outer references, sharing other blocks. At 10,000 rows this copies at most 128 row references and 79 block references instead of 10,000 row references, excluding the replacement row and query objects. Append copies the last block and outer references; deletion repacks the affected tail. Bulk updates still scan all rows. The block size is an implementation choice, not an exhaustively tuned optimum.

Indexes are not stored in snapshots or WALs. Recovered rows rebuild query caches lazily using the same policy. A separate temporary recovery key set validates changed keys during WAL replay and is released before the handle is exposed. INSERT uniqueness checks use the append tree when admitted and otherwise remain O(N); primary-key assignment validation and WAL diff detection remain O(N). Point-update copying is O(128 + ceil(N/128)), not O(1). This adds no MVCC, general optimizer, or secondary-index API.

## Measurement boundary

The point-query JMH suite returns complete Kotlin model lists from KoKoDB and H2 prepared statements with direct constructor mapping. It covers cycling hits, cycling misses, and UPDATE followed by a lookup. The last case measures mutation, cache reuse and result mapping together. Full-table model results are measured separately. All comparisons use memory mode, one caller, the same model values, and no durable acknowledgment claim.

The [initial lookup measurements](baselines/2026-10-18-primary-key-lookup/README.md) retain cold-cache observations and the mixed-write gap that motivated block storage. The [row-block measurements](baselines/2026-10-18-row-blocks/README.md) record the resulting point-write gain, full-model scan regression, allocation/artifact costs, and variable recovery observations. The tradeoff remains workload-specific.

[INSERT scaling measurements](baselines/2026-10-21-insert-scaling/README.md) include warmed batch time, exploratory allocation/heap observations, unchanged WAL sizes and point-query regression checks. The tree bound and admission thresholds are implementation policies, not universally optimal settings.
