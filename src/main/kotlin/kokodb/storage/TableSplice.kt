package kokodb.storage

import kokodb.DatabaseException

internal data class TableSplice(
    val columns: List<Column>,
    val baseRows: Int,
    val prefix: Int,
    val removed: Int,
    val inserted: List<List<Value>>,
) {
    fun apply(previous: Table?): Table {
        if (baseRows == -1) {
            if (previous != null) throw DatabaseException("Invalid storage: created table already exists")
        } else if (previous == null || previous.rows.size != baseRows || previous.columns != columns) {
            throw DatabaseException("Invalid storage: splice predecessor mismatch")
        }
        val old = previous?.rows ?: emptyList()
        val rows = ArrayList<List<Value>>(old.size - removed + inserted.size)
        rows.addAll(old.subList(0, prefix))
        rows.addAll(inserted)
        rows.addAll(old.subList(prefix + removed, old.size))
        val key = columns.indexOfFirst { it.primaryKey }
        if (key >= 0) {
            val seen = HashSet<Value>()
            for (row in rows) {
                if (!seen.add(row[key])) throw DatabaseException("Invalid storage: duplicate primary key")
            }
        }
        return Table(columns).also { it.publishRows(rows) }
    }

    companion object {
        fun between(previous: Table?, next: Table): TableSplice {
            if (previous == null) return TableSplice(next.columns, -1, 0, 0, next.rows)
            if (previous.columns != next.columns) throw DatabaseException("Stored table schema cannot change")
            val before = previous.rows
            val after = next.rows
            var prefix = 0
            while (prefix < minOf(before.size, after.size) && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            while (suffix < minOf(before.size, after.size) - prefix &&
                before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
            return TableSplice(next.columns, before.size, prefix, before.size - prefix - suffix,
                after.subList(prefix, after.size - suffix))
        }
    }
}
