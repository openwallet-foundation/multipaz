package org.multipaz.sdjwt

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url


/**
 * A SD-JWT+KB according to
 * [draft-ietf-oauth-selective-disclosure-jwt](https://datatracker.ietf.org/doc/draft-ietf-oauth-selective-disclosure-jwt/).
 *
 * When a [SdJwtKb] instance is initialized, cursory checks on the provided string with compact serialization
 * are performed. Full verification of the SD-JWT+KB can be done using the [verify] method.
 *
 * A wallet can create an instance of this class by using one of the [SdJwt.present] methods and can then send
 * [compactSerialization] to a verifier via e.g. OpenID4VP.
 *
 * This class is immutable.
 *
 * @param compactSerialization the compact serialization of the SD-JWT+KB.
 * @throws IllegalArgumentException if the given compact serialization is malformed.
 */
class SdJwtKb(
    val compactSerialization: String
) {
    /** The SD-JWT part of the SD-JWT+KB */
    lateinit var sdJwt: SdJwt

    private val kbHeader: String
    private val kbBody: String
    private val kbSignature: String

    /**
     * The body of the SD-JWT+KB.
     *
     * This contains the claims signed by the device-bound key.
     */
    val jwtBody: JsonObject by lazy {
        Json.decodeFromString<JsonObject>(kbBody.fromBase64Url().decodeToString())
    }

    init {
        if (compactSerialization.endsWith('~')) {
            throw IllegalArgumentException("Given compact serialization appears to be a SD-JWT, not SD-JWT+KB")
        }
        val lastTilde = compactSerialization.lastIndexOf('~')
        val sdJwtCompactSerialization = compactSerialization.substring(0, lastTilde + 1)
        val kbJwt = compactSerialization.substring(lastTilde + 1, compactSerialization.length)

        sdJwt = SdJwt(sdJwtCompactSerialization)

        val kbJwtSplits = kbJwt.split(".")
        if (kbJwtSplits.size != 3) {
            throw IllegalArgumentException("KB-JWT in SD-JWT+KB didn't consist of three parts: $kbJwt")
        }
        kbHeader = kbJwtSplits[0]
        kbBody = kbJwtSplits[1]
        kbSignature = kbJwtSplits[2]
    }

    /**
     * Verifies a SD-JWT+KB according to Section 7.3 of the SD-JWT specification
     *
     * @param issuerKey the issuer's key to use for verification.
     * @param checkNonce a function to check that the nonce in the KB JWT is as expected.
     * @param checkAudience a function to check that the audience in the KB JWT is as expected.
     * @param checkCreationTime a function to check that the creation time in the KB JWT is as expected.
     * @return the processed SD-JWT payload,
     * @throws SignatureVerificationException if the issuer signature or key-binding signature failed to validate.
     * @throws IllegalStateException if [checkNonce], [checkAudience], or [checkCreationTime] returns false.
     */
    fun verify(
        issuerKey: EcPublicKey,
        checkNonce: (nonce: String) -> Boolean,
        checkAudience: (audience: String) -> Boolean,
        checkCreationTime: (creationTime: Instant) -> Boolean
    ): JsonObject {
        try {
            JsonWebSignature.verify("$kbHeader.$kbBody.$kbSignature", sdJwt.kbKey!!)
        } catch (e: Throwable) {
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
        val creationTime = Instant.fromEpochSeconds(jwtBody["iat"]!!.jsonPrimitive.content.toLong())
        if (!checkCreationTime(creationTime)) {
            throw IllegalStateException("Failed verification of creationTime")
        }

        return sdJwt.verify(issuerKey)
    }
}