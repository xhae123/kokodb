# Kotlin Catalog CLI

A small working-dataset example: read a file manifest, import it into a memory database, rename one path, remove generated entries, and export the remaining catalog. Both implementations return `List<FileEntry>` to the same Kotlin code for sorting and output.

## Run

From the repository root with JDK 17:

```sh
./gradlew :catalog-example:run --args="kokodb fixtures/files.tsv build/report.tsv"
./gradlew :catalog-example:run --args="h2 fixtures/files.tsv build/report-h2.tsv"
```

Outputs are in `examples/catalog/build/`. The module's working directory is `examples/catalog`. The fixture has 1,000 entries with unique INT ids, string paths and string kinds; 200 are generated entries. The report contains 800 entries, sorted by id, with id 0 renamed to `src/renamed.kt`. Sorting happens in Kotlin because KoKoDB has no SQL ORDER BY.

The CLI accepts three arguments: engine (`kokodb` or `h2`), input TSV and output TSV. Input must have the header `id<TAB>path<TAB>kind`, three non-null fields per row, unique Int ids and id 0 for the rename. It is intentionally a narrow example; it does not scan a repository, escape embedded tabs/newlines, provide a general import framework or persist the database between invocations. Use a new output path when checking failures; an existing report is not deleted by the application before an error.

## Equivalent database work

- [KoKoDB](src/main/kotlin/catalog/KokoCatalog.kt): the annotated [FileEntry](src/main/kotlin/catalog/Main.kt) provides generated schema/mapping; ordinary parameterized SQL INSERTs execute inside one shared transaction, followed by UPDATE and DELETE. The final SELECT creates detached Kotlin models.
- [H2](src/main/kotlin/catalog/JdbcCatalog.kt): matching non-null schema and primary key, one transaction with a prepared INSERT batch, prepared UPDATE/DELETE, commit and a prepared SELECT. JDBC columns are passed directly to the same Kotlin constructor. Errors trigger rollback; resources close through `use`.
- Both consume the same input, change the same data, construct the same model type, sort, write the same UTF-8 bytes and close before process exit. Both are sequential and in memory. H2 is a development/example dependency, not a core-library dependency.

KSP removes schema/mapper boilerplate from this KoKoDB consumer but requires build configuration. Inspect both source files to judge the integration tradeoff; source-line counts are not treated as productivity measurements.

## Verify and measure

```sh
./gradlew :catalog-example:installDist
python3 examples/catalog/measure.py --output .local/catalog-measurement
```

The runner requires Python 3.9+, Linux/macOS and a Java 17 executable on PATH (`--java /path/to/java` overrides it). Output directories must be new. It creates deterministic input and an independent expected report, then runs 20 paired rounds with shuffled engine order. Each child receives only the common app/Kotlin/annotations JARs plus the selected engine; no JMH, compiler, KSP processor or competing engine is on its classpath.

Wall time covers JVM launch through exit, including parsing, SQL, output and shutdown; it excludes Gradle compilation and Python's verification. Peak RSS includes the whole JVM process. Every successful report is checked byte-for-byte and hashed. Both engines must also reject a duplicate id late in the input without creating a report. `--runs 1` is a functional smoke check, not performance evidence. `--rows` permits other input sizes, which are different workloads.

All observations, pair/order, stdout/stderr, report hashes, source/JAR hashes, classpaths, JVM flags and summary ranges are retained. New JVMs use ordinary OS caches, not cold disks. Report writes are normal file writes without a forced-durability contract. There is no file-database, concurrency or customer workload claim. [Recorded study](../../docs/baselines/2026-10-23-catalog-cli/README.md)
