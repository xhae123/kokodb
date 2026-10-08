package sample

import kokodb.KoKoDB
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 1) { "Provide a database file path whose parent directory exists" }
    KoKoDB.open(Path.of(args.single()))
    try {
        val users = KoKoDB<User>("SELECT * FROM users WHERE id = 1")
        if (users.isEmpty()) {
            KoKoDB.transaction { KoKoDB("INSERT INTO users VALUES (1, 'Persisted')") }
        }
        check(KoKoDB<User>("SELECT * FROM users WHERE id = 1") == listOf(User(1, "Persisted")))
        println("Restored: ${KoKoDB<User>("SELECT * FROM users")}")
        KoKoDB.checkpoint()
    } finally { KoKoDB.close() }
}
