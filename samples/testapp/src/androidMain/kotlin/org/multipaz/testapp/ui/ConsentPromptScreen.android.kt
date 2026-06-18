package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.document.Document
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.ConsentData
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.promptModelRequestConsent
import org.multipaz.prompt.showBiometricPrompt
import org.multipaz.prompt.Reason
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.UserAuthenticationType as PromptUserAuthenticationType

actual suspend fun launchAndroidPresentmentActivity(
    source: PresentmentSource,
    paData: AndroidPresentmentActivityData,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): CredentialSelection? {
    PresentmentActivity.presentmentModel.reset(
        source = source,
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
                    trustedRequesterIdentity = trustedRequesterIdentity,
                    consentData = consentData,
                    preselectedDocuments = paData.preselectedDocuments,
                    onDocumentsInFocus = { documents ->
                        PresentmentActivity.presentmentModel.setDocumentsSelected(selectedDocuments = documents)
                    },
                )
                if (selection == null) {
                    throw PresentmentCanceledException("Presentment cancelled because user dismissed consent prompt")
                }
            } else {
                PresentmentActivity.presentmentModel.setDocumentsSelected(
                    selectedDocuments = consentData.credentialQueryResult.select(emptyList())
                        .matches.map { it.credential.document }
                )
            }
            if (paData.requireAuth) {
                if (!(PresentmentActivity.promptModel as AndroidPromptModel).showBiometricPrompt(
                    cryptoObject = null,
                    reason = Reason.HumanReadable(
                        title = "Verify it's you",
                        subtitle = "Authenticate to present credentials",
                        requireConfirmation = paData.authRequireConfirmation
                    ),
                    userAuthenticationTypes = setOf(
                        PromptUserAuthenticationType.BIOMETRIC,
                        PromptUserAuthenticationType.LSKF
                    ),
                    requireConfirmation = paData.authRequireConfirmation
                )) {
                    throw PresentmentCanceledException("Presentment cancelled because user dismissed biometric prompt")
                }
            }

            PresentmentActivity.presentmentModel.setSending()
            delay(paData.sendResponseDuration)
            PresentmentActivity.presentmentModel.setCompleted(null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PresentmentActivity.presentmentModel.setCompleted(e)
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

    return consentData.credentialQueryResult.select(emptyList())
}
