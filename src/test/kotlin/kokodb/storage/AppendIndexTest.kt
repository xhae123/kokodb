package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppendIndexTest {
    @Test
    fun `immutable key indexes preserve earlier versions across sorted reverse and shuffled inserts`() {
        val ordered = listOf(Int.MIN_VALUE, Int.MAX_VALUE) + (0 until 10000).toList()
        for (keys in listOf(ordered, ordered.reversed(), ordered.shuffled(Random(44)))) {
            var index = PrimaryKeyIndex.from(emptyList(), 0)
            val checkpoints = mutableListOf<Pair<PrimaryKeyIndex, Int>>()
            for ((position, value) in keys.withIndex()) {
                if (position % 1000 == 0) checkpoints.add(index to position)
                index = index.added(Value.IntValue(value), position)
            }
            for ((position, value) in keys.withIndex()) assertEquals(position, index.find(Value.IntValue(value)))
            assertEquals(-1, index.find(Value.IntValue(-1)))
            for ((earlier, size) in checkpoints) {
                for (position in listOf(0, size - 1, size, keys.lastIndex).filter { it >= 0 }) {
                    assertEquals(if (position < size) position else -1, earlier.find(Value.IntValue(keys[position])))
                }
            }
        }
    }

    @Test
    fun `appended forks retain independent keys and value-only updates retain the matching index`() {
        val original = table()
        repeat(100) { original.append(row(it)) }
        val left = original.fork()
        val right = original.fork()
        left.append(row(100))
        right.append(row(101))
        assertNull(original.findPrimaryKey(Value.IntValue(100)))
        assertNull(original.findPrimaryKey(Value.IntValue(101)))
        assertNull(left.findPrimaryKey(Value.IntValue(101)))
        assertNull(right.findPrimaryKey(Value.IntValue(100)))
        assertEquals(row(100), left.findPrimaryKey(Value.IntValue(100)))
        assertEquals(row(101), right.findPrimaryKey(Value.IntValue(101)))
        val keys = assertNotNull(index(left))
        left.publishValues(left.rows.updated(10, listOf(Value.IntValue(10), Value.IntValue(999))))
        assertSame(keys, index(left))
        assertEquals(Value.IntValue(999), left.findPrimaryKey(Value.IntValue(10))!![1])
        assertEquals(row(10), original.findPrimaryKey(Value.IntValue(10)))
        assertFailsWith<DatabaseException> { left.append(row(100)) }
        assertEquals(101, left.rows.size)
    }

    @Test
    fun `append cache respects its bound and avoids construction for isolated mutations`() {
        val table = table()
        table.publishRows(List(65532, ::row))
        table.append(row(65532))
        assertNull(index(table))
        table.append(row(65533))
        assertNull(index(table))
        table.append(row(65534))
        assertNotNull(index(table))
        table.append(row(65535))
        assertNotNull(index(table))
        table.append(row(65536))
        assertNull(index(table))
        for (key in listOf(0, 65534, 65535, 65536)) {
            assertEquals(row(key), table.findPrimaryKey(Value.IntValue(key)))
            assertFailsWith<DatabaseException> { table.append(row(key)) }
        }
        assertEquals(65537, table.rows.size)
    }

    @Test
    fun `colliding and unicode text keys stay distinct across append lookup and duplicates`() {
        val table = Table(listOf(Column("id", DataType.TEXT, true)))
        val keys = (0 until 256).map { n -> (0 until 8).joinToString("") { if (n and (1 shl it) == 0) "Aa" else "BB" } } +
            listOf("", "\u0000", "é", "e\u0301", "😀", "😃")
        for (key in keys) table.append(listOf(Value.TextValue(key)))
        for (key in keys) {
            assertEquals(listOf(Value.TextValue(key)), table.findPrimaryKey(Value.TextValue(key)))
            assertFailsWith<DatabaseException> { table.append(listOf(Value.TextValue(key))) }
        }
        assertNull(table.findPrimaryKey(Value.TextValue("absent")))
        assertEquals(keys.size, table.rows.size)
    }

    @Test
    fun `rollback and key-changing statements cannot expose or retain aborted appended keys`() {
        Database.inMemory().use { db ->
            db("CREATE TABLE append_test (id INT PRIMARY KEY, value INT)")
            db.transaction { repeat(100) { this("INSERT INTO append_test VALUES (:id, 0)", mapOf("id" to it)) } }
            val before = db.query("SELECT * FROM append_test").map { it.getInt("id") }.toSet()
            assertFailsWith<DatabaseException> {
                db.transaction {
                    repeat(100) { this("INSERT INTO append_test VALUES (:id, 1)", mapOf("id" to it + 100)) }
                    assertFailsWith<DatabaseException> { this("INSERT INTO append_test VALUES (199, 2)") }
                }
            }
            assertEquals(before, db.query("SELECT * FROM append_test").map { it.getInt("id") }.toSet())
            db("INSERT INTO append_test VALUES (199, 3)")
            db("UPDATE append_test SET id = 299 WHERE id = 199")
            db("INSERT INTO append_test VALUES (199, 4)")
            db("DELETE FROM append_test WHERE id = 50")
            db("INSERT INTO append_test VALUES (50, 5)")
            assertEquals(4, db.query("SELECT value FROM append_test WHERE id = 199").single().getInt("value"))
            assertEquals(3, db.query("SELECT value FROM append_test WHERE id = 299").single().getInt("value"))
            assertEquals(5, db.query("SELECT value FROM append_test WHERE id = 50").single().getInt("value"))
            assertEquals(102, db.query("SELECT * FROM append_test").size)
        }
    }

    private fun table() = Table(listOf(Column("id", DataType.INT, true), Column("value", DataType.INT)))
    private fun row(id: Int): List<Value> = listOf(Value.IntValue(id), Value.IntValue(id))
    private fun index(table: Table): Any? {
        val snapshot = Table::class.java.getDeclaredField("snapshot").also { it.isAccessible = true }.get(table)
        return snapshot.javaClass.getDeclaredField("appendIndex").also { it.isAccessible = true }.get(snapshot)
    }
}
