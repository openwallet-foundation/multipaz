package org.multipaz.compose.prompt

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.multipaz.compose.presentment.ConsentModalBottomSheet
import org.multipaz.presentment.CredentialSelection
import org.multipaz.prompt.ConsentPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentPromptDialog(
    model: PromptDialogModel<ConsentPromptDialogModel.ConsentPromptRequest, CredentialSelection>,
    imageLoader: ImageLoader?,
    maxHeight: Dp?
) {
    val dialogState = model.dialogState.collectAsState(PromptDialogModel.NoDialogState())
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val dialogStateValue = dialogState.value
    if (dialogStateValue is PromptDialogModel.DialogShownState) {
        val dialogParameters = dialogStateValue.parameters
        ConsentModalBottomSheet(
            sheetState = sheetState,
            requester = dialogParameters.requester,
            trustedRequesterIdentity = dialogParameters.trustedRequesterIdentity,
            consentData = dialogParameters.consentData,
            preselectedDocuments = dialogParameters.preselectedDocuments,
            imageLoader = imageLoader,
            maxHeight = maxHeight,
            onDocumentsInFocus = dialogParameters.onDocumentsInFocus,
            onConfirm = { credentialPresentmentSelection ->
                coroutineScope.launch {
                    dialogStateValue.resultChannel.send(credentialPresentmentSelection)
                }
            },
            onCancel = {
                coroutineScope.launch {
                    dialogStateValue.resultChannel.close(PromptDismissedException())
                }
            },
        )
    }
}
