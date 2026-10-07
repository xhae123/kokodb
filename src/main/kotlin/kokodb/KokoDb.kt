package kokodb

/** A lazily opened, shared in-memory database. Each call is serialized; multiple calls are not a transaction. */
object KokoDb {
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

    /** Opens a fresh database explicitly. An already-open database is an error; close it before replacing it. */
    fun openInMemory(
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: Database::class.java.classLoader,
    ) = synchronized(lock) {
        if (database != null) throw DatabaseException("KokoDb is already open; close it before opening a new database")
        database = Database.inMemory(classLoader)
        closed = false
    }

    /** Discards the shared memory store. Further calls require openInMemory(); repeated closes are harmless. */
    fun close() = synchronized(lock) {
        database = null
        closed = true
    }

    private fun <R> withDatabase(operation: (Database) -> R): R = synchronized(lock) {
        if (closed) throw DatabaseException("KokoDb is closed; call openInMemory() before using it again")
        val current = database ?: Database.inMemory().also { database = it }
        operation(current)
    }
}
