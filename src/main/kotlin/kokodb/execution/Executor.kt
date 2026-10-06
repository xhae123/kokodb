package kokodb.execution

import kokodb.DatabaseException
import kokodb.Row
import kokodb.sql.Expression
import kokodb.sql.Projection
import kokodb.sql.Statement
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
            if (statement.values.size != table.columns.size) {
                throw DatabaseException("Table '${statement.table}' expects ${table.columns.size} values, got ${statement.values.size}")
            }
            val values = statement.values.zip(table.columns).map { (expression, column) ->
                resolve(expression, params).also { validateType(column, it) }
            }
            table.rows.add(values)
            1
        }
        is Statement.Select -> throw DatabaseException("Use query() for SELECT")
    }

    fun query(statement: Statement, params: Map<String, Any?>): List<Row> {
        if (statement !is Statement.Select) throw DatabaseException("Use execute() for CREATE TABLE or INSERT")
        val table = catalog.table(statement.table)
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
        val filter = statement.condition?.let { condition ->
            val index = columnIndex(table, condition.column)
            val value = resolve(condition.value, params)
            validateType(table.columns[index], value)
            index to value
        }
        return table.rows.asSequence()
            .filter { row -> filter == null || row[filter.first] == filter.second }
            .map { row -> Row(indices.associate { table.columns[it].name to row[it] }) }
            .toList()
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
