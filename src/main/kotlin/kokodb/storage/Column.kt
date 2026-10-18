package kokodb.storage

internal data class Column(val name: String, val type: DataType, val primaryKey: Boolean = false)
