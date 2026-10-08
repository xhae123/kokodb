package kokodb

import kokodb.execution.Executor
import kokodb.mapping.ModelAdapter
import kokodb.mapping.ModelColumn
import kokodb.mapping.ModelValues
import kokodb.sql.Parser
import kokodb.storage.Catalog
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DbTable("ordered_models")
data class OrderedModel(var displayName: String, @Id val id: Int)

class ModelMappingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `generated mapping uses schema order independently of SELECT column order`() {
        Database.inMemory().use { db ->
            db("INSERT INTO ordered_models VALUES ('Original', 7)")
            val sql = "SELECT ID, DISPLAYNAME FROM ORDERED_MODELS WHERE id = :id"
            val first = db.queryModels(OrderedModel::class.java, sql, mapOf("id" to 7)).single()
            assertEquals(OrderedModel("Original", 7), first)
            first.displayName = "Detached"
            assertEquals(OrderedModel("Original", 7), db.queryModels(OrderedModel::class.java, sql, mapOf("id" to 7)).single())
            assertTrue(db.queryModels(OrderedModel::class.java, sql, mapOf("id" to 8)).isEmpty())
        }
    }

    @Test
    fun `generated ordinal mapping preserves model values after file recovery and later writes`() {
        val path = directory.resolve("mapping.koko")
        Database.open(path).use { db ->
            db("INSERT INTO ordered_models VALUES (:name, :id)", mapOf("name" to "Unicode 🙂", "id" to Int.MIN_VALUE))
        }
        val snapshot = Database.open(path).use { db ->
            val models = db.queryModels(OrderedModel::class.java, "SELECT id, displayname FROM ordered_models")
            db("UPDATE ordered_models SET displayname = 'Later'")
            models
        }
        assertEquals(listOf(OrderedModel("Unicode 🙂", Int.MIN_VALUE)), snapshot)
        Database.open(path).use { db ->
            assertEquals(listOf(OrderedModel("Later", Int.MIN_VALUE)), db.queryModels(OrderedModel::class.java, "SELECT * FROM ordered_models"))
        }
    }

    private fun executor(): Executor = Executor(Catalog()).also {
        it.write(Parser("CREATE TABLE ordered_models (displayname TEXT, id INT PRIMARY KEY)").parse(), emptyMap())
        it.write(Parser("INSERT INTO ordered_models VALUES ('Original', 7)").parse(), emptyMap())
    }

    private abstract class Adapter<M : Any> : ModelAdapter<M> {
        override val tableName = "ordered_models"
        override val columns = listOf(ModelColumn("displayname", ModelColumn.Type.TEXT), ModelColumn("id", ModelColumn.Type.INT, true))
    }

    @Test
    fun `existing Row adapters retain detached complete results through the default bridge`() {
        val executor = executor()
        val adapter = object : Adapter<Row>() {
            override val modelClass = Row::class.java
            override fun read(row: Row) = row
        }
        val row = executor.queryModels(Parser("SELECT id, displayname FROM ordered_models").parse(), emptyMap(), adapter).single()
        executor.write(Parser("DELETE FROM ordered_models").parse(), emptyMap())
        assertEquals(7, row.getInt("ID"))
        assertEquals("Original", row.getString("displayname"))
    }

    @Test
    fun `direct adapters read stored columns and completed views release access`() {
        lateinit var captured: ModelValues
        val adapter = object : Adapter<OrderedModel>() {
            override val modelClass = OrderedModel::class.java
            override fun read(row: Row): OrderedModel = error("Direct mapping must not build a Row")
            override fun readValues(values: ModelValues): OrderedModel {
                captured = values
                return OrderedModel(values.getString(0), values.getInt(1))
            }
        }
        val models = executor().queryModels(Parser("SELECT id, displayname FROM ordered_models").parse(), emptyMap(), adapter)
        assertEquals(listOf(OrderedModel("Original", 7)), models)
        assertFailsWith<DatabaseException> { captured.getString(0) }
    }

    @Test
    fun `failed mapping rejects invalid positions and types and clears the active view`() {
        for (operation in listOf<(ModelValues) -> Unit>(
            { it.getInt(0) }, { it.getString(1) }, { it.getInt(-1) }, { it.getInt(2) },
        )) {
            lateinit var captured: ModelValues
            val adapter = object : Adapter<OrderedModel>() {
                override val modelClass = OrderedModel::class.java
                override fun read(row: Row): OrderedModel = error("Unexpected Row mapping")
                override fun readValues(values: ModelValues): OrderedModel {
                    captured = values
                    operation(values)
                    error("Invalid access was accepted")
                }
            }
            val executor = executor()
            assertFailsWith<DatabaseException> {
                executor.queryModels(Parser("SELECT * FROM ordered_models").parse(), emptyMap(), adapter)
            }
            assertFailsWith<DatabaseException> { captured.getInt(1) }
            assertEquals(7, executor.query(Parser("SELECT id FROM ordered_models").parse(), emptyMap()).single().getInt("id"))
        }
    }
}
