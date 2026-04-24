package org.multipaz.openid4vci.customization

import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.webtoken.ChallengeInvalidException

/**
 * Interface that is responsible for issuing and validating various nonce/challenge
 * values in OpenID4VCI protocols.
 *
 * There are four kinds of nonce/challenges that can occur:
 *  - authorization server DPoP nonce
 *  - client attestation nonce (only used with authorization server)
 *  - resource/credential server DPoP nonce
 *  - credential key binding challenge
 *
 * Generally speaking all these nonce/challenge values are independent on each other,
 * but this is not mandated. For instance the same value could be used for authorization
 * server DPoP and client attestation nonce. The client is not supposed to use a nonce value
 * repeatedly (client should track this for each nonce type separately). However, the
 * server may or may not check that (and also the server can re-issue the same nonce
 * value again).
 *
 * Please consult OpenID4VCI specification for details.
 *
 * NB: in OpenID4VCI nonce values are not bound to a session.
 */
interface NonceManager {
    /**
     * Validate authorization server DPoP nonce and possibly mark it as used up.
     *
     * @param nonce nonce value
     * @throws ChallengeInvalidException if nonce is expired, not valid or already used
     */
    suspend fun checkAndConsumeAuthorizationDPoPNonce(nonce: String)
    /**
     * Validate resource server DPoP nonce and possibly mark it as used up.
     *
     * @param nonce nonce value
     * @throws ChallengeInvalidException if nonce is expired, not valid or already used
     */
    suspend fun checkAndConsumeResourceDPoPNonce(nonce: String)
    /**
     * Validate client attestation challenge and possibly mark it as used up.
     *
     * @param nonce challenge value
     * @throws ChallengeInvalidException if challenge is expired, not valid or already used
     */
    suspend fun checkAndConsumeClientAttestationNonce(nonce: String)
    /**
     * Validate credential binding key challenge and possibly mark it as used up.
     *
     * @param nonce challenge value
     * @throws ChallengeInvalidException if challenge is expired, not valid or already used
     */
    suspend fun checkAndConsumeCredentialNonce(nonce: String)
    /**
     * Issue nonce values for `challenge` authorization endpoint
     *
     * @return nonce values
     */
    suspend fun challenge(): Nonces
    /**
     * Issue nonce values for `par` (Pushed Authorization Request) authorization server endpoint
     *
     * @return nonce values
     */
    suspend fun pushedAuthorizationRequest(): Nonces
    /**
     * Issue nonce values for `token` authorization server endpoint
     *
     * @return nonce values
     */
    suspend fun token(): Nonces
    /**
     * Issue nonce values for `c_nonce` resource server endpoint
     *
     * @return nonce values
     */
    suspend fun cNonce(): Nonces
    /**
     * Issue nonce values for `credential` resource server endpoint
     *
     * @return nonce values
     */
    suspend fun credential(): Nonces

    /**
     * A set of nonce values
     *
     * @param dpopNonce DPoP nonce (either authorization- or resource-server bound)
     * @param clientAttestationNonce client attestation challenge (must be null for resource server)
     * @param credentialNonce credential key binding nonce, must only be ussed for `c_nonce` request
     */
    data class Nonces(
        val dpopNonce: String? = null,
        val clientAttestationNonce: String? = null,
        val credentialNonce: String? = null
    )

    companion object {
        /**
         * @return [NonceManager] implementation
         */
        suspend fun get(): NonceManager =
            BackendEnvironment.getInterface(NonceManager::class) ?: NonceManagerDefault
    }
}