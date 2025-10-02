package org.multipaz.cbor

import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseMac0
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.util.toBase64
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Abstract base class for CBOR data items.
 *
 * Instances of [DataItem] are immutable and implement the [equals], [hashCode], and [toString]
 * methods.
 *
 * To assist with parsing, a number of helper properties are included on this class
 * to access the data using specific types. These include [asBstr], [asTstr], [asNumber]
 * etc. for primitive types and also include higher-level types such as [asCoseSign1]
 * to get a [CoseSign1].
 *
 * CBOR maps and CBOR arrays can be accessed using the [get] method which is also available via
 * the bracket operator. Note that [get] will throw if the requested key isn't available in the
 * map. The [hasKey], [getOrDefault], or [getOrNull] methods can be used in situations where the
 * key is optional.
 *
 * @param majorType the CBOR major type of the data item.
 */
sealed class DataItem(
    val majorType: MajorType
) {
    internal abstract fun encode(builder: ByteStringBuilder)

    /**
     * The value of a [Bstr] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [Bstr].
     */
    val asBstr: ByteArray
        get() {
            require(this is Bstr)
            return value
        }

    /**
     * The value of a [Tstr] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [Tstr].
     */
    val asTstr: String
        get() {
            require(this is Tstr)
            return value
        }

    /**
     * The value of a [Uint] or [Nint] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [Uint] or [Nint].
     */
    val asNumber: Long
        get() {
            when (this) {
                is Uint -> return value.toLong()
                is Nint -> return -value.toLong()
                else -> {
                    throw IllegalArgumentException("Data item is not Uint or Nint")
                }
            }
        }

    /**
     * The value of a [Double] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [Double].
     */
    val asDouble: Double
        get() {
            require(this is CborDouble)
            return value
        }

    /**
     * The value of a [Float] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [Float].
     */
    val asFloat: Float
        get() {
            require(this is CborFloat)
            return value
        }

    /**
     * The value of a [Simple] data item containing either true/false.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [Simple] containing
     * either true/false.
     */
    val asBoolean: Boolean
        get() {
            when (this) {
                is Simple -> {
                    return when (value) {
                        Simple.TRUE.value -> true
                        Simple.FALSE.value -> false
                        else -> {
                            throw IllegalArgumentException("Unexpected simple value $value")
                        }
                    }
                }
                else -> {
                    throw IllegalArgumentException("Data item is not a Simple")
                }
            }
        }

    /**
     * The value of a [CborMap] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [CborMap].
     */
    val asMap: Map<DataItem, DataItem>
        get() {
            require(this is CborMap)
            return this.items
        }

    /**
     * The value of a [CborArray] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [CborArray].
     */
    val asArray: List<DataItem>
        get() {
            require(this is CborArray)
            return this.items
        }

    /**
     * The value of a [Tagged] data item.
     *
     * @throws IllegalArgumentException if not the data item isn't of type [Tagged].
     */
    val asTagged: DataItem
        get() {
            require(this is Tagged)
            return this.taggedItem
        }

    /**
     * The decoded CBOR from a bstr with tag [Tagged.ENCODED_CBOR].
     *
     * @throws IllegalArgumentException if the data item isn't a tag with tag [Tagged.ENCODED_CBOR]
     * containing a bstr with valid CBOR.
     */
    val asTaggedEncodedCbor: DataItem
        get() {
            require(this is Tagged)
            require(this.tagNumber == Tagged.ENCODED_CBOR)
            val child = this.taggedItem
            require(child is Bstr)
            return Cbor.decode(child.value)
        }

    /**
     * The date-time from a tstr with tag [Tagged.DATE_TIME_STRING].
     *
     * @throws IllegalArgumentException if the data item isn't a tag with tag [Tagged.DATE_TIME_STRING]
     * containing a tstr with valid date-time format according to RFC 3339.
     */
    val asDateTimeString: Instant
        get() {
            require(this is Tagged)
            require(this.tagNumber == Tagged.DATE_TIME_STRING)
            require(this.taggedItem is Tstr)
            return Instant.parse(this.taggedItem.value)
        }

    /**
     * The date-time from a tstr with tag [Tagged.FULL_DATE_STRING].
     *
     * @throws IllegalArgumentException if the data item isn't a tag with tag [Tagged.FULL_DATE_STRING]
     * containing a tstr with valid date format.
     */
    val asDateString: LocalDate
        get() {
            require(this is Tagged)
            require(this.tagNumber == Tagged.FULL_DATE_STRING)
            require(this.taggedItem is Tstr)
            return LocalDate.parse(this.taggedItem.value)
        }

    /**
     * Returns whether a map has a value for a given key
     *
     * @param key the key to check for.
     * @return whether the key exists in the map.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun hasKey(key: DataItem): Boolean {
        require(this is CborMap)
        return items.get(key) != null
    }

    /**
     * Returns whether a map has a value for a given key
     *
     * @param key the key to check for.
     * @return whether the key exists in the map.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun hasKey(key: String): Boolean {
        require(this is CborMap)
        return items.get(key.toDataItem()) != null
    }

    /**
     * Returns whether a map has a value for a given key
     *
     * @param key the key to check for.
     * @return whether the key exists in the map.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun hasKey(key: Long): Boolean {
        require(this is CborMap)
        return items.get(key.toDataItem()) != null
    }

    /**
     * Gets the value of a key in a map.
     *
     * @param key the key to get the value for.
     * @return the [DataItem] for the given key.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     * @throws IllegalStateException if the value doesn't exist in the map.
     */
    operator fun get(key: DataItem): DataItem {
        require(this is CborMap)
        val value = items.get(key)
        if (value == null) {
            throw IllegalStateException("Key $key doesn't exist in map")
        }
        return value
    }

    /**
     * Gets the value of a key in a map.
     *
     * @param key the key to get the value for.
     * @return the [DataItem] for the given key.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     * @throws IllegalStateException if the value doesn't exist in the map.
     */
    operator fun get(key: String): DataItem {
        return get(key.toDataItem())
    }

    /**
     * Gets the value of a key in a map or an index in an array
     *
     * @param key the key or index to get the value for.
     * @return the [DataItem] for the given key or index.
     * @throws IllegalArgumentException if the data item isn't a [CborMap] or [CborArray].
     * @throws IllegalStateException if the value doesn't exist in the map.
     * @throws IndexOutOfBoundsException if an array and the index is out of bounds.
     */
    operator fun get(key: Long): DataItem {
        return when (this) {
            is CborArray -> {
                items[key.toInt()]
            }
            else -> {
                get(key.toDataItem())
            }
        }
    }

    /**
     * Gets the value of a key in a map or a default value if the key doesn't exist.
     *
     * @param key the key to get the value for.
     * @param defaultValue the value to return if the key doesn't exist in the map.
     * @return the [DataItem] for the given key or the default value.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun getOrDefault(key: DataItem, defaultValue: DataItem): DataItem {
        require(this is CborMap)
        val value = items.get(key)
        if (value == null) {
            return defaultValue
        }
        return value
    }

    /**
     * Gets the value of a key in a map or a default value if the key doesn't exist.
     *
     * @param key the key to get the value for.
     * @param defaultValue the value to return if the key doesn't exist in the map.
     * @return the [DataItem] for the given key or the default value.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun getOrDefault(key: String, defaultValue: DataItem): DataItem {
        require(this is CborMap)
        val value = items.get(key.toDataItem())
        if (value == null) {
            return defaultValue
        }
        return value
    }

    /**
     * Gets the value of a key in a map or a default value if the key doesn't exist.
     *
     * @param key the key to get the value for.
     * @param defaultValue the value to return if the key doesn't exist in the map.
     * @return the [DataItem] for the given key or the default value.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun getOrDefault(key: Long, defaultValue: DataItem): DataItem {
        require(this is CborMap)
        val value = items.get(key.toDataItem())
        if (value == null) {
            return defaultValue
        }
        return value
    }

    /**
     * Gets the value of a key in a map or null if the key doesn't exist.
     *
     * @param key the key to get the value for.
     * @return the [DataItem] for the given key or null if the key doesn't exist.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun getOrNull(key: DataItem): DataItem? {
        require(this is CborMap)
        return items.get(key)
    }

    /**
     * Gets the value of a key in a map or null if the key doesn't exist.
     *
     * @param key the key to get the value for.
     * @return the [DataItem] for the given key or null if the key doesn't exist.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun getOrNull(key: String): DataItem? {
        require(this is CborMap)
        return items.get(key.toDataItem())
    }

    /**
     * Gets the value of a key in a map or null if the key doesn't exist.
     *
     * @param key the key to get the value for.
     * @return the [DataItem] for the given key or null if the key doesn't exist.
     * @throws IllegalArgumentException if the data item isn't a [CborMap].
     */
    fun getOrNull(key: Long): DataItem? {
        require(this is CborMap)
        return items.get(key.toDataItem())
    }

    /**
     * Converts the [DataItem] to JSON according to Section 6.1 of RFC 8949.
     *
     * @return a [JsonElement].
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toJson(): JsonElement {
        // See RFC 8949 section 6.1
        return when (this) {
            // A byte string (major type 2) that is not embedded in a tag that specifies a proposed
            // encoding is encoded in base64url without padding and becomes a JSON string.
            is Bstr -> JsonPrimitive(asBstr.toBase64Url())
            // A UTF-8 string (major type 3) becomes a JSON string. Note that JSON requires escaping
            // certain characters ([RFC8259], Section 7): quotation mark (U+0022), reverse
            // solidus (U+005C), and the "C0 control characters" (U+0000 through U+001F). All other
            // characters are copied unchanged into the JSON UTF-8 string
            is Tstr -> JsonPrimitive(asTstr)
            // An integer (major type 0 or 1) becomes a JSON number.
            is CborInt -> JsonPrimitive(asNumber)
            // A floating-point value (major type 7, additional information 25 through 27) becomes
            // a JSON number if it is finite (that is, it can be represented in a JSON number); if
            // the value is non-finite (NaN, or positive or negative Infinity), it is represented
            // by the substitute value.
            is CborDouble -> {
                val value = asDouble
                if (value.isFinite()) {
                    JsonPrimitive(value)
                } else {
                    JsonNull
                }
            }
            is CborFloat -> {
                val value = asFloat
                if (value.isFinite()) {
                    JsonPrimitive(value)
                } else {
                    JsonNull
                }
            }
            // An array (major type 4) becomes a JSON array.
            is CborArray -> buildJsonArray {
                items.forEach { add(it.toJson()) }
            }
            // A map (major type 5) becomes a JSON object. This is possible directly only if all keys
            // are UTF-8 strings. A converter might also convert other keys into UTF-8 strings
            // (such as by converting integers into strings containing their decimal representation);
            // however, doing so introduces a danger of key collision. Note also that, if tags on
            // UTF-8 strings are ignored as proposed below, this will cause a key collision if the
            // tags are different but the strings are the same.
            is CborMap -> buildJsonObject {
                items.forEach { (key, value) ->
                    val keyStr = when (key) {
                        is Tstr -> key.asTstr
                        else -> Cbor.toDiagnostics(key, emptySet())
                    }
                    put(keyStr, value.toJson())
                }
            }
            // Indefinite-length items are made definite before conversion.
            is IndefLengthBstr -> {
                val combined = ByteStringBuilder()
                chunks.forEach { combined.append(it) }
                JsonPrimitive(combined.toByteString().toByteArray().toBase64())
            }
            // Indefinite-length items are made definite before conversion.
            is IndefLengthTstr -> {
                val combined = StringBuilder()
                chunks.forEach { combined.append(it) }
                JsonPrimitive(combined.toString())
            }
            is Simple -> {
                when (value) {
                    // False (major type 7, additional information 20) becomes a JSON false.
                    Simple.FALSE.value -> JsonPrimitive(false)
                    // True (major type 7, additional information 21) becomes a JSON true.
                    Simple.TRUE.value -> JsonPrimitive(true)
                    // Null (major type 7, additional information 22) becomes a JSON null.
                    Simple.NULL.value -> JsonNull
                    // Any other simple value (major type 7, any additional information value not
                    // yet discussed) is represented by the substitute value.
                    else -> JsonNull
                }
            }
            is Tagged -> {
                if (taggedItem is Bstr) {
                    when (tagNumber) {
                        Tagged.UNSIGNED_BIGNUM,
                        Tagged.NEGATIVE_BIGNUM -> {
                            // A bignum (major type 6, tag number 2 or 3) is represented by encoding its byte string
                            // in base64url without padding and becomes a JSON string. For tag number 3 (negative
                            // bignum), a "~" (ASCII tilde) is inserted before the base-encoded value. (The
                            // conversion to a binary blob instead of a number is to prevent a likely numeric
                            // overflow for the JSON decoder.)
                            if (tagNumber == Tagged.NEGATIVE_BIGNUM) {
                                JsonPrimitive("~" + taggedItem.value.toBase64Url())
                            } else {
                                JsonPrimitive(taggedItem.value.toBase64Url())
                            }
                        }
                        // A byte string with an encoding hint (major type 6, tag number 21 through 23) is
                        // encoded as described by the hint and becomes a JSON string.
                        Tagged.ENCODING_HINT_BASE64URL -> JsonPrimitive(taggedItem.value.toBase64Url())
                        Tagged.ENCODING_HINT_BASE64_WITH_PADDING -> JsonPrimitive(Base64.encode(taggedItem.value))
                        Tagged.ENCODING_HINT_HEX -> JsonPrimitive(taggedItem.value.toHex(upperCase = true))
                        // For all other tags (major type 6, any other tag number), the tag content is represented
                        // as a JSON value; the tag number is ignored.
                        else -> taggedItem.toJson()
                    }
                } else {
                    // For all other tags (major type 6, any other tag number), the tag content is represented
                    // as a JSON value; the tag number is ignored.
                    taggedItem.toJson()
                }
            }
            is RawCbor -> Cbor.decode(encodedCbor).toJson()
        }
    }

    /**
     * The value of a data item containing a COSE_Key.
     *
     * This is equivalent to calling [CoseKey.fromDataItem].
     *
     * @throws IllegalArgumentException if not the data item isn't a COSE_Key.
     */
    val asCoseKey: CoseKey
        get() = CoseKey.fromDataItem(this)

    /**
     * The value of a data item containing a COSE_Sign1.
     *
     * This is equivalent to calling [CoseSign1.fromDataItem].
     *
     * @throws IllegalArgumentException if not the data item isn't a COSE_Sign1.
     */
    val asCoseSign1: CoseSign1
        get() = CoseSign1.fromDataItem(this)

    /**
     * The value of a data item containing a COSE_Mac0.
     *
     * This is equivalent to calling [CoseMac0.fromDataItem].
     *
     * @throws IllegalArgumentException if not the data item isn't a COSE_Sign1.
     */
    val asCoseMac0: CoseMac0
        get() = CoseMac0.fromDataItem(this)

    /**
     * The value of a data item containing a COSE label (either number or string)
     *
     * This is equivalent to calling [CoseLabel.fromDataItem]
     *
     * @throws IllegalArgumentException if not the data item isn't a COSE label
     */
    val asCoseLabel: CoseLabel
        get() = CoseLabel.fromDataItem(this)

    /**
     * The value of a data item containing a certificate.
     *
     * This is equivalent to calling [X509Cert.fromDataItem].
     *
     * @throws IllegalArgumentException if not the data item isn't a certificate.
     */
    val asX509Cert: X509Cert
        get() = X509Cert.fromDataItem(this)

    /**
     * The value of a data item containing a certificate chain.
     *
     * This is equivalent to calling [X509CertChain.fromDataItem].
     *
     * @throws IllegalArgumentException if not the data item isn't a certificate chain.
     */
    val asX509CertChain: X509CertChain
        get() = X509CertChain.fromDataItem(this)
}
