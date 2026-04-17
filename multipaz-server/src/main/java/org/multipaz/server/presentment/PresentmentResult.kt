package org.multipaz.server.presentment

import org.multipaz.trustmanagement.TrustResult

/**
 * Base class for results of verifying a single credential in a [PresentmentRecord].
 */
sealed class PresentmentResult() {
    /**
     * Credential identifier within the presentment for OpenID4VP (from DCQL),
     * `null` for ISO ISO 18013-5 presentments.
     */
    abstract val id: String?
    /** Result of verifying the issuer certificate chain against configured trust anchors. */
    abstract val trustResult: TrustResult
}