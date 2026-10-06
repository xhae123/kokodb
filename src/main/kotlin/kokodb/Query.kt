package kokodb

import kokodb.execution.Executor
import kokodb.query.Equality
import kokodb.query.Expression
import kokodb.query.Projection
import kokodb.query.Statement

/** An immutable query description. Each terminal call executes against the current database state. */
class Query internal constructor(
    private val executor: Executor,
    private val table: Table,
    private val projection: List<Column<*>>? = null,
    private val condition: Condition? = null,
) {
    /** Selects columns from the source table. Empty or duplicate projections are rejected. */
    fun select(vararg columns: Column<*>): Query {
        if (columns.isEmpty()) throw DatabaseException("Select at least one column")
        columns.forEach { checkOwner(it) }
        if (columns.distinct().size != columns.size) throw DatabaseException("Duplicate result columns are not supported")
        return Query(executor, table, columns.toList(), condition)
    }

    /** Sets the single equality condition, replacing any previous condition. */
    fun where(condition: Condition): Query {
        checkOwner(condition.column)
        return Query(executor, table, projection, condition)
    }

    /** Returns detached rows with no guaranteed order. */
    fun toList(): List<Row> = map { it }

    /** Maps rows during execution without an intermediate result list. The mapper must not mutate this database. */
    fun <R> map(transform: (Row) -> R): List<R> = executor.queryMapped(statement(), emptyMap(), transform)

    private fun statement() = Statement.Select(
        table = table.tableName,
        projection = projection?.let { Projection.Columns(it.map { column -> column.name }) } ?: Projection.All,
        condition = condition?.let { Equality(it.column.name, Expression.Literal(it.value)) },
        expectedSchema = table.schema(),
    )

    private fun checkOwner(column: Column<*>) {
        if (column.table !== table) throw DatabaseException("Column '${column.name}' belongs to another table")
    }
}
