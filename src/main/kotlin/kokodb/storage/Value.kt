package kokodb.storage

internal enum class DataType { INT, TEXT }

internal sealed interface Value {
    val type: DataType

    data class IntValue(val value: Int) : Value {
        override val type = DataType.INT
    }

    data class TextValue(val value: String) : Value {
        override val type = DataType.TEXT
    }
}
