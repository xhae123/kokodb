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
    private val maxSnapshotBytes: Int,
) : AutoCloseable {
    private var recovered: Catalog? = snapshot.catalog
    private val identity = snapshot.identity
    private var sequence = snapshot.sequence
    private var recoveryRequired = false
    private var snapshotBytes = 0L
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
        val changes = dirty.mapValues { (name, table) -> TableSplice.between(old[name], table) }
        val nextSnapshotBytes = SnapshotSize.after(snapshotBytes, old, changes)
        // Older writers could exceed this bound; allow strictly shrinking commits to repair those stores.
        if (nextSnapshotBytes > maxSnapshotBytes && nextSnapshotBytes >= snapshotBytes) {
            throw DatabaseException("Commit exceeds snapshot size limit ($maxSnapshotBytes bytes)")
        }
        val frame = StorageCodec.encodeSpliceFrame(sequence + 1, changes)
        var started = false
        try {
            io.event(StoreEvent.BEFORE_APPEND)
            started = true
            io.append(wal, frame)
            io.event(StoreEvent.AFTER_APPEND)
            io.force(wal)
            io.event(StoreEvent.AFTER_WAL_FORCE)
            sequence++
            snapshotBytes = nextSnapshotBytes
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
        if (snapshotBytes > maxSnapshotBytes) throw DatabaseException("Snapshot exceeds size limit ($maxSnapshotBytes bytes)")
        val bytes = StorageCodec.encodeSnapshot(Snapshot(identity, sequence, catalog))
        try {
            writeReplacement(path, bytes, snapshot = true)
            writeReplacement(walPath, StorageCodec.encodeWalHeader(WalHeader(identity, sequence)), snapshot = false)
            io.close(wal)
            wal = io.open(walPath, READ, WRITE)
            wal.position(wal.size())
        } catch (error: Throwable) {
            recoveryRequired = true
            throw DatabaseException("Checkpoint failed; close and reopen the database").also { it.initCause(error) }
        }
    }

    private fun recover() {
        val replay = RecoveryCatalog(checkNotNull(recovered))
        wal = io.open(walPath, READ, WRITE)
        val header = StorageCodec.decodeWalHeader(read(wal, StorageCodec.WAL_HEADER, io))
        if (header.identity != identity || header.base > sequence) throw DatabaseException("Snapshot and WAL identity/history mismatch")
        val snapshotSequence = sequence
        var previous = header.base
        var validEnd = wal.position()
        while (wal.position() < wal.size()) {
            if (previous == Long.MAX_VALUE) throw DatabaseException("WAL sequence overflow")
            val start = wal.position()
            val prefix = read(wal, minOf(StorageCodec.FRAME_PREFIX.toLong(), wal.size() - start).toInt(), io)
            val length = StorageCodec.frameLength(prefix, previous + 1, header.version)
            if (length == null || wal.size() - start < length) break
            val frame = ByteArray(length)
            prefix.copyInto(frame)
            read(wal, length - prefix.size, io).copyInto(frame, prefix.size)
            if (header.version == 1) {
                val changes = StorageCodec.decodeFrame(frame, previous + 1)
                if (previous + 1 > snapshotSequence) replay.applyTables(changes.entries())
            } else {
                val changes = StorageCodec.decodeSpliceFrame(frame, previous + 1)
                if (previous + 1 > snapshotSequence) replay.applySplices(changes)
            }
            previous++
            sequence = maxOf(sequence, previous)
            validEnd = wal.position()
        }
        val catalog = replay.finish()
        recovered = catalog
        if (validEnd < wal.size()) {
            io.truncate(wal, validEnd)
            io.force(wal)
        }
        wal.position(validEnd)
        snapshotBytes = SnapshotSize.of(catalog)
        // Upgrade only after validating the entire legacy WAL; checkpoint ordering also protects migration.
        if (header.base < snapshotSequence || header.version == 1) checkpoint(catalog)
        var removed = false
        Files.newDirectoryStream(path.parent, temporaryPrefix(path) + "*.tmp").use { files ->
            for (file in files) removed = io.deleteIfExists(file) || removed
        }
        if (removed) io.syncDirectory(path.parent)
    }

    private fun writeReplacement(target: Path, bytes: ByteArray, snapshot: Boolean) {
        withTemporary(path, io) { temporary ->
            io.withChannel(temporary, WRITE) {
                io.append(it, bytes)
                io.force(it)
                io.event(if (snapshot) StoreEvent.SNAPSHOT_FORCED else StoreEvent.WAL_RESET_FORCED)
            }
            io.replace(temporary, target)
            io.event(if (snapshot) StoreEvent.SNAPSHOT_REPLACED else StoreEvent.WAL_RESET_REPLACED)
            io.syncDirectory(path.parent)
            io.event(if (snapshot) StoreEvent.SNAPSHOT_DIRECTORY_SYNCED else StoreEvent.WAL_RESET_DIRECTORY_SYNCED)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        owner.use {
            if (::wal.isInitialized) io.close(wal)
        }
    }

    private class Owner(val path: Path, val channel: FileChannel, val lock: FileLock, val token: Any, val io: StoreIO) : AutoCloseable {
        override fun close() {
            try { io.close(channel) } finally { owners.remove(path, token) }
        }
    }

    companion object {
        private val owners = ConcurrentHashMap<Path, Any>()

        fun open(input: Path, io: StoreIO = StoreIO(), maxSnapshotBytes: Int = StorageCodec.MAX_SNAPSHOT): FileStore = try {
            require(maxSnapshotBytes in (StorageCodec.SNAPSHOT_HEADER + 4)..StorageCodec.MAX_SNAPSHOT)
            openOwned(input, io, maxSnapshotBytes)
        } catch (error: DatabaseException) {
            throw error
        } catch (error: Exception) {
            throw DatabaseException("Unable to open persistent database").also { it.initCause(error) }
        }

        private fun openOwned(input: Path, io: StoreIO, maxSnapshotBytes: Int): FileStore {
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
                channel = io.open(lockPath, CREATE, WRITE)
                val lock = channel.tryLock() ?: throw DatabaseException("Database is locked by another process")
                owner = Owner(path, channel, lock, token, io)
                // Another database may replace a nonempty snapshot at this path, invalidating our inode lock.
                if (channel.size() != 0L) throw DatabaseException("Ownership file is not empty; possible database path collision")
                io.force(channel)
                io.syncDirectory(path.parent)
                val snapshot = initializeOrRead(path, wal, io)
                store = FileStore(path, owner, snapshot, io, maxSnapshotBytes)
                store.recover()
                return store
            } catch (error: Throwable) {
                runCatching { store?.close() ?: owner?.close() ?: channel?.let(io::close) }.exceptionOrNull()?.let(error::addSuppressed)
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
                return StorageCodec.decodeSnapshot(io.readAllBytes(path))
            }
            val initial = if (Files.exists(wal)) {
                if (Files.size(wal) != StorageCodec.WAL_HEADER.toLong()) throw DatabaseException("Required snapshot is missing")
                val header = StorageCodec.decodeWalHeader(io.readAllBytes(wal))
                if (header.base != 0L) throw DatabaseException("Required snapshot history is missing")
                Snapshot(header.identity, 0, Catalog())
            } else Snapshot(UUID.randomUUID(), 0, Catalog())
            // WAL-first bootstrap distinguishes interrupted initialization from loss of a live WAL.
            if (!Files.exists(wal)) install(path, wal, StorageCodec.encodeWalHeader(WalHeader(initial.identity, 0)), io)
            install(path, path, StorageCodec.encodeSnapshot(initial), io)
            return initial
        }

        private fun install(database: Path, target: Path, bytes: ByteArray, io: StoreIO) {
            withTemporary(database, io) { temporary ->
                io.withChannel(temporary, WRITE) { io.append(it, bytes); io.force(it) }
                io.replace(temporary, target)
                io.syncDirectory(database.parent)
            }
        }

        private fun withTemporary(database: Path, io: StoreIO, action: (Path) -> Unit) {
            val temporary = Files.createTempFile(database.parent, temporaryPrefix(database), ".tmp")
            // Keep the write failure primary if deleting its temporary file also fails.
            AutoCloseable { io.deleteIfExists(temporary) }.use { action(temporary) }
        }

        private fun temporaryPrefix(path: Path): String = ".kokodb-" + MessageDigest.getInstance("SHA-256")
            .digest(path.toString().toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) } + "-"

        private fun read(channel: FileChannel, length: Int, io: StoreIO): ByteArray {
            val bytes = ByteArray(length)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (io.read(channel, buffer) < 0) throw DatabaseException("Truncated storage file")
            }
            return bytes
        }
    }
}
