package kokodb

import kokodb.storage.Value
import java.util.Locale

/** A detached result row. Column lookup is case-insensitive; missing or mistyped columns throw [DatabaseException]. */
class Row internal constructor(values: Map<String, Value>, private val tableName: String) {
    private val values = values.toMap()

    fun getInt(column: String): Int = when (val value = value(column)) {
        is Value.IntValue -> value.value
        else -> throw DatabaseException("Column '$column' is not INT")
    }

    fun getString(column: String): String = when (val value = value(column)) {
        is Value.TextValue -> value.value
        else -> throw DatabaseException("Column '$column' is not TEXT")
    }

    /** Reads a typed column. Foreign, unselected, or mistyped columns throw [DatabaseException]. */
    operator fun <T : Any> get(column: Column<T>): T {
        if (column.table.tableName != tableName) throw DatabaseException("Column '${column.name}' belongs to another table")
        return column.read(value(column.name))
    }

    private fun value(column: String): Value = values[column.lowercase(Locale.ROOT)]
        ?: throw DatabaseException("Unknown result column '$column'")
}
