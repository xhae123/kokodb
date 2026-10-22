package sample

import kokodb.Database
import kokodb.DatabaseException
import kokodb.KoKoDB
import java.nio.file.Path
import java.util.Collections

/** Fresh-process consumer used by check_backup.py; each invocation closes its database before returning. */
object BackupFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = Path.of(args[1])
        when (args[0]) {
            "create" -> {
                KoKoDB.open(path)
                try {
                    KoKoDB("CREATE TABLE audit (id INT PRIMARY KEY, name TEXT)")
                    add(1, "Alpha")
                    if (args[2] == "mixed") KoKoDB.checkpoint()
                    add(2, "Saved ' 한글 😀")
                    if (args[2] == "snapshot") KoKoDB.checkpoint()
                } finally { KoKoDB.close() }
                println("SOURCE_CLOSED")
            }
            "restore" -> {
                KoKoDB.open(path)
                try {
                    verify(listOf(User(1, "Alpha"), User(2, "Saved ' 한글 😀")))
                    add(3, "Restored")
                    KoKoDB.checkpoint()
                } finally { KoKoDB.close() }
                KoKoDB.open(path)
                try {
                    verify(listOf(User(1, "Alpha"), User(2, "Saved ' 한글 😀"), User(3, "Restored")))
                } finally { KoKoDB.close() }
                println("RESTORE_VERIFIED")
            }
            "prefix" -> {
                KoKoDB.open(path)
                try { verify(listOf(User(1, "Alpha"))) } finally { KoKoDB.close() }
                println("PREFIX_RECOVERED")
            }
            "incompatible" -> {
                val loader = object : ClassLoader(Database::class.java.classLoader) {
                    override fun getResources(name: String) =
                        if (name == "META-INF/services/kokodb.mapping.ModelProvider") Collections.emptyEnumeration()
                        else super.getResources(name)
                }
                Database.open(path, loader).use { it("CREATE TABLE users (id INT)") }
                println("SOURCE_CLOSED")
            }
            "reject" -> {
                val failure = runCatching { KoKoDB.open(path) }.exceptionOrNull()
                try {
                    check(failure is DatabaseException && failure.message == args[2]) { "Unexpected open result: $failure" }
                } finally { KoKoDB.close() }
                println("OPEN_REJECTED")
            }
            else -> error("Unknown backup fixture operation")
        }
    }

    private fun add(id: Int, name: String) = KoKoDB.transaction {
        val params = mapOf("id" to id, "name" to name)
        KoKoDB("INSERT INTO users VALUES (:id, :name)", params)
        KoKoDB("INSERT INTO audit VALUES (:id, :name)", params)
    }

    private fun verify(expected: List<User>) {
        check(KoKoDB<User>("SELECT * FROM users").sortedBy { it.id } == expected)
        check(KoKoDB.query("SELECT * FROM audit").map { User(it.getInt("id"), it.getString("name")) }.sortedBy { it.id } == expected)
    }
}
