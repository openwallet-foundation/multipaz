package org.multipaz.provisioning

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.openid.claim.OpenID4VCIClaimDescription

/**
 * Metadata for a particular type of credential that an issuer can provision.
 */
@CborSerializable
data class CredentialMetadata(
    /** Credential name and visual representation of that credential in the wallet. */
    val display: Display,
    /** Format of this credential */
    val format: CredentialFormat,
    /** Determines how the key to which the credential is bound should be provided to the issuer */
    val keyBindingType: KeyBindingType,
    /** Maximum number of credentials that can be requested in a single request */
    val maxBatchSize: Int,
    /**
     * Descriptions of the claims in this credential, including their display names, from the `claims`
     * array of the credential configuration in OpenID4VCI issuer metadata, or `null` if the issuer did
     * not provide any.
     */
    val openID4VCIClaimDescriptions: List<OpenID4VCIClaimDescription>? = null
) {
    companion object
}
