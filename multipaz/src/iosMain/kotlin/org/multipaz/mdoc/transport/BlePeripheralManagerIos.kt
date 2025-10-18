package org.multipaz.mdoc.transport

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toByteArray
import org.multipaz.util.toHex
import org.multipaz.util.toKotlinError
import org.multipaz.util.toNSData
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.readByteArray
import org.multipaz.crypto.Hkdf
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBL2CAPPSM
import platform.CoreBluetooth.CBUUID
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.Foundation.NSArray
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

internal class BlePeripheralManagerIos: BlePeripheralManager {
    companion object {
        private const val TAG = "BlePeripheralManagerIos"
        private const val L2CAP_CHUNK_SIZE = 512
    }

    private lateinit var stateCharacteristicUuid: UUID
    private lateinit var client2ServerCharacteristicUuid: UUID
    private lateinit var server2ClientCharacteristicUuid: UUID
    private var identCharacteristicUuid: UUID? = null
    private var l2capCharacteristicUuid: UUID? = null
    private var startL2capServer = false

    override fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?,
        startL2capServer: Boolean
    ) {
        this.stateCharacteristicUuid = stateCharacteristicUuid
        this.client2ServerCharacteristicUuid = client2ServerCharacteristicUuid
        this.server2ClientCharacteristicUuid = server2ClientCharacteristicUuid
        this.identCharacteristicUuid = identCharacteristicUuid
        this.l2capCharacteristicUuid = l2capCharacteristicUuid
        this.startL2capServer = startL2capServer
    }

    private lateinit var onError: (error: Throwable) -> Unit
    private lateinit var onClosed: () -> Unit

    override fun setCallbacks(
        onError: (Throwable) -> Unit,
        onClosed: () -> Unit
    ) {
        this.onError = onError
        this.onClosed = onClosed
    }

    internal enum class WaitState {
        POWER_ON,
        STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT,
        CHARACTERISTIC_READY_TO_WRITE,
        PUBLISH_L2CAP_CHANNEL,
    }

    private data class WaitFor(
        val state: WaitState,
        val continuation: CancellableContinuation<Boolean>,
        val characteristic: CBCharacteristic? = null,
    )

    private var waitFor: WaitFor? = null

    private fun setWaitCondition(
        state: WaitState,
        continuation: CancellableContinuation<Boolean>,
        characteristic: CBCharacteristic? = null
    ) {
        check(waitFor == null)
        waitFor = WaitFor(
            state,
            continuation,
            characteristic
        )
    }

    private fun resumeWait() {
        val continuation = waitFor!!.continuation
        waitFor = null
        continuation.resume(true)
    }

    private fun resumeWaitWithException(exception: Throwable) {
        val continuation = waitFor!!.continuation
        waitFor = null
        continuation.resumeWithException(exception)
    }

    private var maxCharacteristicSize = -1

    private var incomingMessage = ByteStringBuilder()

    override val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)

    private fun handleIncomingData(chunk: ByteArray) {
        if (chunk.size < 1) {
            throw Error("Invalid data length ${chunk.size} for Client2Server characteristic")
        }
        incomingMessage.append(chunk, 1, chunk.size)
        when {
            chunk[0].toInt() == 0x00 -> {
                // Last message.
                val newMessage = incomingMessage.toByteString().toByteArray()
                incomingMessage = ByteStringBuilder()
                runBlocking {
                    incomingMessages.send(newMessage)
                }
            }

            chunk[0].toInt() == 0x01 -> {
                if (chunk.size != maxCharacteristicSize) {
                    Logger.w(TAG, "Client2Server received ${chunk.size} bytes which is not the " +
                            "expected $maxCharacteristicSize bytes")
                }
            }

            else -> {
                throw Error("Invalid first byte ${chunk[0]} in Client2Server data chunk, " +
                            "expected 0 or 1")
            }
        }
    }

    private val peripheralManager: CBPeripheralManager

    private val peripheralManagerDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (waitFor?.state == WaitState.POWER_ON) {
                if (peripheralManager.state == CBPeripheralManagerStatePoweredOn) {
                    resumeWait()
                } else {
                    resumeWaitWithException(Error("Excepted poweredOn, got ${peripheralManager.state}"))
                }
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>
        ) {
            for (attRequest in didReceiveWriteRequests) {
                attRequest as CBATTRequest

                if (attRequest.characteristic == stateCharacteristic) {
                    val data = attRequest.value?.toByteArray() ?: byteArrayOf()
                    if (waitFor?.state == WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT) {
                        if (!(data contentEquals byteArrayOf(
                                BleTransportConstants.STATE_CHARACTERISTIC_START.toByte()
                        ))) {
                            resumeWaitWithException(
                                Error("Expected 0x01 to be written to state characteristic, got ${data.toHex()}")
                            )
                        } else {
                            // Now that the central connected, figure out how big the buffer is for writes.
                            val maximumUpdateValueLength = attRequest.central.maximumUpdateValueLength.toInt()
                            maxCharacteristicSize = min(maximumUpdateValueLength, 512)
                            Logger.i(TAG, "Using $maxCharacteristicSize as maximum data size for characteristics")

                            // Since the central found us, we can stop advertising....
                            peripheralManager.stopAdvertising()

                            resumeWait()
                        }
                    } else {
                        if (data contentEquals byteArrayOf(BleTransportConstants.STATE_CHARACTERISTIC_END.toByte())) {
                            Logger.i(TAG, "Received transport-specific termination message")
                            runBlocking {
                                incomingMessages.send(byteArrayOf())
                            }
                        } else {
                            Logger.w(TAG, "Got write to state characteristics without waiting for it")
                        }
                    }
                } else if (attRequest.characteristic == readCharacteristic) {
                    val data = attRequest.value?.toByteArray() ?: byteArrayOf()
                    try {
                        handleIncomingData(data)
                    } catch (e: Throwable) {
                        onError(Error("Error processing incoming data", e))
                    }
                } else {
                    Logger.w(TAG, "Unexpected write to characteristic with UUID " +
                            attRequest.characteristic.UUID.UUIDString)
                }
            }
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) {
            val attRequest = didReceiveReadRequest
            if (attRequest.characteristic == identCharacteristic) {
                if (identValue == null) {
                    onError(Error("Received request for ident before it's set.."))
                } else {
                    attRequest.value = identValue!!.toNSData()
                    peripheralManager.respondToRequest(attRequest, CBATTErrorSuccess)
                }
            }
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            if (waitFor?.state == WaitState.CHARACTERISTIC_READY_TO_WRITE) {
                resumeWait()
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didPublishL2CAPChannel: CBL2CAPPSM,
            error: NSError?
        ) {
            Logger.i(TAG, "peripheralManager didPublishL2CAPChannel")
            if (waitFor?.state == WaitState.PUBLISH_L2CAP_CHANNEL) {
                if (error != null) {
                    resumeWaitWithException(Error("peripheralManager didPublishL2CAPChannel failed", error.toKotlinError()))
                } else {
                    _l2capPsm = didPublishL2CAPChannel.toInt()
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "peripheralManager didPublishL2CAPChannel but not waiting")
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didOpenL2CAPChannel: CBL2CAPChannel?,
            error: NSError?
        ) {
            Logger.i(TAG, "peripheralManager didOpenL2CAPChannel")

            if (error != null) {
                Logger.w(TAG, "peripheralManager didOpenL2CAPChannel error=$error")
            } else {
                if (waitFor?.state == WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT) {
                    // Since the central found us, we can stop advertising....
                    peripheralManager.stopAdvertising()

                    l2capChannel = didOpenL2CAPChannel
                    l2capSink = l2capChannel!!.outputStream!!.asSink().buffered()
                    l2capSource = l2capChannel!!.inputStream!!.asSource().buffered()

                    // Start reading in a coroutine
                    CoroutineScope(Dispatchers.IO).launch {
                        l2capReadChannel()
                    }

                    resumeWait()
                } else {
                    Logger.w(TAG, "Ignoring incoming L2CAP connection since we're not waiting")
                }
            }
        }

    }

    init {
        peripheralManager = CBPeripheralManager(
            delegate = peripheralManagerDelegate,
            queue = null,
            options = null
        )
    }

    override suspend fun waitForPowerOn() {
        if (peripheralManager.state != CBPeripheralManagerStatePoweredOn) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                setWaitCondition(WaitState.POWER_ON, continuation)
            }
        }
    }

    private var service: CBMutableService? = null

    private var readCharacteristic: CBMutableCharacteristic? = null
    private var writeCharacteristic: CBMutableCharacteristic? = null
    private var stateCharacteristic: CBMutableCharacteristic? = null
    private var identCharacteristic: CBMutableCharacteristic? = null
    private var l2capCharacteristic: CBMutableCharacteristic? = null
    private var identValue: ByteArray? = null

    override suspend fun advertiseService(uuid: UUID) {
        service = CBMutableService(
            type = CBUUID.UUIDWithString(uuid.toString()),
            primary = true
        )
        stateCharacteristic = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(stateCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyNotify +
                    CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        readCharacteristic = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(client2ServerCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        writeCharacteristic = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(server2ClientCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyNotify,
            value = null,
            permissions = CBAttributePermissionsReadable + CBAttributePermissionsWriteable,
        )
        if (identCharacteristicUuid != null) {
            identCharacteristic = CBMutableCharacteristic(
                type = CBUUID.UUIDWithString(identCharacteristicUuid.toString()),
                properties = CBCharacteristicPropertyRead,
                value = null,
                permissions = CBAttributePermissionsReadable,
            )
        }
        if (l2capCharacteristicUuid != null || startL2capServer) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                setWaitCondition(WaitState.PUBLISH_L2CAP_CHANNEL, continuation)
                peripheralManager.publishL2CAPChannelWithEncryption(false)
            }
            Logger.i(TAG, "Listening on PSM $_l2capPsm")
            if (l2capCharacteristicUuid != null) {
                val bsb = ByteStringBuilder()
                bsb.apply {
                    append((_l2capPsm!! shr 24).and(0xff).toByte())
                    append((_l2capPsm!! shr 16).and(0xff).toByte())
                    append((_l2capPsm!! shr 8).and(0xff).toByte())
                    append((_l2capPsm!! shr 0).and(0xff).toByte())
                }
                val encodedPsm = bsb.toByteString().toByteArray()
                l2capCharacteristic = CBMutableCharacteristic(
                    type = CBUUID.UUIDWithString(l2capCharacteristicUuid.toString()),
                    properties = CBCharacteristicPropertyRead,
                    value = encodedPsm.toNSData(),
                    permissions = CBAttributePermissionsReadable,
                )
            }
        }

        service!!.setCharacteristics((
                service!!.characteristics ?: listOf<CBMutableCharacteristic>()) +
                listOf(
                    stateCharacteristic,
                    readCharacteristic,
                    writeCharacteristic,
                ) +
                (if (identCharacteristic != null) listOf(identCharacteristic) else listOf()) +
                (if (l2capCharacteristic != null) listOf(l2capCharacteristic) else listOf())
        )
        peripheralManager.addService(service!!)
        peripheralManager.startAdvertising(
            mapOf(
                CBAdvertisementDataServiceUUIDsKey to
                        (listOf(CBUUID.UUIDWithString(uuid.toString())) as NSArray)
            )
        )
    }

    override suspend fun setESenderKey(eSenderKey: EcPublicKey) {
        val ikm = Cbor.encode(Tagged(24, Bstr(Cbor.encode(eSenderKey.toCoseKey().toDataItem()))))
        val info = "BLEIdent".encodeToByteArray()
        val salt = null
        identValue = Hkdf.deriveKey(Algorithm.HMAC_SHA256, ikm, salt, info, 16)
    }

    override suspend fun waitForStateCharacteristicWriteOrL2CAPClient() {
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT, continuation)
        }
    }

    private suspend fun writeToCharacteristic(
        characteristic: CBMutableCharacteristic,
        value: ByteArray,
    ) {
        while (true) {
            val wasSent = peripheralManager.updateValue(
                value = value.toNSData(),
                forCharacteristic = characteristic,
                onSubscribedCentrals = null
            )
            if (wasSent) {
                Logger.i(TAG, "Wrote to characteristic ${characteristic.UUID}")
                break
            }
            Logger.i(TAG, "Not ready to send to characteristic ${characteristic.UUID}, waiting")
            suspendCancellableCoroutine<Boolean> { continuation ->
                setWaitCondition(WaitState.CHARACTERISTIC_READY_TO_WRITE, continuation)
            }
        }
    }

    override suspend fun writeToStateCharacteristic(value: Int) {
        writeToCharacteristic(stateCharacteristic!!, byteArrayOf(value.toByte()))
    }

    override suspend fun sendMessage(message: ByteArray) {
        if (l2capChannel != null) {
            l2capSendMessage(message)
            return
        }
        Logger.i(TAG, "sendMessage ${message.size} length")
        val maxChunkSize = maxCharacteristicSize - 1  // Need room for the leading 0x00 or 0x01
        val offsets = 0 until message.size step maxChunkSize
        for (offset in offsets) {
            val moreDataComing = (offset != offsets.last)
            val size = min(maxChunkSize, message.size - offset)

            val builder = ByteStringBuilder(size + 1)
            builder.append(if (moreDataComing) 0x01 else 0x00)
            builder.append(message, offset, offset + size)
            val chunk = builder.toByteString().toByteArray()

            writeToCharacteristic(writeCharacteristic!!, chunk)
        }
        Logger.i(TAG, "sendMessage completed")
    }

    override fun close() {
        // Delayed closed because there's no way to flush L2CAP connections...
        _l2capPsm?.let {
            CoroutineScope(Dispatchers.IO).launch {
                // Need 25 seconds for ZKP since the proofs are large
                delay(25_000)
                peripheralManager.unpublishL2CAPChannel(it.toUShort())
            }
        }
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        service = null
        peripheralManager.delegate = null
        incomingMessages.close()
        l2capSink?.close()
        l2capSink = null
        l2capSource?.close()
        l2capSource = null
        l2capChannel = null
    }

    override val l2capPsm: Int?
        get() = _l2capPsm

    private var _l2capPsm: Int? = null

    private var l2capChannel: CBL2CAPChannel? = null
    private var l2capSink: Sink? = null
    private var l2capSource: Source? = null

    override val usingL2cap: Boolean
        get() = (l2capChannel != null)

    private suspend fun l2capReadChannel() {
        try {
            while (true) {
                val length = l2capSource!!.readInt()
                val message = l2capSource!!.readByteArray(length)
                incomingMessages.send(message)
            }
        } catch (e: Throwable) {
            onError(Error("Reading from L2CAP channel failed", e))
        }
    }

    private suspend fun l2capSendMessage(message: ByteArray) {
        Logger.i(TAG, "l2capSendMessage ${message.size} length")
        val bsb = ByteStringBuilder()
        val length = message.size.toUInt()
        bsb.apply {
            append((length shr 24).and(0xffU).toByte())
            append((length shr 16).and(0xffU).toByte())
            append((length shr 8).and(0xffU).toByte())
            append((length shr 0).and(0xffU).toByte())
        }
        bsb.append(message)
        val messageWithHeader = bsb.toByteString().toByteArray()

        // NOTE: for some reason, iOS just fills in zeroes near the end if we send a
        //  really large message. Chunking it up in individual writes fixes this. We use
        //  the constant value L2CAP_CHUNK_SIZE for the chunk size.
        //
        //l2capSink?.write(payload)
        //l2capSink?.emit()

        for (offset in 0 until messageWithHeader.size step L2CAP_CHUNK_SIZE) {
            val size = min(L2CAP_CHUNK_SIZE, messageWithHeader.size - offset)
            val chunk = messageWithHeader.slice(IntRange(offset, offset + size - 1)).toByteArray()
            l2capSink?.write(chunk)
            l2capSink?.flush()
        }
    }

}

