package org.multipaz.facematch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FaceMatcherRepositoryTest {

    @Test
    fun testEmptyRepository() {
        val repo = FaceMatcherRepository()
        assertNull(repo.defaultMatcher)
        assertEquals(0, repo.all.size)
        assertNull(repo.lookup("simulated"))
    }

    @Test
    fun testAddAndLookup() {
        val repo = FaceMatcherRepository()
        val matcher1 = SimulatedFaceMatcher()
        repo.add(matcher1)

        assertEquals(1, repo.all.size)
        assertSame(matcher1, repo.defaultMatcher)
        assertSame(matcher1, repo.lookup("simulated"))
        assertNull(repo.lookup("nonexistent"))
    }

    @Test
    fun testReplaceSameName() {
        val repo = FaceMatcherRepository()
        val matcher1 = SimulatedFaceMatcher(searchDurationMs = 1000L)
        val matcher2 = SimulatedFaceMatcher(searchDurationMs = 2000L)
        repo.add(matcher1)
        repo.add(matcher2)

        assertEquals(1, repo.all.size)
        assertSame(matcher2, repo.defaultMatcher)
        assertSame(matcher2, repo.lookup("simulated"))
    }
}
