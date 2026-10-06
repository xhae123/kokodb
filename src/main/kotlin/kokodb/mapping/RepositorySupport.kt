package kokodb.mapping

import kokodb.Condition
import kokodb.Database
import kokodb.DatabaseException
import kokodb.ModelQuery
import kokodb.Repository
import kokodb.storage.DataType
import kokodb.storage.Value
import kotlin.reflect.KClass

/** Generated-repository SPI. Applications declare interfaces extending [Repository]. */
class RepositorySupport<M : Any, ID : Any>(
    private val database: Database,
    modelClass: KClass<M>,
    idClass: KClass<ID>,
) : Repository<M, ID> {
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

    override fun insert(model: M): Int = adapter.insert(database, model)

    override fun findById(id: ID): M? = database.from(adapter.table).where(keyCondition(id)).map(adapter::read).singleOrNull()

    override fun findAll(): List<M> = query().toList()

    override fun query(): ModelQuery<M> = database.from(adapter.modelClass)

    override fun update(model: M): Int = adapter.update(database, model)

    override fun deleteById(id: ID): Int = database.deleteFrom(adapter.table, keyCondition(id))

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
