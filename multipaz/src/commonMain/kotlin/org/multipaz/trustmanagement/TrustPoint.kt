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
 * @param isIaca whether this trust point represents an Issuing Authority Certificate Authority (IACA).
 * @param docTypes the list of document types (e.g. `org.iso.18013.5.1.mDL`) this trust point is authorized for,
 *   or empty if unrestricted.
 */
data class TrustPoint(
    val certificate: X509Cert,
    val metadata: TrustMetadata,
    val trustManager: TrustManagerInterface,
    val isIaca: Boolean = false,
    val docTypes: List<String> = emptyList(),
)