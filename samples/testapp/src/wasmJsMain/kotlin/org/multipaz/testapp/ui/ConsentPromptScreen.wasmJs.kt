package org.multipaz.testapp.ui

import org.multipaz.document.Document
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.PresentmentData
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

actual suspend fun launchAndroidPresentmentActivity(
    source: PresentmentSource,
    paData: AndroidPresentmentActivityData,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): PresentmentSelection? {
    throw IllegalStateException("Not implemented on this OS")
}
