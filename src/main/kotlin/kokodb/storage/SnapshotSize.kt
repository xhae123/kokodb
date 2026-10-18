package kokodb.storage

import kokodb.DatabaseException

/** Encoded snapshot bytes, independent of retained JVM objects or WAL history. */
internal object SnapshotSize {
    fun of(catalog: Catalog): Long = StorageCodec.SNAPSHOT_HEADER + 4L +
        catalog.entries().entries.sumOf { (name, table) ->
            tableHeader(name, table.columns) + table.rows.sumOf(::row)
        }

    fun after(current: Long, before: Map<String, Table>, changes: Map<String, TableSplice>): Long {
        var result = current
        for ((name, splice) in changes) {
            if (splice.baseRows == -1) result += tableHeader(name, splice.columns)
            else {
                val rows = before.getValue(name).rows
                for (index in splice.prefix until splice.prefix + splice.removed) result -= row(rows[index])
            }
            result += splice.inserted.sumOf(::row)
        }
        return result
    }

    private fun tableHeader(name: String, columns: List<Column>): Long =
        4L + name.length + 4 + columns.sumOf { 4L + it.name.length + 2 } + 4

    private fun row(values: List<Value>): Long = values.sumOf { value ->
        when (value) {
            is Value.IntValue -> 4L
            is Value.TextValue -> 4L + utf8Length(value.value)
        }
    }

    private fun utf8Length(text: String): Long {
        var size = 0L
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            size += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                character.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    index++
                    4
                }
                character.isSurrogate() -> throw DatabaseException("Invalid storage: malformed UTF-16 text")
                else -> 3
            }
        }
        return size
    }
}
