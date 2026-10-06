package kokodb

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DbTable("repository_users")
data class RepositoryUser(@Id val id: Int, var name: String)

@DbTable("repository_settings")
data class RepositorySetting(@property:Id val key: String, val value: Int)

class UserRepository(database: Database) : Repository<RepositoryUser, Int>(
    database, RepositoryUser::class, Int::class,
) {
    fun named(name: String): List<RepositoryUser> = query().where(RepositoryUser::name eq name).toList()
}

class RepositoryTest {
    @Test
    fun `repository supports CRUD without exposing generated tables`() {
        val users = Database.inMemory().repository<RepositoryUser, Int>()
        assertNull(users.findById(1))
        assertEquals(1, users.insert(RepositoryUser(1, "Original")))
        assertEquals(RepositoryUser(1, "Original"), users.findById(1))
        assertEquals(1, users.update(RepositoryUser(1, "Updated")))
        assertEquals(listOf(RepositoryUser(1, "Updated")), users.findAll())
        assertEquals(0, users.update(RepositoryUser(2, "Absent")))
        assertNull(users.findById(2))
        assertEquals(1, users.deleteById(1))
        assertEquals(0, users.deleteById(1))
        assertTrue(users.findAll().isEmpty())
    }

    @Test
    fun `string primary keys and property annotation targets are supported`() {
        val settings = Database.inMemory().repository<RepositorySetting, String>()
        settings.insert(RepositorySetting("theme", 1))
        assertFailsWith<DatabaseException> { settings.insert(RepositorySetting("theme", 2)) }
        assertEquals(1, settings.update(RepositorySetting("theme", 3)))
        assertEquals(RepositorySetting("theme", 3), settings.findById("theme"))
        assertNull(settings.findById("THEME"))
        assertEquals(1, settings.deleteById("theme"))
    }

    @Test
    fun `duplicate keys fail through repository model and SQL interfaces`() {
        val db = Database.inMemory()
        val users = db.repository<RepositoryUser, Int>()
        users.insert(RepositoryUser(1, "Original"))
        assertFailsWith<DatabaseException> { users.insert(RepositoryUser(1, "Repository")) }
        assertFailsWith<DatabaseException> { db.insert(RepositoryUser(1, "Model")) }
        assertFailsWith<DatabaseException> { db.execute("INSERT INTO repository_users VALUES (1, 'SQL')") }
        assertEquals(listOf(RepositoryUser(1, "Original")), users.findAll())
        db.execute("INSERT INTO repository_users VALUES (2, 'SQL')")
        assertEquals(1, users.update(RepositoryUser(2, "Updated")))
        assertEquals("Updated", db.query("SELECT name FROM repository_users WHERE id = 2").single().getString("name"))
        assertEquals(1, users.deleteById(2))
        assertTrue(db.query("SELECT * FROM repository_users WHERE id = 2").isEmpty())
    }

    @Test
    fun `repository validates key metadata and exact ID types at creation`() {
        val db = Database.inMemory()
        assertFailsWith<DatabaseException> { db.repository<RepositoryUser, String>() }
        assertFailsWith<DatabaseException> { db.repository<RepositorySetting, Int>() }
        assertFailsWith<DatabaseException> { db.repository<RepositoryUser, Any>() }
        assertFailsWith<DatabaseException> { db.repository<ModelUser, Int>() }
        assertFailsWith<DatabaseException> { db.repository<Unregistered, Int>() }
    }

    @Test
    fun `repositories can specialize queries and share only their database instance`() {
        val db = Database.inMemory()
        val custom = UserRepository(db)
        val other = db.repository<RepositoryUser, Int>()
        val isolated = Database.inMemory().repository<RepositoryUser, Int>()
        val query = custom.query().where(RepositoryUser::name eq "Koko")
        custom.insert(RepositoryUser(1, "Koko"))
        assertEquals(custom.named("Koko"), query.toList())
        other.update(RepositoryUser(1, "Changed"))
        assertTrue(query.toList().isEmpty())
        assertEquals(RepositoryUser(1, "Changed"), custom.findById(1))
        assertTrue(isolated.findAll().isEmpty())
    }

    @Test
    fun `insert update and results keep stored data detached`() {
        val users = Database.inMemory().repository<RepositoryUser, Int>()
        val input = RepositoryUser(1, "Original")
        users.insert(input)
        input.name = "External"
        val found = users.findById(1)!!
        assertEquals("Original", found.name)
        found.name = "Updated"
        assertEquals("Original", users.findById(1)!!.name)
        users.update(found)
        found.name = "Changed after update"
        assertEquals("Updated", users.findById(1)!!.name)
    }

    @Test
    fun `random CRUD operations agree with a reference map`() {
        val users = Database.inMemory().repository<RepositoryUser, Int>()
        val expected = mutableMapOf<Int, RepositoryUser>()
        val random = Random(7281)
        repeat(300) { step ->
            val id = random.nextInt(20)
            val model = RepositoryUser(id, "value$step")
            when (random.nextInt(4)) {
                0 -> if (id in expected) {
                    assertFailsWith<DatabaseException> { users.insert(model) }
                } else {
                    assertEquals(1, users.insert(model))
                    expected[id] = model
                }
                1 -> {
                    assertEquals(if (id in expected) 1 else 0, users.update(model))
                    if (id in expected) expected[id] = model
                }
                2 -> assertEquals(if (expected.remove(id) != null) 1 else 0, users.deleteById(id))
                3 -> assertEquals(expected[id], users.findById(id))
            }
            assertEquals(expected, users.findAll().associateBy { it.id })
        }
    }

    @Test
    fun `native schemas enforce primary keys and validate replacement before mutation`() {
        val db = Database.inMemory()
        val schema = object : Table("native_users") {
            val id = int("id", primaryKey = true)
            val username = text("name")
        }
        db.execute("CREATE TABLE native_users (id INT PRIMARY KEY, name TEXT)")
        db.execute("INSERT INTO native_users VALUES (1, 'Same')")
        db.execute("INSERT INTO native_users VALUES (2, 'Same')")
        assertFailsWith<DatabaseException> { db.execute("INSERT INTO native_users VALUES (1, 'Duplicate')") }
        assertFailsWith<DatabaseException> {
            db.replaceIn(schema, schema.id eq 1) { set(schema.id, 2); set(schema.username, "Conflict") }
        }
        assertFailsWith<DatabaseException> {
            db.replaceIn(schema, schema.username eq "Same") { set(schema.id, 3); set(schema.username, "Conflict") }
        }
        assertFailsWith<DatabaseException> {
            db.replaceIn(schema, schema.id eq 1) { set(schema.id, 1) }
        }
        assertEquals(listOf(1, 2), db.from(schema).map { it[schema.id] })
        assertEquals(listOf("Same", "Same"), db.from(schema).map { it[schema.username] })
        assertEquals(1, db.replaceIn(schema, schema.id eq 1) { set(schema.id, 3); set(schema.username, "Changed") })
        assertEquals(1, db.deleteFrom(schema, schema.id eq 3))
        assertEquals(listOf(2), db.from(schema).map { it[schema.id] })
        assertFailsWith<DatabaseException> { db.execute("CREATE TABLE invalid_keys (a INT PRIMARY KEY, b TEXT PRIMARY KEY)") }
        val foreign = object : Table("foreign") { val id = int("id") }
        assertFailsWith<DatabaseException> { db.deleteFrom(schema, foreign.id eq 2) }
        assertFailsWith<DatabaseException> { db.replaceIn(schema, foreign.id eq 2) {} }
    }
}
