package org.multipaz.securearea

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey

// TODO: move this class to iOS source tree once annotation processors can work across
// multiple source trees
@CborSerializable(
    schemaHash = "CTDsSD2g89mZTWc2RalsbhsQcRAh7KDUSoc6zX2I03I"
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
