package org.multipaz.asn1

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.util.toHex
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.util.appendUInt8
import org.multipaz.util.getUInt8
import org.multipaz.util.putUInt32
import kotlin.math.max

internal data class IdentifierOctets(
    val cls: ASN1TagClass,
    val enc: ASN1Encoding,
    val tag: Int,
)

/**
 * ASN.1 support routines.
 *
 * This package contains support for ASN.1 with DER encoding according to
 * [ITU-T Recommendation X.690](https://www.itu.int/itu-t/recommendations/rec.aspx?rec=x.690).
 */
object ASN1 {

    internal const val MAX_DECODE_DEPTH = 100

    internal fun appendIdentifierAndLength(
        builder: ByteStringBuilder,
        cls: ASN1TagClass,
        enc: ASN1Encoding,
        tag: Int,
        length: Int
    ) {
        if (tag <= 0x1e) {
            builder.append((cls.value or enc.value or tag).toByte())
        } else {
            builder.append((cls.value or enc.value or 0x1f).toByte())
            val bitLength = Int.SIZE_BITS - tag.countLeadingZeroBits()
            val bytesNeeded = max((bitLength + 6) / 7, 1)
            for (n in IntRange(0, bytesNeeded - 1).reversed()) {
                var digit = tag.shr(n * 7).and(0x7f)
                if (n != 0) {
                    digit = digit.or(0x80)
                }
                builder.append(digit.and(0xff).toByte())
            }
        }

        if (length < 0x80) {
            builder.appendUInt8(length)
        } else {
            val lengthFieldSize = ((Int.SIZE_BITS - length.countLeadingZeroBits()) + 7)/8
            builder.append((0x80 + lengthFieldSize).toByte())
            val encodedLength = ByteArray(4).apply { putUInt32(0, length.toUInt()) }
            for (b in IntRange(4 - lengthFieldSize, 3)) {
                builder.append(encodedLength[b])
            }
        }
    }

    internal fun appendUniversalTagEncodingLength(
        builder: ByteStringBuilder,
        tag: Int,
        encoding: ASN1Encoding,
        length: Int
    ) {
        appendIdentifierAndLength(builder, ASN1TagClass.UNIVERSAL, encoding, tag, length)
    }

    internal fun decodeIdentifierOctets(derEncoded: ByteArray, offset: Int): Pair<Int, IdentifierOctets> {
        if (offset < 0 || offset >= derEncoded.size) {
            throw IllegalArgumentException(
                "Offset $offset out of bounds for DER data of size ${derEncoded.size}"
            )
        }
        var o = offset
        val idOctet0 = derEncoded[o++]
        val cls = ASN1TagClass.parse(idOctet0)
        val enc = ASN1Encoding.parse(idOctet0)
        val tag0 = idOctet0.toInt().and(0x1f)

        val tag = if (tag0 <= 0x1e) {
            tag0
        } else {
            var result = 0
            do {
                if (o >= derEncoded.size) {
                    throw IllegalArgumentException("Truncated high-tag-number encoding at offset $offset")
                }
                val b = derEncoded.getUInt8(o++).toInt()
                if (result > (Int.MAX_VALUE ushr 7)) {
                    throw IllegalArgumentException(
                        "ASN.1 tag number overflows 31-bit integer at offset $offset"
                    )
                }
                result = (result shl 7) or b.and(0x7f)
            } while (b.and(0x80) != 0)
            result
        }
        return Pair(o, IdentifierOctets(cls, enc, tag))
    }

    internal fun decodeLength(derEncoded: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset < 0 || offset >= derEncoded.size) {
            throw IllegalArgumentException("Truncated ASN.1 length at offset $offset")
        }
        val firstOctet = derEncoded.getUInt8(offset).toInt()
        if (firstOctet.and(0x80) == 0) {
            // Short-form
            return Pair(offset + 1, firstOctet)
        }
        val numOctets = firstOctet.and(0x7f)
        if (numOctets == 0) {
            throw IllegalArgumentException("Indeterminate length not supported")
        }
        if (numOctets == 0x7f) {
            throw IllegalArgumentException("Reserved length encoding 0xff not supported")
        }
        if (numOctets > 4) {
            throw IllegalArgumentException("Length size of $numOctets bytes not supported")
        }
        val remaining = derEncoded.size - offset - 1
        if (numOctets > remaining) {
            throw IllegalArgumentException(
                "Truncated ASN.1 length: need $numOctets length bytes at offset ${offset + 1}, " +
                    "only $remaining bytes remaining"
            )
        }
        var decodedLength = 0L
        for (i in 0 until numOctets) {
            decodedLength = (decodedLength shl 8) or derEncoded.getUInt8(offset + 1 + i).toLong()
        }
        if (decodedLength > Int.MAX_VALUE) {
            throw IllegalArgumentException(
                "ASN.1 length $decodedLength exceeds maximum supported length"
            )
        }
        return Pair(offset + 1 + numOctets, decodedLength.toInt())
    }

    internal fun decode(
        derEncoded: ByteArray,
        offset: Int,
        depth: Int = 0
    ): Pair<Int, ASN1Object?> {
        if (depth > MAX_DECODE_DEPTH) {
            throw IllegalArgumentException("Maximum ASN.1 nesting depth ($MAX_DECODE_DEPTH) exceeded")
        }
        if (offset < 0) {
            throw IllegalArgumentException("Negative offset $offset")
        }
        if (offset >= derEncoded.size) {
            // TODO: review if we should move this check somewhere elsewhere.
            // We hit this code when parsing Android key attestation on the Pixel 3a emulator
            // (exercised in DeviceAttestationAndroidTest).
            return Pair(derEncoded.size, null)
        }
        val (lengthOffset, idOctets) = decodeIdentifierOctets(derEncoded, offset)
        val (contentOffset, length) = decodeLength(derEncoded, lengthOffset)
        val remaining = derEncoded.size - contentOffset
        if (length < 0 || length > remaining) {
            throw IllegalArgumentException(
                "ASN.1 element length $length at offset $offset exceeds remaining $remaining bytes"
            )
        }
        val nextOffset = contentOffset + length
        val content = derEncoded.copyOfRange(contentOffset, nextOffset)
        val decodedObject = if (idOctets.cls == ASN1TagClass.UNIVERSAL) {
            when (idOctets.tag) {
                // Note: we currently don't support the following tags and will simply
                // return an ASN1RawObject() instance instead
                //
                //  - ObjectDescriptor (tag 0x07)
                //  - EXTERNAL (tag 0x08)
                //  - REAL (tag 0x09)
                //  - EMBEDDED PDV (tag 0x0b)
                //  - RELATIVE-OID (tag 0x0d)
                //  - DATE (tag 0x1f)
                //  - TIME-OF-DAY (tag 0x20)
                //  - DATE-TIME (tag 0x21)
                //  - DURATION (tag 0x22)
                //
                ASN1Boolean.TAG_NUMBER -> ASN1Boolean.parse(content)
                ASN1Null.TAG_NUMBER -> ASN1Null.parse(content)
                ASN1ObjectIdentifier.TAG_NUMBER -> ASN1ObjectIdentifier.parse(content)
                ASN1OctetString.TAG_NUMBER -> ASN1OctetString.parse(content)
                ASN1BitString.TAG_NUMBER -> ASN1BitString.parse(content)
                ASN1Sequence.TAG_NUMBER -> ASN1Sequence(decodeMultiple(content, depth + 1))
                ASN1Set.TAG_NUMBER -> ASN1Set(decodeMultiple(content, depth + 1))
                else -> {
                    if (ASN1IntegerTag.entries.find { it.tag == idOctets.tag } != null ) {
                        ASN1Integer.parse(content, idOctets.tag)
                    } else if (ASN1StringTag.entries.find { it.tag == idOctets.tag } != null) {
                        ASN1String.parse(content, idOctets.tag)
                    } else if (ASN1TimeTag.entries.find { it.tag == idOctets.tag } != null) {
                        ASN1Time.parse(content, idOctets.tag)
                    } else {
                        ASN1RawObject(idOctets.cls, idOctets.enc, idOctets.tag, content)
                    }
                }
            }
        } else {
            ASN1TaggedObject.parse(idOctets.cls, idOctets.enc, idOctets.tag, content)
        }
        return Pair(nextOffset, decodedObject)
    }

    /**
     * Decodes a single DER encoded value.
     *
     * @param derEncoded the encoded bytes.
     * @return a [ASN1Object]-derived instance.
     * @throws IllegalArgumentException if the given bytes are not valid.
     */
    @Throws(IllegalArgumentException::class)
    fun decode(derEncoded: ByteArray): ASN1Object? {
        val (newOffset, obj) = try {
            decode(derEncoded, 0)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Unexpected error decoding", e)
        }
        if (newOffset != derEncoded.size) {
            throw IllegalArgumentException(
                "${derEncoded.size - newOffset} bytes leftover after decoding"
            )
        }
        return obj
    }

    /**
     * Decodes a single DER encoded value.
     *
     * @param derEncoded the encoded bytes.
     * @return a [ASN1Object]-derived instance.
     * @throws IllegalArgumentException if the given bytes are not valid.
     */
    @Throws(IllegalArgumentException::class)
    fun decode(derEncoded: ByteString): ASN1Object? = decode(derEncoded.toByteArray())

    /**
     * Decodes multiple encoded DER values.
     *
     * @param derEncoded the encoded bytes.
     * @return one or more [ASN1Object]-derived instances.
     * @throws IllegalArgumentException if the given bytes are not valid.
     */
    @Throws(IllegalArgumentException::class)
    fun decodeMultiple(derEncoded: ByteArray): List<ASN1Object> =
        try {
            decodeMultiple(derEncoded, depth = 0)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Unexpected error decoding", e)
        }

    private fun decodeMultiple(derEncoded: ByteArray, depth: Int): List<ASN1Object> {
        val objects = mutableListOf<ASN1Object>()
        var offset = 0
        while (offset < derEncoded.size) {
            val (newOffset, obj) = decode(derEncoded, offset, depth)
            check(newOffset > offset) {
                "ASN.1 decoder failed to advance offset (offset=$offset, newOffset=$newOffset)"
            }
            if (obj != null) {
                objects.add(obj)
            }
            offset = newOffset
        }
        return objects
    }

    /**
     * Encodes a [ASN1Object] instance.
     *
     * @param obj a [ASN1Object]-derived instance.
     * @return the encoded bytes.
     */
    fun encode(obj: ASN1Object): ByteArray {
        val builder = ByteStringBuilder()
        obj.encode(builder)
        return builder.toByteString().toByteArray()
    }

    private fun print(
        sb: StringBuilder,
        indent: Int,
        obj: ASN1Object
    ) {
        for (n in IntRange(1, indent)) {
            sb.append(" ")
        }
        when (obj) {
            is ASN1Boolean -> {
                sb.append("BOOLEAN ${obj.value}\n")
            }
            is ASN1Integer -> {
                val label = when (obj.tag) {
                    ASN1IntegerTag.INTEGER.tag -> "INTEGER"
                    ASN1IntegerTag.ENUMERATED.tag -> "ENUMERATED"
                    else -> throw IllegalArgumentException()
                }
                // TODO: always print as integer when we have BigInteger support
                try {
                    sb.append("$label ${obj.toLong()}\n")
                } catch (e: IllegalStateException) {
                    sb.append("$label ${obj.value.toHex(byteDivider = " ")}\n")
                }
            }
            is ASN1Null -> {
                sb.append("NULL\n")
            }
            is ASN1ObjectIdentifier -> {
                sb.append("OBJECT IDENTIFIER ${obj.oid}")
                OID.lookupByOid(obj.oid)?.let { sb.append(" ${it.description}") }
                sb.append("\n")
            }
            is ASN1OctetString -> {
                sb.append("OCTET STRING (${obj.value.size} byte) " +
                        "${obj.value.toHex(byteDivider = " ", decodeAsString = true)}\n")
            }
            is ASN1BitString -> {
                sb.append("BIT STRING (${obj.value.size*8 - obj.numUnusedBits} bit) ${obj.renderBitString()}\n")
            }
            is ASN1Time -> {
                val label = when (obj.tag) {
                    ASN1TimeTag.GENERALIZED_TIME.tag -> "GeneralizedTime"
                    ASN1TimeTag.UTC_TIME.tag -> "UTCTime"
                    else -> throw IllegalArgumentException()
                }
                sb.append("$label ${obj.value}\n")
            }
            is ASN1String -> {
                val label = when (obj.tag) {
                    ASN1StringTag.UTF8_STRING.tag -> "UTF8String"
                    ASN1StringTag.NUMERIC_STRING.tag -> "NumericString"
                    ASN1StringTag.PRINTABLE_STRING.tag -> "PrintableString"
                    ASN1StringTag.TELETEX_STRING.tag -> "TeletexString"
                    ASN1StringTag.VIDEOTEX_STRING.tag -> "VideotexString"
                    ASN1StringTag.IA5_STRING.tag -> "IA5String"
                    ASN1StringTag.GRAPHIC_STRING.tag -> "GraphicString"
                    ASN1StringTag.VISIBLE_STRING.tag -> "VisibleString"
                    ASN1StringTag.GENERAL_STRING.tag -> "GeneralString"
                    ASN1StringTag.UNIVERSAL_STRING.tag -> "UniversalString"
                    ASN1StringTag.CHARACTER_STRING.tag -> "CharacterString"
                    ASN1StringTag.BMP_STRING.tag -> "BmpString"
                    else -> throw IllegalArgumentException()
                }
                sb.append("$label ${obj.value}\n")
            }
            is ASN1Sequence -> {
                sb.append("SEQUENCE (${obj.elements.size} elem)\n")
                for (elem in obj.elements) {
                    print(sb, indent + 2, elem)
                }
            }
            is ASN1Set -> {
                sb.append("SET (${obj.elements.size} elem)\n")
                for (elem in obj.elements) {
                    print(sb, indent + 2, elem)
                }
            }
            is ASN1TaggedObject -> {
                when (obj.cls) {
                    ASN1TagClass.UNIVERSAL -> {
                        sb.append("[UNIVERSAL ${obj.tag}] (1 elem)\n")
                    }
                    ASN1TagClass.APPLICATION -> {
                        sb.append("[APPLICATION ${obj.tag}] (1 elem)\n")
                    }
                    ASN1TagClass.CONTEXT_SPECIFIC -> {
                        sb.append("[${obj.tag}] (1 elem)\n")
                    }
                    ASN1TagClass.PRIVATE -> {
                        sb.append("[PRIVATE ${obj.tag}] (1 elem)\n")
                    }
                }
                if (obj.enc == ASN1Encoding.CONSTRUCTED && indent / 2 < MAX_DECODE_DEPTH) {
                    // Try and decode the content as ASN.1, and if so, print it. If not,
                    // just print the content
                    try {
                        val decodedObject = ASN1.decode(obj.content)
                        print(sb, indent + 2, decodedObject!!)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        for (n in IntRange(1, indent + 2)) {
                            sb.append(" ")
                        }
                        sb.append(obj.content.toHex(byteDivider = " ", decodeAsString = true) + "\n")
                    }
                } else {
                    for (n in IntRange(1, indent + 2)) {
                        sb.append(" ")
                    }
                    sb.append(obj.content.toHex(byteDivider = " ", decodeAsString = true) + "\n")
                }
            }
            is ASN1RawObject -> {
                sb.append("UNSUPPORTED TAG class=${obj.cls} encoding=${obj.enc} ")
                sb.append("tag=${obj.tag} value=${obj.content.toHex(byteDivider = " ", decodeAsString = true)}")
            }
            is ASN1PrimitiveValue -> {
                //throw IllegalStateException()
            }
        }
    }

    /**
     * Pretty-prints a [ASN1Object] instance.
     *
     * @param obj a [ASN1Object]-derived instance.
     * @return the pretty-printed form.
     */
    fun print(obj: ASN1Object): String {
        val sb = StringBuilder()
        print(sb, 0, obj)
        return sb.toString()
    }
}