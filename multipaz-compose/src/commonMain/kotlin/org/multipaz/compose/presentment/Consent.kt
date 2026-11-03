package org.multipaz.compose.presentment

import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Payment
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.multipaz.claim.Claim
import org.multipaz.compose.ApplicationInfo
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.compose.claim.RenderClaimValue
import org.multipaz.compose.datetime.formattedDateTime
import org.multipaz.compose.decodeImage
import org.multipaz.compose.getApplicationInfo
import org.multipaz.compose.getOutlinedImageVector
import org.multipaz.compose.text.fromMarkdown
import org.multipaz.consent.ClassifyPurposesFn
import org.multipaz.consent.ClassifyRiskFn
import org.multipaz.consent.ConsentRiskAssessment
import org.multipaz.consent.ConsentRiskLevel
import org.multipaz.consent.ConsentRiskReason
import org.multipaz.consent.PresentmentPurpose
import org.multipaz.consent.defaultClassifyPurposes
import org.multipaz.consent.defaultClassifyRisk
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_and_n_more
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_back
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_cancel
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_more
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_wait
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_continue
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_select_option
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_dont_return_any_document
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_data_element_icon_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_details
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_view_details
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_unknown_website
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_more_information
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_more_information_and_privacy_policy
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_no_credentials_available
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_no_document_returned
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_generic_tx_also_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_generic_tx_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_payment_tx_add_tip
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_payment_tx_also_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_payment_tx_amount
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_payment_tx_amount_with_tip
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_payment_tx_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_payment_tx_no_tip
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_payment_tx_payee
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_ping_tx_also_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_ping_tx_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_ping_tx_blob
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_ping_tx_string
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_address_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_age_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_driving_privileges
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_identity_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_address_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_age_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_bare_address_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_bare_age_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_bare_driving_privileges
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_bare_identity_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_bare_loyalty_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_bare_ticket_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_driving_privileges
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_identity_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_loyalty_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_payment
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_item_ticket_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_list_separator
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_loyalty_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_multiple_items
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_payment
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_ticket_verification
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_two_items
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_purpose_unknown
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_first_time
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_first_time_unnamed
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_anonymous_body
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_anonymous_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_application
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_certificate_chain
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_certificate_chain_numbered
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_client_id
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_anonymous_body
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_anonymous_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_certificate_chain
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_certificate_chain_numbered
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_unverified_body
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_unverified_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_verified_body
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_enc_target_verified_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_first_shared
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_history_header
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_issued_by
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_last_shared
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_n_certificates
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_no_history_body
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_no_history_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_privacy_policy_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_shared_n_times
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_shared_once
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_single_certificate
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_technical_details_header
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_test_only
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_unverified_body
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_unverified_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_verified_body
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_verified_title
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_info_website_origin
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_information
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_shared_before
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_shared_before_unnamed
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_known_requester_and_known_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_known_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_unknown_requester_and_known_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_unknown_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_known_requester_and_known_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_known_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_unknown_requester_and_known_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_unknown_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_verifier_icon_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_intent_to_retain
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_pii_requested
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_transaction_data
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_untrusted_encryption_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_unusual_request
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_anonymous
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_app
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_website
import org.multipaz.compose.items.SEGMENT_GAP
import org.multipaz.compose.items.SegmentedItemContainer
import org.multipaz.presentment.PresentmentCredentialMatch
import org.multipaz.presentment.PresentmentCredentialMatchSourceIso18013
import org.multipaz.presentment.PresentmentCredentialMatchSourceOpenID4VP
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.PresentmentUseCase
import org.multipaz.presentment.RequesterHistory
import org.multipaz.presentment.RequesterHistoryEntry
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionDataContext
import org.multipaz.prompt.TransactionDataRendererRepository
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.utopia.knowntypes.PingTransaction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.round

private const val TAG = "Consent"

/**
 * A composable used for obtaining consent when presenting one or more credentials.
 *
 * ### Purpose Classification (`classifyPurposesFn`)
 *
 * [Consent] evaluates the active credential selection using [classifyPurposesFn], which returns a
 * set of [PresentmentPurpose]s:
 * - **Subtitle text**: The classified purposes are localized and formatted into the purpose
 *   subtitle displayed under the relying party header (e.g., "is requesting your age", "is
 *   requesting your driving privileges and identity"). When multiple purposes are returned, they
 *   are joined into a localized list in enum declaration order. If an empty set is returned, a
 *   fallback unknown purpose phrase is shown.
 * - **Forwarded to risk assessment**: The returned purposes set is forwarded directly as the
 *   `purposes` argument to [classifyRiskFn].
 *
 * Defaults to [defaultClassifyPurposes].
 *
 * ### Risk Assessment (`classifyRiskFn`)
 *
 * [Consent] evaluates the active credential selection and classified purposes using [classifyRiskFn],
 * which returns a [ConsentRiskAssessment] containing a [ConsentRiskLevel] and a set of
 * [ConsentRiskReason]s.
 *
 * #### Risk Levels
 *
 * The evaluated [ConsentRiskLevel] governs the placement, visual styling, and confirmation
 * safeguards of the RP-info box:
 *
 * - **[ConsentRiskLevel.NORMAL]**:
 *   - **Placement & styling**: Placed at the **bottom** of the scrollable content using a neutral
 *     surface container (no colored fill or border) with an info (`ⓘ`) icon. When there is only a
 *     single use-case, the RP-info box is attached as the bottom segment of that use-case's
 *     segmented container group; when there are multiple use-cases, it appears as a standalone
 *     segmented container below all use-cases.
 *   - **Confirmation**: The "Continue" button is immediately enabled without any cool-off delay.
 *
 * - **[ConsentRiskLevel.WARNING]**:
 *   - **Placement & styling**: Placed at the **top** of the scrollable content (directly below the
 *     relying party header and above the credentials) in a yellow/amber warning box with a warning
 *     (`⚠`) icon.
 *   - **Confirmation**: The "Continue" button is immediately enabled without any cool-off delay.
 *
 * - **[ConsentRiskLevel.HIGH_RISK]**:
 *   - **Placement & styling**: Placed at the **top** of the scrollable content (directly below the
 *     relying party header and above the credentials) in a red danger box with a warning (`⚠`) icon.
 *   - **Confirmation**: Enforces a 5-second cool-off timer on the primary button ("Wait..."
 *     transitioning to "Continue"). The user must scroll to the bottom of the prompt before the
 *     timer begins.
 *
 * #### Risk Reasons
 *
 * Any [ConsentRiskReason]s in [ConsentRiskAssessment.reasons] generate corresponding explanatory copy
 * inside the RP-info box (or warning/danger box):
 * - [ConsentRiskReason.UNTRUSTED_REQUESTER]: Displays copy indicating that the relying party is
 *   unknown or not in the wallet's trust list. (Rendered as informational copy in the bottom
 *   RP-info container when risk level is [ConsentRiskLevel.NORMAL], or as a warning when [ConsentRiskLevel.WARNING]
 *   or [ConsentRiskLevel.HIGH_RISK]).
 * - [ConsentRiskReason.ANONYMOUS_REQUESTER]: Displays copy indicating that the relying party is
 *   anonymous (did not provide a digital signature or certificate). (Rendered as informational
 *   copy in the bottom RP-info container when risk level is [ConsentRiskLevel.NORMAL], or as a warning
 *   when [ConsentRiskLevel.WARNING] or [ConsentRiskLevel.HIGH_RISK]).
 * - [ConsentRiskReason.UNUSUAL_REQUEST]: Displays an alert that the requested claims are unrecognized,
 *   over-broad, or not accounted for by standard schemas or purpose hints.
 * - [ConsentRiskReason.PII_REQUESTED]: Displays an alert that personally identifiable information has
 *   been requested.
 * - [ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET]: Displays an alert that parts of the response are
 *   requested to be encrypted for a recipient not in the trust list.
 * - [ConsentRiskReason.INTENT_TO_RETAIN]: Displays an alert that the requester intends to store or
 *   retain the disclosed data.
 * - [ConsentRiskReason.TRANSACTION_DATA]: Displays an alert that the request authorizes a transaction.
 *
 * In addition to reason-specific alerts, the RP-info box displays share history (first-time vs.
 * returning share, if [requesterHistory] is provided), a link to the relying party's privacy
 * policy (if available), and any [TrustMetadata.disclaimer].
 *
 * Defaults to [defaultClassifyRisk].
 *
 * @param modifier a [Modifier].
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity conveys the level of trust in the requester, if any.
 * @param presentmentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param imageLoader a [ImageLoader].
 * @param transactionDataRendererRepository the [TransactionDataRendererRepository] to find renderers for
 *   transaction data in, or `null` to use only the built-in rendering.
 * @param requesterHistory an optional [RequesterHistory] used to query whether the user has shared
 *   credentials with [requester] before.
 * @param classifyPurposesFn function used to classify the [PresentmentPurpose]s of the request.
 * @param classifyRiskFn function used to evaluate the [ConsentRiskAssessment] of the request.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @param onConfirm called when the user presses the "Continue" button, returns the user's selection.
 * @param onCancel called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Consent(
    modifier: Modifier = Modifier,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    transactionDataRendererRepository: TransactionDataRendererRepository? = null,
    requesterHistory: RequesterHistory? = null,
    classifyPurposesFn: ClassifyPurposesFn = ::defaultClassifyPurposes,
    classifyRiskFn: ClassifyRiskFn = ::defaultClassifyRisk,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onConfirm: (selection: PresentmentSelection) -> Unit,
    onCancel: () -> Unit = {},
) {
    val navController = rememberNavController()
    val appInfo = remember {
        requester.appId?.let {
            try {
                getApplicationInfo(it)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error looking up information for appId $it")
                null
            }
        }
    }

    val initialSelections = remember(presentmentData, preselectedDocuments) {
        presentmentData.useCases.map { useCase ->
            if (preselectedDocuments.isEmpty()) {
                if (useCase.solutions.isNotEmpty()) 0 else -1
            } else {
                val preselectedSet = preselectedDocuments.toSet()
                val matchingIndex = useCase.solutions.indexOfFirst { solution ->
                    solution.matches.isNotEmpty() && solution.matches.all {
                        it.credential.document in preselectedSet
                    }
                }
                if (matchingIndex != -1) {
                    matchingIndex
                } else if (useCase.optional) {
                    -1
                } else {
                    if (useCase.solutions.isNotEmpty()) 0 else -1
                }
            }
        }
    }

    var selections by remember(initialSelections) { mutableStateOf(initialSelections) }
    var transactionUserInput by remember {
        mutableStateOf(emptyMap<PresentmentCredentialMatch, Map<String, TransactionUserInput>>())
    }
    var activeUseCaseIndex by remember { mutableStateOf(0) }
    var coolOffComplete by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var documentDetails by remember { mutableStateOf<DocumentDetailsTarget?>(null) }
    var transactionDetails by remember { mutableStateOf<TransactionDetailsTarget?>(null) }
    var selectedCertChain by remember { mutableStateOf<X509CertChain?>(null) }
    var requesterHistoryEntry by remember { mutableStateOf<RequesterHistoryEntry?>(null) }
    var requesterHistoryLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(requester, trustedRequesterIdentity, requesterHistory) {
        if (requesterHistory != null) {
            requesterHistoryEntry = requesterHistory.getEntry(requester, trustedRequesterIdentity)
            requesterHistoryLoaded = true
        } else {
            requesterHistoryEntry = null
            requesterHistoryLoaded = false
        }
    }

    val onTransactionUserInputChanged: (PresentmentCredentialMatch, String, TransactionUserInput) -> Unit =
        { match, type, userInput ->
            val current = transactionUserInput[match] ?: emptyMap()
            transactionUserInput = transactionUserInput.plus(
                match to current.plus(type to userInput)
            )
        }

    // TODO: it seems that we do not need to worry about transactionUserInput here; if we actually
    //  do, we should add it BOTH as key to remember and as a parameter to toPresentmentSelection
    val currentSelection = remember(selections, presentmentData) {
        presentmentData.toPresentmentSelection(selections, emptyMap())
    }
    val purposes = remember(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        selections,
        classifyPurposesFn,
    ) {
        classifyPurposesFn(
            requester,
            trustedRequesterIdentity,
            presentmentData,
            selections,
        )
    }
    val riskAssessment = remember(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        selections,
        purposes,
        classifyRiskFn,
    ) {
        classifyRiskFn(
            requester,
            trustedRequesterIdentity,
            presentmentData,
            selections,
            purposes,
        )
    }
    val currentDocumentsInFocus = remember(currentSelection) {
        currentSelection.matches.map { it.credential.document }.distinctBy { it.identifier }
    }
    val encryptionTargets = remember(currentSelection) {
        currentSelection.matches
            .filter { it.encryptionRequested }
            .map {
                EncryptionTargetInfo(
                    certChain = it.encryptionTargetCertificationChain,
                    trustMetadata = it.encryptionTargetTrustMetadata,
                )
            }
            .distinct()
    }
    LaunchedEffect(currentDocumentsInFocus) {
        onDocumentsInFocus(currentDocumentsInFocus)
    }

    val trustMetadata = trustedRequesterIdentity?.trustMetadata
    val requesterDisplayData = if (trustMetadata != null) {
        RequesterDisplayData(
            name = trustMetadata.displayName,
            icon = trustMetadata.displayIcon?.let { remember(it) { it.toByteArray().decodeToImageBitmap() } },
            iconUrl = trustMetadata.displayIconUrl,
            disclaimer = trustMetadata.disclaimer,
        )
    } else if (requester.isWebOrigin) {
        RequesterDisplayData(
            name = requester.origin!!.ifEmpty {
                stringResource(Res.string.credential_presentment_headline_share_with_unknown_website)
            },
        )
    } else if (appInfo != null) {
        RequesterDisplayData(
            name = appInfo.name,
            icon = appInfo.icon
        )
    } else {
        RequesterDisplayData()
    }

    Column(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 0.dp)
    ) {
        NavHost(
            navController = navController,
            startDestination = "main",
            modifier = Modifier.fillMaxWidth(),
            enterTransition = {
                fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { it / 6 }
            },
            exitTransition = {
                fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 6 }
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing))
            },
            sizeTransform = {
                SizeTransform(clip = false) { _, _ ->
                    tween(durationMillis = 300, easing = FastOutSlowInEasing)
                }
            }
        ) {
            composable("main") {
                ConsentPage(
                    requester = requester,
                    trustedRequesterIdentity = trustedRequesterIdentity,
                    requesterDisplayData = requesterDisplayData,
                    appInfo = appInfo,
                    imageLoader = imageLoader,
                    presentmentData = presentmentData,
                    selections = selections,
                    riskAssessment = riskAssessment,
                    purposes = purposes,
                    scrollState = scrollState,
                    coolOffComplete = coolOffComplete,
                    onCoolOffComplete = { coolOffComplete = true },
                    requesterHistoryLoaded = requesterHistoryLoaded,
                    requesterHistoryEntry = requesterHistoryEntry,
                    transactionUserInput = transactionUserInput,
                    transactionDataRendererRepository = transactionDataRendererRepository,
                    onTransactionUserInputChanged = onTransactionUserInputChanged,
                    onShowRequesterInfo = {
                        navController.navigate("showRequesterInfo")
                    },
                    onShowDocumentDetails = { target ->
                        documentDetails = target
                        navController.navigate("documentDetails")
                    },
                    onShowTransactionDetails = { target ->
                        transactionDetails = target
                        navController.navigate("transactionDetails")
                    },
                    onNavigateToPickSolution = { index ->
                        activeUseCaseIndex = index
                        navController.navigate("pickSolution")
                    },
                    onConfirm = onConfirm,
                    onCancel = onCancel
                )
            }

            composable("showRequesterInfo") {
                ShowRequesterInfoPage(
                    requester = requester,
                    trustedRequesterIdentity = trustedRequesterIdentity,
                    requesterDisplayData = requesterDisplayData,
                    encryptionTargets = encryptionTargets,
                    appInfo = appInfo,
                    imageLoader = imageLoader,
                    requesterHistoryLoaded = requesterHistoryLoaded,
                    requesterHistoryEntry = requesterHistoryEntry,
                    onShowCertChain = { certChain ->
                        selectedCertChain = certChain
                        navController.navigate("certificateChain")
                    },
                    onBackClicked = {
                        navController.navigateUp()
                    },
                )
            }

            composable("certificateChain") {
                selectedCertChain?.let { certChain ->
                    CertificateChainPage(
                        certChain = certChain,
                        onBackClicked = {
                            navController.navigateUp()
                        },
                    )
                }
            }

            composable("documentDetails") {
                documentDetails?.let { target ->
                    val useCase = presentmentData.useCases.getOrNull(target.useCaseIndex)
                    if (useCase != null) {
                        DocumentDetailsPage(
                            useCase = useCase,
                            selectionIndex = selections[target.useCaseIndex],
                            matchIndex = target.matchIndex,
                            requester = requester,
                            requesterDisplayData = requesterDisplayData,
                            appInfo = appInfo,
                            onBackClicked = {
                                navController.navigateUp()
                            },
                        )
                    }
                }
            }

            composable("transactionDetails") {
                transactionDetails?.let { target ->
                    TransactionDetailsPage(
                        target = target,
                        requester = requester,
                        trustedRequesterIdentity = trustedRequesterIdentity,
                        purposes = purposes,
                        userInput = transactionUserInput[target.match]?.get(target.block.identifier),
                        onUserInputChanged = { userInput ->
                            onTransactionUserInputChanged(target.match, target.block.identifier, userInput)
                        },
                        onBackClicked = {
                            navController.navigateUp()
                        },
                    )
                }
            }

            composable("pickSolution") {
                val useCase = presentmentData.useCases.getOrNull(activeUseCaseIndex)
                if (useCase != null) {
                    PickSolutionPage(
                        useCase = useCase,
                        currentSolutionIndex = selections[activeUseCaseIndex],
                        onBackClicked = {
                            navController.navigateUp()
                        },
                        onSolutionSelected = { solutionIndex ->
                            val newList = selections.toMutableList()
                            newList[activeUseCaseIndex] = solutionIndex
                            selections = newList
                            navController.navigateUp()
                        }
                    )
                }
            }
        }
    }
}

private data class EncryptionTargetInfo(
    val certChain: X509CertChain?,
    val trustMetadata: TrustMetadata?,
)

@Composable
private fun ShowRequesterInfoPage(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    requesterDisplayData: RequesterDisplayData,
    encryptionTargets: List<EncryptionTargetInfo>,
    appInfo: ApplicationInfo?,
    imageLoader: ImageLoader?,
    requesterHistoryLoaded: Boolean,
    requesterHistoryEntry: RequesterHistoryEntry?,
    onShowCertChain: (X509CertChain) -> Unit,
    onBackClicked: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val trustMetadata = trustedRequesterIdentity?.trustMetadata
    val requesterName = requesterDisplayData.name
        ?: trustedRequesterIdentity?.identity?.certChain?.certificates?.lastOrNull()?.subject?.name
        ?: if (requester.requesterIdentities.isEmpty()) {
            stringResource(Res.string.credential_presentment_requester_info_anonymous_title)
        } else {
            stringResource(Res.string.credential_presentment_headline_share_with_unknown_requester)
        }

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.credential_presentment_back)
                )
            }
            Text(
                text = stringResource(Res.string.credential_presentment_requester_information),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Trust & Verification Summary + Privacy Policy
            val privacyPolicyUrl = trustMetadata?.privacyPolicyUrl
            val topGroupCount = if (privacyPolicyUrl != null) 2 else 1
            Column(
                verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
            ) {
                SegmentedItemContainer(
                    index = 0,
                    count = topGroupCount,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (requesterDisplayData.icon != null) {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                bitmap = requesterDisplayData.icon,
                                contentDescription = stringResource(
                                    Res.string.credential_presentment_verifier_icon_description
                                ),
                                tint = Color.Unspecified,
                            )
                        } else if (requesterDisplayData.iconUrl != null && imageLoader != null) {
                            AsyncImage(
                                modifier = Modifier.size(40.dp),
                                model = requesterDisplayData.iconUrl,
                                imageLoader = imageLoader,
                                contentScale = ContentScale.Crop,
                                contentDescription = stringResource(
                                    Res.string.credential_presentment_verifier_icon_description
                                ),
                            )
                        } else {
                            Icon(
                                imageVector = if (trustedRequesterIdentity != null) {
                                    Icons.Outlined.VerifiedUser
                                } else {
                                    Icons.Outlined.Warning
                                },
                                contentDescription = null,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1.0f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val titleText = when {
                                trustedRequesterIdentity != null -> stringResource(
                                    Res.string.credential_presentment_requester_info_verified_title,
                                    requesterName
                                )
                                requester.requesterIdentities.isNotEmpty() -> stringResource(
                                    Res.string.credential_presentment_requester_info_unverified_title
                                )
                                else -> stringResource(
                                    Res.string.credential_presentment_requester_info_anonymous_title
                                )
                            }
                            val bodyText = when {
                                trustedRequesterIdentity != null -> stringResource(
                                    Res.string.credential_presentment_requester_info_verified_body,
                                    requesterName
                                )
                                requester.requesterIdentities.isNotEmpty() -> stringResource(
                                    Res.string.credential_presentment_requester_info_unverified_body
                                )
                                else -> stringResource(
                                    Res.string.credential_presentment_requester_info_anonymous_body
                                )
                            }
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = bodyText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (trustMetadata?.testOnly == true) {
                                Text(
                                    text = stringResource(Res.string.credential_presentment_requester_info_test_only),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val disclaimer = trustMetadata?.disclaimer
                            if (disclaimer != null) {
                                Text(
                                    text = disclaimer,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (privacyPolicyUrl != null) {
                    SegmentedItemContainer(
                        index = 1,
                        count = topGroupCount,
                        modifier = Modifier.clickable {
                            uriHandler.openUri(privacyPolicyUrl)
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Policy,
                                contentDescription = null,
                            )
                            Column(
                                modifier = Modifier.weight(1.0f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        Res.string.credential_presentment_requester_info_privacy_policy_title,
                                        requesterName
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = privacyPolicyUrl,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            encryptionTargets.forEach { target ->
                EncryptionTargetTrustGroup(
                    target = target,
                    imageLoader = imageLoader,
                )
            }

            // 2. Past Interactions
            if (requesterHistoryLoaded && isRequesterIdentifiable(requester, trustedRequesterIdentity)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
                ) {
                    Text(
                        modifier = Modifier.padding(bottom = 8.dp),
                        text = stringResource(Res.string.credential_presentment_requester_info_history_header),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SegmentedItemContainer(
                        index = 0,
                        count = 1,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                            )
                            Column(
                                modifier = Modifier.weight(1.0f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (requesterHistoryEntry != null) {
                                    val countTitle = if (requesterHistoryEntry.shareCount == 1) {
                                        stringResource(Res.string.credential_presentment_requester_info_shared_once)
                                    } else {
                                        stringResource(
                                            Res.string.credential_presentment_requester_info_shared_n_times,
                                            requesterHistoryEntry.shareCount
                                        )
                                    }
                                    Text(
                                        text = countTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (requesterHistoryEntry.shareCount > 1) {
                                        val firstSharedStr = formattedDateTime(
                                            instant = requesterHistoryEntry.firstSharedAt,
                                            includeDurationFromNow = true
                                        ).text
                                        Text(
                                            text = stringResource(
                                                Res.string.credential_presentment_requester_info_first_shared,
                                                firstSharedStr
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    val lastSharedStr = formattedDateTime(
                                        instant = requesterHistoryEntry.lastSharedAt,
                                        includeDurationFromNow = true
                                    ).text
                                    Text(
                                        text = stringResource(
                                            Res.string.credential_presentment_requester_info_last_shared,
                                            lastSharedStr
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(
                                            Res.string.credential_presentment_requester_info_no_history_title
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(
                                            Res.string.credential_presentment_requester_info_no_history_body
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Technical Details
            val websiteOrigin = requester.origin?.takeIf { requester.isWebOrigin && it.isNotEmpty() }
            val appDisplay = requester.appId?.let { appId ->
                if (appInfo != null) "${appInfo.name} ($appId)" else appId
            }
            val identities = if (requester.requesterIdentities.isNotEmpty()) {
                requester.requesterIdentities
            } else {
                listOfNotNull(trustedRequesterIdentity?.identity)
            }
            val encryptionTargetCertChains = encryptionTargets.mapNotNull { it.certChain }.distinct()
            val techSegmentCount = (if (websiteOrigin != null) 1 else 0) +
                (if (appDisplay != null) 1 else 0) +
                identities.size +
                encryptionTargetCertChains.size

            if (techSegmentCount > 0) {
                var techIdx = 0
                Column(
                    verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
                ) {
                    Text(
                        modifier = Modifier.padding(bottom = 8.dp),
                        text = stringResource(
                            Res.string.credential_presentment_requester_info_technical_details_header
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (websiteOrigin != null) {
                        val idx = techIdx++
                        SegmentedItemContainer(
                            index = idx,
                            count = techSegmentCount,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Language,
                                    contentDescription = null,
                                )
                                Column(
                                    modifier = Modifier.weight(1.0f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            Res.string.credential_presentment_requester_info_website_origin
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = websiteOrigin,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (appDisplay != null) {
                        val idx = techIdx++
                        SegmentedItemContainer(
                            index = idx,
                            count = techSegmentCount,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Apps,
                                    contentDescription = null,
                                )
                                Column(
                                    modifier = Modifier.weight(1.0f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            Res.string.credential_presentment_requester_info_application
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = appDisplay,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    identities.forEachIndexed { chainIndex, identity ->
                        val idx = techIdx++
                        val chainTitle = if (identities.size == 1) {
                            stringResource(Res.string.credential_presentment_requester_info_certificate_chain)
                        } else {
                            stringResource(
                                Res.string.credential_presentment_requester_info_certificate_chain_numbered,
                                chainIndex + 1
                            )
                        }
                        CertificateChainSegment(
                            index = idx,
                            count = techSegmentCount,
                            title = chainTitle,
                            certChain = identity.certChain,
                            clientId = (identity as? OpenID4VPRequesterIdentity)?.clientId,
                            onShowCertChain = onShowCertChain,
                        )
                    }
                    encryptionTargetCertChains.forEachIndexed { chainIndex, certChain ->
                        val idx = techIdx++
                        val chainTitle = if (encryptionTargetCertChains.size == 1) {
                            stringResource(Res.string.credential_presentment_requester_info_enc_target_certificate_chain)
                        } else {
                            stringResource(
                                Res.string.credential_presentment_requester_info_enc_target_certificate_chain_numbered,
                                chainIndex + 1
                            )
                        }
                        CertificateChainSegment(
                            index = idx,
                            count = techSegmentCount,
                            title = chainTitle,
                            certChain = certChain,
                            clientId = null,
                            onShowCertChain = onShowCertChain,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EncryptionTargetTrustGroup(
    target: EncryptionTargetInfo,
    imageLoader: ImageLoader?,
) {
    val uriHandler = LocalUriHandler.current
    val trustMetadata = target.trustMetadata
    val targetName = trustMetadata?.displayName
        ?: target.certChain?.certificates?.lastOrNull()?.subject?.name
        ?: if (target.certChain == null) {
            stringResource(Res.string.credential_presentment_requester_info_anonymous_title)
        } else {
            stringResource(Res.string.credential_presentment_headline_share_with_unknown_requester)
        }
    val targetIcon = trustMetadata?.displayIcon?.let {
        remember(it) { it.toByteArray().decodeToImageBitmap() }
    }
    val targetIconUrl = trustMetadata?.displayIconUrl
    val privacyPolicyUrl = trustMetadata?.privacyPolicyUrl
    val groupCount = if (privacyPolicyUrl != null) 2 else 1

    Column(
        verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
    ) {
        SegmentedItemContainer(
            index = 0,
            count = groupCount,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (targetIcon != null) {
                    Icon(
                        modifier = Modifier.size(40.dp),
                        bitmap = targetIcon,
                        contentDescription = stringResource(
                            Res.string.credential_presentment_verifier_icon_description
                        ),
                        tint = Color.Unspecified,
                    )
                } else if (targetIconUrl != null && imageLoader != null) {
                    AsyncImage(
                        modifier = Modifier.size(40.dp),
                        model = targetIconUrl,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        contentDescription = stringResource(
                            Res.string.credential_presentment_verifier_icon_description
                        ),
                    )
                } else {
                    Icon(
                        imageVector = if (trustMetadata != null) {
                            Icons.Outlined.VerifiedUser
                        } else {
                            Icons.Outlined.Warning
                        },
                        contentDescription = null,
                    )
                }
                Column(
                    modifier = Modifier.weight(1.0f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val titleText = when {
                        trustMetadata != null -> stringResource(
                            Res.string.credential_presentment_requester_info_enc_target_verified_title,
                            targetName
                        )
                        target.certChain != null -> stringResource(
                            Res.string.credential_presentment_requester_info_enc_target_unverified_title
                        )
                        else -> stringResource(
                            Res.string.credential_presentment_requester_info_enc_target_anonymous_title
                        )
                    }
                    val bodyText = when {
                        trustMetadata != null -> stringResource(
                            Res.string.credential_presentment_requester_info_enc_target_verified_body,
                            targetName
                        )
                        target.certChain != null -> stringResource(
                            Res.string.credential_presentment_requester_info_enc_target_unverified_body
                        )
                        else -> stringResource(
                            Res.string.credential_presentment_requester_info_enc_target_anonymous_body
                        )
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (trustMetadata?.testOnly == true) {
                        Text(
                            text = stringResource(Res.string.credential_presentment_requester_info_test_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val disclaimer = trustMetadata?.disclaimer
                    if (disclaimer != null) {
                        Text(
                            text = disclaimer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (privacyPolicyUrl != null) {
            SegmentedItemContainer(
                index = 1,
                count = groupCount,
                modifier = Modifier.clickable {
                    uriHandler.openUri(privacyPolicyUrl)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Policy,
                        contentDescription = null,
                    )
                    Column(
                        modifier = Modifier.weight(1.0f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(
                                Res.string.credential_presentment_requester_info_privacy_policy_title,
                                targetName
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = privacyPolicyUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CertificateChainSegment(
    index: Int,
    count: Int,
    title: String,
    certChain: X509CertChain,
    clientId: String?,
    onShowCertChain: (X509CertChain) -> Unit,
) {
    val certs = certChain.certificates
    val leafName = certs.firstOrNull()?.subject?.name
    val rootName = certs.lastOrNull()?.subject?.name
    val subjectSummary = when {
        leafName != null && rootName != null && leafName != rootName -> stringResource(
            Res.string.credential_presentment_requester_info_issued_by,
            leafName,
            rootName
        )
        leafName != null -> leafName
        rootName != null -> rootName
        else -> null
    }
    val certCountSummary = if (certs.size == 1) {
        stringResource(Res.string.credential_presentment_requester_info_single_certificate)
    } else {
        stringResource(Res.string.credential_presentment_requester_info_n_certificates, certs.size)
    }
    SegmentedItemContainer(
        index = index,
        count = count,
        modifier = Modifier.clickable {
            onShowCertChain(certChain)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Badge,
                contentDescription = null,
            )
            Column(
                modifier = Modifier.weight(1.0f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subjectSummary != null) {
                    Text(
                        text = subjectSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (clientId != null) {
                    Text(
                        text = stringResource(
                            Res.string.credential_presentment_requester_info_client_id,
                            clientId
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = certCountSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CertificateChainPage(
    certChain: X509CertChain,
    onBackClicked: () -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.credential_presentment_back)
                )
            }
            Text(
                text = stringResource(Res.string.credential_presentment_requester_info_certificate_chain),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        X509CertViewer(
            modifier = Modifier.padding(top = 12.dp),
            certChain = certChain,
        )
    }
}

private data class RequesterDisplayData(
    val name: String? = null,
    val icon: ImageBitmap? = null,
    val iconUrl: String? = null,
    val disclaimer: String? = null
)

/** Identifies the use-case and match index that the document details page shows. */
private data class DocumentDetailsTarget(
    val useCaseIndex: Int,
    val matchIndex: Int,
)

private fun PresentmentCredentialMatch.resolveTypeDisplayName(): String =
    credential.document.typeDisplayName
        ?: when (val src = source) {
            is PresentmentCredentialMatchSourceIso18013 ->
                src.docRequest.docType
            is PresentmentCredentialMatchSourceOpenID4VP ->
                src.credentialQuery.mdocDocType
                    ?: src.credentialQuery.vctValues?.firstOrNull()
                    ?: src.credentialQuery.id
        }

/**
 * A page inside the consent prompt showing the selected credential along with all requested
 * claims and their actual values.
 */
@Composable
private fun DocumentDetailsPage(
    useCase: PresentmentUseCase,
    selectionIndex: Int,
    matchIndex: Int,
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    appInfo: ApplicationInfo?,
    onBackClicked: () -> Unit,
) {
    val isSelected = selectionIndex >= 0
    val solutionIndexToShow = if (isSelected) selectionIndex else 0
    val solution = useCase.solutions.getOrNull(solutionIndexToShow)
    val targetMatch = solution?.matches?.getOrNull(matchIndex) ?: solution?.matches?.firstOrNull()
    val group = if (solution != null && targetMatch != null) {
        groupMatchesByDocument(solution.matches)
            .firstOrNull { it.document.identifier == targetMatch.credential.document.identifier }
    } else {
        null
    }

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.credential_presentment_back)
                )
            }
            Text(
                text = stringResource(Res.string.credential_presentment_details),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (group != null && isSelected) {
                SegmentedItemContainer(
                    index = 0,
                    count = 1,
                ) {
                    CredentialViewer(
                        credential = group.credential,
                        showChevron = false,
                        onChevronClicked = {}
                    )
                }

                for (section in group.claimSections) {
                    val sectionHeader = calcSharedWithText(
                        requester = requester,
                        requesterDisplayData = requesterDisplayData,
                        appInfo = appInfo,
                        storesData = section.storesData,
                        encryptionRequested = section.encryptionRequested,
                        encryptionTargetTrustMetadata = section.encryptionTargetTrustMetadata
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
                    ) {
                        Text(
                            modifier = Modifier.padding(bottom = 8.dp),
                            text = sectionHeader,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        section.claims.forEachIndexed { claimIndex, claim ->
                            val icon = claim.attribute?.icon ?: Icon.PERSON
                            SegmentedItemContainer(
                                index = claimIndex,
                                count = section.claims.size,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.Start),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icon.getOutlinedImageVector(),
                                        contentDescription = stringResource(
                                            Res.string.credential_presentment_data_element_icon_description
                                        )
                                    )
                                    Column(
                                        modifier = Modifier.weight(1.0f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = claim.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        RenderClaimValue(
                                            claim = claim,
                                            imageSize = 100.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The transaction data the details page shows: one application-drawn group of one credential. */
private class TransactionDetailsTarget(
    val match: PresentmentCredentialMatch,
    val block: TransactionDataBlock.Wallet,
    val hasClaims: Boolean,
)

/**
 * A page inside the consent prompt showing the application's details view for one group of
 * transaction data. It has no confirm button: the user goes back to the main page to decide.
 */
@Composable
private fun TransactionDetailsPage(
    target: TransactionDetailsTarget,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    purposes: Set<PresentmentPurpose>,
    userInput: TransactionUserInput?,
    onUserInputChanged: (userInput: TransactionUserInput) -> Unit,
    onBackClicked: () -> Unit,
) {
    val details = target.block.renderer.details ?: return
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.credential_presentment_back)
                )
            }
            Text(
                text = stringResource(Res.string.credential_presentment_details),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            details(
                TransactionDataContext(
                    transactionData = target.block.items,
                    requester = requester,
                    trustedRequesterIdentity = trustedRequesterIdentity,
                    hasClaims = target.hasClaims,
                    purposes = purposes,
                    userInput = userInput,
                    onUserInputChanged = onUserInputChanged,
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentPage(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    requesterDisplayData: RequesterDisplayData,
    appInfo: ApplicationInfo?,
    imageLoader: ImageLoader?,
    presentmentData: PresentmentData,
    selections: List<Int>,
    riskAssessment: ConsentRiskAssessment,
    purposes: Set<PresentmentPurpose>,
    scrollState: ScrollState,
    coolOffComplete: Boolean,
    onCoolOffComplete: () -> Unit,
    requesterHistoryLoaded: Boolean,
    requesterHistoryEntry: RequesterHistoryEntry?,
    transactionUserInput: Map<PresentmentCredentialMatch, Map<String, TransactionUserInput>>,
    transactionDataRendererRepository: TransactionDataRendererRepository?,
    onTransactionUserInputChanged: (PresentmentCredentialMatch, String, TransactionUserInput) -> Unit,
    onShowRequesterInfo: () -> Unit,
    onShowDocumentDetails: (DocumentDetailsTarget) -> Unit,
    onShowTransactionDetails: (TransactionDetailsTarget) -> Unit,
    onNavigateToPickSolution: (Int) -> Unit,
    onConfirm: (selection: PresentmentSelection) -> Unit,
    onCancel: () -> Unit
) {
    val trustMetadata = trustedRequesterIdentity?.trustMetadata
    val riskLevel = riskAssessment.level

    Column(
        modifier = Modifier.animateContentSize(
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
        )
    ) {
        RelyingPartySection(
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            requesterDisplayData = requesterDisplayData,
            imageLoader = imageLoader,
            purposes = purposes,
        )

        Column(
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Column(
                modifier = Modifier
                    .focusGroup()
                    .verticalScroll(scrollState)
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val hasEncryptionTargetPrivacyPolicy = presentmentData.useCases.indices.any { index ->
                    val selIndex = selections.getOrElse(index) { -1 }
                    if (selIndex < 0) {
                        false
                    } else {
                        val solution = presentmentData.useCases[index].solutions.getOrNull(selIndex)
                        solution?.matches?.any { match ->
                            match.encryptionRequested &&
                                match.encryptionTargetTrustMetadata?.privacyPolicyUrl != null
                        } ?: false
                    }
                }
                val rpSentences = calcRelyingPartySentences(
                    requester = requester,
                    trustedRequesterIdentity = trustedRequesterIdentity,
                    trustMetadata = trustMetadata,
                    appInfo = appInfo,
                    showUntrustedWarning = ConsentRiskReason.UNTRUSTED_REQUESTER in riskAssessment.reasons,
                    showAnonymousWarning = ConsentRiskReason.ANONYMOUS_REQUESTER in riskAssessment.reasons,
                    requesterHistoryLoaded = requesterHistoryLoaded,
                    requesterHistoryEntry = requesterHistoryEntry,
                    hasEncryptionTargetPrivacyPolicy = hasEncryptionTargetPrivacyPolicy,
                )
                val rpParagraphs = buildList {
                    if (rpSentences.isNotEmpty()) {
                        add(rpSentences.joinToString(". ") { it.trimEnd('.') } + ".")
                    }
                    for (reason in riskAssessment.reasons) {
                        when (reason) {
                            ConsentRiskReason.UNTRUSTED_REQUESTER,
                            ConsentRiskReason.ANONYMOUS_REQUESTER -> {}
                            ConsentRiskReason.UNUSUAL_REQUEST ->
                                add(stringResource(Res.string.credential_presentment_warning_unusual_request))
                            ConsentRiskReason.PII_REQUESTED ->
                                add(stringResource(Res.string.credential_presentment_warning_pii_requested))
                            ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET ->
                                add(
                                    stringResource(
                                        Res.string.credential_presentment_warning_untrusted_encryption_target
                                    )
                                )
                            ConsentRiskReason.INTENT_TO_RETAIN ->
                                add(stringResource(Res.string.credential_presentment_warning_intent_to_retain))
                            ConsentRiskReason.TRANSACTION_DATA ->
                                add(stringResource(Res.string.credential_presentment_warning_transaction_data))
                        }
                    }
                }
                val hasRpInfo = rpParagraphs.isNotEmpty() || requesterDisplayData.disclaimer != null

                if (riskLevel != ConsentRiskLevel.NORMAL && hasRpInfo) {
                    val isDark = isSystemInDarkTheme()
                    val (rpContainerColor, rpBorderColor) = if (riskLevel == ConsentRiskLevel.HIGH_RISK) {
                        if (isDark) {
                            Color(0xFF4A1C1A) to Color(0xFFB3261E)
                        } else {
                            Color(0xFFFCE8E6) to Color(0xFFD93025)
                        }
                    } else {
                        if (isDark) {
                            Color(0xFF3E3012) to Color(0xFFF9AB00)
                        } else {
                            Color(0xFFFEF7E0) to Color(0xFFF9AB00)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(rpContainerColor)
                            .border(1.dp, rpBorderColor, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    ) {
                        RelyingPartyInfoContent(
                            paragraphs = rpParagraphs,
                            disclaimer = requesterDisplayData.disclaimer,
                            showWarningIcon = true,
                            onShowRequesterInfo = onShowRequesterInfo,
                        )
                    }
                }

                val normalSingleUseCaseRpInfoBox: (@Composable () -> Unit)? =
                    if (riskLevel == ConsentRiskLevel.NORMAL && hasRpInfo && presentmentData.useCases.size == 1) {
                        {
                            RelyingPartyInfoContent(
                                paragraphs = rpParagraphs,
                                disclaimer = requesterDisplayData.disclaimer,
                                showWarningIcon = false,
                                onShowRequesterInfo = onShowRequesterInfo,
                            )
                        }
                    } else {
                        null
                    }

                presentmentData.useCases.forEachIndexed { index, useCase ->
                    UseCaseViewer(
                        useCaseIndex = index,
                        useCase = useCase,
                        selectionIndex = selections[index],
                        requester = requester,
                        trustedRequesterIdentity = trustedRequesterIdentity,
                        requesterDisplayData = requesterDisplayData,
                        purposes = purposes,
                        transactionUserInput = transactionUserInput,
                        transactionDataRendererRepository = transactionDataRendererRepository,
                        appInfo = appInfo,
                        onTransactionUserInputChanged = onTransactionUserInputChanged,
                        onShowDocumentDetails = onShowDocumentDetails,
                        onShowTransactionDetails = onShowTransactionDetails,
                        onNavigateToPickSolution = {
                            onNavigateToPickSolution(index)
                        },
                        bottomContent = normalSingleUseCaseRpInfoBox,
                    )
                }
                if (riskLevel == ConsentRiskLevel.NORMAL && hasRpInfo && presentmentData.useCases.size > 1) {
                    SegmentedItemContainer(
                        index = 0,
                        count = 1,
                    ) {
                        RelyingPartyInfoContent(
                            paragraphs = rpParagraphs,
                            disclaimer = requesterDisplayData.disclaimer,
                            showWarningIcon = false,
                            onShowRequesterInfo = onShowRequesterInfo,
                        )
                    }
                }
            }
        }

        ButtonSection(
            onConfirm = {
                onConfirm(presentmentData.toPresentmentSelection(selections, transactionUserInput))
            },
            onCancel = onCancel,
            scrollState = scrollState,
            requireCoolOff = (riskLevel == ConsentRiskLevel.HIGH_RISK),
            coolOffComplete = coolOffComplete,
            onCoolOffComplete = onCoolOffComplete,
        )
    }
}

@Composable
private fun UseCaseViewer(
    useCaseIndex: Int,
    useCase: PresentmentUseCase,
    selectionIndex: Int,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    requesterDisplayData: RequesterDisplayData,
    purposes: Set<PresentmentPurpose>,
    transactionUserInput: Map<PresentmentCredentialMatch, Map<String, TransactionUserInput>>,
    transactionDataRendererRepository: TransactionDataRendererRepository?,
    appInfo: ApplicationInfo?,
    onTransactionUserInputChanged: (PresentmentCredentialMatch, String, TransactionUserInput) -> Unit,
    onShowDocumentDetails: (DocumentDetailsTarget) -> Unit,
    onShowTransactionDetails: (TransactionDetailsTarget) -> Unit,
    onNavigateToPickSolution: () -> Unit,
    bottomContent: (@Composable () -> Unit)? = null,
) {
    val isSelected = selectionIndex >= 0
    val solutionIndexToShow = if (isSelected) selectionIndex else 0
    val solution = useCase.solutions.getOrNull(solutionIndexToShow)

    if (solution != null) {
        val documentGroups = remember(solution) { groupMatchesByDocument(solution.matches) }
        val switchableDocIds = remember(useCase, documentGroups) {
            switchableDocumentIds(useCase, documentGroups)
        }
        val totalSegments = (if (!isSelected) {
            documentGroups.size
        } else {
            documentGroups.sumOf { group ->
                var segments = 1
                if (group.claimSections.isNotEmpty()) segments += 1
                segments += group.matches.count { it.transactionData.isNotEmpty() }
                segments
            }
        }) + if (bottomContent != null) 1 else 0
        var currentSegmentIndex = 0
        val alpha = if (isSelected) 1.0f else 0.5f
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
            ) {
                documentGroups.forEachIndexed { groupIndex, group ->
                    if (!isSelected) {
                        val segmentIdx = currentSegmentIndex++
                        SegmentedItemContainer(
                            index = segmentIdx,
                            count = totalSegments,
                        ) {
                            CredentialViewerNotSelected(
                                typeDisplayName = group.matches.first().resolveTypeDisplayName(),
                                showChevron = (groupIndex == 0),
                                onChevronClicked = onNavigateToPickSolution
                            )
                        }
                    } else {
                        val canSwitchOnCredential = group.document.identifier in switchableDocIds
                        val credSegmentIdx = currentSegmentIndex++
                        SegmentedItemContainer(
                            index = credSegmentIdx,
                            count = totalSegments,
                        ) {
                            CredentialViewer(
                                credential = group.credential,
                                showChevron = canSwitchOnCredential,
                                onChevronClicked = onNavigateToPickSolution
                            )
                        }

                        if (group.claimSections.isNotEmpty()) {
                            val claimsSegmentIdx = currentSegmentIndex++
                            val onViewDetails = {
                                onShowDocumentDetails(
                                    DocumentDetailsTarget(
                                        useCaseIndex = useCaseIndex,
                                        matchIndex = group.firstMatchIndex,
                                    )
                                )
                            }
                            SegmentedItemContainer(
                                index = claimsSegmentIdx,
                                count = totalSegments,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    group.claimSections.forEachIndexed { sectionIndex, section ->
                                        if (sectionIndex > 0) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                        val isLastSection = (sectionIndex == group.claimSections.lastIndex)
                                        val headerText = calcSharedWithText(
                                            requester = requester,
                                            requesterDisplayData = requesterDisplayData,
                                            appInfo = appInfo,
                                            storesData = section.storesData,
                                            encryptionRequested = section.encryptionRequested,
                                            encryptionTargetTrustMetadata = section.encryptionTargetTrustMetadata
                                        )
                                        SharedStoredText(text = headerText)
                                        ClaimsGridView(
                                            allClaims = section.displayClaims,
                                            useColumns = true,
                                            onViewDetailsClicked = if (isLastSection) onViewDetails else null,
                                        )
                                    }
                                }
                            }
                        }

                        val hasClaims = group.claimSections.isNotEmpty()
                        for (txMatch in group.matches.filter { it.transactionData.isNotEmpty() }) {
                            val txSegmentIdx = currentSegmentIndex++
                            SegmentedItemContainer(
                                index = txSegmentIdx,
                                count = totalSegments,
                            ) {
                                CredentialTransactionDataContent(
                                    credential = txMatch,
                                    hasClaims = hasClaims,
                                    requester = requester,
                                    trustedRequesterIdentity = trustedRequesterIdentity,
                                    purposes = purposes,
                                    transactionUserInput = transactionUserInput,
                                    transactionDataRendererRepository = transactionDataRendererRepository,
                                    onTransactionUserInputChanged = onTransactionUserInputChanged,
                                    onShowTransactionDetails = onShowTransactionDetails,
                                )
                            }
                        }
                    }
                }
                if (bottomContent != null) {
                    val bottomSegmentIdx = currentSegmentIndex++
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurface
                    ) {
                        SegmentedItemContainer(
                            index = bottomSegmentIdx,
                            count = totalSegments,
                        ) {
                            bottomContent()
                        }
                    }
                }
            }
        }
    } else {
        SegmentedItemContainer(
            index = 0,
            count = 1,
        ) {
            Text(text = stringResource(Res.string.credential_presentment_no_credentials_available))
        }
    }
}

@Composable
private fun CredentialTransactionDataContent(
    credential: PresentmentCredentialMatch,
    hasClaims: Boolean,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    purposes: Set<PresentmentPurpose>,
    transactionUserInput: Map<PresentmentCredentialMatch, Map<String, TransactionUserInput>>,
    transactionDataRendererRepository: TransactionDataRendererRepository?,
    onTransactionUserInputChanged: (PresentmentCredentialMatch, String, TransactionUserInput) -> Unit,
    onShowTransactionDetails: (TransactionDetailsTarget) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        val blocks = transactionDataBlocks(
            transactionData = credential.transactionData,
            transactionDataRendererRepository = transactionDataRendererRepository,
        )
        for (block in blocks) {
            when (block) {
                is TransactionDataBlock.BuiltIn -> DisplayTransactionData(
                    transactionData = block.item,
                    hasClaims = hasClaims,
                    userInput = transactionUserInput[credential]
                        ?.get(block.item.type.identifier),
                    onUserInputChanged = { userInput ->
                        onTransactionUserInputChanged.invoke(
                            credential,
                            block.item.type.identifier,
                            userInput
                        )
                    }
                )
                is TransactionDataBlock.Wallet -> {
                    val target = TransactionDetailsTarget(
                        match = credential,
                        block = block,
                        hasClaims = hasClaims,
                    )
                    block.renderer.summary(
                        TransactionDataContext(
                            transactionData = block.items,
                            requester = requester,
                            trustedRequesterIdentity = trustedRequesterIdentity,
                            hasClaims = hasClaims,
                            purposes = purposes,
                            userInput = transactionUserInput[credential]
                                ?.get(block.identifier),
                            onUserInputChanged = { userInput ->
                                onTransactionUserInputChanged.invoke(
                                    credential,
                                    block.identifier,
                                    userInput
                                )
                            },
                        )
                    )
                    if (block.renderer.details != null) {
                        ViewDetailsChipCell(
                            modifier = Modifier.padding(top = 8.dp),
                            onClick = { onShowTransactionDetails(target) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DisplayTransactionData(
    transactionData: TransactionData<*>,
    hasClaims: Boolean,
    userInput: TransactionUserInput?,
    onUserInputChanged: (userInput: TransactionUserInput) -> Unit
) {
    when (val type = transactionData.type) {
        PingTransaction -> {
            val payload = transactionData.payload as PingTransaction.Payload
            val headerText = if (hasClaims) {
                stringResource(Res.string.credential_presentment_ping_tx_also_approved)
            } else {
                stringResource(Res.string.credential_presentment_ping_tx_approved)
            }
            SharedStoredText(text = headerText)
            payload.string?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.credential_presentment_ping_tx_string, it),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            payload.blob?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            Res.string.credential_presentment_ping_tx_blob,
                            it.toByteArray().toBase64Url()
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        PaymentTransaction -> {
            val payload = transactionData.payload as PaymentTransaction.Payload
            val tipPercent = (userInput as? PaymentTransaction.UserInput)?.tipPercent ?: 0.0

            val headerText = if (hasClaims) {
                stringResource(Res.string.credential_presentment_payment_tx_also_approved)
            } else {
                stringResource(Res.string.credential_presentment_payment_tx_approved)
            }
            SharedStoredText(text = headerText)

            if (payload.payee.name.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Storefront,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.credential_presentment_payment_tx_payee, payload.payee.name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            val amountText = if (payload.tipRequested == true && tipPercent > 0.0) {
                val tipAmount = ceil(payload.amount * tipPercent) / 100.0
                val totalAmount = payload.amount + tipAmount
                stringResource(
                    Res.string.credential_presentment_payment_tx_amount_with_tip,
                    formatAmount(totalAmount),
                    payload.currency,
                    formatAmount(tipAmount),
                    payload.currency
                )
            } else {
                stringResource(
                    Res.string.credential_presentment_payment_tx_amount,
                    formatAmount(payload.amount),
                    payload.currency
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth().padding(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Payment,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (payload.tipRequested == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    text = stringResource(Res.string.credential_presentment_payment_tx_add_tip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tipOptions.forEach { (percent, label) ->
                        val isSelected = (tipPercent == percent)
                        val chipLabel = label ?: stringResource(Res.string.credential_presentment_payment_tx_no_tip)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                onUserInputChanged(PaymentTransaction.UserInput(percent))
                            },
                            label = {
                                Text(
                                    text = chipLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        )
                    }
                }
            }
        }

        else -> {
            val headerText = if (hasClaims) {
                stringResource(Res.string.credential_presentment_generic_tx_also_approved, type.displayName)
            } else {
                stringResource(Res.string.credential_presentment_generic_tx_approved, type.displayName)
            }
            SharedStoredText(text = headerText)
        }
    }
}

private val tipOptions: List<Pair<Double, String?>> = listOf(
    0.0 to null,
    10.0 to "10%",
    15.0 to "15%",
    20.0 to "20%",
    25.0 to "25%"
)

private fun formatAmount(amount: Double): String {
    val roundedCents = round(amount * 100.0).toLong()
    val dollars = roundedCents / 100
    val cents = abs(roundedCents % 100)
    return "$dollars.${cents.toString().padStart(2, '0')}"
}

@Composable
private fun calcSharedWithText(
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    appInfo: ApplicationInfo?,
    storesData: Boolean,
    encryptionRequested: Boolean,
    encryptionTargetTrustMetadata: TrustMetadata?
): String {
    if (!encryptionRequested) {
        return calcSharedWithTextWithoutEncryption(
            requester = requester,
            requesterDisplayData = requesterDisplayData,
            appInfo = appInfo,
            storesData = storesData
        )
    }

    val knownRequesterUnknownEncTarget = if (storesData) {
        Res.string.credential_presentment_share_and_stored_by_known_requester_and_unknown_enc_target
    } else {
        Res.string.credential_presentment_share_with_known_requester_and_unknown_enc_target
    }
    val unknownRequesterUnknownEncTarget = if (storesData) {
        Res.string.credential_presentment_share_and_stored_by_unknown_requester_and_unknown_enc_target
    } else {
        Res.string.credential_presentment_share_with_unknown_requester_and_unknown_enc_target
    }
    val knownRequesterKnownEncTarget = if (storesData) {
        Res.string.credential_presentment_share_and_stored_by_known_requester_and_known_enc_target
    } else {
        Res.string.credential_presentment_share_with_known_requester_and_known_enc_target
    }
    val unknownRequesterKnownEncTarget = if (storesData) {
        Res.string.credential_presentment_share_and_stored_by_unknown_requester_and_known_enc_target
    } else {
        Res.string.credential_presentment_share_with_unknown_requester_and_known_enc_target
    }

    val encTargetName = encryptionTargetTrustMetadata?.displayName
    return if (encTargetName != null) {
        if (requesterDisplayData.name != null) {
            stringResource(
                knownRequesterKnownEncTarget,
                requesterDisplayData.name,
                encTargetName
            )
        } else if (requester.isWebOrigin) {
            stringResource(
                knownRequesterKnownEncTarget,
                requester.origin!!,
                encTargetName
            )
        } else if (appInfo != null) {
            stringResource(
                knownRequesterKnownEncTarget,
                appInfo.name,
                encTargetName
            )
        } else {
            stringResource(
                unknownRequesterKnownEncTarget,
                encTargetName
            )
        }
    } else {
        if (requesterDisplayData.name != null) {
            stringResource(
                knownRequesterUnknownEncTarget,
                requesterDisplayData.name
            )
        } else if (requester.isWebOrigin) {
            stringResource(
                knownRequesterUnknownEncTarget,
                requester.origin!!
            )
        } else if (appInfo != null) {
            stringResource(
                knownRequesterUnknownEncTarget,
                appInfo.name
            )
        } else {
            stringResource(unknownRequesterUnknownEncTarget)
        }
    }
}

@Composable
private fun calcSharedWithTextWithoutEncryption(
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    appInfo: ApplicationInfo?,
    storesData: Boolean
): String {
    val unknownRequesterStringResource = if (storesData) {
        Res.string.credential_presentment_share_and_stored_by_unknown_requester
    } else {
        Res.string.credential_presentment_share_with_unknown_requester
    }
    val knownRequesterStringResource = if (storesData) {
        Res.string.credential_presentment_share_and_stored_by_known_requester
    } else {
        Res.string.credential_presentment_share_with_known_requester
    }

    val ret = if (requesterDisplayData.name != null) {
        stringResource(
            knownRequesterStringResource,
            requesterDisplayData.name
        )
    } else if (requester.isWebOrigin) {
        stringResource(
            knownRequesterStringResource,
            requester.origin!!
        )
    } else if (appInfo != null) {
        stringResource(
            knownRequesterStringResource,
            appInfo.name
        )
    } else {
        stringResource(unknownRequesterStringResource)
    }

    return ret
}

@Composable
private fun PickSolutionPage(
    useCase: PresentmentUseCase,
    currentSolutionIndex: Int,
    onBackClicked: () -> Unit,
    onSolutionSelected: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.credential_presentment_back)
                )
            }
            Text(
                text = stringResource(Res.string.credential_presentment_select_option),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            useCase.solutions.forEachIndexed { index, solution ->
                val isSelectedSolution = (index == currentSolutionIndex)
                SegmentedItemContainer(
                    index = 0,
                    count = 1,
                    selected = isSelectedSolution,
                    modifier = Modifier.clickable { onSolutionSelected(index) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            groupMatchesByDocument(solution.matches).forEach { group ->
                                CredentialViewer(
                                    modifier = Modifier.fillMaxWidth(),
                                    credential = group.credential,
                                    showChevron = false,
                                    onChevronClicked = {}
                                )
                            }
                        }
                        RadioButton(
                            selected = isSelectedSolution,
                            onClick = null,
                        )
                    }
                }
            }

            if (useCase.optional) {
                val isNotSharingSelected = currentSolutionIndex < 0
                val fallbackTypeName = useCase.solutions.firstOrNull()
                    ?.let { groupMatchesByDocument(it.matches) }
                    ?.joinToString(", ") { it.matches.first().resolveTypeDisplayName() }
                    ?: ""
                SegmentedItemContainer(
                    index = 0,
                    count = 1,
                    selected = isNotSharingSelected,
                    modifier = Modifier.clickable { onSolutionSelected(-1) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(width = 60.dp, height = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Block,
                                contentDescription = null
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1.0f)
                        ) {
                            Text(
                                text = stringResource(Res.string.credential_presentment_dont_return_any_document),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (fallbackTypeName.isNotEmpty()) {
                                Text(
                                    text = fallbackTypeName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        RadioButton(
                            selected = isNotSharingSelected,
                            onClick = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialViewer(
    modifier: Modifier = Modifier,
    credential: Credential,
    showChevron: Boolean,
    onChevronClicked: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val branding = Branding.Current.collectAsState().value
    val boxShape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .then(
                if (showChevron) {
                    Modifier
                        .clip(boxShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, boxShape)
                        .clickable { onChevronClicked() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1.0f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    12.dp,
                    alignment = Alignment.Start
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var cardArtBitmap by remember(credential.document) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(credential.document) {
                    coroutineScope.launch {
                        cardArtBitmap = if (credential.document.cardArt != null) {
                            decodeImage(credential.document.cardArt!!.toByteArray())
                        } else {
                            branding.renderFallbackCardArt(credential.document)
                        }
                    }
                }
                cardArtBitmap?.let {
                    Box(
                        modifier = Modifier.size(width = 60.dp, height = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1.0f)
                ) {
                    Text(
                        text = credential.document.displayName
                            ?: "No Document Title",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    credential.document.typeDisplayName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showChevron) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialViewerNotSelected(
    modifier: Modifier = Modifier,
    typeDisplayName: String,
    showChevron: Boolean,
    onChevronClicked: () -> Unit
) {
    val boxShape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .then(
                if (showChevron) {
                    Modifier
                        .clip(boxShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, boxShape)
                        .clickable { onChevronClicked() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1.0f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    12.dp,
                    alignment = Alignment.Start
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(width = 60.dp, height = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null
                    )
                }
                Column(
                    modifier = Modifier.weight(1.0f)
                ) {
                    Text(
                        text = stringResource(Res.string.credential_presentment_no_document_returned),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = typeDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showChevron) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedStoredText(text: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private fun isRequesterIdentifiable(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
): Boolean =
    trustedRequesterIdentity != null ||
        (requester.isWebOrigin && !requester.origin.isNullOrEmpty()) ||
        requester.appId != null ||
        requester.requesterIdentities.isNotEmpty()

@Composable
private fun calcRelyingPartySentences(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    trustMetadata: TrustMetadata?,
    appInfo: ApplicationInfo?,
    showUntrustedWarning: Boolean = false,
    showAnonymousWarning: Boolean = false,
    requesterHistoryLoaded: Boolean = false,
    requesterHistoryEntry: RequesterHistoryEntry? = null,
    hasEncryptionTargetPrivacyPolicy: Boolean = false,
): List<String> {
    val relyingPartyName = trustMetadata?.displayName
        ?: trustedRequesterIdentity?.identity?.certChain?.certificates?.last()?.subject?.name
        ?: requester.origin?.takeIf { it.isNotEmpty() }
        ?: appInfo?.name
    val untrustedSentence = if (showAnonymousWarning) {
        if (requester.isWebOrigin) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_website)
        } else if (requester.appId != null) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_app)
        } else {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_anonymous)
        }
    } else if (showUntrustedWarning) {
        if (requester.isWebOrigin) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_website)
        } else if (requester.appId != null) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_app)
        } else {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list)
        }
    } else {
        null
    }
    val historySentence = if (requesterHistoryLoaded) {
        if (relyingPartyName != null) {
            if (requesterHistoryEntry != null) {
                stringResource(Res.string.credential_presentment_requester_shared_before, relyingPartyName)
            } else {
                stringResource(Res.string.credential_presentment_requester_first_time, relyingPartyName)
            }
        } else if (isRequesterIdentifiable(requester, trustedRequesterIdentity)) {
            if (requesterHistoryEntry != null) {
                stringResource(Res.string.credential_presentment_requester_shared_before_unnamed)
            } else {
                stringResource(Res.string.credential_presentment_requester_first_time_unnamed)
            }
        } else {
            null
        }
    } else {
        null
    }
    val moreInfoSentence = if (trustMetadata?.privacyPolicyUrl != null || hasEncryptionTargetPrivacyPolicy) {
        stringResource(Res.string.credential_presentment_more_information_and_privacy_policy)
    } else {
        stringResource(Res.string.credential_presentment_more_information)
    }
    return buildList {
        if (untrustedSentence != null) {
            add(untrustedSentence)
        }
        if (historySentence != null) {
            add(historySentence)
        }
        add(moreInfoSentence)
    }
}

@Composable
private fun RelyingPartyInfoContent(
    paragraphs: List<String>,
    disclaimer: String?,
    showWarningIcon: Boolean,
    onShowRequesterInfo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (paragraphs.isNotEmpty()) {
            RelyingPartyTrailer(
                paragraphs = paragraphs,
                showWarningIcon = showWarningIcon,
                onShowRequesterInfo = onShowRequesterInfo,
            )
        }

        if (disclaimer != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    modifier = Modifier.padding(end = 12.dp),
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                )
                Text(
                    text = disclaimer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RelyingPartyTrailer(
    paragraphs: List<String>,
    showWarningIcon: Boolean = false,
    onShowRequesterInfo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            modifier = Modifier.padding(end = 12.dp),
            imageVector = if (showWarningIcon) {
                Icons.Outlined.Warning
            } else {
                Icons.Outlined.Info
            },
            contentDescription = null,
        )
        Column(
            modifier = Modifier.align(Alignment.CenterVertically),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (paragraph in paragraphs) {
                Text(
                    text = AnnotatedString.fromMarkdown(
                        markdownString = paragraph,
                        linkInteractionListener = { onShowRequesterInfo() },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonSection(
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit,
    scrollState: ScrollState,
    requireCoolOff: Boolean = false,
    coolOffComplete: Boolean = false,
    onCoolOffComplete: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val showMoreButton = scrollState.canScrollForward
    val isLayoutReady = scrollState.viewportSize > 0 && scrollState.maxValue != Int.MAX_VALUE

    val coolOffProgress = remember { Animatable(0f) }

    LaunchedEffect(requireCoolOff, showMoreButton, isLayoutReady, coolOffComplete) {
        if (requireCoolOff && !coolOffComplete) {
            if (!isLayoutReady || showMoreButton) {
                coolOffProgress.snapTo(0f)
            } else {
                coolOffProgress.snapTo(0f)
                coolOffProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
                )
                onCoolOffComplete()
            }
        }
    }

    val isWaiting = requireCoolOff && !showMoreButton && !coolOffComplete
    val disabledTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val disabledFillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            modifier = Modifier.weight(1.0f),
            onClick = { coroutineScope.launch { onCancel() } }
        ) {
            Text(
                modifier = Modifier.padding(vertical = 8.dp),
                text = stringResource(Res.string.credential_presentment_button_cancel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Button(
            modifier = Modifier
                .weight(1.0f)
                .clip(ButtonDefaults.shape)
                .drawBehind {
                    if (isWaiting) {
                        drawRect(color = disabledTrackColor)
                        val fillWidth = size.width * coolOffProgress.value
                        val xOffset = if (layoutDirection == LayoutDirection.Rtl) {
                            size.width - fillWidth
                        } else {
                            0f
                        }
                        drawRect(
                            color = disabledFillColor,
                            topLeft = Offset(x = xOffset, y = 0f),
                            size = size.copy(width = fillWidth)
                        )
                    }
                },
            enabled = !isWaiting,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = Color.Transparent,
                disabledContentColor = disabledContentColor,
            ),
            onClick = {
                if (!showMoreButton) {
                    onConfirm()
                } else {
                    coroutineScope.launch {
                        val step = (scrollState.viewportSize * 0.9).toInt()
                        scrollState.animateScrollTo(
                            min(
                                scrollState.value + step,
                                scrollState.maxValue
                            )
                        )
                    }
                }
            }
        ) {
            Text(
                modifier = Modifier.padding(vertical = 8.dp),
                text = when {
                    showMoreButton -> stringResource(Res.string.credential_presentment_button_more)
                    isWaiting -> stringResource(Res.string.credential_presentment_button_wait)
                    else -> stringResource(Res.string.credential_presentment_button_continue)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun List<DisplayClaim>.salientClaims(): List<DisplayClaim> {
    val filtered = filter {
        it.sensitivity != DocumentAttributeSensitivity.ISSUER &&
            it.sensitivity != DocumentAttributeSensitivity.VALIDITY
    }
    return filtered.ifEmpty { this }
}

@Composable
private fun ClaimsGridView(
    allClaims: List<DisplayClaim>,
    useColumns: Boolean,
    onViewDetailsClicked: (() -> Unit)? = null,
) {
    val salient = allClaims.salientClaims()
    // By rule we only show salient claims in the grid and collapse non-salient (ISSUER / VALIDITY)
    // metadata into an "And N more" cell to reduce visual clutter. However, when only 1 non-salient
    // claim would be hidden, "And 1 more" occupies the exact same single grid cell as the claim
    // itself while hiding information, so we intentionally break the rule and show the claim.
    val (visibleClaims, hiddenCount) = if (allClaims.size - salient.size == 1) {
        allClaims to 0
    } else {
        salient to (allClaims.size - salient.size)
    }
    val cells = buildList<@Composable (Modifier) -> Unit> {
        for (claim in visibleClaims) {
            add { modifier -> ClaimsView(claim = claim, modifier = modifier) }
        }
        if (hiddenCount > 0) {
            add { modifier ->
                ClaimsMoreView(
                    modifier = modifier,
                    hiddenCount = hiddenCount,
                )
            }
        }
        if (onViewDetailsClicked != null) {
            add { modifier ->
                ViewDetailsChipCell(
                    modifier = modifier.padding(4.dp),
                    onClick = onViewDetailsClicked,
                )
            }
        }
    }
    if (!useColumns) {
        for (cell in cells) {
            Row(modifier = Modifier.fillMaxWidth()) {
                cell(Modifier.weight(1.0f))
            }
        }
    } else {
        var n = 0
        while (n <= cells.size - 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                cells[n](Modifier.weight(1.0f))
                cells[n + 1](Modifier.weight(1.0f))
            }
            n += 2
        }
        if (n < cells.size) {
            Row(modifier = Modifier.fillMaxWidth()) {
                cells[n](Modifier.weight(1.0f))
                Spacer(modifier = Modifier.weight(1.0f))
            }
        }
    }
}

@Composable
private fun ViewDetailsChipCell(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val chipShape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(chipShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, chipShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(Res.string.credential_presentment_view_details),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClaimsMoreView(
    modifier: Modifier,
    hiddenCount: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = modifier.padding(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.MoreHoriz,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.credential_presentment_and_n_more, hiddenCount),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Individual view for a DataElement.
 */
@Composable
private fun ClaimsView(
    modifier: Modifier,
    claim: DisplayClaim,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = modifier.padding(4.dp),
    ) {
        val icon = claim.icon ?: Icon.PERSON
        Icon(
            imageVector = icon.getOutlinedImageVector(),
            contentDescription = stringResource(Res.string.credential_presentment_data_element_icon_description)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = claim.displayName,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RelyingPartySection(
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    imageLoader: ImageLoader?,
    purposes: Set<PresentmentPurpose>,
) {
    // TODO: maybe also show name / icon for encrypted receivers...

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val requesterName = requesterDisplayData.name
            // If we have a trust point without `displayName` use the name in the root certificate.
            ?: trustedRequesterIdentity?.identity?.certChain?.certificates?.last()?.subject?.name
            ?: if (requester.requesterIdentities.isEmpty()) {
                stringResource(Res.string.credential_presentment_requester_info_anonymous_title)
            } else {
                stringResource(Res.string.credential_presentment_headline_share_with_unknown_requester)
            }

        if (requesterDisplayData.icon != null) {
            Icon(
                modifier = Modifier.size(80.dp),
                bitmap = requesterDisplayData.icon,
                contentDescription = stringResource(Res.string.credential_presentment_verifier_icon_description),
                tint = Color.Unspecified,
            )
        } else if (requesterDisplayData.iconUrl != null && imageLoader != null) {
            AsyncImage(
                modifier = Modifier.size(80.dp),
                model = requesterDisplayData.iconUrl,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                contentDescription = stringResource(Res.string.credential_presentment_verifier_icon_description),
            )
        } else {
            Icon(
                modifier = Modifier.size(80.dp),
                imageVector = if (requester.isWebOrigin) {
                    Icons.Outlined.Language
                } else {
                    Icons.Outlined.Business
                },
                contentDescription = stringResource(Res.string.credential_presentment_verifier_icon_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = requesterName,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = calcPurposeSubtitle(purposes),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun calcPurposeSubtitle(purposes: Set<PresentmentPurpose>): String {
    val ordered = PresentmentPurpose.entries.filter { it in purposes }
    return when (ordered.size) {
        0 -> stringResource(Res.string.credential_presentment_purpose_unknown)
        1 -> when (ordered[0]) {
            PresentmentPurpose.PAYMENT ->
                stringResource(Res.string.credential_presentment_purpose_payment)
            PresentmentPurpose.AGE_VERIFICATION ->
                stringResource(Res.string.credential_presentment_purpose_age_verification)
            PresentmentPurpose.DRIVING_PRIVILEGES ->
                stringResource(Res.string.credential_presentment_purpose_driving_privileges)
            PresentmentPurpose.ADDRESS_VERIFICATION ->
                stringResource(Res.string.credential_presentment_purpose_address_verification)
            PresentmentPurpose.IDENTITY_VERIFICATION ->
                stringResource(Res.string.credential_presentment_purpose_identity_verification)
            PresentmentPurpose.LOYALTY_VERIFICATION ->
                stringResource(Res.string.credential_presentment_purpose_loyalty_verification)
            PresentmentPurpose.TICKET_VERIFICATION ->
                stringResource(Res.string.credential_presentment_purpose_ticket_verification)
        }
        else -> {
            var usedPossessive = false
            val items = ordered.map { purpose ->
                val includePossessive = !usedPossessive
                if (purpose != PresentmentPurpose.PAYMENT) {
                    usedPossessive = true
                }
                purposeItemText(purpose, includePossessive = includePossessive)
            }
            if (items.size == 2) {
                stringResource(
                    Res.string.credential_presentment_purpose_two_items,
                    items[0],
                    items[1],
                )
            } else {
                val separator = stringResource(Res.string.credential_presentment_purpose_list_separator)
                stringResource(
                    Res.string.credential_presentment_purpose_multiple_items,
                    items.dropLast(1).joinToString(separator),
                    items.last(),
                )
            }
        }
    }
}

@Composable
private fun purposeItemText(
    purpose: PresentmentPurpose,
    includePossessive: Boolean,
): String = when (purpose) {
    PresentmentPurpose.PAYMENT ->
        stringResource(Res.string.credential_presentment_purpose_item_payment)
    PresentmentPurpose.AGE_VERIFICATION -> stringResource(
        if (includePossessive) {
            Res.string.credential_presentment_purpose_item_age_verification
        } else {
            Res.string.credential_presentment_purpose_item_bare_age_verification
        }
    )
    PresentmentPurpose.DRIVING_PRIVILEGES -> stringResource(
        if (includePossessive) {
            Res.string.credential_presentment_purpose_item_driving_privileges
        } else {
            Res.string.credential_presentment_purpose_item_bare_driving_privileges
        }
    )
    PresentmentPurpose.ADDRESS_VERIFICATION -> stringResource(
        if (includePossessive) {
            Res.string.credential_presentment_purpose_item_address_verification
        } else {
            Res.string.credential_presentment_purpose_item_bare_address_verification
        }
    )
    PresentmentPurpose.IDENTITY_VERIFICATION -> stringResource(
        if (includePossessive) {
            Res.string.credential_presentment_purpose_item_identity_verification
        } else {
            Res.string.credential_presentment_purpose_item_bare_identity_verification
        }
    )
    PresentmentPurpose.LOYALTY_VERIFICATION -> stringResource(
        if (includePossessive) {
            Res.string.credential_presentment_purpose_item_loyalty_verification
        } else {
            Res.string.credential_presentment_purpose_item_bare_loyalty_verification
        }
    )
    PresentmentPurpose.TICKET_VERIFICATION -> stringResource(
        if (includePossessive) {
            Res.string.credential_presentment_purpose_item_ticket_verification
        } else {
            Res.string.credential_presentment_purpose_item_bare_ticket_verification
        }
    )
}
