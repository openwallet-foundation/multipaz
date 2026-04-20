package org.multipaz.provisioning

/**
 * Credential issuer metadata.
 */
data class ProvisioningMetadata(
    /** The URL of the issuer server. */
    val url: String,

    /** Issuer name and logo. */
    val display: Display,

    /**
     * Credentials that could be provisioned from the issuer indexed by credential configuration id.
     *
     * When [ProvisioningClient] is configured to issue a particular kind of credential, only
     * that credential will be present in the map.
     */
    val credentials: Map<String, CredentialMetadata>
)