package org.multipaz.mdoc.transport

import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.UUID
import kotlinx.coroutines.channels.Channel

internal interface BleCentralManager {
    val incomingMessages: Channel<ByteArray>

    fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capUuid: UUID?
    )

    fun setCallbacks(
        /**
         * Called if an error occurs asynchronously and the error isn't bubbled back
         * to one of the methods on this object. Never invoked by any of the instance
         * methods.
         */
        onError: (error: Throwable) -> Unit,

        /**
         * Called on transport-specific termination. Never invoked by any of the instance
         * methods.
         */
        onClosed: () -> Unit
    )

    suspend fun waitForPowerOn()

    suspend fun waitForPeripheralWithUuid(uuid: UUID)

    suspend fun connectToPeripheral()

    suspend fun requestMtu()

    suspend fun peripheralDiscoverServices(uuid: UUID)

    suspend fun peripheralDiscoverCharacteristics()

    suspend fun checkReaderIdentMatches(eSenderKey: EcPublicKey)

    suspend fun subscribeToCharacteristics()

    suspend fun writeToStateCharacteristic(value: Int)

    suspend fun sendMessage(message: ByteArray)

    // The PSM read from the L2CAP characteristic or null if the peripheral server does not
    // support it or checking for L2CAP wasn't requested when calling [setUuids]
    val l2capPsm: Int?

    suspend fun connectL2cap(psm: Int)

    // True if connected via L2CAP
    val usingL2cap: Boolean

    // The peer Bluetooth MAC address if available (e.g. on Android)
    val peerBluetoothAddress: String?

    fun close()
}