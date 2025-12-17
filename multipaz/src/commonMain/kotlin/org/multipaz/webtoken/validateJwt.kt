package org.multipaz.webtoken

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
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.fromBase64
import org.multipaz.util.fromBase64Url
import org.multipaz.webtoken.WebTokenClaim.Companion.get
import kotlin.collections.iterator
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * General-purpose JWT validation using a set of built-in required checks (expiration
 * and signature validity) and a set of optional checks specified in [checks] parameter.
 *
 * JWT signature is verified either using a supplied [publicKey] or using a
 * trusted key ([WebTokenCheck.TRUST] check must be specified in this case).
 *
 * Most of the optional checks just validate that a particular field in the JWT header or body
 * has certain value. Special optional checks are:
 *
 * [WebTokenCheck.IDENT] checks that `jti` value is fresh and was not used in any not-yet-unexpired JWT
 * that was validated before. The value that should be provided with this check id determines
 * JWT "jti namespace". Two identical `jti` values that belong to distinct namespaces are not
 * considered to be in conflict.
 *
 * [WebTokenCheck.TRUST] specifies that the signature must be checked against a known trusted key
 * (directly or through the certificate chain specified in `x5c`). The value provided with this
 * check id determines [Configuration] name that holds JSON-formatted object that maps
 * the name to the trusted key as either JWK or Base64-encode CA certificate. The name of the key
 * in the map is derived either from the X509 top certificate subject common name, from `kid`
 * parameter in JWT header or `iss` value in the JWT body.
 *
 * [WebTokenCheck.CHALLENGE] defines the nonce/challenge check using the value of the specified property.
 * The value given to this key in [checks] map is used as a property name in the body part of the
 * JWT. That property must exist. Its value is passed to [Challenge.validateAndConsume] method.
 *
 * @param jwt JWT to validate
 * @param jwtName name for the kind of JWT being validated, this is used to generate more meaningful
 *    exception messages
 * @param publicKey public key to use to check signature, either publicKey or [WebTokenCheck.TRUST]
 *    must be used.
 * @param checks validation checks to perform.
 * @param maxValidity when `exp` is not present determines expiration time based on `iat` claim;
 *     when `exp` claim is present, determines how far in the future it can be.
 * @param certificateChainValidator optional function to validate certificate chain in CWT; if
 *     the certificate chain is not valid it should throw [InvalidRequestException] exception,
 *     the returned value should indicate if the chain is trusted (in which case
 *     [WebTokenCheck.TRUST] check is not performed) or not ([WebTokenCheck.TRUST] still applies).
 * @param clock clock that determines current time to check for expiration.
 * @throws ChallengeInvalidException when nonce or challenge check fails (see [WebTokenCheck.CHALLENGE])
 * @throws InvalidRequestException when any other validation fails
 */
suspend fun validateJwt(
    jwt: String,
    jwtName: String,
    publicKey: EcPublicKey? = null,
    checks: Map<WebTokenCheck, String> = mapOf(),
    maxValidity: Duration = 10.hours,
    certificateChainValidator: (suspend (chain: X509CertChain, atTime: Instant) -> Boolean)? = null,
    clock: Clock = Clock.System
): JsonObject {
    require(publicKey == null || certificateChainValidator == null)
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

    val now = clock.now()

    val algorithm = header["alg"]?.jsonPrimitive?.content?.let {
        Algorithm.fromJoseAlgorithmIdentifier(it)
    }

    val expiration = body[WebTokenClaim.Exp] ?: run {
        if (maxValidity == Duration.INFINITE) {
            now + 1.seconds
        } else {
            val iat = body[WebTokenClaim.Iat]
                ?: throw InvalidRequestException("$jwtName: either 'exp' or 'iat' is required")
            if (iat > now) {
                // Allow no more than 5 seconds clock mismatch
                if (iat > now + 5.seconds) {
                    throw InvalidRequestException("$jwtName: 'iat' is in future")
                }
                now + maxValidity
            } else {
                iat + maxValidity
            }
        }
    }

    if (expiration < now) {
        throw InvalidRequestException("$jwtName: expired")
    }
    if (maxValidity != Duration.INFINITE && expiration > now + maxValidity) {
        throw InvalidRequestException("$jwtName: expiration is too far in the future")
    }

    for ((check, expectedValue) in checks) {
        val claim = check.webTokenClaim
        if (claim != null) {
            val part = if (claim.header) header else body
            val fieldValue = part[claim]
            if (fieldValue != expectedValue) {
                throw InvalidRequestException("$jwtName: '${claim.strKey}' is incorrect or missing")
            }
        }
    }

    val certificateChain = header["x5c"]?.let { X509CertChain.fromX5c(it) }
    val caValidated = try {
        certificateChain != null &&
            (certificateChainValidator ?: ::basicCertificateChainValidator)
                .invoke(certificateChain, now)
    } catch (err: InvalidRequestException) {
        throw InvalidRequestException("$jwtName: ${err.message}")
    }

    val caName = checks[WebTokenCheck.TRUST]
    val key = if (caName == null) {
        require(publicKey != null || caValidated)
        publicKey ?: certificateChain!!.certificates.first().ecPublicKey
    } else {
        val issuer = body["iss"]?.jsonPrimitive?.content
        if (certificateChain != null) {
            val first = certificateChain.certificates.first()
            if (checks[WebTokenCheck.X5C_CN_ISS_MATCH] == "required") {
                if (issuer == null) {
                    throw InvalidRequestException("$jwtName: 'iss' must be specified")
                }
                val certificateSubject = first.subject.components[OID.COMMON_NAME.oid]?.value
                    ?: throw InvalidRequestException("$jwtName: no CN entry in 'x5c' certificate")
                if (issuer != certificateSubject) {
                    throw InvalidRequestException("$jwtName: 'iss' does not match 'x5c' certificate subject CN")
                }
            }
            if (!caValidated) {
                val topCertificate = certificateChain.certificates.last()
                val caKey = caPublicKey(
                    issuer = topCertificate.issuer.components[OID.COMMON_NAME.oid]?.value
                        ?: throw InvalidRequestException("$jwtName: No CN entry in 'x5c' CA"),
                    caName = caName
                )
                try {
                    topCertificate.verify(caKey)
                } catch (err: SignatureVerificationException) {
                    throw InvalidRequestException("$jwtName: signature check failed: ${err.message}")
                }
            }
            first.ecPublicKey
        } else {
            val kid = header["kid"]?.jsonPrimitive?.content
                ?: throw InvalidRequestException(
                "$jwtName: either 'iss' and 'kid' or 'x5c' must be specified")
            caPublicKey("$issuer#$kid", caName)
        }
    }

    val signature = EcSignature.fromCoseEncoded(parts[2].fromBase64Url())
    try {
        val message = jwt.take(jwt.length - parts[2].length - 1)
        Crypto.checkSignature(
            publicKey = key,
            message = message.encodeToByteArray(),
            algorithm = algorithm ?: key.curve.defaultSigningAlgorithmFullySpecified,
            signature = signature
        )
    } catch (e: SignatureVerificationException) {
        throw IllegalArgumentException("$jwtName: invalid JWT signature", e)
    }

    val nonceName = checks[WebTokenCheck.CHALLENGE]
    if (nonceName != null) {
        // must be given if WebTokenCheck.CHALLENGE is used
        val nonce = body[nonceName] ?: throw ChallengeInvalidException()
        if (nonce !is JsonPrimitive || !nonce.isString) {
            throw InvalidRequestException("$jwtName: '$nonceName' is invalid")
        }
        Challenge.validateAndConsume(nonce.content)
    }

    val jtiPartition = checks[WebTokenCheck.IDENT]
    if (jtiPartition != null) {
        val jti = body[WebTokenClaim.Jti] ?:
            throw InvalidRequestException("$jwtName: 'jti' is missing or invalid")
        try {
            BackendEnvironment.getTable(jtiTableSpec).insert(
                key = jti,
                data = buildByteString { },
                partitionId = jtiPartition,
                expiration = expiration
            )
        } catch (_: KeyExistsStorageException) {
            throw InvalidRequestException("$jwtName: given 'jti' value was used before")
        }
    }

    return body
}

private val keyCacheLock = Mutex()
private val keyCache = mutableMapOf<String, EcPublicKey>()
private var cachedConfiguration: Configuration? = null

internal suspend fun caPublicKey(
    issuer: String,
    caName: String
): EcPublicKey {
    val configuration = BackendEnvironment.getInterface(Configuration::class)
        ?: throw IllegalStateException("Configuration is required for WebTokenCheck.TRUST")
    val caPath = "$caName:$issuer"
    return keyCacheLock.withLock {
        if (cachedConfiguration != configuration) {
            keyCache.clear()
            cachedConfiguration = configuration
        }
        keyCache.getOrPut(caPath) {
            val ca = configuration.getValue(caName)?.let {
                Json.parseToJsonElement(it).jsonObject[issuer]
            }
            when (ca) {
                is JsonPrimitive ->
                    X509Cert(ByteString(ca.jsonPrimitive.content.fromBase64())).ecPublicKey
                is JsonObject ->
                    EcPublicKey.fromJwk(ca)
                else -> {
                    throw InvalidRequestException("CA not registered: $caPath")
                }
            }
        }
    }
}

/**
 * Performs basic certificate chain validation.
 *
 * Specifically, these checks are performed:
 *  - every certificate in the chain is signed by the next one,
 *  - signer certificate's subject matches signed certificate's issuer,
 *  - certificates are not expired,
 *  - signer certificate have `CERT_SIGN` key usage
 *  - if the lst certificate is self-signed (root) and has basic constrains extension
 *    - CA flag is set to true
 *    - number of certificates in the chain satisfies path length constraint
 *
 * @return `false` (meaning this function cannot find the root certificate and establish trust)
 * @throws InvalidRequestException if the certificate chain is not valid
 */
suspend fun basicCertificateChainValidator(
    certificateChain: X509CertChain,
    now: Instant
): Boolean {
    if (!certificateChain.validate()) {
        throw InvalidRequestException("invalid certificate chain")
    }
    var last: X509Cert? = null
    for (certificate in certificateChain.certificates) {
        if (last != null) {
            if (last.issuer != certificate.subject) {
                throw InvalidRequestException("subject/issuer mismatch")
            }
            if (!certificate.keyUsage.contains(X509KeyUsage.KEY_CERT_SIGN)) {
                throw InvalidRequestException("missing CERT_SIGN usage")
            }
        }
        last = certificate
        if (certificate.validityNotAfter < now) {
            throw InvalidRequestException("expired certificate")
        }
        if (certificate.validityNotBefore > now) {
            throw InvalidRequestException("not-yet-valid certificate")
        }
    }
    if (last != null && last.subject == last.issuer) {
        val basicConstraints = last.basicConstraints
        if (basicConstraints != null) {
            if (!basicConstraints.first) {
                throw InvalidRequestException("BasicConstrains CA is false on root certificate")
            }
            val maxPathLength = basicConstraints.second
            if (maxPathLength != null) {
                // the leaf and the root are not counted in path length constraints
                val pathLength = certificateChain.certificates.size.toLong() - 2
                if (pathLength > maxPathLength) {
                    throw InvalidRequestException("BasicConstrains CA path length exceeded")
                }
            }
        }
    }
    return false  // Certificate chain is valid, but no trust is established
}

private val jtiTableSpec = StorageTableSpec(
    name = "UsedJti",
    supportPartitions = true,
    supportExpiration = true
)

const val TAG = "validateJwt"