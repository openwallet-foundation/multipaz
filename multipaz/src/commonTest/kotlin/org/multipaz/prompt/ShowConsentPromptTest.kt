package org.multipaz.prompt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.request.buildDeviceRequestFromDcql
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.PresentmentData
import org.multipaz.request.Requester
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ShowConsentPromptTest {

    private class TestRenderer : TransactionDataRenderer()

    private suspend fun presentmentData(): PresentmentData {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            )
        )
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            dcqlString = """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                      },
                      "claims": [
                        {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]}
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
        return deviceRequest.execute(presentmentSource = harness.presentmentSource)
    }

    // Binds a mock consent UI which records each request and confirms it with the preselected documents.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.bindConsentUi(
        promptModel: PromptModel
    ): List<ConsentPromptDialogModel.ConsentPromptRequest> {
        val requests = mutableListOf<ConsentPromptDialogModel.ConsentPromptRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            promptModel.getDialogModel(ConsentPromptDialogModel.DialogType).dialogState.collect { state ->
                if (state is PromptDialogModel.DialogShownState) {
                    requests.add(state.parameters)
                    state.resultChannel.send(
                        state.parameters.presentmentData.select(state.parameters.preselectedDocuments)
                    )
                }
            }
        }
        return requests
    }

    @Test
    fun repositoryReachesConsentRequest() = runTest {
        val promptModel = PromptModelTest.TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        val requests = bindConsentUi(promptModel)
        val repository = TransactionDataRendererRepository().apply {
            addRenderer("delegate", TestRenderer())
        }

        val selection = withContext(promptModel) {
            promptModelRequestConsent(
                requester = Requester(origin = "https://verifier.example.com"),
                trustedRequesterIdentity = null,
                presentmentData = presentmentData(),
                preselectedDocuments = emptyList(),
                onDocumentsInFocus = {},
                transactionDataRendererRepository = repository,
            )
        }

        assertNotNull(selection)
        assertSame(repository, requests.single().transactionDataRendererRepository)
    }

    @Test
    fun noRepositoryByDefault() = runTest {
        val promptModel = PromptModelTest.TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        val requests = bindConsentUi(promptModel)

        withContext(promptModel) {
            promptModelRequestConsent(
                requester = Requester(origin = "https://verifier.example.com"),
                trustedRequesterIdentity = null,
                presentmentData = presentmentData(),
                preselectedDocuments = emptyList(),
                onDocumentsInFocus = {},
            )
        }

        assertNull(requests.single().transactionDataRendererRepository)
    }
}
