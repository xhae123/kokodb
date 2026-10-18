package kokodb.storage

import kokodb.DatabaseException

// Retain the former immutable algorithm as a reference for the incremental recovery builder.
internal fun TableSplice.apply(previous: Table?): Table {
    if (baseRows == -1) {
        if (previous != null) throw DatabaseException("Invalid storage: created table already exists")
    } else if (previous == null || previous.rows.size != baseRows || previous.columns != columns) {
        throw DatabaseException("Invalid storage: splice predecessor mismatch")
    }
    val old = previous?.rows ?: RowBlocks.from(emptyList())
    val rows = old.spliced(prefix, removed, inserted)
    val key = columns.indexOfFirst { it.primaryKey }
    if (key >= 0) {
        val seen = HashSet<Value>()
        for (row in rows) {
            if (!seen.add(row[key])) throw DatabaseException("Invalid storage: duplicate primary key")
        }
    }
    return Table(columns).also { it.publishRows(rows) }
}

internal fun Catalog.restoreSplices(changes: Map<String, TableSplice>) {
    val previous = entries()
    val candidates = changes.mapValues { (name, splice) -> splice.apply(previous[name]) }
    restore(candidates)
}
