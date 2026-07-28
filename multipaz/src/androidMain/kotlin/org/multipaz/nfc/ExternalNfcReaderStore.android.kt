package org.multipaz.nfc

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import org.multipaz.context.applicationContext
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

    // If at least one interface for this USB device (VID/PID) already exists in the store,
    // skip examining/adding any new interfaces for this device. When a multi-interface CCID
    // reader is plugged in for the first time, all candidate NFC CCID interfaces are added to the store
    // so the user can manually delete any non-NFC interfaces (e.g. SAM or contact card slots)
    // from the UI. Preserving existing entries allows the user's manual deletions to persist
    // when the device is re-plugged.
    val existingReaderForDevice = this.readers.value.find { externalNfcReader ->
        (externalNfcReader as? ExternalNfcReaderUsb)?.let {
            it.vendorId == device.vendorId && it.productId == device.productId
        } ?: false
    }
    if (existingReaderForDevice != null) {
        Logger.i(TAG, "Device vid=${device.vendorId} pid=${device.productId} already has interface(s) configured in store, skipping auto-adding interfaces.")
        return existingReaderForDevice as ExternalNfcReaderUsb
    }

    val ccidInterfaces = (0 until device.interfaceCount)
        .map { device.getInterface(it) }
        .filter { it.interfaceClass == UsbConstants.USB_CLASS_CSCID }

    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    val connection: UsbDeviceConnection? = try {
        if (usbManager.hasPermission(device)) usbManager.openDevice(device) else null
    } catch (_: Throwable) { null }
    val rawDescriptors = try { connection?.rawDescriptors } catch (_: Throwable) { null }
    try { connection?.close() } catch (_: Throwable) {}

    val candidateNfcInterfaces = ccidInterfaces.filter { !isDefinitelyNotNfc(it, rawDescriptors) }
    val targetInterfaces = when {
        candidateNfcInterfaces.isNotEmpty() -> candidateNfcInterfaces
        ccidInterfaces.isNotEmpty() -> ccidInterfaces
        else -> listOf(device.getInterface(0))
    }
    var lastAddedReader: ExternalNfcReaderUsb? = null

    val format = HexFormat {
        number.prefix = "0x"
        number.minLength = 2
    }

    for (iface in targetInterfaces) {
        val ifaceIndex = iface.id

        val nameSuffix = if (targetInterfaces.size > 1) {
            val ifaceName = try { iface.name?.trim() ?: "" } catch (_: Throwable) { "" }
            if (ifaceName.isNotEmpty()) " ($ifaceName)" else " (Interface ${iface.id})"
        } else ""
        val displayName = "$deviceDisplayName$nameSuffix"

        Logger.i(
            TAG, "Adding USB-connected external NFC reader '$displayName' " +
                    "vid ${device.vendorId.toHexString(format)} pid ${device.productId.toHexString(format)} " +
                    "interfaceIndex $ifaceIndex to persistent ExternalNfcReaderStore")

        val reader = this.addUsbReader(
            displayName = displayName,
            vendorId = device.vendorId,
            productId = device.productId,
            interfaceIndex = ifaceIndex
        )
        lastAddedReader = reader
    }

    return lastAddedReader!!
}

private fun isDefinitelyNotNfc(iface: UsbInterface, rawDescriptors: ByteArray?): Boolean {
    val ifaceName = try { iface.name?.lowercase()?.trim() ?: "" } catch (_: Throwable) { "" }
    if (ifaceName.contains("sam") || ifaceName.contains("sim")) {
        return true
    }
    if (ifaceName.contains("contact") && !ifaceName.contains("contactless")) {
        return true
    }
    if (rawDescriptors != null) {
        val ccidDesc = getCcidDescriptorForInterface(rawDescriptors, iface.id)
        if (ccidDesc != null && ccidDesc.size >= 40) {
            val dwMechanical = (ccidDesc[36].toInt() and 0xFF) or
                    ((ccidDesc[37].toInt() and 0xFF) shl 8) or
                    ((ccidDesc[38].toInt() and 0xFF) shl 16) or
                    ((ccidDesc[39].toInt() and 0xFF) shl 24)
            if (dwMechanical != 0) {
                return true
            }
        }
    }
    return false
}

private fun getCcidDescriptorForInterface(raw: ByteArray, targetIfaceNum: Int): ByteArray? {
    var idx = 0
    var currentIfaceNum = -1
    while (idx < raw.size - 2) {
        val len = raw[idx].toInt() and 0xFF
        val type = raw[idx + 1].toInt() and 0xFF
        if (len <= 0) break
        if (type == 0x04 && len >= 9 && idx + 9 <= raw.size) {
            currentIfaceNum = raw[idx + 2].toInt() and 0xFF
        } else if (type == 0x21 && currentIfaceNum == targetIfaceNum && len >= 0x36 && idx + len <= raw.size) {
            return raw.copyOfRange(idx, idx + len)
        }
        idx += len
    }
    return null
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