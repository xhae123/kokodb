package kokodb.storage

import kokodb.DatabaseException

internal class Table private constructor(val columns: List<Column>, private var snapshot: RowSnapshot) {
    constructor(columns: List<Column>) : this(columns, RowSnapshot(RowBlocks.from(emptyList())))

    val primaryKeyColumn = columns.indexOfFirst { it.primaryKey }
    val rows: RowBlocks get() = snapshot.rows

    fun append(row: List<Value>) {
        val key = primaryKeyColumn
        val index = if (key >= 0) snapshot.indexForAppend(key) else null
        if (key >= 0 && (index?.find(row[key]) ?: rows.indexOfFirst { it[key] == row[key] }) >= 0) {
            throw DatabaseException("Duplicate primary key for column '${columns[key].name}'")
        }
        val nextRows = rows.appended(row)
        val next = RowSnapshot(nextRows)
        next.appendStreak = minOf(2, snapshot.appendStreak + 1)
        if (index != null && nextRows.size <= MAX_APPEND_KEYS) next.appendIndex = index.added(row[key], rows.size)
        snapshot = next
    }

    fun publishRows(rows: List<List<Value>>) {
        if (rows !== snapshot.rows) snapshot = RowSnapshot(RowBlocks.from(rows))
    }

    // Only callers proving unchanged keys and row positions may carry this cache across value updates.
    fun publishValues(rows: RowBlocks) {
        check(rows.size == snapshot.rows.size)
        if (rows !== snapshot.rows) snapshot = RowSnapshot(rows, snapshot)
    }

    fun findPrimaryKeyPosition(value: Value): Int = snapshot.findPosition(primaryKeyColumn, value)

    fun findPrimaryKey(value: Value): List<Value>? = rows.getOrNull(findPrimaryKeyPosition(value))

    fun fork(): Table = Table(columns, snapshot)

    companion object {
        // Bound optional tree retention; larger tables keep the existing scan/read-cache paths.
        private const val MAX_APPEND_KEYS = 65536
    }

    private class RowSnapshot(val rows: RowBlocks, previous: RowSnapshot? = null) {
        var appendStreak: Int = previous?.appendStreak ?: 0
        var appendIndex: PrimaryKeyIndex? = previous?.appendIndex

        fun indexForAppend(key: Int): PrimaryKeyIndex? {
            if (appendIndex == null && appendStreak >= 2 && rows.size in 32 until MAX_APPEND_KEYS) {
                appendIndex = PrimaryKeyIndex.from(rows, key)
            }
            return appendIndex
        }

        private var queried: Boolean = previous?.queried ?: false
        private var indexAllowed: Boolean = previous?.indexAllowed ?: (rows.size in 32..(MAX_SLOTS * 3 / 4))
        private var positions: IntArray? = previous?.positions

        fun findPosition(key: Int, value: Value): Int {
            appendIndex?.let { return it.find(value) }
            var index = positions
            if (index == null && indexAllowed && queried) {
                index = build(key)
                positions = index
                if (index == null) indexAllowed = false
            }
            queried = true
            if (index == null) return rows.indexOfFirst { it[key] == value }
            var slot = hash(value) and (index.size - 1)
            while (index[slot] != 0) {
                val row = rows[index[slot] - 1]
                if (row[key] == value) return index[slot] - 1
                slot = (slot + 1) and (index.size - 1)
            }
            return -1
        }

        private fun build(key: Int): IntArray? {
            var capacity = 64
            while (rows.size > capacity * 3 / 4) capacity *= 2
            val index = IntArray(capacity)
            for (position in rows.indices) {
                var slot = hash(rows[position][key]) and (capacity - 1)
                var probes = 0
                while (index[slot] != 0) {
                    // Abandon adversarial collision chains instead of spending quadratic time building a cache.
                    if (++probes > 128) return null
                    slot = (slot + 1) and (capacity - 1)
                }
                index[slot] = position + 1
            }
            return index
        }

        private fun hash(value: Value): Int {
            var hash = value.hashCode()
            hash = (hash xor (hash ushr 16)) * -2048144789
            hash = (hash xor (hash ushr 13)) * -1028477387
            return hash xor (hash ushr 16)
        }

        companion object {
            // Bound each optional cache to 4 MiB of integer payload; larger tables retain the scan path.
            private const val MAX_SLOTS = 1 shl 20
        }
    }
}
