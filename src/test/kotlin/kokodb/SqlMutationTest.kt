package kokodb

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlMutationTest {
    private fun users(empty: Boolean = false): Database = Database.inMemory().also { db ->
        db.execute("CREATE TABLE mutation_users (id INT PRIMARY KEY, name TEXT)")
        if (!empty) {
            db.execute("INSERT INTO mutation_users VALUES (1, 'Same')")
            db.execute("INSERT INTO mutation_users VALUES (2, 'Same')")
            db.execute("INSERT INTO mutation_users VALUES (3, 'Other')")
        }
    }

    private fun rows(db: Database): Map<Int, String> = db.query("SELECT * FROM mutation_users")
        .associate { it.getInt("id") to it.getString("name") }

    @Test
    fun `UPDATE changes only assigned columns in matching rows`() {
        val db = users()
        assertEquals(2, db.execute("UPDATE mutation_users SET name = :name WHERE name = :old", mapOf("name" to "Changed", "old" to "Same")))
        assertEquals(mapOf(1 to "Changed", 2 to "Changed", 3 to "Other"), rows(db))
        assertEquals(1, db.execute("UPDATE mutation_users SET id = 4, name = 'Moved' WHERE id = 3"))
        assertEquals(mapOf(1 to "Changed", 2 to "Changed", 4 to "Moved"), rows(db))
    }

    @Test
    fun `unconditional UPDATE and DELETE affect all rows`() {
        val db = users()
        assertEquals(3, db.execute("uPdAtE MUTATION_USERS sEt NAME = 'All';"))
        assertEquals(setOf("All"), rows(db).values.toSet())
        assertEquals(3, db.execute("dElEtE fRoM MUTATION_USERS;"))
        assertTrue(rows(db).isEmpty())
        assertEquals(0, db.execute("UPDATE mutation_users SET name = 'Empty'"))
        assertEquals(0, db.execute("DELETE FROM mutation_users"))
    }

    @Test
    fun `affected counts include matches whose values do not change`() {
        val db = users()
        assertEquals(2, db.execute("UPDATE mutation_users SET name = 'Same' WHERE name = 'Same'"))
        assertEquals(0, db.execute("UPDATE mutation_users SET name = 'Absent' WHERE id = 99"))
        assertEquals(0, db.execute("DELETE FROM mutation_users WHERE id = 99"))
        assertEquals(3, rows(db).size)
    }

    @Test
    fun `DELETE filters by bound values and preserves other rows`() {
        val db = users()
        assertEquals(2, db.execute("DELETE FROM mutation_users WHERE name = :name", mapOf("name" to "Same")))
        assertEquals(mapOf(3 to "Other"), rows(db))
        assertEquals(1, db.execute("DELETE FROM mutation_users WHERE id = 3"))
        assertTrue(rows(db).isEmpty())
    }

    @Test
    fun `UPDATE failures leave every original row unchanged`() {
        val db = users()
        val original = rows(db)
        for (sql in listOf(
            "UPDATE mutation_users SET name = 'Changed', missing = 1",
            "UPDATE mutation_users SET name = 'Changed', id = 'Wrong'",
            "UPDATE mutation_users SET name = 'Changed', id = :missing",
            "UPDATE mutation_users SET name = 'Changed', NAME = 'Duplicate'",
            "UPDATE mutation_users SET name = 'Changed' WHERE missing = 1",
            "UPDATE mutation_users SET name = 'Changed' WHERE id = 'Wrong'",
            "UPDATE mutation_users SET name = 'Changed' WHERE id = :missing",
        )) {
            assertFailsWith<DatabaseException>(sql) { db.execute(sql) }
            assertEquals(original, rows(db), sql)
        }
    }

    @Test
    fun `collisions with untouched and changed rows reject the entire UPDATE`() {
        val db = users()
        val original = rows(db)
        for (sql in listOf(
            "UPDATE mutation_users SET name = 'Changed', id = 2 WHERE id = 1",
            "UPDATE mutation_users SET name = 'Changed', id = 4 WHERE name = 'Same'",
            "UPDATE mutation_users SET name = 'Changed', id = 1",
        )) {
            assertFailsWith<DatabaseException>(sql) { db.execute(sql) }
            assertEquals(original, rows(db))
        }
        db.execute("UPDATE mutation_users SET id = 4 WHERE id = 1")
        assertEquals(setOf(2, 3, 4), rows(db).keys)
    }

    @Test
    fun `DELETE validation failures preserve all rows`() {
        val db = users()
        val original = rows(db)
        for (sql in listOf(
            "DELETE FROM mutation_users WHERE missing = 1",
            "DELETE FROM mutation_users WHERE id = 'Wrong'",
            "DELETE FROM mutation_users WHERE id = :missing",
        )) {
            assertFailsWith<DatabaseException> { db.execute(sql) }
            assertEquals(original, rows(db))
        }
    }

    @Test
    fun `invalid assignments and conditions fail even when no rows match`() {
        val db = users(empty = true)
        for (sql in listOf(
            "UPDATE mutation_users SET missing = 1",
            "UPDATE mutation_users SET name = 1",
            "UPDATE mutation_users SET id = :missing",
            "UPDATE mutation_users SET name = 'First', NAME = 'Second'",
            "UPDATE mutation_users SET name = 'Valid' WHERE missing = 1",
            "DELETE FROM mutation_users WHERE missing = 1",
            "DELETE FROM mutation_users WHERE id = :missing",
        )) assertFailsWith<DatabaseException>(sql) { db.execute(sql) }
        for (value in listOf(null, 1L, true, "one")) {
            assertFailsWith<DatabaseException> { db.execute("UPDATE mutation_users SET id = :id", mapOf("id" to value)) }
            assertFailsWith<DatabaseException> { db.execute("DELETE FROM mutation_users WHERE id = :id", mapOf("id" to value)) }
        }
        assertTrue(rows(db).isEmpty())
    }

    @Test
    fun `malformed mutation syntax and query API misuse cannot write`() {
        val db = users()
        val original = rows(db)
        for (sql in listOf(
            "UPDATE mutation_users", "UPDATE mutation_users SET", "UPDATE mutation_users SET name",
            "UPDATE mutation_users SET name =", "UPDATE mutation_users SET name = 'Changed',",
            "UPDATE mutation_users SET name = 'Changed' WHERE", "DELETE mutation_users",
            "DELETE FROM", "DELETE FROM mutation_users WHERE", "DELETE FROM mutation_users WHERE id > 1",
            "UPDATE mutation_users SET name = name", "DELETE FROM mutation_users; DELETE FROM mutation_users",
        )) assertFailsWith<SqlSyntaxException>(sql) { db.execute(sql) }
        for (sql in listOf("UPDATE mutation_users SET name = 'Changed'", "DELETE FROM mutation_users")) {
            assertFailsWith<DatabaseException> { db.query(sql) }
        }
        assertEquals(original, rows(db))
        assertFailsWith<DatabaseException> { db.execute("UPDATE missing SET name = 'Changed'") }
        assertFailsWith<DatabaseException> { db.execute("DELETE FROM missing") }
    }

    @Test
    fun `string primary keys reject collisions and preserve bound text as data`() {
        val db = Database.inMemory()
        db.execute("CREATE TABLE text_keys (name TEXT PRIMARY KEY, value INT)")
        db.execute("INSERT INTO text_keys VALUES ('theme', 1)")
        db.execute("INSERT INTO text_keys VALUES ('THEME', 2)")
        assertFailsWith<DatabaseException> { db.execute("UPDATE text_keys SET name = 'theme', value = 9 WHERE name = 'THEME'") }
        val payload = "theme'; DELETE FROM text_keys; --"
        assertEquals(1, db.execute("UPDATE text_keys SET name = :name WHERE name = 'theme'", mapOf("name" to payload)))
        assertEquals(2, db.query("SELECT * FROM text_keys").size)
        assertEquals(1, db.execute("DELETE FROM text_keys WHERE name = :name", mapOf("name" to payload)))
        assertEquals("THEME", db.query("SELECT * FROM text_keys").single().getString("name"))
    }

    @Test
    fun `random CRUD statements agree with a reference map`() {
        val db = users(empty = true)
        val expected = mutableMapOf<Int, String>()
        val random = Random(9107)
        repeat(500) { step ->
            val id = random.nextInt(20)
            val name = "name${random.nextInt(5)}"
            when (random.nextInt(5)) {
                0 -> if (id in expected) {
                    assertFailsWith<DatabaseException> { db.execute("INSERT INTO mutation_users VALUES (:id, :name)", mapOf("id" to id, "name" to name)) }
                } else {
                    db.execute("INSERT INTO mutation_users VALUES (:id, :name)", mapOf("id" to id, "name" to name))
                    expected[id] = name
                }
                1 -> {
                    assertEquals(if (id in expected) 1 else 0, db.execute("UPDATE mutation_users SET name = :name WHERE id = :id", mapOf("id" to id, "name" to name)))
                    if (id in expected) expected[id] = name
                }
                2 -> assertEquals(if (expected.remove(id) != null) 1 else 0, db.execute("DELETE FROM mutation_users WHERE id = :id", mapOf("id" to id)))
                3 -> {
                    val matching = expected.filterValues { it == name }.keys
                    assertEquals(matching.size, db.execute("UPDATE mutation_users SET name = :new WHERE name = :old", mapOf("new" to "batch$step", "old" to name)))
                    matching.forEach { expected[it] = "batch$step" }
                }
                4 -> {
                    val matching = expected.filterValues { it == name }.keys
                    assertEquals(matching.size, db.execute("DELETE FROM mutation_users WHERE name = :name", mapOf("name" to name)))
                    matching.forEach { expected.remove(it) }
                }
            }
            assertEquals(expected, rows(db), "step $step")
        }
    }
}
