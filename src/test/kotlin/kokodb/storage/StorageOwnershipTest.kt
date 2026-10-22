package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StorageOwnershipTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `a nonempty ownership file is preserved and rejected before creating a store`() {
        val path = directory.resolve("app.koko")
        val lock = directory.resolve("app.koko.lock")
        val original = byteArrayOf(1, 2, 3, 4)
        Files.write(lock, original)

        assertFailsWith<DatabaseException> { Database.open(path).use { } }
        assertContentEquals(original, Files.readAllBytes(lock))
        assertFalse(Files.exists(path))
        assertFalse(Files.exists(directory.resolve("app.koko.wal")))

        Files.delete(lock)
        Database.open(path).use { it("CREATE TABLE retry (id INT)") }
        Database.open(path).use { assertEquals(0, it.query("SELECT * FROM retry").size) }
        assertEquals(0L, Files.size(lock))
    }

    @Test
    fun `a database snapshot cannot also become another database ownership file`() {
        val first = directory.resolve("app.koko.lock")
        val second = directory.resolve("app.koko")
        Database.open(first).use { db ->
            db("CREATE TABLE records (id INT PRIMARY KEY)")
            db("INSERT INTO records VALUES (1)")
            val snapshot = Files.readAllBytes(first)
            val walPath = directory.resolve("app.koko.lock.wal")
            val wal = Files.readAllBytes(walPath)

            assertFailsWith<DatabaseException> { Database.open(second).use { } }
            assertContentEquals(snapshot, Files.readAllBytes(first))
            assertContentEquals(wal, Files.readAllBytes(walPath))
            assertFalse(Files.exists(second))
            assertFalse(Files.exists(directory.resolve("app.koko.wal")))
            db.checkpoint()
            db("INSERT INTO records VALUES (2)")
        }
        Database.open(first).use { db ->
            assertEquals(setOf(1, 2), db.query("SELECT * FROM records").map { it.getInt("id") }.toSet())
        }
    }
}
