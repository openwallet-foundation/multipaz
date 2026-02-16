package org.multipaz.nfc

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger

private const val TAG = "ExternalNfcReaderUsb"

// Used to trigger intent when USB permission changes
private const val ACTION_USB_PERMISSION = "org.multipaz.nfc.USB_PERMISSION"

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
        if (device == null) {
            return ExternalNfcReaderState.NOT_CONNECTED
        } else {
            return if (usbManager.hasPermission(device)) {
                return ExternalNfcReaderState.CONNECTED
            } else {
                return ExternalNfcReaderState.CONNECTED_NO_PERMISSION
            }
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

internal actual suspend fun ExternalNfcReaderUsb.getUsbNfcTagReader(): NfcTagReader {
    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    val deviceList = usbManager.deviceList
    val device = deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }
    if (device == null) {
        throw IllegalStateException("Device is not connected")
    }
    return NfcTagReaderUsb(
        manager = usbManager,
        device = device
    )
}
