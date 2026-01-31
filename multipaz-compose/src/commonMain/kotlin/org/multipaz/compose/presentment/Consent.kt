package org.multipaz.compose.presentment

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDownCircle
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.multipaz.claim.Claim
import org.multipaz.compose.ApplicationInfo
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.compose.decodeImage
import org.multipaz.compose.getApplicationInfo
import org.multipaz.compose.getOutlinedImageVector
import org.multipaz.credential.Credential
import org.multipaz.document.Document
import org.multipaz.documenttype.Icon
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_cancel
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_more
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_share
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_choose_an_option
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_data_element_icon_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list_app
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list_website
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_privacy_policy
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_requester_information
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_and_stored_by_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_share_with_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_verifier_icon_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_anonymous
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_app
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_warning_verifier_not_in_trust_list_website
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import org.multipaz.util.generateAllPaths
import kotlin.math.min

private val PAGER_INDICATOR_HEIGHT = 30.dp
private val PAGER_INDICATOR_PADDING = 8.dp

private const val TAG = "Consent"

private data class CombinationElement(
    val matches: List<CredentialPresentmentSetOptionMemberMatch>
)

private data class Combination(
    val elements: List<CombinationElement>
)

private fun CredentialPresentmentData.generateCombinations(preselectedDocuments: List<Document>): List<Combination> {
    val combinations = mutableListOf<Combination>()

    // First consolidate all single-member options into one...
    val consolidated = consolidate()

    // ...then explode all combinations
    val credentialSetsMaxPath = mutableListOf<Int>()
    consolidated.credentialSets.forEachIndexed { n, credentialSet ->
        // If a credentialSet is optional, it's an extra combination we tag at the end
        credentialSetsMaxPath.add(credentialSet.options.size + (if (credentialSet.optional) 1 else 0))
    }

    for (path in credentialSetsMaxPath.generateAllPaths()) {
        val elements = mutableListOf<CombinationElement>()
        consolidated.credentialSets.forEachIndexed { credentialSetNum, credentialSet ->
            val omitCredentialSet = (path[credentialSetNum] == credentialSet.options.size)
            if (omitCredentialSet) {
                check(credentialSet.optional)
            } else {
                val option = credentialSet.options[path[credentialSetNum]]
                for (member in option.members) {
                    elements.add(CombinationElement(
                        matches = member.matches
                    ))
                }
            }
        }
        combinations.add(Combination(
            elements = elements
        ))
    }

    if (preselectedDocuments.size == 0) {
        return combinations
    }

    val setOfPreselectedDocuments = preselectedDocuments.toSet()
    combinations.forEach { combination ->
        if (combination.elements.size == preselectedDocuments.size) {
            val chosenElements = mutableListOf<CombinationElement>()
            combination.elements.forEachIndexed { n, element ->
                val match = element.matches.find { setOfPreselectedDocuments.contains(it.credential.document) }
                if (match == null) {
                    return@forEach
                }
                chosenElements.add(CombinationElement(matches = listOf(match)))
            }
            // Winner, winner, chicken dinner!
            return listOf(Combination(elements = chosenElements))
        }
    }
    Logger.w(TAG, "Error picking combination for pre-selected documents")
    return combinations
}

private fun setMatch(
    oldValue: List<List<Int>>,
    combinationNum: Int,
    elementNum: Int,
    newMatchNum: Int,
): List<List<Int>> {
    return buildList {
        oldValue.forEachIndexed { combinationNum_, combinations ->
            add(buildList {
                combinations.forEachIndexed { credentialSetNum_, match ->
                    if (combinationNum_ == combinationNum && elementNum == credentialSetNum_) {
                        add(newMatchNum)
                    } else {
                        add(match)
                    }
                }
            })
        }
    }
}

/**
 * A composable used for obtaining consent when presenting one or more credentials.
 *
 * @param modifier a [Modifier].
 * @param requester the relying party which is requesting the data.
 * @param trustMetadata [TrustMetadata] conveying the level of trust in the requester, if any.
 * @param credentialPresentmentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param imageLoader a [ImageLoader].
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @param onConfirm called when the user presses the "Share" button, returns the user's selection.
 * @param onCancel called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Consent(
    modifier: Modifier = Modifier,
    requester: Requester,
    trustMetadata: TrustMetadata?,
    credentialPresentmentData: CredentialPresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onConfirm: (selection: CredentialPresentmentSelection) -> Unit,
    onCancel: () -> Unit = {},
) {
    val currentBranding = Branding.Current.collectAsState().value
    val navController = rememberNavController()
    val appInfo = remember {
        requester.appId?.let {
            try {
                getApplicationInfo(it)
            } catch (e: Throwable) {
                Logger.w(TAG, "Error looking up information for appId $it")
                null
            }
        }
    }
    val combinations = remember { credentialPresentmentData.generateCombinations(preselectedDocuments) }
    val selectMatchCombinationAndElement = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val matchSelectionLists = remember {
        val initialSelections = combinations.map { List(it.elements.size) { 0 } }
        mutableStateOf(initialSelections)
    }
    val pagerState = rememberPagerState(pageCount = { combinations.size })

    // Make sure we inform the caller when the selection of documents change.
    val lastSentDocumentsInFocus = remember { mutableStateOf<List<Document>?>(null) }
    val currentDocumentsInFocus =
        CredentialPresentmentSelection(
            matches = matchSelectionLists.value[pagerState.currentPage].mapIndexed { n, selectedMatch ->
                combinations[pagerState.currentPage].elements[n].matches[selectedMatch]
            },
        ).matches.map { it.credential.document }
    if (currentDocumentsInFocus != lastSentDocumentsInFocus.value) {
        onDocumentsInFocus(currentDocumentsInFocus)
        lastSentDocumentsInFocus.value = currentDocumentsInFocus
    }

    Column(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
    ) {
        currentBranding.appName?.let { appName ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Companion.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                currentBranding.appIconPainter?.let { appIconPainter ->
                    Image(
                        modifier = Modifier.size(20.dp),
                        painter = appIconPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Companion.Fit,
                    )
                }
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Companion.ExtraBold,
                )
            }
        }

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
                    trustMetadata = trustMetadata,
                    appInfo = appInfo,
                    imageLoader = imageLoader,
                    combinations = combinations,
                    matchSelectionLists = matchSelectionLists,
                    onChooseMatch = { combinationNum, elementNum ->
                        selectMatchCombinationAndElement.value = Pair(combinationNum, elementNum)
                        navController.navigate("selectMatch")
                    },
                    onShowRequesterInfo = {
                        navController.navigate("showRequesterInfo")
                    },
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                    pagerState = pagerState
                )
            }

            composable("showRequesterInfo") {
                ShowRequesterInfoPage(
                    requester = requester,
                    trustMetadata = trustMetadata,
                    onBackClicked = {
                        navController.navigateUp()
                    },
                )
            }
            composable("selectMatch") {
                val (combinationNum, elementNum) = selectMatchCombinationAndElement.value!!
                ChooseMatchPage(
                    combinations = combinations,
                    combinationNum = combinationNum,
                    elementNum = elementNum,
                    onBackClicked = {
                        navController.navigateUp()
                    },
                    onMatchClicked = { matchNumber ->
                        matchSelectionLists.value = setMatch(
                            oldValue = matchSelectionLists.value,
                            combinationNum = combinationNum,
                            elementNum = elementNum,
                            newMatchNum = matchNumber
                        )
                        navController.navigateUp()
                    }
                )
            }
        }
    }
}

@Composable
private fun ShowRequesterInfoPage(
    requester: Requester,
    trustMetadata: TrustMetadata?,
    onBackClicked: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
            ),
            verticalAlignment = Alignment.Companion.CenterVertically
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

        requester.certChain?.let { certChain ->
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
                            modifier = Modifier.verticalScroll(scrollState),
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

@Composable
private fun ChooseMatchPage(
    combinations: List<Combination>,
    combinationNum: Int,
    elementNum: Int,
    onBackClicked: () -> Unit,
    onMatchClicked: (matchNumber: Int) -> Unit
) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
            ),
            verticalAlignment = Alignment.Companion.CenterVertically
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
                    text = stringResource(Res.string.credential_presentment_choose_an_option),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        val entries = mutableListOf<@Composable () -> Unit>()

        combinations[combinationNum].elements[elementNum].matches.forEachIndexed { matchNum, match ->
            entries.add {
                CredentialViewer(
                    modifier = Modifier.clickable { onMatchClicked(matchNum) },
                    credential = match.credential,
                    showOptionsButton = false,
                    onOptionsButtonClicked = {}
                )
            }
        }

        EntryList(
            title = null,
            entries = entries
        )

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
    trustMetadata: TrustMetadata?,
    appInfo: ApplicationInfo?,
    imageLoader: ImageLoader?,
    combinations: List<Combination>,
    matchSelectionLists: MutableState<List<List<Int>>>,
    onChooseMatch: (combinationNum: Int, elementNum: Int) -> Unit,
    onShowRequesterInfo: () -> Unit,
    onConfirm: (selection: CredentialPresentmentSelection) -> Unit,
    onCancel: () -> Unit,
    pagerState: PagerState
) {
    val scrollState = rememberScrollState()

    val requesterDisplayData = if (trustMetadata != null) {
        RequesterDisplayData(
            name = trustMetadata.displayName,
            icon = trustMetadata.displayIcon?.let { remember { it.toByteArray().decodeToImageBitmap() } },
            iconUrl = trustMetadata.displayIconUrl,
            disclaimer = trustMetadata.disclaimer,
        )
    } else if (requester.origin != null && isWebOrigin(requester.origin!!)) {
        RequesterDisplayData(
            name = requester.origin,
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
            requester = requester,
            requesterDisplayData = requesterDisplayData,
            trustMetadata = trustMetadata,
            imageLoader = imageLoader,
            onShowRequesterInfo = onShowRequesterInfo
        )

        Column(
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .focusGroup()
                    .verticalScroll(scrollState)
                    .weight(0.9f, false)
            ) {
                HorizontalPager(
                    state = pagerState,
                ) { page ->
                    // Add a very slight drop shadow to make the main box stand out.
                    Column(
                        modifier = Modifier
                            .padding(10.dp)
                            .dropShadow(
                                shape = RoundedCornerShape(10.dp),
                                shadow = Shadow(
                                    radius = 10.dp,
                                    spread = 5.dp,
                                    color = Color.Black.copy(alpha = 0.035f),
                                    offset = DpOffset(x = 0.dp, 2.dp)
                                )
                            ),
                    ) {
                        CredentialSetViewer(
                            combinations = combinations,
                            combinationNum = page,
                            matchSelectionLists = matchSelectionLists,
                            requester = requester,
                            requesterDisplayData = requesterDisplayData,
                            trustMetadata = trustMetadata,
                            appInfo = appInfo,
                            onChooseMatch = onChooseMatch
                        )
                    }
                }
            }

            if (combinations.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .align(Alignment.Companion.End)
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
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .2f)
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

            ButtonSection(
                onConfirm = {
                    onConfirm(CredentialPresentmentSelection(
                        matches = matchSelectionLists.value[pagerState.currentPage].mapIndexed { n, selectedMatch ->
                            combinations[pagerState.currentPage].elements[n].matches[selectedMatch]
                        },
                    ))
                },
                onCancel = onCancel,
                scrollState = scrollState
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialSetViewer(
    modifier: Modifier = Modifier,
    combinations: List<Combination>,
    combinationNum: Int,
    matchSelectionLists: MutableState<List<List<Int>>>,
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    trustMetadata: TrustMetadata?,
    appInfo: ApplicationInfo?,
    onChooseMatch: (combinationNum: Int, elementNum: Int) -> Unit
) {

    val entries = mutableListOf<@Composable () -> Unit>()

    combinations[combinationNum].elements.forEachIndexed { elementNum, combinationElement ->
        val matchNum = matchSelectionLists.value[combinationNum][elementNum]
        entries.add {
            CredentialViewer(
                credential = combinationElement.matches[matchNum].credential,
                showOptionsButton = combinationElement.matches.size > 1,
                onOptionsButtonClicked = { onChooseMatch(combinationNum, elementNum) }
            )
        }
        val notStoredClaims =
            combinationElement.matches[matchNum].claims.mapNotNull { (requestedClaim, claim) ->
                if (requestedClaim is MdocRequestedClaim && requestedClaim.intentToRetain) {
                    null
                } else {
                    claim
                }
            }
        val storedClaims =
            combinationElement.matches[matchNum].claims.mapNotNull { (requestedClaim, claim) ->
                if (requestedClaim is MdocRequestedClaim && requestedClaim.intentToRetain) {
                    claim
                } else {
                    null
                }
            }

        val sharedWithText =
            if (requesterDisplayData.name != null) {
                stringResource(
                    Res.string.credential_presentment_share_with_known_requester,
                    requesterDisplayData.name
                )
            } else if (requester.origin != null && isWebOrigin(requester.origin!!)) {
                stringResource(
                    Res.string.credential_presentment_share_with_known_requester,
                    requester.origin!!
                )
            } else if (appInfo != null) {
                stringResource(
                    Res.string.credential_presentment_share_with_known_requester,
                    appInfo.name
                )
            } else {
                stringResource(Res.string.credential_presentment_share_with_unknown_requester)
            }
        val sharedWithAndStoredByText =
            if (requesterDisplayData.name != null) {
                stringResource(
                    Res.string.credential_presentment_share_and_stored_by_known_requester,
                    requesterDisplayData.name
                )
            } else if (requester.origin != null && isWebOrigin(requester.origin!!)) {
                stringResource(
                    Res.string.credential_presentment_share_and_stored_by_known_requester,
                    requester.origin!!
                )
            } else if (appInfo != null) {
                stringResource(
                    Res.string.credential_presentment_share_and_stored_by_known_requester,
                    appInfo.name
                )
            } else {
                stringResource(Res.string.credential_presentment_share_and_stored_by_unknown_requester)
            }

        entries.add {
            if (storedClaims.size == 0) {
                SharedStoredText(text = sharedWithText)
                ClaimsGridView(claims = notStoredClaims, useColumns = true)
            } else if (notStoredClaims.size == 0) {
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

    entries.add {
        RelyingPartyTrailer(
            requester = requester,
            trustMetadata = trustMetadata
        )
    }

    if (requesterDisplayData.disclaimer != null) {
        entries.add {
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

    EntryList(
        modifier = modifier,
        title = null,
        entries = entries
    )
}

@Composable
private fun CredentialViewer(
    modifier: Modifier = Modifier,
    credential: Credential,
    showOptionsButton: Boolean,
    onOptionsButtonClicked: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val branding = Branding.Current.collectAsState().value

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Companion.Start),
        verticalAlignment = Alignment.Companion.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1.0f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    8.dp,
                    alignment = Alignment.Companion.Start
                ),
                verticalAlignment = Alignment.Companion.CenterVertically
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
                        modifier = modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                        )
                    }
                }
                Column(
                    modifier = Modifier.padding(start = 16.dp).weight(1.0f)
                ) {
                    Text(
                        text = credential.document.displayName
                            ?: "No Document Title",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Companion.Bold
                    )
                    credential.document.typeDisplayName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                if (showOptionsButton) {
                    Icon(
                        modifier = Modifier.clickable { onOptionsButtonClicked() },
                        imageVector = Icons.Outlined.ArrowDropDownCircle,
                        tint = MaterialTheme.colorScheme.primary,
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
        fontWeight = FontWeight.Companion.Bold
    )
}

@Composable
private fun RelyingPartyTrailer(
    requester: Requester,
    trustMetadata: TrustMetadata?,
) {
    if (trustMetadata != null) {
        var text = if (requester.origin != null && isWebOrigin(requester.origin!!)) {
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
                text = AnnotatedString.Companion.fromMarkdown(markdownString = text),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        val text = if (requester.origin != null && isWebOrigin(requester.origin!!)) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_website)
        } else if (requester.appId != null) {
            stringResource(Res.string.credential_presentment_warning_verifier_not_in_trust_list_app)
        } else {
            if (requester.certChain != null) {
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


@Composable
private fun EntryList(
    modifier: Modifier = Modifier,
    title: String?,
    entries: List<@Composable () -> Unit>,
) {
    if (title != null) {
        Text(
            modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Companion.Bold,
            color = MaterialTheme.colorScheme.secondary,
        )
    }

    entries.forEachIndexed { n, section ->
        val isFirst = (n == 0)
        val isLast = (n == entries.size - 1)
        val rounded = 16.dp
        val firstRounded = if (isFirst) rounded else 0.dp
        val endRound = if (isLast) rounded else 0.dp
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape = RoundedCornerShape(firstRounded, firstRounded, endRound, endRound))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(8.dp),
            horizontalAlignment = Alignment.Companion.CenterHorizontally
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                section()
            }
        }
        if (!isLast) {
            Spacer(modifier = Modifier.height(1.dp))
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
        verticalAlignment = Alignment.Companion.CenterVertically,
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
            fontWeight = FontWeight.Companion.Normal,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// This only supports links for now, would be nice to have a full support...
//
private fun AnnotatedString.Companion.fromMarkdown(
    markdownString: String,
    linkInteractionListener: LinkInteractionListener? = null
): AnnotatedString {
    val linkRegex = """\[(.*?)\]\((.*?)\)""".toRegex()

    val links = linkRegex.findAll(markdownString).toMutableList()
    links.sortBy { it.range.start }

    return buildAnnotatedString {
        var idx = 0
        for (link in links) {
            if (idx < link.range.start) {
                append(markdownString.substring(idx, link.range.start))
            }
            val linkText = link.groupValues[1]
            val linkUrl = link.groupValues[2]
            val styleStart = length
            append(linkText)
            addLink(
                url = LinkAnnotation.Url(
                    url = linkUrl,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = Color.Companion.Blue,
                            textDecoration = TextDecoration.Companion.Underline
                        ),
                    ),
                    linkInteractionListener = linkInteractionListener
                ),
                start = styleStart,
                end = length,
            )
            idx = link.range.endInclusive + 1
        }
        if (idx < markdownString.length) {
            append(markdownString.substring(idx, markdownString.length))
        }
    }
}

@Composable
private fun RelyingPartySection(
    requester: Requester,
    requesterDisplayData: RequesterDisplayData,
    trustMetadata: TrustMetadata?,
    imageLoader: ImageLoader?,
    onShowRequesterInfo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val headlineText = if (requesterDisplayData.name != null) {
            requesterDisplayData.name
        } else {
            if (trustMetadata != null && requester.certChain != null) {
                // If we have a trust point without `displayName` use the name in the root certificate.
                requester.certChain!!.certificates.last().subject.name
            } else {
                // We could distinguish between anonymous and unknown request but that's already
                // done in the warning text
                stringResource(Res.string.credential_presentment_headline_share_with_unknown_requester)
            }
        }

        if (requesterDisplayData.icon != null) {
            Icon(
                modifier = Modifier.size(80.dp)
                    .clickable(enabled = requester.certChain != null) { onShowRequesterInfo() },
                bitmap = requesterDisplayData.icon,
                contentDescription = stringResource(Res.string.credential_presentment_verifier_icon_description),
                tint = Color.Unspecified,
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else if (requesterDisplayData.iconUrl != null && imageLoader != null) {
            AsyncImage(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .clickable(enabled = requester.certChain != null) { onShowRequesterInfo() },
                model = requesterDisplayData.iconUrl,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                contentDescription = null
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            modifier = Modifier
                .clickable(enabled = requester.certChain != null) { onShowRequesterInfo() },
            text = headlineText,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun isWebOrigin(origin: String) = origin.startsWith("http://") || origin.startsWith("https://")