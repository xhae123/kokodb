package sample

import kokodb.Database
import kokodb.DbRepository
import kokodb.DbTable
import kokodb.Id
import kokodb.Repository
import kokodb.eq

@DbTable("users")
data class User(@Id val id: Int, val name: String)

@DbRepository
interface UserRepository : Repository<User, Int> {
    fun named(name: String): List<User> = query().where(User::name eq name).toList()
}

class UserService(private val users: UserRepository) {
    fun register(id: Int, name: String): Int = users.insert(User(id, name))
    fun named(name: String): List<User> = users.named(name)
}

fun main() {
    val db = Database.inMemory()
    val repository = UserRepository(db)
    val service = UserService(repository)
    service.register(1, "Koko")
    val users: List<User> = service.named("Koko")
    check(users == listOf(User(1, "Koko")))
    println(users)
    check(repository.update(User(1, "Updated")) == 1)
    check(repository.findById(1) == User(1, "Updated"))
    check(repository.deleteById(1) == 1)
    check(repository.findAll().isEmpty())
}
