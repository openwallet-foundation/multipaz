package org.multipaz.openid4vci.util

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.EcPublicKey
import org.multipaz.storage.StorageTableSpec
import kotlinx.io.bytestring.ByteString
import org.multipaz.verifier.Openid4VpVerifierModel
import org.multipaz.provisioning.SecretCodeRequest
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Data for a credential issuance session.
 *
 * Issuance session maintains data about user authentication and credentials that were obtained
 * based on that.
 */
@CborSerializable
data class IssuanceState(
    var clientId: String?,
    val scope: String,
    var clientAttestationKey: EcPublicKey?,
    var dpopKey: EcPublicKey?,
    var redirectUri: String?,
    var codeChallenge: ByteString?,
    val configurationId: String?,
    var clientState: String? = null,
    var authorized: Instant? = null,  // time of user's authorization
    var openid4VpVerifierModel: Openid4VpVerifierModel? = null,
    var systemOfRecordAuthCode: String? = null,
    var systemOfRecordCodeVerifier: ByteString? = null,
    var systemOfRecordAccess: SystemOfRecordAccess? = null,
    var txCodeSpec: SecretCodeRequest? = null,
    var txCodeHash: ByteString? = null,
    val urlSchema: String? = null,  // for pre-authorized code generation
    var expiration: Instant = Instant.DISTANT_PAST,  // updated before storage
    var credentials: MutableList<CredentialData> = mutableListOf()
) {
    @CborSerializable
    data class CredentialData(
        val bucket: String,
        val index: Int,
        val expiration: Instant
    ) {
        val id get() = CredentialId(bucket, index)
    }

    fun purgeExpiredCredentials() {
        val now = Clock.System.now()
        credentials = credentials.filter { it.expiration >= now }.toMutableList()
    }

    companion object {
        private val tableSpec = StorageTableSpec(
            name = "OID4VCIDocument",
            supportPartitions = false,
            supportExpiration = true
        )

        suspend fun createIssuanceState(
            issuanceState: IssuanceState,
            expiration: Instant
        ): String {
            issuanceState.expiration = expiration
            return BackendEnvironment.getTable(tableSpec).insert(
                key = null,
                data = ByteString(issuanceState.toCbor()),
                expiration = expiration + 10.seconds  // Keep a bit after the actual expiration
            )
        }

        suspend fun updateIssuanceState(
            issuanceStateId: String,
            issuanceState: IssuanceState,
            expiration: Instant?
        ) {
            expiration?.let { issuanceState.expiration = it }
            return BackendEnvironment.getTable(tableSpec).update(
                key = issuanceStateId,
                data = ByteString(issuanceState.toCbor()),
                expiration = expiration?.let { it + 10.seconds }  // Keep a bit after the actual expiration
            )
        }

        suspend fun getIssuanceState(issuanceStateId: String): IssuanceState {
            val data = BackendEnvironment.getTable(tableSpec).get(issuanceStateId)
                ?: throw IllegalStateException("Unknown or stale issuance session")
            return IssuanceState.fromCbor(data.toByteArray())
        }

        suspend fun listIssuanceStates(
            afterId: String? = null,
            limit: Int = Int.MAX_VALUE
        ): List<Pair<String, IssuanceState>> =
            BackendEnvironment.getTable(tableSpec).enumerateWithData(
                afterKey = afterId,
                limit = limit
            ).map { (id, data) ->
                Pair(id, IssuanceState.fromCbor(data.toByteArray()))
            }
    }
}
