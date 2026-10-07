package kokodb

import kokodb.storage.Value
import java.util.Locale

/** A detached result row. Column lookup is case-insensitive; missing or mistyped columns throw [DatabaseException]. */
class Row internal constructor(values: Map<String, Value>) {
    private val values = values.toMap()

    fun getInt(column: String): Int = when (val value = value(column)) {
        is Value.IntValue -> value.value
        else -> throw DatabaseException("Column '$column' is not INT")
    }

    fun getString(column: String): String = when (val value = value(column)) {
        is Value.TextValue -> value.value
        else -> throw DatabaseException("Column '$column' is not TEXT")
    }

    private fun value(column: String): Value = values[column.lowercase(Locale.ROOT)]
        ?: throw DatabaseException("Unknown result column '$column'")
}
