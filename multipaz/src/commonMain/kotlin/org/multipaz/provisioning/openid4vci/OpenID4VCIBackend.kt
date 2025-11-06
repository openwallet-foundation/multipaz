package org.multipaz.provisioning.openid4vci

import org.multipaz.provisioning.ProvisioningClient
import org.multipaz.rpc.annotation.RpcInterface
import org.multipaz.rpc.annotation.RpcMethod
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.KeyAttestation

/**
 * Interface to the wallet back-end functionality, required by OpenID4VCI.
 *
 * OpenID4VCI trust model includes wallet back-end (typically implemented as a server, although
 * this is not mandated). OpenID4VCI provisioning server does not have trust relationship with
 * the wallet application itself (as establishing such trust involves platform-specific
 * assertions/attestations, which are not standardized). Instead, provisioning server trusts
 * the wallet back-end, and the wallet back-end, in turn, establishes trust with the
 * wallet application in implementation-specific manner.
 *
 * This interface exposes back-end functionality for use in OpenID4VCI [ProvisioningClient].
 *
 * An implementation of this interface must be available in [BackendEnvironment] associated
 * with the coroutine context of the [ProvisioningClient] asynchronous calls.
 */
@RpcInterface
interface OpenID4VCIBackend {
    /**
     * Value for `client_id` that this back-end will use.
     */
    @RpcMethod
    suspend fun getClientId(): String
    /**
     * Creates fresh OAuth JWT client assertion based on the server-side key.
     */
    @RpcMethod
    suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String

    /**
     * Creates OAuth JWT wallet attestation based on the mobile-platform-specific [KeyAttestation].
     */
    @RpcMethod
    suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String

    /**
     * Creates OAuth JWT key attestation based on the given list of mobile-platform-specific
     * [KeyAttestation]s.
     */
    @RpcMethod
    suspend fun createJwtKeyAttestation(
        keyAttestations: List<KeyAttestation>,
        challenge: String,
        userAuthentication: List<String>? = null,
        keyStorage: List<String>? = null
    ): String
}