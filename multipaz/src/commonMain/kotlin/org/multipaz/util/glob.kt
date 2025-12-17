package org.multipaz.util

/**
 * Creates regular expression pattern from simplified glob syntax:
 * - letters, digits, '_', '-', '/', '.' - match that character
 * - '?' - matches a single non-'/' character
 * - '*' - matches a sequence of non-'/' characters
 * - '**' - matches an arbitrary sequence of characters
 * - other characters are not allowed/supported
 *
 * @param glob pattern that uses the syntax described above
 * @return regular expression object that matches the given pattern
 */
fun Regex.Companion.fromGlob(glob: String): Regex {
    val regexPattern = StringBuilder()
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i++]) {
            in 'a'..'z', in '0'..'9', in 'A'..'Z', '-', '_', '/' ->
                regexPattern.append(c)
            '.' -> regexPattern.append("\\.")
            '?' -> regexPattern.append("[^/]")
            '*' -> if (i < glob.length && glob[i] == '*') {
                i++
                regexPattern.append(".*")
            } else {
                regexPattern.append("[^/]*")
            }
            else -> throw IllegalArgumentException("Unexpected pattern character '$c'")
        }
    }
    return Regex(regexPattern.toString())
}