package org.multipaz.server.enrollment

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509CertChain

/** Identity serialized for storage in the database: private key alias and certificate chain */
@CborSerializable
internal data class SigningKeyData(
    val certChain:X509CertChain,
    val alias: String
) {
    companion object
}