package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.PublicKey

/**
 * Class with information about a key.
 *
 * Concrete [SecureArea] implementations may subclass this to provide additional
 * implementation-specific information about the key.
 *
 * @param alias the alias for the key.
 * @param algorithm a fully specified [Algorithm] for the key.
 * @param publicKey the public part of the key.
 * @param attestation the attestation for the key.
 */
open class KeyInfo protected constructor(
    val alias: String,
    val algorithm: Algorithm,
    val publicKey: PublicKey,
    val attestation: KeyAttestation
) {
    /**
     * The public key as an [EcPublicKey] if it is an EC key.
     *
     * @throws ClassCastException if the key is not an [EcPublicKey].
     */
    val ecPublicKey: EcPublicKey get() = publicKey as EcPublicKey
}