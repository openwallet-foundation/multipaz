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
        get() = driver.maxCommandLength

    override suspend fun transceive(command: CommandApdu): ResponseApdu {
        val commandApduBytes = command.encode()
        try {
            val responseApduBytes = driver.transceive(commandApduBytes)
            return ResponseApdu.decode(responseApduBytes)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw NfcTagLostException("Tag was lost", e)
        }
    }

    override suspend fun close() {
        // No-op for the reader driver itself; the driver remains connected for future card insertions.
    }

    override suspend fun updateDialogMessage(message: String) {
    }

}

internal class NfcTagReaderUsb(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val interfaceIndex: Int,
): NfcTagReader {
    override val external: Boolean
        get() = true

    override val dialogAlwaysShown: Boolean
        get() = false

    override val dialogNeverShown: Boolean
        get() = true

    /**
     * Scans for an NFC tag using the USB CCID reader and executes [tagInteractionFunc].
     *
     * @param message Optional message to show during scanning.
     * @param tagInteractionFunc The interaction function to execute when a tag is detected.
     * @param options Options for NFC scanning.
     * @param context The coroutine context for execution.
     * @return The result of [tagInteractionFunc].
     * @throws NfcTagLostException if the tag is lost during transaction.
     * @throws IOException if a USB or CCID communication error occurs.
     * @throws SecurityException if USB permission is denied.
     */
    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        val driver = CcidDriver(
            usbManager = manager,
            device = device,
            interfaceIndex = interfaceIndex
        )
        driver.connect()
        try {
            val result = suspendCancellableCoroutine<T> { continuation ->
                var readJob: Job? = null

                val listener = object : CcidDriverListener {
                    override fun onCardInserted() {
                        Logger.i(TAG, "Card inserted")
                        if (readJob == null) {
                            readJob = CoroutineScope(context).launch {
                                val tag = NfcIsoTagUsb(driver = driver)
                                try {
                                    val funcResult = tagInteractionFunc(tag)
                                    if (funcResult != null) {
                                        if (continuation.isActive) {
                                            continuation.resume(funcResult)
                                        }
                                    }
                                } catch (e: NfcTagLostException) {
                                    // This is to properly handle emulated tags - such as on Android - which may be showing
                                    // disambiguation UI if multiple applications have registered for the same AID.
                                    Logger.w(TAG, "Tag lost", e)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(e)
                                    }
                                }
                                readJob = null
                            }
                        }
                    }

                    override fun onCardRemoved() {
                        Logger.i(TAG, "Card removed")
                    }
                }

                driver.setListener(listener = listener)

                try {
                    val status = driver.getCardStatus()
                    if (status == CardStatus.PRESENT_ACTIVE || status == CardStatus.PRESENT_INACTIVE) {
                        listener.onCardInserted()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Failed to query initial card status", e)
                }

                continuation.invokeOnCancellation {
                    readJob?.cancel()
                    driver.disconnect()
                }
            }
            return result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        } finally {
            driver.setListener(null)
            driver.disconnect()
        }
    }
}
