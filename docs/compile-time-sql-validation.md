# Compile-time SQL Validation

With the optional SQL compiler plugin enabled, malformed static SQL fails the Kotlin/JVM build at its call site. The direct SQL API stays the same:

```kotlin
KoKoDB<User>("SELECT * FROM users")
KoKoDB("INSERT INTO users VALUES (1, 'Koko')")
KoKoDB<User>("SELEC * FROM users") // compilation error
```

The checker validates syntax and the query/write category. It does not prove that a query will execute successfully against a particular database. Runtime validation remains mandatory.

## Why a compiler plugin is separate from KSP

KokoProcessor reads `@DbTable` declarations and generates model schemas, adapters, and service registration. [KSP does not expose expressions or function bodies](https://kotlinlang.org/docs/ksp-overview.html), so it cannot inspect ordinary SQL arguments at direct call sites.

The separate `compiler-plugin` module runs an IR inspection during Kotlin compilation. It recognizes resolved methods on KoKoDB, Database, and Transaction, including import aliases and named/reordered arguments. It reads static SQL and passes it to the same Lexer/Parser source files used by the runtime. It does not rewrite calls, execute user code, open a database, or scan source text with regular expressions.

The plugin JAR compiles the shared parser and its supporting types from their existing source files. There is no second SQL grammar and no new public parser API. Compiler dependencies are `compileOnly`; the runtime library has no dependency on the compiler plugin or compiler APIs.

## External approaches and the selected boundary

| Project | Approach | Design consequence |
|---|---|---|
| [SQLDelight](https://sqldelight.github.io/sqldelight/2.2.1/jvm_sqlite/) | SQL schemas and labeled queries in `.sq` files generate Kotlin APIs during the build. | A useful schema-aware design, but moving SQL out of direct calls changes our consumer API. |
| [Room](https://developer.android.com/training/data-storage/room/accessing-data) | SQL in `@Query` DAO methods is validated at compile time; implementations are generated. | Annotation arguments are processor-visible, but requiring DAOs would add a layer users do not need here. |
| [SQLx](https://docs.rs/sqlx/latest/sqlx/macro.query.html) | Rust macros inspect static query text with a database schema or cached offline metadata. Dynamic expressions are outside the macro guarantee. | Make static-input coverage explicit and distinguish grammar checks from schema-dependent guarantees. |
| [sqlx4k](https://github.com/smyrgeorge/sqlx4k#sql-syntax-validation-compile-time) | KSP validates `@Query` SQL with JSqlParser; optional migration-based schema validation is separate. | Keep syntax and schema validation distinct. Reuse our narrower runtime grammar rather than accepting a different parser's dialect. |

This initial implementation keeps SQL in ordinary Kotlin calls, requires no live build-time database, and checks only what the existing grammar and resolved method category can establish.

## Installation in this checkout

The sample already enables the checker. Use a compiler plugin built from the same KoKoDB revision/version as the runtime so their shared grammar agrees. The compiler plugin is separate from the KSP processor and must be enabled in every consumer compilation that should check SQL. Requires the qualified **Kotlin/JVM 2.3.20, KSP 2.3.10, JDK 17** toolchain; compiler-plugin compatibility with other Kotlin versions or targets is not promised. See the [pinned Kotlin compiler-plugin guide](https://github.com/JetBrains/kotlin/blob/v2.3.20/docs/compiler-plugins/basics.md) for the underlying extension mechanism.

```kotlin
val kokoSqlCompilerPlugin by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(project(":"))
    ksp(project(":processor")) // needed only for annotated models
    kokoSqlCompilerPlugin(project(":compiler-plugin"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    inputs.files(kokoSqlCompilerPlugin).withPropertyName("kokoSqlCompilerPlugin")
        .withPathSensitivity(PathSensitivity.NONE)
    compilerOptions.freeCompilerArgs.add(kokoSqlCompilerPlugin.elements.map { files ->
        "-Xplugin=${files.single().asFile.absolutePath}"
    })
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(kokoSqlCompilerPlugin)
}
```

The plugin JAR is an explicit compiler task input so changes to the checker invalidate an otherwise up-to-date compilation. KSP reads compiler arguments before compilation, so it also needs the compiler-plugin artifact as a task dependency. In a command-line compilation, pass `-Xplugin=/path/to/kokodb-sql-compiler-plugin.jar`. Artifacts are not published yet; there is no published Gradle plugin or IntelliJ inspection in this increment. Compiler errors appear in build diagnostics, not as a promised live editor inspection.

Raw SQL does not require KSP or models. Consumers can also omit the SQL compiler plugin and retain runtime-only SQL validation.

## Checked inputs and diagnostic locations

- Direct ordinary and raw multiline string literals, escaped strings, `const val` strings, string concatenation, interpolation with fully known constants, and literal/constant `trimIndent()` expressions.
- Writes through `KoKoDB(sql, params)`, `db(sql, params)`, and `scope(sql, params)`; typed/shared/raw queries through the existing query methods.
- CREATE TABLE, INSERT, SELECT, UPDATE, and DELETE, including named SQL parameters and the optional trailing semicolon, within the runtime's supported grammar.
- Malformed strings/tokens, unsupported SQL clauses, extra statements, out-of-range INT literals, SELECT in write calls, and writes in query calls cause compilation errors.

The diagnostic points to the Kotlin SQL argument and includes the parser's zero-based position in the decoded SQL string. It does not claim an exact character mapping inside escaped, concatenated, or transformed Kotlin strings. Multiple bad SQL calls can produce multiple diagnostics.

## Dynamic SQL and unchecked semantics

Dynamic SQL and unsupported constant forms produce a warning at a recognized call and remain executable:

```kotlin
fun users(sql: String): List<User> = KoKoDB<User>(sql)
```

Constant evaluation has a bounded recursion budget; expressions beyond it warn rather than being partially checked. Ordinary local/property variables, custom getters/functions, and transformations other than the supported `trimIndent()` form are not generally evaluated. A warning means the call has runtime-only SQL validation; a build configured with warnings as errors can reject it. Unknown fragments are not partially validated as if they were complete SQL.

Java source calls, invocation through callable references, and SQL passed through user-defined wrappers are not inspected at their indirect call sites. A wrapper's direct KoKoDB call can warn about its dynamic argument. Import aliases and variables referencing a database handle still resolve to recognized methods and are checked.

Syntax success does not establish table/column existence, stored-model projection compatibility, bound parameter keys/types, primary-key uniqueness, or runtime-created schema validity. Those checks still run in the engine. The plugin does not replace parameter binding or provide an SQL-injection guarantee.

## Verification

Compiler integration tests invoke the actual Kotlin compiler with the plugin loaded. They cover successful statements and handle APIs, aliases/named arguments, constants/concatenation/multiline text, syntax failures and source locations, query/write mismatches, unrelated APIs, dynamic warnings, and optional-plugin behavior.

After `./gradlew build`, run `python3 compiler-plugin/check_gradle.py`. It creates an isolated Gradle consumer using the sample's plugin/KSP wiring, confirms malformed SQL fails `compileKotlin` at the argument location, then fixes the SQL and confirms compilation and generated model mapping succeed. It also verifies unchanged compilation is reused and changed plugin bytes invalidate it. Linux/macOS CI runs this check alongside the existing runtime, persistence, and sample fixtures. It does not modify the checkout's source files.
