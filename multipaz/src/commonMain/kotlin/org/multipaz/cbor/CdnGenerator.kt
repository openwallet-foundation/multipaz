package org.multipaz.cbor

import org.multipaz.util.toHex

internal class CdnGenerator(private val options: CdnGeneratorOptions) {

    fun generate(item: DataItem): String {
        val sb = StringBuilder()
        generateItem(sb, 0, item)
        return sb.toString()
    }

    private fun generateItem(sb: StringBuilder, indent: Int, item: DataItem) {
        val pretty = options.prettyPrint
        val indentStr = if (pretty) " ".repeat(indent) else ""

        if (item is RawCbor) {
            val decoded = Cbor.decode(item.encodedCbor)
            generateItem(sb, indent, decoded)
            return
        }

        // Try application extensions first (e.g. dt'...', ip'...', or custom registered extensions)
        if (options.useApplicationExtensions) {
            for (ext in options.extensionRegistry.all) {
                val formatted = ext.format(item, options)
                if (formatted != null) {
                    sb.append(formatted)
                    return
                }
            }
        }

        when (item.majorType) {
            MajorType.UNSIGNED_INTEGER -> sb.append((item as Uint).value)

            MajorType.NEGATIVE_INTEGER -> {
                sb.append('-')
                sb.append((item as Nint).value)
            }

            MajorType.BYTE_STRING -> {
                when (item) {
                    is IndefLengthBstr -> {
                        if (options.byteStringFormat == ByteStringFormat.LENGTH_ONLY) {
                            sb.append("indefinite-size byte-string")
                        } else {
                            sb.append("(_ ")
                            var count = 0
                            for (chunk in item.chunks) {
                                if (count++ > 0) sb.append(", ")
                                generateByteStringLiteral(sb, chunk)
                            }
                            sb.append(")")
                        }
                    }

                    is Bstr -> {
                        generateByteStringLiteral(sb, item.value)
                    }

                    else -> throw IllegalStateException("Unexpected Bstr type")
                }
            }

            MajorType.UNICODE_STRING -> {
                when (item) {
                    is IndefLengthTstr -> {
                        sb.append("(_ ")
                        var count = 0
                        for (chunk in item.chunks) {
                            if (count++ > 0) sb.append(", ")
                            generateTextStringLiteral(sb, chunk)
                        }
                        sb.append(")")
                    }

                    is Tstr -> {
                        generateTextStringLiteral(sb, item.value)
                    }

                    else -> throw IllegalStateException("Unexpected Tstr type")
                }
            }

            MajorType.ARRAY -> {
                val arrayItems = (item as CborArray).items
                val isIndef = item.indefiniteLength
                val openBracket = if (isIndef) "[_ " else "["

                if (!pretty || arrayItems.isEmpty()) {
                    sb.append(openBracket)
                    var count = 0
                    for (elem in arrayItems) {
                        if (count++ > 0) sb.append(", ")
                        generateItem(sb, indent, elem)
                    }
                    sb.append("]")
                } else {
                    sb.append(if (isIndef) "[_\n" else "[\n")
                    val childIndent = indent + options.indentSize
                    val childIndentStr = " ".repeat(childIndent)
                    var count = 0
                    for (elem in arrayItems) {
                        sb.append(childIndentStr)
                        generateItem(sb, childIndent, elem)
                        if (++count < arrayItems.size) {
                            sb.append(",")
                        }
                        sb.append("\n")
                    }
                    sb.append(indentStr).append("]")
                }
            }

            MajorType.MAP -> {
                val mapObj = item as CborMap
                var mapEntries: List<Map.Entry<DataItem, DataItem>> = mapObj.items.entries.toList()
                if (options.sortMapKeys) {
                    mapEntries = mapEntries.sortedBy { (k, _) -> k.toString() }
                }
                val isIndef = item.indefiniteLength
                val openBrace = if (isIndef) "{_ " else "{"

                if (!pretty || mapEntries.isEmpty()) {
                    sb.append(openBrace)
                    var count = 0
                    for ((key, value) in mapEntries) {
                        if (count++ > 0) sb.append(", ")
                        generateItem(sb, indent, key)
                        sb.append(": ")
                        generateItem(sb, indent, value)
                    }
                    sb.append("}")
                } else {
                    sb.append(if (isIndef) "{_\n" else "{\n")
                    val childIndent = indent + options.indentSize
                    val childIndentStr = " ".repeat(childIndent)
                    var count = 0
                    for ((key, value) in mapEntries) {
                        sb.append(childIndentStr)
                        generateItem(sb, childIndent, key)
                        sb.append(": ")
                        generateItem(sb, childIndent, value)
                        if (++count < mapEntries.size) {
                            sb.append(",")
                        }
                        sb.append("\n")
                    }
                    sb.append(indentStr).append("}")
                }
            }

            MajorType.TAG -> {
                val tagged = item as Tagged
                val tagNum = tagged.tagNumber

                // Embedded CBOR shorthand << item >> for Tag 24
                if (tagNum == Tagged.ENCODED_CBOR && options.useEmbeddedCborShorthand && tagged.taggedItem is Bstr) {
                    try {
                        val embedded = Cbor.decode((tagged.taggedItem as Bstr).value)
                        sb.append("<< ")
                        generateItem(sb, indent, embedded)
                        sb.append(" >>")
                        return
                    } catch (_: Exception) {
                        // Fallback to tag format if CBOR decode fails
                    }
                }

                sb.append(tagNum).append("(")
                generateItem(sb, indent, tagged.taggedItem)
                sb.append(")")
            }

            MajorType.SPECIAL -> {
                when (item) {
                    is Simple -> {
                        when (item) {
                            Simple.TRUE -> sb.append("true")
                            Simple.FALSE -> sb.append("false")
                            Simple.NULL -> sb.append("null")
                            Simple.UNDEFINED -> sb.append("undefined")
                            else -> sb.append("simple(").append(item.value).append(")")
                        }
                    }

                    is CborFloat -> sb.append(item.value)
                    is CborDouble -> sb.append(item.value)
                    else -> throw IllegalArgumentException("Unexpected MajorType.SPECIAL item")
                }
            }
        }
    }

    private fun generateByteStringLiteral(sb: StringBuilder, bytes: ByteArray) {
        when (options.byteStringFormat) {
            ByteStringFormat.LENGTH_ONLY -> {
                val label = if (bytes.size == 1) "1 byte" else "${bytes.size} bytes"
                sb.append(label)
            }

            ByteStringFormat.ASCII_SINGLE_QUOTE -> {
                if (isPrintableAscii(bytes)) {
                    sb.append("'").append(bytes.decodeToString().replace("'", "\\'")).append("'")
                } else {
                    sb.append("h'").append(bytes.toHex()).append("'")
                }
            }

            ByteStringFormat.BASE64 -> {
                val ext = options.extensionRegistry.get("b64")
                val formatted = ext?.format(Bstr(bytes), options)
                if (formatted != null) {
                    sb.append(formatted)
                } else {
                    sb.append("h'").append(bytes.toHex()).append("'")
                }
            }

            ByteStringFormat.HEX -> {
                sb.append("h'").append(bytes.toHex()).append("'")
            }
        }
    }

    private fun generateTextStringLiteral(sb: StringBuilder, text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        sb.append("\"").append(escaped).append("\"")
    }

    private fun isPrintableAscii(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v !in 32..126) return false
        }
        return true
    }
}
