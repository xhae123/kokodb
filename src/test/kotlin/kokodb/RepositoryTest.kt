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

@DbRepository
interface UserRepository : Repository<RepositoryUser, Int> {
    fun named(name: String): List<RepositoryUser> = query().where(RepositoryUser::name eq name).toList()
    val count: Int get() = findAll().size
}

@DbRepository
interface SettingRepository : Repository<RepositorySetting, String>

@DbRepository
interface OtherUserRepository : Repository<RepositoryUser, Int>

private class UserService(private val users: UserRepository) {
    fun rename(id: Int, name: String): Int = users.findById(id)?.let { users.update(it.copy(name = name)) } ?: 0
}

class RepositoryTest {
    @Test
    fun `repository supports CRUD without exposing generated tables`() {
        val users = UserRepository(Database.inMemory())
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
        val settings = SettingRepository(Database.inMemory())
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
        val users = UserRepository(db)
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
    fun `generated support validates adapter availability and ID metadata`() {
        val db = Database.inMemory()
        assertFailsWith<DatabaseException> { kokodb.mapping.RepositorySupport(db, RepositoryUser::class, String::class) }
        assertFailsWith<DatabaseException> { kokodb.mapping.RepositorySupport(db, ModelUser::class, Int::class) }
        assertFailsWith<DatabaseException> { kokodb.mapping.RepositorySupport(db, Unregistered::class, Int::class) }
    }

    @Test
    fun `services depend on declared repositories without a database reference`() {
        val db = Database.inMemory()
        val users = UserRepository(db)
        val service = UserService(users)
        users.insert(RepositoryUser(1, "Original"))
        assertEquals(1, service.rename(1, "Renamed"))
        assertEquals(RepositoryUser(1, "Renamed"), users.findById(1))
        assertEquals(0, service.rename(2, "Absent"))
    }

    @Test
    fun `repositories can specialize queries and share only their database instance`() {
        val db = Database.inMemory()
        val custom = UserRepository(db)
        val other = OtherUserRepository(db)
        val isolated = UserRepository(Database.inMemory())
        val query = custom.query().where(RepositoryUser::name eq "Koko")
        custom.insert(RepositoryUser(1, "Koko"))
        assertEquals(1, custom.count)
        assertEquals(custom.named("Koko"), query.toList())
        other.update(RepositoryUser(1, "Changed"))
        assertTrue(query.toList().isEmpty())
        assertEquals(RepositoryUser(1, "Changed"), custom.findById(1))
        assertTrue(isolated.findAll().isEmpty())
    }

    @Test
    fun `insert update and results keep stored data detached`() {
        val users = UserRepository(Database.inMemory())
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
        val users = UserRepository(Database.inMemory())
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
