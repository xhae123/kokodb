package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Collections
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceOrderingTest {
    @TempDir lateinit var directory: Path
    private val loader = object : ClassLoader(Database::class.java.classLoader) {
        override fun getResources(name: String) =
            if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
            else super.getResources(name)
    }

    private enum class Phase { BOOTSTRAP, COMMIT, CHECKPOINT, TORN_TAIL, LEGACY_MIGRATION, STALE_WAL }

    @Test
    fun `modeled crashes preserve acknowledged transactions at every persistence boundary`() {
        var total = 0
        for (phase in Phase.entries) {
            val io = record(phase, phase.name)
            assertEquals(0, io.omitted)
            assertTrue(io.images.any { it.acknowledged })
            for ((index, image) in io.images.withIndex()) {
                val path = materialize(image, "${phase.name}-crash-$index")
                try {
                    verify(path, phase, image.acknowledged, continueWriting = true)
                } catch (failure: Throwable) {
                    throw AssertionError("$phase image $index after ${image.cut}, acknowledged=${image.acknowledged}", failure)
                }
                total++
            }
            println("$phase: ${io.images.size} unique crash images; trace=${io.trace.joinToString()}")
        }
        println("Persistence ordering: $total crash images recovered, committed again and reopened")
    }

    @Test
    fun `omitted force and directory barriers are detected by crash recovery`() {
        val controls = listOf(
            Phase.COMMIT to PersistenceModelIO.Barrier.WAL_FORCE,
            Phase.CHECKPOINT to PersistenceModelIO.Barrier.SNAPSHOT_FORCE,
            Phase.CHECKPOINT to PersistenceModelIO.Barrier.SNAPSHOT_DIRECTORY,
            Phase.BOOTSTRAP to PersistenceModelIO.Barrier.WAL_DIRECTORY,
        )
        for ((phase, barrier) in controls) {
            val io = record(phase, "negative-$barrier", barrier)
            assertTrue(io.omitted > 0, "The negative control did not omit $barrier")
            val relevant = if (barrier == PersistenceModelIO.Barrier.WAL_FORCE) io.images.filter { it.acknowledged } else io.images
            var detected: String? = null
            for ((index, image) in relevant.withIndex()) {
                val path = materialize(image, "negative-$barrier-crash-$index")
                try {
                    verify(path, phase, image.acknowledged, continueWriting = false)
                } catch (failure: DatabaseException) {
                    detected = "${image.cut}: ${failure.message}"
                } catch (failure: AssertionError) {
                    detected = "${image.cut}: ${failure.message}"
                }
                if (detected != null) break
            }
            assertTrue(detected != null, "The model failed to detect missing $barrier")
            println("Negative control $phase/$barrier detected: $detected")
        }
    }

    private fun record(phase: Phase, name: String, omit: PersistenceModelIO.Barrier? = null): PersistenceModelIO {
        val path = Files.createDirectory(directory.resolve(name)).resolve("app.koko")
        prepare(path, phase)
        val io = PersistenceModelIO(path, omit)
        Database.open(path, loader, io).use { db ->
            when (phase) {
                Phase.COMMIT -> db.transaction {
                    this("UPDATE first SET name = 'New' WHERE id = 1")
                    this("UPDATE second SET name = 'New' WHERE id = 1")
                    this("CREATE TABLE created (id INT PRIMARY KEY)")
                    this("INSERT INTO created VALUES (9)")
                }
                Phase.CHECKPOINT -> db.checkpoint()
                else -> Unit
            }
            io.acknowledge()
        }
        return io
    }

    private fun prepare(path: Path, phase: Phase) {
        if (phase == Phase.BOOTSTRAP) return
        if (phase == Phase.LEGACY_MIGRATION || phase == Phase.STALE_WAL) {
            val catalog = Catalog()
            for (name in listOf("first", "second")) {
                catalog.create(name, listOf(Column("id", DataType.INT, true), Column("name", DataType.TEXT)))
                catalog.table(name).publishRows(listOf(
                    listOf(Value.IntValue(1), Value.TextValue("Old")),
                    listOf(Value.IntValue(2), Value.TextValue("Acknowledged")),
                ))
            }
            val identity = UUID(40, 21)
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

    private fun materialize(image: PersistenceModelIO.Image, name: String): Path {
        val folder = Files.createDirectory(directory.resolve(name))
        for ((file, bytes) in image.files) Files.write(folder.resolve(file), bytes)
        return folder.resolve("app.koko")
    }

    private fun verify(path: Path, phase: Phase, acknowledged: Boolean, continueWriting: Boolean) {
        Database.open(path, loader).use { db ->
            if (phase == Phase.BOOTSTRAP) {
                assertFailsWith<DatabaseException> { db.query("SELECT * FROM first") }
                if (continueWriting) {
                    db("CREATE TABLE recovered (id INT)")
                    db("INSERT INTO recovered VALUES (3)")
                }
            } else {
                val values = listOf("first", "second").map { table ->
                    val rows = db.query("SELECT * FROM $table").associate { it.getInt("id") to it.getString("name") }
                    assertEquals(setOf(1, 2), rows.keys)
                    assertEquals("Acknowledged", rows[2])
                    rows.getValue(1)
                }
                assertEquals(values[0], values[1], "Partial two-table transaction")
                if (phase != Phase.COMMIT) assertEquals("Old", values[0])
                else {
                    if (acknowledged) assertEquals("New", values[0], "Lost an acknowledged commit")
                    else assertTrue(values[0] in setOf("Old", "New"))
                    if (values[0] == "New") assertEquals(9, db.query("SELECT id FROM created").single().getInt("id"))
                    else assertFailsWith<DatabaseException> { db.query("SELECT * FROM created") }
                }
                if (continueWriting) db.transaction {
                    this("INSERT INTO first VALUES (3, 'Recovered')")
                    this("INSERT INTO second VALUES (3, 'Recovered')")
                }
            }
            if (continueWriting) db.checkpoint()
        }
        if (continueWriting) Database.open(path, loader).use { db ->
            if (phase == Phase.BOOTSTRAP) assertEquals(3, db.query("SELECT id FROM recovered").single().getInt("id"))
            else for (table in listOf("first", "second")) {
                assertEquals(3, db.query("SELECT id FROM $table").size)
                assertEquals("Recovered", db.query("SELECT name FROM $table WHERE id = 3").single().getString("name"))
            }
        }
    }

    private fun wal(path: Path) = path.resolveSibling(path.fileName.toString() + ".wal")
}
