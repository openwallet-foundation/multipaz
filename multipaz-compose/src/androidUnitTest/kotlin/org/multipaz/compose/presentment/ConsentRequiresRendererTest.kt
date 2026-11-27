package org.multipaz.compose.presentment

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.compose.prompt.ConsentPromptDialog
import org.multipaz.context.initializeApplication
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.ConsentPromptDialogModel
import org.multipaz.prompt.PromptUiNotAvailableException
import org.multipaz.prompt.TransactionDataRenderer
import org.multipaz.prompt.TransactionDataRendererNotAvailableException
import org.multipaz.prompt.TransactionDataRendererRepository
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.prompt.requestConsent
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Checks that [Consent] never draws, and so never lets the person approve, transaction data of a
 * type that [org.multipaz.documenttype.TransactionType.requiresRenderer] with the built-in
 * rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ConsentRequiresRendererTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        Security.addProvider(BouncyCastleProvider())
        val app = ApplicationProvider.getApplicationContext<Application>()
        initializeApplication(app)
        ConsentGoldenEcosystem.registerFakeAndroidApps(app)
    }

    private class Setup(
        val requester: Requester,
        val trusted: TrustedRequesterIdentity?,
        val presentmentData: PresentmentData,
        val goldenRepository: TransactionDataRendererRepository,
    )

    private fun setup() = runBlocking {
        val eco = ConsentGoldenEcosystem.create()
        val (requester, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_METRO_TRANSIT)
        val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
            it.id == "payment_transaction_only_conf"
        }
        val presentmentData = eco.executeCannedDcql(
            cannedRequest = canned,
            transactionDataMap = mapOf(
                "cred1" to listOf(
                    goldenTransactionData(GoldenTransitFareTransaction, GoldenTransitFareTransaction.sampleData)
                )
            ),
        )
        Setup(requester, trusted, presentmentData, eco.transactionDataRendererRepository)
    }

    private fun showConsent(setup: Setup, repository: TransactionDataRendererRepository?): Pair<Int, Int> {
        var confirmed = 0
        var cancelled = 0
        composeTestRule.setContent {
            Consent(
                requester = setup.requester,
                trustedRequesterIdentity = setup.trusted,
                presentmentData = setup.presentmentData,
                preselectedDocuments = emptyList(),
                imageLoader = null,
                transactionDataRendererRepository = repository,
                onDocumentsInFocus = {},
                onConfirm = { confirmed++ },
                onCancel = { cancelled++ },
            )
        }
        composeTestRule.waitForIdle()
        return Pair(confirmed, cancelled)
    }

    @Test
    fun noRepositoryRefusesInsteadOfDrawingBuiltInRendering() {
        assertEquals(Pair(0, 1), showConsent(setup(), repository = null))
    }

    @Test
    fun refusalGoesToOnRefusedWithTheTypeAndIsNotACancel() {
        val setup = setup()
        var cancelled = 0
        var refused: TransactionDataRendererNotAvailableException? = null
        composeTestRule.setContent {
            Consent(
                requester = setup.requester,
                trustedRequesterIdentity = setup.trusted,
                presentmentData = setup.presentmentData,
                preselectedDocuments = emptyList(),
                imageLoader = null,
                transactionDataRendererRepository = null,
                onDocumentsInFocus = {},
                onConfirm = {},
                onCancel = { cancelled++ },
                onRefused = { refused = it },
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(0, cancelled)
        assertEquals(GoldenTransitFareTransaction.identifier, refused?.typeIdentifier)
    }

    @Test
    fun rendererForAnotherToolkitRefuses() {
        val setup = setup()
        val repository = TransactionDataRendererRepository().apply {
            addRenderer(GoldenTransitFareTransaction.identifier, object : TransactionDataRenderer() {})
        }
        assertEquals(Pair(0, 1), showConsent(setup, repository))
    }

    @Test
    fun composeRendererLetsConsentBeDrawn() {
        val setup = setup()
        assertEquals(Pair(0, 0), showConsent(setup, setup.goldenRepository))
    }

    @Test
    fun allTransactionDataListsTheItemsOfEveryMatch() {
        // The sample holds two payment credentials, so the item is listed once per solution.
        val identifiers = setup().presentmentData.allTransactionData.map { it.type.identifier }
        assertEquals(2, identifiers.size)
        assertEquals(setOf(GoldenTransitFareTransaction.identifier), identifiers.toSet())
    }

    @Test
    fun requestConsentRefusesBeforeShowingAnything() = runBlocking {
        val setup = setup()
        val promptModel = AndroidPromptModel.Builder().addCommonDialogs().build()
        // Nothing is bound to the prompt model, so showing the prompt would have thrown
        // PromptUiNotAvailableException instead.
        val e = assertFailsWith<TransactionDataRendererNotAvailableException> {
            promptModel.requestConsent(
                requester = setup.requester,
                trustedRequesterIdentity = setup.trusted,
                presentmentData = setup.presentmentData,
                preselectedDocuments = emptyList(),
                onDocumentsInFocus = {},
                transactionDataRendererRepository = null,
            )
        }
        assertEquals(GoldenTransitFareTransaction.identifier, e.typeIdentifier)
    }

    // The prompt model has no UI bound, so showing a prompt would throw PromptUiNotAvailableException.
    private suspend fun showWithSource(source: SimplePresentmentSource, setup: Setup) =
        withContext(AndroidPromptModel.Builder().addCommonDialogs().build()) {
            source.showConsentPrompt(
                requester = setup.requester,
                trustedRequesterIdentity = setup.trusted,
                presentmentData = setup.presentmentData,
                preselectedDocuments = emptyList(),
                onDocumentsInFocus = {},
            )
        }

    @Test
    fun presentmentSourceWithoutRepositoryRefuses() = runBlocking {
        val setup = setup()
        val eco = ConsentGoldenEcosystem.create()
        val source = SimplePresentmentSource(eco.documentStore, eco.documentTypeRepository)
        val e = assertFailsWith<TransactionDataRendererNotAvailableException> { showWithSource(source, setup) }
        assertEquals(GoldenTransitFareTransaction.identifier, e.typeIdentifier)
    }

    @Test
    fun presentmentSourceHandsItsRepositoryToTheDefaultPrompt() = runBlocking {
        val setup = setup()
        val eco = ConsentGoldenEcosystem.create()
        val source = SimplePresentmentSource(
            documentStore = eco.documentStore,
            documentTypeRepository = eco.documentTypeRepository,
            transactionDataRendererRepository = setup.goldenRepository,
        )
        // The repository has a renderer for the type, so the prompt is not refused and goes on to show
        // itself, which fails as no UI is bound.
        assertFailsWith<PromptUiNotAvailableException> { showWithSource(source, setup) }
        Unit
    }

    @Test
    fun biometricPromptIsNotUsedForDataThatRequiresARenderer() {
        val setup = setup()
        var asked = 0
        val simpleConsent = simpleConsentForBiometricPrompt(
            requester = setup.requester,
            trustedRequesterIdentity = setup.trusted,
            presentmentData = setup.presentmentData,
            preselectedDocuments = emptyList(),
            classifySimpleConsentFn = { _, _, _, _ -> asked++; null },
            showOnBiometricPrompt = true,
        )
        // The classifier is not even asked, so it cannot choose the prompt that draws no transaction data.
        assertNull(simpleConsent)
        assertEquals(0, asked)
    }

    @Test
    fun biometricPromptIsAskedWhenNoTransactionDataRequiresARenderer() = runBlocking {
        val eco = ConsentGoldenEcosystem.create()
        val (requester, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_METRO_TRANSIT)
        val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
            it.id == "payment_transaction_only_conf"
        }
        var asked = 0
        simpleConsentForBiometricPrompt(
            requester = requester,
            trustedRequesterIdentity = trusted,
            presentmentData = eco.executeCannedDcql(cannedRequest = canned),
            preselectedDocuments = emptyList(),
            classifySimpleConsentFn = { _, _, _, _ -> asked++; null },
            showOnBiometricPrompt = true,
        )
        assertEquals(1, asked)
    }

    @Test
    fun silentConsentRefusesDataThatRequiresARenderer() = runBlocking {
        val setup = setup()
        val e = assertFailsWith<TransactionDataRendererNotAvailableException> {
            promptModelSilentConsent(
                requester = setup.requester,
                trustedRequesterIdentity = setup.trusted,
                presentmentData = setup.presentmentData,
                preselectedDocuments = emptyList(),
                onDocumentsInFocus = {},
            )
        }
        assertEquals(GoldenTransitFareTransaction.identifier, e.typeIdentifier)
    }

    @Test
    fun silentConsentStillApprovesDataWithoutTransactionData() {
        runBlocking {
            val eco = ConsentGoldenEcosystem.create()
            val (requester, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_METRO_TRANSIT)
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction_only_conf"
            }
            val selection = promptModelSilentConsent(
                requester = requester,
                trustedRequesterIdentity = trusted,
                presentmentData = eco.executeCannedDcql(cannedRequest = canned),
                preselectedDocuments = emptyList(),
                onDocumentsInFocus = {},
            )
            assertNotNull(selection)
        }
    }

    @Test
    fun consentPromptDialogRefusesRendererForAnotherToolkit() {
        val setup = setup()
        val model = AndroidPromptModel.Builder().addCommonDialogs().build()
            .getDialogModel(ConsentPromptDialogModel.DialogType)
        composeTestRule.setContent {
            ConsentPromptDialog(model = model, imageLoader = null, maxHeight = null)
        }
        // The renderer is registered, so `requestConsent()` lets the prompt through; it is made for
        // another toolkit, so the dialog is what refuses it, with the exception, not with a dismissal.
        val repository = TransactionDataRendererRepository().apply {
            addRenderer(GoldenTransitFareTransaction.identifier, object : TransactionDataRenderer() {})
        }
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val refusal = scope.async {
                try {
                    model.displayPrompt(
                        ConsentPromptDialogModel.ConsentPromptRequest(
                            requester = setup.requester,
                            trustedRequesterIdentity = setup.trusted,
                            presentmentData = setup.presentmentData,
                            preselectedDocuments = emptyList(),
                            onDocumentsInFocus = {},
                            transactionDataRendererRepository = repository,
                        )
                    )
                    null
                } catch (e: TransactionDataRendererNotAvailableException) {
                    e
                }
            }
            composeTestRule.waitUntil(timeoutMillis = 10.seconds.inWholeMilliseconds) { refusal.isCompleted }
            val e = assertNotNull(runBlocking { refusal.await() })
            assertEquals(GoldenTransitFareTransaction.identifier, e.typeIdentifier)
        } finally {
            scope.cancel()
        }
    }
}
