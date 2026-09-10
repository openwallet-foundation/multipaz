package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.PublicKey

/**
 * Secure Enclave specific class for information about a key.
 */
class SecureEnclaveKeyInfo internal constructor(
    alias: String,
    algorithm: Algorithm,
    publicKey: PublicKey,

    /**
     * Whether the user authentication is required to use the key.
     */
    val isUserAuthenticationRequired: Boolean,

    /**
     * The user authentication types that can be used to unlock the key.
     */
    val userAuthenticationTypes: Set<SecureEnclaveUserAuthType>

): KeyInfo(alias, algorithm, publicKey, KeyAttestation(publicKey, null))
