package kokodb

/** Generates a database-bound implementation and same-name factory for a public repository interface. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DbRepository
