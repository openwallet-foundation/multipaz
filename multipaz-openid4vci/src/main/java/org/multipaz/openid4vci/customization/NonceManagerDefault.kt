package org.multipaz.openid4vci.customization

import kotlinx.io.bytestring.buildByteString
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.webtoken.Challenge
import org.multipaz.webtoken.ChallengeInvalidException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Default implementation for [NonceManager] */
object NonceManagerDefault: NonceManager {
    override suspend fun checkAndConsumeAuthorizationDPoPNonce(nonce: String) {
        if (nonce[0] != 'A') {
            Logger.e(TAG, "Not an authorization server DPoP nonce: '$nonce'")
            throw ChallengeInvalidException()
        }
        Challenge.validateAndConsume(nonce.substring(1))
    }

    override suspend fun checkAndConsumeResourceDPoPNonce(nonce: String) {
        if (nonce[0] != 'R') {
            Logger.e(TAG, "Not a resource server DPoP nonce: '$nonce'")
            throw ChallengeInvalidException()
        }
        Challenge.validateAndConsume(nonce.substring(1))
    }

    override suspend fun checkAndConsumeClientAttestationNonce(nonce: String) {
        if (nonce[0] != 'W') {
            Logger.e(TAG, "Not a client attestation challenge: '$nonce'")
            throw ChallengeInvalidException()
        }
        Challenge.validateAndConsume(nonce.substring(1))
    }

    override suspend fun checkAndConsumeCredentialNonce(nonce: String) {
        val table = BackendEnvironment.getTable(credentialChallengeTableSpec)
        if (!table.delete(nonce)) {
            throw InvalidRequestException("Expired or invalid c_nonce")
        }
    }

    override suspend fun challenge(): NonceManager.Nonces = generate(false).also {
        if (it.clientAttestationNonce == null) {
            throw InvalidRequestException("client attestation challenge is not supported")
        }
    }

    override suspend fun token(): NonceManager.Nonces = generate(false)

    override suspend fun pushedAuthorizationRequest(): NonceManager.Nonces = generate(false)

    override suspend fun cNonce(): NonceManager.Nonces {
        // This is not the most scalable way of managing c_nonce values, but it is the simplest one
        // that would detect c_nonce reuse.
        val generic = generate(true)
        val cNonce = BackendEnvironment.getTable(credentialChallengeTableSpec).insert(
            key = null,
            data = buildByteString {},
            expiration = Clock.System.now() + 10.minutes
        )
        return NonceManager.Nonces(
            dpopNonce = generic.dpopNonce,
            clientAttestationNonce = generic.credentialNonce,
            credentialNonce = cNonce
        )
    }

    override suspend fun credential(): NonceManager.Nonces = generate(true)

    private suspend fun generate(resourceServer: Boolean): NonceManager.Nonces {
        val useClientAttestationChallenge = !resourceServer &&
                BackendEnvironment.getInterface(Configuration::class)!!
                    .getValue("use_client_attestation_challenge") != "false"
        return NonceManager.Nonces(
            dpopNonce = (if (resourceServer) "R" else "A") + Challenge.create(),
            clientAttestationNonce = if (useClientAttestationChallenge) {
                "W" + Challenge.create()
            } else {
                null
            }
        )
    }

    private val credentialChallengeTableSpec = StorageTableSpec(
        name = "CredentialChallenge",
        supportExpiration = true,
        supportPartitions = false
    )

    private const val TAG = "NonceManagerDefault"
}