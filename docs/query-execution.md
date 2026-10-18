# Query Execution and Primary-Key Lookups

A primary-key equality query returns at most one row, but repeatedly scanning a table still makes its cost grow with stored row count. KoKoDB uses an optional in-memory lookup cache for repeated queries against an unchanged row snapshot. It preserves the direct SQL API and adds no runtime dependency.

## Validation and candidate selection

The engine parses SQL and validates the table, requested projection, generated model schema, parameter presence, and parameter types before choosing rows. An empty result or missing indexed key cannot hide an invalid projection or binding.

For equality on the declared primary key, candidate selection uses the snapshot lookup path. Other predicates and full-table queries continue scanning. Typed queries use the same candidate selection and generated constructor mapping as raw queries. Results stay detached and have no ordering guarantee.

## Lazy snapshot cache

- The first primary-key lookup scans. A second lookup on the same unchanged snapshot builds an open-addressed integer array if the table has at least 32 rows.
- Slots contain row positions plus one; zero denotes an empty slot. Actual keys remain in stored rows and are compared after hashing. INT and TEXT primary keys use the same mechanism.
- Capacity is a power of two, sized for at most 75% occupancy. A 10,000-row index has 16,384 slots, or **65,536 bytes of integer payload**. This excludes JVM object headers, the fixed snapshot wrapper, row storage, and result objects; it is not total retained heap.
- Each optional index is bounded to 1,048,576 slots (4 MiB payload). Larger tables retain scans. A build encountering more than 128 probes for an insertion is abandoned and disabled for that snapshot, avoiding quadratic construction for adversarial hash collisions.
- Tiny tables, write-only tables, full-table queries, and non-key queries allocate no position array. A workload that writes before each single key lookup also avoids repeated index construction.

The size/admission thresholds are conservative implementation policies, not a claim that every workload's optimum has been measured. A first lookup and index construction remain O(N); subsequent successful cache lookups are expected O(1), with collisions resolved by probing and value comparison.

## Atomicity and lifecycle

A table fork shares its immutable row snapshot and optional index. Publishing rows replaces that snapshot association; a private transaction cannot change positions belonging to committed rows. Rollback preserves the original snapshot. Key changes, deletes and reinserts invalidate the changed table's cache along with its old row list.

Indexes are not stored in snapshots or WALs. Recovered rows rebuild caches lazily using the same policy. Writes still construct replacement row lists, and primary-key changes still validate candidate uniqueness. This access path does not change those O(N) costs or add MVCC, a general optimizer, or a secondary-index API.

## Measurement boundary

The point-query JMH suite returns complete Kotlin model lists from KoKoDB and H2 prepared statements with direct constructor mapping. It covers cycling hits, cycling misses, and UPDATE followed by a lookup. The last case exposes cache invalidation and the existing row-copy cost. Full-table model results are measured separately. All comparisons use memory mode, one caller, the same model values, and no durable acknowledgment claim.

See the [retained measurements](baselines/2026-10-18-primary-key-lookup/README.md) for before/after/H2 results, allocation, cold-cache observations, artifact cost, and the unresolved UPDATE-then-read gap.
