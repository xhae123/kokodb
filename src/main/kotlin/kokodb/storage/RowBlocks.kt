package kokodb.storage

import kokodb.DatabaseException
import java.util.RandomAccess

internal class RowBlocks private constructor(
    private val blocks: Array<Array<List<Value>>>,
    override val size: Int,
) : AbstractList<List<Value>>(), RandomAccess {
    override fun get(index: Int): List<Value> {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Row index $index, size $size")
        return blocks[index / BLOCK_SIZE][index % BLOCK_SIZE]
    }

    override fun iterator(): Iterator<List<Value>> = object : Iterator<List<Value>> {
        private var block = 0
        private var offset = 0

        override fun hasNext(): Boolean = block < blocks.size

        override fun next(): List<Value> {
            if (!hasNext()) throw NoSuchElementException()
            val values = blocks[block]
            val row = values[offset++]
            if (offset == values.size) {
                block++
                offset = 0
            }
            return row
        }
    }

    fun appended(row: List<Value>): RowBlocks {
        if (size == Int.MAX_VALUE) throw DatabaseException("Table row limit reached")
        val next = if (size % BLOCK_SIZE == 0) {
            Array(blocks.size + 1) { if (it == blocks.size) arrayOf(row) else blocks[it] }
        } else blocks.copyOf().also { next ->
            val last = blocks.last()
            next[next.lastIndex] = Array(last.size + 1) { if (it == last.size) row else last[it] }
        }
        return RowBlocks(next, size + 1)
    }

    fun updated(index: Int, row: List<Value>): RowBlocks {
        if (get(index) == row) return this
        val block = index / BLOCK_SIZE
        val replacement = blocks[block].copyOf().also { it[index % BLOCK_SIZE] = row }
        return RowBlocks(blocks.copyOf().also { it[block] = replacement }, size)
    }

    fun removed(index: Int): RowBlocks {
        get(index)
        val nextSize = size - 1
        val next = Array(blockCount(nextSize)) { block ->
            if (block < index / BLOCK_SIZE) blocks[block]
            else {
                val start = block * BLOCK_SIZE
                Array(minOf(BLOCK_SIZE, nextSize - start)) { offset ->
                    val position = start + offset
                    get(if (position >= index) position + 1 else position)
                }
            }
        }
        return RowBlocks(next, nextSize)
    }

    fun mapRows(transform: (List<Value>) -> List<Value>): RowBlocks {
        var changed = false
        val next = Array(blocks.size) { block ->
            val old = blocks[block]
            var replacement: Array<List<Value>>? = null
            for (index in old.indices) {
                val row = transform(old[index])
                if (row != old[index]) {
                    if (replacement == null) replacement = old.copyOf()
                    replacement[index] = row
                }
            }
            if (replacement == null) old else replacement.also { changed = true }
        }
        return if (changed) RowBlocks(next, size) else this
    }

    fun spliced(prefix: Int, removed: Int, inserted: List<List<Value>>): RowBlocks {
        require(prefix in 0..size && removed in 0..(size - prefix))
        val nextSize = size.toLong() - removed + inserted.size
        if (nextSize > Int.MAX_VALUE) throw DatabaseException("Table row limit reached")
        if (removed == 0 && inserted.isEmpty()) return this
        val next = Array(blockCount(nextSize.toInt())) { block ->
            val start = block * BLOCK_SIZE
            val length = minOf(BLOCK_SIZE, nextSize.toInt() - start)
            val unchanged = start + length <= prefix || (removed == inserted.size && start >= prefix + inserted.size)
            if (unchanged && blocks[block].size == length) {
                blocks[block]
            } else Array(length) { offset ->
                val position = start + offset
                when {
                    position < prefix -> get(position)
                    position - prefix < inserted.size -> inserted[position - prefix]
                    else -> get(position - inserted.size + removed)
                }
            }
        }
        return RowBlocks(next, nextSize.toInt())
    }

    companion object {
        private const val BLOCK_SIZE = 128
        private val empty = RowBlocks(emptyArray(), 0)

        fun from(rows: List<List<Value>>): RowBlocks {
            if (rows is RowBlocks) return rows
            if (rows.isEmpty()) return empty
            val iterator = rows.iterator()
            return RowBlocks(Array(blockCount(rows.size)) { block ->
                Array(minOf(BLOCK_SIZE, rows.size - block * BLOCK_SIZE)) { iterator.next() }
            }, rows.size)
        }

        private fun blockCount(size: Int): Int = if (size == 0) 0 else (size - 1) / BLOCK_SIZE + 1
    }
}
