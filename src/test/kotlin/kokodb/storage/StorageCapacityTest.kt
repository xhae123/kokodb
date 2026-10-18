package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageCapacityTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("capacity.koko")
    private val wal get() = directory.resolve("capacity.koko.wal")
    private val loader = object : ClassLoader(Database::class.java.classLoader) {
        override fun getResources(name: String) =
            if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
            else super.getResources(name)
    }

    private fun open(limit: Int) = Database.open(path, loader, StoreIO(), limit)

    private fun sizeOf(vararg names: String): Int {
        val catalog = Catalog()
        catalog.create("items", listOf(Column("id", DataType.INT), Column("name", DataType.TEXT)))
        catalog.table("items").publishRows(names.mapIndexed { index, name -> listOf(Value.IntValue(index), Value.TextValue(name)) })
        return StorageCodec.encodeSnapshot(Snapshot(UUID(0, 1), 0, catalog)).size
    }

    @Test
    fun `exact Unicode capacity commits but one extra byte rejects before append`() {
        val text = "Aé한😀\u0000"
        val limit = sizeOf(text)
        open(limit).use { db ->
            db("CREATE TABLE items (id INT, name TEXT)")
            db("INSERT INTO items VALUES (0, :text)", mapOf("text" to text))
            val before = Files.readAllBytes(wal)
            val failure = assertFailsWith<DatabaseException> {
                db("UPDATE items SET name = :text", mapOf("text" to "$text!"))
            }
            assertTrue(failure.message!!.contains("snapshot size limit"))
            assertContentEquals(before, Files.readAllBytes(wal))
            assertEquals(text, db.query("SELECT name FROM items").single().getString("name"))
            db.checkpoint()
            assertEquals(limit.toLong(), Files.size(path))
        }
        open(limit).use { db ->
            assertEquals(text, db.query("SELECT name FROM items").single().getString("name"))
            assertFailsWith<DatabaseException> { db("INSERT INTO items VALUES (1, '')") }
            db("DELETE FROM items")
            db("INSERT INTO items VALUES (2, :text)", mapOf("text" to text))
            db.checkpoint()
        }
    }

    @Test
    fun `oversized multi table commit preserves all prior rows schemas and WAL`() {
        val text = "x".repeat(40)
        val limit = sizeOf(text)
        open(limit).use { db ->
            db("CREATE TABLE items (id INT, name TEXT)")
            db("INSERT INTO items VALUES (0, :text)", mapOf("text" to text))
            val before = Files.readAllBytes(wal)
            assertFailsWith<DatabaseException> {
                db.transaction {
                    this("UPDATE items SET name = 'short'")
                    this("CREATE TABLE audit (message TEXT)")
                    this("INSERT INTO audit VALUES (:text)", mapOf("text" to text))
                }
            }
            assertContentEquals(before, Files.readAllBytes(wal))
            assertEquals(text, db.query("SELECT name FROM items").single().getString("name"))
            assertFailsWith<DatabaseException> { db.query("SELECT * FROM audit") }
            db("UPDATE items SET name = 'healthy'")
            db.checkpoint()
        }
        open(limit).use { db -> assertEquals("healthy", db.query("SELECT name FROM items").single().getString("name")) }
    }

    @Test
    fun `only final committed size matters and WAL recovery restores capacity accounting`() {
        val text = "x".repeat(30)
        val limit = sizeOf(text)
        open(limit).use { db ->
            db("CREATE TABLE items (id INT, name TEXT)")
            db("INSERT INTO items VALUES (0, :text)", mapOf("text" to text))
            db.transaction {
                this("INSERT INTO items VALUES (1, :text)", mapOf("text" to text))
                this("DELETE FROM items WHERE id = 0")
            }
        }
        open(limit).use { db ->
            val before = Files.readAllBytes(wal)
            assertFailsWith<DatabaseException> { db("INSERT INTO items VALUES (2, '')") }
            assertContentEquals(before, Files.readAllBytes(wal))
            assertEquals(1, db.query("SELECT id FROM items").single().getInt("id"))
            db.checkpoint()
            assertEquals(limit.toLong(), Files.size(path))
        }
    }

    @Test
    fun `older oversized catalogs can shrink incrementally until checkpoint fits`() {
        val text = "x".repeat(40)
        Database.open(path, loader).use { db ->
            db("CREATE TABLE items (id INT, name TEXT)")
            repeat(3) { db("INSERT INTO items VALUES (:id, :text)", mapOf("id" to it, "text" to text)) }
        }
        val limit = sizeOf(text)
        open(limit).use { db ->
            assertEquals(3, db.query("SELECT id FROM items").size)
            assertFailsWith<DatabaseException> { db.checkpoint() }
            val before = Files.readAllBytes(wal)
            assertFailsWith<DatabaseException> { db("INSERT INTO items VALUES (4, '')") }
            assertFailsWith<DatabaseException> { db("UPDATE items SET id = 4 WHERE id = 0") }
            assertContentEquals(before, Files.readAllBytes(wal))
            db("DELETE FROM items WHERE id = 0")
            assertFailsWith<DatabaseException> { db.checkpoint() }
        }
        open(limit).use { db ->
            db("DELETE FROM items WHERE id = 1")
            db.checkpoint()
            assertEquals(limit.toLong(), Files.size(path))
        }
        open(limit).use { db -> assertEquals(2, db.query("SELECT id FROM items").single().getInt("id")) }
    }
}
