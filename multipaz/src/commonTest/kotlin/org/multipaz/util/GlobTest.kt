package org.multipaz.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobTest {

    @Test
    fun testSimple() {
        assertTrue(matches("foo.txt", "foo.txt"))
        assertFalse(matches("foo.txt", "foo_txt"))
    }

    @Test
    fun testCharWildcard() {
        assertTrue(matches("foo?.txt", "foo1.txt"))
        assertTrue(matches("foo?.txt", "foo..txt"))
        assertTrue(matches("foo???.txt", "foo123.txt"))
        assertFalse(matches("foo?.txt", "foo.txt"))
        assertFalse(matches("foo?.txt", "foo/.txt"))
    }

    @Test
    fun testStarWildcard() {
        assertTrue(matches("*.multipaz.org", "issuer.multipaz.org"))
        assertFalse(matches("*.multipaz.org", "foo.bar.org"))
        assertTrue(matches("foo*bar", "foobar"))
        assertTrue(matches("foo*bar", "foobuzbar"))
        assertFalse(matches("foo*bar", "foo/bar"))
        assertTrue(matches("/*", "/foo"))
        assertFalse(matches("/*", "/foo/bar"))
    }

    @Test
    fun testStarStarWildcard() {
        assertTrue(matches("/**", "/a/b/c"))
        assertFalse(matches("/**/", "/a/b/c"))
        assertTrue(matches("/**/", "/a/b/c/"))
    }

    private fun matches(glob: String, text: String): Boolean =
        Regex.fromGlob(glob).matchEntire(text) != null
}