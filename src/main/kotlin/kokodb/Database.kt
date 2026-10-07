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
    private val executor = Executor(Catalog())
    private val models = mutableMapOf<Class<*>, ModelAdapter<*>>()

    init {
        val providers = ServiceLoader.load(ModelProvider::class.java, classLoader)
        for (provider in providers) {
            for (adapter in provider.adapters()) {
                if (models.putIfAbsent(adapter.modelClass, adapter) != null) {
                    throw DatabaseException("Duplicate generated adapter for '${adapter.modelClass.name}'")
                }
                executor.execute(Statement.CreateTable(adapter.tableName, schema(adapter)), emptyMap())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <M : Any> adapter(modelClass: Class<M>): ModelAdapter<M> {
        // The registry is keyed by the adapter's exact model class, preserving the erased generic type.
        return (models[modelClass] ?: throw DatabaseException(
            "No generated adapter for '${modelClass.name}'; add @DbTable and configure the KokoDB KSP processor"
        )) as ModelAdapter<M>
    }

    /** Executes one CREATE TABLE or INSERT. Returns 0 for creation and 1 for insertion. */
    fun execute(sql: String, params: Map<String, Any?> = emptyMap()): Int =
        executor.execute(Parser(sql).parse(), params)

    /** Executes one SELECT and returns detached rows with no guaranteed order. */
    fun query(sql: String, params: Map<String, Any?> = emptyMap()): List<Row> =
        executor.query(Parser(sql).parse(), params)

    /** Maps SELECT results to a generated model. Its source table and complete projection must match the model. */
    fun <M : Any> queryModels(
        modelClass: Class<M>,
        sql: String,
        params: Map<String, Any?> = emptyMap(),
    ): List<M> {
        val statement = Parser(sql).parse() as? Statement.Select
            ?: throw DatabaseException("Use execute() for CREATE TABLE or INSERT")
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
        return executor.queryMapped(statement.copy(expectedSchema = schema), params, adapter::read)
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
