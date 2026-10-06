package kokodb

import kokodb.execution.Executor
import kokodb.query.Statement
import kokodb.sql.Parser
import kokodb.storage.Catalog

/**
 * A memory-only database for sequential use. Instances do not share data and are not thread-safe.
 * Named parameters accept Int or String values; invalid SQL or execution throws [DatabaseException].
 */
class Database private constructor() {
    private val executor = Executor(Catalog())

    /** Executes one CREATE TABLE or INSERT. Returns 0 for creation and 1 for insertion. */
    fun execute(sql: String, params: Map<String, Any?> = emptyMap()): Int =
        executor.execute(Parser(sql).parse(), params)

    /** Executes one SELECT and returns detached rows with no guaranteed order. */
    fun query(sql: String, params: Map<String, Any?> = emptyMap()): List<Row> =
        executor.query(Parser(sql).parse(), params)

    /** Creates a table from its Kotlin schema. An existing table is an error. */
    fun createTable(table: Table) {
        executor.execute(Statement.CreateTable(table.tableName, table.schema()), emptyMap())
    }

    /** Inserts one row after all assignments and the stored schema have been validated. Returns 1. */
    fun insertInto(table: Table, assign: InsertBuilder.() -> Unit): Int {
        val schema = table.schema()
        val builder = InsertBuilder(table).apply(assign)
        return executor.execute(Statement.Insert(table.tableName, builder.expressions(), schema), emptyMap())
    }

    /** Starts a lazy query selecting all columns. Validation of stored schema occurs on execution. */
    fun from(table: Table): Query {
        table.columns()
        return Query(executor, table)
    }

    companion object {
        fun inMemory(): Database = Database()
    }
}
