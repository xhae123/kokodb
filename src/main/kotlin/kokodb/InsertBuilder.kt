package kokodb

import kokodb.query.Expression
import kokodb.storage.Value

/** Collects one complete row. Foreign, duplicate, or missing column assignments are rejected. */
class InsertBuilder internal constructor(private val table: Table) {
    private val values = mutableMapOf<Column<*>, Value>()

    fun <T : Any> set(column: Column<T>, value: T) {
        if (column.table !== table) throw DatabaseException("Column '${column.name}' belongs to another table")
        if (column in values) throw DatabaseException("Duplicate assignment for column '${column.name}'")
        values[column] = column.encode(value)
    }

    internal fun expressions(): List<Expression> = table.columns().map { column ->
        Expression.Literal(values[column] ?: throw DatabaseException("Missing value for column '${column.name}'"))
    }
}
