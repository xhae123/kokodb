package kokodb.storage

import kokodb.DatabaseException
import kokodb.execution.Executor
import kokodb.sql.Parser
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnapshotSizeTest {
    private fun encodedSize(catalog: Catalog) = StorageCodec.encodeSnapshot(Snapshot(UUID(0, 1), 0, catalog)).size.toLong()

    @Test
    fun `size matches the codec for every Unicode width and schema metadata`() {
        val random = Random(3418)
        val catalog = Catalog()
        catalog.create("empty", listOf(Column("value", DataType.INT)))
        catalog.create("unicode_rows", listOf(Column("id", DataType.INT, true), Column("text_value", DataType.TEXT)))
        val texts = listOf("", "\u0000\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff", "한글 😀 \uDBFF\uDFFF") +
            List(200) {
                buildString {
                    repeat(random.nextInt(60)) {
                        var point: Int
                        do { point = random.nextInt(0x110000) } while (point in 0xd800..0xdfff)
                        appendCodePoint(point)
                    }
                }
            }
        catalog.table("unicode_rows").publishRows(texts.mapIndexed { index, text -> listOf(Value.IntValue(index), Value.TextValue(text)) })
        assertEquals(encodedSize(catalog), SnapshotSize.of(catalog))
        for (text in listOf("\ud800", "\udc00", "\ud800a", "\udc00\ud800")) {
            catalog.table("unicode_rows").publishRows(listOf(listOf(Value.IntValue(1), Value.TextValue(text))))
            assertFailsWith<DatabaseException> { SnapshotSize.of(catalog) }
        }
    }

    @Test
    fun `incremental size matches actual encoding across seeded mixed changes`() {
        val random = Random(34018)
        var catalog = Catalog()
        var size = SnapshotSize.of(catalog)
        repeat(600) { step ->
            val next = catalog.fork()
            val executor = Executor(next)
            fun write(sql: String) { executor.write(Parser(sql).parse(), emptyMap()) }
            if (step < 3) write("CREATE TABLE items$step (id INT, name TEXT)")
            else repeat(random.nextInt(1, 5)) {
                val table = "items${random.nextInt(3)}"
                val id = random.nextInt(20)
                when (random.nextInt(4)) {
                    0 -> write("INSERT INTO $table VALUES ($id, 'Text 한글 😀')")
                    1 -> write("UPDATE $table SET name = '${"a".repeat(random.nextInt(30))}' WHERE id = $id")
                    2 -> write("DELETE FROM $table WHERE id = $id")
                    else -> write("UPDATE $table SET id = $id")
                }
            }
            val before = catalog.entries()
            val changes = next.entries().filter { (name, table) -> before[name]?.rows != table.rows }
                .mapValues { (name, table) -> TableSplice.between(before[name], table) }
            size = SnapshotSize.after(size, before, changes)
            assertEquals(encodedSize(next), size, "step $step")
            catalog = next
        }
    }
}
