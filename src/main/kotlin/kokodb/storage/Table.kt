package kokodb.storage

internal data class Column(val name: String, val type: DataType, val primaryKey: Boolean = false)

internal class Table(val columns: List<Column>) {
    // Values follow schema order; insertion must validate the entire row before appending it.
    val rows = mutableListOf<List<Value>>()
}
