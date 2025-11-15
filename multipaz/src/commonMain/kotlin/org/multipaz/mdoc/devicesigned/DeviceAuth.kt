package org.multipaz.mdoc.devicesigned

import org.multipaz.cose.CoseMac0
import org.multipaz.cose.CoseSign1

/**
 * A structure containing either an ECDSA signature or a MAC, for use in ISO 18013-5 device authentication.
 */
sealed class DeviceAuth {
    /**
     * A MAC for ISO 18013-5 device authentication.
     *
     * @property mac the MAC.
     */
    data class Mac(
        val mac: CoseMac0
    ): DeviceAuth()

    /**
     * A signature for ISO 18013-5 device authentication.
     *
     * @property signature the signature.
     */
    data class Ecdsa(
        val signature: CoseSign1
    ): DeviceAuth()
}