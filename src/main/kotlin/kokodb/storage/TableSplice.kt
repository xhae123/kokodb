package kokodb.storage

import kokodb.DatabaseException

internal data class TableSplice(
    val columns: List<Column>,
    val baseRows: Int,
    val prefix: Int,
    val removed: Int,
    val inserted: List<List<Value>>,
) {
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
