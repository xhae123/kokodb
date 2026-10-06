package kokodb

import kokodb.mapping.ModelAdapter
import kotlin.reflect.KProperty1

/** A typed predicate from a model's property reference. Ownership is checked when attached to a query. */
class ModelCondition<M : Any> internal constructor(
    internal val property: KProperty1<M, *>,
    internal val value: Any,
)

// Concrete overloads prevent KProperty1's covariant value type from widening an invalid comparison to Any.
@JvmName("intPropertyEquals")
infix fun <M : Any> KProperty1<M, Int>.eq(value: Int): ModelCondition<M> = ModelCondition(this, value)

@JvmName("textPropertyEquals")
infix fun <M : Any> KProperty1<M, String>.eq(value: String): ModelCondition<M> = ModelCondition(this, value)

/** A model query. Terminal calls read current data and return reconstructed objects without reflection. */
class ModelQuery<M : Any> internal constructor(
    private val query: Query,
    private val adapter: ModelAdapter<M>,
) {
    fun where(condition: ModelCondition<M>): ModelQuery<M> =
        ModelQuery(query.where(adapter.condition(condition.property, condition.value)), adapter)

    fun toList(): List<M> = query.map(adapter::read)
}
