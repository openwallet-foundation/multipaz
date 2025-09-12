package org.multipaz.models.verification

import kotlinx.serialization.json.JsonObject
import org.multipaz.cbor.DataItem

/**
 * A response using W3C Digital Credentials API response using the `openid4vp-v1-signed` or
 * `openid4vp-v1-unsigned` exchange protocols.
 *
 * @property vpToken the `vp_token` according to OpenID4VP.
 * @property sessionTranscript the `SessionTranscript` CBOR needed to verify embedded ISO mdoc responses.
 */
class OpenID4VPDcResponse(
    val vpToken: JsonObject,
    val sessionTranscript: DataItem
): DcResponse()