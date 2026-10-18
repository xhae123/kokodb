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
internal data class WalHeader(val identity: UUID, val base: Long, val version: Int = 2)

internal object StorageCodec {
    const val SNAPSHOT_HEADER = 52
    const val WAL_HEADER = 40
    const val FRAME_PREFIX = 20
    const val FRAME_OVERHEAD = 32
    const val MAX_SNAPSHOT = 256 * 1024 * 1024
    const val MAX_FRAME = 64 * 1024 * 1024
    private val snapshotMagic = "KOKODB01".toByteArray(StandardCharsets.US_ASCII)
    private val walMagic = "KOKOWAL1".toByteArray(StandardCharsets.US_ASCII)
    private fun frameMagic(version: Int) = "KTX$version".toByteArray(StandardCharsets.US_ASCII)
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
        version(buffer, header.version)
        identity(buffer, header.identity)
        buffer.putLong(header.base).putInt(crc(buffer.array(), 0, WAL_HEADER - 4))
        return buffer.array()
    }

    fun decodeWalHeader(bytes: ByteArray): WalHeader = decode {
        check(bytes.size == WAL_HEADER, "WAL header size")
        val buffer = ByteBuffer.wrap(bytes)
        magic(buffer, walMagic)
        val version = readVersion(buffer, wal = true)
        val identity = readIdentity(buffer)
        val base = buffer.long.also(::requireSequence)
        check(buffer.int == crc(bytes, 0, WAL_HEADER - 4), "WAL header checksum")
        WalHeader(identity, base, version)
    }

    fun encodeFrame(sequence: Long, tables: Map<String, Table>): ByteArray {
        return encodePayloadFrame(sequence, encodeTables(tables, MAX_FRAME - FRAME_OVERHEAD), 1)
    }

    fun encodeSpliceFrame(sequence: Long, changes: Map<String, TableSplice>): ByteArray {
        val payload = encodePayload(MAX_FRAME - FRAME_OVERHEAD) { output ->
            output.writeInt(changes.size)
            for ((name, splice) in changes.toSortedMap()) {
                writeName(output, name)
                writeColumns(output, splice.columns)
                validateSplice(splice)
                output.writeInt(splice.baseRows)
                output.writeInt(splice.prefix)
                output.writeInt(splice.removed)
                writeRows(output, splice.columns, splice.inserted, MAX_FRAME - FRAME_OVERHEAD)
            }
        }
        return encodePayloadFrame(sequence, payload, 2)
    }

    private fun encodePayloadFrame(sequence: Long, payload: ByteArray, version: Int): ByteArray {
        check(sequence > 0, "frame sequence")
        val length = payload.size + FRAME_OVERHEAD
        val buffer = ByteBuffer.allocate(length)
        buffer.put(frameMagic(version)).putInt(length).putLong(sequence).putInt(payload.size).put(payload)
        buffer.putInt(crc(buffer.array(), 0, FRAME_PREFIX + payload.size)).put(trailer).putInt(length)
        return buffer.array()
    }

    /** Validates all complete prefix fields before trusting a length or accepting a torn terminal prefix. */
    fun frameLength(prefix: ByteArray, sequence: Long, version: Int = 1): Int? = decode {
        check(version in 1..2, "unsupported frame version")
        val frameMagic = frameMagic(version)
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
        val payloadLength = validateFrame(bytes, sequence, 1)
        decodeTables(bytes, FRAME_PREFIX, payloadLength)
    }

    fun decodeSpliceFrame(bytes: ByteArray, sequence: Long): Map<String, TableSplice> = decode {
        val length = validateFrame(bytes, sequence, 2)
        val input = DataInputStream(ByteArrayInputStream(bytes, FRAME_PREFIX, length))
        val changes = linkedMapOf<String, TableSplice>()
        repeat(count(input, 25)) {
            val name = readName(input)
            val columns = readColumns(input)
            val base = input.readInt()
            val prefix = input.readInt()
            val removed = input.readInt()
            val rows = readRows(input, columns)
            val splice = TableSplice(columns, base, prefix, removed, rows)
            validateSplice(splice)
            check(changes.put(name, splice) == null, "duplicate changed table")
        }
        check(input.available() == 0, "trailing payload bytes")
        changes
    }

    private fun validateSplice(splice: TableSplice) {
        check(splice.baseRows >= -1 && splice.prefix >= 0 && splice.removed >= 0, "splice bounds")
        val base = maxOf(0, splice.baseRows)
        check(splice.prefix <= base && splice.removed <= base - splice.prefix, "splice bounds")
        check(base.toLong() - splice.removed + splice.inserted.size <= Int.MAX_VALUE, "splice result size")
    }

    private fun validateFrame(bytes: ByteArray, sequence: Long, version: Int): Int {
        check(bytes.size >= FRAME_PREFIX, "frame size")
        val length = frameLength(bytes.copyOf(FRAME_PREFIX), sequence, version)
        check(length == bytes.size, "frame total length")
        val payloadLength = bytes.size - FRAME_OVERHEAD
        val suffix = ByteBuffer.wrap(bytes, FRAME_PREFIX + payloadLength, 12)
        check(suffix.int == crc(bytes, 0, FRAME_PREFIX + payloadLength), "frame checksum")
        magic(suffix, trailer)
        check(suffix.int == length, "frame repeated length")
        return payloadLength
    }

    private fun encodePayload(limit: Int, write: (DataOutputStream) -> Unit): ByteArray {
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
        write(output)
        return bytes.toByteArray()
    }

    private fun encodeTables(tables: Map<String, Table>, limit: Int): ByteArray = encodePayload(limit) { output ->
        output.writeInt(tables.size)
        for ((name, table) in tables.toSortedMap()) {
            writeName(output, name)
            writeColumns(output, table.columns)
            writeRows(output, table.columns, table.rows, limit)
        }
    }

    private fun writeColumns(output: DataOutputStream, columns: List<Column>) {
        check(columns.isNotEmpty(), "empty schema")
        output.writeInt(columns.size)
        for (column in columns) {
            writeName(output, column.name)
            output.writeByte(if (column.type == DataType.INT) 1 else 2)
            output.writeByte(if (column.primaryKey) 1 else 0)
        }
    }

    private fun writeRows(output: DataOutputStream, columns: List<Column>, rows: List<List<Value>>, limit: Int) {
        output.writeInt(rows.size)
        for (row in rows) {
            check(row.size == columns.size, "row width")
            row.forEachIndexed { index, value ->
                check(value.type == columns[index].type, "row type")
                when (value) {
                    is Value.IntValue -> output.writeInt(value.value)
                    is Value.TextValue -> writeText(output, value.value, limit)
                }
            }
        }
    }

    private fun readColumns(input: DataInputStream): List<Column> {
        val columns = List(count(input, 7, nonempty = true)) {
            val name = readName(input)
            val type = when (input.readUnsignedByte()) {
                1 -> DataType.INT
                2 -> DataType.TEXT
                else -> invalid("column type tag")
            }
            val key = input.readUnsignedByte()
            check(key in 0..1, "primary key flag")
            Column(name, type, key == 1)
        }
        check(columns.map { it.name }.toSet().size == columns.size, "duplicate column")
        check(columns.count { it.primaryKey } <= 1, "multiple primary keys")
        return columns
    }

    private fun readRows(input: DataInputStream, columns: List<Column>): List<List<Value>> {
        val rows = List(count(input, 4 * columns.size)) {
            columns.map { column ->
                when (column.type) {
                    DataType.INT -> Value.IntValue(input.readInt())
                    DataType.TEXT -> Value.TextValue(readText(input))
                }
            }
        }
        val key = columns.indexOfFirst { it.primaryKey }
        if (key >= 0) {
            val seen = HashSet<Value>()
            for (row in rows) check(seen.add(row[key]), "duplicate primary key")
        }
        return rows
    }

    private fun decodeTables(bytes: ByteArray, offset: Int, length: Int): Catalog {
        val input = DataInputStream(ByteArrayInputStream(bytes, offset, length))
        val catalog = Catalog()
        repeat(count(input, 13)) {
            val name = readName(input)
            val columns = readColumns(input)
            catalog.create(name, columns)
            catalog.table(name).publishRows(readRows(input, columns))
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

    private fun version(buffer: ByteBuffer, major: Int = 1) { buffer.putShort(major.toShort()).putShort(0) }
    private fun readVersion(buffer: ByteBuffer, wal: Boolean = false): Int {
        val major = buffer.short.toInt()
        check(major in 1..(if (wal) 2 else 1) && buffer.short.toInt() == 0, "unsupported version")
        return major
    }
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
