package kokodb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SharedTransactionTest {
    @BeforeTest
    fun open() {
        KoKoDB.close()
        KoKoDB.openInMemory()
        KoKoDB.execute("INSERT INTO shared_users VALUES (1, 'Original')")
    }

    @AfterTest
    fun close() = KoKoDB.close()

    @Test
    fun `global calls on the owner thread participate in the active transaction`() {
        assertFailsWith<IllegalStateException> {
            KoKoDB.transaction {
                KoKoDB.execute("UPDATE shared_users SET name = 'Pending' WHERE id = 1")
                assertEquals(listOf(SharedUser(1, "Pending")), KoKoDB<SharedUser>("SELECT * FROM shared_users"))
                error("rollback")
            }
        }
        assertEquals(listOf(SharedUser(1, "Original")), KoKoDB<SharedUser>("SELECT * FROM shared_users"))
        KoKoDB.transaction { KoKoDB.execute("UPDATE shared_users SET name = 'Committed' WHERE id = 1") }
        assertEquals(listOf(SharedUser(1, "Committed")), KoKoDB<SharedUser>("SELECT * FROM shared_users"))
    }

    @Test
    fun `caught shared lifecycle and nesting errors abort all writes`() {
        for (operation in listOf<() -> Unit>(
            { KoKoDB.close() }, { KoKoDB.openInMemory() }, { KoKoDB.transaction {} },
        )) {
            assertFailsWith<DatabaseException> {
                KoKoDB.transaction {
                    execute("DELETE FROM shared_users")
                    assertFailsWith<DatabaseException> { operation() }
                }
            }
            assertEquals(listOf(SharedUser(1, "Original")), KoKoDB<SharedUser>("SELECT * FROM shared_users"))
        }
    }

    @Test
    fun `other shared readers wait for commit and cannot see partial changes`() {
        val workers = Executors.newFixedThreadPool(2)
        val pending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        try {
            val writer = workers.submit {
                KoKoDB.transaction {
                    execute("UPDATE shared_users SET name = 'Committed' WHERE id = 1")
                    pending.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    execute("INSERT INTO shared_users VALUES (2, 'Also committed')")
                }
            }
            assertTrue(pending.await(5, TimeUnit.SECONDS))
            val reader = workers.submit<List<SharedUser>> {
                reading.countDown()
                KoKoDB<SharedUser>("SELECT * FROM shared_users")
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { reader.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(setOf(SharedUser(1, "Committed"), SharedUser(2, "Also committed")), reader.get(5, TimeUnit.SECONDS).toSet())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
