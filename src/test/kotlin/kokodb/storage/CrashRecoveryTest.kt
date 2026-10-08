package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object CrashProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = Path.of(args[0])
        if (args[1] == "lock") {
            val failure = runCatching { Database.open(path).close() }.exceptionOrNull()
            val rejected = failure is DatabaseException && failure.message?.contains("locked by another process") == true
            kotlin.system.exitProcess(if (rejected) 0 else 1)
        }
        val boundary = StoreEvent.valueOf(args[1])
        val io = object : StoreIO() {
            var armed = false
            override fun event(event: StoreEvent) {
                if (armed && event == boundary) Runtime.getRuntime().halt(91)
            }
        }
        Database.open(path, Database::class.java.classLoader, io).use { db ->
            io.armed = true
            if (boundary in listOf(StoreEvent.BEFORE_APPEND, StoreEvent.AFTER_APPEND, StoreEvent.AFTER_WAL_FORCE)) {
                db.transaction {
                    this("UPDATE first SET name = 'New' WHERE id = 1")
                    this("UPDATE second SET name = 'New' WHERE id = 1")
                }
            } else {
                db.transaction {
                    this("UPDATE first SET name = 'New' WHERE id = 1")
                    this("UPDATE second SET name = 'New' WHERE id = 1")
                }
                println("commit acknowledged")
                db.checkpoint()
            }
        }
        error("Crash boundary was not reached")
    }
}

class CrashRecoveryTest {
    @TempDir lateinit var directory: Path

    private fun initialize(path: Path) {
        Database.open(path).use { db ->
            db.transaction {
                for (name in listOf("first", "second")) {
                    this("CREATE TABLE $name (id INT PRIMARY KEY, name TEXT)")
                    this("INSERT INTO $name VALUES (1, 'Old')")
                    this("INSERT INTO $name VALUES (2, 'Acknowledged')")
                }
            }
        }
    }

    private fun child(path: Path, boundary: String): Pair<Int, String> {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:-UsePerfData", "-cp", System.getProperty("kokodb.test.classpath"),
            "kokodb.storage.CrashProcess", path.toString(), boundary,
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child did not exit at $boundary")
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @Test
    fun `process termination across commit and checkpoint boundaries preserves atomic recovery`() {
        for (boundary in StoreEvent.entries) {
            val folder = Files.createDirectory(directory.resolve(boundary.name))
            val path = folder.resolve("app.koko")
            initialize(path)
            val (exit, log) = child(path, boundary.name)
            assertEquals(91, exit, "$boundary: $log")
            Database.open(path).use { db ->
                val first = db.query("SELECT * FROM first WHERE id = 1").single().getString("name")
                val second = db.query("SELECT * FROM second WHERE id = 1").single().getString("name")
                assertEquals(first, second, "partial multi-table recovery at $boundary")
                if (boundary == StoreEvent.BEFORE_APPEND) assertEquals("Old", first)
                else if (boundary != StoreEvent.AFTER_APPEND) assertEquals("New", first)
                assertTrue(first == "Old" || first == "New")
                for (name in listOf("first", "second")) {
                    assertEquals("Acknowledged", db.query("SELECT * FROM $name WHERE id = 2").single().getString("name"))
                }
                db("INSERT INTO first VALUES (3, 'After recovery')")
            }
            Database.open(path).use { db -> assertEquals(3, db.query("SELECT * FROM first").size) }
            Files.list(folder).use { files -> assertEquals(0L, files.filter { it.fileName.toString().endsWith(".tmp") }.count()) }
        }
    }

    @Test
    fun `lifetime ownership excludes another JVM`() {
        val path = directory.resolve("app.koko")
        Database.open(path).use { assertEquals(0, child(path, "lock").first) }
    }

    @Test
    fun `every torn frame tail retains acknowledged snapshot data and allows new appends`() {
        val source = directory.resolve("source.koko")
        initialize(source)
        Database.open(source).use { it.checkpoint() }
        val snapshot = StorageCodec.decodeSnapshot(Files.readAllBytes(source))
        val changed = snapshot.catalog.table("first").fork()
        changed.publishRows(listOf(listOf(Value.IntValue(1), Value.TextValue("Candidate"))))
        val frame = StorageCodec.encodeFrame(snapshot.sequence + 1, mapOf("first" to changed))
        val header = Files.readAllBytes(directory.resolve("source.koko.wal"))
        for (length in 0 until frame.size) {
            val path = directory.resolve("torn-$length.koko")
            Files.copy(source, path)
            Files.write(directory.resolve("torn-$length.koko.wal"), header + frame.copyOf(length))
            Database.open(path).use { db ->
                assertEquals("Old", db.query("SELECT * FROM first WHERE id = 1").single().getString("name"))
                assertEquals(2, db.query("SELECT * FROM first").size)
                db("INSERT INTO first VALUES (3, 'After torn tail')")
            }
            Database.open(path).use { db -> assertEquals(3, db.query("SELECT * FROM first").size) }
        }
    }
}
