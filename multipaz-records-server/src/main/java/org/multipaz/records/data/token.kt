package org.multipaz.records.data

import kotlin.time.Clock
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Uint
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.time.Duration

/**
 * Types of tokens for client-server communication.
 */
enum class TokenType(val code: Byte) {
    FE_TOKEN(0),  // used by the font-end to access/edit the identity, given as `token`
    ACCESS_TOKEN(1),  // used by openid4vci to access identity data
    REFRESH_TOKEN(2),  // used by openid4vci to obtain new access token
    ADMIN_COOKIE(-1),  // cookie required to create/edit identities
}

/**
 * Creates an opaque token that can be safely given to the client. On the server the [Identity]
 * objects are identified by its id, which stays the same. When referencing an identity
 * from the client, we do not want the client to be able to play any games, thus the actual
 * server-side id and a small amount of metadata is encrypted using server secret key.
 *
 * We use these tokens for various purposes (identified by [TokenType]) and always validate
 * that the token we get is actually created for its intended purpose. Also a token may contain
 * expiration time and it can only be used until it expires.
 */
suspend fun idToToken(type: TokenType, id: String, expiresIn: Duration): String {
    val buf = ByteStringBuilder()
    buf.append(type.code)
    val idBytes = id.toByteArray()
    check(idBytes.size <= 255)
    buf.append(idBytes.size.toByte())
    buf.append(idBytes)
    if (!expiresIn.isInfinite()) {
        val expiration = Clock.System.now() + expiresIn
        buf.append(Cbor.encode(Uint(expiration.epochSeconds.toULong())))
    }
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    return cipher.encrypt(buf.toByteString().toByteArray()).toBase64Url()
}

/**
 * Decodes a token into server-side id, its type and expiration time.
 */
suspend fun tokenToId(type: TokenType, code: String): String {
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    val buf = cipher.decrypt(code.fromBase64Url())
    if (buf[0] != type.code) {
        throw InvalidRequestException(
            "Not required token type, need ${type.code}, got ${buf[0].toInt()}")
    }
    val len = buf[1].toInt()
    if (2 + len != buf.size) {
        val offsetAndExpirationTimeEpochSeconds = Cbor.decode(buf, 2 + len)
        // returned offset should be at the end of the string
        if (offsetAndExpirationTimeEpochSeconds.first != buf.size) {
            throw InvalidRequestException("Decoding error")
        }
        // expiration time should not be in the past
        if (offsetAndExpirationTimeEpochSeconds.second.asNumber < Clock.System.now().epochSeconds) {
            throw InvalidRequestException("Token expired")
        }
    }
    return String(buf, 2, len)
}
