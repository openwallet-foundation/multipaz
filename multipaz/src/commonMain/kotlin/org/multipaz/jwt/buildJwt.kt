package org.multipaz.jwt

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.SigningKey
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Creates a JWT message signed with the given key.
 *
 * JWT header contains type (`typ`), signature algorithm (`alg`) and key identification (either
 * `kid` or `x5c`). The body of the JWT always contains issuance time (`iat`) and optionally
 * expiration time (`exp`).
 *
 * @param type JWT type
 * @param key private key to sign JWT and provide key identifying information in the JWT header
 * @param header JSON object builder block to provide additional header fields
 * @param clock clock to provide issuance and expiration time
 * @param expiresIn validity duration for the JWT (if any)
 * @param body JSON object builder block for JWT body
 * @return signed JWT
 */
suspend fun buildJwt(
    type: String,
    key: SigningKey,
    header: JsonObjectBuilder.() -> Unit = {},
    clock: Clock = Clock.System,
    expiresIn: Duration? = null,
    body: JsonObjectBuilder.() -> Unit
): String {
    val head = buildJsonObject {
        put("typ", type)
        key.addToJwtHeader(this)
        header.invoke(this)
    }.toString().encodeToByteArray().toBase64Url()

    val now = clock.now()
    val payload = buildJsonObject {
        expiresIn?.let {
            put("exp", (now + expiresIn).epochSeconds)
        }
        put("iat", now.epochSeconds)
        body.invoke(this)
    }.toString().encodeToByteArray().toBase64Url()

    val message = "$head.$payload"
    val signature = key.sign(message.encodeToByteArray()).toCoseEncoded().toBase64Url()

    return "$message.$signature"
}
