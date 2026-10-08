package kokodb.mapping

/**
 * Generated-mapper view of one stored row, indexed in model schema order.
 * Read only during ModelAdapter.readValues; retaining this view is unsupported.
 */
interface ModelValues {
    fun getInt(index: Int): Int
    fun getString(index: Int): String
}
