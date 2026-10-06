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

class DslCompilationTest {
    @TempDir
    lateinit var directory: Path

    private val schema = """
        import kokodb.*
        object Users : Table("users") {
            val id = int("id")
            val username = text("name")
        }
    """.trimIndent()

    private fun compile(name: String, code: String, expected: ExitCode) {
        val source = directory.resolve("$name.kt")
        source.writeText("$schema\n$code")
        val classpath = listOf(Column::class.java, Unit::class.java).joinToString(File.pathSeparator) {
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
    fun `typed insertion equality and result access compile from an external consumer`() {
        compile("Valid", """
            data class User(val id: Int, val name: String)
            fun usage(db: Database): List<User> {
                db.createTable(Users)
                db.insertInto(Users) {
                    set(Users.id, 1)
                    set(Users.username, "Koko")
                }
                return db.from(Users).select(Users.id, Users.username).where(Users.id eq 1)
                    .map { User(it[Users.id], it[Users.username]) }
            }
        """.trimIndent(), ExitCode.OK)
    }

    @Test
    fun `wrong insertion predicate result and null types do not compile`() {
        val invalid = mapOf(
            "Insert" to "fun usage(db: Database) { db.insertInto(Users) { set(Users.id, \"one\") } }",
            "Predicate" to "val condition = Users.id eq \"one\"",
            "Result" to "fun usage(row: Row) { val name: String = row[Users.id] }",
            "Null" to "fun usage(db: Database) { db.insertInto(Users) { set(Users.id, null) } }",
            "Widening" to "val column: Column<Any> = Users.id",
            "Long" to "fun usage(db: Database) { db.insertInto(Users) { set(Users.id, 1L) } }",
        )
        for ((name, code) in invalid) compile(name, code, ExitCode.COMPILATION_ERROR)
    }

    @Test
    fun `model property queries compile without table references`() {
        compile("ModelValid", """
            @DbTable("models")
            data class Model(val id: Int, val name: String)
            fun usage(db: Database): List<Model> {
                db.insert(Model(1, "Koko"))
                return db.from<Model>().where(Model::id eq 1).toList()
            }
        """.trimIndent(), ExitCode.OK)
    }

    @Test
    fun `model predicates reject incompatible values and model owners`() {
        val models = """
            data class Model(val id: Int, val name: String)
            data class Other(val id: Int)
        """.trimIndent()
        val invalid = mapOf(
            "ModelValue" to "val condition = Model::id eq \"one\"",
            "ModelText" to "val condition = Model::name eq 1",
            "ModelNull" to "val condition = Model::id eq null",
            "ModelLong" to "val condition = Model::id eq 1L",
            "ModelOwner" to "fun usage(db: Database) { db.from<Model>().where(Other::id eq 1) }",
        )
        for ((name, code) in invalid) compile(name, "$models\n$code", ExitCode.COMPILATION_ERROR)
    }
}
