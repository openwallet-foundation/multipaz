package org.multipaz.mdoc.transport

/**
 * Starts Bluetooth Channel Sounding (CS) ranging session to continuously monitor the distance to
 * the specified peer Bluetooth device.
 *
 * This function suspends and continues ranging until cancelled.
 *
 * @param peerBluetoothAddress the MAC address of the peer Bluetooth device.
 * @param asInitiator whether this device is the Channel Sounding initiator (true) or responder (false).
 */
internal expect suspend fun startBluetoothChannelSounding(
    peerBluetoothAddress: String,
    asInitiator: Boolean
)
