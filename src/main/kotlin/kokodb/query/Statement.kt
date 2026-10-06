package kokodb.query

import kokodb.storage.Column
import kokodb.storage.Value

internal sealed interface Statement {
    data class CreateTable(val table: String, val columns: List<Column>) : Statement
    data class Insert(
        val table: String,
        val values: List<Expression>,
        val expectedSchema: List<Column>? = null,
    ) : Statement
    data class Select(
        val table: String,
        val projection: Projection,
        val condition: Equality?,
        val expectedSchema: List<Column>? = null,
    ) : Statement
}

internal sealed interface Projection {
    data object All : Projection
    data class Columns(val names: List<String>) : Projection
}

internal sealed interface Expression {
    data class Literal(val value: Value) : Expression
    data class Parameter(val name: String) : Expression
}

internal data class Equality(val column: String, val value: Expression)
