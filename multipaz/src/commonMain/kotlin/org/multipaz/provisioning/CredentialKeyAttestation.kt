package org.multipaz.provisioning

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.securearea.KeyAttestation

/**
 * Public key id and its attestation.
 */
@CborSerializable
class CredentialKeyAttestation(
    val credentialId: String,
    val keyAttestation: KeyAttestation
) {
    companion object
}