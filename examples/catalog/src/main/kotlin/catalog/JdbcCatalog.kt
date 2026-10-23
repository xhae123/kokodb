package catalog

import java.sql.DriverManager
import java.sql.Statement

internal object JdbcCatalog {
    fun clean(entries: List<FileEntry>): List<FileEntry> =
        DriverManager.getConnection("jdbc:h2:mem:catalog", "sa", "").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE files (id INT PRIMARY KEY, path VARCHAR NOT NULL, kind VARCHAR NOT NULL)")
            }
            connection.autoCommit = false
            try {
                connection.prepareStatement("INSERT INTO files VALUES (?, ?, ?)").use { insert ->
                    for (entry in entries) {
                        insert.setInt(1, entry.id)
                        insert.setString(2, entry.path)
                        insert.setString(3, entry.kind)
                        insert.addBatch()
                    }
                    val counts = insert.executeBatch()
                    check(counts.size == entries.size && counts.all { it == 1 || it == Statement.SUCCESS_NO_INFO })
                }
                connection.prepareStatement("UPDATE files SET path = ? WHERE id = ?").use {
                    it.setString(1, "src/renamed.kt")
                    it.setInt(2, 0)
                    check(it.executeUpdate() == 1)
                }
                connection.prepareStatement("DELETE FROM files WHERE kind = ?").use {
                    it.setString(1, "generated")
                    it.executeUpdate()
                }
                connection.commit()
            } catch (failure: Throwable) {
                try { connection.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                throw failure
            }
            connection.prepareStatement("SELECT id, path, kind FROM files").use {
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(FileEntry(rows.getInt(1), rows.getString(2), rows.getString(3)))
                    }
                }
            }
        }
}
