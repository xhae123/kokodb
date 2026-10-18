package kokodb.storage

import kokodb.DatabaseException

/** Private replay state: any failure invalidates it; only finish can publish immutable tables. */
internal class RecoveryCatalog(private val original: Catalog) {
    private val originalTables = original.entries()
    private val changed = mutableMapOf<String, ReplayedTable>()
    private var active = true

    fun applySplices(changes: Map<String, TableSplice>) = guarded {
        for ((name, splice) in changes) {
            val previous = changed[name]
            val stored = originalTables[name]
            val table = if (splice.baseRows == -1) {
                if (previous != null || stored != null) invalid("created table already exists")
                ReplayedTable(Table(splice.columns))
            } else {
                if ((previous?.rows?.size ?: stored?.rows?.size) != splice.baseRows ||
                    (previous?.columns ?: stored?.columns) != splice.columns) {
                    invalid("splice predecessor mismatch")
                }
                previous ?: ReplayedTable(checkNotNull(stored))
            }
            table.splice(splice)
            changed[name] = table
        }
    }

    fun applyTables(changes: Map<String, Table>) = guarded {
        for ((name, table) in changes) {
            val columns = changed[name]?.columns ?: originalTables[name]?.columns
            if (columns != null && columns != table.columns) invalid("stored schema changed")
            changed[name] = ReplayedTable(table)
        }
    }

    fun finish(): Catalog = guarded {
        val result = if (changed.isEmpty()) original else original.fork().also { catalog ->
            catalog.restore(changed.mapValues { (_, table) -> table.freeze() })
        }
        active = false
        changed.clear()
        result
    }

    private inline fun <T> guarded(operation: () -> T): T {
        if (!active) throw DatabaseException("Recovery catalog is no longer active")
        return try { operation() } catch (error: Throwable) {
            active = false
            changed.clear()
            throw error
        }
    }

    private class ReplayedTable(table: Table) {
        val columns = table.columns
        val rows = ArrayList(table.rows)
        private val key = table.primaryKeyColumn
        private val keys = if (key < 0) null else HashSet<Value>().also { keys ->
            for (row in rows) if (!keys.add(row[key])) invalid("duplicate primary key")
        }

        fun splice(splice: TableSplice) {
            if (splice.prefix !in 0..rows.size || splice.removed !in 0..(rows.size - splice.prefix) ||
                rows.size.toLong() - splice.removed + splice.inserted.size > Int.MAX_VALUE) invalid("splice bounds")
            if (keys != null) {
                for (index in splice.prefix until splice.prefix + splice.removed) keys.remove(rows[index][key])
                // Remove the whole replaced interval first, so valid key swaps within it remain legal.
                for (row in splice.inserted) if (!keys.add(row[key])) invalid("duplicate primary key")
            }
            if (splice.removed == splice.inserted.size) {
                for (index in splice.inserted.indices) rows[splice.prefix + index] = splice.inserted[index]
            } else {
                if (splice.removed > 0) rows.subList(splice.prefix, splice.prefix + splice.removed).clear()
                if (splice.inserted.isNotEmpty()) rows.addAll(splice.prefix, splice.inserted)
            }
        }

        fun freeze(): Table = Table(columns).also { it.publishRows(rows) }
    }

    companion object {
        private fun invalid(reason: String): Nothing = throw DatabaseException("Invalid storage: $reason")
    }
}
