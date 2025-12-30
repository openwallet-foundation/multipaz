package org.multipaz.util

/**
 * Implementation of [Lazy] for the cases where the value needs to be eagerly created.
 *
 * There are advantages to avoid laziness in some cases (e.g. failing early).
 *
 * @param value eagerly computed value that [Lazy.value] will return.
 */
class Eager<T>(
    override val value: T
): Lazy<T> {
    override fun isInitialized(): Boolean = true
}