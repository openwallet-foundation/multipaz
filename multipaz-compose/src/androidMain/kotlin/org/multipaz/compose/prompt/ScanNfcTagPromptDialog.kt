package org.multipaz.compose.prompt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import org.multipaz.compose.R
import org.multipaz.nfc.NfcTagReaderModalBottomSheet
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.ScanNfcPromptDialogModel
import org.multipaz.prompt.ScanNfcTagDialogIcon

/**
 * Displays NFC prompt dialog in Composable UI environment.
 */
@Composable
internal fun ScanNfcTagPromptDialog(
    model: PromptDialogModel<ScanNfcPromptDialogModel.NfcDialogParameters, Unit>
) {
    val dialogState = model.dialogState.collectAsState(PromptDialogModel.NoDialogState())
    val coroutineScope = rememberCoroutineScope()
    val dialogStateValue = dialogState.value
    if (dialogStateValue is PromptDialogModel.DialogShownState) {
        val icon = dialogStateValue.parameters.icon.collectAsState().value
        val messageText = dialogStateValue.parameters.message.collectAsState().value
        val iconId = when (icon) {
            ScanNfcTagDialogIcon.READY_TO_SCAN -> R.drawable.nfc_tag_reader_icon_scan
            ScanNfcTagDialogIcon.SUCCESS -> R.drawable.nfc_tag_reader_icon_success
            ScanNfcTagDialogIcon.ERROR -> R.drawable.nfc_tag_reader_icon_error
        }
        NfcTagReaderModalBottomSheet(
            dialogMessage = messageText,
            dialogIconPainter = painterResource(iconId),
            onDismissed = {
                coroutineScope.launch {
                    dialogStateValue.resultChannel.close(PromptDismissedException())
                }
            }
        )
    }
}

