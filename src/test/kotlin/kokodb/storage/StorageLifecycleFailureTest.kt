package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StorageLifecycleFailureTest {
    @TempDir lateinit var directory: Path
    private val loader = object : ClassLoader(Database::class.java.classLoader) {
        override fun getResources(name: String) =
            if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
            else super.getResources(name)
    }

    @Test
    fun `bootstrap cleanup preserves the original write failure`() = cleanupFailure(checkpoint = false)

    @Test
    fun `checkpoint cleanup preserves the original write failure`() = cleanupFailure(checkpoint = true)

    private fun cleanupFailure(checkpoint: Boolean) {
        val path = directory.resolve("cleanup.koko")
        val primary = IOException("Original append failure")
        val cleanup = IOException("Temporary cleanup failure")
        var armed = !checkpoint
        val io = object : StoreIO() {
            override fun append(channel: FileChannel, bytes: ByteArray) {
                if (armed) throw primary
                super.append(channel, bytes)
            }
            override fun deleteIfExists(path: Path): Boolean {
                if (armed) throw cleanup
                return super.deleteIfExists(path)
            }
        }
        val db = if (checkpoint) Database.open(path, loader, io) else null
        try {
            armed = true
            val failure = assertFailsWith<DatabaseException> {
                if (db != null) db.checkpoint() else Database.open(path, loader, io)
            }
            assertSame(primary, failure.cause)
            assertTrue(primary.suppressed.any { it === cleanup })
        } finally { armed = false; db?.close() }
        Database.open(path, loader).use { it("CREATE TABLE recovered (id INT)") }
    }

    @Test
    fun `ownership close failure is suppressed on the original WAL close failure`() {
        val path = directory.resolve("close.koko")
        val walFailure = IOException("WAL close failed")
        val lockFailure = IOException("Ownership close failed")
        var armed = false
        var closes = 0
        val io = object : StoreIO() {
            override fun close(channel: FileChannel) {
                super.close(channel)
                if (armed) throw if (closes++ == 0) walFailure else lockFailure
            }
        }
        val db = Database.open(path, loader, io)
        db("CREATE TABLE acknowledged (id INT)")
        db("INSERT INTO acknowledged VALUES (1)")
        armed = true
        try {
            val failure = assertFailsWith<IOException> { db.close() }
            assertSame(walFailure, failure)
            assertTrue(failure.suppressed.any { it === lockFailure })
            assertFailsWith<DatabaseException> { db.query("SELECT * FROM acknowledged") }
        } finally { armed = false; db.close() }
        Database.open(path, loader).use { assertEquals(1, it.query("SELECT id FROM acknowledged").single().getInt("id")) }
    }

    private enum class Phase { BOOTSTRAP, RECOVERY, TORN_TAIL, CHECKPOINT, CLOSE, STALE_TEMP }
    private enum class Failure { BEFORE, AFTER }

    private class FaultIO(val target: Int = -1, val failure: Failure = Failure.BEFORE) : StoreIO() {
        var armed = false
        var injected = false
        val trace = mutableListOf<String>()
        val cause = IOException("Injected storage lifecycle failure")

        private fun fail(): Nothing { injected = true; throw cause }
        private fun <T> operation(name: String, action: () -> T): T {
            if (!armed || injected) return action()
            trace.add(name)
            val selected = trace.lastIndex == target
            if (selected && failure == Failure.BEFORE) fail()
            val result = action()
            if (selected && failure == Failure.AFTER) fail()
            return result
        }

        override fun open(path: Path, vararg options: OpenOption): FileChannel = operation("open") { super.open(path, *options) }
        override fun read(channel: FileChannel, buffer: ByteBuffer): Int = operation("read-channel") { super.read(channel, buffer) }
        override fun readAllBytes(path: Path): ByteArray = operation("read-file") { super.readAllBytes(path) }
        override fun truncate(channel: FileChannel, size: Long) = operation("truncate") { super.truncate(channel, size) }
        override fun close(channel: FileChannel) = operation("close") { super.close(channel) }
        override fun deleteIfExists(path: Path): Boolean = operation("delete") { super.deleteIfExists(path) }
    }

    @Test
    fun `read truncate open close and cleanup failures release ownership and preserve acknowledged rows`() {
        var total = 0
        for (phase in Phase.entries) {
            val trace = runCase(phase, FaultIO(), "baseline")
            var count = 0
            for ((target, operation) in trace.withIndex()) {
                val modes = when (operation) {
                    "open" -> listOf(Failure.BEFORE)
                    "close" -> listOf(Failure.AFTER)
                    else -> Failure.entries
                }
                for (mode in modes) {
                    val visited = runCase(phase, FaultIO(target, mode), "$target-$mode")
                    assertEquals(trace.take(target + 1), visited, "$phase $target $mode")
                    count++
                }
            }
            total += count
            println("$phase: ${trace.joinToString()} | $count injected failures passed")
        }
        println("Storage lifecycle matrix: $total injected failures and ${Phase.entries.size} baselines passed")
    }

    private fun runCase(phase: Phase, io: FaultIO, suffix: String): List<String> {
        val folder = Files.createDirectory(directory.resolve("$phase-$suffix"))
        val path = folder.resolve("app.koko")
        prepare(path, phase)
        val live = phase == Phase.CHECKPOINT || phase == Phase.CLOSE
        var db: Database? = if (live) Database.open(path, loader, io) else null
        io.armed = true
        try {
            val action = {
                val handle = db ?: Database.open(path, loader, io).also { db = it }
                when (phase) {
                    Phase.CHECKPOINT -> handle.checkpoint()
                    Phase.CLOSE -> handle.close()
                    else -> Unit
                }
            }
            if (io.target < 0) action() else {
                val failure = assertFailsWith<Exception> { action() }
                assertTrue(io.injected)
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === io.cause })
                if (live) assertFailsWith<DatabaseException> { checkNotNull(db).query("SELECT * FROM first") }
            }
        } finally { io.armed = false; db?.close() }
        Database.open(path, loader).use { recovered ->
            if (phase == Phase.BOOTSTRAP) recovered("CREATE TABLE first (id INT PRIMARY KEY)")
            else for (table in listOf("first", "second")) {
                assertEquals(1, recovered.query("SELECT id FROM $table").single().getInt("id"))
            }
            recovered("INSERT INTO first VALUES (2)")
            recovered.checkpoint()
        }
        Database.open(path, loader).use { recovered ->
            assertEquals(if (phase == Phase.BOOTSTRAP) setOf(2) else setOf(1, 2), recovered.query("SELECT id FROM first").map { it.getInt("id") }.toSet())
            if (phase != Phase.BOOTSTRAP) assertEquals(1, recovered.query("SELECT id FROM second").single().getInt("id"))
        }
        Files.list(folder).use { assertEquals(0L, it.filter { file -> file.fileName.toString().endsWith(".tmp") }.count()) }
        return io.trace
    }

    private fun prepare(path: Path, phase: Phase) {
        if (phase == Phase.BOOTSTRAP) return
        val leaveTemporary = object : StoreIO() {
            var armed = false
            override fun replace(source: Path, target: Path) {
                if (armed) throw IOException("Leave a temporary checkpoint for recovery cleanup")
                super.replace(source, target)
            }
            override fun deleteIfExists(path: Path): Boolean = if (armed) false else super.deleteIfExists(path)
        }
        Database.open(path, loader, leaveTemporary).use { db ->
            db.transaction {
                for (table in listOf("first", "second")) {
                    this("CREATE TABLE $table (id INT PRIMARY KEY)")
                    this("INSERT INTO $table VALUES (1)")
                }
            }
            if (phase == Phase.STALE_TEMP) {
                leaveTemporary.armed = true
                assertFailsWith<DatabaseException> { db.checkpoint() }
            }
        }
        if (phase == Phase.TORN_TAIL) Files.write(path.resolveSibling(path.fileName.toString() + ".wal"), byteArrayOf(75, 84, 88, 50), APPEND)
    }
}
