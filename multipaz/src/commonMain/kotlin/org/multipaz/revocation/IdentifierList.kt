package org.multipaz.revocation

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.webtoken.WebTokenCheck
import org.multipaz.webtoken.WebTokenClaim
import org.multipaz.webtoken.WebTokenClaim.Companion.put
import org.multipaz.webtoken.buildCwt
import org.multipaz.webtoken.validateCwt
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Revocation list as defined in ISO/IEC 18013-5 Section 12.3.6.4 "Identifier list details"
 *
 * Documents with identifiers in the [identifiers] set are considered invalid/revoked. Document
 * identifier for the purposes of revocation is stored [RevocationStatus.IdentifierList.id] in
 * [org.multipaz.mdoc.mso.MobileSecurityObject.revocationStatus].
 *
 * @param [identifiers] set of identifiers of revoked document
 * @param [creationTime] time when this object was created
 */
class IdentifierList(
    private val identifiers: Set<ByteString>,
    val creationTime: Instant = Clock.System.now()
) {
    suspend fun serializeAsCwt(
        key: AsymmetricKey,
        subject: String,
        expiresIn: Duration = 20.minutes + Random.Default.nextInt(1000).seconds
    ) = buildCwt(
        key = key,
        type = "application/identifierlist+cwt",
        expiresIn = expiresIn
    ) {
        put(WebTokenClaim.Sub, subject)
        putCborMap(IDENTIFIER_LIST_CLAIM) {
            putCborMap("identifiers") {
                identifiers.forEach { identifier ->
                    putCborMap(identifier.toByteArray().toDataItem()) {}
                }
            }
        }
        put(TTL_CLAIM, expiresIn.inWholeSeconds)
    }

    fun contains(identifier: ByteString) = identifiers.contains(identifier)

    fun contains(identifier: Bstr) = identifiers.contains(ByteString(identifier.value))

    class Builder {
        private val identifiers = mutableSetOf<ByteString>()

        fun add(identifier: ByteString) {
            identifiers.add(identifier)
        }

        fun build(): IdentifierList {
            return IdentifierList(identifiers.toSet())
        }
    }

    companion object {
        private const val IDENTIFIER_LIST_CLAIM = 65530L
        private const val TTL_CLAIM = 65534L

        suspend fun fromCwt(
            cwt: ByteArray,
            publicKey: EcPublicKey? = null,
            checks: Map<WebTokenCheck, String> = mapOf()
        ): IdentifierList {
            val body = validateCwt(
                cwt = cwt,
                cwtName = "Identifier List",
                checks = buildMap {
                    put(WebTokenCheck.TYP, "application/identifierlist+cwt")
                    putAll(checks)
                },
                publicKey = publicKey,
                maxValidity = 365.days
            )
            if (!body.hasKey(IDENTIFIER_LIST_CLAIM)) {
                throw IllegalArgumentException("not a valid identifier list CWT")
            }
            val identifierListClaim = body[IDENTIFIER_LIST_CLAIM] as? CborMap
                ?: throw IllegalArgumentException("not a valid identifier list CWT")
            if (!identifierListClaim.hasKey("identifiers")) {
                throw IllegalArgumentException("not a valid identifier list CWT")
            }
            val identifiers = identifierListClaim["identifiers"] as? CborMap
                ?: throw IllegalArgumentException("not a valid identifier list CWT")
            val builder = Builder()
            for (identifier in identifiers.items.keys) {
                if (identifier is Bstr) {
                    builder.add(ByteString(identifier.value))
                }
            }
            return builder.build()
        }
    }
}