package org.multipaz.statuslist

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.jwt.JwtCheck
import org.multipaz.jwt.buildJwt
import org.multipaz.jwt.validateJwt
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibInflate
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Status list as defined in
 * [OAuth Status List](https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/)
 * in the compressed form.
 *
 * Use this (compressed) form of the list to serve status list requests in JWT (and CBOR in the
 * future) format.
 *
 * @param bitsPerItem number of bits per status code, must be 1, 2, 4, or 8
 * @param compressedStatusList status values packed as an array and compressed as defined by the
 *        spec above
 * @param creationTime time of the status list creation, useful to determine freshness when
 *        the status list is served through HTTP in one of its serialized forms.
 */
class CompressedStatusList(
    val bitsPerItem: Int,
    private val compressedStatusList: ByteArray,
    val creationTime: Instant = Clock.System.now(),
) {
    init {
        require(bitsPerItem == 1 || bitsPerItem == 2 || bitsPerItem == 4 || bitsPerItem == 8)
    }

    /**
     * Creates JWT serialization of the status list.
     *
     * @param key key that is used to sign JWT
     * @param issuer value for `iss` claim in JWT
     * @param expiresIn how long the status list is valid (random duration about half an our
     *    by default, so that update requests don't all come at the same time)
     * @return signed JWT that represents this status list
     */
    suspend fun serializeAsJwt(
        key: AsymmetricKey,
        issuer: String,
        expiresIn: Duration = 20.minutes + Random.Default.nextInt(1000).seconds
    ) = buildJwt(
        key = key,
        type = "statuslist+jwt",
        expiresIn = expiresIn
    ) {
        put("iss", issuer)
        putJsonObject("status_list") {
            put("bits", bitsPerItem)
            put("lst", compressedStatusList.toBase64Url())
        }
        put("ttl", expiresIn.inWholeSeconds)
    }

    /**
     * Creates decompressed form of this status list.
     */
    fun decompress(): StatusList {
        return StatusList(bitsPerItem, compressedStatusList.zlibInflate())
    }

    companion object {
        /**
         * Parses and validates JWT that holds the status list.
         *
         * JWT signature can be validated either by passing [JwtCheck.TRUST] key in the [checks]
         * map or using non-null [publicKey] (see [validateJwt]).
         *
         * @param jwt status list JWT representation
         * @param publicKey public key of the issuance server signing key (optional)
         * @param checks additional checks for JWT validation
         * @return parsed [CompressedStatusList]
         * @throws IllegalArgumentException when [jwt] cannot be parsed as JWT status list
         * @throws InvalidRequestException when JWT validation fails
         */
        suspend fun fromJwt(
            jwt: String,
            publicKey: EcPublicKey? = null,
            checks: Map<JwtCheck, String> = mapOf()
        ): CompressedStatusList {
            val body = validateJwt(
                jwt = jwt,
                jwtName = "Status List",
                checks = buildMap {
                    put(JwtCheck.TYP, "statuslist+jwt")
                    putAll(checks)
                },
                publicKey = publicKey,
                maxValidity = 365.days
            )
            return fromJson(
                json = body["status_list"]?.jsonObject
                    ?: throw IllegalArgumentException("missing required 'status_list' claim")
            )
        }

        /**
         * Parses JSON as status list.
         *
         * This method is mostly useful for testing, as JSON is typically wrapped in JWT.
         *
         * @param json JSON status list representation
         * @return parsed [CompressedStatusList]
         * @throws IllegalArgumentException when [json] does not represent status list
         */
        fun fromJson(json: JsonObject): CompressedStatusList {
            val lst = json["lst"]
                ?: throw IllegalArgumentException("missing 'lst' in 'status_list' claim")
            return CompressedStatusList(
                bitsPerItem = json["bits"]?.jsonPrimitive?.intOrNull
                    ?: throw IllegalArgumentException("missing 'bits' in 'status_list' claim"),
                compressedStatusList = lst.jsonPrimitive.content.fromBase64Url()
            )
        }
    }
}