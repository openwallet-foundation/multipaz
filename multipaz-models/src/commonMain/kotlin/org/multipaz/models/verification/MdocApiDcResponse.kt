package org.multipaz.models.verification

import org.multipaz.cbor.DataItem

/**
 * A response using W3C Digital Credentials API response using the `org-iso-mdoc` exchange protocol.
 *
 * @property deviceResponse the `DeviceResponse` CBOR.
 * @property sessionTranscript the `SessionTranscript` CBOR needed to verify the device response.
 */
data class MdocApiDcResponse(
    val deviceResponse: DataItem,
    val sessionTranscript: DataItem
): DcResponse()