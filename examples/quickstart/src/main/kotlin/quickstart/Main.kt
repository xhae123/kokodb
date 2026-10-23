package quickstart

import kokodb.DatabaseException
import kokodb.DbTable
import kokodb.Id
import kokodb.KoKoDB

@DbTable("files")
data class FileEntry(
    @Id val id: Int,
    val path: String,
    val kind: String,
)

fun sources(): List<FileEntry> = KoKoDB<FileEntry>(
    "SELECT * FROM files WHERE kind = :kind",
    mapOf("kind" to "source"),
)

fun replaceCatalog(entries: List<FileEntry>) {
    KoKoDB.transaction {
        KoKoDB("DELETE FROM files")
        for (entry in entries) {
            KoKoDB(
                "INSERT INTO files VALUES (:id, :path, :kind)",
                mapOf("id" to entry.id, "path" to entry.path, "kind" to entry.kind),
            )
        }
    }
}

fun main() {
    try {
        val original = listOf(
            FileEntry(1, "src/Main.kt", "source"),
            FileEntry(2, "app.conf", "resource"),
        )
        replaceCatalog(original)
        check(sources() == listOf(original.first()))
        println("Source files: ${sources().map { it.path }}")

        val failure = runCatching {
            replaceCatalog(listOf(
                FileEntry(3, "src/New.kt", "source"),
                FileEntry(3, "src/Duplicate.kt", "source"),
            ))
        }.exceptionOrNull()
        check(failure is DatabaseException && failure.message == "Duplicate primary key for column 'id'")
        check(KoKoDB<FileEntry>("SELECT * FROM files").sortedBy { it.id } == original)
        println("Duplicate id rejected; original catalog preserved.")

        val replacement = listOf(
            FileEntry(3, "src/New.kt", "source"),
            FileEntry(4, "new.conf", "resource"),
        )
        replaceCatalog(replacement)
        check(KoKoDB<FileEntry>("SELECT * FROM files").sortedBy { it.id } == replacement)
        check(sources() == listOf(replacement.first()))
        println("Replacement committed; source files: ${sources().map { it.path }}")
    } finally {
        KoKoDB.close()
    }
}
