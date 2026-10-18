package kokodb.storage

internal class Table private constructor(val columns: List<Column>, private var snapshot: RowSnapshot) {
    constructor(columns: List<Column>) : this(columns, RowSnapshot(RowBlocks.from(emptyList())))

    val primaryKeyColumn = columns.indexOfFirst { it.primaryKey }
    val rows: RowBlocks get() = snapshot.rows

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

    private class RowSnapshot(val rows: RowBlocks, previous: RowSnapshot? = null) {
        private var queried: Boolean = previous?.queried ?: false
        private var indexAllowed: Boolean = previous?.indexAllowed ?: (rows.size in 32..(MAX_SLOTS * 3 / 4))
        private var positions: IntArray? = previous?.positions

        fun findPosition(key: Int, value: Value): Int {
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
