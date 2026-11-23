package org.multipaz.sdjwt

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toNumericDateInstant


/**
 * A SD-JWT+KB according to [RFC 9901](https://datatracker.ietf.org/doc/rfc9901/).
 *
 * When a [SdJwtKb] instance is initialized, cursory checks on the provided string with compact serialization
 * are performed. Full verification of the SD-JWT+KB can be done using the [verify] method.
 *
 * A wallet can create an instance of this class by using one of the [SdJwt.present] methods and can then send
 * [compactSerialization] to a verifier via e.g. OpenID4VP.
 *
 * This class is immutable.
 *
 * @property compactSerialization the compact serialization of the SD-JWT+KB, without the
 *   [kbDisclosures] of a Delegated SD-JWT, if any.
 * @property sdJwt The SD-JWT part of the SD-JWT+KB.
 * @property kbDisclosures the disclosures that follow the Key Binding JWT in a Delegated SD-JWT
 *   (see [fromDelegatedCompactSerialization]), empty for an ordinary SD-JWT+KB.
 * @throws IllegalArgumentException if the given compact serialization is malformed.
 */
class SdJwtKb private constructor(
    val compactSerialization: String,
    val sdJwt: SdJwt,
    private val kbHeader: String,
    private val kbBody: String,
    private val kbSignature: String,
    val kbDisclosures: List<String> = emptyList(),
) {
    /**
     * The header of the Key Binding JWT.
     */
    val jwtHeader: JsonObject by lazy {
        Json.decodeFromString<JsonObject>(kbHeader.fromBase64Url().decodeToString())
    }

    /**
     * The body of the SD-JWT+KB.
     *
     * This contains the claims signed by the device-bound key.
     */
    val jwtBody: JsonObject by lazy {
        Json.decodeFromString<JsonObject>(kbBody.fromBase64Url().decodeToString())
    }

    /**
     * Verifies a SD-JWT+KB according to Section 7.3 of the SD-JWT specification.
     *
     * Note that per Section 7.3 of RFC 9901, the returned JSON object is the Processed SD-JWT
     * Payload containing only the Issuer-signed claims (with Disclosures resolved). The
     * device-signed claims in the Key Binding JWT (such as `nonce`, `aud`, `iat`, and any
     * transaction response claims) are not included in this returned payload and must be
     * retrieved separately from [jwtBody].
     *
     * @param issuerKey the issuer's key to use for verification or `null` to not perform issuer signature validation.
     * @param checkNonce a function to check that the nonce in the KB JWT is as expected.
     * @param checkAudience a function to check that the audience in the KB JWT is as expected.
     * @param checkCreationTime a function to check that the creation time in the KB JWT is as expected.
     * @param transactionData transaction data that was sent with the request.
     * @param expectedType the `typ` the KB-JWT must have: `kb+jwt`, as RFC 9901 §4.3 requires. A
     *   profile that defines its own passes it, such as Delegate SD-JWT's `kb+sd-jwt` (§5.1.4).
     *   A Delegated presentation, which ends with disclosures after the KB-JWT, is parsed with
     *   [fromDelegatedCompactSerialization]; its [kbDisclosures] are not checked here, the
     *   transaction types that returned them check them.
     * @return the processed SD-JWT payload.
     * @throws SignatureVerificationException if the issuer signature or key-binding signature failed to validate.
     * @throws IllegalStateException if [checkNonce], [checkAudience], or [checkCreationTime] returns false.
     */
    suspend fun verify(
        issuerKey: PublicKey?,
        checkNonce: (nonce: String) -> Boolean,
        checkAudience: (audience: String) -> Boolean,
        checkCreationTime: (creationTime: Instant) -> Boolean,
        transactionData: List<TransactionData<*>> = listOf(),
        expectedType: String = "kb+jwt",
    ): JsonObject {
        val typHeader = (jwtHeader["typ"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (typHeader != expectedType) {
            throw IllegalStateException("KB-JWT header 'typ' must be '$expectedType', got '$typHeader'")
        }
        val kbKey = sdJwt.kbKey
            ?: throw IllegalStateException("SD-JWT does not contain a key-binding key in 'cnf'")
        try {
            JsonWebSignature.verify("$kbHeader.$kbBody.$kbSignature", kbKey)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw SignatureVerificationException("Error validating KB signature", e)
        }

        val compactSerializationWithoutKB = compactSerialization.substringBeforeLast("~") + "~"
        val expectedSdHash = Crypto.digest(sdJwt.digestAlg, compactSerializationWithoutKB.encodeToByteArray()).toBase64Url()
        val sdHash = jwtBody["sd_hash"]!!.jsonPrimitive.content
        if (expectedSdHash != sdHash) {
            throw IllegalStateException("Error validating KB body - sd_hash didn't match")
        }

        if (!checkNonce(jwtBody["nonce"]!!.jsonPrimitive.content)) {
            throw IllegalStateException("Failed verification of nonce")
        }
        if (!checkAudience(jwtBody["aud"]!!.jsonPrimitive.content)) {
            throw IllegalStateException("Failed verification of audience")
        }
        val creationTime = jwtBody["iat"]?.toNumericDateInstant("iat")
            ?: throw IllegalStateException("Missing 'iat' claim in KB-JWT")
        if (!checkCreationTime(creationTime)) {
            throw IllegalStateException("Failed verification of creationTime")
        }

        val isIso18013_5 = transactionData.any { it.protocol == TransactionProtocol.ISO_18013_5 }
        val hashes = jwtBody["transaction_data_hashes"]
        if (isIso18013_5) {
            if (hashes != null) {
                throw IllegalStateException("Unexpected 'transaction_data_hashes' in ISO 18013-5 presentation")
            }
            for (transaction in transactionData) {
                // ISO/IEC 18013-5 has no place for disclosures after the Key Binding JWT.
                transaction.verifySdJwtResponse(transaction.sdJwtResponseClaims(jwtBody), kbDisclosures)
            }
        } else {
            if (hashes == null) {
                if (transactionData.isNotEmpty()) {
                    throw IllegalStateException("Transaction data was not processed")
                }
            } else {
                hashes as? JsonArray
                    ?: throw IllegalStateException("Invalid 'transaction_data_hashes'")
                if (hashes.size != transactionData.size) {
                    if (transactionData.isEmpty()) {
                        throw IllegalStateException("Unexpected 'transaction_data_hashes'")
                    } else {
                        throw IllegalStateException("Unexpected 'transaction_data_hashes' size")
                    }
                }
                val hashAlgorithm = try {
                    jwtBody["transaction_data_hashes_alg"]?.jsonPrimitive?.content?.let {
                        Algorithm.fromHashAlgorithmIdentifier(it)
                    } ?: Algorithm.SHA256
                } catch (err: Exception) {
                    throw IllegalStateException("Unknown or invalid transaction data hash algorithm", err)
                }
                transactionData.zip(hashes).forEach { (transaction, hash) ->
                    if (hash !is JsonPrimitive || !hash.isString) {
                        throw IllegalStateException("Invalid transaction data hash value")
                    }
                    val responseHash = ByteString(hash.content.fromBase64Url())
                    if (transaction.computeHash(hashAlgorithm) != responseHash) {
                        throw IllegalStateException("Transaction data hash mismatch")
                    }
                }
            }
        }

        return sdJwt.verify(issuerKey)
    }

    companion object {
        /**
         * Creates a [SdJwtKb] from the Delegated SD-JWT compact serialization of
         * draft-ietf-oauth-delegate-sd-jwt §5.1.1, as [SdJwt.presentWithKbDisclosures] produces it:
         * `<SD-JWT>~~<KB-JWT>~<Disclosure 1>~...~<Disclosure M>~`.
         *
         * The result is the SD-JWT+KB made of the SD-JWT and the KB-JWT, with the disclosures that
         * follow the KB-JWT in [kbDisclosures]. A chain of several Key Binding SD-JWTs is not
         * supported.
         *
         * @param compactSerialization the compact serialization of the Delegated SD-JWT.
         * @return a [SdJwtKb] instance.
         * @throws IllegalArgumentException if the given compact serialization is malformed, does not
         *   have exactly one `~~`, or has no disclosure after the KB-JWT.
         */
        suspend fun fromDelegatedCompactSerialization(compactSerialization: String): SdJwtKb {
            val separator = compactSerialization.indexOf("~~")
            require(separator >= 0 && compactSerialization.indexOf("~~", separator + 1) < 0) {
                "A Delegated SD-JWT has exactly one '~~' between the SD-JWT and the Key Binding JWT"
            }
            require(compactSerialization.endsWith('~')) {
                "A Delegated SD-JWT ends with '~' after the last disclosure"
            }
            // The part after "~~" is `<KB-JWT>~<Disclosure 1>~...~<Disclosure M>~`.
            val components = compactSerialization.substring(separator + 2).removeSuffix("~").split("~")
            val kbJwt = components.first()
            val kbDisclosures = components.drop(1)
            require(kbDisclosures.isNotEmpty() && kbDisclosures.none { it.isEmpty() }) {
                "A Delegated SD-JWT has at least one disclosure after the Key Binding JWT, none empty"
            }
            // The SD-JWT+KB made of the SD-JWT and the Key Binding JWT: its sd_hash covers the SD-JWT only.
            val sdJwtKb = fromCompactSerialization(compactSerialization.take(separator + 1) + kbJwt)
            return SdJwtKb(
                compactSerialization = sdJwtKb.compactSerialization,
                sdJwt = sdJwtKb.sdJwt,
                kbHeader = sdJwtKb.kbHeader,
                kbBody = sdJwtKb.kbBody,
                kbSignature = sdJwtKb.kbSignature,
                kbDisclosures = kbDisclosures,
            )
        }

        /**
         * Creates a [SdJwtKb] from compact serialization.
         *
         * @param compactSerialization the compact serialization of the SD-JWT.
         * @return a [SdJwtKb] instance.
         */
        suspend fun fromCompactSerialization(compactSerialization: String): SdJwtKb {
            if (compactSerialization.endsWith('~')) {
                throw IllegalArgumentException("Given compact serialization appears to be a SD-JWT, not SD-JWT+KB")
            }
            val lastTilde = compactSerialization.lastIndexOf('~')
            val sdJwtCompactSerialization = compactSerialization.take(lastTilde + 1)
            val kbJwt = compactSerialization.substring(lastTilde + 1, compactSerialization.length)

            val sdJwt = SdJwt.fromCompactSerialization(sdJwtCompactSerialization)

            val kbJwtSplits = kbJwt.split(".")
            if (kbJwtSplits.size != 3) {
                throw IllegalArgumentException("KB-JWT in SD-JWT+KB didn't consist of three parts: $kbJwt")
            }
            return SdJwtKb(
                compactSerialization = compactSerialization,
                sdJwt = sdJwt,
                kbHeader = kbJwtSplits[0],
                kbBody = kbJwtSplits[1],
                kbSignature = kbJwtSplits[2]
            )
        }
    }
}