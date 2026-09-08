package org.multipaz.request

import org.multipaz.crypto.X509CertChain

/**
 * [RequesterIdentity] in the context of ISO 18013 protocols.
 *
 * @property certChain certificate chain associated with a signature
 */
data class Iso18013RequesterIdentity(
    override val certChain: X509CertChain,
): RequesterIdentity()