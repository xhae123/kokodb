package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpliceRecoveryTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `random mixed transactions preserve reference state through checkpoints and reopen`() {
        val path = directory.resolve("mixed.koko")
        val random = Random(181026)
        var expected = mutableMapOf<Int, String>()
        Database.open(path).use { it("CREATE TABLE items (id INT PRIMARY KEY, name TEXT)") }
        repeat(12) { epoch ->
            Database.open(path).use { db ->
                repeat(15) { transaction ->
                    val candidate = expected.toMutableMap()
                    val rollback = transaction % 5 == 0
                    val operation = {
                        db.transaction {
                            repeat(4) {
                                val id = random.nextInt(40)
                                val name = "v${random.nextInt(5)} 한글"
                                when (random.nextInt(4)) {
                                    0 -> if (id !in candidate) {
                                        this("INSERT INTO items VALUES (:id, :name)", mapOf("id" to id, "name" to name))
                                        candidate[id] = name
                                    }
                                    1 -> {
                                        assertEquals(if (id in candidate) 1 else 0,
                                            this("UPDATE items SET name = :name WHERE id = :id", mapOf("id" to id, "name" to name)))
                                        if (id in candidate) candidate[id] = name
                                    }
                                    2 -> {
                                        assertEquals(if (candidate.remove(id) != null) 1 else 0,
                                            this("DELETE FROM items WHERE id = :id", mapOf("id" to id)))
                                    }
                                    else -> {
                                        val newId = random.nextInt(40)
                                        if (id in candidate && newId !in candidate) {
                                            this("UPDATE items SET id = :next WHERE id = :id", mapOf("id" to id, "next" to newId))
                                            candidate[newId] = candidate.remove(id)!!
                                        }
                                    }
                                }
                            }
                            if (rollback) error("discard candidate")
                        }
                    }
                    if (rollback) assertFailsWith<IllegalStateException> { operation() }
                    else { operation(); expected = candidate }
                    assertEquals(expected, db.query("SELECT * FROM items").associate { it.getInt("id") to it.getString("name") })
                }
                if (epoch % 3 == 0) db.checkpoint()
            }
        }
        Database.open(path).use { db ->
            assertEquals(expected, db.query("SELECT * FROM items").associate { it.getInt("id") to it.getString("name") })
        }
    }

    @Test
    fun `keyless duplicate rows and delete then insert survive replay`() {
        val path = directory.resolve("duplicates.koko")
        Database.open(path).use { db ->
            db("CREATE TABLE items (name TEXT)")
            db.transaction { repeat(4) { this("INSERT INTO items VALUES ('same')") } }
            db("INSERT INTO items VALUES ('other')")
            db.transaction {
                this("DELETE FROM items WHERE name = 'same'")
                this("INSERT INTO items VALUES ('same')")
                this("INSERT INTO items VALUES ('same')")
            }
        }
        Database.open(path).use { db ->
            assertEquals(listOf("other", "same", "same"), db.query("SELECT * FROM items").map { it.getString("name") })
        }
    }

    @Test
    fun `legacy WAL migrates only after successful validation and retains all committed rows`() {
        val path = directory.resolve("legacy.koko")
        val wal = path.resolveSibling("legacy.koko.wal")
        val identity = UUID(18, 26)
        val catalog = legacyCatalog()
        Files.write(path, StorageCodec.encodeSnapshot(Snapshot(identity, 0, Catalog())))
        val bytes = StorageCodec.encodeWalHeader(WalHeader(identity, 0, 1)) + StorageCodec.encodeFrame(1, catalog.entries())
        val corrupt = bytes.clone().also { it[it.lastIndex - 12] = (it[it.lastIndex - 12].toInt() xor 1).toByte() }
        Files.write(wal, corrupt)
        val snapshot = Files.readAllBytes(path)
        assertFailsWith<DatabaseException> { Database.open(path) }
        assertContentEquals(snapshot, Files.readAllBytes(path))
        assertContentEquals(corrupt, Files.readAllBytes(wal))
        Files.write(wal, bytes)
        Database.open(path).use { db ->
            assertEquals("legacy", db.query("SELECT * FROM legacy").single().getString("name"))
            assertEquals(2, StorageCodec.decodeWalHeader(Files.readAllBytes(wal).copyOf(40)).version)
            db("UPDATE legacy SET id = 2, name = 'new' WHERE id = 1")
        }
        Database.open(path).use { db ->
            val row = db.query("SELECT * FROM legacy").single()
            assertEquals(2, row.getInt("id"))
            assertEquals("new", row.getString("name"))
        }
    }

    companion object {
        internal fun legacyCatalog() = Catalog().also { catalog ->
            catalog.create("legacy", listOf(Column("id", DataType.INT, true), Column("name", DataType.TEXT)))
            catalog.table("legacy").publishRows(listOf(listOf(Value.IntValue(1), Value.TextValue("legacy"))))
        }
    }
}
