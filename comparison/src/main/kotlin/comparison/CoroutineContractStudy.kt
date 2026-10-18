package comparison

import kokodb.Database
import kokodb.KoKoDB
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Controlled contract diagnostics; injected stalls are not application latency measurements. */
object CoroutineContractStudy {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        for (engine in listOf("kokodb", "h2")) {
            for (callers in listOf(1, 10, 100, 1000)) {
                for (adapted in listOf(false, true)) admission(engine, callers, adapted)
            }
        }
        cancellationBeforeStart()
        cancellationAfterCommit()
        eagerFlow()
    }

    private suspend fun admission(engine: String, callers: Int, adapted: Boolean) = coroutineScope {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { application ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
                Engine(engine).use { database ->
                    val started = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val entered = CountDownLatch(minOf(2, callers))
                    val occupied = AtomicInteger()
                    val executed = AtomicInteger()
                    val mutex = Mutex()
                    fun submit(id: Int) = async(application) {
                            entered.countDown()
                            val operation = {
                                database.read {
                                    if (id == 0) {
                                        started.countDown()
                                        check(release.await(10, TimeUnit.SECONDS)) { "Controlled stall timed out" }
                                    }
                                }
                                executed.incrementAndGet()
                            }
                            if (adapted) mutex.withLock { withContext(worker) { operation() } }
                            else {
                                occupied.incrementAndGet()
                                try { operation() } finally { occupied.decrementAndGet() }
                            }
                    }
                    val first = submit(0)
                    check(started.await(5, TimeUnit.SECONDS))
                    val jobs = listOf(first) + (1 until callers).map { submit(it) }
                    try {
                        check(entered.await(5, TimeUnit.SECONDS))
                        val heartbeat = async(application) { true }
                        val progressed = withTimeoutOrNull(100) { heartbeat.await() } ?: false
                        val occupiedAtStall = occupied.get()
                        release.countDown()
                        jobs.awaitAll()
                        check(heartbeat.await())
                        check(executed.get() == callers)
                        if (adapted) check(progressed && occupiedAtStall == 0)
                        if (!adapted && callers > 1) check(!progressed && occupiedAtStall == 2)
                        println("""{"case":"admission","engine":"$engine","callers":$callers,"external_adapter":$adapted,"heartbeat_within_injected_100ms":$progressed,"app_threads_inside_blocking_call":$occupiedAtStall,"completed":${executed.get()}}""")
                    } finally {
                        release.countDown()
                    }
                }
            }
        }
    }

    private suspend fun cancellationBeforeStart() = coroutineScope {
        val mutex = Mutex(locked = true)
        var executed = false
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { mutex.withLock { executed = true } }
        waiting.cancelAndJoin()
        mutex.unlock()
        check(!executed)
        println("""{"case":"cancel_before_mutex_admission","operation_executed":false}""")
    }

    private suspend fun cancellationAfterCommit() = coroutineScope {
        val directory = Files.createTempDirectory("kokodb-cancel-study-")
        val path = directory.resolve("study.koko")
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val committed = CountDownLatch(1)
            val deliver = CountDownLatch(1)
            val database = Database.open(path)
            try {
                database("CREATE TABLE receipt (id INT PRIMARY KEY)")
                val operation = async {
                    withContext(worker) {
                        database("INSERT INTO receipt VALUES (1)")
                        committed.countDown()
                        check(deliver.await(10, TimeUnit.SECONDS))
                        "acknowledged"
                    }
                }
                yield()
                try {
                    check(committed.await(5, TimeUnit.SECONDS))
                    operation.cancel()
                    deliver.countDown()
                    operation.join()
                    check(operation.isCancelled)
                } finally { deliver.countDown() }
            } finally { database.close() }
        }
        Database.open(path).use { check(it.query("SELECT * FROM receipt").single().getInt("id") == 1) }
        directory.toFile().deleteRecursively()
        println("""{"case":"cancel_after_durable_commit","caller_cancelled":true,"row_survives_reopen":true}""")
    }

    private suspend fun eagerFlow() {
        Database.inMemory().use { database ->
            database.transaction {
                repeat(10000) { id -> this("INSERT INTO benchmark_users VALUES (:id, :name)", mapOf("id" to id, "name" to "user$id")) }
            }
            val materialized = database.queryModels(BenchmarkUser::class.java, "SELECT * FROM benchmark_users")
            val consumed = materialized.asFlow().take(1).toList()
            check(materialized.size == 10000 && consumed.size == 1)
            println("""{"case":"flow_of_materialized_results","constructed_models":${materialized.size},"consumed_models":${consumed.size},"true_snapshot_streaming":false}""")
        }
    }

    private class Engine(private val engine: String) : AutoCloseable {
        private val h2 = if (engine == "h2") DriverManager.getConnection("jdbc:h2:mem:admission", "sa", "") else null
        private val gate = Any()
        init {
            if (h2 == null) {
                KoKoDB.openInMemory()
                KoKoDB("INSERT INTO benchmark_users VALUES (1, 'user')")
            } else h2.createStatement().use {
                it.execute("CREATE TABLE benchmark_users (id INT PRIMARY KEY, name VARCHAR NOT NULL)")
                it.executeUpdate("INSERT INTO benchmark_users VALUES (1, 'user')")
            }
        }
        fun read(before: () -> Unit) {
            if (h2 == null) KoKoDB.transaction {
                before()
                check(KoKoDB<BenchmarkUser>("SELECT * FROM benchmark_users").size == 1)
            } else synchronized(gate) {
                before()
                h2.prepareStatement("SELECT * FROM benchmark_users").use { statement ->
                    statement.executeQuery().use { result -> check(result.next()); check(result.getInt("id") == 1); check(!result.next()) }
                }
            }
        }
        override fun close() { if (h2 == null) KoKoDB.close() else h2.close() }
    }
}
