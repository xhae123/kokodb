package catalog

import kokodb.KoKoDB

internal object KokoCatalog {
    fun clean(entries: List<FileEntry>): List<FileEntry> {
        try {
            KoKoDB.transaction {
                for (entry in entries) {
                    check(KoKoDB(
                        "INSERT INTO files VALUES (:id, :path, :kind)",
                        mapOf("id" to entry.id, "path" to entry.path, "kind" to entry.kind),
                    ) == 1)
                }
                check(KoKoDB("UPDATE files SET path = 'src/renamed.kt' WHERE id = 0") == 1)
                KoKoDB("DELETE FROM files WHERE kind = 'generated'")
            }
            return KoKoDB<FileEntry>("SELECT * FROM files")
        } finally {
            KoKoDB.close()
        }
    }
}
