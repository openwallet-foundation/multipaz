package org.multipaz.util

import kotlin.test.Test
import kotlin.test.assertTrue

class LocaleUtilTest {

    @Test
    fun testCurrentLocaleReturnsNonEmpty() {
        val locale = currentLocale
        assertTrue(locale.isNotEmpty(), "Locale should not be empty")
    }

    @Test
    fun testCurrentLocaleReturnsTwoLetterCode() {
        val locale = currentLocale
        // Should be a 2-letter code like "en", "fr", "de", etc.
        // Or special cases like "zh-rCN"
        assertTrue(
            locale.length == 2 || locale.matches(Regex("^[a-z]{2}-[a-zA-Z0-9]+")),
            "Locale '$locale' should be a 2-letter code or locale tag with 2-letter language code"
        )
    }

    @Test
    fun testCurrentLocaleReturnsLowercase() {
        val locale = currentLocale
        // The language code portion should be lowercase
        val languageCode = locale.substringBefore("-")
        assertTrue(
            languageCode == languageCode.lowercase(),
            "Language code '$languageCode' should be lowercase"
        )
    }

    @Test
    fun testCurrentLocaleIsConsistent() {
        // Calling multiple times should return the same value
        val locale1 = currentLocale
        val locale2 = currentLocale
        assertTrue(
            locale1 == locale2,
            "Locale should be consistent across calls: '$locale1' vs '$locale2'"
        )
    }
}
