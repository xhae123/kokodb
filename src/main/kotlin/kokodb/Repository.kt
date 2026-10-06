package kokodb

import kokodb.storage.DataType
import kokodb.storage.Value
import kotlin.reflect.KClass

/** Manages detached models by their caller-supplied @Id. Updates do not insert missing rows. */
open class Repository<M : Any, ID : Any>(
    private val database: Database,
    modelClass: KClass<M>,
    idClass: KClass<ID>,
) {
    private val adapter = database.adapter(modelClass.java)
    private val key = adapter.primaryKey
        ?: throw DatabaseException("Model '${modelClass.java.name}' requires one @Id property for a repository")

    init {
        val expected = when (key.type) {
            DataType.INT -> Int::class
            DataType.TEXT -> String::class
        }
        if (idClass != expected) throw DatabaseException("Repository ID type must be ${expected.simpleName}")
    }

    /** Inserts one model; duplicate primary keys fail without changing stored data. */
    fun insert(model: M): Int = adapter.insert(database, model)

    /** Returns a detached model, or null if its key is absent. */
    fun findById(id: ID): M? = database.from(adapter.table).where(keyCondition(id)).map(adapter::read).singleOrNull()

    fun findAll(): List<M> = query().toList()

    /** Starts a model query; each terminal call observes current data. */
    fun query(): ModelQuery<M> = database.from(adapter.modelClass)

    /** Replaces the model with the same key. Returns 1 when present and 0 when absent. */
    fun update(model: M): Int = adapter.update(database, model)

    /** Returns 1 when a row was removed and 0 when the key was absent. */
    fun deleteById(id: ID): Int = database.deleteFrom(adapter.table, keyCondition(id))

    private fun keyCondition(id: ID): Condition {
        val value = when (id) {
            is Int -> Value.IntValue(id)
            is String -> Value.TextValue(id)
            else -> throw DatabaseException("Repository ID must be Int or String")
        }
        if (value.type != key.type) throw DatabaseException("Repository ID type does not match the primary key")
        return Condition(key, value)
    }
}

/** Creates a repository, checking its ID type against the generated model's @Id property. */
inline fun <reified M : Any, reified ID : Any> Database.repository(): Repository<M, ID> =
    Repository(this, M::class, ID::class)
