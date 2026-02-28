package org.multipaz.trustmanagement

import org.multipaz.crypto.X509Cert

/**
 * Class used for the representation of a trusted entity.
 *
 * This is used to represent both trusted issuers and trusted relying parties.
 *
 * @param certificate the root X509 certificate for the CA.
 * @param metadata a [TrustMetadata] with metadata about the trust point.
 * @param trustManager the [TrustManagerInterface] the trust point comes from.
 */
data class TrustPoint(
    val certificate: X509Cert,
    val metadata: TrustMetadata,
    val trustManager: TrustManagerInterface
)