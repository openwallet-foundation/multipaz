package org.multipaz.revocation

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.webtoken.WebTokenCheck
import org.multipaz.webtoken.WebTokenClaim
import org.multipaz.webtoken.WebTokenClaim.Companion.put
import org.multipaz.webtoken.buildCwt
import org.multipaz.webtoken.validateCwt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
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
 * @param [expirationTime] time when this object expires and should be refreshed
 */
class IdentifierList(
    private val identifiers: Set<ByteString>,
    val creationTime: Instant = Clock.System.now(),
    val expirationTime: Instant = creationTime + 20.minutes
) {
    /**
     * Serializes this list as CWT.
     *
     * @param key key for CWT signing
     * @param subject CWT subject field (typically URL which is used to serve this identifier list)
     * @return serialized CWT
     */
    suspend fun serializeAsCwt(
        key: AsymmetricKey,
        subject: String,
    ) = buildCwt(
        key = key,
        type = "application/identifierlist+cwt",
        creationTime = creationTime,
        expiresIn = expirationTime - creationTime
    ) {
        put(WebTokenClaim.Sub, subject)
        putCborMap(IDENTIFIER_LIST_CLAIM) {
            putCborMap("identifiers") {
                identifiers.forEach { identifier ->
                    putCborMap(identifier.toByteArray().toDataItem()) {}
                }
            }
        }
        put(TTL_CLAIM, (expirationTime - creationTime).inWholeSeconds)
    }

    /**
     * Checks if this identifier list contains the given identifier
     *
     * @param identifier identifier to check
     * @return if this list contains the given identifier
     */
    fun contains(identifier: ByteString) = identifiers.contains(identifier)

    /**
     * Checks if this identifier list contains the given identifier
     *
     * @param identifier identifier to check
     * @return if this list contains the given identifier
     */
    fun contains(identifier: Bstr) = identifiers.contains(ByteString(identifier.value))

    /**
     * Builder class for [IdentifierList].
     */
    class Builder {
        private val identifiers = mutableSetOf<ByteString>()

        /**
         * Adds an identifier to the list.
         *
         * @param identifier identifier to add
         */
        fun add(identifier: ByteString) {
            identifiers.add(identifier)
        }

        /**
         * Builds [IdentifierList] object.
         */
        fun build(): IdentifierList {
            return IdentifierList(identifiers.toSet())
        }
    }

    companion object {
        private const val IDENTIFIER_LIST_CLAIM = 65530L
        private const val TTL_CLAIM = 65534L

        /**
         * Parses and validates CWT that holds the identifier list.
         *
         * CWT signature can be validated either by passing [WebTokenCheck.TRUST] key in
         * the [checks] map or using non-null [publicKey] (see [validateCwt]).
         *
         * @param cwt identifier list CWT representation
         * @param publicKey public key of the issuance server signing key (optional)
         * @param checks additional checks for JWT validation
         * @param atTime time instant to check for expiration
         * @param maxValidity maximum CWT validity duration to accept
         * @return parsed [IdentifierList]
         * @throws IllegalArgumentException when [cwt] cannot be parsed as CWT identifier list
         * @throws InvalidRequestException when CWT validation fails
         */
        suspend fun fromCwt(
            cwt: ByteArray,
            publicKey: EcPublicKey? = null,
            checks: Map<WebTokenCheck, String> = mapOf(),
            atTime: Instant = Clock.System.now(),
            maxValidity: Duration = 365.days
        ): IdentifierList {
            val body = validateCwt(
                cwt = cwt,
                cwtName = "Identifier List",
                checks = buildMap {
                    put(WebTokenCheck.TYP, "application/identifierlist+cwt")
                    putAll(checks)
                },
                publicKey = publicKey,
                atTime = atTime,
                maxValidity = maxValidity
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