package kokodb.storage

internal data class Column(val name: String, val type: DataType, val primaryKey: Boolean = false)

internal class Table(val columns: List<Column>) {
    var rows: List<List<Value>> = emptyList()
        private set

    // Publish only fully validated immutable row snapshots, so a failing statement cannot expose partial changes.
    fun publishRows(rows: List<List<Value>>) {
        this.rows = rows
    }

    fun fork(): Table = Table(columns).also { it.publishRows(rows) }
}
