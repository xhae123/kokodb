package kokodb

/** A SQL syntax, name resolution, binding, or type error reported by the database. */
open class DatabaseException(message: String) : RuntimeException(message)

/** [position] is the zero-based character offset in the original SQL string. */
class SqlSyntaxException(message: String, val position: Int) :
    DatabaseException("$message at position $position")
