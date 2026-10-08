package kokodb

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransactionTest {
    private fun database(): Database = Database.inMemory().also {
        it.execute("CREATE TABLE balances (id INT PRIMARY KEY, amount INT)")
        it.execute("INSERT INTO balances VALUES (1, 100)")
        it.execute("INSERT INTO balances VALUES (2, 100)")
    }

    private fun balances(db: Database): Map<Int, Int> = db.query("SELECT * FROM balances")
        .associate { it.getInt("id") to it.getInt("amount") }

    @Test
    fun `commit includes multiple writes and newly created tables`() {
        val db = database()
        val before = db.query("SELECT * FROM balances WHERE id = 1").single()
        val result = db.transaction {
            execute("UPDATE balances SET amount = 50 WHERE id = 1")
            execute("UPDATE balances SET amount = 150 WHERE id = 2")
            execute("CREATE TABLE audit (text TEXT)")
            execute("INSERT INTO audit VALUES ('transfer')")
            assertEquals(50, db.query("SELECT amount FROM balances WHERE id = 1").single().getInt("amount"))
            queryRows("SELECT * FROM audit").single().getString("text")
        }
        assertEquals("transfer", result)
        assertEquals(mapOf(1 to 50, 2 to 150), balances(db))
        assertEquals("transfer", db.query("SELECT * FROM audit").single().getString("text"))
        assertEquals(100, before.getInt("amount"))
    }

    @Test
    fun `callback exceptions roll back all rows and schemas`() {
        val db = database()
        db.execute("CREATE TABLE audit (text TEXT)")
        val error = IllegalStateException("callback failed")
        assertSame(error, assertFailsWith<IllegalStateException> {
            db.transaction {
                execute("DELETE FROM balances WHERE id = 1")
                execute("UPDATE balances SET amount = 200")
                execute("INSERT INTO audit VALUES ('Pending')")
                execute("CREATE TABLE temporary (text TEXT)")
                throw error
            }
        })
        assertEquals(mapOf(1 to 100, 2 to 100), balances(db))
        assertTrue(db.query("SELECT * FROM audit").isEmpty())
        assertFailsWith<DatabaseException> { db.query("SELECT * FROM temporary") }
        db.transaction { execute("INSERT INTO balances VALUES (3, 100)") }
        assertEquals(3, balances(db).size)
    }

    @Test
    fun `caught SQL errors poison the scope and prevent earlier writes from committing`() {
        val db = database()
        var original: DatabaseException? = null
        val aborted = assertFailsWith<DatabaseException> {
            db.transaction {
                execute("UPDATE balances SET amount = 50 WHERE id = 1")
                original = assertFailsWith { execute("UPDATE balances SET id = 1 WHERE id = 2") }
                assertFailsWith<DatabaseException> { queryRows("SELECT * FROM balances") }
            }
        }
        assertSame(original, aborted.cause)
        assertEquals(mapOf(1 to 100, 2 to 100), balances(db))
    }

    @Test
    fun `syntax and model mapping errors caught by the callback also abort`() {
        for (operation in listOf<Transaction.() -> Unit>(
            { execute("UPDATE balances SET") },
            { query<SharedUser>("SELECT * FROM balances") },
            { query<SharedUser>("SELECT name FROM shared_users") },
        )) {
            val db = database()
            assertFailsWith<DatabaseException> {
                db.transaction {
                    execute("DELETE FROM balances")
                    assertFailsWith<DatabaseException> { operation() }
                }
            }
            assertEquals(2, balances(db).size)
        }
    }

    @Test
    fun `typed queries see own writes while returned models remain detached`() {
        val db = Database.inMemory()
        val result = db.transaction {
            execute("INSERT INTO shared_users VALUES (1, 'First')")
            val snapshot = query<SharedUser>("SELECT * FROM shared_users")
            execute("UPDATE shared_users SET name = 'Second' WHERE id = 1")
            assertEquals(listOf(SharedUser(1, "Second")), query<SharedUser>("SELECT * FROM shared_users"))
            snapshot
        }
        assertEquals(listOf(SharedUser(1, "First")), result)
        assertEquals(listOf(SharedUser(1, "Second")), db.queryModels(SharedUser::class.java, "SELECT * FROM shared_users"))
    }

    @Test
    fun `caught nested transactions abort the outer scope`() {
        val db = database()
        assertFailsWith<DatabaseException> {
            db.transaction {
                execute("DELETE FROM balances")
                assertFailsWith<DatabaseException> { db.transaction { execute("DELETE FROM balances") } }
            }
        }
        assertEquals(2, balances(db).size)
    }

    @Test
    fun `completed transaction handles cannot modify a later transaction`() {
        val db = database()
        lateinit var captured: Transaction
        db.transaction { captured = this }
        assertTrue(captured.state.catalog.entries().isEmpty())
        assertFailsWith<DatabaseException> { captured.execute("DELETE FROM balances") }
        db.transaction {
            assertFailsWith<DatabaseException> { captured.execute("DELETE FROM balances") }
            execute("UPDATE balances SET amount = 150 WHERE id = 1")
        }
        assertEquals(mapOf(1 to 150, 2 to 100), balances(db))
        assertFailsWith<IllegalStateException> { db.transaction { captured = this; error("rollback") } }
        assertFailsWith<DatabaseException> { captured.queryRows("SELECT * FROM balances") }
    }

    @Test
    fun `captured scopes and active independent databases reject other threads`() {
        val db = database()
        val workers = Executors.newSingleThreadExecutor()
        try {
            db.transaction {
                val scope = this
                workers.submit {
                    assertFailsWith<DatabaseException> { scope.execute("DELETE FROM balances") }
                    assertFailsWith<DatabaseException> { db.query("SELECT * FROM balances") }
                }.get(5, TimeUnit.SECONDS)
                execute("UPDATE balances SET amount = 150 WHERE id = 1")
            }
            assertEquals(mapOf(1 to 150, 2 to 100), balances(db))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `empty transactions preserve data and successful no match counts`() {
        val db = database()
        assertEquals(0, db.transaction { execute("UPDATE balances SET amount = 100 WHERE id = 99") })
        assertTrue(db.transaction { queryRows("SELECT * FROM balances WHERE id = 99").isEmpty() })
        assertEquals(mapOf(1 to 100, 2 to 100), balances(db))
    }
}
