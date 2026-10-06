package kokodb

/** Typed persistence contract for a declared repository. Models are detached and keys are caller-supplied. */
interface Repository<M : Any, ID : Any> {
    /** Inserts one model. A duplicate primary key fails without changing stored data. */
    fun insert(model: M): Int

    /** Returns a detached model, or null if its key is absent. */
    fun findById(id: ID): M?

    fun findAll(): List<M>

    /** Starts a model query whose terminal calls observe current data. */
    fun query(): ModelQuery<M>

    /** Replaces the complete row with the same key. Returns 1 when present and 0 when absent. */
    fun update(model: M): Int

    /** Returns 1 when a row was removed and 0 when the key was absent. */
    fun deleteById(id: ID): Int
}
