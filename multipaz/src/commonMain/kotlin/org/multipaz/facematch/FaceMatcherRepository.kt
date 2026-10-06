package org.multipaz.facematch

/**
 * A repository of [FaceMatcher] implementations.
 */
class FaceMatcherRepository {
    private val matchers = mutableListOf<FaceMatcher>()

    /**
     * Registers a new [FaceMatcher] into the repository.
     *
     * If a matcher with the same [FaceMatcher.name] is already registered,
     * it will be replaced.
     *
     * @param faceMatcher the face matcher implementation to add.
     * @return this repository for chaining.
     */
    fun add(faceMatcher: FaceMatcher): FaceMatcherRepository {
        matchers.removeAll { it.name == faceMatcher.name }
        matchers.add(faceMatcher)
        return this
    }

    /**
     * Looks up a registered [FaceMatcher] by name.
     *
     * @param name the unique name of the matcher.
     * @return the matching [FaceMatcher], or null if not found.
     */
    fun lookup(name: String): FaceMatcher? {
        return matchers.find { it.name == name }
    }

    /**
     * Returns a list of all registered [FaceMatcher]s.
     */
    val all: List<FaceMatcher>
        get() = matchers.toList()

    /**
     * Returns the default face matcher, which is the first registered matcher,
     * or null if the repository is empty.
     */
    val defaultMatcher: FaceMatcher?
        get() = matchers.firstOrNull()
}
