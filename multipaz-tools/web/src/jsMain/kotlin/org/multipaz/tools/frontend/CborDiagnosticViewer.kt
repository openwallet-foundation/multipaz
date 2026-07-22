package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.Props
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import web.cssom.*

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

private fun tokenizeCborDiagnostics(diagText: String): List<Token> {
    val tokens = mutableListOf<Token>()
    val lines = diagText.split("\n")

    for (lineIdx in lines.indices) {
        val line = lines[lineIdx]
        var i = 0
        val len = line.length

        while (i < len) {
            val ch = line[i]

            // Comment
            if (ch == '#') {
                tokens.add(Token(line.substring(i), TokenType.COMMENT))
                i = len
                break
            }

            // Hex string: h'...'
            if (ch == 'h' && i + 1 < len && line[i + 1] == '\'') {
                val start = i
                i += 2
                while (i < len && line[i] != '\'') {
                    i++
                }
                if (i < len) i++ // include closing quote
                tokens.add(Token(line.substring(start, i), TokenType.HEX_STRING))
                continue
            }

            // Text string: "..."
            if (ch == '"') {
                val start = i
                i++
                while (i < len) {
                    if (line[i] == '\\' && i + 1 < len) {
                        i += 2
                    } else if (line[i] == '"') {
                        i++
                        break
                    } else {
                        i++
                    }
                }
                tokens.add(Token(line.substring(start, i), TokenType.STRING))
                continue
            }

            // Embedded CBOR: << or >>
            if (i + 1 < len && ((ch == '<' && line[i + 1] == '<') || (ch == '>' && line[i + 1] == '>'))) {
                tokens.add(Token(line.substring(i, i + 2), TokenType.EMBEDDED))
                i += 2
                continue
            }

            // Tag: 24( or 100(
            if (ch.isDigit()) {
                var j = i
                while (j < len && line[j].isDigit()) j++
                if (j < len && line[j] == '(') {
                    tokens.add(Token(line.substring(i, j + 1), TokenType.TAG))
                    i = j + 1
                    continue
                }
            }

            // Boolean / null / undefined
            if (ch.isLetter()) {
                val start = i
                while (i < len && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                val word = line.substring(start, i)
                if (word == "true" || word == "false" || word == "null" || word == "undefined") {
                    tokens.add(Token(word, TokenType.BOOL))
                } else {
                    tokens.add(Token(word, TokenType.PLAIN))
                }
                continue
            }

            // Numbers: -123 or 123
            if (ch.isDigit() || (ch == '-' && i + 1 < len && line[i + 1].isDigit())) {
                val start = i
                if (ch == '-') i++
                while (i < len && (line[i].isDigit() || line[i] == '.')) i++
                tokens.add(Token(line.substring(start, i), TokenType.NUMBER))
                continue
            }

            // Punctuation
            if (ch in "{}[],:") {
                tokens.add(Token(ch.toString(), TokenType.PUNCTUATION))
                i++
                continue
            }

            // Plain whitespace / other characters
            val start = i
            while (i < len) {
                val c = line[i]
                if (c == '#' || c == '"' || c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':' ||
                    c.isLetterOrDigit() || c == '-' || (c == 'h' && i + 1 < len && line[i + 1] == '\'') ||
                    (i + 1 < len && ((c == '<' && line[i + 1] == '<') || (c == '>' && line[i + 1] == '>')))) {
                    break
                }
                i++
            }
            if (i > start) {
                tokens.add(Token(line.substring(start, i), TokenType.PLAIN))
            }
        }

        if (lineIdx < lines.size - 1) {
            tokens.add(Token("\n", TokenType.PLAIN))
        }
    }

    return tokens
}

external interface CborDiagnosticViewerProps : Props {
    var diagText: String
    var maxHeight: Length?
}

val CborDiagnosticViewer: FC<CborDiagnosticViewerProps> = FC { props ->
    val tokens = tokenizeCborDiagnostics(props.diagText)

    pre {
        css {
            background = Color("#020617")
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            borderRadius = 8.px
            padding = 16.px
            overflow = "auto".unsafeCast<Overflow>()
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
