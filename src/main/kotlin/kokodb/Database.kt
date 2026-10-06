package kokodb

import kokodb.execution.Executor
import kokodb.mapping.ModelAdapter
import kokodb.mapping.ModelProvider
import kokodb.query.Statement
import kokodb.sql.Parser
import kokodb.storage.Catalog
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
                createTable(adapter.table)
            }
        }
    }

    /** Inserts a generated model. Missing adapters report the required annotation and processor setup. */
    fun <M : Any> insert(model: M): Int = adapter(model.javaClass).insert(this, model)

    inline fun <reified M : Any> from(): ModelQuery<M> = from(M::class.java)

    fun <M : Any> from(modelClass: Class<M>): ModelQuery<M> {
        val adapter = adapter(modelClass)
        return ModelQuery(from(adapter.table), adapter)
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
        /** Discovers generated model providers visible to the class loader and prepares their tables. */
        fun inMemory(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: Database::class.java.classLoader,
        ): Database = Database(classLoader)
    }
}
