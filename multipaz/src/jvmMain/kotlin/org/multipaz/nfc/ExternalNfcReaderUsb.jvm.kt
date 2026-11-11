package org.multipaz.nfc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.multipaz.util.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ExternalNfcReaderUsbJvm"

private const val LIBUSB_SUCCESS = 0
private const val LIBUSB_ERROR_ACCESS = -3
private const val LIBUSB_ERROR_TIMEOUT = -7

private const val USB_REQ_GET_DESCRIPTOR: Byte = 0x06
private const val USB_ENDPOINT_IN: Byte = 0x80.toByte()
private const val USB_DT_CONFIG = 0x02

private val POSIX_USERNAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")
private val USB_DEV_NODE_REGEX = Regex("^/dev/bus/usb/\\d{3}/\\d{3}$")
private val SYSFS_PORT_NAME_REGEX = Regex("^[0-9]+-[0-9]+(?:\\.[0-9]+)*$")

internal actual val nfcIoDispatcher: CoroutineDispatcher = Dispatchers.IO

internal interface LibUsb : Library {
    fun libusb_init(ctx: PointerByReference): Int
    fun libusb_exit(ctx: Pointer)
    fun libusb_get_device_list(ctx: Pointer, list: PointerByReference): NativeLong
    fun libusb_free_device_list(list: Pointer, unrefDevices: Int)
    fun libusb_get_device(devHandle: Pointer): Pointer?
    fun libusb_get_device_descriptor(dev: Pointer, desc: ByteArray): Int
    fun libusb_get_bus_number(dev: Pointer): Byte
    fun libusb_get_device_address(dev: Pointer): Byte
    fun libusb_open(dev: Pointer, devHandle: PointerByReference): Int
    fun libusb_wrap_sys_device(ctx: Pointer, sysDev: Pointer, devHandle: PointerByReference): Int
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

    private fun isJnaAvailable(): Boolean = try {
        Class.forName("com.sun.jna.Native")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private fun loadNativeLibUsb(): LibUsb? {
        return try {
            Native.load("usb-1.0", LibUsb::class.java)
        } catch (e: UnsatisfiedLinkError) {
            Logger.w(TAG, "libusb-1.0 could not be loaded via JNA: ${e.message}")
            null
        } catch (e: SecurityException) {
            Logger.w(TAG, "SecurityException loading libusb-1.0 via JNA: ${e.message}")
            null
        }
    }

    @Synchronized
    fun getOrNull(): LibUsb? {
        if (!loadAttempted) {
            loadAttempted = true
            instance = if (isJnaAvailable()) {
                loadNativeLibUsb()
            } else {
                Logger.w(TAG, "JNA (net.java.dev.jna:jna) is not on the runtime classpath")
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
        } catch (_: Exception) {
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
    private data class CachedLibUsbKey(
        val busNumber: Int,
        val deviceAddress: Int,
        val vendorId: Int,
        val productId: Int,
    )

    private data class CachedLibUsbMetadata(
        val manufacturerName: String?,
        val productName: String?,
        val interfaces: List<UsbInterfaceInfo>,
        val rawDescriptors: ByteArray?,
    )

    private val cacheLock = Any()
    private val cachedLibUsbMetadata = mutableMapOf<CachedLibUsbKey, CachedLibUsbMetadata>()

    /**
     * Enumerates attached USB devices on the host system, preferring Linux `sysfs` metadata
     * (which is world-readable even before `/dev/bus/usb/BBB/DDD` write permission is granted)
     * and falling back to `libusb-1.0` enumeration on non-Linux hosts.
     */
    fun enumerateDevices(
        filterVendorId: Int? = null,
        filterProductId: Int? = null,
    ): List<JvmUsbDeviceSnapshot> {
        val sysfsRoot = File("/sys/bus/usb/devices")
        if (sysfsRoot.isDirectory) {
            val entries = sysfsRoot.listFiles()
            if (entries != null) {
                return enumerateLinuxSysfsDevices(entries, filterVendorId, filterProductId)
            }
        }
        return enumerateLibUsbDevices(filterVendorId, filterProductId)
    }

    private fun enumerateLinuxSysfsDevices(
        entries: Array<File>,
        filterVendorId: Int?,
        filterProductId: Int?,
    ): List<JvmUsbDeviceSnapshot> {
        val results = mutableListOf<JvmUsbDeviceSnapshot>()

        for (dir in entries) {
            // Skip interface subdirectories (which contain ':')
            if (dir.name.contains(':')) continue
            val vidHex = readSysfsFile(dir, "idVendor") ?: continue
            val pidHex = readSysfsFile(dir, "idProduct") ?: continue
            val vendorId = vidHex.toIntOrNull(16) ?: continue
            val productId = pidHex.toIntOrNull(16) ?: continue
            if (filterVendorId != null && vendorId != filterVendorId) continue
            if (filterProductId != null && productId != filterProductId) continue

            val busNum = readSysfsFile(dir, "busnum")?.toIntOrNull() ?: continue
            val devNum = readSysfsFile(dir, "devnum")?.toIntOrNull() ?: continue

            val manufacturer = readSysfsFile(dir, "manufacturer")
            val product = readSysfsFile(dir, "product")
            val descriptorsFile = File(dir, "descriptors")
            val fullDescriptors = try {
                if (descriptorsFile.isFile) descriptorsFile.readBytes() else null
            } catch (_: Exception) {
                null
            }

            val bConfigVal = readSysfsFile(dir, "bConfigurationValue")?.toIntOrNull()
            val activeRawDescriptors = if (fullDescriptors != null) {
                CcidUsbTransport.extractActiveConfigSlice(fullDescriptors, bConfigVal)
            } else {
                null
            }
            val interfaces = if (activeRawDescriptors != null) {
                val cfgNum = bConfigVal ?: 1
                CcidUsbTransport.parseUsbInterfaces(activeRawDescriptors, bConfigVal) { ifaceId, _ ->
                    val ifaceDir = File(dir, "${dir.name}:$cfgNum.$ifaceId")
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
                        rawDescriptors = activeRawDescriptors
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

    private fun enumerateLibUsbDevices(
        filterVendorId: Int?,
        filterProductId: Int?,
    ): List<JvmUsbDeviceSnapshot> {
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
        val seenKeys = mutableSetOf<CachedLibUsbKey>()
        try {
            val devices = listPtr.getPointerArray(0, count.toInt())
            val devDesc = ByteArray(18)
            for (dev in devices) {
                if (dev == null) continue
                if (libusb.libusb_get_device_descriptor(dev, devDesc) != LIBUSB_SUCCESS) continue
                val vendorId = (devDesc[8].toInt() and 0xFF) or ((devDesc[9].toInt() and 0xFF) shl 8)
                val productId = (devDesc[10].toInt() and 0xFF) or ((devDesc[11].toInt() and 0xFF) shl 8)
                val busNum = libusb.libusb_get_bus_number(dev).toInt() and 0xFF
                val devAddr = libusb.libusb_get_device_address(dev).toInt() and 0xFF
                val cacheKey = CachedLibUsbKey(busNum, devAddr, vendorId, productId)
                seenKeys.add(cacheKey)

                if (filterVendorId != null && vendorId != filterVendorId) continue
                if (filterProductId != null && productId != filterProductId) continue

                val cached = synchronized(cacheLock) { cachedLibUsbMetadata[cacheKey] }
                val iManufacturer = devDesc[14]
                val iProduct = devDesc[15]

                // If we only need discovery (no VID/PID filter) and already cached a non-CCID device,
                // avoid re-opening the device on every poll tick.
                if (filterVendorId == null && filterProductId == null && cached != null) {
                    val hasCcid = cached.interfaces.any {
                        it.interfaceClass == CcidUsbTransport.USB_CLASS_CSCID
                    }
                    if (!hasCcid) {
                        continue
                    }
                }

                val handleRef = PointerByReference()
                val openRes = libusb.libusb_open(dev, handleRef)
                val handle = if (openRes == LIBUSB_SUCCESS) handleRef.value else null
                var metadata = cached
                try {
                    if (handle != null && metadata == null) {
                        val manufacturer = readUsbString(libusb, handle, iManufacturer)
                        val product = readUsbString(libusb, handle, iProduct)
                        val configBytes = readActiveConfigurationDescriptor(libusb, handle)
                        val rawDescriptors = if (configBytes != null) devDesc + configBytes else null
                        val interfaces = if (configBytes != null) {
                            CcidUsbTransport.parseUsbInterfaces(configBytes) { _, iIface ->
                                readUsbString(libusb, handle, iIface.toByte())
                            }
                        } else {
                            emptyList()
                        }
                        metadata = CachedLibUsbMetadata(
                            manufacturerName = manufacturer,
                            productName = product,
                            interfaces = interfaces,
                            rawDescriptors = rawDescriptors
                        )
                        synchronized(cacheLock) {
                            cachedLibUsbMetadata[cacheKey] = metadata
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
                            manufacturerName = metadata?.manufacturerName,
                            productName = metadata?.productName,
                            interfaces = metadata?.interfaces ?: emptyList(),
                            rawDescriptors = metadata?.rawDescriptors
                        )
                    )
                )
            }
            if (filterVendorId == null && filterProductId == null) {
                synchronized(cacheLock) {
                    cachedLibUsbMetadata.keys.retainAll(seenKeys)
                }
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

internal sealed class JvmUsbDeviceSelector {
    data class ByVidPid(
        val vendorId: Int,
        val productId: Int,
    ) : JvmUsbDeviceSelector()

    data class ByDevicePath(
        val devicePath: String,
    ) : JvmUsbDeviceSelector()

    data class ByFileDescriptor(
        val fileDescriptor: Int,
    ) : JvmUsbDeviceSelector()
}

/**
 * Resolves a Linux USB device node path, `udev` symlink, or `sysfs` USB port path / name into
 * a `(busNumber, deviceAddress)` pair (`1..255` and `1..127`).
 */
internal fun resolveLinuxUsbBusAndAddress(
    devicePath: String,
    sysfsRoot: File = File("/sys/bus/usb/devices"),
    devBusUsbRoot: File = File("/dev/bus/usb"),
): Pair<Int, Int> {
    val trimmed = devicePath.trim()
    if (trimmed.isEmpty()) {
        throw IOException("USB devicePath must not be blank")
    }

    fun readSysfsBusAndAddr(dir: File): Pair<Int, Int> {
        val busNum = File(dir, "busnum").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?: throw IOException("Missing or invalid 'busnum' in sysfs directory: $dir")
        val devAddr = File(dir, "devnum").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            ?: throw IOException("Missing or invalid 'devnum' in sysfs directory: $dir")
        if (busNum !in 1..255 || devAddr !in 1..127) {
            throw IOException("Out-of-range USB bus/device numbers ($busNum:$devAddr) in $dir")
        }
        return busNum to devAddr
    }

    // 1. Bare Linux sysfs USB port name (e.g. "1-1.2" or "2-3")
    if (SYSFS_PORT_NAME_REGEX.matches(trimmed)) {
        val portEntry = File(sysfsRoot, trimmed)
        if (!portEntry.exists()) {
            throw IOException("USB sysfs port '$trimmed' not found under $sysfsRoot")
        }
        val realPortDir = try {
            portEntry.toPath().toRealPath().toFile()
        } catch (e: IOException) {
            throw IOException("Cannot resolve USB sysfs port '$trimmed'", e)
        }
        if (!realPortDir.isDirectory) {
            throw IOException("USB sysfs port '$trimmed' is not a directory: $realPortDir")
        }
        return readSysfsBusAndAddr(realPortDir)
    }

    // 2. Filesystem path (e.g. "/dev/bus/usb/001/004", udev symlink "/dev/nfc-reader0",
    //    or "/sys/bus/usb/devices/1-1.2")
    val path = try {
        Paths.get(trimmed)
    } catch (e: Exception) {
        throw IOException("Invalid USB devicePath '$trimmed'", e)
    }
    if (!Files.exists(path)) {
        throw IOException("USB device path does not exist: '$trimmed'")
    }
    val realPath = try {
        path.toRealPath()
    } catch (e: IOException) {
        throw IOException("Failed to resolve USB device path '$trimmed'", e)
    }

    if (Files.isDirectory(realPath)) {
        return readSysfsBusAndAddr(realPath.toFile())
    }

    val canonicalDevRoot = try {
        if (devBusUsbRoot.exists()) devBusUsbRoot.toPath().toRealPath() else devBusUsbRoot.toPath().toAbsolutePath().normalize()
    } catch (_: IOException) {
        devBusUsbRoot.toPath().toAbsolutePath().normalize()
    }
    if (realPath.startsWith(canonicalDevRoot) && realPath.nameCount == canonicalDevRoot.nameCount + 2) {
        val busStr = realPath.getName(canonicalDevRoot.nameCount).toString()
        val devStr = realPath.getName(canonicalDevRoot.nameCount + 1).toString()
        val busNum = busStr.toIntOrNull(10)
        val devAddr = devStr.toIntOrNull(10)
        if (busNum != null && devAddr != null && busNum in 1..255 && devAddr in 1..127) {
            return busNum to devAddr
        }
    }

    throw IOException(
        "USB device path '$trimmed' (resolved to '$realPath') is neither a valid usbfs node " +
            "under $canonicalDevRoot nor a sysfs USB device directory"
    )
}

internal class JvmCcidUsbTransport(
    private val selector: JvmUsbDeviceSelector,
    private val requestedInterfaceIndex: Int?,
) : CcidUsbTransport {
    constructor(
        vendorId: Int,
        productId: Int,
        requestedInterfaceIndex: Int?,
    ) : this(
        selector = JvmUsbDeviceSelector.ByVidPid(vendorId, productId),
        requestedInterfaceIndex = requestedInterfaceIndex
    )

    private val lock = Object()
    private var ctx: Pointer? = null

    @Volatile
    private var devHandle: Pointer? = null

    private var claimedInterfaceId: Int? = null

    @Volatile
    private var resolvedVendorId: Int = (selector as? JvmUsbDeviceSelector.ByVidPid)?.vendorId ?: 0

    @Volatile
    private var resolvedProductId: Int = (selector as? JvmUsbDeviceSelector.ByVidPid)?.productId ?: 0

    @Volatile
    private var resolvedEndpoints: CcidInterfaceEndpoints? = null

    @Volatile
    private var cachedRawDescriptors: ByteArray? = null

    @Volatile
    private var resolvedDeviceName: String = when (selector) {
        is JvmUsbDeviceSelector.ByVidPid ->
            String.format("USB %04X:%04X", selector.vendorId, selector.productId)
        is JvmUsbDeviceSelector.ByDevicePath -> selector.devicePath
        is JvmUsbDeviceSelector.ByFileDescriptor -> "usb-fd:${selector.fileDescriptor}"
    }

    @Volatile
    private var closing: Boolean = false

    private var inFlightTransfers: Int = 0

    override val vendorId: Int
        get() = resolvedVendorId

    override val productId: Int
        get() = resolvedProductId

    override val deviceName: String
        get() = resolvedDeviceName

    override val interfaceId: Int
        get() = resolvedEndpoints?.interfaceId ?: requestedInterfaceIndex ?: 0

    override val rawDescriptors: ByteArray?
        get() = cachedRawDescriptors

    override val hasInterruptEndpoint: Boolean
        get() = resolvedEndpoints?.interruptInAddress != null

    override val interruptMaxPacketSize: Int
        get() = resolvedEndpoints?.interruptMaxPacketSize ?: 0

    override fun open() {
        synchronized(lock) {
            if (ctx != null || devHandle != null) {
                close()
            }
            closing = false
            val libusb = LibUsbHolder.getOrThrow()
            val ctxRef = PointerByReference()
            val initErr = libusb.libusb_init(ctxRef)
            if (initErr != LIBUSB_SUCCESS) {
                throw IOException("libusb_init failed: ${LibUsbHolder.formatError(libusb, initErr)}")
            }
            val newCtx = ctxRef.value ?: throw IOException("libusb_init returned null context")
            ctx = newCtx

            var success = false
            try {
                var openedHandle: Pointer? = null
                var devDescBytes: ByteArray? = null
                var busNum = 0
                var devAddr = 0

                when (val sel = selector) {
                    is JvmUsbDeviceSelector.ByFileDescriptor -> {
                        val handleRef = PointerByReference()
                        val wrapErr = try {
                            libusb.libusb_wrap_sys_device(
                                newCtx,
                                Pointer.createConstant(sel.fileDescriptor.toLong()),
                                handleRef
                            )
                        } catch (e: UnsatisfiedLinkError) {
                            throw IOException(
                                "libusb_wrap_sys_device is not available (requires libusb >= 1.0.23)",
                                e
                            )
                        }
                        if (wrapErr == LIBUSB_ERROR_ACCESS) {
                            throw SecurityException(
                                "Permission denied wrapping USB file descriptor ${sel.fileDescriptor}"
                            )
                        }
                        if (wrapErr != LIBUSB_SUCCESS || handleRef.value == null) {
                            throw IOException(
                                "libusb_wrap_sys_device(fd=${sel.fileDescriptor}) failed: " +
                                    LibUsbHolder.formatError(libusb, wrapErr)
                            )
                        }
                        openedHandle = handleRef.value
                        val dev = libusb.libusb_get_device(openedHandle)
                        if (dev != null) {
                            busNum = libusb.libusb_get_bus_number(dev).toInt() and 0xFF
                            devAddr = libusb.libusb_get_device_address(dev).toInt() and 0xFF
                            val desc = ByteArray(18)
                            if (libusb.libusb_get_device_descriptor(dev, desc) == LIBUSB_SUCCESS) {
                                resolvedVendorId = (desc[8].toInt() and 0xFF) or ((desc[9].toInt() and 0xFF) shl 8)
                                resolvedProductId = (desc[10].toInt() and 0xFF) or ((desc[11].toInt() and 0xFF) shl 8)
                                devDescBytes = desc
                            }
                        }
                        resolvedDeviceName = if (busNum > 0 && devAddr > 0) {
                            String.format("/dev/bus/usb/%03d/%03d (fd=%d)", busNum, devAddr, sel.fileDescriptor)
                        } else {
                            "usb-fd:${sel.fileDescriptor}"
                        }
                    }

                    is JvmUsbDeviceSelector.ByVidPid,
                    is JvmUsbDeviceSelector.ByDevicePath -> {
                        val targetBusAddr = if (sel is JvmUsbDeviceSelector.ByDevicePath) {
                            resolveLinuxUsbBusAndAddress(sel.devicePath)
                        } else {
                            null
                        }
                        val listRef = PointerByReference()
                        val count = libusb.libusb_get_device_list(newCtx, listRef).toLong()
                        if (count < 0) {
                            throw IOException(
                                "libusb_get_device_list failed: ${LibUsbHolder.formatError(libusb, count.toInt())}"
                            )
                        }
                        val listPtr = listRef.value
                        try {
                            val devices = listPtr.getPointerArray(0, count.toInt())
                            val desc = ByteArray(18)
                            for (dev in devices) {
                                if (dev == null) continue
                                val candBus = libusb.libusb_get_bus_number(dev).toInt() and 0xFF
                                val candAddr = libusb.libusb_get_device_address(dev).toInt() and 0xFF
                                if (targetBusAddr != null &&
                                    (candBus != targetBusAddr.first || candAddr != targetBusAddr.second)
                                ) {
                                    continue
                                }
                                if (libusb.libusb_get_device_descriptor(dev, desc) != LIBUSB_SUCCESS) continue
                                val vid = (desc[8].toInt() and 0xFF) or ((desc[9].toInt() and 0xFF) shl 8)
                                val pid = (desc[10].toInt() and 0xFF) or ((desc[11].toInt() and 0xFF) shl 8)
                                if (sel is JvmUsbDeviceSelector.ByVidPid &&
                                    (vid != sel.vendorId || pid != sel.productId)
                                ) {
                                    continue
                                }
                                busNum = candBus
                                devAddr = candAddr
                                resolvedVendorId = vid
                                resolvedProductId = pid
                                val handleRef = PointerByReference()
                                val openErr = libusb.libusb_open(dev, handleRef)
                                if (openErr == LIBUSB_ERROR_ACCESS) {
                                    throw SecurityException(
                                        String.format(
                                            "Permission denied for USB device %04X:%04X (/dev/bus/usb/%03d/%03d)",
                                            vid,
                                            pid,
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
                        } finally {
                            if (listPtr != null) {
                                libusb.libusb_free_device_list(listPtr, 1)
                            }
                        }

                        if (openedHandle == null) {
                            val descMsg = when (sel) {
                                is JvmUsbDeviceSelector.ByVidPid ->
                                    String.format("USB CCID device %04X:%04X not found", sel.vendorId, sel.productId)
                                is JvmUsbDeviceSelector.ByDevicePath ->
                                    String.format(
                                        "USB CCID device '%s' (bus %03d, device %03d) not found",
                                        sel.devicePath,
                                        targetBusAddr!!.first,
                                        targetBusAddr.second
                                    )
                                is JvmUsbDeviceSelector.ByFileDescriptor ->
                                    "USB CCID device fd=${sel.fileDescriptor} not found"
                            }
                            throw IOException(descMsg)
                        }
                        resolvedDeviceName = String.format("/dev/bus/usb/%03d/%03d", busNum, devAddr)
                    }
                }

                val handle = openedHandle!!
                devHandle = handle

                val sysfsSnapshot = if (busNum > 0 && devAddr > 0) {
                    JvmUsbEnumerator.enumerateDevices(
                        filterVendorId = resolvedVendorId.takeIf { it != 0 },
                        filterProductId = resolvedProductId.takeIf { it != 0 }
                    ).firstOrNull { it.busNumber == busNum && it.deviceAddress == devAddr }
                } else {
                    null
                }
                val rawDesc = sysfsSnapshot?.deviceInfo?.rawDescriptors ?: run {
                    val cfg = JvmUsbEnumerator.readActiveConfigurationDescriptor(libusb, handle)
                    if (cfg != null && devDescBytes != null) devDescBytes + cfg else cfg
                }
                cachedRawDescriptors = rawDesc

                val endpoints = if (rawDesc != null) {
                    CcidUsbTransport.findCcidInterfaceEndpoints(
                        rawDescriptors = rawDesc,
                        requestedInterfaceIndex = requestedInterfaceIndex,
                        interfaceNameResolver = { ifaceId, iIface ->
                            sysfsSnapshot?.deviceInfo?.interfaces?.firstOrNull { it.id == ifaceId }?.name
                                ?: JvmUsbEnumerator.readUsbString(libusb, handle, iIface.toByte())
                        }
                    )
                } else {
                    null
                } ?: throw IOException("CCID interface or endpoints not found on $resolvedDeviceName")
                resolvedEndpoints = endpoints

                try {
                    libusb.libusb_set_auto_detach_kernel_driver(handle, 1)
                } catch (_: Exception) {
                } catch (_: UnsatisfiedLinkError) {
                }

                val claimErr = libusb.libusb_claim_interface(handle, endpoints.interfaceId)
                if (claimErr != LIBUSB_SUCCESS) {
                    throw IOException(
                        "Could not claim CCID interface id=${endpoints.interfaceId} on $resolvedDeviceName: " +
                            "${LibUsbHolder.formatError(libusb, claimErr)} " +
                            "(ensure pcscd is not holding the device exclusively)"
                    )
                }
                claimedInterfaceId = endpoints.interfaceId
                success = true
            } finally {
                if (!success) {
                    close()
                }
            }
        }
    }

    override fun close() {
        var wasInterrupted = false
        synchronized(lock) {
            closing = true
            // Wait for any in-flight synchronous libusb transfers (e.g. interruptTransferIn) to
            // finish before freeing the native libusb_device_handle and libusb_context.
            while (inFlightTransfers > 0) {
                try {
                    lock.wait(250L)
                } catch (_: InterruptedException) {
                    wasInterrupted = true
                }
            }

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
                } catch (e: Exception) {
                    Logger.w(TAG, "Error releasing USB interface $ifaceId", e)
                }
            }
            if (libusb != null && handle != null) {
                try {
                    libusb.libusb_close(handle)
                } catch (e: Exception) {
                    Logger.w(TAG, "Error closing USB device handle", e)
                }
            }
            if (libusb != null && currentCtx != null) {
                try {
                    libusb.libusb_exit(currentCtx)
                } catch (e: Exception) {
                    Logger.w(TAG, "Error exiting libusb context", e)
                }
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt()
        }
    }

    private inline fun <T> withTransferHandle(
        onClosed: () -> T,
        block: (LibUsb, Pointer, CcidInterfaceEndpoints) -> T,
    ): T {
        val libusb: LibUsb
        val handle: Pointer
        val endpoints: CcidInterfaceEndpoints
        synchronized(lock) {
            if (closing) return onClosed()
            val h = devHandle ?: return onClosed()
            val ep = resolvedEndpoints ?: return onClosed()
            val lib = LibUsbHolder.getOrNull() ?: return onClosed()
            inFlightTransfers += 1
            libusb = lib
            handle = h
            endpoints = ep
        }
        try {
            return block(libusb, handle, endpoints)
        } finally {
            synchronized(lock) {
                inFlightTransfers -= 1
                if (inFlightTransfers == 0) {
                    lock.notifyAll()
                }
            }
        }
    }

    override fun bulkTransferOut(data: ByteArray, timeout: Duration): Int {
        return withTransferHandle(
            onClosed = { throw IOException("USB device is not open") }
        ) { libusb, handle, endpoints ->
            val transferred = IntByReference()
            val rc = libusb.libusb_bulk_transfer(
                handle,
                endpoints.bulkOutAddress,
                data,
                data.size,
                transferred,
                timeout.toUsbTimeoutMillis()
            )
            if (rc != LIBUSB_SUCCESS) {
                throw IOException("USB bulkTransferOut failed: ${LibUsbHolder.formatError(libusb, rc)}")
            }
            transferred.value
        }
    }

    override fun bulkTransferIn(buffer: ByteArray, timeout: Duration): Int {
        return withTransferHandle(
            onClosed = { throw IOException("USB device is not open") }
        ) { libusb, handle, endpoints ->
            val transferred = IntByReference()
            val rc = libusb.libusb_bulk_transfer(
                handle,
                endpoints.bulkInAddress,
                buffer,
                buffer.size,
                transferred,
                timeout.toUsbTimeoutMillis()
            )
            if (rc != LIBUSB_SUCCESS) {
                throw IOException("USB bulkTransferIn failed: ${LibUsbHolder.formatError(libusb, rc)}")
            }
            transferred.value
        }
    }

    override fun interruptTransferIn(buffer: ByteArray, timeout: Duration): Int {
        return withTransferHandle(
            onClosed = { -1 }
        ) { libusb, handle, endpoints ->
            val ep = endpoints.interruptInAddress ?: return@withTransferHandle -1
            val transferred = IntByReference()
            val rc = libusb.libusb_interrupt_transfer(
                handle,
                ep,
                buffer,
                buffer.size,
                transferred,
                timeout.toUsbTimeoutMillis()
            )
            when (rc) {
                LIBUSB_SUCCESS -> transferred.value
                LIBUSB_ERROR_TIMEOUT -> 0
                else -> -1
            }
        }
    }

    private fun Duration.toUsbTimeoutMillis(): Int =
        inWholeMilliseconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

internal actual fun ExternalNfcReaderUsb.observeUsbState(): Flow<ExternalNfcReaderState> = flow {
    while (true) {
        val tickBefore = permissionRefreshTick.value
        val snapshot = withContext(Dispatchers.IO) {
            JvmUsbEnumerator.enumerateDevices(
                filterVendorId = vendorId,
                filterProductId = productId
            ).firstOrNull()
        }
        val state = when {
            snapshot == null -> ExternalNfcReaderState.NOT_CONNECTED
            snapshot.hasPermission -> ExternalNfcReaderState.CONNECTED
            else -> ExternalNfcReaderState.CONNECTED_NO_PERMISSION
        }
        emit(state)
        withTimeoutOrNull(500.milliseconds) {
            permissionRefreshTick.first { it != tickBefore }
        }
    }
}.distinctUntilChanged()

private suspend fun resolveEffectivePosixUsername(): String? {
    val idBinary = listOf("/usr/bin/id", "/bin/id").firstOrNull { File(it).canExecute() } ?: "id"
    return try {
        runInterruptible {
            val proc = ProcessBuilder(idBinary, "-un")
                .redirectErrorStream(true)
                .start()
            try {
                val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
                val exitCode = proc.waitFor()
                if (exitCode == 0 && POSIX_USERNAME_REGEX.matches(output)) {
                    output
                } else {
                    Logger.w(TAG, "'$idBinary -un' failed or returned invalid username (exit=$exitCode)")
                    null
                }
            } finally {
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to resolve current POSIX username via '$idBinary -un'", e)
        null
    }
}

internal actual suspend fun ExternalNfcReaderUsb.requestUsbPermission(): Boolean = withContext(Dispatchers.IO) {
    val snapshot = JvmUsbEnumerator.enumerateDevices(
        filterVendorId = vendorId,
        filterProductId = productId
    ).firstOrNull() ?: throw IllegalStateException("Device is not connected")

    if (snapshot.hasPermission) {
        return@withContext true
    }

    val devNodePath = snapshot.deviceNodePath ?: return@withContext false
    if (!USB_DEV_NODE_REGEX.matches(devNodePath)) {
        Logger.w(TAG, "Refusing to request USB permission for unexpected device path: $devNodePath")
        return@withContext false
    }

    val pkexecPath = "/usr/bin/pkexec".takeIf { File(it).canExecute() } ?: run {
        Logger.w(TAG, "/usr/bin/pkexec is not available to grant USB ACL permission")
        return@withContext false
    }
    val setfaclPath = listOf("/usr/bin/setfacl", "/bin/setfacl").firstOrNull { File(it).canExecute() } ?: run {
        Logger.w(TAG, "setfacl is not available to grant per-user USB ACL permission")
        return@withContext false
    }
    val username = resolveEffectivePosixUsername() ?: return@withContext false

    val cmd = listOf(pkexecPath, setfaclPath, "-m", "u:$username:rw", devNodePath)
    try {
        Logger.i(TAG, "Requesting per-user USB ACL permission via: ${cmd.joinToString(" ")}")
        runInterruptible {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            try {
                val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
                val exitCode = proc.waitFor()
                if (exitCode == 126 || exitCode == 127) {
                    Logger.i(TAG, "USB Polkit authorization dismissed or denied (exit=$exitCode)")
                } else if (exitCode != 0) {
                    Logger.w(TAG, "setfacl helper exited with $exitCode: $output")
                }
            } finally {
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to execute USB permission helper", e)
    }

    val updated = JvmUsbEnumerator.enumerateDevices(
        filterVendorId = vendorId,
        filterProductId = productId
    ).firstOrNull()
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
        JvmUsbEnumerator.enumerateDevices(
            filterVendorId = vendorId,
            filterProductId = productId
        ).firstOrNull()
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

private fun createJvmNfcTagReaderUsb(
    selector: JvmUsbDeviceSelector,
    interfaceIndex: Int?,
    disconnectDelay: Duration,
): NfcTagReaderUsb {
    if (interfaceIndex != null) {
        require(interfaceIndex in 0..255) {
            "interfaceIndex must be in 0..255, got $interfaceIndex"
        }
    }
    require(!disconnectDelay.isNegative()) {
        "disconnectDelay must be non-negative, got $disconnectDelay"
    }
    return NfcTagReaderUsb(
        deviceIdentity = selector,
        interfaceIndex = interfaceIndex,
        disconnectDelay = disconnectDelay,
        createDriver = { pollingFrameData ->
            CcidDriver(
                transport = JvmCcidUsbTransport(
                    selector = selector,
                    requestedInterfaceIndex = interfaceIndex
                ),
                pollingFrameData = pollingFrameData
            )
        }
    )
}

/**
 * Creates a USB CCID [NfcTagReaderUsb] targeting an attached USB reader matching [vendorId]
 * and [productId].
 *
 * The USB device is opened lazily when [NfcTagReaderUsb.scan] is called, so the returned
 * [NfcTagReaderUsb] automatically survives USB resets and hotplug re-enumeration between scans.
 * Call [NfcTagReaderUsb.close] when the reader is no longer needed.
 *
 * @param vendorId the 16-bit USB vendor ID (`0x0000..0xFFFF`), for example `0x072F` (ACS).
 * @param productId the 16-bit USB product ID (`0x0000..0xFFFF`), for example `0x223B` (ACR1252U).
 * @param interfaceIndex the USB `bInterfaceNumber` of the CCID interface to claim, or `null`
 *   (the default) to automatically select the first contactless NFC CCID interface (skipping
 *   SAM/SIM and mechanical contact card slots).
 * @param disconnectDelay how long to keep the USB interface claimed after a completed scan and
 *   tag removal before releasing it (defaults to `5.seconds`).
 * @return a closeable [NfcTagReaderUsb] ready to scan tags via [NfcTagReaderUsb.scan].
 * @throws IllegalArgumentException if [vendorId], [productId], [interfaceIndex], or
 *   [disconnectDelay] is out of range.
 */
fun NfcTagReaderUsb.Companion.create(
    vendorId: Int,
    productId: Int,
    interfaceIndex: Int? = null,
    disconnectDelay: Duration = 5.seconds,
): NfcTagReaderUsb {
    require(vendorId in 0..0xFFFF) {
        "vendorId must be a 16-bit unsigned integer (0..0xFFFF), got $vendorId"
    }
    require(productId in 0..0xFFFF) {
        "productId must be a 16-bit unsigned integer (0..0xFFFF), got $productId"
    }
    return createJvmNfcTagReaderUsb(
        selector = JvmUsbDeviceSelector.ByVidPid(vendorId, productId),
        interfaceIndex = interfaceIndex,
        disconnectDelay = disconnectDelay
    )
}

/**
 * Creates a USB CCID [NfcTagReaderUsb] targeting a specific Linux USB device node, `udev`
 * symlink, or physical `sysfs` USB port path.
 *
 * Supported [devicePath] formats (resolved freshly each time [NfcTagReaderUsb.scan] opens the
 * underlying USB transport, so `udev` symlinks and `sysfs` port paths survive USB hotplugs and
 * re-enumeration):
 * - A `usbfs` device node path, such as `"/dev/bus/usb/001/004"`.
 * - A `udev` symlink pointing to a `/dev/bus/usb/BBB/DDD` node or `sysfs` USB device directory,
 *   such as `"/dev/nfc-reader-front"` (resolved via `Path.toRealPath()`).
 * - A Linux `sysfs` USB port directory path or bare port name, such as
 *   `"/sys/bus/usb/devices/1-1.2"` or `"1-1.2"`, which pins the reader to a physical USB socket
 *   even when multiple attached readers share identical `VID:PID` values and lack serial numbers.
 *
 * Call [NfcTagReaderUsb.close] when the reader is no longer needed.
 *
 * @param devicePath the Linux `usbfs` device node path, `udev` symlink path, or `sysfs` USB port
 *   path/name.
 * @param interfaceIndex the USB `bInterfaceNumber` of the CCID interface to claim, or `null`
 *   (the default) to automatically select the first contactless NFC CCID interface (skipping
 *   SAM/SIM and mechanical contact card slots).
 * @param disconnectDelay how long to keep the USB interface claimed after a completed scan and
 *   tag removal before releasing it (defaults to `5.seconds`).
 * @return a closeable [NfcTagReaderUsb] ready to scan tags via [NfcTagReaderUsb.scan].
 * @throws IllegalArgumentException if [devicePath] is blank or [interfaceIndex] /
 *   [disconnectDelay] is out of range.
 */
fun NfcTagReaderUsb.Companion.create(
    devicePath: String,
    interfaceIndex: Int? = null,
    disconnectDelay: Duration = 5.seconds,
): NfcTagReaderUsb {
    require(devicePath.isNotBlank()) {
        "devicePath must not be blank"
    }
    return createJvmNfcTagReaderUsb(
        selector = JvmUsbDeviceSelector.ByDevicePath(devicePath.trim()),
        interfaceIndex = interfaceIndex,
        disconnectDelay = disconnectDelay
    )
}

/**
 * Creates a USB CCID [NfcTagReaderUsb] wrapping an already-opened OS file descriptor for a
 * `/dev/bus/usb/BBB/DDD` device node via `libusb_wrap_sys_device()` (requires `libusb` >= 1.0.23).
 *
 * This is intended for privilege-separated or containerized Linux deployments where a launcher
 * or supervisor opens the USB device node and passes the file descriptor to an unprivileged
 * reader process. Per `libusb` semantics, calling [NfcTagReaderUsb.close] releases the claimed
 * USB interface and `libusb` handle without closing [fileDescriptor] itself; the caller remains
 * responsible for closing [fileDescriptor] once the reader has been closed.
 *
 * @param fileDescriptor a non-negative open POSIX file descriptor referring to a Linux `usbfs`
 *   device node (`/dev/bus/usb/BBB/DDD`).
 * @param interfaceIndex the USB `bInterfaceNumber` of the CCID interface to claim, or `null`
 *   (the default) to automatically select the first contactless NFC CCID interface (skipping
 *   SAM/SIM and mechanical contact card slots).
 * @param disconnectDelay how long to keep the USB interface claimed after a completed scan and
 *   tag removal before releasing it (defaults to `5.seconds`).
 * @return a closeable [NfcTagReaderUsb] ready to scan tags via [NfcTagReaderUsb.scan].
 * @throws IllegalArgumentException if [fileDescriptor] is negative or [interfaceIndex] /
 *   [disconnectDelay] is out of range.
 */
fun NfcTagReaderUsb.Companion.createFromFileDescriptor(
    fileDescriptor: Int,
    interfaceIndex: Int? = null,
    disconnectDelay: Duration = 5.seconds,
): NfcTagReaderUsb {
    require(fileDescriptor >= 0) {
        "fileDescriptor must be non-negative, got $fileDescriptor"
    }
    return createJvmNfcTagReaderUsb(
        selector = JvmUsbDeviceSelector.ByFileDescriptor(fileDescriptor),
        interfaceIndex = interfaceIndex,
        disconnectDelay = disconnectDelay
    )
}
