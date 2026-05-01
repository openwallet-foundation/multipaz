package org.multipaz.doctypes.localization

import org.multipaz.doctypes.generated.GeneratedTranslations
import org.multipaz.util.currentLocale

/**
 * Provides runtime access to localized strings generated from JSON translation resources.
 *
 * The lookup falls back to English (`en`) and finally to the key itself when no translation is found.
 */
object LocalizedStrings {
    /**
     * Returns the normalized current platform locale used for translation lookup.
     */
    fun getCurrentLocale(): String = normalizeLocale(currentLocale)

    /**
     * Returns all available locales for which translations are bundled.
     */
    fun getAllLocales(): List<String> = GeneratedTranslations.allLanguages

    /**
     * Returns a localized value for [key] using the current platform locale.
     */
    fun getString(key: String): String {
        val locale = getCurrentLocale()
        return getString(key, locale)
    }

    /**
     * Returns a localized value for [key] using the provided [locale].
     */
    fun getString(key: String, locale: String): String {
        val normalizedLocale = normalizeLocale(locale)
        val translations = GeneratedTranslations.getMapForLocale(normalizedLocale)
        val result = translations[key] ?: GeneratedTranslations.getMapForLocale("en")[key] ?: key
        return result
    }

    /**
     * Returns a localized value for [key] using [locale], replacing placeholders in the form
     * `{placeholder}` with values from [placeholders].
     */
    fun getString(key: String, locale: String, placeholders: Map<String, String>): String {
        val template = getString(key, locale)
        return placeholders.entries.fold(template) { acc, (placeholder, value) ->
            acc.replace("{$placeholder}", value)
        }
    }

    private fun normalizeLocale(locale: String): String {
        return when (locale.substringBefore("-").substringBefore("_")) {
            "zh" -> "zh-rCN"
            else -> locale
        }
    }
}
