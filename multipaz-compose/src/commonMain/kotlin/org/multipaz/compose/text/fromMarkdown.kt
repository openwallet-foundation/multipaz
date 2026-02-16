package org.multipaz.compose.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Constructs an [AnnotatedString] from Markdown.
 *
 * Supported inline Markdown:
 * - Links: [text](url)
 * - Bold: **text** or __text__
 * - Italic: *text* or _text_
 * - Strikethrough: ~~text~~
 * - Inline Code: `text`
 *
 * @param markdownString a string with Markdown formatting.
 * @param linkInteractionListener a [LinkInteractionListener] to detect when a link has been clicked or `null`
 *   to have the platform handle it.
 * @return an [AnnotatedString].
 */
fun AnnotatedString.Companion.fromMarkdown(
    markdownString: String,
    linkInteractionListener: LinkInteractionListener? = null
): AnnotatedString {
    // Regex components for different Markdown inline styles
    val linkPattern = """\[(.+?)\]\((.+?)\)"""
    val boldPattern = """\*\*(.+?)\*\*|__(.+?)__"""
    // Lookbehinds/lookaheads ensure we don't catch bold markers as italic
    val italicPattern = """(?<!\*)\*(.+?)\*(?!\*)|(?<!_)_(.+?)_(?!_)"""
    val strikePattern = """~~(.+?)~~"""
    val codePattern = """`(.+?)`"""

    // Combine into a single Regex. The order of alternation matters (e.g., bold before italic).
    val regex = "$linkPattern|$boldPattern|$italicPattern|$strikePattern|$codePattern".toRegex()

    return buildAnnotatedString {
        var idx = 0
        val matches = regex.findAll(markdownString)

        for (match in matches) {
            // Append plain text before the match
            if (idx < match.range.start) {
                append(markdownString.substring(idx, match.range.start))
            }

            val groups = match.groups
            val startStyle = length

            when {
                groups[1] != null -> { // Link matched
                    val linkText = groups[1]!!.value
                    val linkUrl = groups[2]!!.value
                    append(linkText)
                    addLink(
                        url = LinkAnnotation.Url(
                            url = linkUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = Color.Blue,
                                    textDecoration = TextDecoration.Underline
                                ),
                            ),
                            linkInteractionListener = linkInteractionListener
                        ),
                        start = startStyle,
                        end = length,
                    )
                }
                groups[3] != null || groups[4] != null -> { // Bold matched (** or __)
                    val text = groups[3]?.value ?: groups[4]!!.value
                    append(text)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), startStyle, length)
                }
                groups[5] != null || groups[6] != null -> { // Italic matched (* or _)
                    val text = groups[5]?.value ?: groups[6]!!.value
                    append(text)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), startStyle, length)
                }
                groups[7] != null -> { // Strikethrough matched (~~)
                    append(groups[7]!!.value)
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), startStyle, length)
                }
                groups[8] != null -> { // Inline code matched (`)
                    append(groups[8]!!.value)
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.LightGray.copy(alpha = 0.3f)
                        ),
                        startStyle,
                        length
                    )
                }
            }
            idx = match.range.endInclusive + 1
        }

        // Append remaining text after the last match
        if (idx < markdownString.length) {
            append(markdownString.substring(idx, markdownString.length))
        }
    }
}