package org.multipaz.compose.presentment

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.NavigationEventInfo
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import org.multipaz.compose.decodeImage
import org.multipaz.compose.getApplicationInfo
import org.multipaz.compose.items.FloatingItemContainer
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.compose.getOutlinedImageVector
import org.multipaz.compose.text.fromMarkdown
import org.multipaz.credential.Credential
import org.multipaz.document.Document
import org.multipaz.documenttype.Icon
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_cancel
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_more
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_share
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_select_option
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_dont_return_any_document
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_data_element_icon_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_unknown_website
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list_app
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list_website
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_no_document_returned
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_privacy_policy
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_information
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_known_requester_and_known_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_known_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_unknown_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_known_requester_and_known_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_known_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_unknown_requester_and_unknown_enc_target
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_transaction_data
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_verifier_icon_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_anonymous
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_app
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_website
import org.multipaz.presentment.CredentialMatchSourceIso18013
import org.multipaz.presentment.CredentialMatchSourceOpenID4VP
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.ConsentData
import org.multipaz.presentment.ConsentUseCase
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import kotlin.math.min

private val PAGER_INDICATOR_HEIGHT = 30.dp
private val PAGER_INDICATOR_PADDING = 8.dp

private const val TAG = "Consent"

/**
 * A composable used for obtaining consent when presenting one or more credentials.
 *
 * @param modifier a [Modifier].
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity conveys the level of trust in the requester, if any.
 * @param consentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 * example an OS-provided credential picker like Android's Credential Manager) or the empty list
 * if the user didn't preselect.
 * @param imageLoader a [ImageLoader].
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 * first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @param onConfirm called when the user presses the "Share" button, returns the user's selection.
 * @param onCancel called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun Consent(
    modifier: Modifier = Modifier,
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    consentData: ConsentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onConfirm: (selection: CredentialSelection) -> Unit,
    onCancel: () -> Unit = {},
) {
    val currentBranding = Branding.Current.collectAsState().value
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

    val initialSelections = remember(consentData, preselectedDocuments) {
        consentData.useCases.map { useCase ->
            if (preselectedDocuments.isEmpty()) {
                if (useCase.solutions.isNotEmpty()) 0 else -1
            } else {
                val preselectedSet = preselectedDocuments.toSet()
                val matchingIndex = useCase.solutions.indexOfFirst { solution ->
                    solution.credentials.isNotEmpty() && solution.credentials.all {
                        it.match.credential.document in preselectedSet
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
    var activeUseCaseIndex by remember { mutableStateOf(0) }
    var isFlipped by remember { mutableStateOf(false) }

    val currentSelection = remember(selections, consentData) {
        consentData.toCredentialSelection(selections)
    }
    val currentDocumentsInFocus = remember(currentSelection) {
        currentSelection.matches.map { it.credential.document }
    }
    LaunchedEffect(currentDocumentsInFocus) {
        onDocumentsInFocus(currentDocumentsInFocus)
    }

    val navState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navState,
        isBackEnabled = isFlipped,
        onBackCompleted = {
            isFlipped = false
        }
    )

    Column(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
    ) {
        currentBranding.appName?.let { appName ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                currentBranding.appIconPainter?.let { appIconPainter ->
                    Image(
                        modifier = Modifier.size(20.dp),
                        painter = appIconPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        FlipCard(
            isFlipped = isFlipped,
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            front = {
                NavHost(
                    navController = navController,
                    startDestination = "main",
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable("main") {
                        ConsentPage(
                            requester = requester,
                            trustedRequesterIdentity = trustedRequesterIdentity,
                            appInfo = appInfo,
                            imageLoader = imageLoader,
                            consentData = consentData,
                            selections = selections,
                            onSelectionChanged = { index, value ->
                                val newList = selections.toMutableList()
                                newList[index] = value
                                selections = newList
                            },
                            onShowRequesterInfo = {
                                navController.navigate("showRequesterInfo")
                            },
                            onNavigateToPickSolution = { index ->
                                activeUseCaseIndex = index
                                isFlipped = true
                            },
                            onConfirm = onConfirm,
                            onCancel = onCancel
                        )
                    }

                    composable("showRequesterInfo") {
                        ShowRequesterInfoPage(
                            trustedRequesterIdentity = trustedRequesterIdentity,
                            onBackClicked = {
                                navController.navigateUp()
                            },
                        )
                    }
                }
            },
            back = {
                PickSolutionPage(
                    useCaseIndex = activeUseCaseIndex,
                    useCase = consentData.useCases[activeUseCaseIndex],
                    currentSolutionIndex = selections[activeUseCaseIndex],
                    onBackClicked = {
                        isFlipped = false
                    },
                    onSolutionSelected = { solutionIndex ->
                        val newList = selections.toMutableList()
                        newList[activeUseCaseIndex] = solutionIndex
                        selections = newList
                        isFlipped = false
                    }
                )
            }
        )
    }
}

@Composable
private fun ShowRequesterInfoPage(
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    onBackClicked: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = stringResource(Res.string.credential_presentment_requester_information),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        trustedRequesterIdentity?.identity?.certChain?.let { certChain ->
            Box(
                modifier = Modifier.fillMaxHeight()
            ) {
                val pagerState = rememberPagerState(pageCount = { certChain.certificates.size })
                Column(
                    modifier = Modifier.then(
                        if (certChain.certificates.size > 1)
                            Modifier.padding(bottom = PAGER_INDICATOR_HEIGHT + PAGER_INDICATOR_PADDING)
                        else // No pager, no padding.
                            Modifier
                    )
                ) {
                    HorizontalPager(
                        state = pagerState,
                    ) { page ->
                        val scrollState = rememberScrollState()
                        X509CertViewer(
                            modifier = Modifier
                                .padding(top = 10.dp, bottom = 20.dp, start = 10.dp, end = 10.dp)
                                .verticalScroll(scrollState),
                            certificate = certChain.certificates[page]
                        )
                    }
                }

                if (certChain.certificates.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .wrapContentHeight()
                            .fillMaxWidth()
                            .height(PAGER_INDICATOR_HEIGHT)
                            .padding(PAGER_INDICATOR_PADDING),
                    ) {
                        repeat(pagerState.pageCount) { iteration ->
                            val color =
                                if (pagerState.currentPage == iteration) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = .2f)
                                }
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .size(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class RequesterDisplayData(
    val name: String? = null,
    val icon: ImageBitmap? = null,
    val iconUrl: String? = null,
    val disclaimer: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentPage(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    appInfo: ApplicationInfo?,
    imageLoader: ImageLoader?,
    consentData: ConsentData,
    selections: List<Int>,
    onSelectionChanged: (Int, Int) -> Unit,
    onShowRequesterInfo: () -> Unit,
    onNavigateToPickSolution: (Int) -> Unit,
    onConfirm: (selection: CredentialSelection) -> Unit,
    onCancel: () -> Unit
) {
    val scrollState = rememberScrollState()

    val trustMetadata = trustedRequesterIdentity?.trustMetadata
    val requesterDisplayData = if (trustMetadata != null) {
        RequesterDisplayData(
            name = trustMetadata.displayName,
            icon = trustMetadata.displayIcon?.let { remember { it.toByteArray().decodeToImageBitmap() } },
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

    Column {
        RelyingPartySection(
            trustedRequesterIdentity = trustedRequesterIdentity,
            requesterDisplayData = requesterDisplayData,
            imageLoader = imageLoader,
            consentData = consentData,
            selections = selections,
            onShowRequesterInfo = onShowRequesterInfo
        )

        Column(
            modifier = Modifier.padding(top = 12.dp).weight(1f, fill = false)
        ) {
            Column(
                modifier = Modifier
                    .focusGroup()
                    .verticalScroll(scrollState)
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                consentData.useCases.forEachIndexed { index, useCase ->
                    FloatingItemList {
                        UseCaseViewer(
                            useCaseIndex = index,
                            useCase = useCase,
                            selectionIndex = selections[index],
                            requester = requester,
                            requesterDisplayData = requesterDisplayData,
                            trustMetadata = trustMetadata,
                            appInfo = appInfo,
                            onSelectionChanged = { value ->
                                onSelectionChanged(index, value)
                            },
                            onNavigateToPickSolution = {
                                onNavigateToPickSolution(index)
                            }
                        )
                    }
                }

                FloatingItemList {
                    FloatingItemContainer {
                        RelyingPartyTrailer(
                            requester = requester,
                            trustMetadata = trustMetadata,
                        )
                    }

                    if (requesterDisplayData.disclaimer != null) {
                        FloatingItemContainer {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    modifier = Modifier.padding(end = 12.dp),
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                )
                                Text(
                                    text = requesterDisplayData.disclaimer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        ButtonSection(
            onConfirm = {
                onConfirm(consentData.toCredentialSelection(selections))
            },
            onCancel = onCancel,
            scrollState = scrollState
        )
    }
}

@Composable
private fun UseCaseViewer(
    useCaseIndex: Int,
    useCase: ConsentUseCase,
    selectionIndex: Int,
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    trustMetadata: TrustMetadata?,
    appInfo: ApplicationInfo?,
    onSelectionChanged: (Int) -> Unit,
    onNavigateToPickSolution: () -> Unit
) {
    val isSelected = selectionIndex >= 0
    val solutionIndexToShow = if (isSelected) selectionIndex else 0
    val solution = useCase.solutions.getOrNull(solutionIndexToShow)

    val showChevron = useCase.optional || useCase.solutions.size > 1

    if (solution != null) {
        val alpha = if (isSelected) 1.0f else 0.5f
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        ) {
            solution.credentials.forEachIndexed { matchIndex, credential ->
                FloatingItemContainer {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!isSelected) {
                            CredentialViewerNotSelected(
                                typeDisplayName = credential.match.credential.document.typeDisplayName
                                    ?: when (credential.match.source) {
                                        is CredentialMatchSourceIso18013 ->
                                            (credential.match.source as CredentialMatchSourceIso18013).docRequest.docType
                                        is CredentialMatchSourceOpenID4VP ->
                                            (credential.match.source as CredentialMatchSourceOpenID4VP).credentialQuery.vctValues!!.first()
                                    },
                                showChevron = true,
                                onChevronClicked = { onNavigateToPickSolution() }
                            )
                        } else {
                            CredentialViewer(
                                credential = credential.match.credential,
                                showChevron = showChevron && (matchIndex == 0),
                                onChevronClicked = { onNavigateToPickSolution() }
                            )
                            val notStoredClaims = credential.match.claims.mapNotNull { (requestedClaim, claim) ->
                                if (requestedClaim is MdocRequestedClaim && requestedClaim.intentToRetain) {
                                    null
                                } else {
                                    claim
                                }
                            }
                            val storedClaims = credential.match.claims.mapNotNull { (requestedClaim, claim) ->
                                if (requestedClaim is MdocRequestedClaim && requestedClaim.intentToRetain) {
                                    claim
                                } else {
                                    null
                                }
                            }

                            val sharedWithText = calcSharedWithText(
                                requester = requester,
                                requesterDisplayData = requesterDisplayData,
                                appInfo = appInfo,
                                storesData = false,
                                encryptionRequested = credential.encryptionRequested,
                                encryptionTargetTrustMetadata = credential.encryptionTargetTrustMetadata
                            )
                            val sharedWithAndStoredByText = calcSharedWithText(
                                requester = requester,
                                requesterDisplayData = requesterDisplayData,
                                appInfo = appInfo,
                                storesData = true,
                                encryptionRequested = credential.encryptionRequested,
                                encryptionTargetTrustMetadata = credential.encryptionTargetTrustMetadata
                            )

                            if (credential.match.transactionData.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.error)
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(
                                        modifier = Modifier.fillMaxWidth(),
                                        text = stringResource(Res.string.credential_presentment_transaction_data),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onError,
                                        fontWeight = FontWeight.Bold
                                    )
                                    for (data in credential.match.transactionData) {
                                        Text(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp, 0.dp, 0.dp, 0.dp),
                                            text = "\u2022 ${data.type.displayName}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onError,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (storedClaims.isEmpty()) {
                                SharedStoredText(text = sharedWithText)
                                ClaimsGridView(claims = notStoredClaims, useColumns = true)
                            } else if (notStoredClaims.isEmpty()) {
                                SharedStoredText(text = sharedWithAndStoredByText)
                                ClaimsGridView(claims = storedClaims, useColumns = true)
                            } else {
                                SharedStoredText(text = sharedWithText)
                                ClaimsGridView(claims = notStoredClaims, useColumns = true)
                                SharedStoredText(text = sharedWithAndStoredByText)
                                ClaimsGridView(claims = storedClaims, useColumns = true)
                            }
                        }
                    }
                }
            }
        }
    } else {
        FloatingItemContainer {
            Text(text = "No credentials available")
        }
    }
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
        Res.string.credential_presentment_share_and_stored_by_unknown_requester_and_unknown_enc_target
    } else {
        Res.string.credential_presentment_share_with_unknown_requester_and_unknown_enc_target
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
    useCaseIndex: Int,
    useCase: ConsentUseCase,
    currentSolutionIndex: Int,
    onBackClicked: () -> Unit,
    onSolutionSelected: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                text = stringResource(Res.string.credential_presentment_select_option),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(48.dp)) // Balance the back button
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            useCase.solutions.forEachIndexed { index, solution ->
                FloatingItemList {
                    solution.credentials.forEachIndexed { matchIndex, credential ->
                        FloatingItemContainer(
                            modifier = Modifier.clickable { onSolutionSelected(index) }
                        ) {
                            CredentialViewer(
                                credential = credential.match.credential,
                                showChevron = false,
                                onChevronClicked = {}
                            )
                        }
                    }
                }
            }

            if (useCase.optional) {
                FloatingItemList {
                    FloatingItemContainer(
                        modifier = Modifier.clickable { onSolutionSelected(-1) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(Res.string.credential_presentment_dont_return_any_document),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
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

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1.0f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    alignment = Alignment.Start
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var cardArtBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(Unit) {
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
                        modifier = Modifier.size(60.dp),
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
                    modifier = Modifier.padding(start = 5.dp).weight(1.0f)
                ) {
                    Text(
                        text = credential.document.displayName
                            ?: "No Document Title",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    credential.document.typeDisplayName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                if (showChevron) {
                    Icon(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onChevronClicked() },
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1.0f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    alignment = Alignment.Start
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 5.dp).weight(1.0f)
                ) {
                    Text(
                        text = stringResource(Res.string.credential_presentment_no_document_returned),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = typeDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (showChevron) {
                    Icon(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onChevronClicked() },
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
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
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun RelyingPartyTrailer(
    requester: Requester,
    trustMetadata: TrustMetadata?,
) {
    if (trustMetadata != null) {
        var text = if (requester.isWebOrigin) {
            stringResource(Res.string.credential_presentment_info_verifier_in_trust_list_website)
        } else if (requester.appId != null) {
            stringResource(Res.string.credential_presentment_info_verifier_in_trust_list_app)
        } else {
            stringResource(Res.string.credential_presentment_info_verifier_in_trust_list)
        }

        if (trustMetadata.privacyPolicyUrl != null) {
            val privacyPolicyText = stringResource(
                Res.string.credential_presentment_privacy_policy,
                trustMetadata.displayName ?: "",
                trustMetadata.privacyPolicyUrl!!,
            )
            text = "$text. $privacyPolicyText"
        }
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                modifier = Modifier.padding(end = 12.dp),
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
            )
            Text(
                modifier = Modifier.align(Alignment.CenterVertically),
                text = AnnotatedString.fromMarkdown(markdownString = text),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        val text = if (requester.isWebOrigin) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_website)
        } else if (requester.appId != null) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_app)
        } else {
            if (requester.requesterIdentities.isNotEmpty()) {
                stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list)
            } else {
                stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_anonymous)
            }
        }
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.error
        ) {
            Row {
                Icon(
                    modifier = Modifier.padding(end = 12.dp),
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                )
                Text(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
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
    scrollState: ScrollState
) {
    val coroutineScope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedButton(
            modifier = Modifier.weight(1.0f),
            onClick = { coroutineScope.launch { onCancel() } }
        ) {
            Text(
                modifier = Modifier.padding(vertical = 8.dp),
                text = stringResource(Res.string.credential_presentment_button_cancel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Button(
            modifier = Modifier.weight(1.0f),
            onClick = {
                if (!scrollState.canScrollForward) {
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
                text = if (scrollState.canScrollForward) {
                    stringResource(Res.string.credential_presentment_button_more)
                } else {
                    stringResource(Res.string.credential_presentment_button_share)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ClaimsGridView(
    claims: List<Claim>,
    useColumns: Boolean
) {
    if (!useColumns) {
        for (claim in claims) {
            Row(modifier = Modifier.fillMaxWidth()) {
                ClaimsView(claim = claim, modifier = Modifier.weight(1.0f))
            }
        }
    } else {
        var n = 0
        while (n <= claims.size - 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ClaimsView(claim = claims[n], modifier = Modifier.weight(1.0f))
                ClaimsView(
                    claim = claims[n + 1],
                    modifier = Modifier.weight(1.0f)
                )
            }
            n += 2
        }
        if (n < claims.size) {
            Row(modifier = Modifier.fillMaxWidth()) {
                ClaimsView(claim = claims[n], modifier = Modifier.weight(1.0f))
            }
        }
    }
}

/**
 * Individual view for a DataElement.
 */
@Composable
private fun ClaimsView(
    modifier: Modifier,
    claim: Claim,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = modifier.padding(4.dp),
    ) {
        val icon = claim.attribute?.icon ?: Icon.PERSON
        Icon(
            imageVector = icon.getOutlinedImageVector(),
            contentDescription = stringResource(Res.string.credential_presentment_data_element_icon_description)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = claim.displayName,
            fontWeight = FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RelyingPartySection(
    requesterDisplayData: RequesterDisplayData,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    imageLoader: ImageLoader?,
    consentData: ConsentData,
    selections: List<Int>,
    onShowRequesterInfo: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    // TODO: maybe also show name / icon for encrypted receivers...

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val requesterName = requesterDisplayData.name
            // If we have a trust point without `displayName` use the name in the root certificate.
            ?: trustedRequesterIdentity?.identity?.certChain?.certificates?.last()?.subject?.name
            // We could distinguish between anonymous and unknown request but that's already
            // done in the warning text
            ?: stringResource(Res.string.credential_presentment_headline_share_with_unknown_requester)

        if (requesterDisplayData.icon != null) {
            Icon(
                modifier = Modifier.size(80.dp)
                    .clickable(enabled = trustedRequesterIdentity?.identity?.certChain != null) {
                        onShowRequesterInfo()
                    },
                bitmap = requesterDisplayData.icon,
                contentDescription = stringResource(Res.string.credential_presentment_verifier_icon_description),
                tint = Color.Unspecified,
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else if (requesterDisplayData.iconUrl != null && imageLoader != null) {
            AsyncImage(
                modifier = Modifier.size(80.dp)
                    .clickable(enabled = trustedRequesterIdentity?.identity?.certChain != null) {
                        onShowRequesterInfo()
                    },
                model = requesterDisplayData.iconUrl,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                contentDescription = null
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            modifier = Modifier
                .clickable(enabled = trustedRequesterIdentity?.identity?.certChain != null) {
                    onShowRequesterInfo()
                },
            text = requesterName,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FlipCard(
    isFlipped: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(
            durationMillis = 400,
            easing = FastOutSlowInEasing
        ),
        label = "cardFlip"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
    ) {
        if (rotation <= 90f) {
            Box(Modifier.fillMaxWidth()) {
                front()
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        rotationY = 180f
                    }
            ) {
                back()
            }
        }
    }
}
