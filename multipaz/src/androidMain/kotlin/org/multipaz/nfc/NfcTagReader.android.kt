package org.multipaz.nfc

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.context.AndroidUiContext
import org.multipaz.context.applicationContext
import org.multipaz.context.getActivity
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.PromptUiNotAvailableException
import org.multipaz.prompt.ScanNfcPromptDialogModel
import org.multipaz.prompt.ScanNfcTagDialogIcon
import org.multipaz.util.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcTagReaderAndroid"

private fun vibrate(context: Context, pattern: List<Int>) {
    val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
    val vibrationEffect = VibrationEffect.createWaveform(pattern.map { it.toLong() }.toLongArray(), -1)
    vibrator?.vibrate(vibrationEffect)
}

private fun vibrateError(context: Context) {
    vibrate(context, listOf(0, 500))
}

private fun vibrateSuccess(context: Context) {
    vibrate(context, listOf(0, 100, 50, 100))
}

internal suspend fun <T : Any> scanNfcWithPromptDialog(
    message: String?,
    block: suspend (updateMessage: (String) -> Unit, resetMessage: () -> Unit) -> T
): T {
    val dialogMessage = MutableStateFlow(message ?: "")
    val dialogIcon = MutableStateFlow(ScanNfcTagDialogIcon.READY_TO_SCAN)
    val updateMessage: (String) -> Unit = { newMessage ->
        Logger.i(TAG, "NFC dialog message: $newMessage")
        if (message != null) {
            dialogMessage.value = newMessage
        }
    }
    val resetMessage: () -> Unit = {
        if (message != null) {
            dialogMessage.value = message
        }
    }

    val dialogModel = if (message != null) {
        try {
            PromptModel.get().getDialogModel(ScanNfcPromptDialogModel.DialogType)
        } catch (_: PromptModelNotAvailableException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    } else {
        null
    }

    if (dialogModel == null) {
        return try {
            val result = block(updateMessage, resetMessage)
            vibrateSuccess(applicationContext)
            Logger.i(TAG, "Successful NFC scan")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error scanning", e)
            vibrateError(applicationContext)
            throw e
        }
    }

    return coroutineScope {
        val parameters = ScanNfcPromptDialogModel.NfcDialogParameters(
            message = dialogMessage.asStateFlow(),
            icon = dialogIcon.asStateFlow()
        )
        var dismissedByUser: PromptDismissedException? = null
        val promptJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                dialogModel.displayPrompt(
                    parameters = parameters,
                    lingerDuration = 2.seconds
                )
            } catch (_: PromptUiNotAvailableException) {
                // UI is not bound; continue scanning without showing a prompt dialog.
            } catch (e: PromptDismissedException) {
                dismissedByUser = e
                this@coroutineScope.cancel(CancellationException("Prompt dismissed by user", e))
            }
        }

        try {
            val result = block(updateMessage, resetMessage)
            dialogIcon.value = ScanNfcTagDialogIcon.SUCCESS
            vibrateSuccess(applicationContext)
            Logger.i(TAG, "Successful NFC scan")
            if (promptJob.isActive) {
                dialogModel.dismissAfterLinger(parameters)
                promptJob.join()
            }
            result
        } catch (e: CancellationException) {
            Logger.i(TAG, "NFC scan cancelled")
            promptJob.cancel()
            dismissedByUser?.let { throw it }
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error scanning", e)
            dialogIcon.value = ScanNfcTagDialogIcon.ERROR
            dialogMessage.value = e.message ?: e.toString()
            vibrateError(applicationContext)
            if (promptJob.isActive) {
                dialogModel.dismissAfterLinger(parameters)
                promptJob.join()
            }
            throw e
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class NfcReaderCallback<T>(
    private val tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
    private val updateMessage: (String) -> Unit,
    private val resetMessage: () -> Unit,
    private val continuation: CancellableContinuation<T>,
    private val context: CoroutineContext
) : NfcAdapter.ReaderCallback {
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) {
            Logger.e(TAG, "onTagDiscovered called with null tag")
            return
        }
        for (tech in tag.techList) {
            if (tech == IsoDep::class.java.name) {
                val isoDep = IsoDep.get(tag)!!
                isoDep.connect()
                isoDep.timeout = 20.seconds.inWholeMilliseconds.toInt()
                try {
                    // Note: onTagDiscovered() is called in a dedicated thread and we're not supposed
                    // to return until we're done interrogating the tag.
                    val ret = runBlocking {
                        Logger.i(TAG, "maxTransceiveLength: ${isoDep.maxTransceiveLength}")
                        val isoTag = NfcIsoTagAndroid(
                            tag = isoDep,
                            context = context,
                            updateMessage = updateMessage
                        )
                        tagInteractionFunc(isoTag)
                    }
                    Logger.i(TAG, "Tag processed")
                    if (ret == null) {
                        // Keep on listening
                        resetMessage()
                    } else {
                        if (continuation.isActive) {
                            continuation.resume(ret, null)
                        }
                    }
                } catch (e: NfcTagLostException) {
                    // This is to properly handle emulated tags - such as on Android - which may be showing
                    // disambiguation UI if multiple applications have registered for the same AID.
                    resetMessage()
                    Logger.w(TAG, "Tag lost", e)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Error in interaction func", e)
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
    }
}

private object NfcTagReaderAndroid: NfcTagReader {
    private var disableReaderModeJob: Job? = null

    override val external: Boolean
        get() = false

    override val dialogAlwaysShown: Boolean
        get() = false

    override suspend fun <T : Any> scan(
        message: String?,
        tagInteractionFunc: suspend (NfcIsoTag) -> T?,
        options: NfcScanOptions,
        context: CoroutineContext
    ): T {
        val activity = AndroidUiContext.current().getActivity()
            ?: throw IllegalStateException("Context in AndroidUiContext is not an Activity")
        val adapter = NfcAdapter.getDefaultAdapter(activity)
            ?: throw IllegalStateException("NFC is not supported on this device")

        return scanNfcWithPromptDialog(message) { updateMessage, resetMessage ->
            disableReaderModeJob?.cancel()
            disableReaderModeJob = null
            try {
                suspendCancellableCoroutine { continuation ->
                    val nfcCallback = NfcReaderCallback(
                        tagInteractionFunc = tagInteractionFunc,
                        updateMessage = updateMessage,
                        resetMessage = resetMessage,
                        continuation = continuation,
                        context = context
                    )
                    val extras = options.pollingFrameData?.let {
                        val bundle = Bundle()
                        bundle.putByteArray("android.nfc.extra.READER_TECH_A_POLLING_LOOP_ANNOTATION", it.toByteArray())
                        bundle
                    }
                    adapter.enableReaderMode(
                        activity,
                        nfcCallback,
                        NfcAdapter.FLAG_READER_NFC_A + NfcAdapter.FLAG_READER_NFC_B
                                + NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK + NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                        extras
                    )
                }
            } finally {
                disableReaderModeJob = CoroutineScope(Dispatchers.Default).launch {
                    Logger.i(TAG, "NFC: waiting to disable reader mode")
                    delay(5.seconds)
                    Logger.i(TAG, "NFC: reader mode disabled")
                    adapter.disableReaderMode(activity)
                    disableReaderModeJob = null
                }
            }
        }
    }
}

internal actual fun nfcGetPlatformReaders(): List<NfcTagReader> {
    return if (applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
        listOf(NfcTagReaderAndroid)
    } else {
        emptyList()
    }
}
