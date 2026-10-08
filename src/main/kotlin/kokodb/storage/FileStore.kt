package kokodb.storage

import kokodb.CommitOutcomeUnknownException
import kokodb.DatabaseException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class FileStore private constructor(
    val path: Path,
    private val owner: Owner,
    snapshot: Snapshot,
    private val io: StoreIO,
) : AutoCloseable {
    private var recovered: Catalog? = snapshot.catalog
    private val identity = snapshot.identity
    private var sequence = snapshot.sequence
    private var recoveryRequired = false
    private var closed = false
    private lateinit var wal: FileChannel
    private val walPath = path.resolveSibling(path.fileName.toString() + ".wal")

    fun takeCatalog(): Catalog = checkNotNull(recovered).also { recovered = null }

    fun requireHealthy() {
        if (closed) throw DatabaseException("Persistent database is closed")
        if (recoveryRequired) throw DatabaseException("Persistent database is recovery-required; close and reopen it")
    }

    fun commit(before: Catalog, after: Catalog) {
        requireHealthy()
        val old = before.entries()
        val dirty = after.entries().filter { (name, table) ->
            val previous = old[name]
            previous == null || previous.columns != table.columns || previous.rows != table.rows
        }
        if (dirty.isEmpty()) return
        if (sequence == Long.MAX_VALUE) throw DatabaseException("Commit sequence exhausted")
        val frame = StorageCodec.encodeFrame(sequence + 1, dirty)
        var started = false
        try {
            io.event(StoreEvent.BEFORE_APPEND)
            started = true
            io.append(wal, frame)
            io.event(StoreEvent.AFTER_APPEND)
            io.force(wal)
            io.event(StoreEvent.AFTER_WAL_FORCE)
            sequence++
        } catch (error: Throwable) {
            if (started) {
                recoveryRequired = true
                throw CommitOutcomeUnknownException(error)
            }
            throw error
        }
    }

    fun checkpoint(catalog: Catalog) {
        requireHealthy()
        val bytes = StorageCodec.encodeSnapshot(Snapshot(identity, sequence, catalog))
        try {
            writeReplacement(path, bytes, snapshot = true)
            writeReplacement(walPath, StorageCodec.encodeWalHeader(WalHeader(identity, sequence)), snapshot = false)
            wal.close()
            wal = FileChannel.open(walPath, READ, WRITE)
            wal.position(wal.size())
        } catch (error: Throwable) {
            recoveryRequired = true
            throw DatabaseException("Checkpoint failed; close and reopen the database").also { it.initCause(error) }
        }
    }

    private fun recover() {
        val catalog = checkNotNull(recovered)
        wal = FileChannel.open(walPath, READ, WRITE)
        val header = StorageCodec.decodeWalHeader(read(wal, StorageCodec.WAL_HEADER))
        if (header.identity != identity || header.base > sequence) throw DatabaseException("Snapshot and WAL identity/history mismatch")
        val snapshotSequence = sequence
        var previous = header.base
        var validEnd = wal.position()
        while (wal.position() < wal.size()) {
            if (previous == Long.MAX_VALUE) throw DatabaseException("WAL sequence overflow")
            val start = wal.position()
            val prefix = read(wal, minOf(StorageCodec.FRAME_PREFIX.toLong(), wal.size() - start).toInt())
            val length = StorageCodec.frameLength(prefix, previous + 1)
            if (length == null || wal.size() - start < length) break
            val frame = ByteArray(length)
            prefix.copyInto(frame)
            read(wal, length - prefix.size).copyInto(frame, prefix.size)
            val changes = StorageCodec.decodeFrame(frame, previous + 1)
            previous++
            if (previous > snapshotSequence) catalog.restore(changes.entries())
            sequence = maxOf(sequence, previous)
            validEnd = wal.position()
        }
        if (validEnd < wal.size()) {
            wal.truncate(validEnd)
            io.force(wal)
        }
        wal.position(validEnd)
        if (header.base < snapshotSequence) checkpoint(catalog)
        var removed = false
        Files.newDirectoryStream(path.parent, temporaryPrefix(path) + "*.tmp").use { files ->
            for (file in files) removed = Files.deleteIfExists(file) || removed
        }
        if (removed) io.syncDirectory(path.parent)
    }

    private fun writeReplacement(target: Path, bytes: ByteArray, snapshot: Boolean) {
        val temporary = Files.createTempFile(path.parent, temporaryPrefix(path), ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use {
                io.append(it, bytes)
                io.force(it)
                io.event(if (snapshot) StoreEvent.SNAPSHOT_FORCED else StoreEvent.WAL_RESET_FORCED)
            }
            io.replace(temporary, target)
            io.event(if (snapshot) StoreEvent.SNAPSHOT_REPLACED else StoreEvent.WAL_RESET_REPLACED)
            io.syncDirectory(path.parent)
            io.event(if (snapshot) StoreEvent.SNAPSHOT_DIRECTORY_SYNCED else StoreEvent.WAL_RESET_DIRECTORY_SYNCED)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (::wal.isInitialized) wal.close()
        } finally {
            owner.close()
        }
    }

    private class Owner(val path: Path, val channel: FileChannel, val lock: FileLock, val token: Any) : AutoCloseable {
        override fun close() {
            try { channel.close() } finally { owners.remove(path, token) }
        }
    }

    companion object {
        private val owners = ConcurrentHashMap<Path, Any>()

        fun open(input: Path, io: StoreIO = StoreIO()): FileStore = try {
            openOwned(input, io)
        } catch (error: DatabaseException) {
            throw error
        } catch (error: Exception) {
            throw DatabaseException("Unable to open persistent database").also { it.initCause(error) }
        }

        private fun openOwned(input: Path, io: StoreIO): FileStore {
            val absolute = input.toAbsolutePath().normalize()
            if (absolute.fileName == null) throw DatabaseException("Database path must name a file")
            val path = absolute.parent.toRealPath().resolve(absolute.fileName)
            val wal = path.resolveSibling(path.fileName.toString() + ".wal")
            val lockPath = path.resolveSibling(path.fileName.toString() + ".lock")
            if (listOf(path, wal, lockPath).any(Files::isSymbolicLink)) throw DatabaseException("Database files must not be symbolic links")
            val os = System.getProperty("os.name")
            val filesystem = Files.getFileStore(path.parent).type().lowercase(Locale.ROOT)
            if ((os != "Linux" && os != "Mac OS X") || filesystem !in setOf("apfs", "ext4", "overlay")) {
                throw DatabaseException("Persistent storage is unsupported on $os/$filesystem")
            }
            val token = Any()
            if (owners.putIfAbsent(path, token) != null) throw DatabaseException("Database is already owned in this process")
            var channel: FileChannel? = null
            var owner: Owner? = null
            var store: FileStore? = null
            try {
                channel = FileChannel.open(lockPath, CREATE, WRITE)
                val lock = channel.tryLock() ?: throw DatabaseException("Database is locked by another process")
                owner = Owner(path, channel, lock, token)
                io.force(channel)
                io.syncDirectory(path.parent)
                val snapshot = initializeOrRead(path, wal, io)
                store = FileStore(path, owner, snapshot, io)
                store.recover()
                return store
            } catch (error: Throwable) {
                runCatching { store?.close() ?: owner?.close() ?: channel?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                owners.remove(path, token)
                if (error is DatabaseException) throw error
                throw DatabaseException("Unable to open persistent database").also { it.initCause(error) }
            }
        }

        private fun initializeOrRead(path: Path, wal: Path, io: StoreIO): Snapshot {
            if (Files.exists(path)) {
                if (!Files.exists(wal)) throw DatabaseException("Required WAL is missing")
                val length = Files.size(path)
                if (length !in StorageCodec.SNAPSHOT_HEADER.toLong()..StorageCodec.MAX_SNAPSHOT.toLong()) {
                    throw DatabaseException("Invalid snapshot file size")
                }
                return StorageCodec.decodeSnapshot(Files.readAllBytes(path))
            }
            val initial = if (Files.exists(wal)) {
                if (Files.size(wal) != StorageCodec.WAL_HEADER.toLong()) throw DatabaseException("Required snapshot is missing")
                val header = StorageCodec.decodeWalHeader(Files.readAllBytes(wal))
                if (header.base != 0L) throw DatabaseException("Required snapshot history is missing")
                Snapshot(header.identity, 0, Catalog())
            } else Snapshot(UUID.randomUUID(), 0, Catalog())
            // WAL-first bootstrap distinguishes interrupted initialization from loss of a live WAL.
            if (!Files.exists(wal)) install(path, wal, StorageCodec.encodeWalHeader(WalHeader(initial.identity, 0)), io)
            install(path, path, StorageCodec.encodeSnapshot(initial), io)
            return initial
        }

        private fun install(database: Path, target: Path, bytes: ByteArray, io: StoreIO) {
            val temporary = Files.createTempFile(database.parent, temporaryPrefix(database), ".tmp")
            try {
                FileChannel.open(temporary, WRITE).use { io.append(it, bytes); io.force(it) }
                io.replace(temporary, target)
                io.syncDirectory(database.parent)
            } finally { Files.deleteIfExists(temporary) }
        }

        private fun temporaryPrefix(path: Path): String = ".kokodb-" + MessageDigest.getInstance("SHA-256")
            .digest(path.toString().toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) } + "-"

        private fun read(channel: FileChannel, length: Int): ByteArray {
            val bytes = ByteArray(length)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw DatabaseException("Truncated storage file")
            }
            return bytes
        }
    }
}
