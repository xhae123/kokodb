package kokodb.storage

import kokodb.Database
import kokodb.DatabaseException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecoveryCatalogTest {
    @TempDir lateinit var directory: Path
    private val columns = listOf(Column("id", DataType.INT, true), Column("name", DataType.TEXT))
    private fun row(id: Int, text: String = "row$id") = listOf(Value.IntValue(id), Value.TextValue(text))
    private fun table(rows: List<List<Value>>, schema: List<Column> = columns) = Table(schema).also { it.publishRows(rows) }
    private fun contents(catalog: Catalog) = catalog.entries().mapValues { (_, table) -> table.columns to table.rows }

    @Test
    fun `incremental replay agrees with immutable replay for seeded mixed splices`() {
        val random = Random(3818)
        var catalog = Catalog()
        var nextKey = 0
        repeat(100) { epoch ->
            val source = catalog
            val original = contents(catalog)
            val expected = catalog.fork()
            val replay = RecoveryCatalog(catalog)
            repeat(50) { step ->
                val name = "items${random.nextInt(3)}"
                val previous = expected.entries()[name]
                val schema = previous?.columns ?: columns.map { it.copy(primaryKey = it.primaryKey && name != "items2") }
                val rows = previous?.rows?.toMutableList() ?: mutableListOf()
                when (random.nextInt(6)) {
                    0, 1 -> rows.add(random.nextInt(rows.size + 1), row(nextKey++, "한글 😀 $epoch:$step"))
                    2 -> if (rows.isNotEmpty()) rows.removeAt(random.nextInt(rows.size))
                    3 -> if (rows.isNotEmpty()) {
                        val index = random.nextInt(rows.size)
                        rows[index] = listOf(rows[index][0], Value.TextValue("changed"))
                    }
                    4 -> if (rows.size > 1) {
                        val first = random.nextInt(rows.size)
                        val second = random.nextInt(rows.size)
                        val value = rows[first]
                        rows[first] = rows[second]
                        rows[second] = value
                    }
                    else -> if (name == "items2") rows.add(row(0, "duplicate"))
                        else if (rows.isNotEmpty()) rows[random.nextInt(rows.size)] = row(nextKey++)
                }
                val changes = mapOf(name to TableSplice.between(previous, table(rows, schema)))
                val decoded = StorageCodec.decodeSpliceFrame(StorageCodec.encodeSpliceFrame(step + 1L, changes), step + 1L)
                replay.applySplices(decoded)
                expected.restoreSplices(decoded)
            }
            catalog = replay.finish()
            assertEquals(contents(expected), contents(catalog), "epoch $epoch")
            assertEquals(original, contents(source))
            assertFailsWith<DatabaseException> { replay.finish() }
            assertFailsWith<DatabaseException> { replay.applySplices(emptyMap()) }
        }
    }

    @Test
    fun `key swaps are legal and retained key collisions invalidate the private builder`() {
        val base = Catalog().also { it.restore(mapOf("items" to table(listOf(row(1), row(2), row(3))))) }
        val swap = RecoveryCatalog(base)
        swap.applySplices(mapOf("items" to TableSplice(columns, 3, 0, 2, listOf(row(2), row(1)))))
        assertEquals(listOf(row(2), row(1), row(3)), swap.finish().table("items").rows)
        for (inserted in listOf(listOf(row(3)), listOf(row(4), row(4)))) {
            val replay = RecoveryCatalog(base)
            assertFailsWith<DatabaseException> {
                replay.applySplices(mapOf("items" to TableSplice(columns, 3, 0, 1, inserted)))
            }
            assertFailsWith<DatabaseException> { replay.finish() }
            assertFailsWith<DatabaseException> { replay.applyTables(emptyMap()) }
            assertEquals(listOf(row(1), row(2), row(3)), base.table("items").rows)
        }
    }

    @Test
    fun `predecessor schema and creation mismatches reject replay before publication`() {
        val base = Catalog().also { it.restore(mapOf("items" to table(listOf(row(1))))) }
        for (splice in listOf(
            TableSplice(columns, -1, 0, 0, emptyList()),
            TableSplice(columns, 2, 0, 0, emptyList()),
            TableSplice(columns.map { it.copy(primaryKey = false) }, 1, 0, 0, emptyList()),
            TableSplice(columns, 1, 2, 0, emptyList()),
            TableSplice(columns, 1, 0, 2, emptyList()),
        )) {
            val replay = RecoveryCatalog(base)
            assertFailsWith<DatabaseException> { replay.applySplices(mapOf("items" to splice)) }
            assertFailsWith<DatabaseException> { replay.finish() }
        }
        val missing = RecoveryCatalog(Catalog())
        assertFailsWith<DatabaseException> { missing.applySplices(mapOf("items" to TableSplice(columns, 0, 0, 0, emptyList()))) }
        val legacy = RecoveryCatalog(base)
        assertFailsWith<DatabaseException> {
            legacy.applyTables(mapOf("items" to table(listOf(row(1)), columns.map { it.copy(primaryKey = false) })))
        }
        assertFailsWith<DatabaseException> { legacy.finish() }
    }

    @Test
    fun `late valid-checksum key collision fails open without altering authoritative files`() {
        val path = directory.resolve("corrupt.koko")
        val wal = directory.resolve("corrupt.koko.wal")
        val loader = object : ClassLoader(Database::class.java.classLoader) {
            override fun getResources(name: String) =
                if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
                else super.getResources(name)
        }
        Database.open(path, loader).use { db ->
            db.transaction {
                for (name in listOf("first", "second")) {
                    this("CREATE TABLE $name (id INT PRIMARY KEY, name TEXT)")
                    this("INSERT INTO $name VALUES (1, 'one')")
                    this("INSERT INTO $name VALUES (2, 'two')")
                }
            }
            db.checkpoint()
        }
        val snapshotBytes = Files.readAllBytes(path)
        val snapshot = StorageCodec.decodeSnapshot(snapshotBytes)
        val originalWal = Files.readAllBytes(wal)
        val corrupt = originalWal + StorageCodec.encodeSpliceFrame(snapshot.sequence + 1, linkedMapOf(
            "first" to TableSplice(columns, 2, 0, 1, listOf(row(3))),
            "second" to TableSplice(columns, 2, 0, 1, listOf(row(2))),
        ))
        Files.write(wal, corrupt)
        assertFailsWith<DatabaseException> { Database.open(path, loader) }
        assertContentEquals(snapshotBytes, Files.readAllBytes(path))
        assertContentEquals(corrupt, Files.readAllBytes(wal))
        Files.write(wal, originalWal)
        Database.open(path, loader).use { db ->
            for (name in listOf("first", "second")) {
                assertEquals(listOf(1, 2), db.query("SELECT id FROM $name").map { it.getInt("id") })
            }
        }
    }
}
