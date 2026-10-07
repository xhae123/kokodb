package kokodb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlConstraintTest {
    @Test
    fun `INT primary keys reject duplicate inserts without changing stored rows`() {
        val db = Database.inMemory()
        db.execute("CREATE TABLE primary_ints (id INT PRIMARY KEY, name TEXT)")
        db.execute("INSERT INTO primary_ints VALUES (1, 'Original')")
        assertFailsWith<DatabaseException> { db.execute("INSERT INTO primary_ints VALUES (1, 'Duplicate')") }
        assertEquals("Original", db.query("SELECT * FROM primary_ints").single().getString("name"))
        db.execute("INSERT INTO primary_ints VALUES (2, 'Other')")
        assertEquals(setOf(1, 2), db.query("SELECT * FROM primary_ints").map { it.getInt("id") }.toSet())
    }

    @Test
    fun `TEXT primary keys compare exact values`() {
        val db = Database.inMemory()
        db.execute("CREATE TABLE primary_texts (name TEXT PRIMARY KEY, value INT)")
        db.execute("INSERT INTO primary_texts VALUES ('theme', 1)")
        assertFailsWith<DatabaseException> { db.execute("INSERT INTO primary_texts VALUES ('theme', 2)") }
        db.execute("INSERT INTO primary_texts VALUES ('THEME', 3)")
        assertEquals(1, db.query("SELECT value FROM primary_texts WHERE name = 'theme'").single().getInt("value"))
        assertEquals(2, db.query("SELECT * FROM primary_texts").size)
    }

    @Test
    fun `multiple primary keys reject creation without reserving the table name`() {
        val db = Database.inMemory()
        assertFailsWith<DatabaseException> { db.execute("CREATE TABLE invalid_keys (a INT PRIMARY KEY, b TEXT PRIMARY KEY)") }
        db.execute("CREATE TABLE invalid_keys (a INT PRIMARY KEY)")
        assertTrue(db.query("SELECT * FROM invalid_keys").isEmpty())
    }
}
