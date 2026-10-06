package kokodb

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueTest {
    @Test
    fun `bracket assignment and lookup store mixed values without schemas`() {
        val db = Database.inMemory()
        db["theme"] = "dark"
        db["retryCount"] = 3
        db["enabled"] = true
        assertEquals("dark", db["theme"])
        assertEquals(3, db["retryCount"])
        assertEquals(true, db["enabled"])
        assertNull(db["missing"])
    }

    @Test
    fun `scalar values preserve their exact Kotlin types`() {
        val db = Database.inMemory()
        val values = listOf<Any>(
            "", true, false, Byte.MIN_VALUE, Short.MAX_VALUE, Int.MIN_VALUE,
            Long.MAX_VALUE, 1.25f, 1.25, 'K', Float.POSITIVE_INFINITY, Double.NaN,
        )
        for ((index, value) in values.withIndex()) {
            db[index.toString()] = value
            val stored = db[index.toString()]
            assertEquals(value.javaClass, stored?.javaClass)
            assertEquals(value, stored)
        }
    }

    @Test
    fun `keys are exact strings with no SQL identifier restrictions`() {
        val db = Database.inMemory()
        db["theme"] = "lowercase"
        db["Theme"] = "uppercase"
        db[""] = "empty"
        db["settings/theme with spaces"] = "path"
        db["caf\u00e9"] = "unicode"
        assertEquals("lowercase", db["theme"])
        assertEquals("uppercase", db["Theme"])
        assertEquals("empty", db[""])
        assertEquals("path", db["settings/theme with spaces"])
        assertEquals("unicode", db["caf\u00e9"])
    }

    @Test
    fun `overwriting a key can replace both its value and type`() {
        val db = Database.inMemory()
        db["setting"] = "dark"
        db["setting"] = 3
        assertIs<Int>(db["setting"])
        assertEquals(3, db["setting"])
        db["setting"] = null
        assertNull(db["setting"])
        assertTrue("setting" in db)
    }

    @Test
    fun `contains distinguishes stored null from a missing key`() {
        val db = Database.inMemory()
        assertTrue("theme" !in db)
        db["theme"] = null
        assertNull(db["theme"])
        assertTrue("theme" in db)
        assertNull(db.remove("theme"))
        assertTrue("theme" !in db)
    }

    @Test
    fun `remove returns the old value and makes the key absent`() {
        val db = Database.inMemory()
        db["theme"] = "dark"
        assertEquals("dark", db.remove("theme"))
        assertNull(db["theme"])
        assertTrue("theme" !in db)
        assertNull(db.remove("theme"))
    }

    @Test
    fun `byte arrays are copied on writes and reads`() {
        val db = Database.inMemory()
        val original = byteArrayOf(1, 2, 3)
        db["blob"] = original
        original[0] = 9
        val result = assertIs<ByteArray>(db["blob"])
        assertContentEquals(byteArrayOf(1, 2, 3), result)
        result[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<ByteArray>(db["blob"]))
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<ByteArray>(db.remove("blob")))
        assertNull(db["blob"])
        db["empty"] = byteArrayOf()
        assertContentEquals(byteArrayOf(), assertIs<ByteArray>(db["empty"]))
    }

    @Test
    fun `unsupported values fail without changing existing data`() {
        val db = Database.inMemory()
        db["setting"] = "original"
        val unsupported = listOf(listOf(1), mapOf("id" to 1), intArrayOf(1), Any(), Unregistered(1))
        for (value in unsupported) {
            assertFailsWith<DatabaseException> { db["setting"] = value }
            assertEquals("original", db["setting"])
            assertFailsWith<DatabaseException> { db["new"] = value }
            assertTrue("new" !in db)
        }
    }

    @Test
    fun `database instances do not share key value data`() {
        val first = Database.inMemory()
        val second = Database.inMemory()
        first["theme"] = "dark"
        assertNull(second["theme"])
        second["theme"] = "light"
        assertEquals("dark", first["theme"])
        assertEquals("light", second["theme"])
    }

    @Test
    fun `key value names do not conflict with SQL tables or generated models`() {
        val db = Database.inMemory()
        db["users"] = "setting"
        db.execute("CREATE TABLE users (id INT)")
        db.execute("INSERT INTO users VALUES (1)")
        db["model_users"] = 3
        db.insert(ModelUser(1, "Koko"))
        assertEquals("setting", db["users"])
        assertEquals(1, db.query("SELECT id FROM users").single().getInt("id"))
        assertEquals(3, db["model_users"])
        assertEquals(listOf(ModelUser(1, "Koko")), db.from<ModelUser>().toList())
    }

    @Test
    fun `random mixed operations match a map reference model`() {
        val db = Database.inMemory()
        val expected = mutableMapOf<String, Any?>()
        val random = Random(42)
        repeat(500) {
            val key = "key-${random.nextInt(20)}"
            when (random.nextInt(3)) {
                0 -> {
                    val value = when (random.nextInt(4)) {
                        0 -> null
                        1 -> random.nextInt()
                        2 -> "value-${random.nextInt(10)}"
                        else -> random.nextBoolean()
                    }
                    db[key] = value
                    expected[key] = value
                }
                1 -> assertEquals(expected.remove(key), db.remove(key))
                else -> assertEquals(expected[key], db[key])
            }
            for (index in 0 until 20) {
                val checked = "key-$index"
                assertEquals(expected.containsKey(checked), checked in db)
                assertEquals(expected[checked], db[checked])
            }
        }
    }
}
