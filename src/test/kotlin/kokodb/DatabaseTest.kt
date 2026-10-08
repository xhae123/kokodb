package kokodb

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseTest {
    private fun users(): Database = Database.inMemory().also {
        it("CREATE TABLE users (id INT, name TEXT)")
    }

    private fun error(message: String, block: () -> Unit) {
        val failure = assertFailsWith<DatabaseException>(block = block)
        assertTrue(failure.message.orEmpty().contains(message), failure.message)
    }

    @Test
    fun `create insert and query through the public API`() {
        val db = Database.inMemory()
        assertEquals(0, db("CREATE TABLE users (id INT, name TEXT)"))
        assertEquals(1, db("INSERT INTO users VALUES (:id, :name)", mapOf("id" to 1, "name" to "Koko")))
        db("INSERT INTO users VALUES (2, 'Other')")

        val rows = db.query("SELECT name FROM users WHERE id = :id", mapOf("id" to 1))
        assertEquals("Koko", rows.single().getString("name"))
        error("Unknown result column 'id'") { rows.single().getInt("id") }
    }

    @Test
    fun `select all and unfiltered projection`() {
        val db = users()
        db("INSERT INTO users VALUES (1, 'Koko')")
        db("INSERT INTO users VALUES (2, 'Other')")
        assertEquals(setOf(1, 2), db.query("SELECT * FROM users").map { it.getInt("id") }.toSet())
        assertEquals(setOf("Koko", "Other"), db.query("SELECT name FROM users").map { it.getString("name") }.toSet())
    }

    @Test
    fun `filter by text or integer literal`() {
        val db = users()
        db("INSERT INTO users VALUES (1, 'Koko')")
        assertEquals(1, db.query("SELECT id FROM users WHERE name = 'Koko'").single().getInt("id"))
        assertEquals("Koko", db.query("SELECT name FROM users WHERE id = 1").single().getString("name"))
        assertTrue(db.query("SELECT * FROM users WHERE id = 2").isEmpty())
    }

    @Test
    fun `keywords and identifiers are case insensitive`() {
        val db = Database.inMemory()
        db("cReAtE tAbLe Users (ID int, Name text);")
        db("insert into USERS values (1, 'Koko');")
        assertEquals("Koko", db.query("select NAME from users where Id = 1;").single().getString("NAME"))
        error("already exists") { db("CREATE TABLE USERS (id INT)") }
    }

    @Test
    fun `identifier normalization is independent of the default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val db = Database.inMemory()
            db("CREATE TABLE ITEMS (ID INT)")
            db("INSERT INTO items VALUES (1)")
            assertEquals(1, db.query("SELECT id FROM items").single().getInt("ID"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `string literals preserve punctuation whitespace and escaped quotes`() {
        val db = users()
        db("INSERT INTO users VALUES (1, 'Koko''s :name; (home), = *')")
        assertEquals("Koko's :name; (home), = *", db.query("SELECT name FROM users").single().getString("name"))
        db("INSERT INTO users VALUES (2, '')")
        assertEquals("", db.query("SELECT name FROM users WHERE id = 2").single().getString("name"))
    }

    @Test
    fun `bound strings remain data instead of SQL`() {
        val db = users()
        val payload = "'); CREATE TABLE injected (id INT); --"
        db("INSERT INTO users VALUES (1, :name)", mapOf("name" to payload))
        assertEquals(payload, db.query("SELECT name FROM users WHERE name = :name", mapOf("name" to payload)).single().getString("name"))
        error("Unknown table 'injected'") { db.query("SELECT * FROM injected") }
    }

    @Test
    fun `INT accepts both signed boundaries`() {
        val db = Database.inMemory()
        db("CREATE TABLE numbers (n INT)")
        db("INSERT INTO numbers VALUES (-2147483648)")
        db("INSERT INTO numbers VALUES (:n)", mapOf("n" to Int.MAX_VALUE))
        assertEquals(setOf(Int.MIN_VALUE, Int.MAX_VALUE), db.query("SELECT * FROM numbers").map { it.getInt("n") }.toSet())
    }

    @Test
    fun `out of range integer literals report their position`() {
        val db = users()
        for (literal in listOf("2147483648", "-2147483649")) {
            val sql = "INSERT INTO users VALUES ($literal, 'Koko')"
            val failure = assertFailsWith<SqlSyntaxException> { db(sql) }
            assertEquals(sql.indexOf(literal), failure.position)
        }
        assertTrue(db.query("SELECT * FROM users").isEmpty())
    }

    @Test
    fun `syntax errors report zero based character offsets`() {
        val failure = assertFailsWith<SqlSyntaxException> { users().query("SELECT @ FROM users") }
        assertEquals(7, failure.position)
        assertTrue(failure.message.orEmpty().contains("position 7"))
    }

    @Test
    fun `unterminated string points to the opening quote`() {
        val sql = "INSERT INTO users VALUES (1, 'Koko)"
        val failure = assertFailsWith<SqlSyntaxException> { users()(sql) }
        assertEquals(sql.indexOf('\''), failure.position)
    }

    @Test
    fun `malformed and unsupported SQL is rejected`() {
        val db = users()
        for (sql in listOf(
            "", "CREATE TABLE empty ()", "CREATE TABLE invalid (id BOOLEAN)",
            "INSERT INTO users VALUES ()", "INSERT INTO users VALUES (1, 'Koko',)",
            "SELECT FROM users", "SELECT id, FROM users", "SELECT * FROM users WHERE id",
            "SELECT * FROM users WHERE id = -", "SELECT * FROM users WHERE id = :",
            "SELECT * FROM users WHERE id = NULL", "SELECT * FROM users WHERE id > 1",
            "SELECT * FROM users WHERE id = 1 AND name = 'Koko'", "SELECT * FROM users ORDER BY id",
            "SELECT * FROM users;;", "SELECT * FROM users; INSERT INTO users VALUES (1, 'Koko')",
            "INSERT INTO users VALUES (1.5, 'Koko')",
        )) {
            assertFailsWith<SqlSyntaxException>(sql) { db(sql) }
        }
        assertTrue(db.query("SELECT * FROM users").isEmpty())
    }

    @Test
    fun `duplicate column names reject table creation without changing the catalog`() {
        val db = Database.inMemory()
        error("Duplicate column 'id'") { db("CREATE TABLE users (id INT, ID TEXT)") }
        db("CREATE TABLE users (id INT)")
        assertTrue(db.query("SELECT * FROM users").isEmpty())
    }

    @Test
    fun `duplicate table creation preserves existing data`() {
        val db = users()
        db("INSERT INTO users VALUES (1, 'Koko')")
        error("already exists") { db("CREATE TABLE users (other TEXT)") }
        assertEquals("Koko", db.query("SELECT name FROM users").single().getString("name"))
    }

    @Test
    fun `unknown tables fail for inserts and queries`() {
        val db = Database.inMemory()
        error("Unknown table 'missing'") { db("INSERT INTO missing VALUES (1)") }
        error("Unknown table 'missing'") { db.query("SELECT * FROM missing") }
    }

    @Test
    fun `invalid column binding fails even on an empty table`() {
        val db = users()
        error("Unknown column 'missing'") { db.query("SELECT missing FROM users") }
        error("Unknown column 'missing'") { db.query("SELECT * FROM users WHERE missing = 1") }
        error("Missing parameter ':id'") { db.query("SELECT * FROM users WHERE id = :id") }
        error("expects INT, got TEXT") { db.query("SELECT * FROM users WHERE id = '1'") }
    }

    @Test
    fun `insertion errors do not leave partial rows`() {
        val db = users()
        error("expects 2 values") { db("INSERT INTO users VALUES (1)") }
        error("expects 2 values") { db("INSERT INTO users VALUES (1, 'Koko', 2)") }
        error("expects TEXT, got INT") { db("INSERT INTO users VALUES (1, 2)") }
        error("Missing parameter ':name'") { db("INSERT INTO users VALUES (1, :name)") }
        assertTrue(db.query("SELECT * FROM users").isEmpty())
        db("INSERT INTO users VALUES (2, 'Valid')")
        assertEquals(2, db.query("SELECT * FROM users").single().getInt("id"))
    }

    @Test
    fun `NULL and unsupported parameter types fail without coercion`() {
        val db = users()
        error("cannot be NULL") { db("INSERT INTO users VALUES (1, :name)", mapOf("name" to null)) }
        for (value in listOf(1L, true, 1.0, listOf(1))) {
            error("must be Int or String") { db("INSERT INTO users VALUES (:id, 'Koko')", mapOf("id" to value)) }
        }
        error("expects INT, got TEXT") { db("INSERT INTO users VALUES (:id, 'Koko')", mapOf("id" to "1")) }
        assertTrue(db.query("SELECT * FROM users").isEmpty())
    }

    @Test
    fun `parameter names are case sensitive and unused parameters are ignored`() {
        val db = users()
        error("Missing parameter ':ID'") { db("INSERT INTO users VALUES (:ID, 'Koko')", mapOf("id" to 1)) }
        db("INSERT INTO users VALUES (:ID, 'Koko')", mapOf("ID" to 1, "unused" to null))
        assertEquals(1, db.query("SELECT * FROM users").single().getInt("id"))
    }

    @Test
    fun `repeated parameters reuse their bound value`() {
        val db = Database.inMemory()
        db("CREATE TABLE pairs (a INT, b INT)")
        db("INSERT INTO pairs VALUES (:n, :n)", mapOf("n" to 3))
        val row = db.query("SELECT * FROM pairs").single()
        assertEquals(3, row.getInt("a"))
        assertEquals(3, row.getInt("b"))
    }

    @Test
    fun `wrong API rejects statements before mutating data`() {
        val db = users()
        error("Use query()") { db("SELECT * FROM users") }
        error("Use the database call") { db.query("INSERT INTO users VALUES (1, 'Koko')") }
        error("Use the database call") { db.query("CREATE TABLE other (id INT)") }
        assertTrue(db.query("SELECT * FROM users").isEmpty())
        error("Unknown table 'other'") { db.query("SELECT * FROM other") }
    }

    @Test
    fun `duplicate projections are rejected rather than silently collapsed`() {
        error("Duplicate result columns") { users().query("SELECT id, ID FROM users") }
    }

    @Test
    fun `result getters check column presence and type`() {
        val db = users()
        db("INSERT INTO users VALUES (1, 'Koko')")
        val row = db.query("SELECT * FROM users").single()
        error("not TEXT") { row.getString("id") }
        error("not INT") { row.getInt("name") }
        error("Unknown result column") { row.getString("missing") }
    }

    @Test
    fun `query results remain unchanged after subsequent inserts`() {
        val db = users()
        db("INSERT INTO users VALUES (1, 'Koko')")
        val snapshot = db.query("SELECT * FROM users")
        db("INSERT INTO users VALUES (2, 'Other')")
        assertEquals(1, snapshot.size)
        assertEquals("Koko", snapshot.single().getString("name"))
        assertEquals(2, db.query("SELECT * FROM users").size)
    }

    @Test
    fun `database instances have isolated catalogs and rows`() {
        val first = users()
        val second = users()
        first("INSERT INTO users VALUES (1, 'Koko')")
        assertTrue(second.query("SELECT * FROM users").isEmpty())
        assertEquals(1, first.query("SELECT * FROM users").size)
    }

    @Test
    fun `filtered projections match a reference model with repeated keys`() {
        val db = users()
        val random = Random(42)
        val expected = List(100) { random.nextInt(-10, 11) to "Name '${random.nextInt(5)}'" }
        for ((id, name) in expected) {
            db("INSERT INTO users VALUES (:id, :name)", mapOf("id" to id, "name" to name))
        }
        for (id in -11..11) {
            val actual = db.query("SELECT name, id FROM users WHERE id = :id", mapOf("id" to id))
                .map { it.getInt("id") to it.getString("name") }
            val matching = expected.filter { it.first == id }
            assertEquals(matching.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount())
        }
    }
}
