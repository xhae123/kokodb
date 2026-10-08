package kokodb

/** A SQL syntax, name resolution, binding, or type error reported by the database. */
open class DatabaseException(message: String) : RuntimeException(message)

/** A WAL write/force failed after writing began. Reopen to recover; the transaction may have committed. */
class CommitOutcomeUnknownException internal constructor(cause: Throwable) :
    DatabaseException("Commit outcome is unknown; close and reopen the database") {
    init { initCause(cause) }
}

/** [position] is the zero-based character offset in the original SQL string. */
class SqlSyntaxException(message: String, val position: Int) :
    DatabaseException("$message at position $position")
