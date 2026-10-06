package kokodb

/** Generates a schema and mapper for a public top-level data class with non-null Int/String properties. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class DbTable(val name: String)
