package org.multipaz.nfc

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import java.io.IOException
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.time.Duration

private const val TAG = "ExternalNfcReaderUsb"

// Used to trigger intent when USB permission changes
private const val ACTION_USB_PERMISSION = "org.multipaz.nfc.USB_PERMISSION"

internal actual val nfcIoDispatcher: CoroutineDispatcher = Dispatchers.IO

internal class AndroidCcidUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val requestedInterfaceIndex: Int,
) : CcidUsbTransport {
    @Volatile
    private var connection: UsbDeviceConnection? = null

    @Volatile
    private var usbInterface: UsbInterface? = null

    @Volatile
    private var bulkInEndpoint: UsbEndpoint? = null

    @Volatile
    private var bulkOutEndpoint: UsbEndpoint? = null

    @Volatile
    private var interruptEndpoint: UsbEndpoint? = null

    override val deviceName: String
        get() = device.deviceName

    override val vendorId: Int
        get() = device.vendorId

    override val productId: Int
        get() = device.productId

    override val interfaceId: Int
        get() = usbInterface?.id ?: requestedInterfaceIndex

    override val rawDescriptors: ByteArray?
        get() = try {
            connection?.rawDescriptors
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    override val hasInterruptEndpoint: Boolean
        get() = interruptEndpoint != null

    override val interruptMaxPacketSize: Int
        get() = interruptEndpoint?.maxPacketSize ?: 0

    override fun open() {
        if (!usbManager.hasPermission(device)) {
            throw SecurityException("Permission denied for device ${device.deviceName}")
        }
        val conn = usbManager.openDevice(device)
            ?: throw IOException("Could not open device connection")
        connection = conn

        val iface = findCcidInterface(device)
            ?: run {
                conn.close()
                connection = null
                throw IOException("CCID interface not found")
            }
        usbInterface = iface

        findEndpoints(iface)
        if (bulkInEndpoint == null || bulkOutEndpoint == null) {
            conn.close()
            connection = null
            usbInterface = null
            bulkInEndpoint = null
            bulkOutEndpoint = null
            interruptEndpoint = null
            throw IOException("CCID bulk endpoints not found on interface id=${iface.id}")
        }
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            connection = null
            usbInterface = null
            bulkInEndpoint = null
            bulkOutEndpoint = null
            interruptEndpoint = null
            throw IOException("Could not claim CCID interface id=${iface.id}")
        }
    }

    override fun close() {
        val iface = usbInterface
        val conn = connection
        usbInterface = null
        connection = null
        bulkInEndpoint = null
        bulkOutEndpoint = null
        interruptEndpoint = null
        if (conn != null && iface != null) {
            try {
                conn.releaseInterface(iface)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error releasing USB interface", e)
            }
        }
        if (conn != null) {
            try {
                conn.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error closing USB connection", e)
            }
        }
    }

    override fun bulkTransferOut(data: ByteArray, timeout: Duration): Int {
        val conn = connection ?: throw IOException("Failed to send command over bulk-out endpoint.")
        val endpoint = bulkOutEndpoint ?: throw IOException("Bulk-out endpoint not available.")
        return conn.bulkTransfer(endpoint, data, data.size, timeout.toUsbTimeoutMillis())
    }

    override fun bulkTransferIn(buffer: ByteArray, timeout: Duration): Int {
        val conn = connection ?: throw IOException("Failed to read response from bulk-in endpoint.")
        val endpoint = bulkInEndpoint ?: throw IOException("Bulk-in endpoint not available.")
        return conn.bulkTransfer(endpoint, buffer, buffer.size, timeout.toUsbTimeoutMillis())
    }

    override fun interruptTransferIn(buffer: ByteArray, timeout: Duration): Int {
        val conn = connection ?: return -1
        val endpoint = interruptEndpoint ?: return -1
        return conn.bulkTransfer(endpoint, buffer, buffer.size, timeout.toUsbTimeoutMillis())
    }

    private fun Duration.toUsbTimeoutMillis(): Int =
        inWholeMilliseconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun findCcidInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            if (candidate.id == requestedInterfaceIndex) {
                Logger.i(TAG, "Using specified USB interface id=${candidate.id} for device ${device.deviceName}")
                return candidate
            }
        }
        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            if (candidate.interfaceClass == UsbConstants.USB_CLASS_CSCID) {
                Logger.i(TAG, "Defaulting to first CCID interface id=${candidate.id} for device ${device.deviceName}")
                return candidate
            }
        }
        return null
    }

    private fun findEndpoints(iface: UsbInterface) {
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    bulkInEndpoint = endpoint
                } else {
                    bulkOutEndpoint = endpoint
                }
            } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                endpoint.direction == UsbConstants.USB_DIR_IN
            ) {
                interruptEndpoint = endpoint
            }
        }
    }
}

internal actual suspend fun ExternalNfcReaderUsb.requestUsbPermission(): Boolean {
    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    val deviceList = usbManager.deviceList
    val device = deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }

    if (device == null) {
        throw IllegalStateException("Device is not connected")
    }

    if (usbManager.hasPermission(device)) {
        Logger.w(TAG, "Permission already granted")
        return true
    }

    // Suspend the coroutine until the user responds to the dialog
    return suspendCancellableCoroutine { continuation ->

        // 1. Create a one-shot receiver to listen for the result
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    // Instantly unregister so we don't leak the receiver
                    applicationContext.unregisterReceiver(this)

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        continuation.resume(true)
                    } else {
                        continuation.resume(false)
                    }
                }
            }
        }

        // 2. Register the receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 3. Handle coroutine cancellation (e.g., if the user navigates away while the dialog is open)
        continuation.invokeOnCancellation {
            applicationContext.unregisterReceiver(receiver)
        }

        // 4. Trigger the system permission dialog
        val intent = Intent(ACTION_USB_PERMISSION)
        intent.setPackage(applicationContext.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
        }
        @SuppressLint("WrongConstant")
        val pendingIntent = PendingIntentCompat.getBroadcast(
            /* context = */ applicationContext,
            /* requestCode = */ 0,
            /* intent = */ intent,
            /* flags = */ flags,
            /* isMutable = */ true
        )
        usbManager.requestPermission(device, pendingIntent)
    }
}

internal actual fun ExternalNfcReaderUsb.observeUsbState(): Flow<ExternalNfcReaderState> = callbackFlow {
    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    // Helper function to check current state
    fun checkCurrentUsbState(): ExternalNfcReaderState {
        val deviceList = usbManager.deviceList
        val device = deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        return if (device == null) {
            ExternalNfcReaderState.NOT_CONNECTED
        } else if (usbManager.hasPermission(device)) {
            ExternalNfcReaderState.CONNECTED
        } else {
            ExternalNfcReaderState.CONNECTED_NO_PERMISSION
        }
    }
    trySend(checkCurrentUsbState())

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                ACTION_USB_PERMISSION -> {
                    // Whenever a USB event happens, re-evaluate and emit the new state
                    trySend(checkCurrentUsbState())
                }
            }
        }
    }

    val filter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        addAction(ACTION_USB_PERMISSION)
    }
    ContextCompat.registerReceiver(
        applicationContext,
        receiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED
    )

    awaitClose {
        applicationContext.unregisterReceiver(receiver)
    }
}

private data class UsbReaderKey(
    val vendorId: Int,
    val productId: Int,
    val interfaceIndex: Int,
)

private data class AndroidDeviceIdentity(
    val deviceId: Int,
    val deviceName: String,
)

private val usbReadersLock = Any()
private val usbReaders = mutableMapOf<UsbReaderKey, NfcTagReaderUsb>()

internal actual suspend fun ExternalNfcReaderUsb.getUsbNfcTagReader(): NfcTagReader {
    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    val deviceList = usbManager.deviceList
    val device = deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        ?: throw IllegalStateException("Device is not connected")
    val key = UsbReaderKey(vendorId, productId, interfaceIndex)
    val identity = AndroidDeviceIdentity(device.deviceId, device.deviceName)
    var staleReader: NfcTagReaderUsb? = null
    val reader = synchronized(usbReadersLock) {
        val existing = usbReaders[key]
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
                        transport = AndroidCcidUsbTransport(
                            usbManager = usbManager,
                            device = device,
                            requestedInterfaceIndex = interfaceIndex
                        ),
                        pollingFrameData = pollingFrameData
                    )
                }
            ).also { usbReaders[key] = it }
        }
    }
    staleReader?.close()
    return reader
}
