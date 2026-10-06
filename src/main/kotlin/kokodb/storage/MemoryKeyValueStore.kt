package kokodb.storage

import kokodb.DatabaseException

internal class MemoryKeyValueStore {
    private val entries = mutableMapOf<String, Any?>()

    fun set(key: String, value: Any?) {
        entries[key] = snapshot(value)
    }

    fun get(key: String): Any? = snapshot(entries[key])

    fun contains(key: String): Boolean = entries.containsKey(key)

    fun remove(key: String): Any? = snapshot(entries.remove(key))

    private fun snapshot(value: Any?): Any? = when (value) {
        // Arrays need copies at both boundaries so callers cannot mutate stored data through a reference.
        is ByteArray -> value.copyOf()
        null, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is Char -> value
        else -> throw DatabaseException(
            "Unsupported key-value type '${value.javaClass.name}'; use a Kotlin scalar or ByteArray"
        )
    }
}
