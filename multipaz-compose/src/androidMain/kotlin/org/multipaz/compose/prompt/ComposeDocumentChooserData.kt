package org.multipaz.compose.prompt

import android.app.PendingIntent
import android.content.ComponentName
import androidx.compose.runtime.Composable
import org.multipaz.document.Document
import org.multipaz.presentment.DocumentChooserData

/**
 * Data for configuring a document chooser with Compose Multiplatform UI slots.
 *
 * Extends [DocumentChooserData] to add composable UI slots when driving presentment with Compose.
 *
 * @property initiallySelectedDocumentId the document identifier to initially focus or `null`.
 * @property openAppPendingIntentFn a function to create a [PendingIntent] to open the given document when the button is pressed.
 * @property preferredService the services which should be preferred while an activity providing the UI for
 *  [PresentmentModel] is in the foreground. See [PresentmentActivity] for an example.
 * @property onDocumentSelected a callback invoked whenever a document is shown as selected or when no document is selected.
 * @property documentSelectedContent optional composable content (e.g. `@Composable (documentId: String) -> Unit`) to
 *   render for a document is selected in the document chooser. The content will appear below the instructions to the
 *   user for holding closer to a reader to share.
 */
class ComposeDocumentChooserData(
    initiallySelectedDocumentId: String?,
    openAppPendingIntentFn: (document: Document) -> PendingIntent,
    preferredService: ComponentName,
    onDocumentSelected: ((documentId: String?) -> Unit)? = null,
    val documentSelectedContent: (@Composable (documentId: String) -> Unit)? = null
) : DocumentChooserData(
    initiallySelectedDocumentId = initiallySelectedDocumentId,
    openAppPendingIntentFn = openAppPendingIntentFn,
    preferredService = preferredService,
    onDocumentSelected = onDocumentSelected
)
