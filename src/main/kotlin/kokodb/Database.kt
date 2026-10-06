package kokodb

import kokodb.execution.Executor
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

    companion object {
        fun inMemory(): Database = Database()
    }
}
