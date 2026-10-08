package kokodb

import kokodb.execution.Executor
import kokodb.mapping.ModelAdapter
import kokodb.mapping.ModelColumn
import kokodb.mapping.ModelProvider
import kokodb.query.Projection
import kokodb.query.Statement
import kokodb.sql.Parser
import kokodb.storage.Catalog
import kokodb.storage.Column
import kokodb.storage.DataType
import kokodb.storage.FileStore
import kokodb.storage.StoreIO
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * An independent database for sequential use. Instances do not share data and are not thread-safe.
 * Named parameters accept Int or String values; invalid SQL or execution throws [DatabaseException].
 */
class Database private constructor(classLoader: ClassLoader, private val store: FileStore? = null) : AutoCloseable {
    private var state = DatabaseState(store?.takeCatalog() ?: Catalog())
    private var closed = false
    @Volatile private var activeTransaction: Transaction? = null
    private val models = mutableMapOf<Class<*>, ModelAdapter<*>>()

    init {
        val providers = ServiceLoader.load(ModelProvider::class.java, classLoader)
        val tables = mutableSetOf<String>()
        val pending = DatabaseState(state.catalog.fork())
        for (provider in providers) {
            for (adapter in provider.adapters()) {
                if (models.putIfAbsent(adapter.modelClass, adapter) != null) {
                    throw DatabaseException("Duplicate generated adapter for '${adapter.modelClass.name}'")
                }
                if (!tables.add(adapter.tableName)) throw DatabaseException("Duplicate generated table '${adapter.tableName}'")
                val expected = schema(adapter)
                val existing = pending.catalog.entries()[adapter.tableName]
                if (existing == null) pending.executor.execute(Statement.CreateTable(adapter.tableName, expected), emptyMap())
                else if (existing.columns != expected) throw DatabaseException("Generated model does not match stored schema '${adapter.tableName}'")
            }
        }
        store?.commit(state.catalog, pending.catalog)
        state = pending
    }

    @Suppress("UNCHECKED_CAST")
    private fun <M : Any> adapter(modelClass: Class<M>): ModelAdapter<M> {
        // The registry is keyed by the adapter's exact model class, preserving the erased generic type.
        return (models[modelClass] ?: throw DatabaseException(
            "No generated adapter for '${modelClass.name}'; add @DbTable and configure the KoKoDB KSP processor"
        )) as ModelAdapter<M>
    }

    /** Executes one write statement. Returns 0 for CREATE TABLE and matched row counts for INSERT/UPDATE/DELETE. */
    fun execute(sql: String, params: Map<String, Any?> = emptyMap()): Int =
        withExecutor(write = true) { it.execute(Parser(sql).parse(), params) }

    /** Executes one SELECT and returns detached rows with no guaranteed order. */
    fun query(sql: String, params: Map<String, Any?> = emptyMap()): List<Row> =
        withExecutor { it.query(Parser(sql).parse(), params) }

    /** Maps SELECT results to a generated model. Its source table and complete projection must match the model. */
    fun <M : Any> queryModels(
        modelClass: Class<M>,
        sql: String,
        params: Map<String, Any?> = emptyMap(),
    ): List<M> = withExecutor { executor ->
        val statement = Parser(sql).parse() as? Statement.Select
            ?: throw DatabaseException("Use execute() for statements other than SELECT")
        val adapter = adapter(modelClass)
        if (statement.table != adapter.tableName) {
            throw DatabaseException("SELECT source table does not match model '${modelClass.name}'")
        }
        val schema = schema(adapter)
        val names = (statement.projection as? Projection.Columns)?.names
        if (names != null && (names.size != schema.size || names.toSet() != schema.map { it.name }.toSet())) {
            throw DatabaseException("SELECT must include every stored model column exactly once")
        }
        // Validate the mapping before scanning, so incompatible projections also fail on empty results.
        executor.queryMapped(statement.copy(expectedSchema = schema), params, adapter::read)
    }

    /** Commits all callback writes together, or discards them on failure. Caught database errors still abort commit. */
    fun <R> transaction(block: Transaction.() -> R): R {
        requireOpen()
        requireNoTransaction("begin a nested transaction")
        val transaction = Transaction(this, DatabaseState(state.catalog.fork()))
        activeTransaction = transaction
        return try {
            val result = transaction.block()
            transaction.requireCommittable()
            store?.commit(state.catalog, transaction.state.catalog)
            state = transaction.state
            result
        } finally {
            transaction.finish()
            activeTransaction = null
        }
    }

    internal fun requireNoTransaction(operation: String) {
        activeTransaction?.run { throw DatabaseException("Cannot $operation inside a transaction") }
    }

    private fun <R> withExecutor(write: Boolean = false, operation: (Executor) -> R): R {
        requireOpen()
        val transaction = activeTransaction
        if (transaction != null) return transaction.run { operation(transaction.state.executor) }
        if (write && store != null) return transaction { run { operation(this.state.executor) } }
        return operation(state.executor)
    }

    /** Writes a durable snapshot and resets its WAL. Memory databases and active transactions reject checkpoints. */
    fun checkpoint() {
        requireOpen()
        requireNoTransaction("checkpoint")
        (store ?: throw DatabaseException("Memory databases have no checkpoint")).checkpoint(state.catalog)
    }

    override fun close() {
        requireNoTransaction("close")
        if (closed) return
        closed = true
        try { store?.close() } finally {
            state = DatabaseState(Catalog())
            models.clear()
        }
    }

    private fun requireOpen() {
        if (closed) throw DatabaseException("Database is closed")
        store?.requireHealthy()
    }

    private fun schema(adapter: ModelAdapter<*>): List<Column> = adapter.columns.map {
        val type = when (it.type) {
            ModelColumn.Type.INT -> DataType.INT
            ModelColumn.Type.TEXT -> DataType.TEXT
        }
        Column(it.name, type, it.primaryKey)
    }

    companion object {
        /** Opens a file database with exclusive ownership. Commits force WAL before acknowledgment. */
        fun open(
            path: Path,
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: Database::class.java.classLoader,
        ): Database = open(path, classLoader, StoreIO())

        internal fun open(path: Path, classLoader: ClassLoader, io: StoreIO): Database {
            val store = FileStore.open(path, io)
            return try { Database(classLoader, store) } catch (error: Throwable) {
                runCatching { store.close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }

        /** Discovers generated model providers visible to the class loader and prepares their tables. */
        fun inMemory(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: Database::class.java.classLoader,
        ): Database = Database(classLoader)
    }
}
