package org.multipaz.cbor

internal enum class TokenType {
    INTEGER,
    FLOAT,
    STRING_DOUBLE,
    STRING_SINGLE,
    STRING_RAW_DOUBLE,
    STRING_RAW_SINGLE,
    EXTENSION_LITERAL,
    EXTENSION_EMBEDDED_CBOR_OPEN, // e.g. b1<<, t1<<, hash<<
    EMBEDDED_CBOR_OPEN,  // <<
    EMBEDDED_CBOR_CLOSE, // >>
    LBRACKET,            // [
    RBRACKET,            // ]
    LBRACE,              // {
    RBRACE,              // }
    LPAREN,              // (
    RPAREN,              // )
    COLON,               // :
    ARROW,               // =>
    COMMA,               // ,
    SEMICOLON,           // ;
    INDEF_ARRAY_OPEN,    // [_
    INDEF_MAP_OPEN,      // {_
    INDEF_STRING_OPEN,   // (_
    IDENTIFIER,          // true, false, null, undefined, extension id, tag number
    EOF
}

internal data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,
    val column: Int,
    val extraData: String? = null // e.g. extension identifier or raw string contents
)

internal class CdnLexer(private val input: String) {
    private var pos = 0
    private var line = 1
    private var col = 1

    fun nextToken(): Token {
        skipWhitespaceAndComments()

        if (pos >= input.length) {
            return Token(TokenType.EOF, "", line, col)
        }

        val startLine = line
        val startCol = col
        val ch = input[pos]

        // Compound open delimiters: [_ {_ (_
        if (ch == '[' && peek(1) == '_') {
            advance(2)
            return Token(TokenType.INDEF_ARRAY_OPEN, "[_", startLine, startCol)
        }
        if (ch == '{' && peek(1) == '_') {
            advance(2)
            return Token(TokenType.INDEF_MAP_OPEN, "{_", startLine, startCol)
        }
        if (ch == '(' && peek(1) == '_') {
            advance(2)
            return Token(TokenType.INDEF_STRING_OPEN, "(_", startLine, startCol)
        }

        // Embedded CBOR: << and >>
        if (ch == '<' && peek(1) == '<') {
            advance(2)
            return Token(TokenType.EMBEDDED_CBOR_OPEN, "<<", startLine, startCol)
        }
        if (ch == '>' && peek(1) == '>') {
            advance(2)
            return Token(TokenType.EMBEDDED_CBOR_CLOSE, ">>", startLine, startCol)
        }

        // Arrow =>
        if (ch == '=' && peek(1) == '>') {
            advance(2)
            return Token(TokenType.ARROW, "=>", startLine, startCol)
        }

        // Single character tokens
        when (ch) {
            '[' -> { advance(); return Token(TokenType.LBRACKET, "[", startLine, startCol) }
            ']' -> { advance(); return Token(TokenType.RBRACKET, "]", startLine, startCol) }
            '{' -> { advance(); return Token(TokenType.LBRACE, "{", startLine, startCol) }
            '}' -> { advance(); return Token(TokenType.RBRACE, "}", startLine, startCol) }
            '(' -> { advance(); return Token(TokenType.LPAREN, "(", startLine, startCol) }
            ')' -> { advance(); return Token(TokenType.RPAREN, ")", startLine, startCol) }
            ':' -> { advance(); return Token(TokenType.COLON, ":", startLine, startCol) }
            ',' -> { advance(); return Token(TokenType.COMMA, ",", startLine, startCol) }
            ';' -> { advance(); return Token(TokenType.SEMICOLON, ";", startLine, startCol) }
        }

        // Raw strings: """...""" or '''...'''
        if (ch == '"' && peek(1) == '"' && peek(2) == '"') {
            return readRawString(true, startLine, startCol)
        }
        if (ch == '\'' && peek(1) == '\'' && peek(2) == '\'') {
            return readRawString(false, startLine, startCol)
        }

        // Double quoted text string
        if (ch == '"') {
            return readQuotedString('"', TokenType.STRING_DOUBLE, startLine, startCol)
        }

        // Single quoted byte string
        if (ch == '\'') {
            return readQuotedString('\'', TokenType.STRING_SINGLE, startLine, startCol)
        }

        // Identifiers & Application extensions: e.g., dt'...', ip'...', h'...', b64'...'
        if (ch.isLetter() || ch == '_' || ch == '$') {
            val id = readIdentifierName()
            skipWhitespaceAndComments()
            if (pos < input.length) {
                val nextCh = input[pos]
                if (nextCh == '<' && peek(1) == '<') {
                    advance(2)
                    return Token(TokenType.EXTENSION_EMBEDDED_CBOR_OPEN, "<<", startLine, startCol, extraData = id)
                }
                if (nextCh == '"' && peek(1) == '"' && peek(2) == '"') {
                    val rawToken = readRawString(true, startLine, startCol)
                    return Token(TokenType.EXTENSION_LITERAL, rawToken.text, startLine, startCol, extraData = id)
                }
                if (nextCh == '\'' && peek(1) == '\'' && peek(2) == '\'') {
                    val rawToken = readRawString(false, startLine, startCol)
                    return Token(TokenType.EXTENSION_LITERAL, rawToken.text, startLine, startCol, extraData = id)
                }
                if (nextCh == '"') {
                    val strToken = readQuotedString('"', TokenType.STRING_DOUBLE, startLine, startCol)
                    return Token(TokenType.EXTENSION_LITERAL, strToken.text, startLine, startCol, extraData = id)
                }
                if (nextCh == '\'') {
                    val strToken = readQuotedString('\'', TokenType.STRING_SINGLE, startLine, startCol)
                    return Token(TokenType.EXTENSION_LITERAL, strToken.text, startLine, startCol, extraData = id)
                }
            }
            return Token(TokenType.IDENTIFIER, id, startLine, startCol)
        }

        // Numbers: positive or negative, hex, octal, binary, float, Infinity, NaN
        if (ch.isDigit() || ch == '+' || ch == '-') {
            return readNumber(startLine, startCol)
        }

        throw CdnException("Unexpected character '$ch'", startLine, startCol)
    }

    private fun readIdentifierName(): String {
        val sb = StringBuilder()
        while (pos < input.length) {
            val c = input[pos]
            if (c.isLetterOrDigit() || c == '_' || c == '$' || c == '-') {
                sb.append(c)
                advance()
            } else {
                break
            }
        }
        return sb.toString()
    }

    private fun readQuotedString(
        quote: Char,
        type: TokenType,
        startLine: Int,
        startCol: Int
    ): Token {
        advance() // Consume opening quote
        val sb = StringBuilder()
        while (pos < input.length) {
            val c = input[pos]
            if (c == quote) {
                advance() // Consume closing quote
                return Token(type, sb.toString(), startLine, startCol)
            }
            if (c == '\\') {
                advance()
                if (pos >= input.length) {
                    throw CdnException("Unterminated escape sequence in string", line, col)
                }
                val escChar = input[pos]
                advance()
                when (escChar) {
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        // \uXXXX or \u{X...}
                        if (pos < input.length && input[pos] == '{') {
                            advance()
                            val hexSb = StringBuilder()
                            while (pos < input.length && input[pos] != '}') {
                                hexSb.append(input[pos])
                                advance()
                            }
                            if (pos < input.length && input[pos] == '}') {
                                advance()
                            }
                            val codePoint = hexSb.toString().toIntOrNull(16)
                                ?: throw CdnException("Invalid unicode escape: \\u{${hexSb}}", line, col)
                            sb.appendCodePoint(codePoint)
                        } else {
                            if (pos + 4 > input.length) {
                                throw CdnException("Unterminated \\u escape sequence", line, col)
                            }
                            val hexStr = input.substring(pos, pos + 4)
                            advance(4)
                            val codePoint = hexStr.toIntOrNull(16)
                                ?: throw CdnException("Invalid unicode escape: \\u$hexStr", line, col)
                            sb.append(codePoint.toChar())
                        }
                    }
                    else -> sb.append(escChar)
                }
            } else {
                sb.append(c)
                advance()
            }
        }
        throw CdnException("Unterminated string literal starting at $startLine:$startCol", line, col)
    }

    private fun readRawString(
        isDoubleQuote: Boolean,
        startLine: Int,
        startCol: Int
    ): Token {
        advance(3) // Consume initial triple quotes
        val sb = StringBuilder()
        while (pos < input.length) {
            if (input[pos] == (if (isDoubleQuote) '"' else '\'') &&
                peek(1) == (if (isDoubleQuote) '"' else '\'') &&
                peek(2) == (if (isDoubleQuote) '"' else '\'')
            ) {
                advance(3)
                val type = if (isDoubleQuote) TokenType.STRING_RAW_DOUBLE else TokenType.STRING_RAW_SINGLE
                return Token(type, sb.toString(), startLine, startCol)
            }
            sb.append(input[pos])
            advance()
        }
        throw CdnException("Unterminated raw string literal starting at $startLine:$startCol", line, col)
    }

    private fun readNumber(startLine: Int, startCol: Int): Token {
        val sb = StringBuilder()
        if (input[pos] == '+' || input[pos] == '-') {
            sb.append(input[pos])
            advance()
        }

        // Check for named float literals like Infinity, -Infinity, NaN
        if (pos < input.length && input[pos].isLetter()) {
            val word = readIdentifierName()
            val full = sb.toString() + word
            if (full == "Infinity" || full == "+Infinity" || full == "-Infinity" || full == "NaN" || full == "-NaN") {
                return Token(TokenType.FLOAT, full, startLine, startCol)
            }
            throw CdnException("Invalid numeric token '$full'", startLine, startCol)
        }

        // Hex / Octal / Binary prefixed numbers: 0x, 0o, 0b
        if (pos + 1 < input.length && input[pos] == '0') {
            val next = input[pos + 1]
            if (next == 'x' || next == 'X' || next == 'o' || next == 'O' || next == 'b' || next == 'B') {
                sb.append(input[pos])
                sb.append(next)
                advance(2)
                while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
                    if (input[pos] != '_') {
                        sb.append(input[pos])
                    }
                    advance()
                }
                return Token(TokenType.INTEGER, sb.toString(), startLine, startCol)
            }
        }

        var isFloat = false
        var hasExp = false
        while (pos < input.length) {
            val c = input[pos]
            if (c.isDigit()) {
                sb.append(c)
                advance()
            } else if (c == '.' && !isFloat && !hasExp) {
                isFloat = true
                sb.append(c)
                advance()
            } else if ((c == 'e' || c == 'E') && !hasExp) {
                hasExp = true
                isFloat = true
                sb.append(c)
                advance()
                if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                    sb.append(input[pos])
                    advance()
                }
            } else if (c == '_') {
                // Ignore underscores in numeric literals per CDN spec
                advance()
            } else {
                break
            }
        }

        val text = sb.toString()
        if (text == "NaN" || text == "Infinity" || text == "-Infinity" || text == "+Infinity" || isFloat) {
            return Token(TokenType.FLOAT, text, startLine, startCol)
        }
        return Token(TokenType.INTEGER, text, startLine, startCol)
    }

    private fun skipWhitespaceAndComments() {
        while (pos < input.length) {
            val c = input[pos]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance()
            } else if (c == '#') {
                // Line comment starting with #
                advance()
                while (pos < input.length && input[pos] != '\n') {
                    advance()
                }
            } else if (c == '/' && peek(1) == '/') {
                // Line comment starting with //
                advance(2)
                while (pos < input.length && input[pos] != '\n') {
                    advance()
                }
            } else if (c == '/' && peek(1) == '*') {
                // Block comment starting with /*
                advance(2)
                while (pos < input.length) {
                    if (input[pos] == '*' && peek(1) == '/') {
                        advance(2)
                        break
                    }
                    advance()
                }
            } else if (c == '/') {
                // EDN Slash comment starting with /
                advance()
                while (pos < input.length) {
                    val sc = input[pos]
                    if (sc == '/') {
                        advance()
                        break
                    }
                    if (sc == '\\' && peek(1) == '/') {
                        advance(2)
                    } else {
                        advance()
                    }
                }
            } else {
                break
            }
        }
    }

    private fun advance(count: Int = 1) {
        for (i in 0 until count) {
            if (pos < input.length) {
                if (input[pos] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
                pos++
            }
        }
    }

    private fun peek(offset: Int): Char? {
        val index = pos + offset
        return if (index < input.length) input[index] else null
    }

    private fun StringBuilder.appendCodePoint(codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            append(codePoint.toChar())
        } else {
            val high = ((codePoint - 0x10000) shr 10) + 0xD800
            val low = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
            append(high.toChar())
            append(low.toChar())
        }
    }
}
