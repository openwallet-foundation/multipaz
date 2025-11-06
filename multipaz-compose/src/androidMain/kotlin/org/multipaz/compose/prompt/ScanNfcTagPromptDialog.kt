package org.multipaz.compose.prompt

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.multipaz.context.getActivity
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.NfcIsoTagAndroid
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.NfcTagReaderModalBottomSheet
import org.multipaz.prompt.NfcDialogParameters
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.ScanNfcTagDialogIcon
import org.multipaz.prompt.SinglePromptModel
import org.multipaz.util.Logger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.compose.R
import org.multipaz.util.fromHex
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

const val TAG = "ScanNfcTagPromptDialog"

/**
 * Displays NFC prompt dialog in Composable UI environment and runs NFC interactions.
 */
@Composable
internal fun ScanNfcTagPromptDialog(model: SinglePromptModel<NfcDialogParameters<Any>, Any?>) {
    val dialogState = model.dialogState.collectAsState(SinglePromptModel.NoDialogState())
    when (val dialogStateValue = dialogState.value) {
        is SinglePromptModel.NoDialogState -> if (!dialogStateValue.initial) {
            val activity = LocalContext.current.getActivity() as FragmentActivity
            LaunchedEffect(dialogStateValue) {
                val adapter = NfcAdapter.getDefaultAdapter(activity)
                if (adapter != null) {
                    Logger.i(TAG, "NFC: NFC dialog dismissed, waiting to disable reader mode")
                    delay(3.seconds)  // Dialog is dismissed 2 seconds after successful NFC scan
                    Logger.i(TAG, "NFC: reader mode disabled")
                    adapter.disableReaderMode(activity)
                }
            }
        }
        is SinglePromptModel.DialogShownState -> ScanNfcTagPromptDialogShown(dialogStateValue)
    }
}

@Composable
private fun ScanNfcTagPromptDialogShown(
    dialogStateValue: SinglePromptModel.DialogShownState<NfcDialogParameters<Any>, Any?>
) {
    val coroutineScope = rememberCoroutineScope()
    val icon = remember { MutableStateFlow(ScanNfcTagDialogIcon.READY_TO_SCAN) }
    val message = remember { MutableStateFlow(dialogStateValue.parameters.initialMessage) }
    val iconId = when (icon.collectAsState().value) {
        ScanNfcTagDialogIcon.READY_TO_SCAN -> R.drawable.nfc_tag_reader_icon_scan
        ScanNfcTagDialogIcon.SUCCESS -> R.drawable.nfc_tag_reader_icon_success
        ScanNfcTagDialogIcon.ERROR -> R.drawable.nfc_tag_reader_icon_error
    }
    val messageText = message.collectAsState().value
    if (messageText != null) {
        NfcTagReaderModalBottomSheet(
            dialogMessage = messageText,
            dialogIconPainter = painterResource(iconId),
            onDismissed = {
                coroutineScope.launch {
                    // This will dismiss the dialog and cancel LaunchedEffect below.
                    dialogStateValue.resultChannel.close(PromptDismissedException())
                }
            }
        )
    }
    val activity = LocalContext.current.getActivity() as FragmentActivity
    LaunchedEffect(dialogStateValue) {
        Logger.i(TAG, "NFC: NFC dialog is shown")
        val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) {
            dialogStateValue.resultChannel.close(
                IllegalStateException("NFC is not supported on this device"))
            return@LaunchedEffect
        }
        try {
            val result = suspendCancellableCoroutine { continuation ->
                val nfcCallback = NfcReaderCallback(
                    initialMessage = dialogStateValue.parameters.initialMessage,
                    tagInteractionFunc = dialogStateValue.parameters.interactionFunction,
                    dialogMessage = message,
                    continuation = continuation,
                    context = dialogStateValue.parameters.context
                )
                val extras = dialogStateValue.parameters.options.pollingFrameData?.let {
                    val extras = Bundle()
                    extras.putByteArray("android.nfc.extra.READER_TECH_A_POLLING_LOOP_ANNOTATION", it.toByteArray())
                    extras
                }
                adapter.enableReaderMode(
                    activity,
                    nfcCallback,
                    NfcAdapter.FLAG_READER_NFC_A + NfcAdapter.FLAG_READER_NFC_B
                            + NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK + NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    extras
                )
            }
            dialogStateValue.resultChannel.send(result)
            icon.value = ScanNfcTagDialogIcon.SUCCESS
            vibrateSuccess(activity)
            Logger.i(TAG, "Successful NFC scan")
        } catch (e: CancellationException) {
            // coroutine cancelled
            Logger.i(TAG, "NFC Dialog cancelled")
            dialogStateValue.resultChannel.close(PromptDismissedException())
            // coroutine cancellation should not be swallowed
            throw e
        } catch (e: Throwable) {
            Logger.e(TAG, "Error scanning", e)
            icon.value = ScanNfcTagDialogIcon.ERROR
            message.value = e.message ?: e.toString()
            vibrateError(activity)
            // route exception to the caller, don't rethrow it in launched coroutine
            dialogStateValue.resultChannel.close(e)
        }
    }
}

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

@OptIn(ExperimentalCoroutinesApi::class)
class NfcReaderCallback<T>(
    val initialMessage: String?,
    val tagInteractionFunc: suspend (tag: NfcIsoTag) -> T?,
    val dialogMessage: MutableStateFlow<String?>,
    val continuation: CancellableContinuation<T>,
    val context: CoroutineContext
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
                            updateMessage = { message ->
                                Logger.i(TAG, "NFC dialog message: $message")
                                if (initialMessage != null) {
                                    dialogMessage.value = message
                                }
                            }
                        )
                        tagInteractionFunc(isoTag)
                    }
                    Logger.i(TAG, "Tag processed")
                    if (ret == null) {
                        // Keep on listening
                        dialogMessage.value = initialMessage
                    } else {
                        continuation.resume(ret, null)
                    }
                } catch (e: NfcTagLostException) {
                    // This is to to properly handle emulated tags - such as on Android - which may be showing
                    // disambiguation UI if multiple applications have registered for the same AID.
                    dialogMessage.value = initialMessage
                    Logger.w(TAG, "Tag lost", e)
                } catch (e: Throwable) {
                    Logger.e(TAG, "Error in interaction func", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    }
}
