package org.multipaz.request

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509CertChain

/**
 * Class that represents a Verifier identity from the presenter's perspective.
 *
 * Each identity corresponds to a signature on the verification request (there may be multiple).
 *
 * @property certChain certificate chain associated with a signature
 */
@CborSerializable
sealed class RequesterIdentity() {
    abstract val certChain: X509CertChain

    companion object
}