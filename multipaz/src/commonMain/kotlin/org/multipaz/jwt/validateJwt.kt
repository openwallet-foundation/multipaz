package org.multipaz.jwt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.OID
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.fromBase64
import org.multipaz.util.fromBase64Url
import kotlin.collections.iterator
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Defines a specific type of JWT validation.
 *
 * @param propertyNameValueCheck checks that the specific property is of the given value
 * @param headerProperty `true` when the property is in the header rather than the body of JWT
 */
enum class JwtCheck(
    val propertyNameValueCheck: String? = null,
    val headerProperty: Boolean = false
) {
    JTI,  // value is jti partition name (typically clientId)
    TRUST,  // value is the path where to find trusted key
    CHALLENGE,  // value is challenge jwt property name (current specs use "nonce" or "challenge")
    NONCE("nonce"),  // direct nonce value check, prefer CHALLENGE
    TYP("typ", true),
    AUD("aud"),
    ISS("iss"),
    SUB("sub"),
    HTU("htu"),
    HTM("htm"),
    ATH("ath"),
}

/**
 * General-purpose JWT [jwt] validation using a set of built-in required checks (expiration
 * and signature validity) and a set of optional checks specified in [checks] parameter, mostly
 * aim to simplify server-side code.
 *
 * JWT signature is verified either using a supplied [publicKey] and [algorithm] or using a
 * trusted key ([JwtCheck.TRUST] check must be specified in this case).
 *
 * Most of the optional checks just validate that a particular field in the JWT header or body
 * has certain value. Special optional checks are:
 *
 * [JwtCheck.JTI] checks that `jti` value is fresh and was not used in any not-yet-unexpired JWT
 * that was validated before. The value that should be provided with this check id determines
 * JWT "jti namespace". Two identical `jti` values that belong to distinct namespaces are not
 * considered to be in conflict.
 *
 * [JwtCheck.TRUST] specifies that the signature must be checked against a known trusted key
 * (directly or through the certificate chain specified in `x5c`). The value provided with this
 * check id determines [Configuration] name that holds JSON-formatted object that maps
 * the name to the trusted key as either JWK or Base64-encode CA certificate. The name of the key
 * in the map is derived either from the X509 top certificate subject common name, from `kid`
 * parameter in JWT header or `iss` value in the JWT body.
 *
 * [JwtCheck.CHALLENGE] defines the nonce/challenge check using the value of the specified property.
 * The value given to this key in [checks] map is used as a property name in the body part of the
 * JWT. That property must exist. Its value is passed to [Challenge.validateAndConsume] method.
 *
 * [maxValidity] determines expiration time for JWTs that have `iat`, but not `exp` parameter
 * un their body and [clock] determines current time to check for expiration.
 *
 * @throws ChallengeInvalidException when nonce or challenge check fails (see [JwtCheck.CHALLENGE])
 * @throws InvalidRequestException when any other validation fails
 */
suspend fun validateJwt(
    jwt: String,
    jwtName: String,
    publicKey: EcPublicKey?,
    algorithm: Algorithm? = publicKey?.curve?.defaultSigningAlgorithmFullySpecified,
    checks: Map<JwtCheck, String> = mapOf(),
    maxValidity: Duration = 10.hours,
    clock: Clock = Clock.System
): JsonObject {
    val parts = jwt.split('.')
    if (parts.size != 3) {
        throw InvalidRequestException("$jwtName: invalid")
    }
    val header = Json.parseToJsonElement(
        parts[0].fromBase64Url().decodeToString()
    ).jsonObject
    val body = Json.parseToJsonElement(
        parts[1].fromBase64Url().decodeToString()
    ).jsonObject

    val expiration = if (body.containsKey("exp")) {
        val exp = body["exp"]
        if (exp !is JsonPrimitive || exp.isString) {
            throw InvalidRequestException("$jwtName: 'exp' is invalid")
        }
        exp.content.toLong()
    } else {
        val iat = body["iat"]
        if (iat !is JsonPrimitive || iat.isString) {
            throw InvalidRequestException("$jwtName: 'exp' is missing and 'iat' is missing or invalid")
        }
        iat.content.toLong() + maxValidity.inWholeSeconds - 5
    }

    val now = clock.now().epochSeconds
    if (expiration < now) {
        throw InvalidRequestException("$jwtName: expired")
    }
    if (expiration > now + maxValidity.inWholeSeconds) {
        throw InvalidRequestException("$jwtName: expiration is too far in the future")
    }

    for ((check, expectedValue) in checks) {
        val fieldName = check.propertyNameValueCheck
        if (fieldName != null) {
            val part = if (check.headerProperty) header else body
            val fieldValue = part[fieldName]
            if (fieldValue !is JsonPrimitive || fieldValue.content != expectedValue) {
                throw InvalidRequestException("$jwtName: '$fieldName' is incorrect or missing")
            }
        }
    }

    val caName = checks[JwtCheck.TRUST]
    val (key, alg) = if (caName == null) {
        Pair(publicKey!!, algorithm!!)
    } else {
        val issuer = body["iss"]?.jsonPrimitive?.content
        if (header.containsKey("x5c")) {
            val x5c = header["x5c"]!!
            val certificateChain = X509CertChain.fromX5c(x5c)
            if (!certificateChain.validate()) {
                throw InvalidRequestException("$jwtName: 'x5c' certificate chain")
            }
            // TODO: check certificate issuance/expiration
            val first = certificateChain.certificates.first()
            if (issuer != null) {
                // If 'iss' is specified, it should match leaf certificate subject CN.
                val certificateSubject = first.subject.components[OID.COMMON_NAME.oid]?.value
                    ?: throw InvalidRequestException("$jwtName: no CN entry in 'x5c' certificate")
                if (issuer != certificateSubject) {
                    throw InvalidRequestException("$jwtName: 'iss' does not match 'x5c' certificate subject CN")
                }
            }
            val caCertificate = certificateChain.certificates.last()
            val caKey = caPublicKey(
                keyId = caCertificate.subject.components[OID.COMMON_NAME.oid]?.value
                    ?: throw InvalidRequestException("$jwtName: No CN entry in 'x5c' CA"),
                caName = caName
            )
            if (caCertificate.ecPublicKey != caKey) {
                throw InvalidRequestException("$jwtName: CA key mismatch")
            }
            Pair(first.ecPublicKey, first.signatureAlgorithm)
        } else {
            val keyId = header["kid"]?.jsonPrimitive?.content ?: issuer
            ?: throw InvalidRequestException(
                "$jwtName: either 'iss', 'kid', or 'x5c' must be specified")
            val caKey = caPublicKey(keyId, caName)
            Pair(caKey, caKey.curve.defaultSigningAlgorithmFullySpecified)
        }
    }

    val signature = EcSignature.fromCoseEncoded(parts[2].fromBase64Url())
    try {
        val message = jwt.substring(0, jwt.length - parts[2].length - 1)
        Crypto.checkSignature(key, message.encodeToByteArray(), alg, signature)
    } catch (e: SignatureVerificationException) {
        throw IllegalArgumentException("Invalid JWT signature", e)
    }

    val nonceName = checks[JwtCheck.CHALLENGE]
    if (nonceName != null) {
        val nonce = body[nonceName]  // must be given if JwtCheck.CHALLENGE is used
        if (nonce == null) {
            throw ChallengeInvalidException()
        }
        if (nonce !is JsonPrimitive || !nonce.isString) {
            throw InvalidRequestException("$jwtName: '$nonceName' is invalid")
        }
        Challenge.validateAndConsume(nonce.content)
    }

    val jtiPartition = checks[JwtCheck.JTI]
    if (jtiPartition != null) {
        val jti = body["jti"]
        if (jti !is JsonPrimitive || !jti.isString) {
            throw InvalidRequestException("$jwtName: 'jti' is missing or invalid")
        }
        try {
            BackendEnvironment.getTable(jtiTableSpec).insert(
                key = jti.content,
                data = buildByteString { },
                partitionId = jtiPartition,
                expiration = Instant.fromEpochSeconds(expiration)
            )
        } catch (_: KeyExistsStorageException) {
            throw InvalidRequestException("$jwtName: given 'jti' value was used before")
        }
    }

    return body
}

private val keyCacheLock = Mutex()
private val keyCache = mutableMapOf<String, EcPublicKey>()

private suspend fun caPublicKey(
    keyId: String,
    caName: String
): EcPublicKey {
    val configuration = BackendEnvironment.getInterface(Configuration::class)
        ?: throw IllegalStateException("Configuration is required for JwtCheck.TRUST")
    val caPath = "$caName:$keyId"
    return keyCacheLock.withLock {
        keyCache.getOrPut(caPath) {
            val caConfig = configuration.getValue(caName)
                ?: throw IllegalStateException("'$caName': no trusted keys in config")
            val ca = Json.parseToJsonElement(caConfig).jsonObject[keyId]
            if (ca is JsonPrimitive) {
                X509Cert(ByteString(ca.jsonPrimitive.content.fromBase64())).ecPublicKey
            } else if (ca is JsonObject) {
                EcPublicKey.fromJwk(ca)
            } else {
                throw InvalidRequestException("CA not registered: $caPath")
            }
        }
    }
}

private val jtiTableSpec = StorageTableSpec(
    name = "UsedJti",
    supportPartitions = true,
    supportExpiration = true
)
