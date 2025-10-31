package org.multipaz.jwt

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Creates a JWT message signed with the given key.
 *
 * JWT header contains type (`typ`), signature algorithm (`alg`) and, unless the key is
 * [AsymmetricKey.Anonymous], key identification (either `kid` or `x5c`). The body of the JWT will
 * have issuance time (`iat`) and optionally expiration time (`exp`), unless [creationTime] is
 * set to [Instant.DISTANT_PAST]
 *
 * @param type JWT type
 * @param key private key to sign JWT and provide key identifying information in the JWT header
 * @param header JSON object builder block to provide additional header fields
 * @param creationTime JWT issuance timestamp (`iat`)
 * @param expiresIn validity duration for the JWT (if any)
 * @param body JSON object builder block for JWT body
 * @return signed JWT
 */
suspend fun buildJwt(
    type: String,
    key: AsymmetricKey,
    header: suspend JsonObjectBuilder.() -> Unit = {},
    creationTime: Instant = Clock.System.now(),
    expiresIn: Duration? = null,
    body: suspend JsonObjectBuilder.() -> Unit
): String {
    val head = buildJsonObject {
        put("typ", type)
        key.addToJwtHeader(this)
        header.invoke(this)
    }.toString().encodeToByteArray().toBase64Url()

    val payload = buildJsonObject {
        if (creationTime != Instant.DISTANT_PAST) {
            expiresIn?.let {
                put("exp", (creationTime + expiresIn).epochSeconds)
            }
            put("iat", creationTime.epochSeconds)
        }
        body.invoke(this)
    }.toString().encodeToByteArray().toBase64Url()

    val message = "$head.$payload"
    val signature = key.sign(message.encodeToByteArray()).toCoseEncoded().toBase64Url()

    return "$message.$signature"
}

private fun AsymmetricKey.addToJwtHeader(header: JsonObjectBuilder) {
    header.put(
        key = "alg",
        value = algorithm.joseAlgorithmIdentifier ?:
            publicKey.curve.defaultSigningAlgorithmFullySpecified.joseAlgorithmIdentifier
    )
    when (this) {
        is AsymmetricKey.X509CertifiedSecureAreaBased,
        is AsymmetricKey.X509CertifiedExplicit -> header.put("x5c", certChain.toX5c())
        is AsymmetricKey.NamedExplicit,
        is AsymmetricKey.NamedSecureAreaBased -> header.put("kid", keyId)
        is AsymmetricKey.AnonymousExplicit,
        is AsymmetricKey.AnonymousSecureAreaBased -> {}
    }
}
