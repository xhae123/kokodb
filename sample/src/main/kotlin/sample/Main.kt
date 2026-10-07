package sample

import kokodb.DbTable
import kokodb.Id
import kokodb.KokoDb

@DbTable("users")
data class User(@Id val id: Int, val name: String)

fun register(id: Int, name: String): Int = KokoDb.execute(
    "INSERT INTO users VALUES (:id, :name)",
    params = mapOf("id" to id, "name" to name),
)

fun named(name: String): List<User> = KokoDb<User>(
    "SELECT id, name FROM users WHERE name = :name",
    params = mapOf("name" to name),
)

fun main() {
    try {
        check(register(1, "Koko") == 1)
        val users = named("Koko")
        check(users == listOf(User(1, "Koko")))
        println(users)
        check(KokoDb<User>("SELECT * FROM users WHERE id = 2").isEmpty())
    } finally {
        KokoDb.close()
    }
}
