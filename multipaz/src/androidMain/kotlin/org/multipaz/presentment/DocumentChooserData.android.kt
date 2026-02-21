package org.multipaz.presentment

import android.app.PendingIntent
import android.content.ComponentName
import org.multipaz.document.Document

/**
 * Data for configuring a document chooser.
 *
 * This is used with [PresentmentModel] to show a document chooser when launched e.g. as a Quick Access Wallet.
 *
 * @property initiallySelectedDocumentId the document identifier to initially focus or `null`.
 * @property openAppPendingIntentFn a function to create a [PendingIntent] to open the given document when the button is pressed.
 * @property preferredServices a list of services which should be preferred while an activity providing the UI for
 *  [PresentmentModel] is in the foreground. See [PresenmentActivity] in the multipaz-compose library for an example.
 *
 */
actual data class DocumentChooserData(
    val initiallySelectedDocumentId: String?,
    val openAppPendingIntentFn: (document: Document) -> PendingIntent,
    val preferredServices: List<ComponentName>
)
