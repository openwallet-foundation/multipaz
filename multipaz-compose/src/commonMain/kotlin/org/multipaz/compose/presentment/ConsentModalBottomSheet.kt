package org.multipaz.compose.presentment

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.multipaz.consent.ClassifyPurposesFn
import org.multipaz.consent.ClassifyRiskFn
import org.multipaz.consent.ClassifySimpleConsentFn
import org.multipaz.consent.SimpleConsent
import org.multipaz.consent.defaultClassifyPurposes
import org.multipaz.consent.defaultClassifyRisk
import org.multipaz.consent.defaultClassifySimpleConsent
import org.multipaz.document.Document
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.RequesterHistory
import org.multipaz.prompt.PlatformConsentOptions
import org.multipaz.prompt.TransactionDataRendererNotAvailableException
import org.multipaz.prompt.TransactionDataRendererRepository
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

/**
 * Bottom sheet used for obtaining consent when presenting one or more credentials.
 *
 * @param sheetState a [SheetState] for state.
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity conveys the level of trust in the requester, if any.
 * @param presentmentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param imageLoader a [ImageLoader].
 * @param maxHeight the maximum height of the bottom sheet or `null` if no limit.
 * @param transactionDataRendererRepository the [TransactionDataRendererRepository] to find renderers for
 *   transaction data in, or `null` to use only the built-in rendering, which is refused for a type that
 *   [org.multipaz.documenttype.TransactionType.requiresRenderer].
 * @param requesterHistory an optional [RequesterHistory] used to query whether the user has shared
 *   credentials with [requester] before.
 * @param classifyPurposesFn a function used to classify the [org.multipaz.consent.PresentmentPurpose]s
 *   for the active selection.
 * @param classifyRiskFn a function used to evaluate the [org.multipaz.consent.ConsentRiskAssessment]
 *   for the active selection.
 * @param classifySimpleConsentFn a function used to evaluate whether the request qualifies for a
 *   combined simple consent and biometric authentication prompt. It is not asked, and that prompt is
 *   not used, when [presentmentData] holds transaction data of a type that
 *   [org.multipaz.documenttype.TransactionType.requiresRenderer], as that prompt draws no transaction data.
 * @param platformConsentOptions optional [PlatformConsentOptions] with platform-specific consent options.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @param onConfirm called when the user presses the "Continue" button, returns the user's selection.
 * @param onCancel called when the sheet is dismissed.
 * @param onRefused called when [presentmentData] holds transaction data of a type that
 *   [org.multipaz.documenttype.TransactionType.requiresRenderer] and [transactionDataRendererRepository]
 *   has no [TransactionDataRendererCompose] for it: such data is never approved with the built-in rendering,
 *   so the sheet is hidden without drawing the consent. The exception names the transaction type. Defaults to [onCancel], which is what the verifier then sees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentModalBottomSheet(
    sheetState: SheetState,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    maxHeight: Dp? = null,
    transactionDataRendererRepository: TransactionDataRendererRepository? = null,
    requesterHistory: RequesterHistory? = null,
    classifyPurposesFn: ClassifyPurposesFn = ::defaultClassifyPurposes,
    classifyRiskFn: ClassifyRiskFn = ::defaultClassifyRisk,
    classifySimpleConsentFn: ClassifySimpleConsentFn = ::defaultClassifySimpleConsent,
    platformConsentOptions: PlatformConsentOptions? = null,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onConfirm: (selection: PresentmentSelection) -> Unit,
    onCancel: () -> Unit = {},
    onRefused: (TransactionDataRendererNotAvailableException) -> Unit = { onCancel() },
) {
    val initialSimpleConsent = remember(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        preselectedDocuments,
        classifySimpleConsentFn,
        platformConsentOptions,
    ) {
        simpleConsentForBiometricPrompt(
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            presentmentData = presentmentData,
            preselectedDocuments = preselectedDocuments,
            classifySimpleConsentFn = classifySimpleConsentFn,
            showOnBiometricPrompt = shouldShowOnBiometricPrompt(platformConsentOptions),
        )
    }
    var showFullConsent by remember(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        preselectedDocuments,
        platformConsentOptions,
    ) {
        mutableStateOf(false)
    }

    if (initialSimpleConsent != null && !showFullConsent) {
        ConsentEmbeddedBiometricPrompt(
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            presentmentData = presentmentData,
            preselectedDocuments = preselectedDocuments,
            imageLoader = imageLoader,
            classifySimpleConsentFn = classifySimpleConsentFn,
            onDocumentsInFocus = onDocumentsInFocus,
            onShowDetails = {
                showFullConsent = true
            },
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
        return
    }

    val coroutineScope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { onCancel() },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = if (isSystemInDarkTheme()) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Consent(
            modifier = if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier,
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            presentmentData = presentmentData,
            preselectedDocuments = preselectedDocuments,
            imageLoader = imageLoader,
            transactionDataRendererRepository = transactionDataRendererRepository,
            requesterHistory = requesterHistory,
            classifyPurposesFn = classifyPurposesFn,
            classifyRiskFn = classifyRiskFn,
            onDocumentsInFocus = onDocumentsInFocus,
            onConfirm = onConfirm,
            onCancel = {
                coroutineScope.launch { sheetState.hide() }
                onCancel()
            },
            onRefused = {
                coroutineScope.launch { sheetState.hide() }
                onRefused(it)
            },
        )
    }
}

/**
 * The [SimpleConsent] to show on the embedded biometric prompt, or `null` to show the full consent.
 *
 * The embedded prompt draws no transaction data, so it is never used when [presentmentData] holds
 * transaction data of a type that [org.multipaz.documenttype.TransactionType.requiresRenderer],
 * whatever [classifySimpleConsentFn] would say, and [classifySimpleConsentFn] is not even asked.
 *
 * @param showOnBiometricPrompt whether the platform shows consent on its biometric prompt at all.
 */
internal fun simpleConsentForBiometricPrompt(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document>,
    classifySimpleConsentFn: ClassifySimpleConsentFn,
    showOnBiometricPrompt: Boolean,
): SimpleConsent? {
    if (!showOnBiometricPrompt || presentmentData.allTransactionData.any { it.type.requiresRenderer }) {
        return null
    }
    return classifySimpleConsentFn(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        presentmentData.defaultSelections(preselectedDocuments),
    )
}

internal expect fun shouldShowOnBiometricPrompt(
    platformConsentOptions: PlatformConsentOptions?,
): Boolean

@Composable
internal expect fun ConsentEmbeddedBiometricPrompt(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    classifySimpleConsentFn: ClassifySimpleConsentFn,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onShowDetails: () -> Unit,
    onConfirm: (selection: PresentmentSelection) -> Unit,
    onCancel: () -> Unit,
)
