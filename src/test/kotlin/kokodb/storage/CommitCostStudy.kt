package kokodb.storage

import kokodb.Database
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.Collections

/** Attributes current durable-call costs; it does not implement group commit. */
object CommitCostStudy {
    @JvmStatic
    fun main(args: Array<String>) {
        val loader = object : ClassLoader(Database::class.java.classLoader) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
                if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
                else super.getResources(name)
        }
        for (rows in listOf(100, 1000)) for (batch in listOf(false, true)) repeat(3) { round ->
            val directory = Files.createTempDirectory("kokodb-force-study-")
            val path = directory.resolve("data.koko")
            val io = RecordingIO()
            var elapsed: Long
            Database.open(path, loader, io).use { database ->
                database("CREATE TABLE entries (id INT PRIMARY KEY, name TEXT)")
                database.checkpoint()
                io.reset()
                val started = System.nanoTime()
                val insert = { repeat(rows) { id -> database("INSERT INTO entries VALUES (:id, :name)", mapOf("id" to id, "name" to "row$id")) } }
                if (batch) database.transaction { insert() } else insert()
                elapsed = System.nanoTime() - started
                check(io.appends == if (batch) 1 else rows)
                check(io.forces == io.appends)
                check(database.query("SELECT * FROM entries").size == rows)
            }
            Database.open(path, loader).use { database ->
                val recovered = database.query("SELECT * FROM entries").associate { it.getInt("id") to it.getString("name") }
                check(recovered == (0 until rows).associateWith { "row$it" })
            }
            println("""{"rows":$rows,"one_transaction":$batch,"round":${round + 1},"appends":${io.appends},"forces":${io.forces},"wal_bytes":${io.bytes},"elapsed_ns":$elapsed,"append_ns":${io.appendNanos},"force_ns":${io.forceNanos},"other_ns":${elapsed - io.appendNanos - io.forceNanos}}""")
            directory.toFile().deleteRecursively()
        }
    }

    private class RecordingIO : StoreIO() {
        var appends = 0
        var forces = 0
        var bytes = 0L
        var appendNanos = 0L
        var forceNanos = 0L
        fun reset() { appends = 0; forces = 0; bytes = 0; appendNanos = 0; forceNanos = 0 }
        override fun append(channel: FileChannel, bytes: ByteArray) {
            val started = System.nanoTime()
            super.append(channel, bytes)
            appendNanos += System.nanoTime() - started
            this.bytes += bytes.size
            appends++
        }
        override fun force(channel: FileChannel) {
            val started = System.nanoTime()
            super.force(channel)
            forceNanos += System.nanoTime() - started
            forces++
        }
    }
}
