package org.multipaz.eventlogger

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.provisioning.Display

/**
 * Base class for data about the issuer in a provisioning event.
 *
 * @property display display information about the issuer.
 */
@CborSerializable
sealed class EventProvisioningIssuerData(
    open val display: Display
) {
    companion object
}
