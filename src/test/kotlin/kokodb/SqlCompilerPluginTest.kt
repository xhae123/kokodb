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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SqlCompilerPluginTest {
    @TempDir lateinit var directory: Path
    private var compilation = 0
    private val model = "@DbTable(\"compiler_users\") data class User(@Id val id: Int, val name: String)"

    private fun compile(code: String, expected: ExitCode, plugin: Boolean = true): String {
        val name = "SqlFixture${compilation++}"
        val source = directory.resolve("$name.kt")
        source.writeText("import kokodb.*\n$code")
        val classpath = listOf(KoKoDB::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            File(it.protectionDomain.codeSource.location.toURI()).path
        }
        val arguments = mutableListOf(
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
            "-d", directory.resolve(name).toString(), source.toString(),
        )
        if (plugin) arguments.add("-Xplugin=${System.getProperty("kokodb.test.sqlPlugin")}")
        val output = ByteArrayOutputStream()
        val result = PrintStream(output).use { K2JVMCompiler().exec(it, *arguments.toTypedArray()) }
        assertEquals(expected, result, output.toString())
        return output.toString()
    }

    @Test
    fun `valid static SQL compiles on each recognized API`() {
        val output = compile("""
            $model
            fun usage(db: Database, scope: Transaction) {
                KoKoDB("CREATE TABLE settings (name TEXT PRIMARY KEY, value INT)")
                KoKoDB("INSERT INTO settings VALUES (:name, :value)", mapOf("name" to "count", "value" to 3))
                KoKoDB("UPDATE settings SET value = 4 WHERE name = 'count';")
                KoKoDB("DELETE FROM settings WHERE name = :name", mapOf("name" to "count"))
                KoKoDB<User>("SELECT name, id FROM compiler_users WHERE id = :id", mapOf("id" to 1))
                KoKoDB.query("SELECT value FROM settings")
                KoKoDB.queryModels(User::class.java, "SELECT * FROM compiler_users")
                db("INSERT INTO compiler_users VALUES (1, 'Koko')")
                db.query("SELECT * FROM compiler_users")
                db.queryModels(User::class.java, "SELECT * FROM compiler_users")
                scope("UPDATE compiler_users SET name = 'Updated'")
                scope.query<User>("SELECT * FROM compiler_users")
                scope.queryModels(User::class.java, "SELECT * FROM compiler_users")
                scope.queryRows("SELECT name FROM compiler_users")
                val handle = KoKoDB
                handle.query("SELECT * FROM compiler_users")
            }
        """.trimIndent(), ExitCode.OK)
        assertFalse(output.contains("checked only at runtime"), output)
    }

    @Test
    fun `syntax failures report SQL offsets and Kotlin argument locations`() {
        for (sql in listOf(
            "SELEC * FROM compiler_users", "SELECT FROM", "SELECT * FROM compiler_users ORDER BY id",
            "SELECT * FROM compiler_users; DELETE FROM compiler_users", "SELECT * FROM compiler_users WHERE id = 2147483648",
            "SELECT * FROM compiler_users WHERE name = 'unterminated", "SELECT * FROM compiler_users WHERE id = :",
            "INSERT INTO compiler_users VALUES (1, 'Koko')", "UPDATE compiler_users SET name =",
        )) {
            val call = if (sql.startsWith("INSERT")) "KoKoDB(\"$sql\" + \" trailing\")" else "KoKoDB<User>(\"$sql\")"
            val output = compile("$model\nfun usage() { $call }", ExitCode.COMPILATION_ERROR)
            assertContains(output, "invalid KoKoDB SQL:")
            assertContains(output, "position")
            assertContains(output, ".kt:3:")
        }
    }

    @Test
    fun `resolved calls detect aliases named arguments and all query write mismatches`() {
        val output = compile("""
            import kokodb.KoKoDB as Db
            $model
            fun usage() = Db<User>(params = emptyMap(), sql = "SELEC * FROM compiler_users")
        """.trimIndent(), ExitCode.COMPILATION_ERROR)
        assertContains(output, "invalid KoKoDB SQL:")
        for (call in listOf(
            "KoKoDB(\"SELECT * FROM compiler_users\")",
            "db(\"SELECT * FROM compiler_users\")",
            "scope(\"SELECT * FROM compiler_users\")",
            "KoKoDB<User>(\"DELETE FROM compiler_users\")",
            "KoKoDB.query(\"CREATE TABLE other (id INT)\")",
            "db.queryModels(User::class.java, \"DELETE FROM compiler_users\")",
            "scope.query<User>(\"INSERT INTO compiler_users VALUES (1, 'Koko')\")",
            "scope.queryRows(\"DELETE FROM compiler_users\")",
        )) assertContains(compile("$model\nfun usage(db: Database, scope: Transaction) { $call }", ExitCode.COMPILATION_ERROR), "KoKoDB", ignoreCase = true)
    }

    @Test
    fun `literal constants concatenation interpolation and multiline SQL are checked`() {
        val quotes = "\"\"\""
        val output = compile("""
            $model
            const val TABLE = "compiler_users"
            const val SQL = "SELECT * FROM " + TABLE
            fun usage() {
                KoKoDB<User>(SQL)
                KoKoDB<User>("SELECT * FROM ${'$'}TABLE")
                KoKoDB<User>("SELECT * " + "FROM compiler_users")
                KoKoDB<User>("SELECT *\nFROM compiler_users WHERE name = 'Koko''s'")
                KoKoDB<User>($quotes
                    SELECT *
                    FROM compiler_users
                $quotes.trimIndent())
            }
        """.trimIndent(), ExitCode.OK)
        assertFalse(output.contains("checked only at runtime"), output)
        assertContains(compile("$model\nconst val SQL = \"SELEC * FROM compiler_users\"\nfun usage() = KoKoDB<User>(SQL)", ExitCode.COMPILATION_ERROR), "invalid KoKoDB SQL:")
        assertContains(compile("$model\nfun usage() = KoKoDB<User>(\" SELEC * FROM compiler_users \".trimIndent())", ExitCode.COMPILATION_ERROR), "invalid KoKoDB SQL:")
    }

    @Test
    fun `unrelated methods and SQL looking strings are not validated`() {
        val output = compile("""
            object OtherDatabase { operator fun invoke(sql: String) = sql }
            object KoKoDB { operator fun invoke(sql: String) = sql }
            fun usage() {
                val text = "SELEC * FROM anything"
                OtherDatabase(text)
                OtherDatabase("SELEC * FROM anything")
                KoKoDB("SELEC * FROM anything")
            }
        """.trimIndent(), ExitCode.OK)
        assertFalse(output.lowercase().contains("kokodb sql"), output)
    }

    @Test
    fun `dynamic SQL warns while unrelated schema and bindings remain runtime concerns`() {
        val output = compile("""
            $model
            fun usage(sql: String, table: String) {
                KoKoDB<User>(sql)
                KoKoDB.query("SELECT * FROM ${'$'}table")
                KoKoDB.query("SELEC * FROM ${'$'}table")
                KoKoDB<User>("SELECT absent FROM missing WHERE id = :unbound")
            }
        """.trimIndent(), ExitCode.OK)
        assertEquals(3, Regex("checked only at runtime").findAll(output).count(), output)
        Database.inMemory().use { db -> assertFailsWith<SqlSyntaxException> { db.query("SELEC * FROM missing") } }
    }

    @Test
    fun `ordinary variables custom getters transformations and indirect calls stay outside the static guarantee`() {
        val output = compile("""
            val customSql: String get() = error("Compiler must not execute this getter")
            fun usage() {
                val sql = "SELEC * FROM users"
                KoKoDB.query(sql)
                KoKoDB.query(customSql)
                KoKoDB.query("SELECT * FROM users".uppercase())
                val query = KoKoDB::query
                query("SELEC * FROM users", emptyMap())
            }
        """.trimIndent(), ExitCode.OK)
        assertEquals(3, Regex("checked only at runtime").findAll(output).count(), output)
    }

    @Test
    fun `plugin opt in changes malformed static SQL from runtime to compile time failure`() {
        val code = "$model\nfun usage() = KoKoDB<User>(\"SELEC * FROM compiler_users\")"
        compile(code, ExitCode.OK, plugin = false)
        assertContains(compile(code, ExitCode.COMPILATION_ERROR), "invalid KoKoDB SQL:")
    }
}
