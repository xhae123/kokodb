package kokodb.mapping

import kokodb.Row
import kokodb.storage.Value

/** Generated-code SPI for model schema discovery and SQL result mapping. */
interface ModelAdapter<M : Any> {
    val modelClass: Class<M>
    val tableName: String
    val columns: List<ModelColumn>
    fun read(row: Row): M

    /** Maps stored values directly. Older adapters retain detached Row mapping through this default bridge. */
    fun readValues(values: ModelValues): M = read(Row(columns.mapIndexed { index, column ->
        column.name to when (column.type) {
            ModelColumn.Type.INT -> Value.IntValue(values.getInt(index))
            ModelColumn.Type.TEXT -> Value.TextValue(values.getString(index))
        }
    }.toMap()))
}

/** Schema metadata emitted by the model processor, in constructor order. */
data class ModelColumn(val name: String, val type: Type, val primaryKey: Boolean = false) {
    enum class Type { INT, TEXT }
}

/** Service-loaded entry point generated into each module containing annotated models. */
interface ModelProvider {
    fun adapters(): List<ModelAdapter<*>>
}
