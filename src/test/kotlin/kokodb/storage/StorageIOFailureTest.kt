package kokodb.storage

import kokodb.CommitOutcomeUnknownException
import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Collections
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StorageIOFailureTest {
    @TempDir lateinit var directory: Path
    private val loader = object : ClassLoader(Database::class.java.classLoader) {
        override fun getResources(name: String) =
            if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
            else super.getResources(name)
    }

    private enum class Phase { BOOTSTRAP, COMMIT, CHECKPOINT, TORN_TAIL, LEGACY_MIGRATION, STALE_WAL }
    private enum class Failure { BEFORE, AFTER, PARTIAL_APPEND }

    private class FaultIO(val target: Int = -1, val failure: Failure = Failure.BEFORE) : StoreIO() {
        val trace = mutableListOf<String>()
        var armed = false
        var injected = false

        private fun fail(): Nothing {
            injected = true
            throw IOException("Injected $failure at ${trace.size - 1}:${trace.last()}")
        }

        private fun operation(name: String, partial: (() -> Unit)? = null, action: () -> Unit) {
            if (!armed) return action()
            trace.add(name)
            val selected = trace.lastIndex == target
            if (selected && failure == Failure.BEFORE) fail()
            if (selected && failure == Failure.PARTIAL_APPEND) {
                checkNotNull(partial)()
                fail()
            }
            action()
            if (selected && failure == Failure.AFTER) fail()
        }

        override fun append(channel: FileChannel, bytes: ByteArray) = operation("append", {
            super.append(channel, bytes.copyOf(maxOf(1, bytes.size / 2)))
        }) { super.append(channel, bytes) }

        override fun force(channel: FileChannel) = operation("force") { super.force(channel) }
        override fun replace(source: Path, target: Path) = operation("replace") { super.replace(source, target) }
        override fun syncDirectory(directory: Path) = operation("directory-sync") { super.syncDirectory(directory) }
    }

    @Test
    fun `every injected file operation preserves acknowledged state and permits recovery`() {
        var failures = 0
        for (phase in Phase.entries) {
            val trace = runCase(phase, FaultIO(), "baseline")
            assertTrue(trace.isNotEmpty(), "$phase produced no I/O trace")
            var phaseFailures = 0
            for ((target, operation) in trace.withIndex()) {
                val modes = listOf(Failure.BEFORE, Failure.AFTER) +
                    if (operation == "append") listOf(Failure.PARTIAL_APPEND) else emptyList()
                for (mode in modes) {
                    val visited = runCase(phase, FaultIO(target, mode), "$target-$mode")
                    assertEquals(trace.take(target + 1), visited, "$phase $target $mode")
                    failures++
                    phaseFailures++
                }
            }
            println("$phase: ${trace.joinToString()} | $phaseFailures injected failures passed")
        }
        println("Storage I/O matrix: $failures injected failures and ${Phase.entries.size} baselines passed")
    }

    private fun runCase(phase: Phase, io: FaultIO, suffix: String): List<String> {
        val folder = Files.createDirectory(directory.resolve("$phase-$suffix"))
        val path = folder.resolve("app.koko")
        prepare(path, phase)
        val opensBeforeArming = phase == Phase.COMMIT || phase == Phase.CHECKPOINT
        var db: Database? = if (opensBeforeArming) Database.open(path, loader, io) else null
        io.armed = true
        try {
            val action = {
                val handle = db ?: Database.open(path, loader, io).also { db = it }
                when (phase) {
                    Phase.COMMIT -> handle.transaction {
                        this("UPDATE first SET name = 'New' WHERE id = 1")
                        this("UPDATE second SET name = 'New' WHERE id = 1")
                    }
                    Phase.CHECKPOINT -> handle.checkpoint()
                    else -> Unit
                }
            }
            if (io.target < 0) action()
            else {
                val error = assertFailsWith<DatabaseException>("$phase $suffix") { action() }
                assertTrue(io.injected, "Failure did not reach the requested I/O operation: $phase $suffix")
                if (phase == Phase.COMMIT) assertIs<CommitOutcomeUnknownException>(error)
                if (opensBeforeArming) {
                    val handle = checkNotNull(db)
                    assertFailsWith<DatabaseException> { handle.query("SELECT * FROM first") }
                    assertFailsWith<DatabaseException> { handle("DELETE FROM first") }
                    assertFailsWith<DatabaseException> { handle.checkpoint() }
                }
            }
        } finally { db?.close() }
        verifyAndContinue(path, phase, io.target < 0)
        Files.list(folder).use { files ->
            assertEquals(0L, files.filter { it.fileName.toString().endsWith(".tmp") }.count())
        }
        return io.trace
    }

    private fun prepare(path: Path, phase: Phase) {
        if (phase == Phase.BOOTSTRAP) return
        if (phase == Phase.LEGACY_MIGRATION || phase == Phase.STALE_WAL) {
            val catalog = Catalog()
            for (table in listOf("first", "second")) {
                catalog.create(table, listOf(Column("id", DataType.INT, true), Column("name", DataType.TEXT)))
                catalog.table(table).publishRows(listOf(
                    listOf(Value.IntValue(1), Value.TextValue("Old")),
                    listOf(Value.IntValue(2), Value.TextValue("Acknowledged")),
                ))
            }
            val identity = UUID(36, 18)
            val legacy = phase == Phase.LEGACY_MIGRATION
            Files.write(path, StorageCodec.encodeSnapshot(Snapshot(identity, if (legacy) 0 else 1, if (legacy) Catalog() else catalog)))
            val frame = if (legacy) StorageCodec.encodeFrame(1, catalog.entries()) else
                StorageCodec.encodeSpliceFrame(1, catalog.entries().mapValues { (_, table) -> TableSplice.between(null, table) })
            Files.write(wal(path), StorageCodec.encodeWalHeader(WalHeader(identity, 0, if (legacy) 1 else 2)) + frame)
        } else {
            Database.open(path, loader).use { db ->
                db.transaction {
                    for (table in listOf("first", "second")) {
                        this("CREATE TABLE $table (id INT PRIMARY KEY, name TEXT)")
                        this("INSERT INTO $table VALUES (1, 'Old')")
                        this("INSERT INTO $table VALUES (2, 'Acknowledged')")
                    }
                }
            }
            if (phase == Phase.TORN_TAIL) Files.write(wal(path), byteArrayOf(75, 84, 88, 50), APPEND)
        }
    }

    private fun verifyAndContinue(path: Path, phase: Phase, baseline: Boolean) {
        Database.open(path, loader).use { db ->
            if (phase == Phase.BOOTSTRAP) {
                db("CREATE TABLE recovered (id INT)")
                db("INSERT INTO recovered VALUES (1)")
            } else {
                val values = listOf("first", "second").map { table ->
                    val rows = db.query("SELECT * FROM $table").associate { it.getInt("id") to it.getString("name") }
                    assertEquals(setOf(1, 2), rows.keys)
                    assertEquals("Acknowledged", rows[2])
                    rows.getValue(1)
                }
                assertEquals(values[0], values[1], "Partial two-table recovery")
                if (phase != Phase.COMMIT) assertEquals("Old", values[0])
                else if (baseline) assertEquals("New", values[0])
                else assertTrue(values[0] in setOf("Old", "New"))
                db.transaction {
                    this("INSERT INTO first VALUES (3, 'Recovered')")
                    this("INSERT INTO second VALUES (3, 'Recovered')")
                }
            }
            db.checkpoint()
        }
        Database.open(path, loader).use { db ->
            if (phase == Phase.BOOTSTRAP) assertEquals(1, db.query("SELECT id FROM recovered").single().getInt("id"))
            else for (table in listOf("first", "second")) {
                assertEquals(3, db.query("SELECT id FROM $table").size)
                assertEquals("Recovered", db.query("SELECT name FROM $table WHERE id = 3").single().getString("name"))
            }
        }
    }

    private fun wal(path: Path) = path.resolveSibling(path.fileName.toString() + ".wal")
}
