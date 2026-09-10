package org.multipaz.securearea

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.X509CertChain

/**
 * Class for key attestations.
 *
 * Key attestations are used to prove to a remote party that a key was generated in
 * a specific Secure Area.
 *
 * If the Secure Area implementation supports attestation with X.509 certificate chains,
 * the [certChain] parameter contains the chain. Not all Secure Area implementations
 * support attestation.
 *
 * @param publicKey the key that the attestation is for.
 * @param certChain If set, this contains a X.509 certificate chain attesting to the key.
 */
@CborSerializable
data class KeyAttestation(
    val publicKey: PublicKey,
    val certChain: X509CertChain?
) {
    /**
     * The public key as an [EcPublicKey] if it is an EC key.
     *
     * @throws ClassCastException if the key is not an [EcPublicKey].
     */
    val ecPublicKey: EcPublicKey get() = publicKey as EcPublicKey

    companion object
}
