package kokodb

import kokodb.storage.Column
import kokodb.storage.DataType
import kokodb.storage.Table
import kokodb.storage.Value
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@DbTable("indexed_entries")
data class IndexEntry(@Id val id: Int, val name: String)

class PrimaryKeyLookupTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `indexed INT queries agree with scans through mutations rollback and recovery`() {
        val path = directory.resolve("index.koko")
        val random = Random(271018)
        Database.open(path).use { db ->
            db.transaction { repeat(100) { this("INSERT INTO indexed_entries VALUES (:id, :name)", mapOf("id" to it, "name" to "n$it")) } }
            repeat(80) { step ->
                val id = random.nextInt(100)
                assertQueries(db, id)
                when (step % 5) {
                    0 -> db("UPDATE indexed_entries SET name = :name WHERE id = :id", mapOf("id" to id, "name" to "changed$step"))
                    1 -> {
                        db("DELETE FROM indexed_entries WHERE id = :id", mapOf("id" to id))
                        db("INSERT INTO indexed_entries VALUES (:id, 'reinserted')", mapOf("id" to id))
                    }
                    2 -> db.transaction {
                        this("UPDATE indexed_entries SET id = :next WHERE id = :id", mapOf("id" to id, "next" to id + 100))
                        assertQueries(db, id)
                        assertQueries(db, id + 100)
                        this("UPDATE indexed_entries SET id = :id WHERE id = :next", mapOf("id" to id, "next" to id + 100))
                    }
                    3 -> assertFailsWith<IllegalStateException> {
                        db.transaction {
                            this("DELETE FROM indexed_entries WHERE id = :id", mapOf("id" to id))
                            assertQueries(db, id)
                            error("rollback")
                        }
                    }
                    else -> assertFailsWith<DatabaseException> {
                        db("UPDATE indexed_entries SET id = :next WHERE id = :id", mapOf("id" to id, "next" to (id + 1) % 100))
                    }
                }
                assertQueries(db, id)
                if (step % 20 == 0) db.checkpoint()
            }
        }
        Database.open(path).use { db -> for (id in -1..100) assertQueries(db, id) }
    }

    private fun assertQueries(db: Database, id: Int) {
        val all = db.query("SELECT * FROM indexed_entries")
        val expected = all.filter { it.getInt("id") == id }.map { IndexEntry(it.getInt("id"), it.getString("name")) }
        repeat(3) {
            assertEquals(expected, db.queryModels(IndexEntry::class.java, "SELECT name, id FROM indexed_entries WHERE id = :id", mapOf("id" to id)))
            assertEquals(expected.map { it.name }, db.query("SELECT name FROM indexed_entries WHERE id = :id", mapOf("id" to id)).map { it.getString("name") })
        }
    }

    @Test
    fun `TEXT primary key collisions preserve correct hits misses and scan fallback`() {
        val collisions = (0 until 256).map { bits -> (0 until 8).joinToString("") { if (bits and (1 shl it) == 0) "Aa" else "BB" } }
        assertEquals(1, collisions.map { it.hashCode() }.toSet().size)
        Database.inMemory().use { db ->
            db("CREATE TABLE names (id TEXT PRIMARY KEY, value INT)")
            for ((id, key) in collisions.withIndex()) db("INSERT INTO names VALUES (:key, :id)", mapOf("key" to key, "id" to id))
            repeat(2) {
                for ((id, key) in collisions.withIndex()) {
                    assertEquals(id, db.query("SELECT * FROM names WHERE id = :key", mapOf("key" to key)).single().getInt("value"))
                }
                assertEquals(emptyList(), db.query("SELECT * FROM names WHERE id = 'absent'"))
            }
            db("UPDATE names SET id = 'changed' WHERE value = 17")
            assertEquals(emptyList(), db.query("SELECT * FROM names WHERE id = :key", mapOf("key" to collisions[17])))
            assertEquals(17, db.query("SELECT * FROM names WHERE id = 'changed'").single().getInt("value"))
        }
    }

    @Test
    fun `missing indexed keys do not bypass projection or parameter validation`() {
        Database.inMemory().use { db ->
            repeat(40) { db("INSERT INTO indexed_entries VALUES (:id, 'name')", mapOf("id" to it)) }
            assertQueries(db, -1)
            assertFailsWith<DatabaseException> { db.query("SELECT missing FROM indexed_entries WHERE id = -1") }
            assertFailsWith<DatabaseException> { db.query("SELECT * FROM indexed_entries WHERE id = :id") }
            assertFailsWith<DatabaseException> { db.query("SELECT * FROM indexed_entries WHERE id = 'wrong type'") }
            assertFailsWith<DatabaseException> { db.queryModels(IndexEntry::class.java, "SELECT id FROM indexed_entries WHERE id = -1") }
        }
    }

    @Test
    fun `lookup caching does not allocate for first reads and forks share only matching snapshots`() {
        val table = Table(listOf(Column("id", DataType.INT, true)))
        val rows = List(10000) { listOf(Value.IntValue(it)) }
        table.publishRows(rows)
        assertEquals(rows[5], table.findPrimaryKey(Value.IntValue(5)))
        assertNull(positions(table))
        assertEquals(rows[6], table.findPrimaryKey(Value.IntValue(6)))
        val index = assertNotNull(positions(table))
        assertEquals(65536, index.size * Int.SIZE_BYTES)
        val fork = table.fork()
        assertSame(index, positions(fork))
        fork.publishRows(rows.drop(1))
        assertNull(positions(fork))
        assertNull(fork.findPrimaryKey(Value.IntValue(0)))
        assertNull(positions(fork))
        assertEquals(rows[0], table.findPrimaryKey(Value.IntValue(0)))
        assertSame(index, positions(table))
    }

    @Test
    fun `tiny snapshots and adversarial builds remain scan based`() {
        val tiny = Table(listOf(Column("id", DataType.INT, true)))
        tiny.publishRows(listOf(listOf(Value.IntValue(Int.MIN_VALUE)), listOf(Value.IntValue(Int.MAX_VALUE))))
        repeat(5) { assertNotNull(tiny.findPrimaryKey(Value.IntValue(Int.MIN_VALUE))) }
        assertNull(positions(tiny))
        val colliding = Table(listOf(Column("id", DataType.TEXT, true)))
        val keys = (0 until 256).map { n -> (0 until 8).joinToString("") { if (n and (1 shl it) == 0) "Aa" else "BB" } }
        colliding.publishRows(keys.map { listOf(Value.TextValue(it)) })
        repeat(5) { assertNotNull(colliding.findPrimaryKey(Value.TextValue(keys[200]))) }
        assertNull(positions(colliding))
    }

    private fun positions(table: Table): IntArray? {
        val snapshot = Table::class.java.getDeclaredField("snapshot").also { it.isAccessible = true }.get(table)
        return snapshot.javaClass.getDeclaredField("positions").also { it.isAccessible = true }.get(snapshot) as IntArray?
    }
}
