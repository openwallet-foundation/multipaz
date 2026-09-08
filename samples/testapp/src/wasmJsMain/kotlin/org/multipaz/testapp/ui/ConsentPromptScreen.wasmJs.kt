package org.multipaz.testapp.ui

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.ConsentData
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

actual suspend fun launchAndroidPresentmentActivity(
    source: PresentmentSource,
    paData: AndroidPresentmentActivityData,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialSelection? {
    throw IllegalStateException("Not implemented on this OS")
}
