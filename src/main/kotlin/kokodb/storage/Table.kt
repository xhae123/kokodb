package kokodb.storage

internal data class Column(val name: String, val type: DataType, val primaryKey: Boolean = false)

internal class Table private constructor(val columns: List<Column>, private var snapshot: RowSnapshot) {
    constructor(columns: List<Column>) : this(columns, RowSnapshot(emptyList()))

    val primaryKeyColumn = columns.indexOfFirst { it.primaryKey }
    val rows: List<List<Value>> get() = snapshot.rows

    // The lookup cache belongs to exactly one immutable row snapshot, including across transaction forks.
    fun publishRows(rows: List<List<Value>>) {
        if (rows !== snapshot.rows) snapshot = RowSnapshot(rows)
    }

    fun findPrimaryKey(value: Value): List<Value>? = snapshot.find(primaryKeyColumn, value)

    fun fork(): Table = Table(columns, snapshot)

    private class RowSnapshot(val rows: List<List<Value>>) {
        private var queried = false
        private var indexAllowed = rows.size in 32..(MAX_SLOTS * 3 / 4)
        private var positions: IntArray? = null

        fun find(key: Int, value: Value): List<Value>? {
            var index = positions
            if (index == null && indexAllowed && queried) {
                index = build(key)
                positions = index
                if (index == null) indexAllowed = false
            }
            queried = true
            if (index == null) return rows.firstOrNull { it[key] == value }
            var slot = hash(value) and (index.size - 1)
            while (index[slot] != 0) {
                val row = rows[index[slot] - 1]
                if (row[key] == value) return row
                slot = (slot + 1) and (index.size - 1)
            }
            return null
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
