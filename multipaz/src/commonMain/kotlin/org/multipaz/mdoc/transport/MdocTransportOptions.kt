package org.multipaz.mdoc.transport

/**
 * Options for using a [MdocTransport].
 *
 * @property bleUseL2CAP set to `true` to use BLE L2CAP if available, `false` otherwise
 * @property bleUseL2CAPInEngagement set to `true` to use BLE L2CAP from the engagement, `false` otherwise.
 */
data class MdocTransportOptions(
    val bleUseL2CAP: Boolean = false,
    val bleUseL2CAPInEngagement: Boolean = false
)