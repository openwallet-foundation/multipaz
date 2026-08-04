package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import web.cssom.*
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.Cbor

private enum class TokenType {
    PLAIN,
    STRING,
    HEX_STRING,
    EMBEDDED,
    TAG,
    BOOL,
    NUMBER,
    PUNCTUATION,
    COMMENT
}

private data class Token(val text: String, val type: TokenType)

fun formatNumberWithCommas(number: Int): String {
    val str = number.toString()
    val regex = Regex("(\\d)(?=(\\d{3})+$)")
    return str.replace(regex, "$1,")
}

private fun tokenizeCborDiagnostics(diagText: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    val len = diagText.length

    while (i < len) {
        val ch = diagText[i]

        // Triple-single-quoted string: '''...''' or cert'''...'''
        if (ch == '\'' && i + 2 < len && diagText[i + 1] == '\'' && diagText[i + 2] == '\'') {
            val start = i
            i += 3
            while (i + 2 < len && !(diagText[i] == '\'' && diagText[i + 1] == '\'' && diagText[i + 2] == '\'')) {
                i++
            }
            if (i + 2 < len) i += 3 else i = len
            tokens.add(Token(diagText.substring(start, i), TokenType.STRING))
            continue
        }

        // Triple-double-quoted string: """..."""
        if (ch == '"' && i + 2 < len && diagText[i + 1] == '"' && diagText[i + 2] == '"') {
            val start = i
            i += 3
            while (i + 2 < len && !(diagText[i] == '"' && diagText[i + 1] == '"' && diagText[i + 2] == '"')) {
                i++
            }
            if (i + 2 < len) i += 3 else i = len
            tokens.add(Token(diagText.substring(start, i), TokenType.STRING))
            continue
        }

        // Single-line Comment: // or #
        if (ch == '#' || (ch == '/' && i + 1 < len && diagText[i + 1] == '/')) {
            val start = i
            while (i < len && diagText[i] != '\n') i++
            tokens.add(Token(diagText.substring(start, i), TokenType.COMMENT))
            continue
        }

        // Multi-line Comment: /* ... */
        if (ch == '/' && i + 1 < len && diagText[i + 1] == '*') {
            val start = i
            i += 2
            while (i + 1 < len && !(diagText[i] == '*' && diagText[i + 1] == '/')) i++
            if (i + 1 < len) i += 2 else i = len
            tokens.add(Token(diagText.substring(start, i), TokenType.COMMENT))
            continue
        }

        // Hex string: h'...' or b64'...'
        if ((ch == 'h' || ch == 'b') && i + 1 < len && diagText[i + 1] == '\'') {
            val start = i
            i += 2
            while (i < len && diagText[i] != '\'' && diagText[i] != '\n') i++
            if (i < len && diagText[i] == '\'') i++
            tokens.add(Token(diagText.substring(start, i), TokenType.HEX_STRING))
            continue
        }

        // Single-quoted byte string: '...'
        if (ch == '\'') {
            val start = i
            i++
            while (i < len && diagText[i] != '\'' && diagText[i] != '\n') {
                if (diagText[i] == '\\' && i + 1 < len) i += 2 else i++
            }
            if (i < len && diagText[i] == '\'') i++
            tokens.add(Token(diagText.substring(start, i), TokenType.STRING))
            continue
        }

        // Double-quoted text string: "..."
        if (ch == '"') {
            val start = i
            i++
            while (i < len && diagText[i] != '\n') {
                if (diagText[i] == '\\' && i + 1 < len) {
                    i += 2
                } else if (diagText[i] == '"') {
                    i++
                    break
                } else {
                    i++
                }
            }
            tokens.add(Token(diagText.substring(start, i), TokenType.STRING))
            continue
        }

        // Embedded CBOR: << or >>
        if (i + 1 < len && ((ch == '<' && diagText[i + 1] == '<') || (ch == '>' && diagText[i + 1] == '>'))) {
            tokens.add(Token(diagText.substring(i, i + 2), TokenType.EMBEDDED))
            i += 2
            continue
        }

        // Tag: e.g. 18( or 24( or 24_0(
        if (ch.isDigit()) {
            var j = i
            while (j < len && (diagText[j].isDigit() || (diagText[j] == '_' && j + 1 < len && diagText[j + 1].isDigit()))) j++
            if (j < len && diagText[j] == '(') {
                tokens.add(Token(diagText.substring(i, j + 1), TokenType.TAG))
                i = j + 1
                continue
            }
        }

        // Identifiers & Keywords (e.g. cert, dt, ip, true, false, null)
        if (ch.isLetter() || ch == '_' || ch == '$') {
            val start = i
            while (i < len && (diagText[i].isLetterOrDigit() || diagText[i] == '_' || diagText[i] == '-' || diagText[i] == '$')) i++
            val word = diagText.substring(start, i)
            if (word == "true" || word == "false" || word == "null" || word == "undefined") {
                tokens.add(Token(word, TokenType.BOOL))
            } else {
                tokens.add(Token(word, TokenType.PLAIN))
            }
            continue
        }

        // Numbers: -123 or 123
        if (ch.isDigit() || (ch == '-' && i + 1 < len && diagText[i + 1].isDigit())) {
            val start = i
            if (ch == '-') i++
            while (i < len && (diagText[i].isDigit() || diagText[i] == '.' || diagText[i] == 'e' || diagText[i] == 'E')) i++
            tokens.add(Token(diagText.substring(start, i), TokenType.NUMBER))
            continue
        }

        // Punctuation
        if (ch in "{}[],:()") {
            tokens.add(Token(ch.toString(), TokenType.PUNCTUATION))
            i++
            continue
        }

        // Newline
        if (ch == '\n') {
            tokens.add(Token("\n", TokenType.PLAIN))
            i++
            continue
        }

        // Whitespace and other characters
        val start = i
        while (i < len) {
            val c = diagText[i]
            if (c == '\n' || c == '#' || c == '"' || c == '\'' || c == '{' || c == '}' || c == '[' || c == ']' ||
                c == '(' || c == ')' || c == ',' || c == ':' || c.isLetterOrDigit() || c == '-' ||
                (c == 'h' && i + 1 < len && diagText[i + 1] == '\'') ||
                (i + 1 < len && ((c == '<' && diagText[i + 1] == '<') || (c == '>' && diagText[i + 1] == '>')))) {
                break
            }
            i++
        }
        if (i > start) {
            tokens.add(Token(diagText.substring(start, i), TokenType.PLAIN))
        } else {
            tokens.add(Token(diagText[i].toString(), TokenType.PLAIN))
            i++
        }
    }

    // Merge adjacent tokens of the same type (except newlines, embedded symbols, or tags) to minimize DOM nodes
    val merged = mutableListOf<Token>()
    for (t in tokens) {
        if (merged.isNotEmpty() &&
            merged.last().type == t.type &&
            !merged.last().text.endsWith("\n") &&
            t.text != "\n" &&
            t.type != TokenType.EMBEDDED &&
            t.type != TokenType.TAG
        ) {
            merged[merged.lastIndex] = Token(merged.last().text + t.text, t.type)
        } else {
            merged.add(t)
        }
    }

    return merged
}

external interface CborDiagnosticViewerProps : Props {
    var diagText: String
    var byteCount: Int?
    var maxHeight: Length?
}

val CborDiagnosticViewer: FC<CborDiagnosticViewerProps> = FC { props ->
    val tokens = tokenizeCborDiagnostics(props.diagText)

    val calculatedByteCount = props.byteCount ?: try {
        if (props.diagText.isNotBlank()) {
            Cbor.encode(Cdn.parse(props.diagText)).size
        } else null
    } catch (e: Throwable) {
        null
    }

    div {
        if (calculatedByteCount != null) {
            div {
                css {
                    fontSize = 12.px
                    color = Color("#94a3b8")
                    fontWeight = FontWeight.normal
                    marginBottom = 6.px
                }
                val formatted = formatNumberWithCommas(calculatedByteCount)
                val unit = if (calculatedByteCount == 1) "byte" else "bytes"
                +"Encoded CBOR size: $formatted $unit"
            }
        }

        pre {
            css {
                background = Color("#020617")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 8.px
                padding = 16.px
                overflow = Auto.auto
                maxHeight = props.maxHeight ?: 400.px
                margin = 0.px
            }
        code {
            css {
                fontFamily = FontFamily.monospace
                fontSize = 14.px
                display = Display.block
                width = "max-content".unsafeCast<Width>()
                minWidth = 100.pct
                whiteSpace = "pre".unsafeCast<WhiteSpace>()
            }
            tokens.forEach { token ->
                if (token.text == "\n") {
                    +"\n"
                } else {
                    val tokenColor = when (token.type) {
                        TokenType.STRING -> Color("#4ade80")
                        TokenType.HEX_STRING -> Color("#fbbf24")
                        TokenType.EMBEDDED -> Color("#38bdf8")
                        TokenType.TAG -> Color("#c084fc")
                        TokenType.BOOL -> Color("#f43f5e")
                        TokenType.NUMBER -> Color("#a855f7")
                        TokenType.PUNCTUATION -> Color("#94a3b8")
                        TokenType.COMMENT -> Color("#64748b")
                        TokenType.PLAIN -> Color("#e2e8f0")
                    }
                    val isItalic = token.type == TokenType.COMMENT

                    span {
                        css {
                            color = tokenColor
                            if (isItalic) fontStyle = FontStyle.italic
                        }
                        +token.text
                    }
                }
            }
        }
    }
}
}
