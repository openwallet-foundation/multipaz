package org.multipaz.compose.presentment

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Looper
import android.text.TextUtils
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.compose.datetime.clockForTesting
import org.multipaz.consent.ClassifyPurposesFn
import org.multipaz.consent.ClassifyRiskFn
import org.multipaz.consent.ConsentRiskAssessment
import org.multipaz.consent.ConsentRiskLevel
import org.multipaz.consent.ConsentRiskReason
import org.multipaz.consent.defaultClassifyPurposes
import org.multipaz.consent.defaultClassifyRisk
import org.multipaz.context.initializeApplication
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.RequesterHistory
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.utopia.knowntypes.Loyalty
import org.multipaz.utopia.knowntypes.UtopiaBoardingPass
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.security.Security
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ConsentGoldenScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var savedLocale: Locale
    private lateinit var savedTimeZone: TimeZone

    private data class ActiveConsentSpec(
        val key: String,
        val requester: Requester,
        val trustedRequesterIdentity: TrustedRequesterIdentity?,
        val presentmentData: PresentmentData,
        val requesterHistory: RequesterHistory?,
        val classifyPurposesFn: ClassifyPurposesFn = ::defaultClassifyPurposes,
        val classifyRiskFn: ClassifyRiskFn = ::defaultClassifyRisk,
        val darkTheme: Boolean = false,
        val locale: Locale = Locale.US,
    )

    @Before
    fun setUp() {
        Security.addProvider(BouncyCastleProvider())
        savedLocale = Locale.getDefault()
        savedTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        clockForTesting = object : Clock {
            override fun now(): Instant = ConsentGoldenEcosystem.NOW
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        initializeApplication(app)
        ConsentGoldenEcosystem.registerFakeAndroidApps(app)
    }

    @After
    fun tearDown() {
        clockForTesting = null
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedTimeZone)
    }

    @Test
    fun verifyAndCatalogConsentGoldens() = runBlocking {
        val eco = ConsentGoldenEcosystem.create()
        val updateGoldens = System.getProperty("updateGoldens")?.toBoolean() == true
        val goldenRootDir = resolveGoldenRootDir()

        var activeSpec by mutableStateOf<ActiveConsentSpec?>(null)
        var composeRootView: View? = null
        composeTestRule.setContent {
            composeRootView = LocalView.current
            val spec = activeSpec
            if (spec != null) {
                key(spec.key) {
                    val baseConfig = LocalConfiguration.current
                    val targetConfig = Configuration(baseConfig).apply {
                        setLocale(spec.locale)
                        setLayoutDirection(spec.locale)
                        uiMode = if (spec.darkTheme) {
                            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                        } else {
                            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
                        }
                    }
                    val layoutDirection = if (
                        TextUtils.getLayoutDirectionFromLocale(spec.locale) == View.LAYOUT_DIRECTION_RTL
                    ) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    }
                    CompositionLocalProvider(
                        LocalConfiguration provides targetConfig,
                        LocalLayoutDirection provides layoutDirection,
                    ) {
                        val colorScheme = if (spec.darkTheme) darkColorScheme() else lightColorScheme()
                        MaterialTheme(colorScheme = colorScheme) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = if (spec.darkTheme) {
                                    colorScheme.surfaceContainerLow
                                } else {
                                    colorScheme.surfaceContainer
                                },
                            ) {
                                Consent(
                                    requester = spec.requester,
                                    trustedRequesterIdentity = spec.trustedRequesterIdentity,
                                    presentmentData = spec.presentmentData,
                                    preselectedDocuments = emptyList(),
                                    imageLoader = null,
                                    transactionDataRendererRepository = eco.transactionDataRendererRepository,
                                    requesterHistory = spec.requesterHistory,
                                    classifyPurposesFn = spec.classifyPurposesFn,
                                    classifyRiskFn = spec.classifyRiskFn,
                                    onDocumentsInFocus = {},
                                    onConfirm = {},
                                    onCancel = {},
                                )
                            }
                        }
                    }
                }
            }
        }

        val recordedScenarios = mutableListOf<GoldenScenarioRecord>()
        val mismatches = mutableListOf<String>()

        fun runScenario(
            categoryDir: String,
            categoryTitle: String,
            categoryDescription: String,
            scenarioId: String,
            title: String,
            requesterSummary: String,
            protocolSummary: String,
            riskBadge: String,
            description: String,
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            presentmentData: PresentmentData,
            requesterHistory: RequesterHistory? = ConsentGoldenEcosystem.firstTimeHistory(),
            classifyPurposesFn: ClassifyPurposesFn = ::defaultClassifyPurposes,
            classifyRiskFn: ClassifyRiskFn = ::defaultClassifyRisk,
            darkTheme: Boolean = false,
            locale: Locale = Locale.US,
            captureSteps: ScenarioStepScope.() -> Unit,
        ) {
            val isHighRisk = riskBadge.startsWith("HIGH_RISK")
            composeTestRule.mainClock.autoAdvance = true
            activeSpec = null
            composeTestRule.waitForIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            Locale.setDefault(locale)
            val app = ApplicationProvider.getApplicationContext<Application>()
            app.resources.configuration.setLocale(locale)
            app.resources.configuration.setLayoutDirection(locale)

            try {
                composeTestRule.mainClock.autoAdvance = !isHighRisk
                activeSpec = ActiveConsentSpec(
                    key = "$categoryDir/$scenarioId",
                    requester = requester,
                    trustedRequesterIdentity = trustedRequesterIdentity,
                    presentmentData = presentmentData,
                    requesterHistory = requesterHistory,
                    classifyPurposesFn = classifyPurposesFn,
                    classifyRiskFn = classifyRiskFn,
                    darkTheme = darkTheme,
                    locale = locale,
                )
                if (isHighRisk) {
                    repeat(30) {
                        Snapshot.sendApplyNotifications()
                        composeTestRule.mainClock.advanceTimeBy(50L)
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                    }
                }
                composeTestRule.waitForIdle()
                Shadows.shadowOf(Looper.getMainLooper()).idle()

                val steps = mutableListOf<GoldenStepRecord>()
                val scope = ScenarioStepScope(
                    categoryDir = categoryDir,
                    scenarioId = scenarioId,
                    goldenRootDir = goldenRootDir,
                    updateGoldens = updateGoldens,
                    steps = steps,
                    mismatches = mismatches,
                    rootViewProvider = { checkNotNull(composeRootView) },
                )
                scope.captureSteps()
                composeTestRule.mainClock.autoAdvance = true

                recordedScenarios.add(
                    GoldenScenarioRecord(
                        categoryDir = categoryDir,
                        categoryTitle = categoryTitle,
                        categoryDescription = categoryDescription,
                        scenarioId = scenarioId,
                        title = title,
                        requesterSummary = requesterSummary,
                        protocolSummary = protocolSummary,
                        riskBadge = riskBadge,
                        description = description,
                        steps = steps,
                    )
                )
            } finally {
                Locale.setDefault(Locale.US)
                app.resources.configuration.setLocale(Locale.US)
                app.resources.configuration.setLayoutDirection(Locale.US)
            }
        }

        // =========================================================================================
        // Category 1: 01_normal_single
        // =========================================================================================
        val cat1Dir = "01_normal_single"
        val cat1Title = "1. Normal — Single-Credential Requests"
        val cat1Desc = "Trusted relying parties requesting standard, recognized presentment purposes from a single credential, as well as unverified or anonymous proximity requests for non-PII (e.g., age_over_21 and portrait) where no warning is shown because the user is in physical proximity and the relying party is already free to capture their likeness (whereas over-the-Internet and app-to-app requests for the same claims trigger a warning)."

        // 1.1 age_verification_first_time
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_VINEYARDS)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "age_verification_first_time",
                title = "Age Verification (First-Time Share)",
                requesterSummary = "Utopia Vineyards — Proximity (ISO 18013-5)",
                protocolSummary = "mDL (age_over_21, portrait)",
                riskBadge = "NORMAL",
                description = "A trusted wine shop terminal requests proof of age over 21 and holder portrait for the first time. We capture the main prompt, the document claims details page, the requester info page, and the X.509 certificate chain viewer.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                requesterHistory = ConsentGoldenEcosystem.firstTimeHistory(),
            ) {
                capture("main", "Main consent prompt")
                clickViewDetails()
                capture("doc_details", "Document details page")
                clickBack()
                clickMoreInformation()
                capture("requester_info", "Requester info page (first-time share)")
                clickCertificateChain("Certificate chain")
                capture("cert_chain", "X.509 Certificate chain viewer")
            }
        }

        // 1.2 age_verification_returning_user
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_VINEYARDS)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "age_verification_returning_user",
                title = "Age Verification (Returning User History)",
                requesterSummary = "Utopia Vineyards — Proximity (ISO 18013-5)",
                protocolSummary = "mDL (age_over_21, portrait)",
                riskBadge = "NORMAL",
                description = "The user has previously shared credentials with Utopia Vineyards 3 times. The bottom RP-info box notes the prior interaction, and the Requester Info page displays the first and most recent share timestamps.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                requesterHistory = ConsentGoldenEcosystem.returningUserHistory(shareCount = 3),
            ) {
                capture("main", "Main consent prompt (shared before)")
                clickMoreInformation()
                capture("requester_info", "Requester info page (3 past shares)")
            }
        }

        // 1.3 age_over_21_eu_av_over_the_internet
        run {
            val (req, trusted) = eco.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_VINEYARDS,
                origin = "https://store.utopiavineyards.example.com",
            )
            val canned = AgeVerification.getDocumentType().cannedRequests.first { it.id == "age_over_21" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "age_over_21_eu_av_over_the_internet",
                title = "Age Over 21 Over-the-Internet (EU Age Verification Credential)",
                requesterSummary = "Utopia Vineyards — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "EU Age Verification (eu.europa.ec.av.1: age_over_21)",
                riskBadge = "NORMAL",
                description = "An online wine store requests only age_over_21 from an EU Age Verification credential over the Internet. No portrait is requested, so the relying party only receives the piece of information that someone is over 21, not whether it is the authorized holder. This can still work if the relying party assumes that the age credential is on the authorized holder's device (that is, adequate user proofing was done at provisioning time), it is device-bound (so it cannot be copied to other devices), and the device has been configured so only the authorized holder can access it.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main consent prompt (EU Age Verification age_over_21)")
                clickViewDetails()
                capture("doc_details", "Document details page")
            }
        }

        // 1.3 driving_privileges_with_disclaimer
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_DOT)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "us-transportation" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "driving_privileges_with_disclaimer",
                title = "Driving Privileges with Statutory Disclaimer",
                requesterSummary = "Utopia Department of Transportation — Proximity (ISO 18013-5)",
                protocolSummary = "mDL (US Transportation profile)",
                riskBadge = "NORMAL",
                description = "A roadside inspection reader requests driving privileges and displays a statutory TrustMetadata disclaimer in the bottom RP-info box. Non-salient validity/issuer claims are collapsed into 'And N more'.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main consent prompt with disclaimer")
                clickViewDetails()
                capture("doc_details", "Document details page")
                clickBack()
                clickMoreInformation()
                capture("requester_info", "Requester info page with disclaimer")
            }
        }

        // 1.4 address_verification_partial_retention
        run {
            val (req, trusted) = eco.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_MARKETPLACE,
                origin = "https://checkout.utopiamarketplace.example.com",
            )
            val canned = DrivingLicense.getDocumentType().cannedRequests.first {
                it.id == "name-and-address-partially-stored"
            }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "address_verification_partial_retention",
                title = "Address Verification with Partial Data Retention",
                requesterSummary = "Utopia Marketplace — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL (transient portrait/issue/expiry + retained name & address)",
                riskBadge = "NORMAL",
                description = "An e-commerce checkout requests transient verification of portrait and validity dates alongside retained name and shipping address, splitting claims into 'shared with' and 'stored by' sections.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main consent prompt (split transient/stored)")
                clickViewDetails()
                capture("doc_details", "Document details page")
            }
        }

        // 1.5 identity_multiple_matching_docs
        run {
            val (req, trusted) = eco.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_AIRLINES,
                origin = "https://checkin.utopiaairlines.example.com",
            )
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"] }
                      ]
                    },
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${PhotoID.PHOTO_ID_DOCTYPE}" },
                      "claims": [
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "given_name"] },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "family_name"] },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "birth_date"] },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "portrait"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [
                        ["mdl"],
                        ["photoid"]
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "identity_multiple_matching_docs",
                title = "Identity Verification with Multiple Matching Documents",
                requesterSummary = "Utopia Airlines — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL or Photo ID",
                riskBadge = "NORMAL",
                description = "Multiple holder documents satisfy the request. The credential card shows a chevron to open the document chooser, allowing the user to switch from Erika's Driving License to Erika's Photo ID.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (Driving License selected)")
                clickNodeWithText("Erika's Driving License")
                capture("chooser", "Document chooser page")
                clickNodeWithText("Erika's Photo ID")
                capture("main_switched", "Main prompt (Photo ID selected)")
            }
        }

        // 1.6 loyalty_card
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_WHOLESALE)
            val canned = Loyalty.getDocumentType().cannedRequests.first { it.id == "mandatory" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "loyalty_card",
                title = "Loyalty Card Verification",
                requesterSummary = "Utopia Wholesale — Proximity (ISO 18013-5)",
                protocolSummary = "Loyalty ID mdoc (org.multipaz.loyalty.1)",
                riskBadge = "NORMAL",
                description = "A wholesale club point-of-sale terminal requests the holder's Utopia Wholesale Membership card.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main consent prompt (Loyalty verification)")
            }
        }

        // 1.7 movie_ticket_sdjwt
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_CINEMA)
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "ticket",
                      "format": "dc+sd-jwt",
                      "meta": { "vct_values": ["${UtopiaMovieTicket.MOVIE_TICKET_VCT}"] },
                      "claims": [
                        { "path": ["ticket_id"] },
                        { "path": ["movie"] },
                        { "path": ["show_date_time"] },
                        { "path": ["theater_id"] },
                        { "path": ["seat_id"] }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "movie_ticket_sdjwt",
                title = "Movie Ticket Verification (SD-JWT VC)",
                requesterSummary = "Utopia Cinema — Proximity (ISO 18013-5)",
                protocolSummary = "Keyless SD-JWT VC (Movie Ticket)",
                riskBadge = "NORMAL",
                description = "A cinema usher scanner verifies a keyless IETF SD-JWT VC movie ticket for 'Return to Utopia'.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main consent prompt (Ticket verification)")
                clickViewDetails()
                capture("doc_details", "Document details page")
                clickBack()
                clickMoreInformation()
                capture("requester_info", "Requester info page")
            }
        }

        // 1.8 unverified_proximity_reader_age_over_21
        run {
            val (req, trusted) = eco.unverifiedProximityRequester(ConsentGoldenEcosystem.RP_UNVERIFIED_LOUNGE)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "unverified_proximity_reader_age_over_21",
                title = "Unverified Proximity Reader Requesting Non-PII (Signed Certificate Not in Trust List)",
                requesterSummary = "Harbor View Lounge — Unverified Proximity (ISO 18013-5)",
                protocolSummary = "mDL (age_over_21, portrait)",
                riskBadge = "NORMAL",
                description = "A proximity reader presents a valid X.509 certificate chain whose root CA is not in the wallet's trust list, requesting only non-PII (age_over_21 and portrait). Because the user is in physical proximity and the relying party is already free to capture their likeness, no warning is shown (unlike over-the-Internet or app-to-app requests, which trigger a warning). The Requester Info page still shows 'Unverified requester' with the inspectable certificate chain.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (no warning for proximity non-PII)")
                clickMoreInformation()
                capture("requester_info", "Requester info ('Unverified requester')")
            }
        }

        // 1.9 anonymous_proximity_reader_age_over_21
        run {
            val (req, trusted) = eco.anonymousProximityRequester()
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat1Dir,
                categoryTitle = cat1Title,
                categoryDescription = cat1Desc,
                scenarioId = "anonymous_proximity_reader_age_over_21",
                title = "Anonymous Proximity Reader Requesting Non-PII (No Reader Certificate)",
                requesterSummary = "Unknown Requester — Anonymous Proximity (ISO 18013-5)",
                protocolSummary = "mDL (age_over_21, portrait)",
                riskBadge = "NORMAL",
                description = "A proximity reader provides no reader authentication certificate at all while requesting only non-PII (age_over_21 and portrait). Because the user is in physical proximity and the relying party is already free to capture their likeness, no warning is shown (whereas an over-the-Internet or app-to-app request would show a warning). The Requester Info page displays 'Anonymous requester'.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (no warning for anonymous proximity non-PII)")
                clickMoreInformation()
                capture("requester_info", "Requester info ('Anonymous requester')")
            }
        }

        // =========================================================================================
        // Category 2: 02_transactions
        // =========================================================================================
        val cat2Dir = "02_transactions"
        val cat2Title = "2. Transactions — Built-In & Custom Transaction Renderers"
        val cat2Desc = "Requests attaching transaction data payloads, including built-in SCA payment rendering and custom Compose transaction renderers."

        // 2.1 payment_with_claims
        run {
            val (req, trusted) = eco.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_MARKETPLACE,
                origin = "https://checkout.utopiamarketplace.example.com",
            )
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction"
            }
            val pd = eco.executeCannedDcql(
                cannedRequest = canned,
                transactionDataMap = mapOf(
                    "cred1" to listOf(
                        utopiaPaymentTransactionData(
                            payeeName = "Utopia Marketplace",
                            amount = 78.50,
                            tipRequested = true,
                        )
                    )
                ),
            )
            runScenario(
                categoryDir = cat2Dir,
                categoryTitle = cat2Title,
                categoryDescription = cat2Desc,
                scenarioId = "payment_with_claims",
                title = "Payment Transaction with Card Claims & Card Switcher",
                requesterSummary = "Utopia Marketplace — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "DigitalPaymentCredential + PaymentTransaction",
                riskBadge = "NORMAL",
                description = "Approving a payment transaction while also sharing masked account and holder name claims. Because the wallet holds both Utopia Bank Platinum and Utopia Bank Gold, the user can open the chooser to pick which card to pay with.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main payment prompt")
                clickNodeWithText("Utopia Bank Gold")
                capture("chooser", "Payment card chooser (Gold vs Platinum)")
            }
        }

        // 2.2 payment_confirmation_only
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_WHOLESALE)
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction_only_conf"
            }
            val pd = eco.executeCannedDcql(
                cannedRequest = canned,
                transactionDataMap = mapOf(
                    "cred1" to listOf(
                        utopiaPaymentTransactionData(
                            payeeName = "Utopia Wholesale",
                            amount = 142.80,
                            tipRequested = false,
                        )
                    )
                ),
            )
            runScenario(
                categoryDir = cat2Dir,
                categoryTitle = cat2Title,
                categoryDescription = cat2Desc,
                scenarioId = "payment_confirmation_only",
                title = "Payment Confirmation Only (Zero Attribute Claims)",
                requesterSummary = "Utopia Wholesale — Proximity (ISO 18013-5)",
                protocolSummary = "DigitalPaymentCredential (0 claims) + PaymentTransaction",
                riskBadge = "NORMAL",
                description = "A cryptographic payment confirmation with zero identity/card attribute claims disclosed.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main payment confirmation prompt")
            }
        }

        // 2.3 transit_fare_custom_renderer
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_METRO_TRANSIT)
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction_only_conf"
            }
            val pd = eco.executeCannedDcql(
                cannedRequest = canned,
                transactionDataMap = mapOf(
                    "cred1" to listOf(
                        goldenTransactionData(
                            GoldenTransitFareTransaction,
                            GoldenTransitFareTransaction.sampleData
                        )
                    )
                ),
            )
            runScenario(
                categoryDir = cat2Dir,
                categoryTitle = cat2Title,
                categoryDescription = cat2Desc,
                scenarioId = "transit_fare_custom_renderer",
                title = "Transit Fare Selection (Custom Interactive Transaction Renderer)",
                requesterSummary = "Utopia Metro Transit — Proximity (ISO 18013-5)",
                protocolSummary = "DigitalPaymentCredential + GoldenTransitFareTransaction",
                riskBadge = "NORMAL",
                description = "Demonstrates a custom TransactionDataRendererCompose with interactive FilterChips allowing the commuter to pick between a Single ride, Day pass, or 7-day pass.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main transit fare prompt")
                clickViewDetails()
                capture("tx_details", "Transit fare transaction details page")
            }
        }

        // 2.4 ap2_payment_delegate
        run {
            val (req, trusted) = eco.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_AIRLINES,
                origin = "https://book.utopiaair.example.com",
            )
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction_only_conf"
            }
            val pd = eco.executeCannedDcql(
                cannedRequest = canned,
                transactionDataMap = mapOf(
                    "cred1" to listOf(
                        goldenTransactionData(
                            GoldenAp2MandateTransaction,
                            GoldenAp2MandateTransaction.sampleData
                        )
                    )
                ),
            )
            runScenario(
                categoryDir = cat2Dir,
                categoryTitle = cat2Title,
                categoryDescription = cat2Desc,
                scenarioId = "ap2_payment_delegate",
                title = "AP2 Payment Mandate Delegation (Summary + Signed Mandate Details)",
                requesterSummary = "Utopia Airlines — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "DigitalPaymentCredential + GoldenAp2MandateTransaction",
                riskBadge = "NORMAL",
                description = "Delegating payment authority via an Agent Payments Protocol (AP2) intent mandate so an AI travel assistant can purchase flights up to a spending cap when the fare drops. The summary shows the spending cap, delegate agent, and intent, while the details page exposes all signed mandate fields.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main AP2 delegate mandate prompt")
                clickViewDetails()
                capture("tx_details", "AP2 mandate transaction details page")
            }
        }

        // =========================================================================================
        // Category 3: 03_multi_credential
        // =========================================================================================
        val cat3Dir = "03_multi_credential"
        val cat3Title = "3. Multi-Credential & Optional Combinations"
        val cat3Desc = "Multi-credential requests exercising compound solutions, alternative credential types, and optional use-cases."

        // 3.1 mdl_and_boarding_pass_single_use_case
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_AIRLINES)
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"] }
                      ]
                    },
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${PhotoID.PHOTO_ID_DOCTYPE}" },
                      "claims": [
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "given_name"] },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "family_name"] },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "birth_date"] },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "portrait"] }
                      ]
                    },
                    {
                      "id": "boarding-pass",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${UtopiaBoardingPass.BOARDING_PASS_DOCTYPE}" },
                      "claims": [
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "passenger_name"] },
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "flight_number"] },
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "seat_number"] },
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "departure_time"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [
                        ["mdl", "boarding-pass"],
                        ["photoid", "boarding-pass"]
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat3Dir,
                categoryTitle = cat3Title,
                categoryDescription = cat3Desc,
                scenarioId = "mdl_and_boarding_pass_single_use_case",
                title = "Compound Solution: ID + Boarding Pass in a Single Use-Case",
                requesterSummary = "Utopia Airlines — Proximity (ISO 18013-5)",
                protocolSummary = "(mDL + Boarding Pass) OR (Photo ID + Boarding Pass)",
                riskBadge = "NORMAL",
                description = "A single use-case whose options each pair an identity document with a boarding pass. The chooser displays compound multi-card radio options.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (2 credentials in 1 use-case)")
                clickNodeWithText("Share something else")
                capture("chooser", "Compound solution chooser")
            }
        }

        // 3.2 mdl_or_boarding_pass
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_AIRLINES)
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"] }
                      ]
                    },
                    {
                      "id": "boarding-pass",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${UtopiaBoardingPass.BOARDING_PASS_DOCTYPE}" },
                      "claims": [
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "passenger_name"] },
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "flight_number"] },
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "seat_number"] },
                        { "path": ["${UtopiaBoardingPass.BOARDING_PASS_NS}", "departure_time"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [
                        ["mdl"],
                        ["boarding-pass"]
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat3Dir,
                categoryTitle = cat3Title,
                categoryDescription = cat3Desc,
                scenarioId = "mdl_or_boarding_pass",
                title = "Alternative Credential Types: mDL OR Boarding Pass",
                requesterSummary = "Utopia Airlines Lounge — Proximity (ISO 18013-5)",
                protocolSummary = "mDL OR Boarding Pass",
                riskBadge = "NORMAL",
                description = "A lounge reader accepts either a Driving License or a Boarding Pass to verify lounge entry.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (mDL selected)")
                clickNodeWithText("Erika's Driving License")
                capture("chooser", "Chooser between mDL and Boarding Pass")
            }
        }

        // 3.3 mandatory_mdl_and_optional_loyalty
        run {
            val (req, trusted) = eco.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_CAR_RENTALS,
                origin = "https://reserve.utopiacarrentals.example.com",
            )
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "driving_privileges"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"] }
                      ]
                    },
                    {
                      "id": "loyalty",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${Loyalty.LOYALTY_DOCTYPE}" },
                      "claims": [
                        { "path": ["${Loyalty.LOYALTY_NAMESPACE}", "membership_number"] },
                        { "path": ["${Loyalty.LOYALTY_NAMESPACE}", "tier"] }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "required": true,
                      "options": [ ["mdl"] ]
                    },
                    {
                      "required": false,
                      "options": [ ["loyalty"] ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat3Dir,
                categoryTitle = cat3Title,
                categoryDescription = cat3Desc,
                scenarioId = "mandatory_mdl_and_optional_loyalty",
                title = "Mandatory mDL + Optional Loyalty Card (Opting Out)",
                requesterSummary = "Utopia Car Rentals — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "Required mDL + Optional Loyalty ID",
                riskBadge = "NORMAL",
                description = "Two separate use-cases (driving privileges + identity verification, and loyalty verification) resulting in a compound purpose subtitle ('is requesting your driving privileges, identity, and loyalty membership'). The user can open the optional card's chooser and select 'Don't share anything', dynamically updating the purpose subtitle to 'is requesting your driving privileges and identity'.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main_both_selected", "Main prompt (both selected)")
                clickNodeWithText("Utopia Wholesale Membership")
                capture("chooser_optional", "Optional credential chooser ('Don't share anything')")
                clickNodeWithText("Don't share anything")
                capture("main_optional_omitted", "Main prompt ('Don't share anything' selected)")
            }
        }

        // =========================================================================================
        // Category 4: 04_encryption
        // =========================================================================================
        val cat4Dir = "04_encryption"
        val cat4Title = "4. Claim-Level Encryption (Intermediary + Encryption Target)"
        val cat4Desc = "ISO/IEC 18013-5 second-edition DocRequestEncryption where an intermediary reader forwards encrypted datagroups to an upstream authority."

        // 4.1 trusted_requester_trusted_target
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_AIRLINES)
            val pd = eco.executeBorderCrossingEncryptionRequest(
                intentToRetain = false,
                encryptionTargetRpId = ConsentGoldenEcosystem.RP_CBP,
                encryptionTargetTrusted = true,
            )
            runScenario(
                categoryDir = cat4Dir,
                categoryTitle = cat4Title,
                categoryDescription = cat4Desc,
                scenarioId = "trusted_requester_trusted_target",
                title = "Trusted Intermediary + Trusted Encryption Target (Transient)",
                requesterSummary = "Utopia Airlines -> encrypted for Utopia Border Protection — Proximity (ISO 18013-5)",
                protocolSummary = "Photo ID (plain org.iso.23220.2 + HPKE-encrypted datagroups)",
                riskBadge = "NORMAL",
                description = "Utopia Airlines verifies basic identity while forwarding HPKE-encrypted eMRTD datagroups (SOD, DG1, DG2) to Utopia Border Protection. The Requester Info page displays trust cards and certificate chains for both entities.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (plain + encrypted sections)")
                clickMoreInformation()
                capture("requester_info", "Requester info (requester + encryption target)")
                clickCertificateChain("Encryption target certificate chain")
                capture("enc_target_cert_chain", "Encryption target certificate chain")
            }
        }

        // 4.2 trusted_requester_trusted_target_retained
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_AIRLINES)
            val pd = eco.executeBorderCrossingEncryptionRequest(
                intentToRetain = true,
                encryptionTargetRpId = ConsentGoldenEcosystem.RP_CBP,
                encryptionTargetTrusted = true,
            )
            runScenario(
                categoryDir = cat4Dir,
                categoryTitle = cat4Title,
                categoryDescription = cat4Desc,
                scenarioId = "trusted_requester_trusted_target_retained",
                title = "Trusted Intermediary + Trusted Encryption Target (Retained)",
                requesterSummary = "Utopia Airlines -> encrypted & stored by Utopia Border Protection — Proximity (ISO 18013-5)",
                protocolSummary = "Photo ID (intentToRetain = true on plain & encrypted claims)",
                riskBadge = "NORMAL",
                description = "Same border-crossing flow with intentToRetain = true, changing the encrypted section phrasing to indicate that Utopia Airlines will forward the encrypted claims to be stored by Utopia Border Protection.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (encrypted & stored)")
            }
        }

        // =========================================================================================
        // Category 5: 05_warning_untrusted
        // =========================================================================================
        val cat5Dir = "05_warning_untrusted"
        val cat5Title = "5. Warning (Yellow) — Untrusted Over-the-Internet & App-to-App Requests for Non-PII"
        val cat5Desc = "Over-the-Internet and app-to-app relying parties not in any trust list requesting only low-sensitivity non-PII claims (age booleans + portrait, no PII, no retention, no transaction data). Unlike proximity requests (where the user is physically present and the relying party is free to capture their likeness), over-the-Internet and app-to-app requests render a top-anchored yellow warning box without a 5-second cool-off timer."

        // 5.1 unverified_website_age_over_18
        run {
            val (req, trusted) = eco.unverifiedWebRequester("https://age-check.utopia-gaming.example.com")
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"] }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat5Dir,
                categoryTitle = cat5Title,
                categoryDescription = cat5Desc,
                scenarioId = "unverified_website_age_over_18",
                title = "Unverified Website Requesting Age Over 18",
                requesterSummary = "https://age-check.utopia-gaming.example.com — Unverified Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL (age_over_18, portrait)",
                riskBadge = "WARNING (UNTRUSTED_REQUESTER)",
                description = "An over-the-Internet website origin not in the wallet's trust list requests age_over_18 and portrait. Unlike a proximity request, an over-the-Internet request for non-PII triggers a WARNING: the headline displays the origin URL and the yellow warning box states 'Website is not in a trust list'.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (untrusted website warning)")
                clickMoreInformation()
                capture("requester_info", "Requester info (Website origin + client ID)")
            }
        }

        // 5.2 unverified_app_age_over_21
        run {
            val (req, trusted) = eco.unverifiedAppRequester(ConsentGoldenEcosystem.APP_NIGHTCLUB_SCANNER)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat5Dir,
                categoryTitle = cat5Title,
                categoryDescription = cat5Desc,
                scenarioId = "unverified_app_age_over_21",
                title = "Unverified Local Android App Requesting Age Over 21",
                requesterSummary = "Utopia Nightclub Scanner (org.utopia.nightclub.scanner) — App-to-app",
                protocolSummary = "mDL (age_over_21, portrait)",
                riskBadge = "WARNING (UNTRUSTED_REQUESTER)",
                description = "An app-to-app request from a local Android application not in a trust list requesting age_over_21 and portrait. Unlike a proximity request, an app-to-app request for non-PII triggers a WARNING: the prompt resolves the app name and icon via PackageManager and displays 'Application is not in a trust list'.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (untrusted app warning)")
            }
        }

        // =========================================================================================
        // Category 6: 06_high_risk
        // =========================================================================================
        val cat6Dir = "06_high_risk"
        val cat6Title = "6. High-Risk (Red) & Cool-Off Safeguards"
        val cat6Desc = "Requests triggering ConsentRiskLevel.HIGH_RISK due to untrusted PII/retention/transactions, over-broad/unusual claim combinations, mismatched purpose hints, or untrusted encryption targets. Enforces a 5-second cool-off timer on the Continue button."

        // 6.1 untrusted_requester_pii_requested
        run {
            val (req, trusted) = eco.unverifiedProximityRequester(ConsentGoldenEcosystem.RP_UNVERIFIED_LOUNGE)
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"] }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat6Dir,
                categoryTitle = cat6Title,
                categoryDescription = cat6Desc,
                scenarioId = "untrusted_requester_pii_requested",
                title = "Untrusted Requester Asking for Personally Identifiable Information (PII)",
                requesterSummary = "Harbor View Lounge — Unverified Proximity (ISO 18013-5)",
                protocolSummary = "mDL (given_name, family_name, birth_date, portrait)",
                riskBadge = "HIGH_RISK (UNTRUSTED_REQUESTER, PII_REQUESTED)",
                description = "An untrusted verifier requests PII attributes. A red danger box is anchored at the top and the primary button is disabled in the 'Wait' cool-off state for 5 seconds before transitioning to 'Continue'. The user can tap 'More information' to inspect the unverified requester details.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main_waiting", "Main prompt ('Wait' cool-off active)")
                advanceCoolOffTimer()
                capture("main_ready", "Main prompt ('Continue' enabled after 5s)")
                clickMoreInformation()
                capture("requester_info", "Requester info ('Unverified requester')")
            }
        }

        // 6.2 trusted_requester_unusual_overbroad
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_VINEYARDS)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "full" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat6Dir,
                categoryTitle = cat6Title,
                categoryDescription = cat6Desc,
                scenarioId = "trusted_requester_unusual_overbroad",
                title = "Trusted Requester Making an Over-Broad / Unusual Request (Scroll + Cool-Off)",
                requesterSummary = "Utopia Vineyards — Proximity (ISO 18013-5)",
                protocolSummary = "mDL (all 20+ data elements)",
                riskBadge = "HIGH_RISK (UNUSUAL_REQUEST)",
                description = "Even though Utopia Vineyards is in the trust list, requesting every mDL attribute cannot be classified into any standard PresentmentPurpose. Because the claim list overflows the viewport, the user must first scroll to the bottom ('More') before the 5-second 'Wait' cool-off timer starts.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main_top", "Top of prompt ('More' scroll button)")
                scrollToBottomViaMoreButton()
                capture("main_scrolled_waiting", "Scrolled to bottom ('Wait' cool-off)")
                advanceCoolOffTimer()
                capture("main_scrolled_ready", "Cool-off complete ('Continue' enabled)")
            }
        }

        // 6.3 trusted_requester_mismatched_purpose_hint
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_VINEYARDS)
            // Hint says AGE_VERIFICATION (1), but claims include resident_address & document_number
            val pd = eco.executeMdocRequestWithPurposeHints(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "portrait" to false,
                        "given_name" to false,
                        "family_name" to false,
                        "resident_address" to false,
                    )
                ),
                purposeHints = mapOf("org.iso.18013.5.1.mDL" to 1),
            )
            runScenario(
                categoryDir = cat6Dir,
                categoryTitle = cat6Title,
                categoryDescription = cat6Desc,
                scenarioId = "trusted_requester_mismatched_purpose_hint",
                title = "Trusted Requester with Mismatched ISO 18013-5 Purpose Hint",
                requesterSummary = "Utopia Vineyards — Proximity (ISO 18013-5)",
                protocolSummary = "mDL (purposeHint = Age Verification, but requests name & address)",
                riskBadge = "HIGH_RISK (UNUSUAL_REQUEST)",
                description = "The reader declares the ISO 18013-5 purposeHint for Age Verification (1) in DeviceRequestInfo, but actually requests given_name, family_name, and resident_address.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (mismatched purpose hint danger)")
            }
        }

        // 6.4 trusted_requester_untrusted_encryption_target
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_AIRLINES)
            val pd = eco.executeBorderCrossingEncryptionRequest(
                intentToRetain = false,
                encryptionTargetRpId = ConsentGoldenEcosystem.RP_UNVERIFIED_BROKER,
                encryptionTargetTrusted = false,
            )
            runScenario(
                categoryDir = cat6Dir,
                categoryTitle = cat6Title,
                categoryDescription = cat6Desc,
                scenarioId = "trusted_requester_untrusted_encryption_target",
                title = "Trusted Intermediary + Untrusted Encryption Target",
                requesterSummary = "Utopia Airlines -> encrypted for Unverified Recipient — Proximity (ISO 18013-5)",
                protocolSummary = "Photo ID (plain org.iso.23220.2 + encrypted datagroups to untrusted key)",
                riskBadge = "HIGH_RISK (UNTRUSTED_ENCRYPTION_TARGET)",
                description = "Utopia Airlines is trusted, but the encryption recipient certificate for the passport datagroups is not in any trust list.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (untrusted encryption target danger)")
                clickMoreInformation()
                capture("requester_info", "Requester info (verified requester + unverified target)")
            }
        }

        // 6.5 anonymous_requester_trusted_target
        run {
            val (req, trusted) = eco.anonymousProximityRequester()
            val pd = eco.executeBorderCrossingEncryptionRequest(
                intentToRetain = false,
                encryptionTargetRpId = ConsentGoldenEcosystem.RP_CBP,
                encryptionTargetTrusted = true,
            )
            // Note: Even though the datagroups are encrypted for a trusted target (Utopia Border
            // Protection), the anonymous intermediary still has knowledge of the transaction, so
            // defaultClassifyRisk classifies this as HIGH_RISK (UNTRUSTED_REQUESTER, PII_REQUESTED).
            // Like anything else, this is policy and a wallet could choose to classify this as
            // ConsentRiskLevel.NORMAL if its threat model accepts an untrusted intermediary
            // forwarding encrypted claims to a trusted encryption target.
            val encryptedOnlyPd = PresentmentData(
                useCases = pd.useCases.map { uc ->
                    uc.copy(
                        solutions = uc.solutions.map { sol ->
                            sol.copy(matches = sol.matches.filter { it.encryptionRequested })
                        }
                    )
                }
            )
            runScenario(
                categoryDir = cat6Dir,
                categoryTitle = cat6Title,
                categoryDescription = cat6Desc,
                scenarioId = "anonymous_requester_trusted_target",
                title = "Anonymous Intermediary Kiosk + Trusted Encryption Target",
                requesterSummary = "Anonymous Kiosk -> encrypted for Utopia Border Protection — Proximity (ISO 18013-5)",
                protocolSummary = "Photo ID (HPKE-encrypted datagroups only)",
                riskBadge = "HIGH_RISK (UNTRUSTED_REQUESTER, PII_REQUESTED)",
                description = "An unauthenticated airport kiosk forwards HPKE-encrypted passport datagroups to a verified Utopia Border Protection key. Because the anonymous intermediary still has knowledge of the transaction, the default policy classifies this as HIGH_RISK (though a wallet whose threat model accepts an untrusted intermediary forwarding encrypted claims to a trusted target could override classifyRiskFn to classify this as NORMAL).",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = encryptedOnlyPd,
            ) {
                capture("main", "Main prompt (anonymous kiosk + trusted encryption target)")
                clickMoreInformation()
                capture("requester_info", "Requester info (anonymous requester + verified target)")
            }
        }

        // 6.6 untrusted_requester_intent_to_retain
        run {
            val (req, trusted) = eco.unverifiedWebRequester("https://age-check.utopia-gaming.example.com")
            val pd = eco.executeMdocRequestWithPurposeHints(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to true,
                        "portrait" to true,
                    )
                ),
                purposeHints = emptyMap(),
            )
            runScenario(
                categoryDir = cat6Dir,
                categoryTitle = cat6Title,
                categoryDescription = cat6Desc,
                scenarioId = "untrusted_requester_intent_to_retain",
                title = "Untrusted Over-the-Internet Requester Asking to Retain Portrait (intentToRetain = true)",
                requesterSummary = "https://age-check.utopia-gaming.example.com — Unverified Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL (age_over_21 & portrait with intentToRetain = true)",
                riskBadge = "HIGH_RISK (UNTRUSTED_REQUESTER, INTENT_TO_RETAIN)",
                description = "An unverified over-the-Internet website requests age_over_21 and portrait with intentToRetain = true. For non-proximity requests, retaining a claim with sensitivity >= PORTRAIT_IMAGE escalates the prompt from WARNING to HIGH_RISK (whereas for proximity requests, INTENT_TO_RETAIN only triggers for sensitivity >= PII).",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (untrusted retention danger)")
            }
        }

        // 6.6 untrusted_requester_transaction_data
        run {
            val (req, trusted) = eco.unverifiedWebRequester("https://pay.unverified-merchant.example.com")
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction_only_conf"
            }
            val pd = eco.executeCannedDcql(
                cannedRequest = canned,
                transactionDataMap = mapOf(
                    "cred1" to listOf(
                        utopiaPaymentTransactionData(
                            payeeName = "Unverified Utopia Merchant",
                            amount = 299.99,
                            tipRequested = false,
                        )
                    )
                ),
            )
            runScenario(
                categoryDir = cat6Dir,
                categoryTitle = cat6Title,
                categoryDescription = cat6Desc,
                scenarioId = "untrusted_requester_transaction_data",
                title = "Untrusted Requester Attaching Transaction Data",
                requesterSummary = "https://pay.unverified-merchant.example.com — Unverified Over-the-Internet (ISO 18013-7)",
                protocolSummary = "DigitalPaymentCredential + PaymentTransaction",
                riskBadge = "HIGH_RISK (UNTRUSTED_REQUESTER, TRANSACTION_DATA)",
                description = "An untrusted website attaches a payment transaction payload, triggering ConsentRiskReason.TRANSACTION_DATA.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
            ) {
                capture("main", "Main prompt (untrusted transaction danger)")
            }
        }

        // =========================================================================================
        // Category 7: 07_custom_policy
        // =========================================================================================
        val cat7Dir = "07_custom_policy"
        val cat7Title = "7. Custom Risk & Purpose Classification Callbacks"
        val cat7Desc = "Demonstrates overriding classifyPurposesFn and classifyRiskFn on Consent to enforce custom wallet security policies."

        // 7.1 custom_warning_for_trusted_rp_retaining_data
        run {
            val (req, trusted) = eco.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_MARKETPLACE,
                origin = "https://checkout.utopiamarketplace.example.com",
            )
            val canned = DrivingLicense.getDocumentType().cannedRequests.first {
                it.id == "name-and-address-all-stored"
            }
            val pd = eco.executeCannedDcql(canned)
            val strictRetentionRiskFn: ClassifyRiskFn = { r, t, data, sels, purposes ->
                val base = defaultClassifyRisk(r, t, data, sels, purposes)
                val retainsAny = data.useCases.indices.any { idx ->
                    val solIdx = sels.getOrElse(idx) { -1 }
                    solIdx >= 0 && data.useCases[idx].solutions[solIdx].matches.any { match ->
                        match.claims.keys.any { (it as? MdocRequestedClaim)?.intentToRetain == true }
                    }
                }
                if (base.level == ConsentRiskLevel.NORMAL && retainsAny) {
                    ConsentRiskAssessment(
                        level = ConsentRiskLevel.WARNING,
                        reasons = setOf(ConsentRiskReason.INTENT_TO_RETAIN),
                    )
                } else {
                    base
                }
            }
            runScenario(
                categoryDir = cat7Dir,
                categoryTitle = cat7Title,
                categoryDescription = cat7Desc,
                scenarioId = "custom_warning_for_trusted_rp_retaining_data",
                title = "Custom Policy: Elevate Trusted RP Data Retention to WARNING",
                requesterSummary = "Utopia Marketplace — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL (retained name & address) + custom classifyRiskFn",
                riskBadge = "WARNING (INTENT_TO_RETAIN via custom classifyRiskFn)",
                description = "A privacy-focused wallet overrides classifyRiskFn so that even when a relying party is in a trust list, any request with intentToRetain = true displays a top yellow warning box highlighting data retention.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                classifyRiskFn = strictRetentionRiskFn,
            ) {
                capture("main", "Main prompt (custom retention warning)")
            }
        }

        // 7.2 custom_normal_for_allowlisted_first_party_app
        run {
            val (req, trusted) = eco.unverifiedAppRequester(ConsentGoldenEcosystem.APP_WALLET_VERIFIER)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            val allowlistAppRiskFn: ClassifyRiskFn = { r, t, data, sels, purposes ->
                if (r.appId == ConsentGoldenEcosystem.APP_WALLET_VERIFIER) {
                    ConsentRiskAssessment(
                        level = ConsentRiskLevel.NORMAL,
                        reasons = emptySet(),
                    )
                } else {
                    defaultClassifyRisk(r, t, data, sels, purposes)
                }
            }
            runScenario(
                categoryDir = cat7Dir,
                categoryTitle = cat7Title,
                categoryDescription = cat7Desc,
                scenarioId = "custom_normal_for_allowlisted_first_party_app",
                title = "Custom Policy: Allowlist First-Party Verifier App as NORMAL",
                requesterSummary = "Utopia Wallet Verifier (gov.utopia.wallet.verifier) — App-to-app",
                protocolSummary = "mDL (age_over_21, portrait) + custom classifyRiskFn",
                riskBadge = "NORMAL (via custom classifyRiskFn)",
                description = "A wallet overrides classifyRiskFn to treat its companion first-party verifier app (gov.utopia.wallet.verifier) as NORMAL even when invoked without an X.509 reader certificate.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                classifyRiskFn = allowlistAppRiskFn,
            ) {
                capture("main", "Main prompt (allowlisted app as NORMAL)")
            }
        }

        // =========================================================================================
        // Category 8: 08_dark_mode
        // =========================================================================================
        val cat8Dir = "08_dark_mode"
        val cat8Title = "8. Dark Theme Coverage"
        val cat8Desc = "Verifies dark-theme surface contrast, segmented container colors, warning/danger box palettes, and sub-page legibility."

        // 8.1 normal_age_verification
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_VINEYARDS)
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat8Dir,
                categoryTitle = cat8Title,
                categoryDescription = cat8Desc,
                scenarioId = "normal_age_verification",
                title = "Dark Mode — Normal Age Verification",
                requesterSummary = "Utopia Vineyards — Proximity (ISO 18013-5)",
                protocolSummary = "mDL (age_over_21, portrait)",
                riskBadge = "NORMAL (Dark Theme)",
                description = "Dark mode rendering of the standard NORMAL consent prompt with surfaceContainerHigh segmented containers.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                darkTheme = true,
            ) {
                capture("main", "Main prompt (Dark mode NORMAL)")
            }
        }

        // 8.2 warning_untrusted_website
        run {
            val (req, trusted) = eco.unverifiedWebRequester("https://age-check.utopia-gaming.example.com")
            val canned = DrivingLicense.getDocumentType().cannedRequests.first { it.id == "age_over_21_and_portrait" }
            val pd = eco.executeCannedDcql(canned)
            runScenario(
                categoryDir = cat8Dir,
                categoryTitle = cat8Title,
                categoryDescription = cat8Desc,
                scenarioId = "warning_untrusted_website",
                title = "Dark Mode — Warning (Untrusted Over-the-Internet Requester)",
                requesterSummary = "https://age-check.utopia-gaming.example.com — Unverified Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL (age_over_21, portrait)",
                riskBadge = "WARNING (Dark Theme)",
                description = "Dark mode rendering of the amber warning box (#3E3012 fill / #F9AB00 border) for an untrusted over-the-Internet request.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                darkTheme = true,
            ) {
                capture("main", "Main prompt (Dark mode WARNING)")
            }
        }

        // 8.3 high_risk_pii_requested
        run {
            val (req, trusted) = eco.unverifiedProximityRequester(ConsentGoldenEcosystem.RP_UNVERIFIED_LOUNGE)
            val pd = eco.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"] },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"] }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat8Dir,
                categoryTitle = cat8Title,
                categoryDescription = cat8Desc,
                scenarioId = "high_risk_pii_requested",
                title = "Dark Mode — High-Risk (Untrusted Reader Requesting PII)",
                requesterSummary = "Harbor View Lounge — Unverified Proximity (ISO 18013-5)",
                protocolSummary = "mDL (given_name, family_name, birth_date, portrait)",
                riskBadge = "HIGH_RISK (Dark Theme)",
                description = "Dark mode rendering of the red danger box (#4A1C1A fill / #B3261E border) and disabled 'Wait' cool-off button.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                darkTheme = true,
            ) {
                capture("main", "Main prompt (Dark mode HIGH_RISK)")
            }
        }

        // 8.4 requester_info_with_encryption_target
        run {
            val (req, trusted) = eco.trustedProximityRequester(ConsentGoldenEcosystem.RP_AIRLINES)
            val pd = eco.executeBorderCrossingEncryptionRequest(
                intentToRetain = false,
                encryptionTargetRpId = ConsentGoldenEcosystem.RP_CBP,
                encryptionTargetTrusted = true,
            )
            runScenario(
                categoryDir = cat8Dir,
                categoryTitle = cat8Title,
                categoryDescription = cat8Desc,
                scenarioId = "requester_info_with_encryption_target",
                title = "Dark Mode — Requester Info Page with Encryption Target",
                requesterSummary = "Utopia Airlines -> Utopia Border Protection — Proximity (ISO 18013-5)",
                protocolSummary = "Photo ID (plain + encrypted datagroups)",
                riskBadge = "NORMAL (Dark Theme)",
                description = "Dark mode rendering of the Requester Info sub-page showing verified requester and encryption target trust cards, history, and certificate chain entries.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                darkTheme = true,
            ) {
                clickMoreInformation()
                capture("requester_info", "Requester info page (Dark mode)")
            }
        }

        // =========================================================================================
        // Category 9: 09_localization
        // =========================================================================================
        val cat9Dir = "09_localization"
        val cat9Title = "9. Localization — Danish & Right-to-Left (Arabic)"
        val cat9Desc = "Verifies complete translation of all Consent UI strings, localized document/claim names, date formatting, and Right-to-Left (RTL) layout mirroring across Danish (da-DK) and Arabic (ar-SA)."

        val localeDa = Locale.forLanguageTag("da-DK")
        val localeAr = Locale.forLanguageTag("ar-SA")
        val ecoDa = ConsentGoldenEcosystem.create(localeDa)
        val ecoAr = ConsentGoldenEcosystem.create(localeAr)

        // 9.1 danish_normal_with_subpages
        run {
            val (req, trusted) = ecoDa.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_CAR_RENTALS,
                origin = "https://utopiacarrentals.com",
            )
            val pd = ecoDa.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_21"], "intent_to_retain": false },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"], "intent_to_retain": false }
                      ]
                    },
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${PhotoID.PHOTO_ID_DOCTYPE}" },
                      "claims": [
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "given_name"], "intent_to_retain": true },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "family_name"], "intent_to_retain": true },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "age_over_21"], "intent_to_retain": false },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "portrait"], "intent_to_retain": false }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [["mdl"], ["photoid"]]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat9Dir,
                categoryTitle = cat9Title,
                categoryDescription = cat9Desc,
                scenarioId = "danish_normal_with_subpages",
                title = "Danish (da-DK) — Full Flow Across Main, Details, Chooser, Requester Info & Certificate Chain",
                requesterSummary = "Utopia Car Rentals — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL OR Photo ID with partial retention",
                riskBadge = "NORMAL (da-DK)",
                description = "Full Danish (da-DK) localization of the main consent prompt (with both shared and stored claim sections, credential switcher, and returning-user RP info box), Document Details ('Se detaljer' -> 'Detaljer'), Document Chooser ('Vælg hvad der skal deles'), Requester Info ('Anmoders information'), and X.509 Certificate Chain viewer ('Certifikatkæde').",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                requesterHistory = ConsentGoldenEcosystem.returningUserHistory(shareCount = 3),
                locale = localeDa,
            ) {
                capture("main", "Main prompt (Danish da-DK)")
                clickViewDetails("Se detaljer")
                capture("doc_details", "Document details page (Danish da-DK)")
                clickBack("Tilbage")
                clickNodeWithText("Erikas kørekort")
                capture("chooser", "Document chooser page (Danish da-DK)")
                clickBack("Tilbage")
                clickMoreInformation("Flere oplysninger")
                capture("requester_info", "Requester info page (Danish da-DK)")
                clickCertificateChain("Certifikatkæde")
                capture("cert_chain", "X.509 Certificate chain viewer (Danish da-DK)")
            }
        }

        // 9.2 danish_payment_with_tip
        run {
            val (req, trusted) = ecoDa.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_MARKETPLACE,
                origin = "https://checkout.utopiamarketplace.example.com",
            )
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction"
            }
            val pd = ecoDa.executeCannedDcql(
                cannedRequest = canned,
                transactionDataMap = mapOf(
                    "cred1" to listOf(
                        utopiaPaymentTransactionData(
                            payeeName = "Utopia Marketplace",
                            amount = 42.50,
                            tipRequested = true,
                        )
                    )
                ),
            )
            runScenario(
                categoryDir = cat9Dir,
                categoryTitle = cat9Title,
                categoryDescription = cat9Desc,
                scenarioId = "danish_payment_with_tip",
                title = "Danish (da-DK) — Payment Transaction with Tip Selector",
                requesterSummary = "Utopia Marketplace — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "Digital Payment Credential + PaymentTransaction ($42.50 USD)",
                riskBadge = "NORMAL (da-DK)",
                description = "Danish localization of the built-in PaymentTransaction card showing payee ('Modtager'), amount ('Beløb: 42.50 USD' -> 'Beløb: 51.00 USD (drikkepenge: 8.50 USD)'), and tip selector chips ('Tilføj drikkepenge', 'Ingen drikkepenge').",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                locale = localeDa,
            ) {
                capture("main", "Payment prompt with 'Ingen drikkepenge' selected (Danish da-DK)")
                clickNodeWithText("20%")
                capture("tip_20_percent", "Payment prompt with 20% tip selected (Danish da-DK)")
            }
        }

        // 9.3 danish_high_risk_pii_and_retention
        run {
            val (req, trusted) = ecoDa.unverifiedProximityRequester(ConsentGoldenEcosystem.RP_UNVERIFIED_LOUNGE)
            val pd = ecoDa.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"], "intent_to_retain": true }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat9Dir,
                categoryTitle = cat9Title,
                categoryDescription = cat9Desc,
                scenarioId = "danish_high_risk_pii_and_retention",
                title = "Danish (da-DK) — High-Risk Untrusted Reader Requesting PII & Retention",
                requesterSummary = "Harbor View Lounge — Unverified Proximity (ISO 18013-5)",
                protocolSummary = "mDL (given_name, family_name, birth_date, portrait) retained",
                riskBadge = "HIGH_RISK (da-DK)",
                description = "Danish localization of the red high-risk warning card (with bullets for PII requested and intent to retain) and the 3-second 'Vent…' -> 'Fortsæt' cool-off button.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                locale = localeDa,
            ) {
                capture("waiting", "High-risk prompt during cool-off ('Vent…', Danish da-DK)")
                advanceCoolOffTimer()
                capture("ready", "High-risk prompt after cool-off ('Fortsæt', Danish da-DK)")
            }
        }

        // 9.4 arabic_rtl_normal_with_subpages
        run {
            val (req, trusted) = ecoAr.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_CAR_RENTALS,
                origin = "https://utopiacarrentals.com",
            )
            val pd = ecoAr.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_21"], "intent_to_retain": false },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"], "intent_to_retain": false }
                      ]
                    },
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${PhotoID.PHOTO_ID_DOCTYPE}" },
                      "claims": [
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "given_name"], "intent_to_retain": true },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "family_name"], "intent_to_retain": true },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "age_over_21"], "intent_to_retain": false },
                        { "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "portrait"], "intent_to_retain": false }
                      ]
                    }
                  ],
                  "credential_sets": [
                    {
                      "options": [["mdl"], ["photoid"]]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat9Dir,
                categoryTitle = cat9Title,
                categoryDescription = cat9Desc,
                scenarioId = "arabic_rtl_normal_with_subpages",
                title = "Arabic RTL (ar-SA) — Full Flow Across Main, Details, Chooser, Requester Info & Certificate Chain",
                requesterSummary = "Utopia Car Rentals — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "mDL OR Photo ID with partial retention",
                riskBadge = "NORMAL (ar-SA RTL)",
                description = "Full Arabic (ar-SA) Right-to-Left (RTL) rendering across the main consent prompt, Document Details, Document Chooser, Requester Info, and X.509 Certificate Chain viewer. Verifies RTL row alignment, right-aligned Arabic typography, and auto-mirrored navigation/chevron/external-link icons.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                requesterHistory = ConsentGoldenEcosystem.returningUserHistory(shareCount = 3),
                locale = localeAr,
            ) {
                capture("main", "Main prompt (Arabic ar-SA RTL)")
                clickViewDetails("عرض التفاصيل")
                capture("doc_details", "Document details page (Arabic ar-SA RTL)")
                clickBack("رجوع")
                clickNodeWithText("رخصة قيادة إريكا")
                capture("chooser", "Document chooser page (Arabic ar-SA RTL)")
                clickBack("رجوع")
                clickMoreInformation("مزيد من المعلومات")
                capture("requester_info", "Requester info page (Arabic ar-SA RTL)")
                clickCertificateChain("سلسلة الشهادات")
                capture("cert_chain", "X.509 Certificate chain viewer (Arabic ar-SA RTL)")
            }
        }

        // 9.5 arabic_rtl_payment_with_tip
        run {
            val (req, trusted) = ecoAr.trustedWebRequester(
                rpId = ConsentGoldenEcosystem.RP_MARKETPLACE,
                origin = "https://checkout.utopiamarketplace.example.com",
            )
            val canned = DigitalPaymentCredential.getDocumentType().cannedRequests.first {
                it.id == "payment_transaction"
            }
            val pd = ecoAr.executeCannedDcql(
                cannedRequest = canned,
                transactionDataMap = mapOf(
                    "cred1" to listOf(
                        utopiaPaymentTransactionData(
                            payeeName = "Utopia Marketplace",
                            amount = 42.50,
                            tipRequested = true,
                        )
                    )
                ),
            )
            runScenario(
                categoryDir = cat9Dir,
                categoryTitle = cat9Title,
                categoryDescription = cat9Desc,
                scenarioId = "arabic_rtl_payment_with_tip",
                title = "Arabic RTL (ar-SA) — Payment Transaction with Tip Selector",
                requesterSummary = "Utopia Marketplace — Over-the-Internet (ISO 18013-7)",
                protocolSummary = "Digital Payment Credential + PaymentTransaction ($42.50 USD)",
                riskBadge = "NORMAL (ar-SA RTL)",
                description = "Arabic RTL rendering of the built-in PaymentTransaction card with right-aligned payee/amount rows and RTL tip selector chips.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                locale = localeAr,
            ) {
                capture("main", "Payment prompt with 'بدون إكرامية' selected (Arabic ar-SA RTL)")
                clickNodeWithText("20%")
                capture("tip_20_percent", "Payment prompt with 20% tip selected (Arabic ar-SA RTL)")
            }
        }

        // 9.6 arabic_rtl_high_risk_pii_and_retention
        run {
            val (req, trusted) = ecoAr.unverifiedProximityRequester(ConsentGoldenEcosystem.RP_UNVERIFIED_LOUNGE)
            val pd = ecoAr.executeDcql(
                """
                {
                  "credentials": [
                    {
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                      "claims": [
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"], "intent_to_retain": true },
                        { "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"], "intent_to_retain": true }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            runScenario(
                categoryDir = cat9Dir,
                categoryTitle = cat9Title,
                categoryDescription = cat9Desc,
                scenarioId = "arabic_rtl_high_risk_pii_and_retention",
                title = "Arabic RTL (ar-SA) — High-Risk Untrusted Reader with RTL Cool-Off Progress Bar",
                requesterSummary = "Harbor View Lounge — Unverified Proximity (ISO 18013-5)",
                protocolSummary = "mDL (given_name, family_name, birth_date, portrait) retained",
                riskBadge = "HIGH_RISK (ar-SA RTL)",
                description = "Arabic RTL rendering of the red high-risk warning card, RTL button placement ('إلغاء' on the right, 'انتظر…' / 'متابعة' on the left), and the 3-second cool-off progress bar filling from right to left.",
                requester = req,
                trustedRequesterIdentity = trusted,
                presentmentData = pd,
                locale = localeAr,
            ) {
                capture("waiting", "High-risk prompt during cool-off with RTL progress fill (Arabic ar-SA RTL)")
                advanceCoolOffTimer()
                capture("ready", "High-risk prompt after cool-off ('متابعة', Arabic ar-SA RTL)")
            }
        }

        if (updateGoldens) {
            ConsentGoldenCatalogGenerator.generate(goldenRootDir, recordedScenarios)
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "Golden screenshot mismatches (${mismatches.size}):\n" +
                    mismatches.joinToString("\n") +
                    "\nRun `./gradlew :multipaz-compose:testDebugUnitTest -PupdateGoldens=true` to update golden masters."
            )
        }
    }

    private inner class ScenarioStepScope(
        private val categoryDir: String,
        private val scenarioId: String,
        private val goldenRootDir: File,
        private val updateGoldens: Boolean,
        private val steps: MutableList<GoldenStepRecord>,
        private val mismatches: MutableList<String>,
        private val rootViewProvider: () -> View,
    ) {
        private var previousBitmap: Bitmap? = null
        private var previousStepId: String? = null

        fun capture(stepId: String, label: String) {
            composeTestRule.waitForIdle()
            val bitmap = composeTestRule.runOnIdle {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val view = rootViewProvider()
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                view.draw(canvas)
                bmp
            }
            val prevBmp = previousBitmap
            if (prevBmp != null && compareBitmaps(prevBmp, bitmap, channelTolerance = 0, maxDiffFraction = 0.0) == null) {
                fail(
                    "Step '$stepId' in '$categoryDir/$scenarioId' produced an identical screenshot to " +
                        "previous step '$previousStepId' — did a click/navigation action no-op?"
                )
            }
            previousBitmap = bitmap
            previousStepId = stepId

            val relativePath = "$categoryDir/${scenarioId}__${stepId}.png"
            val goldenFile = File(goldenRootDir, relativePath)
            steps.add(GoldenStepRecord(stepId = stepId, label = label, relativePath = relativePath))

            if (updateGoldens) {
                goldenFile.parentFile?.mkdirs()
                FileOutputStream(goldenFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } else {
                if (!goldenFile.exists()) {
                    mismatches.add("Missing golden file: ${goldenFile.path}")
                    return
                }
                val expected = BitmapFactory.decodeFile(goldenFile.absolutePath)
                val diff = compareBitmaps(expected, bitmap)
                if (diff != null) {
                    val actualFile = File(goldenRootDir, "$categoryDir/${scenarioId}__${stepId}.actual.png")
                    FileOutputStream(actualFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    mismatches.add("$relativePath: $diff (wrote ${actualFile.name})")
                }
            }
        }

        private fun settleUi() {
            if (!composeTestRule.mainClock.autoAdvance) {
                repeat(20) {
                    Snapshot.sendApplyNotifications()
                    composeTestRule.mainClock.advanceTimeBy(50L)
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            }
            composeTestRule.waitForIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        fun clickViewDetails(text: String = "View details", index: Int = 0) {
            composeTestRule.onAllNodes(hasText(text))[index].performScrollTo().performClick()
            settleUi()
        }

        fun clickMoreInformation(linkText: String = "More information") {
            val interaction = composeTestRule.onNodeWithText(linkText, substring = true)
                .performScrollTo()
            val node = interaction.fetchSemanticsNode()
            val layoutResults = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layoutResults)
            val textLayout = layoutResults.firstOrNull()
            if (textLayout != null) {
                val textStr = textLayout.layoutInput.text.text
                val linkIdx = textStr.indexOf(linkText)
                if (linkIdx >= 0) {
                    val charBox = textLayout.getBoundingBox(linkIdx + (linkText.length / 2))
                    interaction.performTouchInput { click(charBox.center) }
                    settleUi()
                    return
                }
            }
            interaction.performClick()
            settleUi()
        }

        fun clickCertificateChain(title: String) {
            composeTestRule.onNodeWithText(title).performScrollTo().performClick()
            settleUi()
        }

        fun clickNodeWithText(text: String) {
            composeTestRule.onNodeWithText(text, substring = true).performScrollTo().performClick()
            settleUi()
        }

        fun clickBack(contentDescription: String = "Back") {
            composeTestRule.onNodeWithContentDescription(contentDescription).performClick()
            settleUi()
        }

        fun scrollToBottomViaMoreButton() {
            repeat(6) {
                val moreNodes = composeTestRule.onAllNodes(hasText("More")).fetchSemanticsNodes()
                if (moreNodes.isNotEmpty()) {
                    composeTestRule.onNodeWithText("More").performClick()
                    settleUi()
                }
            }
        }

        fun advanceCoolOffTimer() {
            repeat(90) {
                Snapshot.sendApplyNotifications()
                composeTestRule.mainClock.advanceTimeBy(50L)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            composeTestRule.waitForIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun resolveGoldenRootDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return if (userDir.name == "multipaz-compose") {
            File(userDir, "golden-screenshots")
        } else {
            File(userDir, "multipaz-compose/golden-screenshots")
        }
    }

    private fun compareBitmaps(
        expected: Bitmap,
        actual: Bitmap,
        channelTolerance: Int = 2,
        maxDiffFraction: Double = 0.001,
    ): String? {
        if (expected.width != actual.width || expected.height != actual.height) {
            return "Dimension mismatch: expected ${expected.width}x${expected.height}, got ${actual.width}x${actual.height}"
        }
        val totalPixels = expected.width * expected.height
        val expectedPixels = IntArray(totalPixels)
        val actualPixels = IntArray(totalPixels)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)

        var differingPixels = 0
        for (i in 0 until totalPixels) {
            val e = expectedPixels[i]
            val a = actualPixels[i]
            if (e != a) {
                val da = abs(((e ushr 24) and 0xFF) - ((a ushr 24) and 0xFF))
                val dr = abs(((e ushr 16) and 0xFF) - ((a ushr 16) and 0xFF))
                val dg = abs(((e ushr 8) and 0xFF) - ((a ushr 8) and 0xFF))
                val db = abs((e and 0xFF) - (a and 0xFF))
                if (da > channelTolerance || dr > channelTolerance || dg > channelTolerance || db > channelTolerance) {
                    differingPixels++
                }
            }
        }
        val fraction = differingPixels.toDouble() / totalPixels.toDouble()
        return if (fraction > maxDiffFraction) {
            "$differingPixels / $totalPixels pixels differed (${(fraction * 100.0).format2()}%)"
        } else {
            null
        }
    }

    private fun Double.format2(): String = String.format(Locale.US, "%.3f", this)
}
