package org.multipaz.eventlogger

import org.multipaz.provisioning.Display

/**
 * Issuer data for provisioning via OpenID4VCI.
 *
 * @property url the URL of the issuer.
 * @property credentialId the identifier of the credential.
 */
data class EventProvisioningIssuerDataOpenID4VCI(
    override val display: Display,
    val url: String,
    val credentialId: String,
): EventProvisioningIssuerData(display)
