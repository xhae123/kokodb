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
import java.util.ServiceLoader

/**
 * A memory-only database for sequential use. Instances do not share data and are not thread-safe.
 * Named parameters accept Int or String values; invalid SQL or execution throws [DatabaseException].
 */
class Database private constructor(classLoader: ClassLoader) {
    private var state = DatabaseState(Catalog())
    @Volatile private var activeTransaction: Transaction? = null
    private val models = mutableMapOf<Class<*>, ModelAdapter<*>>()

    init {
        val providers = ServiceLoader.load(ModelProvider::class.java, classLoader)
        for (provider in providers) {
            for (adapter in provider.adapters()) {
                if (models.putIfAbsent(adapter.modelClass, adapter) != null) {
                    throw DatabaseException("Duplicate generated adapter for '${adapter.modelClass.name}'")
                }
                state.executor.execute(Statement.CreateTable(adapter.tableName, schema(adapter)), emptyMap())
            }
        }
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
        withExecutor { it.execute(Parser(sql).parse(), params) }

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
        requireNoTransaction("begin a nested transaction")
        val transaction = Transaction(this, DatabaseState(state.catalog.fork()))
        activeTransaction = transaction
        return try {
            val result = transaction.block()
            transaction.requireCommittable()
            state = transaction.state
            result
        } finally {
            transaction.active = false
            activeTransaction = null
        }
    }

    internal fun requireNoTransaction(operation: String) {
        activeTransaction?.run { throw DatabaseException("Cannot $operation inside a transaction") }
    }

    private fun <R> withExecutor(operation: (Executor) -> R): R {
        val transaction = activeTransaction ?: return operation(state.executor)
        return transaction.run { operation(transaction.state.executor) }
    }

    private fun schema(adapter: ModelAdapter<*>): List<Column> = adapter.columns.map {
        val type = when (it.type) {
            ModelColumn.Type.INT -> DataType.INT
            ModelColumn.Type.TEXT -> DataType.TEXT
        }
        Column(it.name, type, it.primaryKey)
    }

    companion object {
        /** Discovers generated model providers visible to the class loader and prepares their tables. */
        fun inMemory(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: Database::class.java.classLoader,
        ): Database = Database(classLoader)
    }
}
