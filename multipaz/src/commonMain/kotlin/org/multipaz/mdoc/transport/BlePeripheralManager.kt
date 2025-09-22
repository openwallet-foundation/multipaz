package org.multipaz.mdoc.transport

import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.UUID
import kotlinx.coroutines.channels.Channel

internal interface BlePeripheralManager {
    val incomingMessages: Channel<ByteArray>

    fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?,
        startL2capServer: Boolean
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

    suspend fun advertiseService(uuid: UUID)

    suspend fun setESenderKey(eSenderKey: EcPublicKey)

    suspend fun waitForStateCharacteristicWriteOrL2CAPClient()

    suspend fun writeToStateCharacteristic(value: Int)

    suspend fun sendMessage(message: ByteArray)

    fun close()

    // The PSM if listening on L2CAP.
    //
    // This is guaranteed to be available after [advertiseService] is called if the `l2capCharacteristicUuid` passed
    // to [setUuids] isn't `null`.
    //
    val l2capPsm: Int?

    // True if connected via L2CAP
    val usingL2cap: Boolean
}