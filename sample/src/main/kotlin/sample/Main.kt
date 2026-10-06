package sample

import kokodb.Database
import kokodb.DbTable
import kokodb.eq

@DbTable("users")
data class User(val id: Int, val name: String)

fun main() {
    val db = Database.inMemory()
    db.insert(User(1, "Koko"))
    val users: List<User> = db.from<User>().where(User::id eq 1).toList()
    check(users == listOf(User(1, "Koko")))
    println(users)
}
