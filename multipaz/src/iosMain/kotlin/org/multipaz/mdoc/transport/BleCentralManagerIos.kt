package org.multipaz.mdoc.transport

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toByteArray
import org.multipaz.util.toKotlinError
import org.multipaz.util.toHex
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
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.readByteArray
import org.multipaz.crypto.Hkdf
import org.multipaz.util.getUInt32
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreFoundation.CFAbsoluteTime
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

internal class BleCentralManagerIos : BleCentralManager {
    companion object {
        private const val TAG = "BleCentralManagerIos"
        private const val L2CAP_CHUNK_SIZE = 512
    }

    private lateinit var stateCharacteristicUuid: UUID
    private lateinit var client2ServerCharacteristicUuid: UUID
    private lateinit var server2ClientCharacteristicUuid: UUID
    private var identCharacteristicUuid: UUID? = null
    private var l2capCharacteristicUuid: UUID? = null

    override fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capUuid: UUID?,
    ) {
        this.stateCharacteristicUuid = stateCharacteristicUuid
        this.client2ServerCharacteristicUuid = client2ServerCharacteristicUuid
        this.server2ClientCharacteristicUuid = server2ClientCharacteristicUuid
        this.identCharacteristicUuid = identCharacteristicUuid
        this.l2capCharacteristicUuid = l2capUuid
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
        PERIPHERAL_DISCOVERED,
        CONNECT_TO_PERIPHERAL,
        PERIPHERAL_DISCOVER_SERVICES,
        PERIPHERAL_DISCOVER_CHARACTERISTICS,
        PERIPHERAL_READ_VALUE_FOR_CHARACTERISTIC,
        CHARACTERISTIC_READY_TO_WRITE,
        OPEN_L2CAP_CHANNEL,
    }

    private data class WaitFor(
        val state: WaitState,
        val continuation: CancellableContinuation<Boolean>,
        val characteristic: CBCharacteristic? = null,
    )

    private var waitFor: WaitFor? = null

    private val centralManager: CBCentralManager

    private var peripheral: CBPeripheral? = null

    private var maxCharacteristicSize = 512

    private var readCharacteristic: CBCharacteristic? = null
    private var writeCharacteristic: CBCharacteristic? = null
    private var stateCharacteristic: CBCharacteristic? = null
    private var identCharacteristic: CBCharacteristic? = null
    private var l2capCharacteristic: CBCharacteristic? = null

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

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverServices: NSError?
        ) {
            val error = didDiscoverServices
            Logger.d(TAG, "didDiscoverServices peripheral=$peripheral error=${didDiscoverServices?.toKotlinError()}")
            if (waitFor?.state == WaitState.PERIPHERAL_DISCOVER_SERVICES) {
                if (error != null) {
                    resumeWaitWithException(error.toKotlinError())
                } else {
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "CBPeripheralDelegate didDiscoverServices callback but not waiting")
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            val service = didDiscoverCharacteristicsForService
            Logger.d(
                TAG,
                "didDiscoverCharacteristicsForService service=$service error=${error?.toKotlinError()}"
            )
            if (waitFor?.state == WaitState.PERIPHERAL_DISCOVER_CHARACTERISTICS) {
                if (error != null) {
                    resumeWaitWithException(error.toKotlinError())
                } else {
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "CBPeripheralDelegate didDiscoverCharacteristicsForService callback but not waiting")
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            val characteristic = didUpdateValueForCharacteristic
            Logger.d(
                TAG,
                "didUpdateValueForCharacteristic characteristic=$characteristic error=${error?.toKotlinError()}"
            )
            if (characteristic == readCharacteristic) {
                try {
                    handleIncomingData(characteristic.value!!.toByteArray())
                } catch (error: Throwable) {
                    onError(error)
                }
            } else {
                if (waitFor?.state == WaitState.PERIPHERAL_READ_VALUE_FOR_CHARACTERISTIC &&
                    waitFor?.characteristic == characteristic
                ) {
                    if (error != null) {
                        resumeWaitWithException(error.toKotlinError())
                    } else {
                        resumeWait()
                    }
                } else {
                    if (characteristic == stateCharacteristic) {
                        if (characteristic.value?.toByteArray() contentEquals byteArrayOf(
                                BleTransportConstants.STATE_CHARACTERISTIC_END.toByte()
                            )
                        ) {
                            Logger.i(TAG, "Received transport-specific termination message")
                            runBlocking {
                                incomingMessages.send(byteArrayOf())
                            }
                        } else {
                            Logger.w(TAG, "Unexpected value written to state characteristic")
                        }
                    } else {
                        Logger.w(TAG, "CBPeripheralDelegate didUpdateValueForCharacteristic callback but not waiting")
                    }
                }
            }
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            Logger.d(TAG, "peripheralIsReadyToSendWriteWithoutResponse peripheral=$peripheral")
            if (waitFor?.state == WaitState.CHARACTERISTIC_READY_TO_WRITE) {
                resumeWait()
            } else {
                Logger.w(TAG, "peripheralIsReadyToSendWriteWithoutResponse but not waiting")
            }
        }

        override fun peripheral(peripheral: CBPeripheral, didModifyServices: List<*>) {
            val invalidatedServices = didModifyServices
            Logger.d(TAG, "peripheral:didModifyServices invalidatedServices=${invalidatedServices}")
            onError(Error("Remote service vanished"))
        }

        override fun peripheral(peripheral: CBPeripheral, didOpenL2CAPChannel: CBL2CAPChannel?, error: NSError?) {
            Logger.d(TAG, "peripheralDidOpenL2CAPChannel")
            if (waitFor?.state == WaitState.OPEN_L2CAP_CHANNEL) {
                if (error != null) {
                    resumeWaitWithException(Error("peripheralDidOpenL2CAPChannel failed", error.toKotlinError()))
                } else {
                    this@BleCentralManagerIos.l2capChannel = didOpenL2CAPChannel
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "peripheralDidOpenL2CAPChannel but not waiting")
            }
        }
    }

    private suspend fun writeToCharacteristicWithoutResponse(
        characteristic: CBCharacteristic,
        value: ByteArray
    ) {
        check(peripheral != null)
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.CHARACTERISTIC_READY_TO_WRITE, continuation)

            Logger.i(TAG, "writing")
            peripheral!!.writeValue(
                data = value.toNSData(),
                forCharacteristic = characteristic,
                CBCharacteristicWriteWithoutResponse
            )
            if (peripheral!!.canSendWriteWithoutResponse) {
                throw Error("canSendWriteWithoutResponse is true right after writing value")
            }
        }
    }


    private var incomingMessage = ByteStringBuilder()

    override val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)

    private fun handleIncomingData(chunk: ByteArray) {
        if (chunk.size < 1) {
            throw Error("Invalid data length ${chunk.size} for Server2Client characteristic")
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
                    // Because this is not fatal and some buggy implementations do this, we treat
                    // it just as a warning, not an error.
                    Logger.w(TAG, "Server2Client received ${chunk.size} bytes which is not the " +
                            "expected $maxCharacteristicSize bytes"
                    )
                }
            }

            else -> {
                throw Error(
                    "Invalid first byte ${chunk[0]} in Server2Client data chunk, " +
                            "expected 0 or 1"
                )
            }
        }
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    private val centralManagerDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(cbCentralManager: CBCentralManager) {
            // TODO: b/393388152 - error: "centralManager must be initialized",
            //  also "cbCentralManager doesn't match super type param. name",
            //  also cbCentralManager param is not used in this method.
            //  IOS impl. review required.
            Logger.d(TAG, "didUpdateState state=${centralManager.state}")
            if (waitFor?.state == WaitState.POWER_ON) {
                if (centralManager.state == CBCentralManagerStatePoweredOn) {
                    resumeWait()
                } else {
                    resumeWaitWithException(Error("Excepted poweredOn, got ${centralManager.state}"))
                }
            } else {
                Logger.w(TAG, "CBCentralManagerDelegate didUpdateState callback but not waiting")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            val discoveredPeripheral = didDiscoverPeripheral
            Logger.d(TAG, "didDiscoverPeripheral: discoveredPeripheral=$discoveredPeripheral")
            if (waitFor?.state == WaitState.PERIPHERAL_DISCOVERED) {
                peripheral = discoveredPeripheral
                peripheral!!.delegate = peripheralDelegate
                resumeWait()
            } else {
                Logger.w(TAG, "CBCentralManagerDelegate didDiscoverPeripheral callback but not waiting")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            var connectedPeripheral = didConnectPeripheral
            Logger.d(TAG, "didDiscoverPeripheral: peripheral=$connectedPeripheral")
            if (waitFor?.state == WaitState.CONNECT_TO_PERIPHERAL) {
                if (connectedPeripheral == peripheral) {
                    resumeWait()
                } else {
                    Logger.w(TAG, "Callback for unexpected peripheral")
                }
            } else {
                Logger.w(TAG, "CBCentralManagerDelegate didConnectPeripheral callback but not waiting")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            timestamp: CFAbsoluteTime,
            isReconnecting: Boolean,
            error: NSError?
        ) {
            Logger.d(TAG, "didDisconnectPeripheral: peripheral=$didDisconnectPeripheral timestamp=${timestamp} " +
                    "isReconnecting=$isReconnecting error=${error?.toKotlinError()}")
            onError(Error("Peripheral unexpectedly disconnected"))
        }
    }

    init {
        centralManager = CBCentralManager(
            delegate = centralManagerDelegate,
            queue = null,
            options = null,
        )
    }

    override suspend fun waitForPowerOn() {
        if (centralManager.state != CBCentralManagerStatePoweredOn) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                setWaitCondition(WaitState.POWER_ON, continuation)
            }
        }
    }

    override suspend fun waitForPeripheralWithUuid(uuid: UUID) {
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.PERIPHERAL_DISCOVERED, continuation)
            centralManager.scanForPeripheralsWithServices(
                serviceUUIDs = listOf(CBUUID.UUIDWithString(uuid.toString())),
                options = null
            )
        }
        centralManager.stopScan()
    }

    override suspend fun connectToPeripheral() {
        check(peripheral != null)
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.CONNECT_TO_PERIPHERAL, continuation)
            centralManager.connectPeripheral(peripheral!!, null)
        }
    }

    override suspend fun requestMtu() {
        // The MTU isn't configurable in CoreBluetooth so this is a NO-OP.
    }

    override suspend fun peripheralDiscoverServices(uuid: UUID) {
        check(peripheral != null)
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.PERIPHERAL_DISCOVER_SERVICES, continuation)
            peripheral!!.discoverServices(
                serviceUUIDs = listOf(CBUUID.UUIDWithString(uuid.toString())),
            )
        }
    }

    private fun getCharacteristic(
        availableCharacteristics: List<CBCharacteristic>,
        uuid: UUID,
    ): CBCharacteristic {
        val uuidLowercase = uuid.toString().lowercase()
        val characteristic = availableCharacteristics.find {
            it.UUID.UUIDString.lowercase() == uuidLowercase
        }
        if (characteristic == null) {
            throw IllegalStateException("Missing characteristic with UUID $uuidLowercase")
        }
        return characteristic
    }

    private suspend fun processCharacteristics(
        availableCharacteristics: List<CBCharacteristic>
    ) {
        readCharacteristic = getCharacteristic(
            availableCharacteristics,
            server2ClientCharacteristicUuid
        )
        writeCharacteristic = getCharacteristic(
            availableCharacteristics,
            client2ServerCharacteristicUuid
        )
        stateCharacteristic = getCharacteristic(
            availableCharacteristics,
            stateCharacteristicUuid
        )
        if (identCharacteristicUuid != null) {
            identCharacteristic = getCharacteristic(
                availableCharacteristics,
                identCharacteristicUuid!!
            )
        }
        if (l2capCharacteristicUuid != null) {
            try {
                l2capCharacteristic = getCharacteristic(
                    availableCharacteristics,
                    l2capCharacteristicUuid!!
                )
                readValueForCharacteristic(l2capCharacteristic!!)
                val value = l2capCharacteristic!!.value!!.toByteArray()
                _l2capPsm = value.getUInt32(0).toInt()
                Logger.i(TAG, "L2CAP PSM is $_l2capPsm")
            } catch (e: Throwable) {
                Logger.i(TAG, "L2CAP not available on peripheral", e)
            }
        }
    }

    override suspend fun peripheralDiscoverCharacteristics() {
        check(peripheral != null)
        for (service in peripheral!!.services!!) {
            service as CBService
            suspendCancellableCoroutine<Boolean> { continuation ->
                setWaitCondition(WaitState.PERIPHERAL_DISCOVER_CHARACTERISTICS, continuation)
                peripheral!!.discoverCharacteristics(
                    characteristicUUIDs = null,
                    service,
                )
            }

            // Suppressing as all objects in list from the library appear to be subclassing CBCharacteristic. */
            @Suppress("UNCHECKED_CAST")
            processCharacteristics(service.characteristics as List<CBCharacteristic>)
        }

        val maximumWriteValueLengthForType = peripheral!!.maximumWriteValueLengthForType(
            CBCharacteristicWriteWithoutResponse
        ).toInt()
        maxCharacteristicSize = min(maximumWriteValueLengthForType, 512)
        Logger.i(TAG, "Using $maxCharacteristicSize as maximum data size for characteristics")
    }

    private suspend fun readValueForCharacteristic(characteristic: CBCharacteristic) {
        check(peripheral != null)
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(
                WaitState.PERIPHERAL_READ_VALUE_FOR_CHARACTERISTIC,
                continuation,
                characteristic
            )
            peripheral!!.readValueForCharacteristic(characteristic)
        }
    }

    override suspend fun checkReaderIdentMatches(eSenderKey: EcPublicKey) {
        check(peripheral != null)
        check(identCharacteristic != null)
        readValueForCharacteristic(identCharacteristic!!)

        val ikm = Cbor.encode(Tagged(24, Bstr(Cbor.encode(eSenderKey.toCoseKey().toDataItem()))))
        val info = "BLEIdent".encodeToByteArray()
        val salt = null
        val expectedIdentValue = Hkdf.deriveKey(Algorithm.HMAC_SHA256, ikm, salt, info, 16)
        val identValue = identCharacteristic!!.value!!.toByteArray()
        if (!(expectedIdentValue contentEquals identValue)) {
            close()
            throw Error(
                "Ident doesn't match, expected ${expectedIdentValue.toHex()} " +
                        " got ${identValue.toHex()}"
            )
        }
    }

    override suspend fun subscribeToCharacteristics() {
        check(stateCharacteristic != null)
        check(readCharacteristic != null)
        peripheral!!.setNotifyValue(true, stateCharacteristic!!)
        peripheral!!.setNotifyValue(true, readCharacteristic!!)
    }

    override suspend fun writeToStateCharacteristic(value: Int) {
        check(stateCharacteristic != null)
        writeToCharacteristicWithoutResponse(
            stateCharacteristic!!,
            byteArrayOf(value.toByte())
        )
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

            writeToCharacteristicWithoutResponse(writeCharacteristic!!, chunk)
        }
        Logger.i(TAG, "sendMessage completed")
    }

    override fun close() {
        centralManager.delegate = null
        // Delayed closed because there's no way to flush L2CAP connections...
        peripheral?.let {
            CoroutineScope(Dispatchers.IO).launch {
                // Need 25 seconds for ZKP since the proofs are large
                delay(25_000)
                centralManager.cancelPeripheralConnection(it)
            }
        }
        peripheral?.delegate = null
        peripheral = null
        incomingMessages.close()
        l2capSink?.close()
        l2capSink = null
        l2capSource?.close()
        l2capSource = null
        l2capChannel = null
    }

    private var _l2capPsm: Int? = null

    override val l2capPsm: Int?
        get() = _l2capPsm

    override val usingL2cap: Boolean
        get() = (l2capChannel != null)

    private var l2capChannel: CBL2CAPChannel? = null
    private var l2capSink: Sink? = null
    private var l2capSource: Source? = null

    override suspend fun connectL2cap(psm: Int) {
        Logger.i(TAG, "connectL2cap $psm")
        check(peripheral != null)
        if (peripheral!!.state != CBPeripheralStateConnected) {
            // This can happen on the path where the PSM is used directly from the engagement
            connectToPeripheral()
        }
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.OPEN_L2CAP_CHANNEL, continuation)
            peripheral!!.openL2CAPChannel(psm.toUShort())
        }
        Logger.i(TAG, "l2capChannel open")
        l2capSink = l2capChannel!!.outputStream!!.asSink().buffered()
        l2capSource = l2capChannel!!.inputStream!!.asSource().buffered()

        // Start reading in a coroutine
        CoroutineScope(Dispatchers.IO).launch {
            l2capReadChannel()
        }
    }

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
            withContext(Dispatchers.IO) {
                l2capSink?.write(chunk)
                l2capSink?.flush()
            }
        }
    }
}
