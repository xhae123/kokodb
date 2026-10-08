package kokodb.storage

import kokodb.DatabaseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32C

internal data class Snapshot(val identity: UUID, val sequence: Long, val catalog: Catalog)
internal data class WalHeader(val identity: UUID, val base: Long)

internal object StorageCodec {
    const val SNAPSHOT_HEADER = 52
    const val WAL_HEADER = 40
    const val FRAME_PREFIX = 20
    const val FRAME_OVERHEAD = 32
    const val MAX_SNAPSHOT = 256 * 1024 * 1024
    const val MAX_FRAME = 64 * 1024 * 1024
    private val snapshotMagic = "KOKODB01".toByteArray(StandardCharsets.US_ASCII)
    private val walMagic = "KOKOWAL1".toByteArray(StandardCharsets.US_ASCII)
    private val frameMagic = "KTX1".toByteArray(StandardCharsets.US_ASCII)
    private val trailer = "CMIT".toByteArray(StandardCharsets.US_ASCII)
    private val identifier = Regex("[a-z_][a-z0-9_]*")

    fun encodeSnapshot(snapshot: Snapshot): ByteArray {
        requireSequence(snapshot.sequence)
        val payload = encodeTables(snapshot.catalog.entries(), MAX_SNAPSHOT - SNAPSHOT_HEADER)
        val buffer = ByteBuffer.allocate(SNAPSHOT_HEADER + payload.size)
        buffer.put(snapshotMagic)
        version(buffer)
        identity(buffer, snapshot.identity)
        buffer.putLong(snapshot.sequence).putLong(payload.size.toLong()).putInt(crc(payload))
        buffer.putInt(crc(buffer.array(), 0, SNAPSHOT_HEADER - 4)).put(payload)
        return buffer.array()
    }

    fun decodeSnapshot(bytes: ByteArray): Snapshot = decode {
        check(bytes.size in SNAPSHOT_HEADER..MAX_SNAPSHOT, "snapshot size")
        val buffer = ByteBuffer.wrap(bytes)
        magic(buffer, snapshotMagic)
        readVersion(buffer)
        val identity = readIdentity(buffer)
        val sequence = buffer.long.also(::requireSequence)
        val length = buffer.long
        val payloadCrc = buffer.int
        check(buffer.int == crc(bytes, 0, SNAPSHOT_HEADER - 4), "snapshot header checksum")
        check(length == (bytes.size - SNAPSHOT_HEADER).toLong(), "snapshot payload length")
        check(payloadCrc == crc(bytes, SNAPSHOT_HEADER, length.toInt()), "snapshot payload checksum")
        Snapshot(identity, sequence, decodeTables(bytes, SNAPSHOT_HEADER, length.toInt()))
    }

    fun encodeWalHeader(header: WalHeader): ByteArray {
        requireSequence(header.base)
        val buffer = ByteBuffer.allocate(WAL_HEADER)
        buffer.put(walMagic)
        version(buffer)
        identity(buffer, header.identity)
        buffer.putLong(header.base).putInt(crc(buffer.array(), 0, WAL_HEADER - 4))
        return buffer.array()
    }

    fun decodeWalHeader(bytes: ByteArray): WalHeader = decode {
        check(bytes.size == WAL_HEADER, "WAL header size")
        val buffer = ByteBuffer.wrap(bytes)
        magic(buffer, walMagic)
        readVersion(buffer)
        val identity = readIdentity(buffer)
        val base = buffer.long.also(::requireSequence)
        check(buffer.int == crc(bytes, 0, WAL_HEADER - 4), "WAL header checksum")
        WalHeader(identity, base)
    }

    fun encodeFrame(sequence: Long, tables: Map<String, Table>): ByteArray {
        check(sequence > 0, "frame sequence")
        val payload = encodeTables(tables, MAX_FRAME - FRAME_OVERHEAD)
        val length = payload.size + FRAME_OVERHEAD
        val buffer = ByteBuffer.allocate(length)
        buffer.put(frameMagic).putInt(length).putLong(sequence).putInt(payload.size).put(payload)
        buffer.putInt(crc(buffer.array(), 0, FRAME_PREFIX + payload.size)).put(trailer).putInt(length)
        return buffer.array()
    }

    /** Validates all complete prefix fields before trusting a length or accepting a torn terminal prefix. */
    fun frameLength(prefix: ByteArray, sequence: Long): Int? = decode {
        check(prefix.size <= FRAME_PREFIX, "frame prefix size")
        check(prefix.take(minOf(4, prefix.size)).toByteArray().contentEquals(frameMagic.copyOf(minOf(4, prefix.size))), "frame magic")
        if (prefix.size < 8) return@decode null
        val buffer = ByteBuffer.wrap(prefix)
        buffer.position(4)
        val length = buffer.int
        check(length in (FRAME_OVERHEAD + 4)..MAX_FRAME, "frame length")
        if (prefix.size >= 16) check(buffer.long == sequence && sequence > 0, "frame sequence gap")
        if (prefix.size < FRAME_PREFIX) return@decode null
        check(buffer.int == length - FRAME_OVERHEAD, "frame payload length")
        length
    }

    fun decodeFrame(bytes: ByteArray, sequence: Long): Catalog = decode {
        check(bytes.size >= FRAME_PREFIX, "frame size")
        val length = frameLength(bytes.copyOf(FRAME_PREFIX), sequence)
        check(length == bytes.size, "frame total length")
        val payloadLength = bytes.size - FRAME_OVERHEAD
        val suffix = ByteBuffer.wrap(bytes, FRAME_PREFIX + payloadLength, 12)
        check(suffix.int == crc(bytes, 0, FRAME_PREFIX + payloadLength), "frame checksum")
        magic(suffix, trailer)
        check(suffix.int == length, "frame repeated length")
        decodeTables(bytes, FRAME_PREFIX, payloadLength)
    }

    private fun encodeTables(tables: Map<String, Table>, limit: Int): ByteArray {
        val bytes = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                check(count < limit, "payload exceeds size limit")
                super.write(value)
            }
            override fun write(values: ByteArray, offset: Int, length: Int) {
                check(length <= limit - count, "payload exceeds size limit")
                super.write(values, offset, length)
            }
        }
        val output = DataOutputStream(bytes)
        output.writeInt(tables.size)
        for ((name, table) in tables.toSortedMap()) {
            writeName(output, name)
            check(table.columns.isNotEmpty(), "empty schema")
            output.writeInt(table.columns.size)
            for (column in table.columns) {
                writeName(output, column.name)
                output.writeByte(if (column.type == DataType.INT) 1 else 2)
                output.writeByte(if (column.primaryKey) 1 else 0)
            }
            output.writeInt(table.rows.size)
            for (row in table.rows) {
                check(row.size == table.columns.size, "row width")
                row.forEachIndexed { index, value ->
                    check(value.type == table.columns[index].type, "row type")
                    when (value) {
                        is Value.IntValue -> output.writeInt(value.value)
                        is Value.TextValue -> writeText(output, value.value, limit)
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun decodeTables(bytes: ByteArray, offset: Int, length: Int): Catalog {
        val input = DataInputStream(ByteArrayInputStream(bytes, offset, length))
        val catalog = Catalog()
        repeat(count(input, 13)) {
            val name = readName(input)
            val columns = List(count(input, 7, nonempty = true)) {
                val column = readName(input)
                val type = when (input.readUnsignedByte()) {
                    1 -> DataType.INT
                    2 -> DataType.TEXT
                    else -> invalid("column type tag")
                }
                val key = input.readUnsignedByte()
                check(key in 0..1, "primary key flag")
                Column(column, type, key == 1)
            }
            catalog.create(name, columns)
            val rows = List(count(input, 4 * columns.size)) {
                columns.map { column ->
                    when (column.type) {
                        DataType.INT -> Value.IntValue(input.readInt())
                        DataType.TEXT -> Value.TextValue(readText(input))
                    }
                }
            }
            val key = columns.indexOfFirst { it.primaryKey }
            if (key >= 0) check(rows.map { it[key] }.toSet().size == rows.size, "duplicate primary key")
            catalog.table(name).publishRows(rows)
        }
        check(input.available() == 0, "trailing payload bytes")
        return catalog
    }

    private fun count(input: DataInputStream, minimumBytes: Int, nonempty: Boolean = false): Int {
        val count = input.readInt()
        check(count >= (if (nonempty) 1 else 0) && count <= input.available() / minimumBytes, "field count")
        return count
    }

    private fun writeName(output: DataOutputStream, name: String) {
        check(identifier.matches(name), "identifier")
        val bytes = name.toByteArray(StandardCharsets.US_ASCII)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readName(input: DataInputStream): String = readText(input).also { check(identifier.matches(it), "identifier") }

    private fun writeText(output: DataOutputStream, text: String, limit: Int) {
        check(text.length <= limit, "text size")
        val bytes = try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        } catch (error: CharacterCodingException) {
            throw DatabaseException("Invalid storage: malformed UTF-16 text").also { it.initCause(error) }
        }
        output.writeInt(bytes.remaining())
        output.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining())
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readInt()
        check(length >= 0 && length <= input.available(), "text length")
        val bytes = input.readNBytes(length)
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun version(buffer: ByteBuffer) { buffer.putShort(1).putShort(0) }
    private fun readVersion(buffer: ByteBuffer) { check(buffer.short.toInt() == 1 && buffer.short.toInt() == 0, "unsupported version") }
    private fun identity(buffer: ByteBuffer, identity: UUID) { buffer.putLong(identity.mostSignificantBits).putLong(identity.leastSignificantBits) }
    private fun readIdentity(buffer: ByteBuffer): UUID = UUID(buffer.long, buffer.long)
    private fun requireSequence(sequence: Long) { check(sequence >= 0, "sequence overflow") }
    private fun magic(buffer: ByteBuffer, expected: ByteArray) {
        val actual = ByteArray(expected.size)
        buffer.get(actual)
        check(actual.contentEquals(expected), "magic")
    }
    private fun crc(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int =
        CRC32C().also { it.update(bytes, offset, length) }.value.toInt()
    private fun check(valid: Boolean, reason: String) { if (!valid) invalid(reason) }
    private fun invalid(reason: String): Nothing = throw DatabaseException("Invalid storage: $reason")
    private inline fun <T> decode(operation: () -> T): T = try {
        operation()
    } catch (error: DatabaseException) {
        throw error
    } catch (error: Exception) {
        throw DatabaseException("Invalid storage: truncated or malformed encoding").also { it.initCause(error) }
    }
}
