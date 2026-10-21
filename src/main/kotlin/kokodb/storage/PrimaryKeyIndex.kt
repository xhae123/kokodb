package kokodb.storage

/** Immutable AVL nodes let appended snapshots retain earlier keys without copying a whole index. */
internal class PrimaryKeyIndex private constructor(private val root: Node?) {
    fun find(value: Value): Int {
        var node = root
        while (node != null) {
            val order = compare(value, node.value)
            node = when {
                order < 0 -> node.left
                order > 0 -> node.right
                else -> return node.position
            }
        }
        return -1
    }

    fun added(value: Value, position: Int): PrimaryKeyIndex = PrimaryKeyIndex(insert(root, value, position))

    private class Node(val value: Value, val position: Int, val left: Node?, val right: Node?) {
        val height = maxOf(left.height(), right.height()) + 1
    }

    companion object {
        fun from(rows: List<List<Value>>, key: Int): PrimaryKeyIndex {
            val positions = rows.indices.toMutableList()
            positions.sortWith { a, b -> compare(rows[a][key], rows[b][key]) }
            fun build(start: Int, end: Int): Node? {
                if (start == end) return null
                val middle = (start + end) ushr 1
                val position = positions[middle]
                return Node(rows[position][key], position, build(start, middle), build(middle + 1, end))
            }
            return PrimaryKeyIndex(build(0, positions.size))
        }

        private fun insert(node: Node?, value: Value, position: Int): Node {
            if (node == null) return Node(value, position, null, null)
            val order = compare(value, node.value)
            check(order != 0) { "Duplicate key in a validated append" }
            val next = if (order < 0) Node(node.value, node.position, insert(node.left, value, position), node.right)
                else Node(node.value, node.position, node.left, insert(node.right, value, position))
            val balance = next.left.height() - next.right.height()
            return when {
                balance > 1 -> {
                    val left = checkNotNull(next.left)
                    rotateRight(if (left.left.height() < left.right.height())
                        Node(next.value, next.position, rotateLeft(left), next.right) else next)
                }
                balance < -1 -> {
                    val right = checkNotNull(next.right)
                    rotateLeft(if (right.right.height() < right.left.height())
                        Node(next.value, next.position, next.left, rotateRight(right)) else next)
                }
                else -> next
            }
        }

        private fun rotateLeft(node: Node): Node {
            val right = checkNotNull(node.right)
            return Node(right.value, right.position, Node(node.value, node.position, node.left, right.left), right.right)
        }

        private fun rotateRight(node: Node): Node {
            val left = checkNotNull(node.left)
            return Node(left.value, left.position, left.left, Node(node.value, node.position, left.right, node.right))
        }

        private fun Node?.height(): Int = this?.height ?: 0

        private fun compare(first: Value, second: Value): Int = when (first) {
            is Value.IntValue -> first.value.compareTo((second as Value.IntValue).value)
            is Value.TextValue -> first.value.compareTo((second as Value.TextValue).value)
        }
    }
}
