package kokodb

import kokodb.storage.DataType
import kokodb.storage.Value

/** A non-null column whose type is shared by insertion, equality predicates, and result access. */
class Column<T : Any> internal constructor(
    internal val table: Table,
    val name: String,
    internal val type: DataType,
    internal val primaryKey: Boolean,
    internal val encode: (T) -> Value,
    private val decode: (Value) -> T,
) {
    infix fun eq(value: T): Condition = Condition(this, encode(value))

    internal fun read(value: Value): T {
        if (value.type != type) throw DatabaseException("Column '$name' expects $type, got ${value.type}")
        return decode(value)
    }
}

/** An equality predicate built by [Column.eq]. */
class Condition internal constructor(internal val column: Column<*>, internal val value: Value)
