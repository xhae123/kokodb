package kokodb

import kokodb.execution.Executor
import kokodb.storage.Catalog

internal class DatabaseState(val catalog: Catalog) {
    val executor = Executor(catalog)
}

/** A synchronous, thread-owned transaction scope. Results are detached; a completed scope cannot be reused. */
class Transaction internal constructor(private val database: Database, state: DatabaseState) {
    internal var state = state
        private set
    private val owner = Thread.currentThread()
    @Volatile internal var active = true
    internal var failure: Throwable? = null
        private set

    /** Executes one write in this scope and returns its matched row count; CREATE TABLE returns 0. */
    operator fun invoke(sql: String, params: Map<String, Any?> = emptyMap()): Int {
        checkAccess()
        return database(sql, params)
    }

    /** Runs typed SELECT against the transaction's own pending writes. */
    inline fun <reified T : Any> query(sql: String, params: Map<String, Any?> = emptyMap()): List<T> =
        queryModels(T::class.java, sql, params)

    fun <T : Any> queryModels(modelClass: Class<T>, sql: String, params: Map<String, Any?> = emptyMap()): List<T> {
        checkAccess()
        return database.queryModels(modelClass, sql, params)
    }

    fun queryRows(sql: String, params: Map<String, Any?> = emptyMap()): List<Row> {
        checkAccess()
        return database.query(sql, params)
    }

    internal fun <R> run(operation: () -> R): R {
        checkAccess()
        failure?.let { throw aborted(it) }
        return try {
            operation()
        } catch (error: Throwable) {
            failure = error
            throw error
        }
    }

    internal fun requireCommittable() {
        failure?.let { throw aborted(it) }
    }

    internal fun finish() {
        active = false
        state = DatabaseState(Catalog())
    }

    private fun checkAccess() {
        if (Thread.currentThread() !== owner) throw DatabaseException("Transaction belongs to another thread")
        if (!active) throw DatabaseException("Transaction scope is no longer active")
    }

    private fun aborted(cause: Throwable): DatabaseException =
        DatabaseException("Transaction is rollback-only after an earlier failure").also { it.initCause(cause) }
}
