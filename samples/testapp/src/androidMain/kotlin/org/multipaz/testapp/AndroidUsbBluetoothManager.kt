package org.multipaz.testapp

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import org.multipaz.bthci.AndroidUsbHciPacketIo
import org.multipaz.bthci.BtHciMdocTransportFactory
import org.multipaz.bthci.HciPacketIo
import org.multipaz.context.applicationContext
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

internal enum class UsbBluetoothAdapterState {
    CONNECTED,
    CONNECTED_NO_PERMISSION,
    NOT_CONNECTED,
}

internal data class UsbBluetoothAdapterInfo(
    val uiKey: String,
    val persistedId: String,
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String?,
    val deviceName: String?,
    val disambiguateByPath: Boolean,
    val state: UsbBluetoothAdapterState,
    val isSelected: Boolean,
) {
    val vidPidHex: String
        get() = "${vendorId.toHex4()}:${productId.toHex4()}"
}

internal data class UsbBluetoothSelectionSnapshot(
    val builtInSelected: Boolean,
    val adapters: List<UsbBluetoothAdapterInfo>,
)

private fun Int.toHex4(): String = (this and 0xFFFF).toString(16).padStart(4, '0')

/**
 * Discovers external USB Bluetooth HCI adapters on Android, manages USB permissions, persists the
 * active adapter choice in [TestAppSettingsModel], and provides the active [MdocTransportFactory].
 */
internal object AndroidUsbBluetoothManager {
    private const val TAG = "AndroidUsbBtMgr"
    private const val ACTION_USB_BT_HCI_PERMISSION = "org.multipaz.testapp.USB_BT_HCI_PERMISSION"

    private const val HCI_OP_RESET = 0x0C03
    private const val HCI_OP_READ_LOCAL_VERSION = 0x1001
    private const val HCI_OP_READ_BD_ADDR = 0x1009

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initLock = Any()
    private var initialized = false

    @Volatile
    private var settingsModel: TestAppSettingsModel? = null

    // Tie-breaker for the current process when two identical dongles without serial numbers are attached.
    @Volatile
    private var preferredDeviceName: String? = null

    private val _isExternalAdapterSelected = MutableStateFlow(false)
    val isExternalAdapterSelected: StateFlow<Boolean> = _isExternalAdapterSelected.asStateFlow()

    private val _snapshot = MutableStateFlow(
        UsbBluetoothSelectionSnapshot(
            builtInSelected = true,
            adapters = emptyList(),
        )
    )
    val snapshot: StateFlow<UsbBluetoothSelectionSnapshot> = _snapshot.asStateFlow()

    private val btHciTransportFactory = BtHciMdocTransportFactory(
        fallbackFactory = MdocTransportFactory.Default,
        packetIoFactory = ::openSelectedUsbHciPacketIo,
    )

    fun init(model: TestAppSettingsModel) {
        synchronized(initLock) {
            settingsModel = model
            if (initialized) {
                refresh()
                return
            }
            initialized = true
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED,
                    ACTION_USB_BT_HCI_PERMISSION -> refresh()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_BT_HCI_PERMISSION)
        }
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch {
            model.androidBleUsbAdapterId.collect { selectedId ->
                _isExternalAdapterSelected.value = selectedId.isNotEmpty()
                if (selectedId.isEmpty()) {
                    preferredDeviceName = null
                }
                refresh()
            }
        }
        refresh()
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        Logger.i(TAG, "USB Bluetooth HCI adapter attached: ${device.deviceName}")
        refresh()
    }

    fun getActiveTransportFactory(): MdocTransportFactory {
        val selectedId = settingsModel?.androidBleUsbAdapterId?.value.orEmpty()
        return if (selectedId.isEmpty()) {
            MdocTransportFactory.Default
        } else {
            btHciTransportFactory
        }
    }

    fun selectBuiltIn() {
        val model = settingsModel ?: return
        preferredDeviceName = null
        model.androidBleUsbAdapterId.value = ""
        model.androidBleUsbAdapterName.value = ""
        refresh()
    }

    suspend fun selectUsbAdapter(adapter: UsbBluetoothAdapterInfo): Boolean {
        val model = settingsModel ?: return false
        preferredDeviceName = adapter.deviceName
        model.androidBleUsbAdapterId.value = adapter.persistedId
        model.androidBleUsbAdapterName.value = adapter.displayName
        refresh()
        if (adapter.state == UsbBluetoothAdapterState.CONNECTED_NO_PERMISSION) {
            return requestPermission(adapter)
        }
        return adapter.state == UsbBluetoothAdapterState.CONNECTED
    }

    suspend fun requestPermission(adapter: UsbBluetoothAdapterInfo): Boolean {
        val usbManager = getUsbManager()
        val device = findMatchingAttachedDevice(
            usbManager = usbManager,
            persistedId = adapter.persistedId,
            deviceNameHint = adapter.deviceName ?: preferredDeviceName,
        ) ?: throw IOException("USB Bluetooth adapter '${adapter.displayName}' is not connected")

        if (usbManager.hasPermission(device)) {
            promotePersistedSerialIfSelected(usbManager, device, adapter.persistedId)
            refresh()
            return true
        }

        val granted = suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_BT_HCI_PERMISSION) {
                        try {
                            applicationContext.unregisterReceiver(this)
                        } catch (_: IllegalArgumentException) {
                        }
                        val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        continuation.resume(ok)
                    }
                }
            }
            val filter = IntentFilter(ACTION_USB_BT_HCI_PERMISSION)
            ContextCompat.registerReceiver(
                applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            continuation.invokeOnCancellation {
                try {
                    applicationContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                }
            }

            val intent = Intent(ACTION_USB_BT_HCI_PERMISSION).apply {
                setPackage(applicationContext.packageName)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            }
            @SuppressLint("WrongConstant")
            val pendingIntent = PendingIntentCompat.getBroadcast(
                applicationContext,
                0,
                intent,
                flags,
                true,
            )
            usbManager.requestPermission(device, pendingIntent)
        }

        if (granted) {
            promotePersistedSerialIfSelected(usbManager, device, adapter.persistedId)
        }
        refresh()
        return granted
    }

    /**
     * Opens the currently selected USB Bluetooth HCI adapter, runs `HCI_Reset`,
     * `HCI_Read_Local_Version_Information`, and `HCI_Read_BD_ADDR`, and returns a human-readable
     * controller summary string.
     */
    suspend fun probeSelectedAdapter(): String = withTimeout(3.seconds) {
        val io = openSelectedUsbHciPacketIo()
        try {
            val resetParams = executeProbeCommand(io, HCI_OP_RESET)
            if (resetParams.isEmpty() || (resetParams[0].toInt() and 0xFF) != 0x00) {
                throw IOException("HCI_Reset failed")
            }
            io.markControllerResetCompleted()

            val verParams = executeProbeCommand(io, HCI_OP_READ_LOCAL_VERSION)
            if (verParams.size < 9 || (verParams[0].toInt() and 0xFF) != 0x00) {
                throw IOException("HCI_Read_Local_Version_Information failed")
            }
            val hciVersion = verParams[1].toInt() and 0xFF
            val hciRevision = (verParams[2].toInt() and 0xFF) or ((verParams[3].toInt() and 0xFF) shl 8)
            val companyId = (verParams[5].toInt() and 0xFF) or ((verParams[6].toInt() and 0xFF) shl 8)

            val addrParams = executeProbeCommand(io, HCI_OP_READ_BD_ADDR)
            if (addrParams.size < 7 || (addrParams[0].toInt() and 0xFF) != 0x00) {
                throw IOException("HCI_Read_BD_ADDR failed")
            }
            val bdAddr = (6 downTo 1).joinToString(":") { idx ->
                (addrParams[idx].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
            }
            val btSpecName = hciVersionToSpecString(hciVersion)
            "OK — Bluetooth $btSpecName (HCI rev 0x${hciRevision.toHex4()}, " +
                "Company 0x${companyId.toHex4()}), BD_ADDR $bdAddr"
        } finally {
            io.close()
        }
    }

    private suspend fun executeProbeCommand(io: HciPacketIo, opcode: Int): ByteArray {
        val cmd = byteArrayOf(
            0x01,
            (opcode and 0xFF).toByte(),
            ((opcode ushr 8) and 0xFF).toByte(),
            0x00,
        )
        io.writePacket(cmd)
        while (true) {
            val pkt = io.readPacket() ?: throw IOException("USB HCI transport closed while awaiting response")
            if (pkt.size >= 6 &&
                pkt[0] == 0x04.toByte() &&
                pkt[1] == 0x0E.toByte()
            ) {
                val paramLen = pkt[2].toInt() and 0xFF
                if (pkt.size < 3 + paramLen || paramLen < 3) continue
                val evtOpcode = (pkt[4].toInt() and 0xFF) or ((pkt[5].toInt() and 0xFF) shl 8)
                if (evtOpcode == opcode) {
                    return pkt.copyOfRange(6, 3 + paramLen)
                }
            }
        }
    }

    private fun hciVersionToSpecString(hciVersion: Int): String = when (hciVersion) {
        0x06 -> "4.0"
        0x07 -> "4.1"
        0x08 -> "4.2"
        0x09 -> "5.0"
        0x0A -> "5.1"
        0x0B -> "5.2"
        0x0C -> "5.3"
        0x0D -> "5.4"
        0x0E -> "6.0"
        else -> "HCI 0x${hciVersion.toString(16)}"
    }

    private suspend fun openSelectedUsbHciPacketIo(): HciPacketIo {
        val model = settingsModel
            ?: throw IOException("TestAppSettingsModel is not initialized")
        val selectedId = model.androidBleUsbAdapterId.value
        if (selectedId.isEmpty()) {
            throw IOException("No external USB Bluetooth HCI adapter is selected")
        }
        val fallbackLabel = model.androidBleUsbAdapterName.value.ifEmpty { selectedId }
        val usbManager = getUsbManager()
        val device = findMatchingAttachedDevice(
            usbManager = usbManager,
            persistedId = selectedId,
            deviceNameHint = preferredDeviceName,
        ) ?: throw IOException(
            "Selected USB Bluetooth adapter '$fallbackLabel' is not connected"
        )
        if (!usbManager.hasPermission(device)) {
            throw IOException(
                "USB permission not granted for Bluetooth adapter '$fallbackLabel'. " +
                    "Grant permission in Settings."
            )
        }
        promotePersistedSerialIfSelected(usbManager, device, selectedId)
        return AndroidUsbHciPacketIo(usbManager = usbManager, device = device).also {
            it.open()
        }
    }

    fun refresh() {
        val usbManager = try {
            getUsbManager()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return
        }
        val model = settingsModel
        val selectedId = model?.androidBleUsbAdapterId?.value.orEmpty()
        val selectedName = model?.androidBleUsbAdapterName?.value.orEmpty()

        val attachedBtDevices = usbManager.deviceList.values
            .filter { AndroidUsbHciPacketIo.isBluetoothHciDevice(it) }
            .sortedBy { it.deviceName }

        // Auto-promote serial into persistedId if permission was granted externally (e.g. via USB attach intent)
        if (selectedId.isNotEmpty() && model != null) {
            val matched = findMatchingAttachedDevice(usbManager, selectedId, preferredDeviceName)
            if (matched != null && usbManager.hasPermission(matched)) {
                val upgradedId = buildPersistedId(usbManager, matched)
                if (upgradedId != selectedId) {
                    model.androidBleUsbAdapterId.value = upgradedId
                }
            }
        }

        val effectiveSelectedId = model?.androidBleUsbAdapterId?.value.orEmpty()
        val countsByPersistedId = mutableMapOf<String, Int>()
        for (dev in attachedBtDevices) {
            val pid = buildPersistedId(usbManager, dev)
            countsByPersistedId[pid] = (countsByPersistedId[pid] ?: 0) + 1
        }

        val selectedMatchedDevice = if (effectiveSelectedId.isNotEmpty()) {
            findMatchingAttachedDevice(usbManager, effectiveSelectedId, preferredDeviceName)
        } else {
            null
        }

        val items = mutableListOf<UsbBluetoothAdapterInfo>()
        for (dev in attachedBtDevices) {
            val hasPerm = usbManager.hasPermission(dev)
            val serial = safeSerialNumber(usbManager, dev)
            val persistedId = buildPersistedId(usbManager, dev)
            val disambiguate = (countsByPersistedId[persistedId] ?: 0) > 1
            val isThisSelected = selectedMatchedDevice != null &&
                selectedMatchedDevice.deviceName == dev.deviceName
            items.add(
                UsbBluetoothAdapterInfo(
                    uiKey = dev.deviceName,
                    persistedId = persistedId,
                    displayName = buildDisplayName(dev),
                    vendorId = dev.vendorId,
                    productId = dev.productId,
                    serialNumber = serial,
                    deviceName = dev.deviceName,
                    disambiguateByPath = disambiguate,
                    state = if (hasPerm) {
                        UsbBluetoothAdapterState.CONNECTED
                    } else {
                        UsbBluetoothAdapterState.CONNECTED_NO_PERMISSION
                    },
                    isSelected = isThisSelected,
                )
            )
        }

        if (effectiveSelectedId.isNotEmpty() && selectedMatchedDevice == null) {
            val parsed = parsePersistedId(effectiveSelectedId)
            val vid = parsed?.first ?: 0
            val pid = parsed?.second ?: 0
            val serial = parsed?.third
            items.add(
                UsbBluetoothAdapterInfo(
                    uiKey = "disconnected:$effectiveSelectedId",
                    persistedId = effectiveSelectedId,
                    displayName = selectedName.ifEmpty { "USB Bluetooth Adapter" },
                    vendorId = vid,
                    productId = pid,
                    serialNumber = serial,
                    deviceName = null,
                    disambiguateByPath = false,
                    state = UsbBluetoothAdapterState.NOT_CONNECTED,
                    isSelected = true,
                )
            )
        }

        _isExternalAdapterSelected.value = effectiveSelectedId.isNotEmpty()
        _snapshot.value = UsbBluetoothSelectionSnapshot(
            builtInSelected = effectiveSelectedId.isEmpty(),
            adapters = items,
        )
    }

    private fun promotePersistedSerialIfSelected(
        usbManager: UsbManager,
        device: UsbDevice,
        previousId: String,
    ) {
        val model = settingsModel ?: return
        val currentSelected = model.androidBleUsbAdapterId.value
        if (currentSelected.isEmpty()) return
        if (currentSelected == previousId || parsePersistedId(currentSelected)?.let {
                it.first == device.vendorId && it.second == device.productId
            } == true
        ) {
            val upgraded = buildPersistedId(usbManager, device)
            if (upgraded != currentSelected) {
                model.androidBleUsbAdapterId.value = upgraded
            }
            model.androidBleUsbAdapterName.value = buildDisplayName(device)
        }
    }

    private fun findMatchingAttachedDevice(
        usbManager: UsbManager,
        persistedId: String,
        deviceNameHint: String?,
    ): UsbDevice? {
        val (vid, pid, savedSerial) = parsePersistedId(persistedId) ?: return null
        val candidates = usbManager.deviceList.values
            .filter {
                AndroidUsbHciPacketIo.isBluetoothHciDevice(it) &&
                    it.vendorId == vid &&
                    it.productId == pid
            }
            .sortedBy { it.deviceName }
        if (candidates.isEmpty()) return null

        if (savedSerial != null) {
            candidates.firstOrNull { safeSerialNumber(usbManager, it) == savedSerial }?.let {
                return it
            }
            // Before USB permission is granted on Android 10+, serialNumber cannot be read yet;
            // match an unauthorized VID:PID candidate so the user can grant permission.
            if (deviceNameHint != null) {
                candidates.firstOrNull {
                    !usbManager.hasPermission(it) && it.deviceName == deviceNameHint
                }?.let { return it }
            }
            return candidates.firstOrNull { !usbManager.hasPermission(it) }
        }

        if (deviceNameHint != null) {
            candidates.firstOrNull { it.deviceName == deviceNameHint }?.let { return it }
        }
        return candidates.first()
    }

    private fun buildPersistedId(usbManager: UsbManager, device: UsbDevice): String {
        val base = "${device.vendorId.toHex4()}:${device.productId.toHex4()}"
        val serial = safeSerialNumber(usbManager, device)
        return if (serial != null) "$base:$serial" else base
    }

    private fun parsePersistedId(persistedId: String): Triple<Int, Int, String?>? {
        val parts = persistedId.split(':', limit = 3)
        if (parts.size < 2) return null
        val vid = parts[0].toIntOrNull(16) ?: return null
        val pid = parts[1].toIntOrNull(16) ?: return null
        val serial = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
        return Triple(vid, pid, serial)
    }

    private fun safeSerialNumber(usbManager: UsbManager, device: UsbDevice): String? {
        if (!usbManager.hasPermission(device)) return null
        return try {
            device.serialNumber?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun buildDisplayName(device: UsbDevice): String {
        val manufacturer = device.manufacturerName?.trim()?.takeIf { it.isNotEmpty() }
        val product = device.productName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            manufacturer != null && product != null -> {
                if (product.startsWith(manufacturer, ignoreCase = true)) {
                    product
                } else {
                    "$manufacturer $product"
                }
            }
            product != null -> product
            manufacturer != null -> "$manufacturer Bluetooth Adapter"
            else -> "USB Bluetooth Adapter"
        }
    }

    private fun getUsbManager(): UsbManager =
        applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
}
