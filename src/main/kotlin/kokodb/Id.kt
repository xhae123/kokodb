package kokodb

/** Marks the single, non-null primary key of a generated model. Keys are supplied by the caller. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Id
