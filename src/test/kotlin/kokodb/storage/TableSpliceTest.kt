package kokodb.storage

import kokodb.DatabaseException
import java.nio.ByteBuffer
import java.util.zip.CRC32C
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableSpliceTest {
    private val columns = listOf(Column("id", DataType.INT, true), Column("name", DataType.TEXT))
    private fun row(id: Int, name: String = "row$id") = listOf(Value.IntValue(id), Value.TextValue(name))
    private fun table(rows: List<List<Value>>, schema: List<Column> = columns) = Table(schema).also { it.publishRows(rows) }
    private fun encoded(before: Table?, after: Table) =
        StorageCodec.encodeSpliceFrame(1, mapOf("items" to TableSplice.between(before, after)))
    private fun decoded(bytes: ByteArray) = StorageCodec.decodeSpliceFrame(bytes, 1).getValue("items")

    @Test
    fun `empty version two frame has stable golden encoding`() {
        val bytes = StorageCodec.encodeSpliceFrame(8, emptyMap())
        assertEquals("4b5458320000002400000000000000080000000400000000f8544857434d495400000024", bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `point changes record only the replacement regardless of table size`() {
        for (size in listOf(10, 10000)) {
            val before = table(List(size) { row(it) })
            val inserted = table(before.rows + listOf(row(-1)))
            val updated = table(before.rows.mapIndexed { i, row -> if (i == size / 2) row(-1) else row })
            val deleted = table(before.rows.filterIndexed { i, _ -> i != size / 2 })
            for (after in listOf(inserted, updated, deleted)) {
                val bytes = encoded(before, after)
                val splice = decoded(bytes)
                assertTrue(bytes.size < 128)
                assertTrue(splice.inserted.size <= 1)
                assertEquals(after.rows, splice.apply(before).rows)
            }
        }
    }

    @Test
    fun `random row sequences including keyless duplicates round trip`() {
        val random = Random(181025)
        val schema = listOf(Column("value", DataType.INT))
        repeat(1000) {
            val before = table(List(random.nextInt(30)) { listOf(Value.IntValue(random.nextInt(4))) }, schema)
            val after = table(List(random.nextInt(30)) { listOf(Value.IntValue(random.nextInt(4))) }, schema)
            assertEquals(after.rows, decoded(encoded(before, after)).apply(before).rows)
            assertEquals(after.rows, decoded(encoded(null, after)).apply(null).rows)
        }
    }

    @Test
    fun `version two frames reject every complete corruption and truncation`() {
        val frame = encoded(table(listOf(row(1))), table(listOf(row(1), row(2, "한글 😀"))))
        for (index in frame.indices) {
            val bytes = frame.clone().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<DatabaseException>("byte $index") { decoded(bytes) }
        }
        for (length in 0 until frame.size) {
            assertFailsWith<DatabaseException>("length $length") { decoded(frame.copyOf(length)) }
        }
        for (length in 0 until StorageCodec.FRAME_PREFIX) {
            assertNull(StorageCodec.frameLength(frame.copyOf(length), 1, 2))
        }
        assertFailsWith<DatabaseException> { StorageCodec.frameLength(frame.copyOf(20), 1, 1) }
    }

    @Test
    fun `valid checksum cannot bypass splice bounds counts or predecessor validation`() {
        val before = table(listOf(row(1), row(2)))
        val after = table(listOf(row(1), row(3)))
        val frame = encoded(before, after)
        val offset = spliceOffset(frame)
        for ((field, invalid) in listOf(0 to -2, 4 to -1, 4 to 3, 8 to -1, 8 to 2, 12 to Int.MAX_VALUE)) {
            val bytes = frame.clone()
            ByteBuffer.wrap(bytes).putInt(offset + field, invalid)
            repairChecksum(bytes)
            assertFailsWith<DatabaseException> { decoded(bytes) }
        }
        val splice = decoded(frame)
        assertFailsWith<DatabaseException> { splice.apply(null) }
        assertFailsWith<DatabaseException> { splice.apply(table(listOf(row(1)))) }
        assertFailsWith<DatabaseException> { decoded(encoded(null, after)).apply(before) }
        val wrongSchema = table(before.rows, columns.map { it.copy(primaryKey = false) })
        assertFailsWith<DatabaseException> { splice.apply(wrongSchema) }
        val overflow = frame.clone()
        ByteBuffer.wrap(overflow).putInt(offset, Int.MAX_VALUE).putInt(offset + 4, 0).putInt(offset + 8, 0)
        repairChecksum(overflow)
        assertFailsWith<DatabaseException> { decoded(overflow) }
    }

    @Test
    fun `resulting key collision rejects the whole multi table recovered candidate`() {
        val catalog = Catalog()
        for (name in listOf("first", "second")) {
            catalog.create(name, columns)
            catalog.table(name).publishRows(listOf(row(1), row(2)))
        }
        val changes = linkedMapOf(
            "first" to TableSplice(columns, 2, 0, 1, listOf(row(3))),
            "second" to TableSplice(columns, 2, 0, 1, listOf(row(2))),
        )
        val decoded = StorageCodec.decodeSpliceFrame(StorageCodec.encodeSpliceFrame(1, changes), 1)
        assertFailsWith<DatabaseException> { catalog.restoreSplices(decoded) }
        assertEquals(listOf(row(1), row(2)), catalog.table("first").rows)
        assertEquals(listOf(row(1), row(2)), catalog.table("second").rows)
    }

    @Test
    fun `duplicate inserted keys malformed text and duplicate table changes are rejected`() {
        val bad = encoded(null, table(listOf(row(1), row(1))))
        assertFailsWith<DatabaseException> { decoded(bad) }
        assertFailsWith<DatabaseException> { encoded(null, table(listOf(row(1, "\ud800")))) }
        val one = encoded(null, table(listOf(row(1))))
        val payload = one.copyOfRange(24, one.size - 12)
        val duplicate = ByteBuffer.allocate(one.size + payload.size)
        duplicate.put(one, 0, 24).put(payload).put(payload)
        duplicate.putInt(0).putInt(0x434d4954).putInt(duplicate.capacity())
        duplicate.putInt(4, duplicate.capacity()).putInt(16, duplicate.capacity() - 32).putInt(20, 2)
        val bytes = duplicate.array()
        repairChecksum(bytes)
        assertFailsWith<DatabaseException> { decoded(bytes) }
    }

    private fun spliceOffset(bytes: ByteArray): Int {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(24)
        fun skipName() { val length = buffer.int; buffer.position(buffer.position() + length) }
        skipName()
        repeat(buffer.int) { skipName(); buffer.get(); buffer.get() }
        return buffer.position()
    }

    private fun repairChecksum(bytes: ByteArray) {
        val checksum = CRC32C().also { it.update(bytes, 0, bytes.size - 12) }.value.toInt()
        ByteBuffer.wrap(bytes).putInt(bytes.size - 12, checksum)
    }
}
