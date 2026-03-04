package org.multipaz.provisioning.openid4vci

import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.ProvisioningClient

/**
 * Client set-up for [ProvisioningClient] that support Openid4Vci provisioning.
 */
data class OpenID4VCIClientPreferences(
    /** OAuth2 client_id parameter */
    val clientId: String,
    /** OAuth2 redirect_url */
    val redirectUrl: String,
    /** List of locales in the order of preference that this client supports */
    val locales: List<String>,
    /** Digital signing algorithms that this client supports */
    val signingAlgorithms: List<Algorithm>,
    /** Optional compatibility switches for known non-standard server behavior. */
    val interopOptions: OpenID4VCIInteropOptions = OpenID4VCIInteropOptions()
)
