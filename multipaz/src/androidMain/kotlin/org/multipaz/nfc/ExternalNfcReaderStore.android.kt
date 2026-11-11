package org.multipaz.nfc

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CancellationException
import org.multipaz.context.applicationContext

/**
 * Adds a USB device to the [ExternalNfcReaderStore] if it doesn't exist already.
 *
 * This can be used from an Activity like this:
 * ```
 * class MainActivity : FragmentActivity() {
 *
 *     // [...]
 *
 *     private fun handleIntent(intent: Intent) {
 *         if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
 *             val device = IntentCompat.getParcelableExtra(
 *                 intent,
 *                 UsbManager.EXTRA_DEVICE,
 *                 UsbDevice::class.java
 *             )
 *             if (device != null) {
 *                 lifecycle.coroutineScope.launch {
 *                     val app = App.getInstance()
 *                     app.initialize()
 *                     app.externalNfcReaderStore.handleUsbDeviceAttached(device)
 *                 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param device a [UsbDevice] received from handling the [UsbManager.ACTION_USB_DEVICE_ATTACHED] intent.
 * @return a [ExternalNfcReaderUsb] representing [device], either newly added or previously added.
 */
suspend fun ExternalNfcReaderStore.handleUsbDeviceAttached(device: UsbDevice): ExternalNfcReaderUsb {
    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    val connection: UsbDeviceConnection? = try {
        if (usbManager.hasPermission(device)) usbManager.openDevice(device) else null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
    val rawDescriptors = try {
        connection?.rawDescriptors
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    } finally {
        try {
            connection?.close()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    val interfaces = (0 until device.interfaceCount).map { idx ->
        val iface = device.getInterface(idx)
        val ifaceName = try {
            iface.name
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        UsbInterfaceInfo(
            id = iface.id,
            interfaceClass = iface.interfaceClass,
            name = ifaceName
        )
    }

    val deviceInfo = UsbDeviceInfo(
        vendorId = device.vendorId,
        productId = device.productId,
        manufacturerName = device.manufacturerName,
        productName = device.productName,
        interfaces = interfaces,
        rawDescriptors = rawDescriptors
    )

    return handleUsbDeviceAttached(deviceInfo)
}
