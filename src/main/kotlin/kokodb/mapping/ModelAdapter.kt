package kokodb.mapping

import kokodb.Condition
import kokodb.Column
import kokodb.Database
import kokodb.Row
import kokodb.Table
import kotlin.reflect.KProperty1

/** Generated-code SPI. Applications use their model classes rather than implementing adapters. */
interface ModelAdapter<M : Any> {
    val modelClass: Class<M>
    val table: Table
    val primaryKey: Column<*>?
    fun insert(database: Database, model: M): Int
    fun update(database: Database, model: M): Int
    fun read(row: Row): M
    fun condition(property: KProperty1<M, *>, value: Any): Condition
}

/** Service-loaded entry point generated into each module containing annotated models. */
interface ModelProvider {
    fun adapters(): List<ModelAdapter<*>>
}
