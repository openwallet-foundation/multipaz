package org.multipaz.request

import org.multipaz.crypto.X509CertChain

/**
 * [RequesterIdentity] in the context of OpenID4VP protocol.
 *
 * @property certChain certificate chain associated with a signature
 * @property clientId OpenID4VP `client_id` parameter; different identities may use different
 *  client ids
 */
data class OpenID4VPRequesterIdentity(
    override val certChain: X509CertChain,
    val clientId: String
): RequesterIdentity()