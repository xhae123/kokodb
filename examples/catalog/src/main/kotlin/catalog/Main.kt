package catalog

import java.nio.file.Files
import java.nio.file.Path
import kokodb.DbTable
import kokodb.Id

@DbTable("files")
data class FileEntry(@Id val id: Int, val path: String, val kind: String)

fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: catalog <kokodb|h2> <input.tsv> <report.tsv>" }
    val entries = Files.readAllLines(Path.of(args[1])).let { lines ->
        require(lines.firstOrNull() == "id\tpath\tkind") { "Expected id, path and kind TSV columns" }
        lines.drop(1).map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "Expected three fields per entry" }
            FileEntry(fields[0].toInt(), fields[1], fields[2])
        }
    }
    val cleaned = when (args[0]) {
        "kokodb" -> KokoCatalog.clean(entries)
        "h2" -> JdbcCatalog.clean(entries)
        else -> error("Unknown engine: ${args[0]}")
    }
    val report = buildString {
        append("id\tpath\tkind\n")
        for (entry in cleaned.sortedBy { it.id }) {
            append("${entry.id}\t${entry.path}\t${entry.kind}\n")
        }
    }
    Files.writeString(Path.of(args[2]), report)
    println("Wrote ${cleaned.size} catalog entries from ${entries.size} input entries")
}
