package kokodb

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DslTest {
    private class Users : Table("users") {
        val id = int("id")
        val name = text("name")
    }

    private class Other : Table("other") {
        val id = int("id")
        val name = text("name")
    }

    private data class User(val id: Int, val name: String)

    private fun error(message: String, block: () -> Unit) {
        val failure = assertFailsWith<DatabaseException>(block = block)
        assertTrue(failure.message.orEmpty().contains(message), failure.message)
    }

    @Test
    fun `typed schema insertion and object mapping work end to end`() {
        val users = Users()
        val db = Database.inMemory()
        db.createTable(users)
        assertEquals(1, db.insertInto(users) {
            set(users.name, "Koko")
            set(users.id, 1)
        })
        db.insertInto(users) {
            set(users.id, 2)
            set(users.name, "Other")
        }

        val result: List<User> = db.from(users)
            .select(users.name, users.id)
            .where(users.id eq 1)
            .map { User(it[users.id], it[users.name]) }
        assertEquals(listOf(User(1, "Koko")), result)
    }

    @Test
    fun `SQL and DSL share tables and execution semantics`() {
        val users = Users()
        val db = Database.inMemory()
        db.createTable(users)
        db.execute("INSERT INTO users VALUES (1, 'SQL')")
        db.insertInto(users) {
            set(users.id, 2)
            set(users.name, "DSL")
        }
        assertEquals("SQL", db.from(users).where(users.id eq 1).toList().single()[users.name])
        assertEquals("DSL", db.query("SELECT name FROM users WHERE id = 2").single()[users.name])
        assertEquals(2, db.from(users).toList().size)
    }

    @Test
    fun `DSL can bind to a compatible SQL created schema`() {
        val users = Users()
        val db = Database.inMemory()
        db.execute("CREATE TABLE users (id INT, name TEXT)")
        db.insertInto(users) {
            set(users.id, 1)
            set(users.name, "Koko")
        }
        assertEquals(1, db.from(users).select(users.id).toList().single()[users.id])
    }

    @Test
    fun `missing duplicate foreign and aborted assignments leave no rows`() {
        val users = Users()
        val other = Other()
        val db = Database.inMemory()
        db.createTable(users)
        error("Missing value for column 'name'") {
            db.insertInto(users) { set(users.id, 1) }
        }
        error("Duplicate assignment") {
            db.insertInto(users) {
                set(users.id, 1)
                set(users.id, 2)
                set(users.name, "Koko")
            }
        }
        error("belongs to another table") {
            db.insertInto(users) { set(other.id, 1) }
        }
        assertFailsWith<IllegalStateException> {
            db.insertInto(users) {
                set(users.id, 1)
                throw IllegalStateException("Aborted")
            }
        }
        assertTrue(db.from(users).toList().isEmpty())
    }

    @Test
    fun `queries are immutable and terminal calls see current state`() {
        val users = Users()
        val db = Database.inMemory()
        db.createTable(users)
        val all = db.from(users)
        val first = all.where(users.id eq 1)
        val names = all.select(users.name)
        assertTrue(first.toList().isEmpty())
        db.insertInto(users) { set(users.id, 1); set(users.name, "First") }
        val snapshot = all.toList()
        db.insertInto(users) { set(users.id, 2); set(users.name, "Second") }
        assertEquals(1, first.toList().size)
        assertEquals(2, all.toList().size)
        assertEquals(1, snapshot.size)
        assertEquals(setOf("First", "Second"), names.map { it[users.name] }.toSet())
        assertEquals(2, first.where(users.id eq 2).toList().single()[users.id])
    }

    @Test
    fun `projections and predicates reject foreign columns`() {
        val users = Users()
        val other = Other()
        val anotherUsersDefinition = Users()
        val db = Database.inMemory()
        db.createTable(users)
        error("belongs to another table") { db.from(users).select(other.id) }
        error("belongs to another table") { db.from(users).where(other.id eq 1) }
        error("belongs to another table") { db.from(users).select(anotherUsersDefinition.id) }
        error("Select at least one column") { db.from(users).select() }
        error("Duplicate result columns") { db.from(users).select(users.id, users.id) }
    }

    @Test
    fun `typed result access rejects foreign unselected and mistyped columns`() {
        val users = Users()
        val other = Other()
        val db = Database.inMemory()
        db.createTable(users)
        db.insertInto(users) { set(users.id, 1); set(users.name, "Koko") }
        val row = db.from(users).select(users.name).toList().single()
        error("Unknown result column 'id'") { row[users.id] }
        error("belongs to another table") { row[other.name] }
        val incompatible = object : Table("users") { val name = int("name") }
        error("expects INT, got TEXT") { row[incompatible.name] }
    }

    @Test
    fun `stored schema drift is rejected before mapping or insertion`() {
        val users = Users()
        for (schema in listOf("id TEXT, name TEXT", "name TEXT, id INT", "id INT", "id INT, name TEXT, age INT")) {
            val db = Database.inMemory()
            db.execute("CREATE TABLE users ($schema)")
            var mapperCalled = false
            error("does not match the stored schema") {
                db.from(users).map { mapperCalled = true }
            }
            assertTrue(!mapperCalled)
            error("does not match the stored schema") {
                db.insertInto(users) { set(users.id, 1); set(users.name, "Koko") }
            }
            assertTrue(db.query("SELECT * FROM users").isEmpty())
        }
    }

    @Test
    fun `table definitions reject invalid names duplicate columns and empty schemas`() {
        error("Invalid identifier") { Table("bad name") }
        error("Invalid identifier") { object : Table("users") { val bad = int("bad-name") } }
        error("Duplicate column 'id'") {
            object : Table("users") { val first = int("id"); val second = text("ID") }
        }
        val db = Database.inMemory()
        error("at least one column") { db.createTable(Table("empty")) }
        error("Unknown table 'empty'") { db.query("SELECT * FROM empty") }
    }

    @Test
    fun `table definition cannot change after first database use`() {
        val table = object : Table("numbers") {
            val id = int("id")
            fun addColumn() = text("late")
        }
        val db = Database.inMemory()
        db.createTable(table)
        error("definition is frozen") { table.addColumn() }
        db.insertInto(table) { set(table.id, 1) }
        assertEquals(1, db.from(table).toList().single()[table.id])
    }

    @Test
    fun `unknown tables fail on execution and table definitions are reusable across databases`() {
        val users = Users()
        val first = Database.inMemory()
        val second = Database.inMemory()
        error("Unknown table 'users'") { first.from(users).toList() }
        error("Unknown table 'users'") { first.insertInto(users) { set(users.id, 1); set(users.name, "Koko") } }
        first.createTable(users)
        second.createTable(users)
        first.insertInto(users) { set(users.id, 1); set(users.name, "Koko") }
        assertTrue(second.from(users).toList().isEmpty())
    }

    @Test
    fun `DSL values preserve SQL like text and integer boundaries`() {
        val table = object : Table("Users") { val id = int("ID"); val nameColumn = text("NAME") }
        val db = Database.inMemory()
        db.createTable(table)
        val payload = "'); SELECT * FROM missing; --"
        db.insertInto(table) { set(table.id, Int.MIN_VALUE); set(table.nameColumn, payload) }
        db.insertInto(table) { set(table.id, Int.MAX_VALUE); set(table.nameColumn, "") }
        assertEquals(Int.MIN_VALUE, db.from(table).where(table.nameColumn eq payload).toList().single()[table.id])
        assertEquals("", db.from(table).where(table.id eq Int.MAX_VALUE).toList().single()[table.nameColumn])
    }

    @Test
    fun `mapping errors propagate and leave stored rows unchanged`() {
        val users = Users()
        val db = Database.inMemory()
        db.createTable(users)
        db.insertInto(users) { set(users.id, 1); set(users.name, "Koko") }
        assertFailsWith<IllegalStateException> {
            db.from(users).map { throw IllegalStateException("Mapping failed") }
        }
        assertEquals("Koko", db.from(users).toList().single()[users.name])
    }

    @Test
    fun `SQL and DSL filtered projections match a reference model`() {
        val users = Users()
        val db = Database.inMemory()
        db.createTable(users)
        val random = Random(42)
        val expected = List(100) { User(random.nextInt(-5, 6), "Name '${random.nextInt(5)}'") }
        for (user in expected) {
            db.insertInto(users) { set(users.id, user.id); set(users.name, user.name) }
        }
        for (id in -6..6) {
            val dsl = db.from(users).select(users.name, users.id).where(users.id eq id)
                .map { User(it[users.id], it[users.name]) }
            val sql = db.query("SELECT name, id FROM users WHERE id = :id", mapOf("id" to id))
                .map { User(it[users.id], it[users.name]) }
            val counts = expected.filter { it.id == id }.groupingBy { it }.eachCount()
            assertEquals(counts, dsl.groupingBy { it }.eachCount())
            assertEquals(counts, sql.groupingBy { it }.eachCount())
        }
    }
}
