package org.multipaz.nfc

import kotlinx.coroutines.CancellationException
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.util.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "NfcTagReaderUsb"

private class NfcIsoTagUsb(
    private val driver: CcidDriver,
): NfcIsoTag() {
    override val maxTransceiveLength: Int
        get() = 0xfeff

    override suspend fun transceive(command: CommandApdu): ResponseApdu {
        val commandApduBytes = command.encode()
        //Logger.iHex(TAG, "Sending APDU", commandApduBytes)
        try {
            val responseApduBytes = driver.transceive(commandApduBytes)
            //Logger.iHex(TAG, "Received APDU", responseApduBytes)
            return ResponseApdu.decode(responseApduBytes)
        } catch (e: CcidException) {
            throw NfcTagLostException("Tag was lost", e)
        }
    }

    override suspend fun close() {
        driver.disconnect()
    }

    override suspend fun updateDialogMessage(message: String) {
    }

}

internal class NfcTagReaderUsb(
    private val manager: UsbManager,
    private val device: UsbDevice,
): NfcTagReader {
    override val external: Boolean
        get() = true

    override val dialogAlwaysShown: Boolean
        get() = false

    override val dialogNeverShown: Boolean
        get() = true

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
        try {
            val result = suspendCancellableCoroutine<T> { continuation ->
                var readJob: Job? = null

                driver.setListener(listener = object : CcidDriverListener {
                    override fun onCardInserted() {
                        Logger.i(TAG, "Card inserted")
                        if (readJob == null) {
                            readJob = CoroutineScope(context).launch {
                                val tag = NfcIsoTagUsb(driver = driver)
                                try {
                                    val funcResult = tagInteractionFunc(tag)
                                    if (funcResult != null) {
                                        continuation.resume(funcResult)
                                    }
                                } catch (e: NfcTagLostException) {
                                    // This is to properly handle emulated tags - such as on Android - which may be showing
                                    // disambiguation UI if multiple applications have registered for the same AID.
                                    Logger.w(TAG, "Tag lost", e)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    continuation.resumeWithException(e)
                                }
                                readJob = null
                            }
                        }
                    }

                    override fun onCardRemoved() {
                        Logger.i(TAG, "Card removed")
                    }
                })

                continuation.invokeOnCancellation {
                    readJob?.cancel()
                    driver.disconnect()
                }
            }
            driver.disconnect()
            return result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            driver.disconnect()
            throw e
        }
    }
}
