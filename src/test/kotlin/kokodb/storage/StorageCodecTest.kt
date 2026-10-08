package kokodb.storage

import kokodb.DatabaseException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32C
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StorageCodecTest {
    private val identity = UUID(0, 1)

    private fun catalog(): Catalog = Catalog().also {
        it.create("users", listOf(Column("id", DataType.INT, true), Column("name", DataType.TEXT)))
        it.table("users").publishRows(listOf(
            listOf(Value.IntValue(Int.MIN_VALUE), Value.TextValue("Koko ' 한글 😀 \u0000")),
            listOf(Value.IntValue(Int.MAX_VALUE), Value.TextValue("")),
        ))
        it.create("empty", listOf(Column("value", DataType.TEXT)))
    }

    private fun content(catalog: Catalog) = catalog.entries().mapValues { (_, table) -> table.columns to table.rows }

    @Test
    fun `snapshot round trips relational values schemas identity and sequence`() {
        val original = Snapshot(identity, 7, catalog())
        val encoded = StorageCodec.encodeSnapshot(original)
        val decoded = StorageCodec.decodeSnapshot(encoded)
        assertEquals(identity, decoded.identity)
        assertEquals(7L, decoded.sequence)
        assertEquals(content(original.catalog), content(decoded.catalog))
        assertContentEquals(encoded, StorageCodec.encodeSnapshot(decoded))
    }

    @Test
    fun `empty snapshot has stable version one golden encoding`() {
        val bytes = StorageCodec.encodeSnapshot(Snapshot(identity, 7, Catalog()))
        assertEquals("4b4f4b4f4442303100010000000000000000000000000000000000010000000000000007000000000000000448674bc7464f9d7800000000", bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `WAL header and atomic multi table frame round trip`() {
        val header = WalHeader(identity, 7)
        assertEquals(header, StorageCodec.decodeWalHeader(StorageCodec.encodeWalHeader(header)))
        val original = catalog()
        val frame = StorageCodec.encodeFrame(8, original.entries())
        assertEquals(frame.size, StorageCodec.frameLength(frame.copyOf(StorageCodec.FRAME_PREFIX), 8))
        assertEquals(content(original), content(StorageCodec.decodeFrame(frame, 8)))
        assertFailsWith<DatabaseException> { StorageCodec.decodeFrame(frame, 9) }
        assertFailsWith<DatabaseException> { StorageCodec.decodeWalHeader(byteArrayOf()) }
    }

    @Test
    fun `every snapshot truncation and single byte corruption is rejected`() {
        val snapshot = StorageCodec.encodeSnapshot(Snapshot(identity, 7, catalog()))
        for (length in 0 until snapshot.size) {
            assertFailsWith<DatabaseException>("length $length") { StorageCodec.decodeSnapshot(snapshot.copyOf(length)) }
        }
        for (index in snapshot.indices) {
            val corrupt = snapshot.clone()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertFailsWith<DatabaseException>("offset $index") { StorageCodec.decodeSnapshot(corrupt) }
        }
        assertFailsWith<DatabaseException> { StorageCodec.decodeSnapshot(snapshot + byteArrayOf(0)) }
    }

    @Test
    fun `all complete frame corruption and truncation is rejected`() {
        val frame = StorageCodec.encodeFrame(8, catalog().entries())
        for (index in frame.indices) {
            val corrupt = frame.clone()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertFailsWith<DatabaseException>("offset $index") { StorageCodec.decodeFrame(corrupt, 8) }
        }
        for (length in 0 until frame.size) {
            assertFailsWith<DatabaseException> { StorageCodec.decodeFrame(frame.copyOf(length), 8) }
        }
        for (length in 0 until StorageCodec.FRAME_PREFIX) {
            assertNull(StorageCodec.frameLength(frame.copyOf(length), 8))
        }
    }

    @Test
    fun `untrusted lengths versions and sequences are rejected before allocation`() {
        val frame = StorageCodec.encodeFrame(8, catalog().entries())
        for (length in listOf(-1, 0, StorageCodec.MAX_FRAME + 1, Int.MAX_VALUE)) {
            val prefix = frame.copyOf(StorageCodec.FRAME_PREFIX)
            ByteBuffer.wrap(prefix).putInt(4, length)
            assertFailsWith<DatabaseException> { StorageCodec.frameLength(prefix, 8) }
        }
        val header = StorageCodec.encodeWalHeader(WalHeader(identity, 7))
        for (index in header.indices) {
            val corrupt = header.clone()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertFailsWith<DatabaseException> { StorageCodec.decodeWalHeader(corrupt) }
        }
        assertFailsWith<DatabaseException> { StorageCodec.encodeSnapshot(Snapshot(identity, -1, catalog())) }
        assertFailsWith<DatabaseException> { StorageCodec.encodeFrame(0, catalog().entries()) }
    }

    @Test
    fun `valid checksums do not bypass semantic schema key or UTF8 validation`() {
        val duplicate = catalog()
        val table = duplicate.table("users")
        table.publishRows(listOf(table.rows.first(), table.rows.first()))
        val bytes = StorageCodec.encodeSnapshot(Snapshot(identity, 7, duplicate))
        assertFailsWith<DatabaseException> { StorageCodec.decodeSnapshot(bytes) }
        val malformed = catalog()
        malformed.table("users").publishRows(listOf(listOf(Value.IntValue(1), Value.TextValue("\ud800"))))
        assertFailsWith<DatabaseException> { StorageCodec.encodeSnapshot(Snapshot(identity, 7, malformed)) }
        val badCount = StorageCodec.encodeSnapshot(Snapshot(identity, 7, catalog()))
        ByteBuffer.wrap(badCount).putInt(StorageCodec.SNAPSHOT_HEADER, Int.MAX_VALUE)
        repairSnapshotChecksums(badCount)
        assertFailsWith<DatabaseException> { StorageCodec.decodeSnapshot(badCount) }
        val badUtf8 = StorageCodec.encodeSnapshot(Snapshot(identity, 7, catalog()))
        val offset = badUtf8.indices.first { it > StorageCodec.SNAPSHOT_HEADER && badUtf8[it] == 'K'.code.toByte() }
        badUtf8[offset] = 0xff.toByte()
        repairSnapshotChecksums(badUtf8)
        assertFailsWith<DatabaseException> { StorageCodec.decodeSnapshot(badUtf8) }
    }

    @Test
    fun `random malformed snapshots fail with database errors`() {
        val random = Random(731)
        repeat(1000) {
            val bytes = random.nextBytes(random.nextInt(256))
            assertFailsWith<DatabaseException> { StorageCodec.decodeSnapshot(bytes) }
        }
    }

    private fun repairSnapshotChecksums(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        val payload = CRC32C().also { it.update(bytes, StorageCodec.SNAPSHOT_HEADER, bytes.size - StorageCodec.SNAPSHOT_HEADER) }
        buffer.putInt(44, payload.value.toInt())
        val header = CRC32C().also { it.update(bytes, 0, StorageCodec.SNAPSHOT_HEADER - 4) }
        buffer.putInt(48, header.value.toInt())
    }
}
