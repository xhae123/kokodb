package kokodb.storage

import kokodb.DatabaseException

internal class Catalog {
    private val tables = mutableMapOf<String, Table>()

    fun create(name: String, columns: List<Column>) {
        if (name in tables) throw DatabaseException("Table '$name' already exists")
        val duplicate = columns.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) throw DatabaseException("Duplicate column '${duplicate.key}'")
        if (columns.count { it.primaryKey } > 1) throw DatabaseException("Only one primary key column is supported")
        tables[name] = Table(columns)
    }

    fun table(name: String): Table =
        tables[name] ?: throw DatabaseException("Unknown table '$name'")

    fun fork(): Catalog = Catalog().also { copy ->
        tables.forEach { (name, table) -> copy.tables[name] = table.fork() }
    }
}
