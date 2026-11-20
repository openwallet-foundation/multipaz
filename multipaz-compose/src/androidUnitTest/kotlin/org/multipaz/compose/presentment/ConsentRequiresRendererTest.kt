package org.multipaz.compose.presentment

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.context.initializeApplication
import org.multipaz.presentment.PresentmentData
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.TransactionDataRenderer
import org.multipaz.prompt.TransactionDataRendererNotAvailableException
import org.multipaz.prompt.TransactionDataRendererRepository
import org.multipaz.prompt.requestConsent
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
