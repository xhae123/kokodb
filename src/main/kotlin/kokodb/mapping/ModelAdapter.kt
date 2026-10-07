package kokodb.mapping

import kokodb.Row

/** Generated-code SPI for model schema discovery and SQL result mapping. */
interface ModelAdapter<M : Any> {
    val modelClass: Class<M>
    val tableName: String
    val columns: List<ModelColumn>
    fun read(row: Row): M
}

/** Schema metadata emitted by the model processor, in constructor order. */
data class ModelColumn(val name: String, val type: Type, val primaryKey: Boolean = false) {
    enum class Type { INT, TEXT }
}

/** Service-loaded entry point generated into each module containing annotated models. */
interface ModelProvider {
    fun adapters(): List<ModelAdapter<*>>
}
