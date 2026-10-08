package kokodb.storage

import kokodb.CommitOutcomeUnknownException
import kokodb.Database
import kokodb.DatabaseException
import kokodb.DbTable
import kokodb.Id
import kokodb.KoKoDB
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DbTable("durable_people")
data class DurablePerson(@Id val id: Int, val name: String)

class FileStoreTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("app.koko")
    private val wal get() = directory.resolve("app.koko.wal")
    private val loader get() = Database::class.java.classLoader

    private fun initialize() {
        Database.open(path).use { db ->
            db("CREATE TABLE records (id INT PRIMARY KEY, name TEXT)")
            db("INSERT INTO records VALUES (1, 'Original')")
        }
    }

    @Test
    fun `commits survive close and reopen without requiring a checkpoint`() {
        Database.open(path).use { db ->
            db("INSERT INTO durable_people VALUES (1, '한글 😀')")
            db.transaction {
                this("CREATE TABLE audit (name TEXT)")
                this("INSERT INTO audit VALUES ('created')")
                this("UPDATE durable_people SET name = 'Updated' WHERE id = 1")
            }
            assertFailsWith<IllegalStateException> {
                db.transaction { this("DELETE FROM durable_people"); error("discard") }
            }
        }
        Database.open(path).use { db ->
            assertEquals(listOf(DurablePerson(1, "Updated")), db.queryModels(DurablePerson::class.java, "SELECT * FROM durable_people"))
            assertEquals("created", db.query("SELECT * FROM audit").single().getString("name"))
        }
    }

    @Test
    fun `checkpoint resets WAL only after installing the complete snapshot`() {
        initialize()
        Database.open(path).use { db ->
            assertTrue(Files.size(wal) > StorageCodec.WAL_HEADER)
            db.checkpoint()
            assertEquals(StorageCodec.WAL_HEADER.toLong(), Files.size(wal))
            db("UPDATE records SET name = 'After' WHERE id = 1")
        }
        Database.open(path).use { db -> assertEquals("After", db.query("SELECT * FROM records").single().getString("name")) }
    }

    @Test
    fun `no net change and rolled back callbacks append no WAL`() {
        initialize()
        Database.open(path).use { db ->
            val before = Files.size(wal)
            assertEquals(1, db("UPDATE records SET name = 'Original' WHERE id = 1"))
            assertEquals(0, db("DELETE FROM records WHERE id = 99"))
            db.transaction {
                this("UPDATE records SET name = 'Temporary' WHERE id = 1")
                this("UPDATE records SET name = 'Original' WHERE id = 1")
            }
            assertFailsWith<IllegalArgumentException> { db.transaction { this("DELETE FROM records"); throw IllegalArgumentException() } }
            assertEquals(before, Files.size(wal))
        }
    }

    @Test
    fun `partial append is indeterminate and torn tail is removed before future writes`() {
        initialize()
        val io = object : StoreIO() {
            var armed = false
            override fun append(channel: FileChannel, bytes: ByteArray) {
                if (!armed) return super.append(channel, bytes)
                channel.write(ByteBuffer.wrap(bytes, 0, bytes.size / 2))
                throw IOException("injected partial append")
            }
        }
        Database.open(path, loader, io).use { db ->
            io.armed = true
            assertFailsWith<CommitOutcomeUnknownException> { db("UPDATE records SET name = 'Suspect' WHERE id = 1") }
            assertFailsWith<DatabaseException> { db.query("SELECT * FROM records") }
            assertFailsWith<DatabaseException> { db("DELETE FROM records") }
            assertFailsWith<DatabaseException> { db.checkpoint() }
        }
        Database.open(path).use { db ->
            assertEquals("Original", db.query("SELECT * FROM records").single().getString("name"))
            db("UPDATE records SET name = 'Recovered' WHERE id = 1")
        }
        Database.open(path).use { db -> assertEquals("Recovered", db.query("SELECT * FROM records").single().getString("name")) }
    }

    @Test
    fun `force errors cannot pretend to prove rollback after a durable write`() {
        initialize()
        val io = object : StoreIO() {
            var armed = false
            override fun force(channel: FileChannel) {
                super.force(channel)
                if (armed) throw IOException("injected failure after force")
            }
        }
        Database.open(path, loader, io).use { db ->
            io.armed = true
            assertFailsWith<CommitOutcomeUnknownException> { db("UPDATE records SET name = 'Durable' WHERE id = 1") }
            assertFailsWith<DatabaseException> { db.query("SELECT * FROM records") }
        }
        Database.open(path).use { db -> assertEquals("Durable", db.query("SELECT * FROM records").single().getString("name")) }
    }

    @Test
    fun `pre append and text encoding failures are definite failures and retain a healthy handle`() {
        initialize()
        val io = object : StoreIO() {
            var armed = false
            override fun event(event: StoreEvent) {
                if (armed && event == StoreEvent.BEFORE_APPEND) throw IllegalStateException("before append")
            }
        }
        Database.open(path, loader, io).use { db ->
            val before = Files.size(wal)
            assertFailsWith<DatabaseException> { db("UPDATE records SET name = :name", mapOf("name" to "\ud800")) }
            io.armed = true
            assertFailsWith<IllegalStateException> { db("DELETE FROM records") }
            assertEquals(before, Files.size(wal))
            assertEquals("Original", db.query("SELECT * FROM records").single().getString("name"))
        }
    }

    @Test
    fun `a second owner fails until close releases the lock`() {
        val db = Database.open(path)
        assertFailsWith<DatabaseException> { Database.open(path) }
        db.close()
        db.close()
        assertFailsWith<DatabaseException> { db.query("SELECT * FROM durable_people") }
        Database.open(path).use { assertTrue(it.query("SELECT * FROM durable_people").isEmpty()) }
    }

    @Test
    fun `corrupt complete WAL and snapshot files fail without rewriting them`() {
        initialize()
        val original = Files.readAllBytes(wal)
        val corrupted = original.clone().also { it[it.lastIndex - 12] = (it[it.lastIndex - 12].toInt() xor 1).toByte() }
        Files.write(wal, corrupted)
        assertFailsWith<DatabaseException> { Database.open(path) }
        assertContentEquals(corrupted, Files.readAllBytes(wal))
        Files.write(wal, original)
        val snapshot = Files.readAllBytes(path)
        Files.write(path, snapshot.clone().also { it[0] = 0 })
        assertFailsWith<DatabaseException> { Database.open(path) }
        Files.write(path, snapshot)
        Database.open(path).use { assertEquals(1, it.query("SELECT * FROM records").size) }
    }

    @Test
    fun `missing or mismatched sidecars cannot silently clear committed data`() {
        initialize()
        val original = Files.readAllBytes(wal)
        Files.delete(wal)
        assertFailsWith<DatabaseException> { Database.open(path) }
        Files.write(wal, StorageCodec.encodeWalHeader(WalHeader(UUID.randomUUID(), 0)))
        assertFailsWith<DatabaseException> { Database.open(path) }
        Files.write(wal, original)
        val snapshot = Files.readAllBytes(path)
        Files.delete(path)
        assertFailsWith<DatabaseException> { Database.open(path) }
        Files.write(path, snapshot)
        Database.open(path).use { assertEquals("Original", it.query("SELECT * FROM records").single().getString("name")) }
    }

    @Test
    fun `WAL first bootstrap may complete only an empty sequence zero WAL`() {
        val identity = UUID.randomUUID()
        Files.write(wal, StorageCodec.encodeWalHeader(WalHeader(identity, 0)))
        Database.open(path).use { assertTrue(it.query("SELECT * FROM durable_people").isEmpty()) }
        assertEquals(identity, StorageCodec.decodeSnapshot(Files.readAllBytes(path)).identity)
    }

    @Test
    fun `generated schema mismatch rejects opening before writing missing model tables`() {
        val rawLoader = object : ClassLoader(loader) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
                if (name == "META-INF/services/kokodb.mapping.ModelProvider") java.util.Collections.emptyEnumeration()
                else super.getResources(name)
        }
        Database.open(path, rawLoader).use { it("CREATE TABLE durable_people (id INT)") }
        val before = Files.readAllBytes(wal)
        assertFailsWith<DatabaseException> { Database.open(path) }
        assertContentEquals(before, Files.readAllBytes(wal))
        Database.open(path, rawLoader).use { assertTrue(it.query("SELECT * FROM durable_people").isEmpty()) }
    }

    @Test
    fun `failed checkpoints retain acknowledged data and require recovery`() {
        for (boundary in listOf(StoreEvent.SNAPSHOT_REPLACED, StoreEvent.WAL_RESET_REPLACED)) {
            val target = directory.resolve("$boundary.koko")
            val io = object : StoreIO() {
                var armed = false
                override fun event(event: StoreEvent) {
                    if (armed && event == boundary) throw IOException("injected replacement failure")
                }
            }
            Database.open(target, loader, io).use { db ->
                db("INSERT INTO durable_people VALUES (1, 'Acknowledged')")
                io.armed = true
                assertFailsWith<DatabaseException> { db.checkpoint() }
                assertFailsWith<DatabaseException> { db.query("SELECT * FROM durable_people") }
            }
            Database.open(target).use { db ->
                assertEquals(listOf(DurablePerson(1, "Acknowledged")), db.queryModels(DurablePerson::class.java, "SELECT * FROM durable_people"))
            }
        }
    }

    @Test
    fun `complete sequence gaps fail without scanning past or repairing the record`() {
        initialize()
        Database.open(path).use { it.checkpoint() }
        val snapshot = StorageCodec.decodeSnapshot(Files.readAllBytes(path))
        val bytes = Files.readAllBytes(wal) + StorageCodec.encodeFrame(snapshot.sequence + 2, snapshot.catalog.entries())
        Files.write(wal, bytes)
        assertFailsWith<DatabaseException> { Database.open(path) }
        assertContentEquals(bytes, Files.readAllBytes(wal))
    }

    @Test
    fun `failed explicit file opening cannot fall back to a lazy memory store`() {
        KoKoDB.close()
        try {
            assertFailsWith<DatabaseException> { KoKoDB.open(directory.resolve("missing").resolve("app.koko")) }
            assertFailsWith<DatabaseException> { KoKoDB.query("SELECT * FROM durable_people") }
        } finally { KoKoDB.close() }
    }

    @Test
    fun `shared facade closes file channels and supports explicit persistent reopen`() {
        KoKoDB.close()
        try {
            KoKoDB.open(path)
            KoKoDB("INSERT INTO durable_people VALUES (1, 'Shared')")
            assertFailsWith<DatabaseException> { KoKoDB.open(path) }
            assertFailsWith<DatabaseException> { KoKoDB.transaction { KoKoDB.checkpoint() } }
            KoKoDB.close()
            assertFailsWith<DatabaseException> { KoKoDB<DurablePerson>("SELECT * FROM durable_people") }
            KoKoDB.open(path)
            assertEquals(listOf(DurablePerson(1, "Shared")), KoKoDB<DurablePerson>("SELECT * FROM durable_people"))
            KoKoDB.checkpoint()
        } finally { KoKoDB.close() }
    }
}
