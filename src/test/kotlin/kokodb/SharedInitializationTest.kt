package kokodb

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.ServiceConfigurationError
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal object SharedInitializationProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val path = Path.of(args[1])
        val rawLoader = object : ClassLoader(Database::class.java.classLoader) {
            override fun getResources(name: String) =
                if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
                else super.getResources(name)
        }
        val brokenLoader = object : ClassLoader(rawLoader) {
            override fun getResources(name: String) =
                if (name == "META-INF/services/kokodb.mapping.ModelProvider") throw IOException("Model discovery unavailable")
                else super.getResources(name)
        }
        try {
            when (mode) {
                "lazy" -> writeAndRead()
                "memory", "file" -> {
                    val failure = assertFailsWith<ServiceConfigurationError> {
                        if (mode == "memory") KoKoDB.openInMemory(brokenLoader) else KoKoDB.open(path, brokenLoader)
                    }
                    assertEquals("Model discovery unavailable", failure.cause?.message)
                    assertClosed()
                    if (mode == "memory") KoKoDB.openInMemory(rawLoader) else KoKoDB.open(path, rawLoader)
                    writeAndRead()
                }
                else -> error("Unknown scenario: $mode")
            }
            KoKoDB.close()
            assertClosed()
            if (mode == "file") {
                KoKoDB.open(path, rawLoader)
                assertEquals(7, KoKoDB.query("SELECT id FROM startup").single().getInt("id"))
            } else {
                KoKoDB.openInMemory(rawLoader)
                assertFailsWith<DatabaseException> { KoKoDB.query("SELECT * FROM startup") }
                writeAndRead()
            }
            println("$mode: initialization, access guards and explicit reopening passed")
        } finally { KoKoDB.close() }
    }

    private fun writeAndRead() {
        assertEquals(0, KoKoDB("CREATE TABLE startup (id INT PRIMARY KEY)"))
        assertEquals(1, KoKoDB("INSERT INTO startup VALUES (7)"))
        assertEquals(7, KoKoDB.query("SELECT id FROM startup").single().getInt("id"))
    }

    private fun assertClosed() {
        fun rejected(action: () -> Unit) {
            val failure = assertFailsWith<DatabaseException> { action() }
            assertTrue(failure.message.orEmpty().startsWith("KoKoDB is closed"), failure.message)
        }
        rejected { KoKoDB("CREATE TABLE unexpected (id INT)") }
        rejected { KoKoDB.query("SELECT * FROM startup") }
        rejected { KoKoDB<SharedUser>("SELECT * FROM shared_users") }
        var called = false
        rejected { KoKoDB.transaction { called = true } }
        assertFalse(called, "A closed database must not invoke the transaction callback")
        rejected { KoKoDB.checkpoint() }
    }
}

class SharedInitializationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `first use lazily opens the shared memory database`() = child("lazy")

    @Test
    fun `failed first explicit memory opening blocks lazy fallback`() = child("memory")

    @Test
    fun `failed first explicit file opening blocks lazy fallback and releases ownership`() = child("file")

    private fun child(mode: String) {
        // Existing facade tests close first; only a fresh JVM exercises the initial lazy-open state.
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:-UsePerfData", "-cp", System.getProperty("kokodb.test.classpath"),
            "kokodb.SharedInitializationProcess", mode, directory.resolve("app.koko").toString(),
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "$mode initialization timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            println(output.trim())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
