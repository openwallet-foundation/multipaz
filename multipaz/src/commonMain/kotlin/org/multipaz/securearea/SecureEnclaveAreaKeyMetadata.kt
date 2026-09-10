package org.multipaz.securearea

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey

// TODO: move this class to iOS source tree once annotation processors can work across
// multiple source trees
@CborSerializable(
    schemaHash = "ZeCFE8W0ewH8s5B2lZjG_rCOVQ5w7HkVPwHBwQU-wKE"
)
internal data class SecureEnclaveAreaKeyMetadata(
    val algorithm: Algorithm,
    val userAuthenticationRequired: Boolean,
    val userAuthenticationTypes: Long,
    val publicKey: EcPublicKey,
    val keyBlob: ByteString
) {
    companion object
}
