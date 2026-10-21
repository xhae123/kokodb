package kokodb.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ

internal enum class StoreEvent {
    BEFORE_APPEND, AFTER_APPEND, AFTER_WAL_FORCE,
    SNAPSHOT_FORCED, SNAPSHOT_REPLACED, SNAPSHOT_DIRECTORY_SYNCED,
    WAL_RESET_FORCED, WAL_RESET_REPLACED, WAL_RESET_DIRECTORY_SYNCED,
}

/** File operations are injectable so tests can fail or terminate at actual durability boundaries. */
internal open class StoreIO {
    open fun open(path: Path, vararg options: OpenOption): FileChannel = FileChannel.open(path, *options)

    open fun read(channel: FileChannel, buffer: ByteBuffer): Int = channel.read(buffer)

    open fun readAllBytes(path: Path): ByteArray = Files.readAllBytes(path)

    open fun close(channel: FileChannel) = channel.close()

    open fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    fun <T> withChannel(path: Path, vararg options: OpenOption, block: (FileChannel) -> T): T {
        val channel = open(path, *options)
        return AutoCloseable { close(channel) }.use { block(channel) }
    }

    open fun append(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    open fun force(channel: FileChannel) = channel.force(true)

    open fun truncate(channel: FileChannel, size: Long) {
        channel.truncate(size)
    }

    open fun replace(source: Path, target: Path) {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    open fun syncDirectory(directory: Path) {
        FileChannel.open(directory, READ).use { it.force(true) }
    }

    open fun event(event: StoreEvent) = Unit
}
