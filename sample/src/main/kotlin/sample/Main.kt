package sample

import kokodb.Database
import kokodb.DbTable
import kokodb.Id
import kokodb.Repository
import kokodb.eq
import kokodb.repository

@DbTable("users")
data class User(@Id val id: Int, val name: String)

class UserRepository(database: Database) : Repository<User, Int>(database, User::class, Int::class) {
    fun named(name: String): List<User> = query().where(User::name eq name).toList()
}

fun main() {
    val db = Database.inMemory()
    val repository = db.repository<User, Int>()
    repository.insert(User(1, "Koko"))
    val users: List<User> = UserRepository(db).named("Koko")
    check(users == listOf(User(1, "Koko")))
    println(users)
    check(repository.update(User(1, "Updated")) == 1)
    check(repository.findById(1) == User(1, "Updated"))
    check(repository.deleteById(1) == 1)
    check(repository.findAll().isEmpty())
}
