package kokodb

import java.nio.file.Path

/** A shared database, lazily opened in memory unless a file is opened explicitly. Calls/scopes are serialized. */
object KoKoDB {
    private val lock = Any()
    private var database: Database? = null
    private var closed = false

    /** Runs SELECT and returns detached models. T is one row's type and requires @DbTable and KSP mapping. */
    inline operator fun <reified T : Any> invoke(
        sql: String,
        params: Map<String, Any?> = emptyMap(),
    ): List<T> = queryModels(T::class.java, sql, params)

    /** Typed-query bridge for callers with a model Class token. */
    fun <T : Any> queryModels(
        modelClass: Class<T>,
        sql: String,
        params: Map<String, Any?> = emptyMap(),
    ): List<T> = withDatabase { it.queryModels(modelClass, sql, params) }

    /** Executes CREATE TABLE, INSERT, UPDATE, or DELETE and returns the affected row count; creation returns 0. */
    fun execute(sql: String, params: Map<String, Any?> = emptyMap()): Int =
        withDatabase { it.execute(sql, params) }

    /** Returns detached SQL rows without requiring a generated result model. */
    fun query(sql: String, params: Map<String, Any?> = emptyMap()): List<Row> =
        withDatabase { it.query(sql, params) }

    /** Commits the synchronous callback as one unit. Other shared callers wait until it finishes. */
    fun <R> transaction(block: Transaction.() -> R): R = withDatabase { it.transaction(block) }

    /** Opens persistent storage explicitly. The parent directory must exist and the database must have one owner. */
    fun open(
        path: Path,
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: Database::class.java.classLoader,
    ) = synchronized(lock) {
        database?.requireNoTransaction("open")
        if (database != null) throw DatabaseException("KoKoDB is already open; close it before opening a database")
        closed = true
        database = Database.open(path, classLoader)
        closed = false
    }

    fun checkpoint() = withDatabase { it.checkpoint() }

    /** Opens a fresh database explicitly. An already-open database is an error; close it before replacing it. */
    fun openInMemory(
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: Database::class.java.classLoader,
    ) = synchronized(lock) {
        database?.requireNoTransaction("openInMemory")
        if (database != null) throw DatabaseException("KoKoDB is already open; close it before opening a new database")
        database = Database.inMemory(classLoader)
        closed = false
    }

    /** Closes the shared handle. Memory rows are discarded; file commits remain durable. Reopening is explicit. */
    fun close() {
        synchronized(lock) {
            database?.requireNoTransaction("close")
            try { database?.close() } finally {
                database = null
                closed = true
            }
        }
    }

    private fun <R> withDatabase(operation: (Database) -> R): R = synchronized(lock) {
        if (closed) throw DatabaseException("KoKoDB is closed; call open() or openInMemory() before using it again")
        val current = database ?: Database.inMemory().also { database = it }
        operation(current)
    }
}
