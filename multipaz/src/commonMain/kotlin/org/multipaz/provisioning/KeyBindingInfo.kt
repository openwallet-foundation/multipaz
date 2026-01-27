package org.multipaz.provisioning

import org.multipaz.securearea.KeyAttestation

/**
 * Provides keys to which credentials are to be bound and key proofing information.
 */
sealed class KeyBindingInfo {
    /** Credential is not bound to keys. */
    data object Keyless: KeyBindingInfo()

    /**
     * Keys are supplied using Openid4Vci-defined proof-of-possession JWT.
     *
     * `kid` header claim in JWT must correspond to the pending credential id.
     */
    data class OpenidProofOfPossession(
        val jwtList: List<String>
    ): KeyBindingInfo()

    /** Keys are supplied using [KeyAttestation] objects */
    data class Attestation(
        val attestations: List<CredentialKeyAttestation>
    ): KeyBindingInfo()
}