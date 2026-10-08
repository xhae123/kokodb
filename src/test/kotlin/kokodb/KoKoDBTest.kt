package kokodb

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@DbTable("shared_users")
data class SharedUser(@Id val id: Int, var name: String) {
    val display: String get() = "$id: $name"
}

@DbTable("shared_notes")
data class SharedNote(val id: Int, val text: String)

data class Unregistered(val id: Int)

class KoKoDBTest {
    @BeforeTest
    fun open() {
        KoKoDB.close()
        KoKoDB.openInMemory()
    }

    @AfterTest
    fun close() {
        KoKoDB.close()
    }

    private fun register(id: Int, name: String) = KoKoDB(
        "INSERT INTO shared_users VALUES (:id, :name)", mapOf("id" to id, "name" to name)
    )

    private fun find(id: Int): List<SharedUser> = KoKoDB<SharedUser>(
        "SELECT id, name FROM shared_users WHERE id = :id", mapOf("id" to id)
    )

    @Test
    fun `calls from different functions share the same database`() {
        assertEquals(1, register(1, "Koko"))
        assertEquals(listOf(SharedUser(1, "Koko")), find(1))
        register(2, "Other")
        assertEquals(2, KoKoDB<SharedUser>("SELECT * FROM shared_users").size)
        assertTrue(Database.inMemory().queryModels(SharedUser::class.java, "SELECT * FROM shared_users").isEmpty())
        assertEquals("Koko", KoKoDB.query("SELECT name FROM shared_users WHERE id = 1").single().getString("name"))
    }

    @Test
    fun `typed queries observe atomic SQL updates and deletes`() {
        register(1, "Original")
        register(2, "Other")
        val detached = find(1).single()
        assertEquals(1, KoKoDB(
            "UPDATE shared_users SET name = :name WHERE id = :id",
            mapOf("name" to "Updated", "id" to 1),
        ))
        assertEquals(listOf(SharedUser(1, "Updated")), find(1))
        assertEquals("Original", detached.name)
        assertFailsWith<DatabaseException> {
            KoKoDB("UPDATE shared_users SET id = 2, name = 'Rejected' WHERE id = 1")
        }
        assertEquals(listOf(SharedUser(1, "Updated")), find(1))
        assertEquals(1, KoKoDB("DELETE FROM shared_users WHERE id = :id", mapOf("id" to 1)))
        assertTrue(find(1).isEmpty())
        assertEquals(listOf(SharedUser(2, "Other")), find(2))
    }

    @Test
    fun `typed SQL accepts reordered columns case insensitive names and bound values`() {
        val name = "Koko' OR id = 2"
        register(1, name)
        register(2, "Other")
        assertEquals(listOf(SharedUser(1, name)), KoKoDB<SharedUser>(
            "SELECT NAME, ID FROM SHARED_USERS WHERE name = :name", mapOf("name" to name)
        ))
        assertTrue(find(3).isEmpty())
        assertEquals(listOf(SharedUser(1, name)), KoKoDB.queryModels(
            SharedUser::class.java, "SELECT * FROM shared_users WHERE id = 1"
        ))
    }

    @Test
    fun `typed SQL validates mappings before scanning empty results`() {
        for (sql in listOf(
            "SELECT name FROM shared_users",
            "SELECT id, id FROM shared_users",
            "SELECT id, name, unknown FROM shared_users",
            "SELECT * FROM shared_notes",
        )) assertFailsWith<DatabaseException> { KoKoDB<SharedUser>(sql) }
        assertFailsWith<DatabaseException> { KoKoDB<Unregistered>("SELECT * FROM shared_users") }
        assertFailsWith<DatabaseException> { KoKoDB<SharedUser>("INSERT INTO shared_users VALUES (1, 'Unexpected')") }
        assertFailsWith<DatabaseException> { KoKoDB("SELECT * FROM shared_users") }
        assertTrue(KoKoDB<SharedUser>("SELECT * FROM shared_users").isEmpty())
        assertFailsWith<SqlSyntaxException> { KoKoDB<SharedUser>("SELECT FROM") }
    }

    @Test
    fun `invalid parameters and predicates fail even on empty results`() {
        val sql = "SELECT * FROM shared_users WHERE id = :id"
        for (params in listOf(emptyMap(), mapOf("id" to "one"), mapOf("id" to 1L), mapOf("id" to null))) {
            assertFailsWith<DatabaseException> { KoKoDB<SharedUser>(sql, params) }
        }
        assertFailsWith<DatabaseException> { KoKoDB<SharedUser>("SELECT * FROM shared_users WHERE missing = 1") }
    }

    @Test
    fun `each call returns fresh detached models`() {
        register(1, "Original")
        val first = find(1).single()
        val second = find(1).single()
        assertEquals("1: Original", first.display)
        assertNotSame(first, second)
        first.name = "Changed"
        assertEquals(SharedUser(1, "Original"), find(1).single())
        val snapshot = KoKoDB<SharedUser>("SELECT * FROM shared_users")
        register(2, "Later")
        assertEquals(1, snapshot.size)
        assertEquals(2, KoKoDB<SharedUser>("SELECT * FROM shared_users").size)
    }

    @Test
    fun `generated primary key metadata is enforced by SQL inserts`() {
        register(1, "Original")
        assertFailsWith<DatabaseException> { register(1, "Duplicate") }
        assertEquals(listOf(SharedUser(1, "Original")), find(1))
    }

    @Test
    fun `models without primary keys map SQL results with repeated values`() {
        KoKoDB("INSERT INTO shared_notes VALUES (1, 'First')")
        KoKoDB("INSERT INTO shared_notes VALUES (1, 'Second')")
        assertEquals(setOf(SharedNote(1, "First"), SharedNote(1, "Second")),
            KoKoDB<SharedNote>("SELECT text, id FROM shared_notes").toSet())
    }

    @Test
    fun `close blocks access until an explicit fresh open`() {
        register(1, "Original")
        KoKoDB.close()
        KoKoDB.close()
        assertFailsWith<DatabaseException> { find(1) }
        assertFailsWith<DatabaseException> { KoKoDB.query("SELECT * FROM shared_users") }
        assertFailsWith<DatabaseException> { register(2, "Blocked") }
        KoKoDB.openInMemory()
        assertTrue(find(1).isEmpty())
        register(1, "Fresh")
        assertEquals(listOf(SharedUser(1, "Fresh")), find(1))
    }

    @Test
    fun `opening an active database cannot discard its rows`() {
        register(1, "Original")
        assertFailsWith<DatabaseException> { KoKoDB.openInMemory() }
        assertEquals(listOf(SharedUser(1, "Original")), find(1))
    }

    @Test
    fun `raw SQL needs no result model or generated repository`() {
        KoKoDB.close()
        val loader = object : ClassLoader(Database::class.java.classLoader) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
                if (name == "META-INF/services/kokodb.mapping.ModelProvider") java.util.Collections.emptyEnumeration()
                else super.getResources(name)
        }
        KoKoDB.openInMemory(loader)
        assertEquals(0, KoKoDB("CREATE TABLE shared_settings (name TEXT, value INT)"))
        KoKoDB("INSERT INTO shared_settings VALUES (:name, :value)", mapOf("name" to "count", "value" to 3))
        assertEquals(3, KoKoDB.query("SELECT value FROM shared_settings").single().getInt("value"))
        assertFailsWith<DatabaseException> { KoKoDB<SharedUser>("SELECT * FROM shared_settings") }
    }

    @Test
    fun `independent databases can execute typed SQL without sharing singleton rows`() {
        val db = Database.inMemory()
        db("INSERT INTO shared_users VALUES (1, 'Independent')")
        assertEquals(listOf(SharedUser(1, "Independent")), db.queryModels(SharedUser::class.java, "SELECT * FROM shared_users"))
        assertTrue(find(1).isEmpty())
    }

    @Test
    fun `concurrent callers retain every successful write`() {
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..100).map { id -> workers.submit {
                register(id, "user$id")
                assertEquals(listOf(SharedUser(id, "user$id")), find(id))
            } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals((1..100).toSet(), KoKoDB<SharedUser>("SELECT * FROM shared_users").map { it.id }.toSet())
        } finally {
            workers.shutdownNow()
        }
    }
}
