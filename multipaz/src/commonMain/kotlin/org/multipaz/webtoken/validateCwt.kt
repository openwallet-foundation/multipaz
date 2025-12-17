package org.multipaz.webtoken

import kotlinx.io.bytestring.buildByteString
import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseTextLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.WebTokenClaim.Companion.get
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * General-purpose CWT validation using a set of built-in required checks (expiration
 * and signature validity) and a set of optional checks specified in [checks] parameter.
 *
 * JWT signature is verified either using a supplied [publicKey] or using a
 * trusted key ([WebTokenCheck.TRUST] check must be specified in this case).
 *
 * Most of the optional checks just validate that a particular field in the CWT header or body
 * has certain value. Special optional checks are:
 *
 * [WebTokenCheck.IDENT] checks that `cti` value is fresh and was not used in any not-yet-unexpired
 * CWT that was validated before. The value that should be provided with this check id determines
 * CWT "jti namespace". Two identical `cti` values that belong to distinct namespaces are not
 * considered to be in conflict.
 *
 * [WebTokenCheck.TRUST] specifies that the signature must be checked against a known trusted key
 * (directly or through the certificate chain specified in `x5c`). The value provided with this
 * check id determines [Configuration] name that holds JSON-formatted object that maps
 * the name to the trusted key as either JWK or Base64-encode CA certificate. The name of the key
 * in the map is derived either from the X509 top certificate subject common name, from `kid`
 * parameter in CWT header or `iss` value in the CWT body.
 *
 * [WebTokenCheck.CHALLENGE] defines the nonce/challenge check using the value of the specified
 * property. The value given to this key in [checks] map is used as a property name in the body
 * part of the CWT. That property must exist. Its value is passed to [Challenge.validateAndConsume]
 * method.
 *
 * @param cwt CWT to validate
 * @param cwtName name for the kind of CWT being validated, this is used to generate more meaningful
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
suspend fun validateCwt(
    cwt: ByteArray,
    cwtName: String,
    publicKey: EcPublicKey?,
    checks: Map<WebTokenCheck, String> = mapOf(),
    maxValidity: Duration = 10.hours,
    certificateChainValidator: (suspend (chain: X509CertChain, atTime: Instant) -> Boolean)? = null,
    clock: Clock = Clock.System
): CborMap {
    val cbor = Cbor.decode(cwt)
    val unwrapped = if (cbor is Tagged && cbor.tagNumber == Tagged.COSE_SIGN1) {
        cbor.taggedItem
    } else {
        cbor
    }
    val sign1 = unwrapped.asCoseSign1

    val body = Cbor.decode(sign1.payload!!) as? CborMap
        ?: throw IllegalArgumentException("$cwtName: not a valid CWT")

    val now = clock.now()

    val expiration = body[WebTokenClaim.Exp] ?: run {
        if (maxValidity == Duration.INFINITE) {
            now + 1.seconds
        } else {
            val iat = body[WebTokenClaim.Iat]
                ?: throw InvalidRequestException("$cwtName: either 'exp' or 'iat' is required")
            if (iat > now) {
                // Allow no more than 5 seconds clock mismatch
                if (iat > now + 5.seconds) {
                    throw InvalidRequestException("$cwtName: 'iat' is in future")
                }
                now + maxValidity
            } else {
                iat + maxValidity
            }
        }
    }

    if (expiration < now) {
        throw InvalidRequestException("$cwtName: expired")
    }
    if (maxValidity != Duration.INFINITE && expiration > now + maxValidity) {
        throw InvalidRequestException("$cwtName: expiration is too far in the future")
    }

    for ((check, expectedValue) in checks) {
        val claim = check.webTokenClaim
        if (claim != null) {
            val fieldValue = if (claim.header) {
                val label = if (claim.numKey != null) {
                    CoseNumberLabel(claim.numKey)
                } else {
                    CoseTextLabel(claim.strKey)
                }
                sign1.protectedHeaders[label]?.asTstr
            } else {
                body[claim]
            }
            if (fieldValue != expectedValue) {
                throw InvalidRequestException("$cwtName: '${claim.strKey}' is incorrect or missing")
            }
        }
    }

    val certificateChain = (sign1.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel] ?:
            sign1.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel])?.let {
        X509CertChain.fromDataItem(it)
    }
    val caValidated = try {
        certificateChain != null &&
            (certificateChainValidator ?: ::basicCertificateChainValidator)
                .invoke(certificateChain, now)
    } catch (err: InvalidRequestException) {
        throw InvalidRequestException("$cwtName: ${err.message}")
    }

    val caName = checks[WebTokenCheck.TRUST]
    val key = if (caName == null) {
        require(publicKey != null || caValidated)
        publicKey ?: certificateChain!!.certificates.first().ecPublicKey
    } else {
        val issuer = body[WebTokenClaim.Iss]
        if (certificateChain != null) {
            val first = certificateChain.certificates.first()
            if (checks[WebTokenCheck.X5C_CN_ISS_MATCH] == "required") {
                if (issuer == null) {
                    throw InvalidRequestException("$cwtName: 'iss' must be specified")
                }
                val certificateSubject = first.subject.components[OID.COMMON_NAME.oid]?.value
                    ?: throw InvalidRequestException("$cwtName: no CN entry in 'x5chain' certificate")
                if (issuer != certificateSubject) {
                    throw InvalidRequestException("$cwtName: 'iss' does not match 'x5chain' certificate subject CN")
                }
            }
            if (!caValidated) {
                val caCertificate = certificateChain.certificates.last()
                val caKey = caPublicKey(
                    issuer = caCertificate.issuer.components[OID.COMMON_NAME.oid]?.value
                        ?: throw InvalidRequestException("$cwtName: No CN entry in 'x5chain' CA"),
                    caName = caName
                )
                try {
                    caCertificate.verify(caKey)
                } catch (err: SignatureVerificationException) {
                    throw InvalidRequestException("$cwtName: signature check failed: ${err.message}")
                }
            }
            first.ecPublicKey
        } else {
            val kid = sign1.protectedHeaders[Cose.COSE_LABEL_KID.toCoseLabel]?.asBstr?.decodeToString()
                ?: sign1.unprotectedHeaders[Cose.COSE_LABEL_KID.toCoseLabel]?.asBstr?.decodeToString()
                ?: throw InvalidRequestException("$cwtName: either 'iss' and 'kid', or 'x5chain' must be specified")
            caPublicKey("$issuer#$kid", caName)
        }
    }

    val algId = sign1.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_ALG)]!!.asNumber.toInt()
    try {
        Cose.coseSign1Check(
            publicKey = key,
            detachedData = null,
            signature = sign1,
            signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(algId)
        )
    } catch (err: SignatureVerificationException) {
        throw IllegalArgumentException("$cwtName: signature verification failed", err)
    }

    val nonceName = checks[WebTokenCheck.CHALLENGE]
    if (nonceName != null) {
        if (!body.hasKey(nonceName)) {
            throw ChallengeInvalidException()
        }
        val nonce = body[nonceName]  // must be given if WebTokenCheck.CHALLENGE is used
        if (nonce !is Tstr) {
            throw InvalidRequestException("$cwtName: '$nonceName' is invalid")
        }
        Challenge.validateAndConsume(nonce.asTstr)
    }

    val jtiPartition = checks[WebTokenCheck.IDENT]
    if (jtiPartition != null) {
        val cti = body[WebTokenClaim.Cti] ?:
            throw InvalidRequestException("$cwtName: 'cti' is missing or invalid")
        try {
            BackendEnvironment.getTable(ctiTableSpec).insert(
                key = cti.toByteArray().toBase64Url(),
                data = buildByteString { },
                partitionId = jtiPartition,
                expiration = expiration
            )
        } catch (_: KeyExistsStorageException) {
            throw InvalidRequestException("$cwtName: given 'cti' value was used before")
        }
    }

    return body
}

private val ctiTableSpec = StorageTableSpec(
    name = "UsedCti",
    supportPartitions = true,
    supportExpiration = true
)