package org.multipaz.sdjwt.credential

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.claim.JsonClaim
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.sdjwt.SdJwt
import org.multipaz.validation.ValidationFinding
import org.multipaz.validation.ValidationResult
import org.multipaz.validation.ValidationSeverity
import org.multipaz.validation.buildValidationResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A SD-JWT VC credential, according to [draft-ietf-oauth-sd-jwt-vc-03]
 * (https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/).
 *
 * An object that implements this interface must also be a [Credential]
 */
interface SdJwtVcCredential {
    /**
     * The Verifiable Credential Type - or `vct` - as defined in section 3.2.2.1.1 of
     * [draft-ietf-oauth-sd-jwt-vc-03]
     * (https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/)
     */
    val vct: String

    /**
     * The issuer-provided data associated with the credential, see [Credential.issuerProvidedData].
     *
     * This data must be the encoded string containing the SD-JWT VC. The SD-JWT VC itself is by
     * disclosures: `<header>.<body>.<signature>~<Disclosure 1>~<Disclosure 2>~...~<Disclosure N>~`
     */
    val issuerProvidedData: ByteString

    /**
     * The X.509 certificate chain for the issuer signature from the SD-JWT VC header (`x5c`), if present.
     */
    suspend fun getIssuerCertChain(): X509CertChain? {
        val sdJwt = SdJwt.fromCompactSerialization(issuerProvidedData.decodeToString())
        return sdJwt.x5c
    }

    suspend fun getClaimsImpl(
        documentTypeRepository: DocumentTypeRepository?
    ): List<JsonClaim> {
        val ret = mutableListOf<JsonClaim>()
        val sdJwt = SdJwt.fromCompactSerialization(issuerProvidedData.decodeToString())
        // We only support keys that are certified with the certificate chain. Web-based resolution
        // is not supported (and it is not clear it is suitable for identity credentials in general
        // if the future goal is to support (offline) proximity presentment).
        val x5c = sdJwt.x5c
            ?: throw IllegalStateException("Only X509-certified keys are supported in SD-JWT")
        val issuerKey = x5c.certificates.first().publicKey
        val processedJwt = sdJwt.verify(issuerKey)

        // By design, we only include the top-level claims.
        val dt = documentTypeRepository?.getDocumentTypeForJson(vct)
        for ((claimName, claimValue) in processedJwt) {
            val attribute = dt?.jsonDocumentType?.claims?.get(claimName)
            ret.add(
                JsonClaim(
                    displayName = dt?.jsonDocumentType?.claims?.get(claimName)?.displayName ?: claimName,
                    attribute = attribute,
                    vct = vct,
                    claimPath = buildJsonArray { add(claimName) },
                    value = claimValue
                )
            )
        }
        return ret
    }

    /**
     * Extracts validity from the credential
     *
     * @return a `Pair(validFrom, validUntil)`
     */
    suspend fun extractValidityFromIssuerDataImpl(): Pair<Instant, Instant> {
        val sdJwt = SdJwt.fromCompactSerialization(issuerProvidedData.decodeToString())
        // If SD-JWT somehow does not specify these values, treat it as effectively non-expiring
        val validFrom = sdJwt.validFrom ?: Instant.fromEpochMilliseconds(0)
        val validUntil = sdJwt.validUntil ?: Instant.fromEpochMilliseconds(Long.MAX_VALUE)
        return Pair(validFrom, validUntil)
    }

    /**
     * Validates candidate issuer-provided static authentication data against this SD-JWT VC
     * credential according to RFC 9901 and the SD-JWT VC profile.
     *
     * @param issuerProvidedAuthenticationData candidate issuer-provided static authentication data.
     * @param now reference time to check expiration and validity windows, or `null`.
     * @return a [ValidationResult] containing any errors or warnings.
     */
    suspend fun validateSdJwtVc(
        issuerProvidedAuthenticationData: ByteString,
        now: Instant? = null
    ): ValidationResult = buildValidationResult {
        val compactSerialization = try {
            issuerProvidedAuthenticationData.decodeToString()
        } catch (e: Throwable) {
            addError("Failed to decode issuerProvidedAuthenticationData as UTF-8: ${e.message}")
            return@buildValidationResult
        }

        val sdJwtValidation = SdJwt.validateVc(
            compactSerialization = compactSerialization,
            expectedVct = vct,
            now = now
        )
        addAll(sdJwtValidation)

        val sdJwt = try {
            SdJwt.fromCompactSerialization(compactSerialization)
        } catch (_: Throwable) {
            null
        }

        if (sdJwt != null) {
            val cred = this@SdJwtVcCredential as? Credential
            if (cred != null && cred.isCertified) {
                val expectedValidFrom = sdJwt.validFrom ?: Instant.fromEpochMilliseconds(0)
                if (cred.validFrom != expectedValidFrom) {
                    addError("Credential validFrom (${cred.validFrom}) does not match SD-JWT validFrom ($expectedValidFrom)")
                }
                val expectedValidUntil = sdJwt.validUntil ?: Instant.fromEpochMilliseconds(Long.MAX_VALUE)
                if (cred.validUntil != expectedValidUntil) {
                    addError("Credential validUntil (${cred.validUntil}) does not match SD-JWT validUntil ($expectedValidUntil)")
                }
            }

            if (this@SdJwtVcCredential is KeyBoundSdJwtVcCredential) {
                val kbCred = this@SdJwtVcCredential
                val kbKey = sdJwt.kbKey
                if (kbKey == null) {
                    addError("Key-bound credential is missing 'cnf.jwk' key-binding in SD-JWT")
                } else {
                    val keyInfo = try {
                        kbCred.secureArea.getKeyInfo(kbCred.alias)
                    } catch (e: Throwable) {
                        addError("Failed to get key info from SecureArea for alias '${kbCred.alias}': ${e.message}")
                        null
                    }
                    if (keyInfo != null && keyInfo.publicKey != kbKey) {
                        addError("Credential authentication key in SecureArea does not match 'cnf.jwk' in SD-JWT (presentations will fail)")
                    }
                }
            } else if (this@SdJwtVcCredential is KeylessSdJwtVcCredential) {
                if (sdJwt.kbKey != null) {
                    addWarning("Keyless credential contains unexpected key-binding 'cnf' claim in SD-JWT")
                }
            }
        }
    }
}

/**
 * Validates candidate issuer-provided static authentication data against this SD-JWT VC credential.
 *
 * @param issuerProvidedAuthenticationData candidate issuer-provided static authentication data.
 * @param now reference time for checking expiration and validity intervals, or `null`.
 * @return a [ValidationResult] containing any errors or warnings.
 */
suspend fun SdJwtVcCredential.validate(
    issuerProvidedAuthenticationData: ByteString,
    now: Instant? = null
): ValidationResult = (this as Credential).validate(issuerProvidedAuthenticationData, now)

/**
 * Validates this certified SD-JWT VC credential against its current issuer-provided data.
 *
 * @param now reference time for checking expiration and validity intervals, or `null`.
 * @return a [ValidationResult] containing any errors or warnings.
 */
suspend fun SdJwtVcCredential.validate(
    now: Instant? = null
): ValidationResult = (this as Credential).validate(now)