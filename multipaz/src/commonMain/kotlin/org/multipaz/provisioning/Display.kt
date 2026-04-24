package org.multipaz.provisioning

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Describes something in a user-facing manner.
 *
 * This is used to describe both issuers and credentials.
 *
 * @property text User-visible text.
 * @property logo Image bytes in PNG or JPEG format.
 */
@CborSerializable
data class Display(
    val text: String,
    val logo: ByteString?
) {
    companion object
}