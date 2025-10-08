package org.multipaz.compose.presentment

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.Painter
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.multipaz.document.Document
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.trustmanagement.TrustPoint

/**
 * Bottom sheet used for obtaining consent when presenting one or more credentials.
 *
 * @param sheetState a [SheetState] for state.
 * @param requester the relying party which is requesting the data.
 * @param trustPoint if the requester is in a trust-list, the [TrustPoint] indicating this
 * @param credentialPresentmentData the combinatinos of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param imageLoader a [ImageLoader].
 * @param dynamicMetadataResolver a function which can be used to calculate [TrustMetadata] on a
 *   per-request basis.
 * @param appName the name of the application or `null` to not show the name.
 * @param appIconPainter the icon for the application or `null to not show the icon.
 * @param onConfirm called when the user presses the "Share" button, returns the user's selection.
 * @param onCancel called when the sheet is dismissed.
 * @param showCancelAsBack if `true`, the cancel button will say "Back" instead of "Cancel".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentModalBottomSheet(
    sheetState: SheetState,
    requester: Requester,
    trustPoint: TrustPoint?,
    credentialPresentmentData: CredentialPresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader,
    dynamicMetadataResolver: (requester: Requester) -> TrustMetadata? = { chain -> null },
    appName: String? = null,
    appIconPainter: Painter? = null,
    onConfirm: (selection: CredentialPresentmentSelection) -> Unit,
    onCancel: () -> Unit = {},
    showCancelAsBack: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { onCancel() },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Consent(
            requester = requester,
            trustPoint = trustPoint,
            credentialPresentmentData = credentialPresentmentData,
            preselectedDocuments = preselectedDocuments,
            imageLoader = imageLoader,
            dynamicMetadataResolver = dynamicMetadataResolver,
            appName = appName,
            appIconPainter = appIconPainter,
            onConfirm = onConfirm,
            onCancel = {
                coroutineScope.launch { sheetState.hide() }
                onCancel()
            },
            showCancelAsBack = showCancelAsBack
        )
    }
}
