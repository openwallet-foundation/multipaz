package org.multipaz.nfc

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcTagReaderUsb"

private class NfcIsoTagUsb(
    private val driver: CcidDriver,
    private val updateMessage: (message: String) -> Unit,
    private val onClose: () -> Unit,
): NfcIsoTag() {
    @Volatile
    var isClosed: Boolean = false
        private set

    fun resetClosed() {
        isClosed = false
    }

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
        isClosed = true
        onClose()
    }

    override suspend fun updateDialogMessage(message: String) {
        updateMessage(message)
    }

}

internal class NfcTagReaderUsb(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val interfaceIndex: Int,
): NfcTagReader {
    private var activeDriver: CcidDriver? = null
    private var delayedDisconnectJob: Job? = null

    override val external: Boolean
        get() = true

    override val dialogAlwaysShown: Boolean
        get() = false

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
        delayedDisconnectJob?.cancel()
        delayedDisconnectJob = null
        activeDriver?.disconnect()
        activeDriver = null

        val driver = CcidDriver(
            usbManager = manager,
            device = device,
            interfaceIndex = interfaceIndex,
            pollingFrameData = options.pollingFrameData?.toByteArray()
        )
        driver.connect()
        activeDriver = driver
        val scanCompleted = AtomicBoolean(false)
        var keepDriverConnectedUntilTagClosed = false
        var activeTag: NfcIsoTagUsb? = null
        try {
            return scanNfcWithPromptDialog(message) { updateMessage, resetMessage ->
                suspendCancellableCoroutine { continuation ->
                    val stateLock = Any()
                    var readJob: Job? = null
                    val isCardPresent = AtomicBoolean(false)

                    val listener = object : CcidDriverListener {
                        override fun onCardInserted() {
                            Logger.i(TAG, "Card inserted")
                            synchronized(stateLock) {
                                isCardPresent.set(true)
                                if (readJob != null) return
                                readJob = CoroutineScope(context).launch {
                                    val tag = NfcIsoTagUsb(
                                        driver = driver,
                                        updateMessage = updateMessage,
                                        onClose = {
                                            if (scanCompleted.get()) {
                                                delayedDisconnectJob?.cancel()
                                                delayedDisconnectJob = null
                                                if (activeDriver === driver) {
                                                    activeDriver = null
                                                }
                                                driver.disconnect()
                                            }
                                        }
                                    )
                                    activeTag = tag
                                    try {
                                        while (continuation.isActive && isCardPresent.get()) {
                                            tag.resetClosed()
                                            val funcResult = tagInteractionFunc(tag)
                                            if (funcResult != null) {
                                                keepDriverConnectedUntilTagClosed = !tag.isClosed
                                                if (continuation.isActive) {
                                                    continuation.resume(funcResult)
                                                }
                                                break
                                            }
                                            resetMessage()
                                            // Keep the RF field on briefly while Android's PollingFrameHandler
                                            // finishes disabling Observe Mode, then power-cycle the slot so a fresh
                                            // RATS/ATTRIB triggers ISO-DEP activation on the phone.
                                            delay(200.milliseconds)
                                            if (!continuation.isActive || !isCardPresent.get()) break
                                            if (!driver.powerCycleCard()) {
                                                delay(150.milliseconds)
                                            }
                                        }
                                    } catch (e: NfcTagLostException) {
                                        // This is to properly handle emulated tags - such as on Android - which may be showing
                                        // disambiguation UI if multiple applications have registered for the same AID.
                                        resetMessage()
                                        Logger.w(TAG, "Tag lost", e)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(e)
                                        }
                                    } finally {
                                        synchronized(stateLock) {
                                            readJob = null
                                        }
                                    }
                                }
                            }
                        }

                        override fun onCardRemoved() {
                            Logger.i(TAG, "Card removed")
                            synchronized(stateLock) {
                                isCardPresent.set(false)
                            }
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
                        if (activeDriver === driver) {
                            activeDriver = null
                        }
                        driver.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        } finally {
            scanCompleted.set(true)
            driver.setListener(null)
            if (!keepDriverConnectedUntilTagClosed || activeTag?.isClosed == true) {
                if (activeDriver === driver) {
                    activeDriver = null
                }
                driver.disconnect()
            } else {
                delayedDisconnectJob = CoroutineScope(Dispatchers.Default).launch {
                    delay(30.seconds)
                    if (activeDriver === driver) {
                        activeDriver = null
                    }
                    driver.disconnect()
                }
            }
        }
    }
}
