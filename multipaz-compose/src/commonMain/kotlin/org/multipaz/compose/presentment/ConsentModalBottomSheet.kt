package org.multipaz.compose.presentment

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.multipaz.document.Document
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.ConsentData
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata

/**
 * Bottom sheet used for obtaining consent when presenting one or more credentials.
 *
 * @param sheetState a [SheetState] for state.
 * @param requester the relying party which is requesting the data.
 * @param trustMetadata [TrustMetadata] conveying the level of trust in the requester, if any.
 * @param consentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param imageLoader a [ImageLoader].
 * @param maxHeight the maximum height of the bottom sheet or `null` if no limit.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @param onConfirm called when the user presses the "Share" button, returns the user's selection.
 * @param onCancel called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentModalBottomSheet(
    sheetState: SheetState,
    requester: Requester,
    trustMetadata: TrustMetadata?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    maxHeight: Dp? = null,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onConfirm: (selection: CredentialSelection) -> Unit,
    onCancel: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { onCancel() },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Consent(
            modifier = (if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
                .animateContentSize(),
            requester = requester,
            trustMetadata = trustMetadata,
            consentData = consentData,
            preselectedDocuments = preselectedDocuments,
            imageLoader = imageLoader,
            onDocumentsInFocus = onDocumentsInFocus,
            onConfirm = onConfirm,
            onCancel = {
                coroutineScope.launch { sheetState.hide() }
                onCancel()
            },
        )
    }
}
