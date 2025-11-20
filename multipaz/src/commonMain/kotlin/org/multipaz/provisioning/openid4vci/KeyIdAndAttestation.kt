package org.multipaz.provisioning.openid4vci

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.securearea.KeyAttestation

/**
 * Public key id and its attestation.
 */
@CborSerializable
class KeyIdAndAttestation(
    val keyId: String,
    val keyAttestation: KeyAttestation
) {
    companion object
}