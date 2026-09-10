package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.PublicKey
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Android Keystore specific class for information about a key.
 */
class AndroidKeystoreKeyInfo internal constructor(
    alias: String,
    algorithm: Algorithm,
    publicKey: PublicKey,
    attestation: KeyAttestation,

    /**
     * The attest key alias for the key, if any.
     */
    val attestKeyAlias: String?,

    /**
     * Whether the user authentication is required to use the key.
     */
    val isUserAuthenticationRequired: Boolean,

    /**
     * The timeout for user authentication or 0 if user authentication is needed for
     * every use of the key.
     */
    val userAuthenticationTimeout: Duration,

    /**
     * The set of possible ways an user can authentication to unlock the key.
     *
     * @return a combination of [UserAuthenticationType] or empty if user authentication is
     * not required.
     */
    val userAuthenticationTypes: Set<UserAuthenticationType>,

    /**
     * Whether the key is StrongBox based.
     */
    val isStrongBoxBacked: Boolean,

    /**
     * The point in time before which the key is not valid, if set.
     */
    val validFrom: Instant?,

    /**
     * The point in time after which the key is not valid, if set.
     */
    val validUntil: Instant?
) : KeyInfo(alias, algorithm, publicKey, attestation)