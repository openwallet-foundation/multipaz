package org.multipaz.cbor

internal class CdnParser(
    input: String,
    private val options: CdnParserOptions = CdnParserOptions.Default
) {
    private val lexer = CdnLexer(input)
    private var currentToken: Token = lexer.nextToken()
    private var depth = 0

    fun parseSingle(): DataItem {
        if (currentToken.type == TokenType.EOF) {
            throw CdnException("Unexpected EOF when parsing CDN data item", currentToken.line, currentToken.column)
        }
        val item = parseDataItem()
        return item
    }

    fun parseSequence(): List<DataItem> {
        val items = mutableListOf<DataItem>()
        while (currentToken.type != TokenType.EOF) {
            items.add(parseDataItem())
            // Optional separator between sequence items
            if (currentToken.type == TokenType.COMMA || currentToken.type == TokenType.SEMICOLON) {
                advance()
            }
        }
        return items
    }

    private fun parseDataItem(): DataItem {
        if (depth > options.maxDepth) {
            throw CdnException("Exceeded maximum nesting depth of ${options.maxDepth}", currentToken.line, currentToken.column)
        }

        val token = currentToken
        return when (token.type) {
            TokenType.INTEGER -> {
                advance()
                val text = token.text
                val isNegative = text.startsWith("-")
                val cleanText = if (isNegative) text.substring(1) else text

                val value = when {
                    cleanText.startsWith("0x", ignoreCase = true) -> cleanText.substring(2).toLong(16)
                    cleanText.startsWith("0o", ignoreCase = true) -> cleanText.substring(2).toLong(8)
                    cleanText.startsWith("0b", ignoreCase = true) -> cleanText.substring(2).toLong(2)
                    else -> cleanText.toLong()
                }

                val item = if (isNegative) {
                    Nint(value.toULong())
                } else {
                    Uint(value.toULong())
                }

                // Check for tagged value: e.g. 0("2026-07-27...") or 32("https://...")
                if (currentToken.type == TokenType.LPAREN) {
                    advance() // consume (
                    depth++
                    val taggedItem = parseDataItem()
                    expect(TokenType.RPAREN, "Expected ')' after tagged item")
                    depth--
                    val tagNum = if (isNegative) -value else value
                    Tagged(tagNum, taggedItem)
                } else {
                    item
                }
            }

            TokenType.FLOAT -> {
                advance()
                val d = when (token.text) {
                    "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
                    "-Infinity" -> Double.NEGATIVE_INFINITY
                    "NaN" -> Double.NaN
                    else -> token.text.toDouble()
                }
                CborDouble(d)
            }

            TokenType.STRING_DOUBLE -> {
                advance()
                Tstr(token.text)
            }

            TokenType.STRING_SINGLE -> {
                advance()
                Bstr(token.text.encodeToByteArray())
            }

            TokenType.STRING_RAW_DOUBLE -> {
                advance()
                Tstr(token.text)
            }

            TokenType.STRING_RAW_SINGLE -> {
                advance()
                Bstr(token.text.encodeToByteArray())
            }

            TokenType.EXTENSION_LITERAL -> {
                advance()
                val extensionId = token.extraData
                    ?: throw CdnException("Missing extension identifier", token.line, token.column)
                val ext = options.extensionRegistry.get(extensionId)
                    ?: throw CdnException("Unknown application extension '$extensionId'", token.line, token.column)
                val delimiter = if (token.text.startsWith("\"")) '"' else '\''
                ext.parseLiteral(token.text, delimiter)
            }

            TokenType.IDENTIFIER -> {
                advance()
                when (token.text) {
                    "true" -> Simple.TRUE
                    "false" -> Simple.FALSE
                    "null" -> Simple.NULL
                    "undefined" -> Simple.UNDEFINED
                    "Infinity", "+Infinity" -> CborDouble(Double.POSITIVE_INFINITY)
                    "-Infinity" -> CborDouble(Double.NEGATIVE_INFINITY)
                    "NaN" -> CborDouble(Double.NaN)
                    "simple" -> {
                        expect(TokenType.LPAREN, "Expected '(' after 'simple'")
                        val numToken = expect(TokenType.INTEGER, "Expected simple value integer")
                        val simpleValue = numToken.text.toInt()
                        expect(TokenType.RPAREN, "Expected ')' after simple value")
                        Simple(simpleValue.toUInt())
                    }
                    else -> {
                        // Identifier tag e.g. tag(value)
                        val tagNum = token.text.toLongOrNull()
                        if (tagNum != null && currentToken.type == TokenType.LPAREN) {
                            advance() // consume (
                            depth++
                            val taggedItem = parseDataItem()
                            expect(TokenType.RPAREN, "Expected ')' after tagged item")
                            depth--
                            Tagged(tagNum, taggedItem)
                        } else {
                            throw CdnException("Unrecognized identifier '${token.text}'", token.line, token.column)
                        }
                    }
                }
            }

            TokenType.EMBEDDED_CBOR_OPEN -> {
                advance() // consume <<
                depth++
                val seq = mutableListOf<DataItem>()
                while (currentToken.type != TokenType.EMBEDDED_CBOR_CLOSE && currentToken.type != TokenType.EOF) {
                    seq.add(parseDataItem())
                    if (currentToken.type == TokenType.COMMA || currentToken.type == TokenType.SEMICOLON) {
                        advance()
                    }
                }
                expect(TokenType.EMBEDDED_CBOR_CLOSE, "Expected '>>' closing embedded CBOR literal")
                depth--
                val encodedBytes = seq.fold(byteArrayOf()) { acc, item -> acc + Cbor.encode(item) }
                Tagged(Tagged.ENCODED_CBOR, Bstr(encodedBytes))
            }

            TokenType.LBRACKET -> parseArray(indefinite = false)
            TokenType.INDEF_ARRAY_OPEN -> parseArray(indefinite = true)

            TokenType.LBRACE -> parseMap(indefinite = false)
            TokenType.INDEF_MAP_OPEN -> parseMap(indefinite = true)

            TokenType.INDEF_STRING_OPEN -> parseIndefiniteLengthString()

            else -> throw CdnException("Unexpected token '${token.text}' (${token.type})", token.line, token.column)
        }
    }

    private fun parseArray(indefinite: Boolean): DataItem {
        advance() // consume [ or [_
        depth++
        val items = mutableListOf<DataItem>()
        while (currentToken.type != TokenType.RBRACKET && currentToken.type != TokenType.EOF) {
            items.add(parseDataItem())
            if (currentToken.type == TokenType.COMMA) {
                advance()
            } else if (currentToken.type != TokenType.RBRACKET) {
                // Optional trailing comma or whitespace separator
            }
        }
        expect(TokenType.RBRACKET, "Expected ']' closing array")
        depth--
        return CborArray(items, indefiniteLength = indefinite)
    }

    private fun parseMap(indefinite: Boolean): DataItem {
        advance() // consume { or {_
        depth++
        val mapItems = LinkedHashMap<DataItem, DataItem>()
        while (currentToken.type != TokenType.RBRACE && currentToken.type != TokenType.EOF) {
            val key = parseDataItem()
            if (currentToken.type == TokenType.COLON || currentToken.type == TokenType.ARROW) {
                advance()
            } else {
                throw CdnException("Expected ':' or '=>' after map key", currentToken.line, currentToken.column)
            }
            val value = parseDataItem()
            mapItems[key] = value

            if (currentToken.type == TokenType.COMMA) {
                advance()
            } else if (currentToken.type != TokenType.RBRACE) {
                // Optional trailing comma
            }
        }
        expect(TokenType.RBRACE, "Expected '}' closing map")
        depth--
        return CborMap(mapItems, indefiniteLength = indefinite)
    }

    private fun parseIndefiniteLengthString(): DataItem {
        advance() // consume (_
        depth++
        val chunks = mutableListOf<DataItem>()
        var isByteString = false
        var isTextString = false

        while (currentToken.type != TokenType.RPAREN && currentToken.type != TokenType.EOF) {
            val chunk = parseDataItem()
            if (chunk is Bstr) {
                isByteString = true
                chunks.add(chunk)
            } else if (chunk is Tstr) {
                isTextString = true
                chunks.add(chunk)
            } else {
                throw CdnException("Indefinite-length string chunk must be a string", currentToken.line, currentToken.column)
            }

            if (currentToken.type == TokenType.COMMA) {
                advance()
            }
        }
        expect(TokenType.RPAREN, "Expected ')' closing indefinite string")
        depth--

        return if (isByteString && !isTextString) {
            val bstrChunks = chunks.map { (it as Bstr).value }
            IndefLengthBstr(bstrChunks)
        } else if (isTextString && !isByteString) {
            val tstrChunks = chunks.map { (it as Tstr).value }
            IndefLengthTstr(tstrChunks)
        } else {
            throw CdnException("Indefinite string cannot mix text and byte string chunks", currentToken.line, currentToken.column)
        }
    }

    private fun expect(type: TokenType, errorMessage: String): Token {
        if (currentToken.type != type) {
            throw CdnException("$errorMessage (found '${currentToken.text}')", currentToken.line, currentToken.column)
        }
        val token = currentToken
        advance()
        return token
    }

    private fun advance() {
        currentToken = lexer.nextToken()
    }
}
