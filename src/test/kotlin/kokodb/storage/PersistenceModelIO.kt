package kokodb.storage

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Models file contents separately from durable names. Crash images cover every append prefix and
 * independent persistence of atomic replacements; they do not model sectors, caches, or hardware.
 */
internal class PersistenceModelIO(
    database: Path,
    private val omit: Barrier? = null,
) : StoreIO() {
    enum class Barrier { WAL_FORCE, SNAPSHOT_FORCE, SNAPSHOT_DIRECTORY, WAL_DIRECTORY }

    data class Image(val cut: String, val acknowledged: Boolean, val files: Map<String, ByteArray>)

    private class Inode(var bytes: ByteArray) {
        val possible = mutableListOf(bytes)
    }

    private val database = database.parent.toRealPath().resolve(database.fileName)
    private val names = listOf(database.fileName.toString(), database.fileName.toString() + ".wal")
    private val live = mutableMapOf<Path, Inode>()
    private val channels = mutableMapOf<FileChannel, Inode>()
    private val durableNames = mutableMapOf<String, Inode>()
    private val pendingNames = mutableMapOf<String, MutableList<Inode>>()
    private val uniqueImages = linkedMapOf<String, Image>()
    private var replacement: String? = null
    private var acknowledged = false
    val trace = mutableListOf<String>()
    var omitted = 0
        private set
    val images: List<Image> get() = uniqueImages.values.toList()

    init {
        for (name in names) {
            val path = this.database.resolveSibling(name)
            if (Files.exists(path)) {
                val inode = Inode(Files.readAllBytes(path))
                live[path] = inode
                durableNames[name] = inode
            }
        }
        capture("initial")
    }

    override fun open(path: Path, vararg options: OpenOption): FileChannel {
        val channel = super.open(path, *options)
        channels[channel] = live.getOrPut(path) { Inode(Files.readAllBytes(path)) }
        return channel
    }

    override fun append(channel: FileChannel, bytes: ByteArray) {
        val inode = channels.getValue(channel)
        check(bytes.size <= 4096) { "Exhaustive prefix modeling requires small fixtures" }
        check(channel.position() == inode.bytes.size.toLong()) { "The model only supports append writes" }
        val before = inode.bytes
        super.append(channel, bytes)
        for (length in 1..bytes.size) inode.possible.add(before + bytes.copyOf(length))
        inode.bytes = before + bytes
        capture("append:${role(inode)}:${bytes.size}")
    }

    override fun force(channel: FileChannel) {
        val inode = channels.getValue(channel)
        val role = role(inode)
        val skipped = (role == "wal" && omit == Barrier.WAL_FORCE) ||
            (role == "snapshot" && omit == Barrier.SNAPSHOT_FORCE)
        if (skipped) omitted++ else {
            super.force(channel)
            inode.possible.clear()
            inode.possible.add(inode.bytes)
        }
        capture("force:$role${if (skipped) ":omitted" else ""}")
    }

    override fun truncate(channel: FileChannel, size: Long) {
        val inode = channels.getValue(channel)
        check(size <= inode.bytes.size)
        super.truncate(channel, size)
        inode.bytes = inode.bytes.copyOf(size.toInt())
        inode.possible.add(inode.bytes)
        capture("truncate:$size")
    }

    override fun replace(source: Path, target: Path) {
        val inode = live.getValue(source)
        super.replace(source, target)
        live.remove(source)
        live[target] = inode
        val name = target.fileName.toString()
        check(name in names)
        pendingNames.getOrPut(name) { mutableListOf() }.add(inode)
        replacement = if (name == names.first()) "snapshot" else "wal"
        capture("replace:$replacement")
    }

    override fun syncDirectory(directory: Path) {
        val skipped = (replacement == "snapshot" && omit == Barrier.SNAPSHOT_DIRECTORY) ||
            (replacement == "wal" && omit == Barrier.WAL_DIRECTORY)
        if (skipped) omitted++ else {
            super.syncDirectory(directory)
            for (name in names) live[database.resolveSibling(name)]?.let { durableNames[name] = it }
            pendingNames.clear()
        }
        capture("directory:${replacement ?: "ownership"}${if (skipped) ":omitted" else ""}")
        replacement = null
    }

    fun acknowledge() {
        acknowledged = true
        capture("acknowledged")
    }

    private fun role(inode: Inode): String = when (String(inode.bytes.take(8).toByteArray(), Charsets.US_ASCII)) {
        "KOKODB01" -> "snapshot"
        "KOKOWAL1" -> "wal"
        else -> "empty"
    }

    private fun capture(cut: String) {
        for (name in names) {
            val path = database.resolveSibling(name)
            check(Files.exists(path) == live.containsKey(path)) { "Untracked file binding: $name" }
            live[path]?.let { check(Files.readAllBytes(path).contentEquals(it.bytes)) { "Untracked file write: $name" } }
        }
        trace.add(cut)
        fun visit(index: Int, files: Map<String, ByteArray>) {
            if (index == names.size) {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(if (acknowledged) 1.toByte() else 0.toByte())
                for ((name, bytes) in files.toSortedMap()) {
                    digest.update(name.toByteArray())
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                    digest.update(bytes)
                }
                val key = digest.digest().joinToString("") { "%02x".format(it) }
                uniqueImages.putIfAbsent(key, Image(cut, acknowledged, files))
                return
            }
            val name = names[index]
            val bindings = listOf(durableNames[name]) + pendingNames[name].orEmpty()
            for (inode in bindings.distinct()) {
                if (inode == null) visit(index + 1, files)
                else for (bytes in inode.possible) visit(index + 1, files + (name to bytes))
            }
        }
        visit(0, emptyMap())
    }
}
