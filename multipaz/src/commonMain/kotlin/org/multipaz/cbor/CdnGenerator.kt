package org.multipaz.cbor

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcCurve
import org.multipaz.util.toHex

internal class CdnGenerator(private val options: CdnGeneratorOptions) {

    fun generate(item: DataItem): String {
        val sb = StringBuilder()
        generateItem(sb, 0, item)
        return sb.toString()
    }

    private fun generateItem(
        sb: StringBuilder,
        indent: Int,
        item: DataItem,
        isCoseHeaderMap: Boolean = false
    ) {
        val pretty = options.prettyPrint
        val indentStr = if (pretty) " ".repeat(indent) else ""

        if (item is RawCbor) {
            val decoded = Cbor.decode(item.encodedCbor)
            generateItem(sb, indent, decoded, isCoseHeaderMap)
            return
        }

        // Try application extensions first (e.g. cert'''...''', dt'...', ip'...')
        if (options.useApplicationExtensions) {
            for (ext in options.extensionRegistry.all) {
                if (ext is HexExtension || ext is Base64Extension) continue
                val formatted = ext.format(item, options, indent)
                if (formatted != null) {
                    if (formatted.startsWith("\n")) {
                        while (sb.isNotEmpty() && sb.last() == ' ') {
                            sb.deleteAt(sb.length - 1)
                        }
                        if (sb.endsWith("\n")) {
                            sb.append(formatted.substring(1))
                        } else {
                            sb.append(formatted)
                        }
                    } else {
                        sb.append(formatted)
                    }
                    return
                }
            }
        }

        if (item is Bstr && options.useEmbeddedCborOpportunistically) {
            try {
                val embedded = Cbor.decode(item.value)
                if (embedded !is Bstr) {
                    sb.append("<< ")
                    generateItem(sb, indent, embedded, isCoseHeaderMap = isCoseHeaderMap)
                    sb.append(" >>")
                    return
                }
            } catch (_: Throwable) {
                // Fallback if CBOR decode fails
            }
        }

        // Try remaining application extensions (e.g. h'...', b64'...')
        if (options.useApplicationExtensions) {
            val hexExt = options.extensionRegistry.get("h")
            val hexFormatted = hexExt?.format(item, options, indent)
            if (hexFormatted != null) {
                sb.append(hexFormatted)
                return
            }
            val b64Ext = options.extensionRegistry.get("b64")
            val b64Formatted = b64Ext?.format(item, options, indent)
            if (b64Formatted != null) {
                sb.append(b64Formatted)
                return
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

                val coseStructure = if (options.annotateCoseOpportunistically) {
                    detectCoseArrayStructure(item, isTaggedCose = false)
                } else null

                if (!pretty || arrayItems.isEmpty()) {
                    sb.append(openBracket)
                    var count = 0
                    for ((idx, elem) in arrayItems.withIndex()) {
                        if (count++ > 0) sb.append(", ")
                        if (coseStructure != null) {
                            val elementComment = getCoseArrayElementComment(coseStructure, idx)
                            if (elementComment != null) {
                                sb.append("/").append(elementComment).append("/ ")
                            }
                        }
                        val isHeaderElem = coseStructure != null && (idx == 0 || idx == 1)
                        generateItem(sb, indent, elem, isCoseHeaderMap = isHeaderElem)
                    }
                    sb.append("]")
                } else {
                    val structComment = when (coseStructure) {
                        CoseStructureType.COSE_SIGN1 -> " # COSE_Sign1"
                        CoseStructureType.COSE_MAC0 -> " # COSE_Mac0"
                        null -> ""
                    }
                    sb.append(if (isIndef) "[_$structComment\n" else "[$structComment\n")
                    val childIndent = indent + options.indentSize
                    val childIndentStr = " ".repeat(childIndent)
                    var count = 0
                    for ((idx, elem) in arrayItems.withIndex()) {
                        sb.append(childIndentStr)
                        if (coseStructure != null) {
                            val elementComment = getCoseArrayElementComment(coseStructure, idx)
                            if (elementComment != null) {
                                sb.append("/").append(elementComment).append("/ ")
                            }
                        }
                        val isHeaderElem = coseStructure != null && (idx == 0 || idx == 1)
                        generateItem(sb, childIndent, elem, isCoseHeaderMap = isHeaderElem)
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

                val isCoseKeyMap = options.annotateCoseOpportunistically && isCoseKey(mapObj)
                val isCoseHeaderMapToUse = options.annotateCoseOpportunistically && !isCoseKeyMap && (isCoseHeaderMap || (indent == 0 && isCoseHeaderMap(mapObj)))
                val ktyVal = if (isCoseKeyMap) getKtyValue(mapObj) else null

                if (!pretty || mapEntries.isEmpty()) {
                    sb.append(openBrace)
                    var count = 0
                    for ((key, value) in mapEntries) {
                        if (count++ > 0) sb.append(", ")
                        val keyInt = getIntegerKey(key)
                        val labelComment = when {
                            isCoseKeyMap && keyInt != null -> getCoseKeyLabelComment(keyInt, ktyVal)
                            isCoseHeaderMapToUse && keyInt != null -> getCoseHeaderLabelComment(keyInt)
                            else -> null
                        }
                        if (labelComment != null) {
                            sb.append("/").append(labelComment).append("/ ")
                        }
                        generateItem(sb, indent, key)
                        sb.append(": ")
                        generateItem(sb, indent, value)
                        val valDesc = if (labelComment != null) getCoseValueDescription(labelComment, value) else null
                        if (valDesc != null) {
                            sb.append(" # ").append(valDesc)
                        }
                    }
                    sb.append("}")
                } else {
                    val keyComment = if (isCoseKeyMap) " # COSE_Key" else ""
                    sb.append(if (isIndef) "{_$keyComment\n" else "{$keyComment\n")
                    val childIndent = indent + options.indentSize
                    val childIndentStr = " ".repeat(childIndent)
                    var count = 0
                    for ((key, value) in mapEntries) {
                        sb.append(childIndentStr)
                        val keyInt = getIntegerKey(key)
                        val labelComment = when {
                            isCoseKeyMap && keyInt != null -> getCoseKeyLabelComment(keyInt, ktyVal)
                            isCoseHeaderMapToUse && keyInt != null -> getCoseHeaderLabelComment(keyInt)
                            else -> null
                        }
                        if (labelComment != null) {
                            sb.append("/").append(labelComment).append("/ ")
                        }
                        generateItem(sb, childIndent, key)
                        sb.append(": ")
                        generateItem(sb, childIndent, value)
                        val isLast = (++count == mapEntries.size)
                        if (!isLast) {
                            sb.append(",")
                        }
                        val valDesc = if (labelComment != null) getCoseValueDescription(labelComment, value) else null
                        if (valDesc != null) {
                            sb.append(" # ").append(valDesc)
                        }
                        sb.append("\n")
                    }
                    sb.append(indentStr).append("}")
                }
            }

            MajorType.TAG -> {
                val tagged = item as Tagged
                val tagNum = tagged.tagNumber

                // Embedded CBOR shorthand 24(<< item >>) for Tag 24
                if (tagNum == Tagged.ENCODED_CBOR && options.useEmbeddedCborShorthand && tagged.taggedItem is Bstr) {
                    try {
                        val embedded = Cbor.decode((tagged.taggedItem as Bstr).value)
                        sb.append("24(<< ")
                        generateItem(sb, indent, embedded, isCoseHeaderMap)
                        sb.append(" >>)")
                        return
                    } catch (_: Throwable) {
                        // Fallback to tag format if CBOR decode fails
                    }
                }

                if (options.annotateCoseOpportunistically && (tagNum == Tagged.COSE_SIGN1 || tagNum == Tagged.COSE_MAC0) && tagged.taggedItem is CborArray) {
                    val coseStructure = if (tagNum == Tagged.COSE_SIGN1) CoseStructureType.COSE_SIGN1 else CoseStructureType.COSE_MAC0
                    sb.append(tagNum).append("(")
                    generateCoseArray(sb, indent, tagged.taggedItem as CborArray, coseStructure)
                    sb.append(")")
                    return
                }

                sb.append(tagNum).append("(")
                generateItem(sb, indent, tagged.taggedItem, isCoseHeaderMap)
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

    private fun generateCoseArray(
        sb: StringBuilder,
        indent: Int,
        array: CborArray,
        structure: CoseStructureType
    ) {
        val arrayItems = array.items
        val isIndef = array.indefiniteLength
        val pretty = options.prettyPrint
        val indentStr = if (pretty) " ".repeat(indent) else ""

        if (!pretty || arrayItems.isEmpty()) {
            val openBracket = if (isIndef) "[_ " else "["
            sb.append(openBracket)
            var count = 0
            for ((idx, elem) in arrayItems.withIndex()) {
                if (count++ > 0) sb.append(", ")
                val elementComment = getCoseArrayElementComment(structure, idx)
                if (elementComment != null) {
                    sb.append("/").append(elementComment).append("/ ")
                }
                val isHeaderElem = (idx == 0 || idx == 1)
                generateItem(sb, indent, elem, isCoseHeaderMap = isHeaderElem)
            }
            sb.append("]")
        } else {
            val structComment = if (structure == CoseStructureType.COSE_SIGN1) " # COSE_Sign1" else " # COSE_Mac0"
            sb.append(if (isIndef) "[_$structComment\n" else "[$structComment\n")
            val childIndent = indent + options.indentSize
            val childIndentStr = " ".repeat(childIndent)
            var count = 0
            for ((idx, elem) in arrayItems.withIndex()) {
                sb.append(childIndentStr)
                val elementComment = getCoseArrayElementComment(structure, idx)
                if (elementComment != null) {
                    sb.append("/").append(elementComment).append("/ ")
                }
                val isHeaderElem = (idx == 0 || idx == 1)
                generateItem(sb, childIndent, elem, isCoseHeaderMap = isHeaderElem)
                if (++count < arrayItems.size) {
                    sb.append(",")
                }
                sb.append("\n")
            }
            sb.append(indentStr).append("]")
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

    private enum class CoseStructureType {
        COSE_SIGN1,
        COSE_MAC0
    }

    private fun detectCoseArrayStructure(item: DataItem, isTaggedCose: Boolean): CoseStructureType? {
        if (item !is CborArray) return null
        val items = item.items
        if (items.size != 4) return null

        val p = items[0] as? Bstr ?: return null
        val uMap = items[1] as? CborMap ?: return null
        val payload = items[2]
        if (items[3] !is Bstr) return null
        if (payload !is Bstr && payload != Simple.NULL && payload != Simple.UNDEFINED) return null

        val pMap: CborMap = if (p.value.isEmpty()) {
            CborMap(mutableMapOf())
        } else {
            try {
                val decoded = Cbor.decode(p.value)
                decoded as? CborMap
            } catch (_: Throwable) {
                null
            }
        } ?: return null

        val alg = getCoseAlgorithm(pMap) ?: getCoseAlgorithm(uMap)
        if (alg != null) {
            if (alg in COSE_MAC_ALGORITHM_IDS) {
                return CoseStructureType.COSE_MAC0
            }
            return CoseStructureType.COSE_SIGN1
        }

        if (hasCoseHeader(pMap, setOf(33L)) || hasCoseHeader(uMap, setOf(33L))) {
            return CoseStructureType.COSE_SIGN1
        }

        val hasCoseHeaders = hasCoseHeader(pMap, COSE_HEADER_LABELS) || hasCoseHeader(uMap, COSE_HEADER_LABELS)
        return when {
            hasCoseHeaders || isTaggedCose -> CoseStructureType.COSE_SIGN1
            else -> null
        }
    }

    private fun getCoseAlgorithm(map: CborMap): Long? {
        for ((key, value) in map.items) {
            if (getIntegerKey(key) == 1L) {
                return getIntegerValue(value)
            }
        }
        return null
    }

    private fun isCoseHeaderMap(map: CborMap): Boolean {
        return hasCoseHeader(map, COSE_HEADER_LABELS)
    }

    private fun hasCoseHeader(map: CborMap, labels: Set<Long>): Boolean {
        for (key in map.items.keys) {
            val k = getIntegerKey(key) ?: continue
            if (k in labels) return true
        }
        return false
    }

    private fun isCoseKey(map: CborMap): Boolean {
        var hasKty = false
        var hasOtherKeyParam = false

        for ((key, value) in map.items) {
            val k = getIntegerKey(key) ?: continue
            if (k == 1L) {
                val v = getIntegerValue(value) ?: continue
                if (v in 1L..4L) {
                    hasKty = true
                }
            } else if (k in COSE_KEY_PARAM_LABELS) {
                hasOtherKeyParam = true
            }
        }
        return hasKty && hasOtherKeyParam
    }

    private fun getKtyValue(map: CborMap): Long? {
        for ((key, value) in map.items) {
            if (getIntegerKey(key) == 1L) {
                return getIntegerValue(value)
            }
        }
        return null
    }

    private fun getIntegerKey(item: DataItem): Long? {
        return when (item) {
            is Uint -> item.value.toLong()
            is Nint -> -item.value.toLong()
            else -> null
        }
    }

    private fun getIntegerValue(item: DataItem): Long? {
        return when (item) {
            is Uint -> item.value.toLong()
            is Nint -> -item.value.toLong()
            else -> null
        }
    }

    private fun getCoseArrayElementComment(structure: CoseStructureType, index: Int): String? {
        return when (index) {
            0 -> "protected"
            1 -> "unprotected"
            2 -> "payload"
            3 -> if (structure == CoseStructureType.COSE_SIGN1) "signature" else "tag"
            else -> null
        }
    }

    private fun getCoseHeaderLabelComment(key: Long): String? {
        return when (key) {
            1L -> "alg"
            2L -> "crit"
            3L -> "content type"
            4L -> "kid"
            5L -> "IV"
            6L -> "Partial IV"
            33L -> "x5chain"
            else -> null
        }
    }

    private fun getCoseKeyLabelComment(key: Long, ktyVal: Long?): String? {
        return when (key) {
            1L -> "kty"
            2L -> "kid"
            3L -> "alg"
            4L -> "key_ops"
            5L -> "Base IV"
            -1L -> if (ktyVal == 4L) "k" else "crv"
            -2L -> "x"
            -3L -> "y"
            -4L -> "d"
            else -> null
        }
    }

    private fun getCoseValueDescription(labelComment: String, value: DataItem): String? {
        val intVal = getIntegerValue(value) ?: return null
        return when (labelComment) {
            "kty" -> when (intVal) {
                1L -> "OKP"
                2L -> "EC2"
                3L -> "RSA"
                4L -> "Symmetric"
                else -> null
            }

            "alg" -> {
                val alg = try {
                    Algorithm.fromCoseAlgorithmIdentifier(intVal.toInt())
                } catch (_: Throwable) {
                    null
                }
                if (alg != null) "${alg.name}: ${alg.description}" else null
            }

            "crv" -> {
                val curve = EcCurve.entries.firstOrNull { it.coseCurveIdentifier == intVal.toInt() }
                when (curve) {
                    EcCurve.P256 -> "P-256"
                    EcCurve.P384 -> "P-384"
                    EcCurve.P521 -> "P-521"
                    EcCurve.ED25519 -> "Ed25519"
                    EcCurve.ED448 -> "Ed448"
                    EcCurve.X25519 -> "X25519"
                    EcCurve.X448 -> "X448"
                    EcCurve.BRAINPOOLP256R1 -> "brainpoolP256r1"
                    EcCurve.BRAINPOOLP320R1 -> "brainpoolP320r1"
                    EcCurve.BRAINPOOLP384R1 -> "brainpoolP384r1"
                    EcCurve.BRAINPOOLP512R1 -> "brainpoolP512r1"
                    null -> null
                }
            }

            else -> null
        }
    }

    companion object {
        private val COSE_HEADER_LABELS = setOf(1L, 2L, 3L, 4L, 5L, 6L, 33L)
        private val COSE_KEY_PARAM_LABELS = setOf(2L, 3L, 4L, 5L, -1L, -2L, -3L, -4L)
        private val COSE_MAC_ALGORITHM_IDS = setOf(4L, 5L, 6L, 7L, 14L, 15L, 25L, 26L)
    }
}
