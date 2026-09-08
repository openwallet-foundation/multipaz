package org.multipaz.trustmanagement

import org.multipaz.crypto.X509CertChain

/**
 * Class containing the verdict of whether a given entity is trusted.
 *
 * @property isTrusted trust if a trust point was found.
 * @property trustChain the chain that was built, is `null` if [isTrusted] is `false` or the entity isn't identified
 *   using a X509 certificates.
 * @property trustPoints the set of trust points that matched, is empty if [isTrusted] is `false`.
 * @property error a [Throwable] indicating if an error occurred validating the trust chain.
 * @property authorizedDocTypes the list of document types (e.g. `org.iso.18013.5.1.mDL`) authorized by the matched trust points.
 */
data class TrustResult(
    val isTrusted: Boolean,
    val trustChain: X509CertChain? = null,
    val trustPoints: List<TrustPoint> = emptyList(),
    val error: Throwable? = null,
    val authorizedDocTypes: List<String> = emptyList(),
)
