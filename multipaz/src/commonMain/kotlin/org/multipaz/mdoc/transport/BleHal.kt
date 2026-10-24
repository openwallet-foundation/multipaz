package org.multipaz.mdoc.transport

import org.multipaz.util.UUID

/**
 * Abstraction for an L2CAP channel used by [BleCentralManagerImpl] and [BlePeripheralManagerImpl].
 */
internal interface BleL2capChannel {
    /**
     * Reads up to [length] bytes into [buffer] starting at [offset].
     *
     * @return the number of bytes read, or `-1` if the stream has reached end-of-file.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Writes all bytes in [data] to the L2CAP channel and flushes it.
     */
    suspend fun write(data: ByteArray)

    /**
     * Closes the L2CAP channel.
     */
    fun close()
}

/**
 * Platform-specific low-level BLE Central HAL used by [BleCentralManagerImpl].
 *
 * All ISO/IEC 18013-5 protocol state machines, continuation management, retry/backoff loops,
 * `BLEIdent` HKDF verification, GATT message chunking/reassembly, and L2CAP 4-byte length-prefixed
 * framing live in [BleCentralManagerImpl] in `commonMain`. Implementations of this interface only
 * perform thin OS Bluetooth calls and forward raw OS callbacks to [Callback].
 */
internal interface BleCentralHal {
    interface Callback {
        fun onPowerOn(error: Throwable?)
        fun onPeripheralDiscovered()
        fun onScanFailed(error: Throwable)
        fun onConnected()
        fun onDisconnected()
        fun onMtuChanged(mtu: Int, error: Throwable?)
        fun onServicesDiscovered(hasTargetService: Boolean, error: Throwable?)
        fun onCharacteristicsDiscovered(
            maxWriteSize: Int?,
            hasL2capCharacteristic: Boolean,
            error: Throwable?
        )
        fun onCharacteristicRead(uuid: UUID, value: ByteArray?, error: Throwable?)
        fun onCharacteristicWrite(uuid: UUID, error: Throwable?)
        fun onDescriptorWrite(characteristicUuid: UUID, error: Throwable?)
        fun onCharacteristicChanged(uuid: UUID, value: ByteArray)
        fun onL2capConnected(channel: BleL2capChannel?, error: Throwable?)
        fun onError(error: Throwable)
    }

    /**
     * Whether a connection to the peripheral has already been established.
     */
    val isConnected: Boolean

    fun setCallback(callback: Callback)

    fun waitForPowerOn()

    fun startScan(serviceUuid: UUID)

    fun stopScan()

    fun onScanStoppedAfterDiscovery()

    fun connectToPeripheral()

    fun requestMtu(mtu: Int)

    fun discoverServices(serviceUuid: UUID)

    fun discoverCharacteristics(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?
    )

    fun readCharacteristic(uuid: UUID)

    fun enableNotifications(characteristicUuid: UUID)

    fun writeCharacteristic(uuid: UUID, value: ByteArray)

    fun connectL2cap(psm: Int)

    fun closeConnectedL2capAttempt()

    fun close()
}

/**
 * Platform-specific low-level BLE Peripheral HAL used by [BlePeripheralManagerImpl].
 *
 * All ISO/IEC 18013-5 protocol state machines, continuation management, `BLEIdent` HKDF calculation,
 * L2CAP PSM 4-byte big-endian encoding, GATT message chunking/reassembly, and L2CAP 4-byte
 * length-prefixed framing live in [BlePeripheralManagerImpl] in `commonMain`. Implementations of
 * this interface only perform thin OS Bluetooth calls and forward raw OS callbacks to [Callback].
 */
internal interface BlePeripheralHal {
    interface Callback {
        fun onPowerOn(error: Throwable?)
        fun onL2capPublished(psm: Int?, error: Throwable?)
        fun onServiceAdded(error: Throwable?)
        fun onAdvertisingStarted(error: Throwable?)
        fun onMtuChanged(mtu: Int)
        fun onCharacteristicReadRequest(requestId: Int, uuid: UUID)
        fun onCharacteristicWriteRequest(
            uuid: UUID,
            value: ByteArray,
            maxWriteSizeForCentral: Int?
        )
        fun onNotificationSent(uuid: UUID, error: Throwable?)
        fun onL2capClientConnected(channel: BleL2capChannel)
        fun onCentralDisconnected()
        fun onError(error: Throwable)
    }

    fun setCallback(callback: Callback)

    fun waitForPowerOn()

    fun publishL2capChannel()

    fun closeL2capServer()

    fun setupGattServerAndAddService(
        serviceUuid: UUID,
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?,
        encodedL2capPsm: ByteArray?
    )

    fun startAdvertising(serviceUuid: UUID)

    fun stopAdvertising()

    fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray)

    fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray)

    fun close()
}
