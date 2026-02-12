package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.document.Document
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentCanceled
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.promptModelRequestConsent
import org.multipaz.prompt.showBiometricPrompt
import org.multipaz.request.Requester
import org.multipaz.securearea.UserAuthenticationType
import org.multipaz.trustmanagement.TrustMetadata

private const val TAG = "ConsentPromptScreen"

actual suspend fun launchAndroidPresentmentActivity(
    source: PresentmentSource,
    paData: AndroidPresentmentActivityData,
    requester: Requester,
    trustMetadata: TrustMetadata?,
    credentialPresentmentData: CredentialPresentmentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialPresentmentSelection? {
    PresentmentActivity.presentmentModel.reset(
        documentStore = source.documentStore,
        documentTypeRepository = source.documentTypeRepository,
        preselectedDocuments = paData.preselectedDocuments
    )
    PresentmentActivity.startActivity()

    val consentAndAuthJob = CoroutineScope(Dispatchers.IO + PresentmentActivity.promptModel).launch {
        try {
            PresentmentActivity.presentmentModel.setWaitingForReader()
            delay(paData.connectionDuration)
            PresentmentActivity.presentmentModel.setWaitingForUserInput()

            if (paData.showConsent) {
                val selection = promptModelRequestConsent(
                    requester = requester,
                    trustMetadata = trustMetadata,
                    credentialPresentmentData = credentialPresentmentData,
                    preselectedDocuments = paData.preselectedDocuments,
                    onDocumentsInFocus = { documents ->
                        PresentmentActivity.presentmentModel.setDocumentsSelected(selectedDocuments = documents)
                    },
                )
                if (selection == null) {
                    throw PresentmentCanceled("Presentment cancelled because user dismissed consent prompt")
                }
            } else {
                PresentmentActivity.presentmentModel.setDocumentsSelected(
                    selectedDocuments = credentialPresentmentData.select(emptyList())
                        .matches.map { it.credential.document }
                )
            }
            if (paData.requireAuth) {
                if (!PresentmentActivity.promptModel.showBiometricPrompt(
                    cryptoObject = null,
                    title = "Verify it's you",
                    subtitle = "Authenticate to present credentials",
                    userAuthenticationTypes = setOf(UserAuthenticationType.BIOMETRIC, UserAuthenticationType.LSKF),
                    requireConfirmation = paData.authRequireConfirmation
                )) {
                    throw PresentmentCanceled("Presentment cancelled because user dismissed biometric prompt")
                }
            }

            PresentmentActivity.presentmentModel.setSending()
            delay(paData.sendResponseDuration)
            PresentmentActivity.presentmentModel.setCompleted(null)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                PresentmentActivity.presentmentModel.setCompleted(PresentmentCanceled("Presentment was cancelled"))
            } else {
                PresentmentActivity.presentmentModel.setCompleted(e)
            }
        }
    }

    val listenForCancellationFromUiJob = CoroutineScope(Dispatchers.Main).launch {
        PresentmentActivity.presentmentModel.state.collect { state ->
            if (state == PresentmentModel.State.CanceledByUser) {
                consentAndAuthJob.cancel()
            }
        }
    }

    consentAndAuthJob.join()
    listenForCancellationFromUiJob.cancel()

    return credentialPresentmentData.select(emptyList())
}
