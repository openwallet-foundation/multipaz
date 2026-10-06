package org.multipaz.util

/**
 * Picks the element of [candidates] whose language tag best matches [locales].
 *
 * Language tags are compared case-insensitively after normalizing platform spellings to BCP 47,
 * that is `ja_JP` is treated as `ja-JP` and the Android resource qualifier form `zh-rCN` as
 * `zh-CN`. For each preferred language, in order, the following are tried:
 *
 * - a candidate with exactly the same tag,
 * - a candidate whose tag is more specific, for example `ja-JP` when `ja` is preferred, as in
 *   the basic filtering scheme of RFC 4647 section 3.3.1,
 * - a candidate whose tag is less specific, for example `ja` when `ja-JP` is preferred, obtained
 *   by truncating the preferred tag as in the lookup scheme of RFC 4647 section 3.4.
 *
 * If none of the preferred languages match, English is tried in the same way, then a candidate
 * without a language tag, and finally the first candidate.
 *
 * @param candidates the elements to choose from.
 * @param locales BCP 47 language tags, most preferred first.
 * @param languageTagOf returns the language tag of a candidate, or `null` if it has none.
 * @return the best matching candidate or `null` if [candidates] is empty.
 */
internal fun <T : Any> selectByLanguage(
    candidates: List<T>,
    locales: List<String>,
    languageTagOf: (T) -> String?
): T? {
    if (candidates.isEmpty()) {
        return null
    }
    val tags = candidates.map { candidate -> languageTagOf(candidate)?.let { normalizeLanguageTag(it) } }
    fun firstWithTag(predicate: (String?) -> Boolean): T? =
        tags.indexOfFirst(predicate).takeIf { it >= 0 }?.let { candidates[it] }

    for (preferred in locales + listOf("en-US", "en")) {
        val range = normalizeLanguageTag(preferred) ?: continue
        firstWithTag { it == range }?.let { return it }
        firstWithTag { it != null && it.startsWith("$range-") }?.let { return it }
        var truncated = range
        while (truncated.contains('-')) {
            truncated = truncated.substringBeforeLast('-')
            // Never end on a singleton such as the `x` in `ja-x-foo`.
            if (truncated.substringAfterLast('-').length == 1 && truncated.contains('-')) {
                truncated = truncated.substringBeforeLast('-')
            }
            firstWithTag { it == truncated }?.let { return it }
        }
    }
    return firstWithTag { it == null } ?: candidates.first()
}

/**
 * Normalizes [tag] to a lowercase BCP 47 language tag, or returns `null` if it does not identify
 * a language.
 *
 * POSIX suffixes are dropped (`ja_JP.UTF-8` becomes `ja-jp`), underscores are replaced with
 * hyphens and the Android resource qualifier region form is converted (`zh-rCN` becomes
 * `zh-cn`).
 */
internal fun normalizeLanguageTag(tag: String): String? {
    val parts = tag.trim()
        .substringBefore('.')
        .substringBefore('@')
        .replace('_', '-')
        .lowercase()
        .split('-')
        .filter { it.isNotEmpty() }
    val language = parts.firstOrNull() ?: return null
    if (language == "und" || !language.all { it in 'a'..'z' }) {
        return null
    }
    return (listOf(language) + parts.drop(1).map { subtag ->
        if (subtag.length == 3 && subtag[0] == 'r' && subtag[1].isLetter() && subtag[2].isLetter()) {
            subtag.substring(1)
        } else {
            subtag
        }
    }).joinToString("-")
}
