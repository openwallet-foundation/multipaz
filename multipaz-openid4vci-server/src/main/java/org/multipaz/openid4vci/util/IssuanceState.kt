package org.multipaz.openid4vci.util

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.EcPublicKey
import org.multipaz.storage.StorageTableSpec
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.verifier.Openid4VpVerifierModel
import org.multipaz.provisioning.SecretCodeRequest
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.util.toBase64Url

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
    var authorized: Boolean? = null,
    var openid4VpVerifierModel: Openid4VpVerifierModel? = null,
    var systemOfRecordAuthCode: String? = null,
    var systemOfRecordCodeVerifier: ByteString? = null,
    var systemOfRecordAccess: SystemOfRecordAccess? = null,
    var txCodeSpec: SecretCodeRequest? = null,
    var txCodeHash: ByteString? = null,
    val urlSchema: String? = null,  // for pre-authorized code generation
) {
    companion object {
        private val tableSpec = StorageTableSpec(
            name = "Openid4VciIssuanceSession",
            supportPartitions = false,
            supportExpiration = false
        )

        private fun keyHash(dpopKey: EcPublicKey): String {
            return Crypto.digest(
                algorithm = Algorithm.SHA256,
                message = Cbor.encode(dpopKey.toDataItem())
            ).toBase64Url()
        }

        suspend fun createIssuanceState(issuanceState: IssuanceState): String =
            BackendEnvironment.getTable(tableSpec).insert(
                key = null,
                data = ByteString(issuanceState.toCbor())
            )

        suspend fun updateIssuanceState(issuanceStateId: String, issuanceState: IssuanceState) {
            return BackendEnvironment.getTable(tableSpec).update(
                key = issuanceStateId,
                data = ByteString(issuanceState.toCbor())
            )
        }

        suspend fun getIssuanceState(issuanceStateId: String): IssuanceState {
            val data = BackendEnvironment.getTable(tableSpec).get(issuanceStateId)
                ?: throw IllegalStateException("Unknown or stale issuance session")
            return IssuanceState.fromCbor(data.toByteArray())
        }
    }
}
