package org.multipaz.cbor

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.X509Cert
import org.multipaz.util.fromBase64
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64
import org.multipaz.util.toHex

/**
 * Interface for CDN application-oriented extension literals (e.g. `dt'2026-07-27T16:00:00Z'`).
 */
interface CdnExtension {
    /**
     * The extension identifier, e.g., "dt", "ip", "h", "b64", "hash", "b1", "t1", "float".
     */
    val identifier: String

    /**
     * Parses the string content of an extension literal into a [DataItem].
     *
     * @param content The text within the quotes or delimiters of the extension.
     * @param delimiter The delimiter character used (' or " or `).
     */
    fun parseLiteral(content: String, delimiter: Char): DataItem

    /**
     * Formats a [DataItem] into CDN extension syntax, or returns `null` if not applicable.
     */
    fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int = 0): String?
}

/**
 * Registry for CDN application-oriented extensions.
 */
class CdnExtensionRegistry {
    private val extensions = mutableMapOf<String, CdnExtension>()

    /**
     * Registers a [CdnExtension] with this registry.
     */
    fun register(extension: CdnExtension) {
        extensions[extension.identifier] = extension
    }

    /**
     * Gets a registered [CdnExtension] by its identifier.
     */
    fun get(identifier: String): CdnExtension? = extensions[identifier]

    /**
     * All registered [CdnExtension] instances.
     */
    val all: Collection<CdnExtension> get() = extensions.values

    companion object {
        /**
         * Default extension registry populated with standard extension handlers.
         */
        val Default: CdnExtensionRegistry by lazy {
            CdnExtensionRegistry().apply {
                register(CertExtension)
                register(DateTimeExtension)
                register(IpExtension)
                register(FloatExtension)
                register(StringConcatByteExtension)
                register(StringConcatTextExtension)
                register(HexExtension)
                register(Base64Extension)
            }
        }
    }
}

/**
 * Extension for Hexadecimal byte strings: `h'01020304'` or `h"01020304"`.
 */
object HexExtension : CdnExtension {
    override val identifier: String = "h"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        val cleanContent = content.filterNot { it.isWhitespace() }
        val bytes = cleanContent.fromHex()
        return Bstr(bytes)
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? {
        if (options.byteStringFormat == ByteStringFormat.HEX && item is Bstr) {
            return "h'${item.value.toHex()}'"
        }
        return null
    }
}

/**
 * Extension for Base64 byte strings: `b64'AQIDBA=='`.
 */
object Base64Extension : CdnExtension {
    override val identifier: String = "b64"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        val cleanContent = content.filterNot { it.isWhitespace() }
        val bytes = try {
            cleanContent.fromBase64Url()
        } catch (e: Exception) {
            cleanContent.fromBase64()
        }
        return Bstr(bytes)
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? {
        if (options.byteStringFormat == ByteStringFormat.BASE64 && item is Bstr) {
            return "b64'${item.value.toBase64()}'"
        }
        return null
    }
}

/**
 * Extension for RFC 3339 Date/Time: `dt'2026-07-27T16:00:00Z'`.
 */
object DateTimeExtension : CdnExtension {
    override val identifier: String = "dt"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        return Tagged(Tagged.DATE_TIME_STRING, Tstr(content))
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? {
        if (!options.useApplicationExtensions) return null
        if (item is Tagged && item.tagNumber == Tagged.DATE_TIME_STRING && item.taggedItem is Tstr) {
            return "dt'${(item.taggedItem as Tstr).value}'"
        }
        return null
    }
}

/**
 * Extension for IP addresses: `ip'192.168.1.1'` or `ip'2001:db8::1'`.
 */
object IpExtension : CdnExtension {
    override val identifier: String = "ip"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        if (content.contains(":")) {
            // IPv6 address parsing
            val bytes = parseIpv6(content)
            return Tagged(54L, Bstr(bytes))
        } else {
            // IPv4 address parsing
            val parts = content.split(".")
            if (parts.size != 4) {
                throw CdnException("Invalid IPv4 address in ip extension: $content")
            }
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val byteVal = parts[i].toIntOrNull()
                    ?: throw CdnException("Invalid component in IPv4 address: $content")
                if (byteVal !in 0..255) {
                    throw CdnException("IPv4 byte value out of range: $byteVal")
                }
                bytes[i] = byteVal.toByte()
            }
            return Tagged(52L, Bstr(bytes))
        }
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? {
        if (!options.useApplicationExtensions) return null
        if (item is Tagged) {
            if (item.tagNumber == 52L && item.taggedItem is Bstr && item.taggedItem.asBstr.size == 4) {
                val bytes = item.taggedItem.asBstr
                val ipStr = bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
                return "ip'$ipStr'"
            }
            if (item.tagNumber == 54L && item.taggedItem is Bstr && item.taggedItem.asBstr.size == 16) {
                val bytes = item.taggedItem.asBstr
                val words = IntArray(8)
                for (i in 0 until 8) {
                    words[i] = ((bytes[i * 2].toInt() and 0xff) shl 8) or (bytes[i * 2 + 1].toInt() and 0xff)
                }
                val ipStr = words.joinToString(":") { it.toString(16) }
                return "ip'$ipStr'"
            }
        }
        return null
    }

    private fun parseIpv6(ipStr: String): ByteArray {
        val bytes = ByteArray(16)
        val parts = ipStr.split("::")
        if (parts.size > 2) throw CdnException("Invalid IPv6 address: $ipStr")
        
        fun parseHexWords(section: String): List<Int> {
            if (section.isEmpty()) return emptyList()
            return section.split(":").map { word ->
                word.toIntOrNull(16) ?: throw CdnException("Invalid hex in IPv6: $ipStr")
            }
        }

        val head = parseHexWords(parts[0])
        val tail = if (parts.size == 2) parseHexWords(parts[1]) else emptyList()
        val totalWords = head.size + tail.size
        if (parts.size == 1 && totalWords != 8) throw CdnException("IPv6 must contain 8 words: $ipStr")
        if (totalWords > 8) throw CdnException("Too many words in IPv6 address: $ipStr")

        val words = IntArray(8)
        for (i in head.indices) words[i] = head[i]
        val tailStart = 8 - tail.size
        for (i in tail.indices) words[tailStart + i] = tail[i]

        for (i in 0 until 8) {
            bytes[i * 2] = (words[i] shr 8).toByte()
            bytes[i * 2 + 1] = words[i].toByte()
        }
        return bytes
    }
}

/**
 * Extension for explicit floating point precision bit-widths: `float'16(0x7c00)'`.
 */
object FloatExtension : CdnExtension {
    override val identifier: String = "float"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        val trimmed = content.trim()
        if (trimmed.startsWith("16(")) {
            val hex = trimmed.removePrefix("16(").removeSuffix(")").removePrefix("0x").filterNot { it.isWhitespace() }
            val bits = hex.toInt(16)
            return CborFloat(float16ToFloat(bits))
        } else if (trimmed.startsWith("32(")) {
            val hex = trimmed.removePrefix("32(").removeSuffix(")").removePrefix("0x").filterNot { it.isWhitespace() }
            val bits = hex.toLong(16).toInt()
            return CborFloat(Float.fromBits(bits))
        } else if (trimmed.startsWith("64(")) {
            val hex = trimmed.removePrefix("64(").removeSuffix(")").removePrefix("0x").filterNot { it.isWhitespace() }
            val bits = hex.toULong(16).toLong()
            return CborDouble(Double.fromBits(bits))
        }
        throw CdnException("Invalid float extension literal: $content")
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? = null

    private fun float16ToFloat(bits: Int): Float {
        val s = (bits shr 15) and 0x01
        val e = (bits shr 10) and 0x1f
        val m = bits and 0x03ff
        if (e == 0) {
            if (m == 0) return if (s == 1) -0.0f else 0.0f
            val f = (m / 1024.0f) * (1.0f / 16384.0f)
            return if (s == 1) -f else f
        } else if (e == 31) {
            if (m == 0) return if (s == 1) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
            return Float.NaN
        }
        var exp = e - 15
        var mult = 1.0f
        if (exp >= 0) {
            for (i in 0 until exp) mult *= 2.0f
        } else {
            for (i in 0 until -exp) mult /= 2.0f
        }
        val f = (1.0f + m / 1024.0f) * mult
        return if (s == 1) -f else f
    }
}

/**
 * Extension for Byte String Concatenation: `b1'...'`.
 */
object StringConcatByteExtension : CdnExtension {
    override val identifier: String = "b1"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        return Bstr(content.encodeToByteArray())
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? = null
}

/**
 * Extension for Text String Concatenation: `t1'...'`.
 */
object StringConcatTextExtension : CdnExtension {
    override val identifier: String = "t1"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        return Tstr(content)
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? = null
}

/**
 * Extension for X.509 certificates: `cert'''-----BEGIN CERTIFICATE-----...-----END CERTIFICATE-----'''`.
 */
object CertExtension : CdnExtension {
    override val identifier: String = "cert"

    override fun parseLiteral(content: String, delimiter: Char): DataItem {
        val cert = X509Cert.fromPem(content)
        return Bstr(cert.encoded.toByteArray())
    }

    override fun format(item: DataItem, options: CdnGeneratorOptions, indent: Int): String? {
        if (!options.useApplicationExtensions || !options.useEmbeddedCertsOpportunistically) return null
        if (item is Bstr) {
            val bytes = item.value
            // Fast path check: X.509 DER certificate MUST start with 0x30 (SEQUENCE) and be >= 64 bytes
            if (bytes.size < 64 || bytes[0] != 0x30.toByte()) {
                return null
            }
            try {
                val cert = X509Cert(ByteString(bytes))
                cert.version // Trigger parsing to ensure valid X.509 structure
                val pem = cert.toPem().trim()
                return if (options.prettyPrint) {
                    val itemIndentStr = " ".repeat(indent)
                    val pemIndentStr = " ".repeat(indent + options.indentSize)
                    val subjectLine = "$itemIndentStr# Subject DN: ${cert.subject.name}"
                    val issuerLine = "$itemIndentStr# Issuer DN: ${cert.issuer.name}"
                    val indentedPem = pem.lines().joinToString("\n") { "$pemIndentStr$it" }
                    val block = "$subjectLine\n$issuerLine\n${itemIndentStr}cert'''\n$indentedPem\n$itemIndentStr'''"
                    if (indent > 0) "\n$block" else block
                } else {
                    "cert'''\n# Subject DN: ${cert.subject.name}\n# Issuer DN: ${cert.issuer.name}\n$pem\n'''"
                }
            } catch (_: Throwable) {
                // Not a valid X.509 certificate
            }
        }
        return null
    }
}
