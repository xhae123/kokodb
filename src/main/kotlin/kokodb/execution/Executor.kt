package kokodb.execution

import kokodb.DatabaseException
import kokodb.Row
import kokodb.query.Equality
import kokodb.query.Expression
import kokodb.query.Projection
import kokodb.query.Statement
import kokodb.storage.Catalog
import kokodb.storage.Column
import kokodb.storage.Table
import kokodb.storage.Value

internal class Executor(private val catalog: Catalog) {
    fun execute(statement: Statement, params: Map<String, Any?>): Int = when (statement) {
        is Statement.CreateTable -> {
            catalog.create(statement.table, statement.columns)
            0
        }
        is Statement.Insert -> {
            val table = catalog.table(statement.table)
            val values = resolveRow(table, statement.values, params)
            val rows = table.rows + listOf(values)
            validateKeys(table, rows.asSequence())
            table.publishRows(rows)
            1
        }
        is Statement.Update -> {
            val table = catalog.table(statement.table)
            if (statement.assignments.map { it.column }.distinct().size != statement.assignments.size) {
                throw DatabaseException("Duplicate UPDATE assignments are not supported")
            }
            val assignments = statement.assignments.associate { assignment ->
                val index = columnIndex(table, assignment.column)
                val value = resolve(assignment.value, params)
                validateType(table.columns[index], value)
                index to value
            }
            val filter = statement.condition?.let { resolveFilter(table, it, params) }
            var affected = 0
            val rows = table.rows.map { row ->
                if (matches(row, filter)) {
                    affected++
                    row.mapIndexed { index, value -> assignments[index] ?: value }
                } else row
            }
            validateKeys(table, rows.asSequence())
            table.publishRows(rows)
            affected
        }
        is Statement.Delete -> {
            val table = catalog.table(statement.table)
            val filter = statement.condition?.let { resolveFilter(table, it, params) }
            val rows = table.rows.filterNot { matches(it, filter) }
            val affected = table.rows.size - rows.size
            table.publishRows(rows)
            affected
        }
        is Statement.Select -> throw DatabaseException("Use query() for SELECT")
    }

    fun query(statement: Statement, params: Map<String, Any?>): List<Row> = queryMapped(statement, params) { it }

    fun <R> queryMapped(statement: Statement, params: Map<String, Any?>, transform: (Row) -> R): List<R> {
        if (statement !is Statement.Select) throw DatabaseException("Use execute() for statements other than SELECT")
        val table = catalog.table(statement.table)
        validateSchema(table, statement.expectedSchema)
        val indices = when (val projection = statement.projection) {
            Projection.All -> table.columns.indices.toList()
            is Projection.Columns -> {
                if (projection.names.distinct().size != projection.names.size) {
                    throw DatabaseException("Duplicate result columns are not supported")
                }
                projection.names.map { columnIndex(table, it) }
            }
        }
        // Resolve and validate before scanning so invalid queries also fail on empty tables.
        val filter = statement.condition?.let { resolveFilter(table, it, params) }
        return table.rows.asSequence()
            .filter { row -> matches(row, filter) }
            .map { row -> transform(Row(indices.associate { table.columns[it].name to row[it] })) }
            .toList()
    }

    private fun matches(row: List<Value>, filter: Pair<Int, Value>?): Boolean =
        filter == null || row[filter.first] == filter.second

    private fun validateSchema(table: Table, expected: List<Column>?) {
        if (expected != null && table.columns != expected) {
            throw DatabaseException("Kotlin table definition does not match the stored schema")
        }
    }

    private fun resolveRow(table: Table, expressions: List<Expression>, params: Map<String, Any?>): List<Value> {
        if (expressions.size != table.columns.size) {
            throw DatabaseException("Table expects ${table.columns.size} values, got ${expressions.size}")
        }
        return expressions.zip(table.columns).map { (expression, column) ->
            resolve(expression, params).also { validateType(column, it) }
        }
    }

    private fun resolveFilter(table: Table, condition: Equality, params: Map<String, Any?>): Pair<Int, Value> {
        val index = columnIndex(table, condition.column)
        val value = resolve(condition.value, params)
        validateType(table.columns[index], value)
        return index to value
    }

    private fun validateKeys(table: Table, rows: Sequence<List<Value>>) {
        val index = table.columns.indexOfFirst { it.primaryKey }
        if (index < 0) return
        val seen = mutableSetOf<Value>()
        for (row in rows) {
            if (!seen.add(row[index])) throw DatabaseException("Duplicate primary key for column '${table.columns[index].name}'")
        }
    }

    private fun columnIndex(table: Table, name: String): Int {
        val index = table.columns.indexOfFirst { it.name == name }
        if (index == -1) throw DatabaseException("Unknown column '$name'")
        return index
    }

    private fun validateType(column: Column, value: Value) {
        if (column.type != value.type) {
            throw DatabaseException("Column '${column.name}' expects ${column.type}, got ${value.type}")
        }
    }

    private fun resolve(expression: Expression, params: Map<String, Any?>): Value = when (expression) {
        is Expression.Literal -> expression.value
        is Expression.Parameter -> {
            if (!params.containsKey(expression.name)) {
                throw DatabaseException("Missing parameter ':${expression.name}'")
            }
            when (val value = params[expression.name]) {
                is Int -> Value.IntValue(value)
                is String -> Value.TextValue(value)
                null -> throw DatabaseException("Parameter ':${expression.name}' cannot be NULL")
                else -> throw DatabaseException("Parameter ':${expression.name}' must be Int or String")
            }
        }
    }
}
