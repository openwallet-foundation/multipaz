package org.multipaz.bthci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.multipaz.util.Logger
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal interface UsbHciConnection {
    val interfaceId: Int
    val interruptMaxPacketSize: Int
    val bulkInMaxPacketSize: Int

    @Throws(IOException::class)
    fun open(
        onEventChunk: (chunk: ByteArray, length: Int, isShortPacket: Boolean) -> Unit,
        onAclChunk: (chunk: ByteArray, length: Int, isShortPacket: Boolean) -> Unit,
        onReaderError: (IOException) -> Unit,
    )

    @Throws(IOException::class)
    fun sendCommand(payload: ByteArray, timeoutMs: Int)

    @Throws(IOException::class)
    fun sendAcl(payload: ByteArray, timeoutMs: Int)

    fun stopReader()

    fun drainInterruptIn(buffer: ByteArray, timeoutMs: Int): Int

    fun close()
}

private class AndroidUsbHciConnection(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val endpoints: AndroidUsbHciPacketIo.UsbHciEndpoints,
) : UsbHciConnection {
    companion object {
        private const val TAG = "AndroidUsbHciConn"
        private const val READER_POLL_TIMEOUT_MS = 200L
        private const val INITIAL_DRAIN_TIMEOUT_MS = 10
        private const val MAX_INITIAL_DRAIN_READS = 16
        private const val USB_RECIP_DEVICE = 0x00
        private const val USB_RECIP_INTERFACE = 0x01
    }

    override val interfaceId: Int
        get() = endpoints.usbInterface.id

    override val interruptMaxPacketSize: Int
        get() = endpoints.interruptIn.maxPacketSize.coerceAtLeast(16)

    override val bulkInMaxPacketSize: Int
        get() = endpoints.bulkIn.maxPacketSize.coerceAtLeast(64)

    @Volatile
    private var connection: UsbDeviceConnection? = null

    @Volatile
    private var interruptRequest: UsbRequest? = null

    @Volatile
    private var bulkInRequest: UsbRequest? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var readerStopped: Boolean = false

    override fun open(
        onEventChunk: (chunk: ByteArray, length: Int, isShortPacket: Boolean) -> Unit,
        onAclChunk: (chunk: ByteArray, length: Int, isShortPacket: Boolean) -> Unit,
        onReaderError: (IOException) -> Unit,
    ) {
        if (!usbManager.hasPermission(device)) {
            throw IOException("USB permission not granted for Bluetooth HCI device ${device.deviceName}")
        }
        val conn = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB Bluetooth HCI device ${device.deviceName}")
        if (!conn.claimInterface(endpoints.usbInterface, true)) {
            conn.close()
            throw IOException(
                "Failed to claim USB Bluetooth HCI interface ${endpoints.usbInterface.id} on ${device.deviceName}"
            )
        }
        connection = conn

        val drainBuf = ByteArray(maxOf(interruptMaxPacketSize, bulkInMaxPacketSize))
        for (i in 0 until MAX_INITIAL_DRAIN_READS) {
            val n = conn.bulkTransfer(
                endpoints.interruptIn,
                drainBuf,
                interruptMaxPacketSize,
                INITIAL_DRAIN_TIMEOUT_MS
            )
            if (n <= 0) break
        }
        for (i in 0 until MAX_INITIAL_DRAIN_READS) {
            val n = conn.bulkTransfer(
                endpoints.bulkIn,
                drainBuf,
                bulkInMaxPacketSize,
                INITIAL_DRAIN_TIMEOUT_MS
            )
            if (n <= 0) break
        }

        val intReq = UsbRequest()
        val bulkReq = UsbRequest()
        if (!intReq.initialize(conn, endpoints.interruptIn)) {
            intReq.close()
            conn.releaseInterface(endpoints.usbInterface)
            conn.close()
            connection = null
            throw IOException("Failed to initialize USB Interrupt IN UsbRequest on ${device.deviceName}")
        }
        if (!bulkReq.initialize(conn, endpoints.bulkIn)) {
            intReq.close()
            bulkReq.close()
            conn.releaseInterface(endpoints.usbInterface)
            conn.close()
            connection = null
            throw IOException("Failed to initialize USB Bulk IN UsbRequest on ${device.deviceName}")
        }

        val intBuf = ByteBuffer.allocateDirect(interruptMaxPacketSize)
        val bulkBuf = ByteBuffer.allocateDirect(bulkInMaxPacketSize)
        intBuf.clear()
        bulkBuf.clear()

        if (!intReq.queue(intBuf)) {
            intReq.close()
            bulkReq.close()
            conn.releaseInterface(endpoints.usbInterface)
            conn.close()
            connection = null
            throw IOException("Failed to queue initial USB Interrupt IN transfer on ${device.deviceName}")
        }
        if (!bulkReq.queue(bulkBuf)) {
            intReq.cancel()
            intReq.close()
            bulkReq.close()
            conn.releaseInterface(endpoints.usbInterface)
            conn.close()
            connection = null
            throw IOException("Failed to queue initial USB Bulk IN transfer on ${device.deviceName}")
        }

        interruptRequest = intReq
        bulkInRequest = bulkReq

        val thread = Thread(
            {
                runReaderLoop(
                    conn = conn,
                    intReq = intReq,
                    intBuf = intBuf,
                    bulkReq = bulkReq,
                    bulkBuf = bulkBuf,
                    onEventChunk = onEventChunk,
                    onAclChunk = onAclChunk,
                    onReaderError = onReaderError,
                )
            },
            "AndroidUsbHciReader-${device.deviceId}"
        ).apply {
            isDaemon = true
        }
        readerThread = thread
        thread.start()
    }

    private fun runReaderLoop(
        conn: UsbDeviceConnection,
        intReq: UsbRequest,
        intBuf: ByteBuffer,
        bulkReq: UsbRequest,
        bulkBuf: ByteBuffer,
        onEventChunk: (chunk: ByteArray, length: Int, isShortPacket: Boolean) -> Unit,
        onAclChunk: (chunk: ByteArray, length: Int, isShortPacket: Boolean) -> Unit,
        onReaderError: (IOException) -> Unit,
    ) {
        val intScratch = ByteArray(interruptMaxPacketSize)
        val bulkScratch = ByteArray(bulkInMaxPacketSize)
        try {
            while (!readerStopped) {
                val completed = try {
                    conn.requestWait(READER_POLL_TIMEOUT_MS)
                } catch (_: TimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!readerStopped) {
                        onReaderError(IOException("USB HCI requestWait failed on ${device.deviceName}", e))
                    }
                    break
                }

                if (readerStopped) break
                if (completed == null) {
                    if (!readerStopped) {
                        onReaderError(IOException("USB Bluetooth HCI device ${device.deviceName} disconnected"))
                    }
                    break
                }

                if (completed === intReq) {
                    val bytesRead = intBuf.position()
                    if (bytesRead > 0) {
                        intBuf.flip()
                        intBuf.get(intScratch, 0, bytesRead)
                    }
                    try {
                        onEventChunk(intScratch, bytesRead, bytesRead < interruptMaxPacketSize)
                    } catch (e: IOException) {
                        if (!readerStopped) onReaderError(e)
                        break
                    }
                    if (readerStopped) break
                    intBuf.clear()
                    if (!intReq.queue(intBuf)) {
                        if (!readerStopped) {
                            onReaderError(
                                IOException("Failed to re-queue USB Interrupt IN transfer on ${device.deviceName}")
                            )
                        }
                        break
                    }
                } else if (completed === bulkReq) {
                    val bytesRead = bulkBuf.position()
                    if (bytesRead > 0) {
                        bulkBuf.flip()
                        bulkBuf.get(bulkScratch, 0, bytesRead)
                    }
                    try {
                        onAclChunk(bulkScratch, bytesRead, bytesRead < bulkInMaxPacketSize)
                    } catch (e: IOException) {
                        if (!readerStopped) onReaderError(e)
                        break
                    }
                    if (readerStopped) break
                    bulkBuf.clear()
                    if (!bulkReq.queue(bulkBuf)) {
                        if (!readerStopped) {
                            onReaderError(
                                IOException("Failed to re-queue USB Bulk IN transfer on ${device.deviceName}")
                            )
                        }
                        break
                    }
                }
            }
        } finally {
            try {
                intReq.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing Interrupt IN UsbRequest", e)
            }
            try {
                bulkReq.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing Bulk IN UsbRequest", e)
            }
            interruptRequest = null
            bulkInRequest = null
        }
    }

    override fun sendCommand(payload: ByteArray, timeoutMs: Int) {
        val conn = connection ?: throw IOException("USB Bluetooth HCI connection is not open")
        val requestType = if (interfaceId == 0) {
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_DEVICE
        } else {
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE
        }
        val index = if (interfaceId == 0) 0 else interfaceId
        val transferred = conn.controlTransfer(
            requestType,
            0x00,
            0x0000,
            index,
            payload,
            payload.size,
            timeoutMs
        )
        if (transferred != payload.size) {
            throw IOException(
                "USB HCI command controlTransfer failed on ${device.deviceName}: " +
                    "transferred=$transferred expected=${payload.size}"
            )
        }
    }

    override fun sendAcl(payload: ByteArray, timeoutMs: Int) {
        val conn = connection ?: throw IOException("USB Bluetooth HCI connection is not open")
        val transferred = conn.bulkTransfer(endpoints.bulkOut, payload, payload.size, timeoutMs)
        if (transferred != payload.size) {
            throw IOException(
                "USB HCI ACL bulkTransfer failed on ${device.deviceName}: " +
                    "transferred=$transferred expected=${payload.size}"
            )
        }
    }

    override fun stopReader() {
        readerStopped = true
        try {
            interruptRequest?.cancel()
        } catch (_: Exception) {
        }
        try {
            bulkInRequest?.cancel()
        } catch (_: Exception) {
        }
        val thread = readerThread
        if (thread != null && Thread.currentThread() !== thread) {
            try {
                thread.join(1000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun drainInterruptIn(buffer: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        return try {
            conn.bulkTransfer(endpoints.interruptIn, buffer, buffer.size, timeoutMs)
        } catch (_: Exception) {
            -1
        }
    }

    override fun close() {
        stopReader()
        val conn = connection
        connection = null
        if (conn != null) {
            try {
                conn.releaseInterface(endpoints.usbInterface)
            } catch (e: Exception) {
                Logger.w(TAG, "Error releasing USB HCI interface", e)
            }
            try {
                conn.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing USB HCI connection", e)
            }
        }
    }
}

/**
 * Android USB Host (`android.hardware.usb`) implementation of [HciPacketIo] for external USB
 * Bluetooth HCI Primary Controllers (Bluetooth Core Specification Vol 4, Part B).
 *
 * Outbound H4 HCI Command packets (`0x01`) are stripped of their H4 indicator and sent via USB
 * Class Control transfers on Endpoint 0 (`bmRequestType = 0x20` for interface 0 or `0x21` for
 * composite non-zero interfaces). Outbound H4 ACL Data packets (`0x02`) are sent over the Bulk OUT
 * endpoint. Inbound HCI Events and ACL Data are continuously received over the Interrupt IN and
 * Bulk IN endpoints respectively, reassembled into complete frames, and prefixed with H4 packet
 * indicators (`0x04` and `0x02`).
 */
class AndroidUsbHciPacketIo internal constructor(
    private val deviceKey: String,
    private val connectionFactory: () -> UsbHciConnection,
    private val transferTimeout: Duration = 2.seconds,
) : HciPacketIo {

    /**
     * Constructs an [AndroidUsbHciPacketIo] bound to [device] via [usbManager].
     *
     * Call [open] before invoking [readPacket] or [writePacket].
     *
     * @param usbManager the Android [UsbManager] system service.
     * @param device the attached [UsbDevice] exposing a Bluetooth HCI interface.
     * @param transferTimeout timeout for outbound USB control and bulk transfers.
     */
    constructor(
        usbManager: UsbManager,
        device: UsbDevice,
        transferTimeout: Duration = 2.seconds,
    ) : this(
        deviceKey = device.deviceName,
        connectionFactory = {
            val endpoints = findBluetoothHciInterface(device)
                ?: throw IOException("No Bluetooth HCI USB interface found on device ${device.deviceName}")
            AndroidUsbHciConnection(
                usbManager = usbManager,
                device = device,
                endpoints = endpoints,
            )
        },
        transferTimeout = transferTimeout,
    )

    /**
     * USB interface and endpoints for a Bluetooth HCI Primary Controller over USB
     * (Bluetooth Core Specification Vol 4, Part B).
     *
     * @property usbInterface the USB HCI interface to claim.
     * @property interruptIn the Interrupt IN endpoint carrying HCI Event packets.
     * @property bulkIn the Bulk IN endpoint carrying inbound HCI ACL Data packets.
     * @property bulkOut the Bulk OUT endpoint carrying outbound HCI ACL Data packets.
     */
    data class UsbHciEndpoints(
        val usbInterface: UsbInterface,
        val interruptIn: UsbEndpoint,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    companion object {
        private const val TAG = "AndroidUsbHciPacketIo"
        private const val BT_HCI_SUBCLASS = 0x01
        private const val BT_HCI_PROTOCOL = 0x01
        private const val FALLBACK_RESET_TIMEOUT_MS = 200
        private const val FALLBACK_RESET_DRAIN_MS = 100

        // HCI_Reset command payload without H4 indicator: [opcodeLo=0x03, opcodeHi=0x0C, paramLen=0x00]
        private val HCI_RESET_PAYLOAD = byteArrayOf(0x03, 0x0C, 0x00)

        private val activeDevicesLock = Any()
        private val activeDevices = mutableSetOf<String>()

        /**
         * Inspects [device]'s USB descriptors and returns its [UsbHciEndpoints] if it exposes a
         * standard (`0xE0/0x01/0x01`) or Broadcom-style vendor-specific (`0xFF/0x01/0x01`)
         * Bluetooth HCI Primary Controller interface with Interrupt IN, Bulk IN, and Bulk OUT
         * endpoints, or `null` otherwise.
         *
         * @param device the [UsbDevice] to inspect.
         * @return the discovered [UsbHciEndpoints], or `null` if [device] is not a supported USB
         *   Bluetooth HCI adapter.
         */
        fun findBluetoothHciInterface(device: UsbDevice): UsbHciEndpoints? {
            val deviceIsBtHci = isBtHciClassTriplet(
                usbClass = device.deviceClass,
                usbSubclass = device.deviceSubclass,
                usbProtocol = device.deviceProtocol,
            )
            var fallbackCandidate: UsbHciEndpoints? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val ifaceMatches = isBtHciClassTriplet(
                    usbClass = iface.interfaceClass,
                    usbSubclass = iface.interfaceSubclass,
                    usbProtocol = iface.interfaceProtocol,
                ) || (deviceIsBtHci && iface.id == 0)
                if (!ifaceMatches) continue

                val endpoints = extractEndpoints(iface) ?: continue
                if (iface.alternateSetting == 0) {
                    return endpoints
                }
                if (fallbackCandidate == null) {
                    fallbackCandidate = endpoints
                }
            }
            return fallbackCandidate
        }

        /**
         * Returns `true` if [device] exposes a supported Bluetooth HCI USB interface.
         *
         * @param device the [UsbDevice] to inspect.
         * @return `true` if [findBluetoothHciInterface] finds valid HCI endpoints on [device].
         */
        fun isBluetoothHciDevice(device: UsbDevice): Boolean =
            findBluetoothHciInterface(device) != null

        private fun isBtHciClassTriplet(usbClass: Int, usbSubclass: Int, usbProtocol: Int): Boolean =
            (usbClass == UsbConstants.USB_CLASS_WIRELESS_CONTROLLER ||
                usbClass == UsbConstants.USB_CLASS_VENDOR_SPEC) &&
                usbSubclass == BT_HCI_SUBCLASS &&
                usbProtocol == BT_HCI_PROTOCOL

        private fun extractEndpoints(iface: UsbInterface): UsbHciEndpoints? {
            var interruptIn: UsbEndpoint? = null
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (epIdx in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(epIdx)
                when {
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.direction == UsbConstants.USB_DIR_IN -> {
                        if (interruptIn == null) interruptIn = ep
                    }
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN -> {
                        if (bulkIn == null) bulkIn = ep
                    }
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT -> {
                        if (bulkOut == null) bulkOut = ep
                    }
                }
            }
            if (interruptIn == null || bulkIn == null || bulkOut == null) {
                return null
            }
            return UsbHciEndpoints(
                usbInterface = iface,
                interruptIn = interruptIn,
                bulkIn = bulkIn,
                bulkOut = bulkOut,
            )
        }
    }

    private val incomingPackets = Channel<ByteArray>(Channel.UNLIMITED)
    private val eventAssembler = UsbHciEventAssembler()
    private val aclAssembler = UsbHciAclAssembler()
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val closeLock = java.lang.Object()

    @Volatile
    private var usbConnection: UsbHciConnection? = null

    @Volatile
    private var opened: Boolean = false

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var controllerResetCompleted: Boolean = false

    private var activeWriteCount: Int = 0
    private var closeCompleted: Boolean = false
    private var ownsDeviceClaim: Boolean = false

    override val requiresControllerResetOnClose: Boolean
        get() = true

    override fun markControllerResetCompleted() {
        controllerResetCompleted = true
    }

    /**
     * Opens the USB device connection, claims the Bluetooth HCI interface, drains any stale FIFO
     * packets, and starts the asynchronous Interrupt IN and Bulk IN reader loop.
     *
     * @throws IOException if the device lacks permission, is already in use, or cannot be opened.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun open() = withContext(Dispatchers.IO) {
        synchronized(closeLock) {
            if (closed) {
                throw IOException("AndroidUsbHciPacketIo is already closed")
            }
            check(!opened) { "AndroidUsbHciPacketIo.open() has already been called" }
            opened = true
        }

        val claimed = synchronized(activeDevicesLock) {
            activeDevices.add(deviceKey)
        }
        if (!claimed) {
            close()
            throw IOException(
                "USB Bluetooth HCI device $deviceKey is already in use by another active BLE session"
            )
        }

        val claimedBeforeClose = synchronized(closeLock) {
            if (closed) {
                false
            } else {
                ownsDeviceClaim = true
                true
            }
        }
        if (!claimedBeforeClose) {
            synchronized(activeDevicesLock) {
                activeDevices.remove(deviceKey)
            }
            throw IOException("AndroidUsbHciPacketIo was closed during open()")
        }

        try {
            val conn = connectionFactory()
            conn.open(
                onEventChunk = { chunk, len, isShort ->
                    val packets = eventAssembler.onChunk(chunk, len, isShort)
                    for (pkt in packets) {
                        incomingPackets.trySend(pkt)
                    }
                },
                onAclChunk = { chunk, len, isShort ->
                    val packets = aclAssembler.onChunk(chunk, len, isShort)
                    for (pkt in packets) {
                        incomingPackets.trySend(pkt)
                    }
                },
                onReaderError = { err ->
                    incomingPackets.close(err)
                },
            )
            val keepOpen = synchronized(closeLock) {
                if (closed) {
                    false
                } else {
                    usbConnection = conn
                    true
                }
            }
            if (!keepOpen) {
                conn.close()
                throw IOException("AndroidUsbHciPacketIo was closed during open()")
            }
            Logger.i(TAG, "Opened USB Bluetooth HCI device $deviceKey (iface=${conn.interfaceId})")
        } catch (e: Exception) {
            close()
            if (e is CancellationException) throw e
            throw e as? IOException ?: IOException("Failed opening USB Bluetooth HCI device $deviceKey", e)
        }
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun readPacket(): ByteArray? = readMutex.withLock {
        if (closed) return null
        if (!opened) {
            throw IOException("USB Bluetooth HCI transport on $deviceKey is not open")
        }
        val result = incomingPackets.receiveCatching()
        if (result.isSuccess) {
            return result.getOrNull()
        }
        val cause = result.exceptionOrNull()
        if (closed || cause == null) {
            return null
        }
        if (cause is CancellationException) throw cause
        throw cause as? IOException ?: IOException("USB Bluetooth HCI read failed on $deviceKey", cause)
    }

    @Throws(IOException::class, CancellationException::class)
    override suspend fun writePacket(packet: ByteArray): Unit = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val conn = synchronized(closeLock) {
                if (closed) {
                    throw IOException("USB Bluetooth HCI transport on $deviceKey is closed")
                }
                val activeConn = usbConnection
                    ?: throw IOException("USB Bluetooth HCI transport on $deviceKey is not open")
                activeWriteCount++
                activeConn
            }
            try {
                if (packet.isEmpty()) {
                    throw IOException("Cannot write empty H4 packet")
                }
                val timeoutMs = transferTimeout.inWholeMilliseconds
                    .coerceIn(1L, Int.MAX_VALUE.toLong())
                    .toInt()
                when (packet[0]) {
                    BleHciConstants.HCI_COMMAND_PKT -> {
                        val payload = extractUsbHciCommandPayload(packet)
                        conn.sendCommand(payload, timeoutMs)
                    }
                    BleHciConstants.HCI_ACL_DATA_PKT -> {
                        val payload = extractUsbHciAclPayload(packet)
                        conn.sendAcl(payload, timeoutMs)
                    }
                    else -> {
                        throw IOException(
                            "Unsupported outbound H4 packet type 0x${(packet[0].toInt() and 0xFF).toString(16)}"
                        )
                    }
                }
            } finally {
                releaseActiveWrite()
            }
        }
    }

    private fun releaseActiveWrite() {
        synchronized(closeLock) {
            activeWriteCount--
            if (activeWriteCount == 0) {
                closeLock.notifyAll()
            }
        }
    }

    private fun awaitFallbackResetComplete(conn: UsbHciConnection) {
        val assembler = UsbHciEventAssembler()
        val buf = ByteArray(conn.interruptMaxPacketSize)
        val deadlineNanos = System.nanoTime() + FALLBACK_RESET_DRAIN_MS * 1_000_000L
        while (true) {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMs <= 0) break
            val n = conn.drainInterruptIn(buf, remainingMs)
            if (n < 0) break
            val events = assembler.onChunk(buf, n, n < buf.size)
            val sawResetComplete = events.any { pkt ->
                pkt.size >= 6 &&
                    pkt[0] == BleHciConstants.HCI_EVENT_PKT &&
                    pkt[1] == 0x0E.toByte() &&
                    pkt[4] == 0x03.toByte() &&
                    pkt[5] == 0x0C.toByte()
            }
            if (sawResetComplete) break
        }
    }

    override fun close() {
        var connToClose: UsbHciConnection? = null
        var releaseClaim = false
        var interrupted = false
        synchronized(closeLock) {
            if (closed) {
                while (!closeCompleted) {
                    try {
                        closeLock.wait()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
                return
            }
            closed = true
            incomingPackets.close()
            while (activeWriteCount > 0) {
                try {
                    closeLock.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            connToClose = usbConnection
            usbConnection = null
            releaseClaim = ownsDeviceClaim
            ownsDeviceClaim = false
        }

        try {
            connToClose?.let { conn ->
                conn.stopReader()
                if (!controllerResetCompleted) {
                    try {
                        conn.sendCommand(HCI_RESET_PAYLOAD, FALLBACK_RESET_TIMEOUT_MS)
                        awaitFallbackResetComplete(conn)
                    } catch (_: Exception) {
                    }
                }
                conn.close()
            }
        } finally {
            if (releaseClaim) {
                synchronized(activeDevicesLock) {
                    activeDevices.remove(deviceKey)
                }
            }
            synchronized(closeLock) {
                closeCompleted = true
                closeLock.notifyAll()
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
