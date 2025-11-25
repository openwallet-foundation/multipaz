package org.multipaz.webtoken

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.MapBuilder
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.time.Instant

/**
 * Helper object to represent a web token (CWT or JWT) claim.
 *
 * Most of the claims go to the body of the web token, but some must be in the header. In JWT
 * all claims use string keys, but in CWT some use numeric keys.
 *
 * @param kType value type for the claim
 * @param strKey string key for the claim
 * @param numKey numeric key for the claim (in any)
 * @param header true if the claim must be in the header
 */
abstract class WebTokenClaim<T: Any>(
    val kType: KClass<T>,
    val strKey: String,
    val numKey: Long? = null,
    val header: Boolean = false
) {
    /** Token type (`typ`) claim. */
    object Typ: WebTokenClaim<String>(String::class, "typ", Cose.COSE_LABEL_TYP, true)

    /** Issuer (`iss`) claim. */
    object Iss: WebTokenClaim<String>(String::class, "iss", 1L)

    /** Subject (`sub`) claim. */
    object Sub: WebTokenClaim<String>(String::class, "sub", 2L)

    /** Audience (`aud`) claim. */
    object Aud: WebTokenClaim<String>(String::class, "aud", 3L)

    /** Expiration (`exp`) claim. */
    object Exp: WebTokenClaim<Instant>(Instant::class, "exp", 4L)

    /** Not-before (`nbf`) claim. */
    object Nbf: WebTokenClaim<Instant>(Instant::class, "nbf", 5L)

    /** Issued-at (`iat`) claim. */
    object Iat: WebTokenClaim<Instant>(Instant::class, "iat", 6L)

    /** JWT identifier (`jti`) claim. */
    object Jti: WebTokenClaim<String>(String::class, "jti")

    /** CWT identifier (`cti`) claim. */
    object Cti: WebTokenClaim<ByteString>(ByteString::class, "cti", 7L)

    /** Nonce (`nonce`) claim. */
    object Nonce: WebTokenClaim<String>(String::class, "nonce")

    /** Challenge (`challenge`) claim. */
    object Challenge: WebTokenClaim<String>(String::class, "challenge")

    /** HTTP method (`htm`) claim. */
    object Htm: WebTokenClaim<String>(String::class, "htm")

    /** HTTP url (`htu`) claim. */
    object Htu: WebTokenClaim<String>(String::class, "htu")

    /** Authorization token (`ath`) claim. */
    object Ath: WebTokenClaim<String>(String::class, "ath")

    companion object Companion {
        /**
         * Extract claim value from CBOR map
         *
         * @receiver map that holds the claim (typically CWT body)
         * @param claim claim to query
         * @return claim value or `null` if the map does not contain this claim
         */
        operator fun<T: Any> CborMap.get(claim: WebTokenClaim<T>): T? {
            val hasKey = if (claim.numKey != null) hasKey(claim.numKey) else hasKey(claim.strKey)
            if (!hasKey) {
                return null
            }
            val dataItem = if (claim.numKey != null) this[claim.numKey] else this[claim.strKey]
            val value = when (claim.kType) {
                String::class -> dataItem.asTstr
                Int::class -> dataItem.asNumber.toInt()
                Long::class -> dataItem.asNumber
                Float::class -> dataItem.asFloat
                Double::class -> dataItem.asDouble
                Instant::class -> Instant.fromEpochSeconds(dataItem.asNumber)
                ByteString::class -> ByteString(dataItem.asBstr)
                else -> throw IllegalArgumentException("Unsupported claim type")
            }
            return claim.kType.cast(value)
        }

        /**
         * Put the claim in a CBOR map.
         *
         * @receiver map to add the claim to
         * @param claim claim
         * @param value value for the claim
         */
        fun<T: Any> MapBuilder<*>.put(claim: WebTokenClaim<T>, value: T) {
            val dataItem = when (claim.kType) {
                String::class -> Tstr(value as String)
                Int::class -> (value as Int).toDataItem()
                Long::class -> (value as Long).toDataItem()
                Float::class -> (value as Float).toDataItem()
                Double::class -> (value as Double).toDataItem()
                Instant::class -> (value as Instant).epochSeconds.toDataItem()
                ByteString::class -> (value as ByteString).toByteArray().toDataItem()
                else -> throw IllegalArgumentException("Unsupported claim type")
            }
            if (claim.numKey != null) {
                put(claim.numKey, dataItem)
            } else {
                put(claim.strKey, dataItem)
            }
        }

        /**
         * Extract claim value from a JSON object
         *
         * @receiver object that holds the claim (typically JWT body)
         * @param claim claim to query
         * @return claim value or `null` if the object does not contain this claim
         */
        operator fun<T: Any> JsonObject.get(claim: WebTokenClaim<T>): T? {
            val json = this[claim.strKey] ?: return null
            val value = when (claim.kType) {
                String::class -> json.jsonPrimitive.content
                Int::class -> json.jsonPrimitive.intOrNull
                Long::class -> json.jsonPrimitive.longOrNull
                Float::class -> json.jsonPrimitive.floatOrNull
                Double::class -> json.jsonPrimitive.doubleOrNull
                Instant::class -> json.jsonPrimitive.longOrNull?.let {
                    Instant.fromEpochSeconds(it)
                }
                ByteString::class -> ByteString(json.jsonPrimitive.content.fromBase64Url())
                else -> throw IllegalArgumentException("Unsupported claim type")
            }
            return claim.kType.cast(value)
        }

        /**
         * Put the claim in a JSON object.
         *
         * @receiver object builder to add the claim to
         * @param claim claim
         * @param value value for the claim
         */
        fun<T: Any> JsonObjectBuilder.put(claim: WebTokenClaim<T>, value: T) {
            when (claim.kType) {
                String::class -> this.put(claim.strKey, value as String)
                Int::class -> this.put(claim.strKey, value as Int)
                Long::class -> this.put(claim.strKey, value as Long)
                Float::class -> this.put(claim.strKey, value as Float)
                Double::class -> this.put(claim.strKey, value as Double)
                Instant::class -> this.put(claim.strKey, (value as Instant).epochSeconds)
                ByteString::class -> this.put(claim.strKey, (value as ByteString).toByteArray().toBase64Url())
                else -> throw IllegalArgumentException("Unsupported claim type")
            }
        }
    }
}