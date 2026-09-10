package org.multipaz.securearea

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.PublicKey

// TODO: move this class to iOS source tree once annotation processors can work across
// multiple source trees
@CborSerializable(
    schemaHash = "EbR406LxCZGdALsNHxxg8sRWGloXoERhW5qgb-1kESo"
)
internal data class SecureEnclaveAreaKeyMetadata(
    val algorithm: Algorithm,
    val userAuthenticationRequired: Boolean,
    val userAuthenticationTypes: Long,
    val publicKey: PublicKey,
    val keyBlob: ByteString
) {
    companion object
}
