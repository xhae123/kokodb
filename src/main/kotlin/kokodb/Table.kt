package kokodb

import kokodb.storage.Column as StoredColumn
import kokodb.storage.DataType
import kokodb.storage.Value
import java.util.Locale

/** A Kotlin schema definition. Column registration is frozen on first database use. */
open class Table(name: String) {
    val tableName: String = identifier(name)
    private val registered = mutableListOf<Column<*>>()
    private var frozen = false

    protected fun int(name: String, primaryKey: Boolean = false): Column<Int> = register(
        name, DataType.INT, primaryKey, { Value.IntValue(it) }, { (it as Value.IntValue).value }
    )

    protected fun text(name: String, primaryKey: Boolean = false): Column<String> = register(
        name, DataType.TEXT, primaryKey, { Value.TextValue(it) }, { (it as Value.TextValue).value }
    )

    internal fun columns(): List<Column<*>> {
        if (registered.isEmpty()) throw DatabaseException("Table '$tableName' must have at least one column")
        frozen = true
        return registered.toList()
    }

    internal fun schema(): List<StoredColumn> = columns().map { StoredColumn(it.name, it.type, it.primaryKey) }

    private fun <T : Any> register(
        name: String,
        type: DataType,
        primaryKey: Boolean,
        encode: (T) -> Value,
        decode: (Value) -> T,
    ): Column<T> {
        if (frozen) throw DatabaseException("Table '$tableName' definition is frozen")
        val normalized = identifier(name)
        if (registered.any { it.name == normalized }) throw DatabaseException("Duplicate column '$normalized'")
        if (primaryKey && registered.any { it.primaryKey }) throw DatabaseException("Only one primary key column is supported")
        return Column(this, normalized, type, primaryKey, encode, decode).also { registered.add(it) }
    }
}

private fun identifier(name: String): String {
    if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
        throw DatabaseException("Invalid identifier '$name'")
    }
    return name.lowercase(Locale.ROOT)
}
