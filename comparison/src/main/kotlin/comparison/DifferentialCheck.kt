package comparison

import kokodb.Database
import kokodb.DatabaseException
import kokodb.Row
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Random
import java.util.UUID

/** A finite, seeded SQL semantics and recovery check, not a performance benchmark. */
object DifferentialCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            for (mode in listOf("memory", "file")) for (seed in listOf(17, 31)) {
                val directory = Files.createTempDirectory("kokodb-differential-")
                main(arrayOf(mode, seed.toString(), "150", directory.toString()))
                directory.toFile().deleteRecursively()
            }
            return
        }
        require(args.size == 4) { "Expected memory|file, seed, steps and an empty output directory" }
        val mode = args[0]
        require(mode == "memory" || mode == "file")
        val seed = args[1].toLong()
        val steps = args[2].toInt().also { require(it > 0) }
        val directory = Path.of(args[3])
        Files.createDirectories(directory)
        require(Files.list(directory).use { it.findAny().isEmpty }) { "Output directory must be empty" }
        Harness(mode, seed, directory).use { harness ->
            try {
                harness.run(steps)
                val result = harness.result(steps)
                Files.writeString(directory.resolve("result.json"), result + "\n")
                println(result)
            } catch (failure: Throwable) {
                val diagnostic = "mode=$mode seed=$seed steps=$steps\n" + harness.trace.joinToString("\n") + "\n" + failure.stackTraceToString()
                Files.writeString(directory.resolve("failure.txt"), diagnostic)
                System.err.println(diagnostic)
                throw failure
            }
        }
    }

    private data class Table(val name: String, val textKey: Boolean) {
        fun key(index: Int): Any = if (textKey) "key:$index" else index
        fun row(row: Row) = Entry(if (textKey) row.getString("id") else row.getInt("id"), row.getString("name"), row.getInt("bucket"))
    }
    private data class Entry(val id: Any, val name: String, val bucket: Int)
    private enum class Kind { INSERT, NAME_BY_ID, NAME_BY_BUCKET, MOVE_KEY, DELETE_ID, DELETE_BUCKET, ALL_KEYS, ALL_NAMES, CLEAR }
    private data class Operation(val table: Table, val kind: Kind, val key: Any, val next: Any, val name: String, val bucket: Int) {
        val params: Map<String, Any> = mapOf("id" to key, "next" to next, "name" to name, "bucket" to bucket)
        val sql: String get() = when (kind) {
            Kind.INSERT -> "INSERT INTO ${table.name} VALUES (:id, :name, :bucket)"
            Kind.NAME_BY_ID -> "UPDATE ${table.name} SET name = :name WHERE id = :id"
            Kind.NAME_BY_BUCKET -> "UPDATE ${table.name} SET name = :name WHERE bucket = :bucket"
            Kind.MOVE_KEY -> "UPDATE ${table.name} SET id = :next WHERE id = :id"
            Kind.DELETE_ID -> "DELETE FROM ${table.name} WHERE id = :id"
            Kind.DELETE_BUCKET -> "DELETE FROM ${table.name} WHERE bucket = :bucket"
            Kind.ALL_KEYS -> "UPDATE ${table.name} SET id = :next"
            Kind.ALL_NAMES -> "UPDATE ${table.name} SET name = :name"
            Kind.CLEAR -> "DELETE FROM ${table.name}"
        }

        fun candidate(rows: Map<Any, Entry>): Pair<Int, Map<Any, Entry>>? {
            if (kind == Kind.INSERT) {
                if (rows.containsKey(key)) return null
                return 1 to (rows + (key to Entry(key, name, bucket)))
            }
            var affected = 0
            val result = linkedMapOf<Any, Entry>()
            for (row in rows.values) {
                val matches = when (kind) {
                    Kind.NAME_BY_ID, Kind.MOVE_KEY, Kind.DELETE_ID -> row.id == key
                    Kind.NAME_BY_BUCKET, Kind.DELETE_BUCKET -> row.bucket == bucket
                    else -> true
                }
                val updated = if (!matches) row else {
                    affected++
                    when (kind) {
                        Kind.NAME_BY_ID, Kind.NAME_BY_BUCKET, Kind.ALL_NAMES -> row.copy(name = name)
                        Kind.MOVE_KEY, Kind.ALL_KEYS -> row.copy(id = next)
                        else -> null
                    }
                }
                if (updated != null && result.put(updated.id, updated) != null) return null
            }
            return affected to result
        }
    }
    private class Rollback : RuntimeException()

    private class Harness(private val mode: String, private val seed: Long, private val directory: Path) : AutoCloseable {
        private val random = Random(seed)
        private val tables = listOf(Table("check_ints", false), Table("check_texts", true))
        private var expected = tables.associateWith { emptyMap<Any, Entry>() }.toMutableMap()
        private val path = directory.resolve("data.koko")
        private var database = open()
        private val h2 = DriverManager.getConnection("jdbc:h2:mem:check_${UUID.randomUUID()}", "sa", "")
        val trace = ArrayDeque<String>()
        private var attempts = 0
        private var committedTransactions = 0
        private var rolledBackTransactions = 0
        private var autocommitStatements = 0
        private var constraintFailures = 0
        private var comparisons = 0
        private var reopens = 0
        private var checkpoints = 0

        private fun open() = if (mode == "file") Database.open(path) else Database.inMemory()
        private fun record(message: String) {
            if (trace.size == 128) trace.removeFirst()
            trace.addLast(message)
        }

        fun run(steps: Int) {
            record("mode=$mode seed=$seed steps=$steps")
            for (table in tables) {
                val type = if (table.textKey) "TEXT" else "INT"
                database("CREATE TABLE ${table.name} (id $type PRIMARY KEY, name TEXT, bucket INT)")
                h2.createStatement().use { it.execute("CREATE TABLE ${table.name} (id $type PRIMARY KEY, name TEXT NOT NULL, bucket INT NOT NULL)") }
            }
            // Start beyond two block boundaries; both tables participate in one committed transaction.
            transaction(tables.flatMap { table -> (0 until 300).map { operation(table, Kind.INSERT, it, it, "initial$it", it % 7) } }, false)
            transaction(listOf(operation(tables[0], Kind.ALL_NAMES), operation(tables[1], Kind.ALL_NAMES)), true)
            transaction(listOf(operation(tables[0], Kind.ALL_NAMES), operation(tables[1], Kind.ALL_KEYS)), false)
            repeat(steps) { step ->
                record("step=$step")
                val count = 1 + random.nextInt(5)
                val operations = List(count) { randomOperation() }
                if (step % 4 == 0) {
                    autocommitStatements++
                    apply(operations[0], expected)
                    verify(expected)
                } else transaction(operations, step % 11 == 0)
                if (mode == "file" && step % 17 == 0) reopen(step % 34 == 0)
            }
            if (mode == "file") { reopen(false); reopen(true) }
            verify(expected)
        }

        private fun operation(table: Table, kind: Kind, key: Int = 0, next: Int = 1, name: String = "changed", bucket: Int = 0) =
            Operation(table, kind, table.key(key), table.key(next), name, bucket)

        private fun randomOperation(): Operation {
            val table = tables[random.nextInt(tables.size)]
            val kind = when (random.nextInt(100)) {
                in 0..34 -> Kind.INSERT
                in 35..54 -> Kind.NAME_BY_ID
                in 55..64 -> Kind.MOVE_KEY
                in 65..74 -> Kind.DELETE_ID
                in 75..82 -> Kind.NAME_BY_BUCKET
                in 83..87 -> Kind.DELETE_BUCKET
                in 88..92 -> Kind.ALL_NAMES
                in 93..98 -> Kind.ALL_KEYS
                else -> Kind.CLEAR
            }
            val names = arrayOf("", "O'Brien", "\uD55C\uAE00\uD83E\uDDEA", "nul\u0000value", "comma,:name\n", "value${random.nextInt(50)}")
            return operation(table, kind, random.nextInt(512) - 32, random.nextInt(512) - 32, names[random.nextInt(names.size)], random.nextInt(7))
        }

        private fun transaction(operations: List<Operation>, cancel: Boolean) {
            val working = expected.toMutableMap()
            h2.autoCommit = false
            try {
                database.transaction {
                    for (operation in operations) {
                        if (!apply(operation, working)) throw Rollback()
                        verify(working)
                    }
                    if (cancel) throw Rollback()
                }
                h2.commit()
                expected = working
                committedTransactions++
            } catch (_: Rollback) {
                h2.rollback()
                rolledBackTransactions++
            } catch (failure: Throwable) {
                h2.rollback()
                throw failure
            } finally {
                h2.autoCommit = true
            }
            verify(expected)
        }

        private fun apply(operation: Operation, model: MutableMap<Table, Map<Any, Entry>>): Boolean {
            record("${operation.sql} ${operation.params}")
            attempts++
            val candidate = operation.candidate(model.getValue(operation.table))
            val actual = runCatching { database(operation.sql, operation.params) }
            val other = runCatching { write(h2, operation.sql, operation.params) }
            if (candidate == null) {
                check(actual.exceptionOrNull() is DatabaseException) { "KoKoDB accepted duplicate key: $actual" }
                val failure = other.exceptionOrNull()
                check(failure is SQLException && failure.sqlState == "23505") { "H2 duplicate-key outcome: $other" }
                constraintFailures++
                return false
            }
            check(actual.getOrThrow() == candidate.first) { "KoKoDB affected count: $actual expected=${candidate.first}" }
            check(other.getOrThrow() == candidate.first) { "H2 affected count: $other expected=${candidate.first}" }
            model[operation.table] = candidate.second
            return true
        }

        private fun verify(model: Map<Table, Map<Any, Entry>>) {
            for (table in tables) {
                val rows = model.getValue(table)
                compare(table, "SELECT * FROM ${table.name}", emptyMap(), rows.values.toList())
                val key = table.key(127)
                repeat(2) { compare(table, "SELECT name, bucket, id FROM ${table.name} WHERE id = :id", mapOf("id" to key), rows.values.filter { it.id == key }) }
                compare(table, "SELECT * FROM ${table.name} WHERE bucket = :bucket", mapOf("bucket" to 3), rows.values.filter { it.bucket == 3 })
                compare(table, "SELECT * FROM ${table.name} WHERE id = :id", mapOf("id" to table.key(Int.MIN_VALUE)), emptyList())
            }
        }

        private fun compare(table: Table, sql: String, params: Map<String, Any>, expectedRows: List<Entry>) {
            val actual = database.query(sql, params).map(table::row)
            val other = h2.prepareStatement(jdbc(sql).first).use { statement ->
                bind(statement, sql, params)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(Entry(if (table.textKey) result.getString("id") else result.getInt("id"), result.getString("name"), result.getInt("bucket")))
                    }
                }
            }
            val expectedByKey = expectedRows.associateBy { it.id }
            check(actual.size == expectedRows.size && actual.associateBy { it.id } == expectedByKey) { "KoKoDB rows differ: $sql $params expected=$expectedRows actual=$actual" }
            check(other.size == expectedRows.size && other.associateBy { it.id } == expectedByKey) { "H2 rows differ: $sql $params expected=$expectedRows actual=$other" }
            comparisons++
        }

        private fun reopen(checkpoint: Boolean) {
            record("reopen checkpoint=$checkpoint")
            if (checkpoint) { database.checkpoint(); checkpoints++ }
            database.close()
            database = open()
            reopens++
            verify(expected)
        }

        fun result(steps: Int): String = """{"mode":"$mode","seed":$seed,"steps":$steps,"attempted_statements":$attempts,"autocommit_statements":$autocommitStatements,"committed_transactions":$committedTransactions,"rolled_back_transactions":$rolledBackTransactions,"constraint_failures":$constraintFailures,"query_comparisons":$comparisons,"reopens":$reopens,"checkpoints":$checkpoints,"final_rows":${expected.values.sumOf { it.size }}}"""

        override fun close() {
            try { database.close() } finally { h2.close() }
        }
    }

    private val parameter = Regex(":([A-Za-z_][A-Za-z0-9_]*)")
    private fun jdbc(sql: String) = parameter.replace(sql, "?") to parameter.findAll(sql).map { it.groupValues[1] }.toList()
    private fun bind(statement: java.sql.PreparedStatement, sql: String, params: Map<String, Any>) {
        jdbc(sql).second.forEachIndexed { index, name -> statement.setObject(index + 1, params.getValue(name)) }
    }
    private fun write(connection: Connection, sql: String, params: Map<String, Any>): Int =
        connection.prepareStatement(jdbc(sql).first).use { statement ->
            bind(statement, sql, params)
            statement.executeUpdate()
        }
}
