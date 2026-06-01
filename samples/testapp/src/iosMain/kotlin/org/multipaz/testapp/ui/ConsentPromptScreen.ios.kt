package org.multipaz.testapp.ui

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.ConsentData
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata

actual suspend fun launchAndroidPresentmentActivity(
    source: PresentmentSource,
    paData: AndroidPresentmentActivityData,
    requester: Requester,
    trustMetadata: TrustMetadata?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialSelection? {
    throw IllegalStateException("Not implemented on this OS")
}
