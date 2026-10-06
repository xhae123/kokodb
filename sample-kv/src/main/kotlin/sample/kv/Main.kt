package sample.kv

import kokodb.Database

fun main() {
    val db = Database.inMemory()
    db["theme"] = "dark"
    db["retryCount"] = 3
    check(db["theme"] == "dark")
    check(db["retryCount"] == 3)
    println("theme=${db["theme"]}, retryCount=${db["retryCount"]}")
}
