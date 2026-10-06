package org.multipaz.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageTagMatchingTest {

    // Candidates are the positions in [tags], so that a candidate without a tag (null) can be told apart from no match.
    private fun select(tags: List<String?>, preferred: List<String>): String? =
        selectByLanguage(tags.indices.toList(), preferred) { tags[it] }?.let { tags[it] }

    @Test
    fun exactMatch() {
        assertEquals("ja-JP", select(listOf("en-US", "ja-JP"), listOf("ja-JP")))
    }

    @Test
    fun exactMatchWinsOverLessAndMoreSpecificTags() {
        assertEquals("ja-JP", select(listOf("ja", "ja-JP-x-foo", "ja-JP"), listOf("ja-JP")))
        assertEquals("ja", select(listOf("ja-JP", "ja"), listOf("ja")))
    }

    @Test
    fun moreSpecificTagMatchesLanguageOnlyPreference() {
        // Basic filtering: a device reporting only `ja` gets the issuer's `ja-JP` display.
        assertEquals("ja-JP", select(listOf("en-US", "ja-JP"), listOf("ja")))
    }

    @Test
    fun lessSpecificTagMatchesRegionalPreference() {
        // Lookup: an issuer tagging its display `ja` is picked for a `ja-JP` device.
        assertEquals("ja", select(listOf("en-US", "ja"), listOf("ja-JP")))
    }

    @Test
    fun prefixMatchRespectsSubtagBoundaries() {
        // `jam` (Jamaican Creole English) must not be taken for `ja`.
        assertEquals("en-US", select(listOf("jam", "en-US"), listOf("ja")))
    }

    @Test
    fun truncationSkipsSingletons() {
        assertEquals("ja", select(listOf("ja-x", "ja"), listOf("ja-x-foo")))
    }

    @Test
    fun preferencesAreTriedInOrder() {
        assertEquals("fr", select(listOf("en-US", "fr", "ja-JP"), listOf("de", "fr-CA", "ja-JP")))
    }

    @Test
    fun englishIsTheFallback() {
        assertEquals("en-US", select(listOf("ja-JP", "en-US"), listOf("fr")))
        assertEquals("en", select(listOf("ja-JP", "en"), listOf("fr")))
    }

    @Test
    fun untaggedIsUsedWhenNoLanguageMatches() {
        assertEquals("de", selectByLanguage(listOf("ja-JP" to "a", null to "de"), listOf("fr")) { it.first }?.second)
    }

    @Test
    fun firstCandidateIsTheLastResort() {
        assertEquals("ja-JP", select(listOf("ja-JP", "zh-CN"), listOf("fr")))
    }

    @Test
    fun noCandidates() {
        assertNull(select(emptyList(), listOf("ja-JP")))
    }

    @Test
    fun comparisonIsCaseInsensitive() {
        assertEquals("JA-jp", select(listOf("en-US", "JA-jp"), listOf("ja-JP")))
    }

    @Test
    fun platformSpellingsAreNormalized() {
        assertEquals("ja-JP", select(listOf("en-US", "ja-JP"), listOf("ja_JP")))
        assertEquals("zh-CN", select(listOf("en-US", "zh-CN"), listOf("zh-rCN")))
        assertEquals("de-DE", select(listOf("en-US", "de-DE"), listOf("de_DE.UTF-8")))
    }

    @Test
    fun normalizeLanguageTag() {
        assertEquals("ja-jp", normalizeLanguageTag(" ja_JP "))
        assertEquals("zh-cn", normalizeLanguageTag("zh-rCN"))
        assertEquals("zh-hans-cn", normalizeLanguageTag("zh-Hans-CN"))
        assertNull(normalizeLanguageTag(""))
        assertNull(normalizeLanguageTag("und"))
        assertNull(normalizeLanguageTag("*"))
        assertNull(normalizeLanguageTag("12"))
    }
}
