package org.multipaz.presentment

import org.multipaz.cbor.DataItem
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey

/**
 * Encapsulates the engagement parameters required for ISO/IEC 18013-5 presentment sessions.
 *
 * @property eDeviceKey the ephemeral device key generated for engagement.
 * @property deviceEngagement the encoded DeviceEngagement structure.
 * @property handover the handover structure.
 * @property eReaderKey the ephemeral reader key, if available.
 */
data class EngagementParams(
    val eDeviceKey: EcPrivateKey,
    val deviceEngagement: DataItem,
    val handover: DataItem,
    val eReaderKey: EcPublicKey? = null,
)
