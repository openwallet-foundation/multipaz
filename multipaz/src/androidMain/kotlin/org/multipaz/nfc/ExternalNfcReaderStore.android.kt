package org.multipaz.nfc

import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import org.multipaz.util.Logger

private const val TAG = "ExternalNfcReaderStore"

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
    val deviceDisplayName = device.getNiceDisplayName()
    Logger.d(TAG, "USB Device attached: $deviceDisplayName")
    val existingReader = this.readers.value.find { externalNfcReader ->
        (externalNfcReader as? ExternalNfcReaderUsb)?.let {
            if (it.vendorId == device.vendorId && it.productId == device.productId) true else false
        } ?: false
    }
    if (existingReader != null) {
        return existingReader as ExternalNfcReaderUsb
    }
    val format = HexFormat {
        number.prefix = "0x"
        number.minLength = 2
    }
    Logger.i(
        TAG, "Adding USB-connected external NFC reader '$deviceDisplayName' " +
                "vid ${device.vendorId.toHexString(format)} pid ${device.productId.toHexString(format)} " +
                "to persistent ExternalNfcReaderStore")
    return this.addUsbReader(
        displayName = deviceDisplayName,
        vendorId = device.vendorId,
        productId = device.productId
    )
}

private fun UsbDevice.getNiceDisplayName(): String {
    val manufacturer = this.manufacturerName?.trim()
    val product = this.productName?.trim()
    return when {
        // Ideal scenario: "MyManufacturer MyProduct"
        !manufacturer.isNullOrBlank() && !product.isNullOrBlank() -> {
            // Prevent repeating the manufacturer name if the product name already includes it
            if (product.startsWith(manufacturer, ignoreCase = true)) {
                product
            } else {
                "$manufacturer $product"
            }
        }

        // Fallback 1: Just the product name ("MyProduct")
        !product.isNullOrBlank() -> product

        // Fallback 2: Just the manufacturer ("MyManufacturer")
        !manufacturer.isNullOrBlank() -> manufacturer

        // Fallback 3: Hardware IDs formatted in standard Hex ("Unknown USB Device (VID: 0781, PID: 5571)")
        else -> {
            val vidHex = String.format("%04X", this.vendorId)
            val pidHex = String.format("%04X", this.productId)
            "Unknown USB Device (VID: $vidHex, PID: $pidHex)"
        }
    }
}