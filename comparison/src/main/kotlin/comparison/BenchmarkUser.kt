package comparison

import kokodb.DbTable
import kokodb.Id

@DbTable("benchmark_users")
data class BenchmarkUser(@Id val id: Int, val name: String)
