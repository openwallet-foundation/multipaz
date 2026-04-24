package org.multipaz.eventlogger

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Data for a credential fetched during provisioning.
 *
 * @property issuerProvidedData the raw data provided by the issuer.
 */
@CborSerializable
data class EventProvisioningCredentialData(
    val issuerProvidedData: ByteString,
) {
    companion object
}
