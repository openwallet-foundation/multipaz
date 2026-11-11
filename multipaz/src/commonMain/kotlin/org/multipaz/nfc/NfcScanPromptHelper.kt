package org.multipaz.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.multipaz.mdoc.nfc.vibrateMdocNfcError
import org.multipaz.mdoc.nfc.vibrateMdocNfcSuccess
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.PromptUiNotAvailableException
import org.multipaz.prompt.ScanNfcPromptDialogModel
import org.multipaz.prompt.ScanNfcTagDialogIcon
import org.multipaz.util.Logger
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NfcScanPromptHelper"

/**
 * Runs an NFC tag scanning [block] while managing the [ScanNfcPromptDialogModel] prompt UI
 * (if [message] is non-null and a [PromptModel] with bound UI is present in the coroutine context)
 * and platform haptic feedback.
 *
 * @param message initial message to show in the scan dialog, or `null` to scan without a dialog.
 * @param block scanning block receiving `updateMessage` and `resetMessage` callbacks.
 * @return the result returned by [block].
 * @throws PromptDismissedException if the user dismisses the scan dialog.
 * @throws CancellationException if the calling coroutine is cancelled.
 */
@Throws(PromptDismissedException::class, CancellationException::class)
suspend fun <T : Any> scanNfcWithPromptDialog(
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
            vibrateMdocNfcSuccess()
            Logger.i(TAG, "Successful NFC scan")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error scanning", e)
            vibrateMdocNfcError()
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
            vibrateMdocNfcSuccess()
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
            vibrateMdocNfcError()
            if (promptJob.isActive) {
                dialogModel.dismissAfterLinger(parameters)
                promptJob.join()
            }
            throw e
        }
    }
}
