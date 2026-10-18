package kokodb.storage

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RowBlocksTest {
    private fun row(id: Int): List<Value> = listOf(Value.IntValue(id))

    @Test
    fun `append update and removal preserve values across every block boundary`() {
        for (size in listOf(0, 1, 127, 128, 129, 255, 256, 257, 1000)) {
            val values = List(size) { row(it) }
            val original = RowBlocks.from(values)
            assertEquals<List<List<Value>>>(values + listOf(row(-1)), original.appended(row(-1)))
            for (index in values.indices) {
                val updated = values.toMutableList().also { it[index] = row(-1) }
                assertEquals<List<List<Value>>>(updated, original.updated(index, row(-1)), "$size/$index update")
                val removed = values.toMutableList().also { it.removeAt(index) }
                assertEquals<List<List<Value>>>(removed, original.removed(index), "$size/$index remove")
                assertEquals<List<List<Value>>>(values, original, "prior snapshot changed")
            }
        }
    }

    @Test
    fun `random operations agree with a mutable reference without changing old snapshots`() {
        val random = Random(291018)
        val expected = mutableListOf<List<Value>>()
        var actual = RowBlocks.from(emptyList())
        repeat(5000) { step ->
            val before = actual
            val expectedBefore = expected.toList()
            when (if (expected.isEmpty()) 0 else random.nextInt(4)) {
                0, 1 -> { expected.add(row(step)); actual = actual.appended(row(step)) }
                2 -> {
                    val index = random.nextInt(expected.size)
                    expected[index] = row(-step)
                    actual = actual.updated(index, row(-step))
                }
                else -> {
                    val index = random.nextInt(expected.size)
                    expected.removeAt(index)
                    actual = actual.removed(index)
                }
            }
            assertEquals<List<List<Value>>>(expected, actual, "step $step")
            assertEquals<List<List<Value>>>(expectedBefore, before, "old snapshot at $step")
        }
    }

    @Test
    fun `bulk mapping preserves unchanged snapshots and cannot publish a partial failure`() {
        val expected = List(300) { row(it) }
        val original = RowBlocks.from(expected)
        assertSame(original, original.mapRows { it.toList() })
        val mapped = original.mapRows { values ->
            val id = (values[0] as Value.IntValue).value
            if (id % 2 == 0) row(-id) else values
        }
        assertEquals<List<List<Value>>>(List(300) { row(if (it % 2 == 0) -it else it) }, mapped)
        assertFailsWith<IllegalStateException> {
            original.mapRows { values ->
                val id = (values[0] as Value.IntValue).value
                if (id == 150) error("candidate failure")
                row(-id)
            }
        }
        assertEquals<List<List<Value>>>(expected, original)
    }

    @Test
    fun `invalid indices fail and input list mutation cannot alter stored blocks`() {
        val input = mutableListOf(row(1), row(2))
        val rows = RowBlocks.from(input)
        input[0] = row(9)
        input.clear()
        assertEquals<List<List<Value>>>(listOf(row(1), row(2)), rows)
        for (index in listOf(-1, 2, Int.MAX_VALUE)) {
            assertFailsWith<IndexOutOfBoundsException> { rows[index] }
            assertFailsWith<IndexOutOfBoundsException> { rows.updated(index, row(3)) }
            assertFailsWith<IndexOutOfBoundsException> { rows.removed(index) }
        }
        assertSame(rows, RowBlocks.from(rows))
    }
}
