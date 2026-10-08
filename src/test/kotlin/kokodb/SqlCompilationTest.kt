package kokodb

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlCompilationTest {
    @TempDir
    lateinit var directory: Path

    private fun compile(name: String, code: String, expected: ExitCode) {
        val source = directory.resolve("$name.kt")
        source.writeText("import kokodb.*\n$code")
        val classpath = listOf(KoKoDB::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            File(it.protectionDomain.codeSource.location.toURI()).path
        }
        val output = ByteArrayOutputStream()
        val result = PrintStream(output).use {
            K2JVMCompiler().exec(
                it, "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                "-d", directory.resolve(name).toString(), source.toString()
            )
        }
        assertEquals(expected, result, output.toString())
    }

    @Test
    fun `singleton typed SQL invocation compiles without database or repository construction`() {
        val model = "@DbTable(\"models\") data class Model(val id: Int, val name: String)"
        compile("SingletonSql", "$model\n" + """
            fun usage(): List<Model> {
                KoKoDB("INSERT INTO models VALUES (:id, :name)", mapOf("id" to 1, "name" to "Koko"))
                return KoKoDB<Model>("SELECT * FROM models WHERE id = :id", params = mapOf("id" to 1))
            }
        """.trimIndent(), ExitCode.OK)
        compile("SingletonResult", "$model\nfun usage(): List<String> = KoKoDB<Model>(\"SELECT * FROM models\")", ExitCode.COMPILATION_ERROR)
        compile("SingletonNull", "$model\nfun usage() = KoKoDB<Model?>(\"SELECT * FROM models\")", ExitCode.COMPILATION_ERROR)
        compile("ExplicitModel", "$model\nfun usage(): List<Model> = KoKoDB(\"SELECT * FROM models\")", ExitCode.COMPILATION_ERROR)
    }

    @Test
    fun `write calls resolve to Int across shared independent and transaction APIs`() {
        compile("WriteCalls", """
            fun usage(): Int {
                val created = KoKoDB("CREATE TABLE counts (value INT)")
                val count: Int = created
                val inserted: Int = KoKoDB(sql = "INSERT INTO counts VALUES (:value)", params = mapOf("value" to 3))
                val db = Database.inMemory()
                val independent: Int = db("CREATE TABLE counts (value INT)")
                val scoped: Int = db.transaction { this("INSERT INTO counts VALUES (4)") }
                return count + inserted + independent + scoped
            }
        """.trimIndent(), ExitCode.OK)
        compile("WriteResult", "fun usage(): List<Row> = KoKoDB(\"INSERT INTO counts VALUES (3)\")", ExitCode.COMPILATION_ERROR)
    }

    @Test
    fun `removed write methods cannot compile on any public handle`() {
        for ((name, usage) in listOf(
            "SharedWriteMethod" to "KoKoDB.execute(\"DELETE FROM counts\")",
            "IndependentWriteMethod" to "Database.inMemory().execute(\"DELETE FROM counts\")",
            "ScopedWriteMethod" to "Database.inMemory().transaction { execute(\"DELETE FROM counts\") }",
        )) compile(name, "fun usage() { $usage }", ExitCode.COMPILATION_ERROR)
    }

    @Test
    fun `raw SQL consumers compile without model declarations`() {
        compile("RawSql", """
            fun usage(): Int {
                KoKoDB("CREATE TABLE counts (value INT)")
                KoKoDB("INSERT INTO counts VALUES (:value)", mapOf("value" to 3))
                return KoKoDB.query("SELECT * FROM counts").single().getInt("value")
            }
        """.trimIndent(), ExitCode.OK)
    }
}
