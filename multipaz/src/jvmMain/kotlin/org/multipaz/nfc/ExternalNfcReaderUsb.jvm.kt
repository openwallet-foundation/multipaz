package org.multipaz.nfc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.multipaz.util.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "ExternalNfcReaderUsbJvm"

private const val LIBUSB_SUCCESS = 0
private const val LIBUSB_ERROR_ACCESS = -3
private const val LIBUSB_ERROR_TIMEOUT = -7

private const val USB_REQ_GET_DESCRIPTOR: Byte = 0x06
private const val USB_ENDPOINT_IN: Byte = 0x80.toByte()
private const val USB_DT_CONFIG = 0x02

internal interface LibUsb : Library {
    fun libusb_init(ctx: PointerByReference): Int
    fun libusb_exit(ctx: Pointer)
    fun libusb_get_device_list(ctx: Pointer, list: PointerByReference): NativeLong
    fun libusb_free_device_list(list: Pointer, unrefDevices: Int)
    fun libusb_get_device_descriptor(dev: Pointer, desc: ByteArray): Int
    fun libusb_get_bus_number(dev: Pointer): Byte
    fun libusb_get_device_address(dev: Pointer): Byte
    fun libusb_open(dev: Pointer, devHandle: PointerByReference): Int
    fun libusb_close(devHandle: Pointer)
    fun libusb_set_auto_detach_kernel_driver(devHandle: Pointer, enable: Int): Int
    fun libusb_claim_interface(devHandle: Pointer, interfaceNumber: Int): Int
    fun libusb_release_interface(devHandle: Pointer, interfaceNumber: Int): Int
    fun libusb_get_string_descriptor_ascii(
        devHandle: Pointer,
        descIndex: Byte,
        data: ByteArray,
        length: Int
    ): Int
    fun libusb_control_transfer(
        devHandle: Pointer,
        bmRequestType: Byte,
        bRequest: Byte,
        wValue: Short,
        wIndex: Short,
        data: ByteArray,
        wLength: Short,
        timeout: Int
    ): Int
    fun libusb_bulk_transfer(
        devHandle: Pointer,
        endpoint: Byte,
        data: ByteArray,
        length: Int,
        actualLength: IntByReference,
        timeout: Int
    ): Int
    fun libusb_interrupt_transfer(
        devHandle: Pointer,
        endpoint: Byte,
        data: ByteArray,
        length: Int,
        actualLength: IntByReference,
        timeout: Int
    ): Int
    fun libusb_strerror(errcode: Int): String?
}

internal object LibUsbHolder {
    private var loadAttempted = false
    private var instance: LibUsb? = null

    @Synchronized
    fun getOrNull(): LibUsb? {
        if (!loadAttempted) {
            loadAttempted = true
            instance = try {
                Native.load("usb-1.0", LibUsb::class.java)
            } catch (t: Throwable) {
                Logger.w(TAG, "libusb-1.0 could not be loaded via JNA: ${t.message}")
                null
            }
        }
        return instance
    }

    fun getOrThrow(): LibUsb {
        return getOrNull() ?: throw IOException(
            "libusb-1.0 native library (libusb-1.0.so.0 / libusb-1.0.dylib / libusb-1.0.dll) " +
                "and JNA (net.java.dev.jna:jna) are required for USB CCID NFC readers on JVM."
        )
    }

    fun formatError(libusb: LibUsb, err: Int): String {
        val msg = try {
            libusb.libusb_strerror(err)
        } catch (_: Throwable) {
            null
        }
        return if (!msg.isNullOrBlank()) "$msg ($err)" else "libusb error $err"
    }
}

internal data class JvmUsbDeviceSnapshot(
    val busNumber: Int,
    val deviceAddress: Int,
    val deviceNodePath: String?,
    val hasPermission: Boolean,
    val deviceInfo: UsbDeviceInfo,
)

private val permissionRefreshTick = MutableStateFlow(0L)

internal object JvmUsbEnumerator {
    /**
     * Enumerates attached USB devices on the host system, combining Linux `sysfs` metadata
     * (which is world-readable even before `/dev/bus/usb/BBB/DDD` write permission is granted)
     * with `libusb-1.0` enumeration.
     */
    fun enumerateDevices(): List<JvmUsbDeviceSnapshot> {
        val sysfsDevices = enumerateLinuxSysfsDevices()
        if (sysfsDevices.isNotEmpty()) {
            return sysfsDevices
        }
        return enumerateLibUsbDevices()
    }

    private fun enumerateLinuxSysfsDevices(): List<JvmUsbDeviceSnapshot> {
        val sysfsRoot = File("/sys/bus/usb/devices")
        if (!sysfsRoot.isDirectory) return emptyList()
        val entries = sysfsRoot.listFiles() ?: return emptyList()
        val results = mutableListOf<JvmUsbDeviceSnapshot>()

        for (dir in entries) {
            // Skip interface subdirectories (which contain ':')
            if (dir.name.contains(':')) continue
            val vidHex = readSysfsFile(dir, "idVendor") ?: continue
            val pidHex = readSysfsFile(dir, "idProduct") ?: continue
            val busNum = readSysfsFile(dir, "busnum")?.toIntOrNull() ?: continue
            val devNum = readSysfsFile(dir, "devnum")?.toIntOrNull() ?: continue
            val vendorId = vidHex.toIntOrNull(16) ?: continue
            val productId = pidHex.toIntOrNull(16) ?: continue

            val manufacturer = readSysfsFile(dir, "manufacturer")
            val product = readSysfsFile(dir, "product")
            val descriptorsFile = File(dir, "descriptors")
            val rawDescriptors = try {
                if (descriptorsFile.isFile) descriptorsFile.readBytes() else null
            } catch (_: Exception) {
                null
            }

            val bConfigVal = readSysfsFile(dir, "bConfigurationValue")?.toIntOrNull() ?: 1
            val interfaces = if (rawDescriptors != null) {
                CcidUsbTransport.parseUsbInterfaces(rawDescriptors) { ifaceId, _ ->
                    val ifaceDir = File(dir, "${dir.name}:$bConfigVal.$ifaceId")
                    readSysfsFile(ifaceDir, "interface")
                }
            } else {
                emptyList()
            }

            val devNodePath = String.format("/dev/bus/usb/%03d/%03d", busNum, devNum)
            val devNodeFile = File(devNodePath)
            val hasPermission = try {
                devNodeFile.exists() &&
                    Files.isReadable(devNodeFile.toPath()) &&
                    Files.isWritable(devNodeFile.toPath())
            } catch (_: Exception) {
                false
            }

            results.add(
                JvmUsbDeviceSnapshot(
                    busNumber = busNum,
                    deviceAddress = devNum,
                    deviceNodePath = if (devNodeFile.exists()) devNodePath else null,
                    hasPermission = hasPermission,
                    deviceInfo = UsbDeviceInfo(
                        vendorId = vendorId,
                        productId = productId,
                        manufacturerName = manufacturer,
                        productName = product,
                        interfaces = interfaces,
                        rawDescriptors = rawDescriptors
                    )
                )
            )
        }
        return results
    }

    private fun readSysfsFile(dir: File, name: String): String? {
        val file = File(dir, name)
        if (!file.isFile) return null
        return try {
            file.readText().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun enumerateLibUsbDevices(): List<JvmUsbDeviceSnapshot> {
        val libusb = LibUsbHolder.getOrNull() ?: return emptyList()
        val ctxRef = PointerByReference()
        if (libusb.libusb_init(ctxRef) != LIBUSB_SUCCESS) {
            return emptyList()
        }
        val ctx = ctxRef.value ?: return emptyList()
        val listRef = PointerByReference()
        val count = libusb.libusb_get_device_list(ctx, listRef).toLong()
        if (count < 0) {
            libusb.libusb_exit(ctx)
            return emptyList()
        }
        val listPtr = listRef.value
        val results = mutableListOf<JvmUsbDeviceSnapshot>()
        try {
            val devices = listPtr.getPointerArray(0, count.toInt())
            val devDesc = ByteArray(18)
            for (dev in devices) {
                if (dev == null) continue
                if (libusb.libusb_get_device_descriptor(dev, devDesc) != LIBUSB_SUCCESS) continue
                val vendorId = (devDesc[8].toInt() and 0xFF) or ((devDesc[9].toInt() and 0xFF) shl 8)
                val productId = (devDesc[10].toInt() and 0xFF) or ((devDesc[11].toInt() and 0xFF) shl 8)
                val iManufacturer = devDesc[14]
                val iProduct = devDesc[15]
                val busNum = libusb.libusb_get_bus_number(dev).toInt() and 0xFF
                val devAddr = libusb.libusb_get_device_address(dev).toInt() and 0xFF

                val handleRef = PointerByReference()
                val openRes = libusb.libusb_open(dev, handleRef)
                val handle = if (openRes == LIBUSB_SUCCESS) handleRef.value else null
                var manufacturer: String? = null
                var product: String? = null
                var rawDescriptors: ByteArray? = null
                var interfaces: List<UsbInterfaceInfo> = emptyList()
                try {
                    if (handle != null) {
                        manufacturer = readUsbString(libusb, handle, iManufacturer)
                        product = readUsbString(libusb, handle, iProduct)
                        val configBytes = readActiveConfigurationDescriptor(libusb, handle)
                        if (configBytes != null) {
                            rawDescriptors = devDesc + configBytes
                            interfaces = CcidUsbTransport.parseUsbInterfaces(rawDescriptors) { _, iIface ->
                                readUsbString(libusb, handle, iIface.toByte())
                            }
                        }
                    }
                } finally {
                    if (handle != null) {
                        libusb.libusb_close(handle)
                    }
                }

                results.add(
                    JvmUsbDeviceSnapshot(
                        busNumber = busNum,
                        deviceAddress = devAddr,
                        deviceNodePath = null,
                        hasPermission = (openRes == LIBUSB_SUCCESS),
                        deviceInfo = UsbDeviceInfo(
                            vendorId = vendorId,
                            productId = productId,
                            manufacturerName = manufacturer,
                            productName = product,
                            interfaces = interfaces,
                            rawDescriptors = rawDescriptors
                        )
                    )
                )
            }
        } finally {
            if (listPtr != null) {
                libusb.libusb_free_device_list(listPtr, 1)
            }
            libusb.libusb_exit(ctx)
        }
        return results
    }

    internal fun readUsbString(libusb: LibUsb, handle: Pointer, index: Byte): String? {
        if ((index.toInt() and 0xFF) == 0) return null
        val buf = ByteArray(256)
        val len = libusb.libusb_get_string_descriptor_ascii(handle, index, buf, buf.size)
        if (len <= 0) return null
        return buf.decodeToString(0, len).trim().takeIf { it.isNotEmpty() }
    }

    internal fun readActiveConfigurationDescriptor(libusb: LibUsb, handle: Pointer): ByteArray? {
        val header = ByteArray(9)
        val wValue = ((USB_DT_CONFIG shl 8) or 0).toShort()
        val nHeader = libusb.libusb_control_transfer(
            handle,
            USB_ENDPOINT_IN,
            USB_REQ_GET_DESCRIPTOR,
            wValue,
            0,
            header,
            header.size.toShort(),
            1000
        )
        if (nHeader < 4) return null
        val totalLength = ((header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8))
            .coerceIn(9, 4096)
        val full = ByteArray(totalLength)
        val nFull = libusb.libusb_control_transfer(
            handle,
            USB_ENDPOINT_IN,
            USB_REQ_GET_DESCRIPTOR,
            wValue,
            0,
            full,
            totalLength.toShort(),
            1000
        )
        if (nFull < 9) return null
        return full.copyOfRange(0, nFull)
    }
}

internal class JvmCcidUsbTransport(
    override val vendorId: Int,
    override val productId: Int,
    private val requestedInterfaceIndex: Int,
) : CcidUsbTransport {
    private val lock = Any()
    private var ctx: Pointer? = null
    private var devHandle: Pointer? = null
    private var claimedInterfaceId: Int? = null
    private var resolvedEndpoints: CcidInterfaceEndpoints? = null
    private var cachedRawDescriptors: ByteArray? = null
    private var resolvedDeviceName: String = String.format("USB %04X:%04X", vendorId, productId)

    override val deviceName: String
        get() = resolvedDeviceName

    override val interfaceId: Int
        get() = resolvedEndpoints?.interfaceId ?: requestedInterfaceIndex

    override val rawDescriptors: ByteArray?
        get() = cachedRawDescriptors

    override val hasInterruptEndpoint: Boolean
        get() = resolvedEndpoints?.interruptInAddress != null

    override val interruptMaxPacketSize: Int
        get() = resolvedEndpoints?.interruptMaxPacketSize ?: 0

    override fun open() {
        synchronized(lock) {
            val libusb = LibUsbHolder.getOrThrow()
            val ctxRef = PointerByReference()
            val initErr = libusb.libusb_init(ctxRef)
            if (initErr != LIBUSB_SUCCESS) {
                throw IOException("libusb_init failed: ${LibUsbHolder.formatError(libusb, initErr)}")
            }
            val newCtx = ctxRef.value ?: throw IOException("libusb_init returned null context")
            ctx = newCtx

            val listRef = PointerByReference()
            val count = libusb.libusb_get_device_list(newCtx, listRef).toLong()
            if (count < 0) {
                close()
                throw IOException("libusb_get_device_list failed: ${LibUsbHolder.formatError(libusb, count.toInt())}")
            }
            val listPtr = listRef.value
            var openedHandle: Pointer? = null
            var devDescBytes: ByteArray? = null
            var busNum = 0
            var devAddr = 0

            try {
                val devices = listPtr.getPointerArray(0, count.toInt())
                val desc = ByteArray(18)
                for (dev in devices) {
                    if (dev == null) continue
                    if (libusb.libusb_get_device_descriptor(dev, desc) != LIBUSB_SUCCESS) continue
                    val vid = (desc[8].toInt() and 0xFF) or ((desc[9].toInt() and 0xFF) shl 8)
                    val pid = (desc[10].toInt() and 0xFF) or ((desc[11].toInt() and 0xFF) shl 8)
                    if (vid == vendorId && pid == productId) {
                        busNum = libusb.libusb_get_bus_number(dev).toInt() and 0xFF
                        devAddr = libusb.libusb_get_device_address(dev).toInt() and 0xFF
                        val handleRef = PointerByReference()
                        val openErr = libusb.libusb_open(dev, handleRef)
                        if (openErr == LIBUSB_ERROR_ACCESS) {
                            throw SecurityException(
                                String.format(
                                    "Permission denied for USB device %04X:%04X (/dev/bus/usb/%03d/%03d)",
                                    vendorId,
                                    productId,
                                    busNum,
                                    devAddr
                                )
                            )
                        }
                        if (openErr != LIBUSB_SUCCESS || handleRef.value == null) {
                            throw IOException(
                                "Could not open USB device: ${LibUsbHolder.formatError(libusb, openErr)}"
                            )
                        }
                        openedHandle = handleRef.value
                        devDescBytes = desc.copyOf()
                        break
                    }
                }
            } finally {
                if (listPtr != null) {
                    libusb.libusb_free_device_list(listPtr, 1)
                }
            }

            val handle = openedHandle ?: run {
                close()
                throw IOException(String.format("USB CCID device %04X:%04X not found", vendorId, productId))
            }
            devHandle = handle
            resolvedDeviceName = String.format("/dev/bus/usb/%03d/%03d", busNum, devAddr)

            val sysfsSnapshot = JvmUsbEnumerator.enumerateDevices().firstOrNull {
                it.deviceInfo.vendorId == vendorId && it.deviceInfo.productId == productId
            }
            val rawDesc = sysfsSnapshot?.deviceInfo?.rawDescriptors ?: run {
                val cfg = JvmUsbEnumerator.readActiveConfigurationDescriptor(libusb, handle)
                if (cfg != null && devDescBytes != null) devDescBytes + cfg else cfg
            }
            cachedRawDescriptors = rawDesc

            val endpoints = if (rawDesc != null) {
                CcidUsbTransport.findCcidInterfaceEndpoints(rawDesc, requestedInterfaceIndex)
            } else {
                null
            } ?: run {
                close()
                throw IOException("CCID interface or endpoints not found on $resolvedDeviceName")
            }
            resolvedEndpoints = endpoints

            try {
                libusb.libusb_set_auto_detach_kernel_driver(handle, 1)
            } catch (_: Throwable) {
            }

            val claimErr = libusb.libusb_claim_interface(handle, endpoints.interfaceId)
            if (claimErr != LIBUSB_SUCCESS) {
                close()
                throw IOException(
                    "Could not claim CCID interface id=${endpoints.interfaceId} on $resolvedDeviceName: " +
                        "${LibUsbHolder.formatError(libusb, claimErr)} (ensure pcscd is not holding the device exclusively)"
                )
            }
            claimedInterfaceId = endpoints.interfaceId
        }
    }

    override fun close() {
        synchronized(lock) {
            val libusb = LibUsbHolder.getOrNull()
            val handle = devHandle
            val ifaceId = claimedInterfaceId
            val currentCtx = ctx
            claimedInterfaceId = null
            devHandle = null
            ctx = null
            resolvedEndpoints = null

            if (libusb != null && handle != null && ifaceId != null) {
                try {
                    libusb.libusb_release_interface(handle, ifaceId)
                } catch (e: Throwable) {
                    Logger.w(TAG, "Error releasing USB interface $ifaceId", e)
                }
            }
            if (libusb != null && handle != null) {
                try {
                    libusb.libusb_close(handle)
                } catch (e: Throwable) {
                    Logger.w(TAG, "Error closing USB device handle", e)
                }
            }
            if (libusb != null && currentCtx != null) {
                try {
                    libusb.libusb_exit(currentCtx)
                } catch (e: Throwable) {
                    Logger.w(TAG, "Error exiting libusb context", e)
                }
            }
        }
    }

    override fun bulkTransferOut(data: ByteArray, timeoutMs: Int): Int {
        val libusb = LibUsbHolder.getOrThrow()
        val handle = devHandle ?: throw IOException("USB device is not open")
        val ep = resolvedEndpoints?.bulkOutAddress ?: throw IOException("Bulk-OUT endpoint not resolved")
        val transferred = IntByReference()
        val rc = libusb.libusb_bulk_transfer(handle, ep, data, data.size, transferred, timeoutMs)
        if (rc != LIBUSB_SUCCESS) {
            throw IOException("USB bulkTransferOut failed: ${LibUsbHolder.formatError(libusb, rc)}")
        }
        return transferred.value
    }

    override fun bulkTransferIn(buffer: ByteArray, timeoutMs: Int): Int {
        val libusb = LibUsbHolder.getOrThrow()
        val handle = devHandle ?: throw IOException("USB device is not open")
        val ep = resolvedEndpoints?.bulkInAddress ?: throw IOException("Bulk-IN endpoint not resolved")
        val transferred = IntByReference()
        val rc = libusb.libusb_bulk_transfer(handle, ep, buffer, buffer.size, transferred, timeoutMs)
        if (rc != LIBUSB_SUCCESS) {
            throw IOException("USB bulkTransferIn failed: ${LibUsbHolder.formatError(libusb, rc)}")
        }
        return transferred.value
    }

    override fun interruptTransferIn(buffer: ByteArray, timeoutMs: Int): Int {
        val libusb = LibUsbHolder.getOrNull() ?: return -1
        val handle = devHandle ?: return -1
        val ep = resolvedEndpoints?.interruptInAddress ?: return -1
        val transferred = IntByReference()
        val rc = libusb.libusb_interrupt_transfer(handle, ep, buffer, buffer.size, transferred, timeoutMs)
        return when (rc) {
            LIBUSB_SUCCESS -> transferred.value
            LIBUSB_ERROR_TIMEOUT -> 0
            else -> -1
        }
    }
}

internal actual fun ExternalNfcReaderUsb.observeUsbState(): Flow<ExternalNfcReaderState> = flow {
    while (true) {
        val snapshot = withContext(Dispatchers.IO) {
            JvmUsbEnumerator.enumerateDevices().firstOrNull {
                it.deviceInfo.vendorId == vendorId && it.deviceInfo.productId == productId
            }
        }
        val state = when {
            snapshot == null -> ExternalNfcReaderState.NOT_CONNECTED
            snapshot.hasPermission -> ExternalNfcReaderState.CONNECTED
            else -> ExternalNfcReaderState.CONNECTED_NO_PERMISSION
        }
        emit(state)
        delay(500.milliseconds)
    }
}.distinctUntilChanged()

internal actual suspend fun ExternalNfcReaderUsb.requestUsbPermission(): Boolean = withContext(Dispatchers.IO) {
    val snapshot = JvmUsbEnumerator.enumerateDevices().firstOrNull {
        it.deviceInfo.vendorId == vendorId && it.deviceInfo.productId == productId
    } ?: throw IllegalStateException("Device is not connected")

    if (snapshot.hasPermission) {
        return@withContext true
    }

    val devNodePath = snapshot.deviceNodePath ?: return@withContext false
    val user = System.getProperty("user.name")?.trim().orEmpty()

    val commands = buildList {
        if (user.isNotEmpty() && File("/usr/bin/pkexec").canExecute() && File("/usr/bin/setfacl").canExecute()) {
            add(listOf("/usr/bin/pkexec", "/usr/bin/setfacl", "-m", "u:$user:rw", devNodePath))
        }
        if (File("/usr/bin/pkexec").canExecute()) {
            add(listOf("/usr/bin/pkexec", "/bin/chmod", "0666", devNodePath))
        }
    }

    for (cmd in commands) {
        try {
            Logger.i(TAG, "Requesting USB device permission via: ${cmd.joinToString(" ")}")
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                break
            } else {
                val output = proc.inputStream.bufferedReader().readText().trim()
                Logger.w(TAG, "Permission helper exited with $exitCode: $output")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Failed to execute permission helper ${cmd.first()}", e)
        }
    }

    val updated = JvmUsbEnumerator.enumerateDevices().firstOrNull {
        it.deviceInfo.vendorId == vendorId && it.deviceInfo.productId == productId
    }
    permissionRefreshTick.value++
    updated?.hasPermission == true
}

private data class JvmUsbReaderKey(
    val vendorId: Int,
    val productId: Int,
    val interfaceIndex: Int,
)

private data class JvmDeviceIdentity(
    val busNumber: Int,
    val deviceAddress: Int,
)

private val jvmUsbReadersLock = Any()
private val jvmUsbReaders = mutableMapOf<JvmUsbReaderKey, NfcTagReaderUsb>()

internal actual suspend fun ExternalNfcReaderUsb.getUsbNfcTagReader(): NfcTagReader {
    val snapshot = withContext(Dispatchers.IO) {
        JvmUsbEnumerator.enumerateDevices().firstOrNull {
            it.deviceInfo.vendorId == vendorId && it.deviceInfo.productId == productId
        }
    } ?: throw IllegalStateException("Device is not connected")

    val key = JvmUsbReaderKey(vendorId, productId, interfaceIndex)
    val identity = JvmDeviceIdentity(snapshot.busNumber, snapshot.deviceAddress)
    var staleReader: NfcTagReaderUsb? = null
    val reader = synchronized(jvmUsbReadersLock) {
        val existing = jvmUsbReaders[key]
        if (existing != null &&
            existing.deviceIdentity == identity &&
            existing.interfaceIndex == interfaceIndex
        ) {
            existing
        } else {
            staleReader = existing
            NfcTagReaderUsb(
                deviceIdentity = identity,
                interfaceIndex = interfaceIndex,
                createDriver = { pollingFrameData ->
                    CcidDriver(
                        transport = JvmCcidUsbTransport(
                            vendorId = vendorId,
                            productId = productId,
                            requestedInterfaceIndex = interfaceIndex
                        ),
                        pollingFrameData = pollingFrameData
                    )
                }
            ).also { jvmUsbReaders[key] = it }
        }
    }
    staleReader?.close()
    return reader
}
