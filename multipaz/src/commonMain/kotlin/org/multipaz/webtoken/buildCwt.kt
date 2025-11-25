package org.multipaz.webtoken

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.webtoken.WebTokenClaim.Companion.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Creates a CWT message signed with the given key.
 *
 * CWT header contains type, signature algorithm and, unless the key is
 * [AsymmetricKey.Anonymous], key identification (either `kid` or `x5chain`). The body of the
 * CWT will have issuance time (`iat`) and optionally expiration time (`exp`), unless
 * [creationTime] is set to [Instant.DISTANT_PAST]
 *
 * @param type CWT type as String
 * @param key private key to sign CWT and provide key identifying information in the CWT header
 * @param protectedHeaders headers that are covered by the signature
 * @param creationTime CWT issuance timestamp (`iat`)
 * @param expiresIn validity duration for the CWT (if any)
 * @param body JSON object builder block for CWT body
 * @return signed CWT
 */
suspend fun buildCwt(
    type: String,
    key: AsymmetricKey,
    protectedHeaders: Map<CoseLabel, DataItem> = mapOf(),
    unprotectedHeaders: Map<CoseLabel, DataItem> = mapOf(),
    creationTime: Instant = Clock.System.now(),
    expiresIn: Duration? = null,
    body: suspend MapBuilder<*>.() -> Unit
): ByteArray = buildCwt(type.toDataItem(), key, protectedHeaders,
    unprotectedHeaders, creationTime, expiresIn, body)

/**
 * Creates a CWT message signed with the given key.
 *
 * CWT header contains type, signature algorithm and, unless the key is
 * [AsymmetricKey.Anonymous], key identification (either `kid` or `x5chain`). The body of the
 * CWT will have issuance time (`iat`) and optionally expiration time (`exp`), unless
 * [creationTime] is set to [Instant.DISTANT_PAST]
 *
 * @param type CWT type as [DataItem], must be a [Tstr] or an [Uint]
 * @param key private key to sign CWT and provide key identifying information in the CWT header
 * @param protectedHeaders headers that are covered by the signature
 * @param creationTime CWT issuance timestamp (`iat`)
 * @param expiresIn validity duration for the CWT (if any)
 * @param builderAction JSON object builder block for CWT body
 * @return signed CWT
 */
suspend fun buildCwt(
    type: DataItem,
    key: AsymmetricKey,
    protectedHeaders: Map<CoseLabel, DataItem> = mapOf(),
    unprotectedHeaders: Map<CoseLabel, DataItem> = mapOf(),
    creationTime: Instant = Clock.System.now(),
    expiresIn: Duration? = null,
    builderAction: suspend MapBuilder<*>.() -> Unit
): ByteArray {
    require(type is Tstr || type is Uint)
    val messageBuilder = CborMap.builder()
    if (creationTime != Instant.DISTANT_PAST) {
        expiresIn?.let {
            messageBuilder.put(WebTokenClaim.Exp, creationTime + expiresIn)
        }
        messageBuilder.put(WebTokenClaim.Iat, creationTime)
    }
    val adjustedProtectedHeaders = protectedHeaders.toMutableMap()
    adjustedProtectedHeaders[Cose.COSE_LABEL_TYP.toCoseLabel] = type
    key.addProtectedHeaders(adjustedProtectedHeaders)
    builderAction.invoke(messageBuilder)
    val cose = Cose.coseSign1Sign(
        signingKey = key,
        message = Cbor.encode(messageBuilder.end().build()),
        includeMessageInPayload = true,
        protectedHeaders = adjustedProtectedHeaders,
        unprotectedHeaders = unprotectedHeaders
    )
    return Cbor.encode(Tagged(
        tagNumber = Tagged.COSE_SIGN1,
        taggedItem = cose.toDataItem()
    ))
}

private fun AsymmetricKey.addProtectedHeaders(
    protectedHeader: MutableMap<CoseLabel, DataItem>
) {
    when (this) {
        is AsymmetricKey.X509CertifiedSecureAreaBased,
        is AsymmetricKey.X509CertifiedExplicit -> {
            protectedHeader[Cose.COSE_LABEL_X5CHAIN.toCoseLabel] = certChain.toDataItem()
        }
        is AsymmetricKey.NamedExplicit,
        is AsymmetricKey.NamedSecureAreaBased -> {
            // Must be a Bstr, not Tstr
            protectedHeader[Cose.COSE_LABEL_KID.toCoseLabel] = keyId.encodeToByteArray().toDataItem()
        }
        is AsymmetricKey.AnonymousExplicit,
        is AsymmetricKey.AnonymousSecureAreaBased -> {}
    }
}
