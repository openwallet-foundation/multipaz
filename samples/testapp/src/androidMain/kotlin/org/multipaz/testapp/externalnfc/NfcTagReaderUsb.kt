package org.multipaz.testapp.externalnfc

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.context.applicationContext
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagReader
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Logger
import kotlin.collections.iterator
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

private const val TAG = "NfcTagReaderUsb"

private class NfcIsoTagUsb(
    private val driver: CcidDriver,
): NfcIsoTag() {
    override val maxTransceiveLength: Int
        get() = 0xfeff  // TODO

    override suspend fun transceive(command: CommandApdu): ResponseApdu {
        val commandApduBytes = command.encode()
        //Logger.iHex(TAG, "Sending APDU", commandApduBytes)
        val responseApduBytes = driver.transceive(commandApduBytes)
        //Logger.iHex(TAG, "Received APDU", responseApduBytes)
        return ResponseApdu.decode(responseApduBytes)
    }

    override suspend fun close() {
        driver.disconnect()
    }

    override suspend fun updateDialogMessage(message: String) {
    }

}

class NfcTagReaderUsb(
    private val manager: UsbManager,
    private val device: UsbDevice,
): NfcTagReader {
    val readerName: String
        get() {
            val sb = StringBuilder()
            device.manufacturerName?.let {
                sb.append(it)
                sb.append(" ")
            }
            device.productName?.let {
                sb.append(it)
            }
            return sb.toString()
        }

    override val external: Boolean
        get() = true

    override val dialogAlwaysShown: Boolean
        get() = false

    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        val driver = CcidDriver(
            usbManager = manager,
            device = device
        )
        driver.connect()
        val result = suspendCancellableCoroutine<T> { continuation ->
            var readJob: Job? = null

            driver.setListener(listener = object: CcidDriverListener {
                override fun onCardInserted() {
                    Logger.i(TAG, "Card inserted")
                    if (readJob != null) {
                        Logger.w(TAG, "job already active?")
                    } else {
                        readJob = CoroutineScope(context).launch {
                            val tag = NfcIsoTagUsb(driver = driver)
                            val funcResult = tagInteractionFunc(tag)
                            if (funcResult != null) {
                                continuation.resume(funcResult)
                            }
                        }
                    }
                }

                override fun onCardRemoved() {
                    Logger.i(TAG, "Card removed")
                    readJob?.cancel()
                    readJob = null
                }
            })

            continuation.invokeOnCancellation {
                Logger.i(TAG, "Was cancelled")
                readJob?.cancel()
                driver.disconnect()
            }
        }
        driver.disconnect()
        Logger.i(TAG, "Returning $result")
        return result
    }
}

@SuppressLint("WrongConstant")
suspend fun nfcTagReaderUsbCheck(): NfcTagReaderUsb? {
    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    for ((_, device) in usbManager.deviceList) {
        // vid=0x072f, pid=0x223b: https://www.acs.com.hk/en/products/342/acr1252u-usb-nfc-reader-iii-nfc-forum-certified-reader/
        // vid=0x072f, pid=0x2401: https://www.acs.com.hk/en/products/641/walletmate-ii-mobile-wallet-nfc-reader-apple-vas-google-smart-tap-certified/
        if (device.vendorId == 0x072f && (device.productId == 0x223b || device.productId == 0x2401)) {
            if (!usbManager.hasPermission(device)) {
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    flags = flags or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                }
                val pendingIntent = PendingIntentCompat.getBroadcast(
                    /* context = */ applicationContext,
                    /* requestCode = */ 0,
                    /* intent = */ Intent("com.android.example.USB_PERMISSION"),
                    /* flags = */ flags,
                    /* isMutable = */ true
                )
                usbManager.requestPermission(device, pendingIntent)
            }
            return NfcTagReaderUsb(usbManager, device)
        }
    }

    return null
}