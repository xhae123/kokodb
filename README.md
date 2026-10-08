# KoKoDB

Everything you need for a small SQL database, in one simple Kotlin library.

KoKoDB is an embedded relational database for Kotlin/JVM. Its primary query API is `KoKoDB<Model>(sql, params)`: write SQL and receive Kotlin objects from a shared database, without JDBC, a separate server, or a required repository layer.

The goal is a small, easy-to-adopt database with minimal dependencies and low resource overhead. Storage is currently memory-only; disk persistence is not implemented.

The problem we target is the deployment and resource cost of relational storage in small Kotlin/JVM applications. A JVM implementation can avoid separately packaged native database binaries and expose the execution, allocation, and recovery paths directly. That alone does not establish lower memory use or better performance: we measure artifact size, fresh-process startup, process RSS, and workload allocation before making those claims.

## Getting started

Declare the result model. KSP generates its table schema and mapper; the database discovers the generated definitions and prepares the tables automatically.

```kotlin
import kokodb.DbTable
import kokodb.Id
import kokodb.KoKoDB

@DbTable("users")
data class User(@Id val id: Int, val name: String)

KoKoDB.execute(
    "INSERT INTO users VALUES (:id, :name)",
    params = mapOf("id" to 1, "name" to "Koko"),
)

val users: List<User> = KoKoDB<User>(
    "SELECT id, name FROM users WHERE name = :name",
    params = mapOf("name" to "Koko"),
)

check(users == listOf(User(1, "Koko")))
```

`KoKoDB` is an object, and the call syntax invokes its query operator. Calls reuse the same database rather than constructing a new database. The type argument describes one result row; the returned value is always `List<Model>`.

No application database wrapper, service class, repository declaration, or manual mapper is required. Use the same `KoKoDB` API from different application functions.

## Query and model contracts

- `KoKoDB<Model>(sql, params)` accepts SELECT and returns freshly reconstructed, detached model objects. Empty results return an empty list.
- Typed results currently require an `@DbTable` model and the KoKoDB KSP processor. Ordinary unannotated DTOs, scalar result types, and arbitrary object mapping are not supported.
- Models must be public top-level non-generic data classes with public constructors and public non-null Int/String constructor properties. Computed properties are not stored.
- SELECT must use the model's table and include each stored property exactly once, or use `*`. Column order may differ from constructor order. Wrong tables, incomplete/duplicate projections, incompatible schemas, and invalid parameters fail even when no rows match.
- Typed partial projections, aliases, joins, aggregation, and sorting are not implemented. Use raw rows for supported partial projections.
- `@Id` optionally declares one caller-supplied Int/String primary key. Duplicate keys fail before INSERT or UPDATE changes any rows. A key is not required for typed queries.
- SQL identifiers are case-insensitive. String values and parameter names remain case-sensitive. Parameters bind Int/String values; null and implicit type conversion are unsupported.
- Input SQL and stored schemas are validated at execution time. Kotlin checks result assignment types, not SQL text.
- Mutating a returned model does not write to the database. There is no automatic dirty checking.

## Raw SQL

Raw SQL does not require a model declaration or KSP. Create schemas explicitly and receive detached Row values:

```kotlin
KoKoDB.execute("CREATE TABLE settings (name TEXT PRIMARY KEY, value INT)")
KoKoDB.execute(
    "INSERT INTO settings VALUES (:name, :value)",
    mapOf("name" to "count", "value" to 3),
)
val count = KoKoDB.query("SELECT value FROM settings WHERE name = :name", mapOf("name" to "count"))
    .single().getInt("value")
```

```kotlin
val updated = KoKoDB.execute(
    "UPDATE settings SET value = :value WHERE name = :name",
    mapOf("value" to 4, "name" to "count"),
)
val deleted = KoKoDB.execute("DELETE FROM settings WHERE name = :name", mapOf("name" to "count"))
check(updated == 1 && deleted == 1)
```

`execute()` returns 0 for CREATE TABLE, 1 for INSERT, and the number of matched rows for UPDATE/DELETE. UPDATE counts matches even when their values stay equal. Omitting WHERE updates or deletes all rows. `query()` returns `List<Row>`; getters reject missing or mistyped columns. Typed and raw calls use the same catalog and engine.

Each write statement validates its entire candidate state before publishing changes. Invalid columns, types, parameters, duplicate assignments, or primary-key collisions leave existing rows unchanged, including failures involving multiple matching rows. This is statement atomicity; several calls do not commit or roll back together.

## Shared database lifetime

- The first query or execute call opens the shared memory database lazily. Its model definitions come from the calling thread's context class loader, falling back to the library class loader.
- `KoKoDB.openInMemory(classLoader)` can open it explicitly before use. Opening an already-active database fails rather than discarding its rows.
- `KoKoDB.close()` discards the shared memory store. Calls then fail until `openInMemory()` explicitly opens a fresh store. Repeated closes are harmless, and previously returned results remain detached.
- Each shared API call and explicit transaction callback is serialized. Concurrent callers wait for the active operation; separate calls outside a transaction do not commit together.
- The singleton is shared within its loaded JVM class loader. It is not shared across processes or persisted across application restarts.

```kotlin
KoKoDB.close()
KoKoDB.openInMemory() // a fresh, empty store with generated model tables
```

For isolated databases or tests, use independent instances. These are intended for sequential use and do not share data with KoKoDB:

```kotlin
import kokodb.Database

val db = Database.inMemory()
val users = db.queryModels(User::class.java, "SELECT * FROM users")
```

## Memory transactions

```kotlin
KoKoDB.transaction {
    execute("UPDATE users SET name = :name WHERE id = :id", mapOf("name" to "Updated", "id" to 1))
    execute("INSERT INTO users VALUES (2, 'Another')")
    val pending: List<User> = query<User>("SELECT * FROM users")
    check(pending.size == 2)
}
```

- The callback reads its own writes. Returning normally publishes all its changes together, including newly created tables. A thrown exception discards them all.
- A SQL or mapping error makes the scope rollback-only even if caught inside the callback. Further operations and commit fail; earlier writes are discarded.
- Global KoKoDB calls on the owner thread join the scope. Other shared callers wait until the entire callback finishes.
- Use `query<Model>()` for typed results and `queryRows()` for raw rows inside the scope. Independent databases support `db.transaction { ... }` too.
- Scopes are synchronous and owned by one thread. Captured handles reject another thread or reuse after completion. Nested scopes and shared close/open inside a scope fail and abort it if caught.
- Transaction callbacks are never automatically retried. External side effects are not rolled back; do not wait for another thread to call KoKoDB while holding its transaction lock.
- Memory commit is not durable storage. A process exit or close still discards all memory data.

## Build setup

Requires JDK 17. The Gradle Wrapper downloads the pinned Gradle distribution on first use.

```sh
./gradlew build :sample:run
```

Artifacts are not published yet. The `sample` module demonstrates typed SQL calls from separate functions and the required consumer setup inside this checkout:

```kotlin
plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":"))
    ksp(project(":processor"))
}
```

Enable KSP in modules declaring annotated models. The processor runs at build time and is not a runtime dependency. Raw SQL consumers only need the runtime library.

## Architecture

Reproduce the current resource baseline with the [benchmark guide](benchmarks/README.md). Benchmarks are a separate development module and add no runtime-library dependency.

```mermaid
sequenceDiagram
    participant App as Kotlin application
    participant Shared as KoKoDB singleton
    participant DB as Shared Database
    participant Parser as SQL parser
    participant Engine as Relational executor
    participant Adapter as Generated model mapper
    App->>Shared: KoKoDB<User>(sql, params)
    Shared->>Shared: Serialize call and reuse database
    Shared->>DB: queryModels(User class, sql, params)
    DB->>Parser: Parse SELECT
    DB->>DB: Validate model table and complete projection
    DB->>Engine: Validate stored schema and execute SELECT
    Engine->>Adapter: Map each result row
    Adapter-->>Engine: Detached User
    Engine-->>DB: List<User>
    DB-->>Shared: List<User>
    Shared-->>App: List<User>
```

The parser builds a shared query representation. The executor validates schemas, resolves parameters, and scans or replaces row snapshots in the in-memory catalog. Generated model mapping uses constructor calls rather than runtime constructor introspection. Mapping runs during the scan without building an intermediate List<Row>.

## Supported SQL and limitations

- CREATE TABLE with non-null INT/TEXT columns and optional column-level PRIMARY KEY.
- Single-row INSERT INTO ... VALUES (...).
- SELECT with `*` or named columns and an optional single equality condition.
- UPDATE with one or more SET assignments to literals or named parameters, and optional single equality WHERE.
- DELETE FROM with optional single equality WHERE. Without WHERE, UPDATE/DELETE affect the entire table.
- ASCII identifiers (`[A-Za-z_][A-Za-z0-9_]*`), signed 32-bit INT literals, TEXT literals escaped with `''`, and `:name` value parameters.
- One statement per call, with an optional trailing semicolon. Quoted identifiers and NULL are unsupported. Result order is unspecified.
- DatabaseException reports execution errors; SqlSyntaxException.position reports a zero-based offset in the SQL string.

Disk persistence, indexes, foreign keys, generated/composite keys, joins, and general-purpose result DTO mapping are not implemented. Lookups and primary-key checks currently scan rows. UPDATE/DELETE build replacement row lists; writes may copy table-sized state.

The [transaction and persistence design](docs/transactions-and-persistence.md) specifies future transaction boundaries, binary snapshots, a table after-image WAL, durable commit ordering, checkpoints, and restart recovery. Memory transactions are implemented; persistent APIs and storage mechanisms remain planned.

Tests cover shared lifetime and reuse, typed SQL mapping and failures, parameter binding, detached results, concurrent facade calls, independent databases, compiler checks, SQL execution, primary-key constraints, generated model mapping, atomic UPDATE/DELETE failures, and randomized CRUD against a reference map. GitHub Actions runs the build for pull requests and pushes to main.
