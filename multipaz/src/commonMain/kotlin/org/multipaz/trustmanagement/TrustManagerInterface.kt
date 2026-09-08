package org.multipaz.trustmanagement

import org.multipaz.crypto.X509Cert
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Interface for checking if an entity is trusted.
 *
 * This can be used for both issuers and relying parties.
 *
 * This looks up a database of trusted entities to render verdicts, see [TrustManager]
 * for an implementation that uses a local trust store, [VicalTrustManager] for an implementation
 * using a VICAL, and [RicalTrustManager] for an implementation using a RICAL. Also see
 * [CompositeTrustManager] for a way to combine multiple trust managers.
 */
interface TrustManagerInterface {

    /**
     * An identifier for the [TrustManagerInterface] instance.
     */
    val identifier: String

    /**
     * Gets all trust points known to this [TrustManagerInterface] instance.
     *
     * @return a list of [TrustPoint]s.
     */
    suspend fun getTrustPoints(): List<TrustPoint>

    /**
     * Checks if an entity identifying itself via a certificate chain is trusted.
     *
     * Equivalent to calling `verify(chain, atTime, validateCaValidity, docType = null)`.
     *
     * @param chain the certificate chain without the self-signed root certificate.
     * @param atTime the point in time to check validity for.
     * @param validateCaValidity whether to validate validity intervals for CA certificates in the chain.
     * @return a [TrustResult] instance with the verdict.
     */
    suspend fun verify(
        chain: List<X509Cert>,
        atTime: Instant = Clock.System.now(),
        validateCaValidity: Boolean = true
    ): TrustResult = verify(
        chain = chain,
        atTime = atTime,
        validateCaValidity = validateCaValidity,
        docType = null
    )

    /**
     * Checks if an entity identifying itself via a certificate chain is trusted for a specific
     * ISO mdoc document type.
     *
     * The following checks are performed:
     * - A matching trust point is located by comparing its Subject Key Identifier (extension 2.5.29.14)
     *   with the Authority Key Identifier (extension 2.5.29.35) of the certificate issued under it
     *   (or by matching the certificate itself if a single certificate is supplied).
     * - The certification path from the leaf certificate to the root certificate is validated according
     *   to RFC 5280:
     *   - The digital signature on each certificate in the chain is verified using the public key of the issuer.
     *   - The root certificate signature is verified to be self-signed.
     *   - The leaf certificate validity period is verified against [atTime]. If [validateCaValidity] is
     *     true, intermediate and root CA validity periods are also checked.
     *   - The leaf certificate's Key Usage extension is verified to contain `digitalSignature`.
     * - In accordance with ISO/IEC 18013-5 Second Edition clause 12.8.3, if verifying an issuer
     *   certificate chain (such as when [docType] is specified or trust is established via a VICAL):
     *   - If the root (IACA) certificate specifies a `countryName` (C), the leaf certificate must have the
     *     same `countryName`.
     *   - If both the root (IACA) and leaf certificate specify a `stateOrProvinceName` (ST), they must match.
     * - In accordance with ISO/IEC 18013-5 Second Edition clause 12.8.1:
     *   - If [docType] is provided and trust is established via a VICAL, [docType] must be in the list of
     *     authorized document types for that certificate.
     *
     * @param chain the certificate chain without the self-signed root certificate.
     * @param atTime the point in time to check validity for.
     * @param validateCaValidity whether to validate validity intervals for CA certificates in the chain.
     * @param docType the ISO mdoc document type (e.g. `org.iso.18013.5.1.mDL`), if validating authorization against a VICAL.
     * @return a [TrustResult] instance with the verdict.
     */
    suspend fun verify(
        chain: List<X509Cert>,
        atTime: Instant = Clock.System.now(),
        validateCaValidity: Boolean = true,
        docType: String? = null
    ): TrustResult
}
