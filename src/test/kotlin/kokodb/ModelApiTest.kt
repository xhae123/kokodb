package kokodb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DbTable("model_users")
data class ModelUser(val id: Int, val name: String) {
    val display: String get() = "$id: $name"
}

@DbTable("model_notes")
data class ModelNote(val id: Int, var text: String)

data class Unregistered(val id: Int)

class ModelApiTest {
    @Test
    fun `annotation automatically prepares tables and maps objects`() {
        val db = Database.inMemory()
        assertEquals(1, db.insert(ModelUser(1, "Koko")))
        db.insert(ModelUser(2, "Other"))
        val users: List<ModelUser> = db.from<ModelUser>().where(ModelUser::id eq 1).toList()
        assertEquals(listOf(ModelUser(1, "Koko")), users)
        assertEquals(listOf(ModelUser(2, "Other")), db.from<ModelUser>().where(ModelUser::name eq "Other").toList())
        assertEquals(2, db.from<ModelUser>().toList().size)
    }

    @Test
    fun `SQL and model queries share automatically prepared tables`() {
        val db = Database.inMemory()
        db.execute("INSERT INTO model_users VALUES (1, 'SQL')")
        assertEquals(listOf(ModelUser(1, "SQL")), db.from<ModelUser>().toList())
        db.insert(ModelUser(2, "Model"))
        assertEquals("Model", db.query("SELECT name FROM model_users WHERE id = 2").single().getString("name"))
    }

    @Test
    fun `each instance starts with prepared empty model tables`() {
        val first = Database.inMemory()
        val second = Database.inMemory()
        first.insert(ModelUser(1, "Koko"))
        assertTrue(second.from<ModelUser>().toList().isEmpty())
        assertTrue(first.from<ModelNote>().toList().isEmpty())
    }

    @Test
    fun `mutating inserted or returned objects does not mutate stored values`() {
        val db = Database.inMemory()
        val note = ModelNote(1, "Original")
        db.insert(note)
        note.text = "Changed outside"
        val result = db.from<ModelNote>().toList().single()
        assertEquals("Original", result.text)
        result.text = "Changed result"
        assertEquals("Original", db.from<ModelNote>().toList().single().text)
    }

    @Test
    fun `unregistered models report the missing code generation setup`() {
        val db = Database.inMemory()
        val failure = assertFailsWith<DatabaseException> { db.insert(Unregistered(1)) }
        assertTrue(failure.message.orEmpty().contains("@DbTable"))
        assertTrue(failure.message.orEmpty().contains("KSP"))
        assertFailsWith<DatabaseException> { db.from<Unregistered>() }
    }

    @Test
    fun `computed properties cannot be used as stored fields`() {
        val db = Database.inMemory()
        assertFailsWith<DatabaseException> { db.from<ModelUser>().where(ModelUser::display eq "1: Koko") }
    }

    @Test
    fun `model queries are immutable and see new data on each execution`() {
        val db = Database.inMemory()
        val all = db.from<ModelUser>()
        val first = all.where(ModelUser::id eq 1)
        db.insert(ModelUser(1, "Koko"))
        val snapshot = all.toList()
        db.insert(ModelUser(2, "Other"))
        assertEquals(listOf(ModelUser(1, "Koko")), first.toList())
        assertEquals(2, all.toList().size)
        assertEquals(1, snapshot.size)
    }
}
