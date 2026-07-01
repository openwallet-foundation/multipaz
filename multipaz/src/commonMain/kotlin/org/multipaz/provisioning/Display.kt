package org.multipaz.provisioning

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Describes something in a user-facing manner.
 *
 * This is used to describe both issuers and credentials.
 *
 * @property text user-visible name.
 * @property logo image bytes in PNG or JPEG format.
 * @property description user-visible description.
 * @property textColor text color as CSS numerical color value.
 * @property backgroundColor background color as CSS numerical color value.
 * @property backgroundImage background image bytes in PNG or JPEG format.
 */
@CborSerializable
data class Display(
    val text: String,
    val logo: ByteString? = null,
    val description: String? = null,
    val textColor: String? = null,
    val backgroundColor: String? = null,
    val backgroundImage: ByteString? = null
) {
    companion object
}