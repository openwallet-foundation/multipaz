package org.multipaz.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.UnlockReason
import org.multipaz.securearea.SecureArea
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url

/**
 * JSON Web Signature support
 */
object JsonWebSignature {
    /**
     * Sign a claims set.
     *
     * Use [org.multipaz.jwt.buildJwt] instead.
     *
     * @param key the key to sign with.
     * @param signatureAlgorithm a fully-specified signature algorithm to use.
     * @param claimsSet the claims set.
     * @param type the value to put in the "typ" header parameter or `null`.
     * @param x5c: the certificate chain to put in the "x5c" header parameter or `null`.
     * @return the compact serialization with the JWS.
     */
    @Deprecated("Use org.multipaz.jwt.buildJwt instead")
    fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        claimsSet: JsonObject,
        type: String?,
        x5c: X509CertChain?
    ): String {
        require(signatureAlgorithm.fullySpecified) {
            "signatureAlgorithm must be fully specified"
        }
        val headerStr = buildJsonObject {
            put("alg", JsonPrimitive(signatureAlgorithm.joseAlgorithmIdentifier!!))
            type?.let { put("typ", JsonPrimitive(it)) }
            x5c?.let { put("x5c", x5c.toX5c()) }
        }.toString().encodeToByteArray().toBase64Url()
        val bodyStr = claimsSet.toString().encodeToByteArray().toBase64Url()
        val toBeSigned = "$headerStr.$bodyStr".encodeToByteArray()

        val signature = Crypto.sign(
            key = key,
            signatureAlgorithm = signatureAlgorithm,
            message = toBeSigned
        )
        val signatureStr = (signature.r + signature.s).toBase64Url()
        return "$headerStr.$bodyStr.$signatureStr"
    }

    /**
     * Sign a claims set using a [SecureArea].
     *
     * Use [org.multipaz.jwt.buildJwt] instead.
     *
     * @param secureArea the [SecureArea] for the key to sign with.
     * @param alias the alias for key to sign with.
     * @param keyUnlockData the [KeyUnlockData] to use or `null`.
     * @param claimsSet the claims set.
     * @param type the value to put in the "typ" header parameter or `null`.
     * @param x5c: the certificate chain to put in the "x5c" header parameter or `null`.
     * @return the compact serialization of the JWS.
     */
    @Deprecated("Use org.multipaz.jwt.buildJwt instead")
    suspend fun sign(
        secureArea: SecureArea,
        alias: String,
        unlockReason: UnlockReason,
        claimsSet: JsonObject,
        type: String?,
        x5c: X509CertChain?
    ): String {
        val headerStr = buildJsonObject {
            put("alg", JsonPrimitive(secureArea.getKeyInfo(alias).algorithm.joseAlgorithmIdentifier!!))
            type?.let { put("typ", JsonPrimitive(it)) }
            x5c?.let { put("x5c", x5c.toX5c()) }
        }.toString().encodeToByteArray().toBase64Url()
        val bodyStr = claimsSet.toString().encodeToByteArray().toBase64Url()
        val toBeSigned = "$headerStr.$bodyStr".encodeToByteArray()

        val signature = secureArea.sign(
            alias = alias,
            dataToSign = toBeSigned,
            unlockReason = unlockReason
        )
        val signatureStr = (signature.r + signature.s).toBase64Url()
        return "$headerStr.$bodyStr.$signatureStr"
    }

    /**
     * Verify the signature of a JWS.
     *
     * @param jws the compact serialization of the JWS.
     * @param publicKey the key to use for verification
     * @throws Throwable if verification fails.
     */
    fun verify(
        jws: String,
        publicKey: EcPublicKey
    ) {
        val splits = jws.split(".")
        require(splits.size == 3) { "Malformed JWS" }
        val (headerStr, bodyStr, signatureStr) = splits
        val headerObj = Json.decodeFromString(JsonObject.serializer(), headerStr.fromBase64Url().decodeToString())

        val toBeVerified = "$headerStr.$bodyStr".encodeToByteArray()
        val signature = EcSignature.fromCoseEncoded(signatureStr.fromBase64Url())
        val algorithm = Algorithm.fromJoseAlgorithmIdentifier(headerObj["alg"]!!.jsonPrimitive.content)
        Crypto.checkSignature(
            publicKey = publicKey,
            message = toBeVerified,
            algorithm = algorithm,
            signature = signature
        )
    }

    /**
     * Information about a JSON Web Signature.
     *
     * @property claimsSet the claims set being signed.
     * @property type the value of the `typ` header element of `null.
     * @property x5c the certificate chain in the `x5c` header element or `null`.
     */
    data class JwsInfo(
        val claimsSet: JsonObject,
        val type: String?,
        val x5c: X509CertChain?
    )

    /**
     * Get information about a JWS.
     *
     * @param jws the compact serialization of the JWS.
     * @return a [JwsInfo] with information about the JWS.
     */
    fun getInfo(jws: String): JwsInfo {
        val splits = jws.split(".")
        require(splits.size == 3) { "Malformed JWS" }
        val (headerStr, bodyStr, _) = splits
        val headerObj = Json.decodeFromString(JsonObject.serializer(), headerStr.fromBase64Url().decodeToString())
        val claimsObj = Json.decodeFromString(JsonObject.serializer(), bodyStr.fromBase64Url().decodeToString())
        return JwsInfo(
            claimsSet = claimsObj,
            type = headerObj["typ"]!!.jsonPrimitive.content,
            x5c = headerObj["x5c"]?.let { X509CertChain.fromX5c(it) }
        )
    }
}
