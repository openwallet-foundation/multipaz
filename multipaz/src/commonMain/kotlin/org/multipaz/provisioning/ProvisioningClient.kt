package org.multipaz.provisioning

import org.multipaz.securearea.SecureArea

/**
 * Interface to provision credentials, typically from a server (such as OpenId4Vci server).
 *
 * An instance of this interface provisions credentials for a single document.
 *
 * Provisioning is generally performed in this sequence of steps:
 *  - create appropriate [ProvisioningClient] using externally-supplied data, e.g. provisioning
 *   server and the protocol, or OpenId4Vci credential offer,
 *  - examine provisioning metadata returned by [getMetadata] to ensure that provisioning is
 *   feasible and to configure local systems, e.g. [SecureArea],
 *  - get a list of [AuthorizationChallenge] objects, each of which describes a particular
 *   way to authorize the user; once the list is empty, the user is fully authorized
 *  - select a supported/desirable way of authorization and supply necessary information using
 *   [authorize] method,
 *  - once user is fully authorized, obtain key binding challenge and create device binding
 *   key(s) to which credentials will be bound (this step is not needed for keyless credentials),
 *  - obtain credentials using [obtainCredentials] call.
 */
interface ProvisioningClient {
    /** Reads provisioning server metadata */
    suspend fun getMetadata(): ProvisioningMetadata

    /**
     * Returns the list of authorization challenges.
     *
     * Each authorization challenge describes a particular way to authorize the user. Any
     * challenge from the list can be used. Response to the challenge is sent using [authorize]
     * method.
     */
    suspend fun getAuthorizationChallenges(): List<AuthorizationChallenge>

    /**
     * Sends response to an authorization challenge from the list previously obtained using
     * [getAuthorizationChallenges].
     *
     * [AuthorizationChallenge] id should be equal to the id of [AuthorizationChallenge] to which
     * the response is sent.
     */
    suspend fun authorize(response: AuthorizationResponse)

    /**
     * Returns a challenge for the key(s) to which the credential(s) will be bound.
     *
     * For the key-bound credentials, the issuing server typically requires freshly-minted
     * keys to be used. Freshness is typically guaranteed by signing some type of issuer-supplied
     * challenge by either the key itself, or by some higher-level (attestation) keys. Exact details
     * are determined by [CredentialMetadata.keyBindingType].
     *
     * This method must not be called for keyless credentials.
     */
    suspend fun getKeyBindingChallenge(): String

    /**
     * Obtains credentials using key binding information.
     *
     * [keyInfo] key binding information, required type is determined by
     * [CredentialMetadata.keyBindingType].
     */
    suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials
}