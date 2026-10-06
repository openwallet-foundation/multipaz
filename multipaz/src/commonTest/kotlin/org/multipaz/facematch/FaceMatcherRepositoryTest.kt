package org.multipaz.facematch

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FaceMatcherRepositoryTest {

    private class TestFaceMatcher(
        override val name: String,
        override val displayName: String = name,
        override val supportsLiveness: Boolean = false
    ) : FaceMatcher {
        override fun createSession(referencePortrait: ByteString): FaceMatcherSession =
            throw UnsupportedOperationException()
    }

    @Test
    fun testEmptyRepository() {
        val repo = FaceMatcherRepository()
        assertNull(repo.defaultMatcher)
        assertEquals(0, repo.all.size)
        assertNull(repo.lookup("test"))
    }

    @Test
    fun testAddAndLookup() {
        val repo = FaceMatcherRepository()
        val matcher1 = TestFaceMatcher("test")
        repo.add(matcher1)

        assertEquals(1, repo.all.size)
        assertSame(matcher1, repo.defaultMatcher)
        assertSame(matcher1, repo.lookup("test"))
        assertNull(repo.lookup("nonexistent"))
    }

    @Test
    fun testReplaceSameName() {
        val repo = FaceMatcherRepository()
        val matcher1 = TestFaceMatcher("test")
        val matcher2 = TestFaceMatcher("test")
        repo.add(matcher1)
        repo.add(matcher2)

        assertEquals(1, repo.all.size)
        assertSame(matcher2, repo.defaultMatcher)
        assertSame(matcher2, repo.lookup("test"))
    }
}
