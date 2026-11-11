package org.multipaz.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.multipaz.util.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ExternalNfcReaderStoreJvm"

/**
 * Monitors attached USB devices on Desktop JVM and automatically adds newly attached
 * USB CCID (`bInterfaceClass == 0x0B`) NFC readers to this [ExternalNfcReaderStore].
 *
 * Keeps running until the calling coroutine is cancelled.
 *
 * @param pollInterval how frequently to scan for newly attached USB CCID devices.
 */
suspend fun ExternalNfcReaderStore.observeUsbDevices(
    pollInterval: Duration = 1.seconds
) {
    val seenDeviceKeys = mutableSetOf<String>()
    while (currentCoroutineContext().isActive) {
        try {
            val snapshots = withContext(Dispatchers.IO) {
                JvmUsbEnumerator.enumerateDevices()
            }
            val currentKeys = mutableSetOf<String>()
            for (snapshot in snapshots) {
                val info = snapshot.deviceInfo
                val hasCcidInterface = info.interfaces.any {
                    it.interfaceClass == CcidUsbTransport.USB_CLASS_CSCID
                }
                if (!hasCcidInterface) continue
                val key = "${snapshot.busNumber}:${snapshot.deviceAddress}:${info.vendorId}:${info.productId}"
                currentKeys.add(key)
                if (seenDeviceKeys.add(key)) {
                    handleUsbDeviceAttached(info)
                }
            }
            seenDeviceKeys.retainAll(currentKeys)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Error polling USB CCID devices", e)
        }
        delay(pollInterval)
    }
}
