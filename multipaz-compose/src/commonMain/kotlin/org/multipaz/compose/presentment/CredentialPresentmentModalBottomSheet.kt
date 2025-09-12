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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.Painter
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
import org.multipaz.compose.document.RenderCartArtWithFallback
import org.multipaz.compose.getApplicationInfo
import org.multipaz.compose.getOutlinedImageVector
import org.multipaz.credential.Credential
import org.multipaz.document.Document
import org.multipaz.documenttype.Icon
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_back
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_cancel
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_more
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_share
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_choose_an_option
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_data_element_icon_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_anonymous_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_headline_share_with_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list_app
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_info_verifier_in_trust_list_website
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_privacy_policy
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
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Logger
import org.multipaz.util.generateAllPaths
import kotlin.math.min

private val PAGER_INDICATOR_HEIGHT = 30.dp
private val PAGER_INDICATOR_PADDING = 8.dp

private const val TAG = "CredentialPresentmentModalBottomSheet"

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
 * Bottom sheet used for obtaining consent when presenting one or more credentials.
 *
 * @param sheetState a [SheetState] for state.
 * @param requester the relying party which is requesting the data.
 * @param trustPoint if the requester is in a trust-list, the [TrustPoint] indicating this
 * @param credentialPresentmentData the combinatinos of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param imageLoader a [ImageLoader].
 * @param dynamicMetadataResolver a function which can be used to calculate [TrustMetadata] on a
 *   per-request basis.
 * @param appName the name of the application or `null` to not show the name.
 * @param appIconPainter the icon for the application or `null to not show the icon.
 * @param onConfirm called when the user presses the "Share" button, returns the user's selection.
 * @param onCancel called when the sheet is dismissed.
 * @param showCancelAsBack if `true`, the cancel button will say "Back" instead of "Cancel".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPresentmentModalBottomSheet(
    sheetState: SheetState,
    requester: Requester,
    trustPoint: TrustPoint?,
    credentialPresentmentData: CredentialPresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader,
    dynamicMetadataResolver: (requester: Requester) -> TrustMetadata? = { chain -> null },
    appName: String? = null,
    appIconPainter: Painter? = null,
    onConfirm: (selection: CredentialPresentmentSelection) -> Unit,
    onCancel: () -> Unit = {},
    showCancelAsBack: Boolean = false
) {
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

    ModalBottomSheet(
        onDismissRequest = { onCancel() },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
        ) {
            if (appName != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Companion.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    if (appIconPainter != null) {
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
                        trustPoint = trustPoint,
                        dynamicMetadataResolver = dynamicMetadataResolver,
                        appInfo = appInfo,
                        imageLoader = imageLoader,
                        combinations = combinations,
                        matchSelectionLists = matchSelectionLists,
                        onChooseMatch = { combinationNum, elementNum ->
                            selectMatchCombinationAndElement.value = Pair(combinationNum, elementNum)
                            navController.navigate("selectMatch")
                        },
                        onConfirm = onConfirm,
                        onCancel = onCancel,
                        showCancelAsBack = showCancelAsBack,
                        sheetState = sheetState,
                        pagerState = pagerState
                    )
                }

                composable("selectMatch") {
                    val (combinationNum, elementNum) = selectMatchCombinationAndElement.value!!
                    ChooseMatchPage(
                        combinations = combinations,
                        combinationNum = combinationNum,
                        elementNum = elementNum,
                        onBackClicked = {
                            navController.navigate("main")
                        },
                        onMatchClicked = { matchNumber ->
                            matchSelectionLists.value = setMatch(
                                oldValue = matchSelectionLists.value,
                                combinationNum = combinationNum,
                                elementNum = elementNum,
                                newMatchNum = matchNumber
                            )
                            navController.navigate("main")
                        }
                    )
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
    trustPoint: TrustPoint?,
    dynamicMetadataResolver: (requester: Requester) -> TrustMetadata? = { chain -> null },
    appInfo: ApplicationInfo?,
    imageLoader: ImageLoader,
    combinations: List<Combination>,
    matchSelectionLists: MutableState<List<List<Int>>>,
    onChooseMatch: (combinationNum: Int, elementNum: Int) -> Unit,
    onConfirm: (selection: CredentialPresentmentSelection) -> Unit,
    onCancel: () -> Unit,
    showCancelAsBack: Boolean,
    sheetState: SheetState,
    pagerState: PagerState
) {
    val scrollState = rememberScrollState()

    val requesterDisplayData = if (trustPoint != null) {
        val metadata = dynamicMetadataResolver(requester) ?: trustPoint.metadata
        RequesterDisplayData(
            name = metadata.displayName,
            icon = metadata.displayIcon?.let { remember { it.toByteArray().decodeToImageBitmap() } },
            iconUrl = metadata.displayIconUrl,
            disclaimer = metadata.disclaimer,
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
            trustPoint = trustPoint,
            imageLoader = imageLoader,
            onShowCertChain = {}
        )

        Column(
            modifier = Modifier.padding(top = 8.dp)
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
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        CredentialSetViewer(
                            combinations = combinations,
                            combinationNum = page,
                            matchSelectionLists = matchSelectionLists,
                            requester = requester,
                            requesterDisplayData = requesterDisplayData,
                            trustPoint = trustPoint,
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
                showCancelAsBack = showCancelAsBack,
                sheetState = sheetState,
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
    trustPoint: TrustPoint?,
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
            combinationElement.matches[0].claims.mapNotNull { (requestedClaim, claim) ->
                if (requestedClaim is MdocRequestedClaim && requestedClaim.intentToRetain) {
                    null
                } else {
                    claim
                }
            }
        val storedClaims =
            combinationElement.matches[0].claims.mapNotNull { (requestedClaim, claim) ->
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
            trustPoint = trustPoint
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
                credential.document.RenderCartArtWithFallback()
                Column(
                    modifier = Modifier.padding(start = 16.dp).weight(1.0f)
                ) {
                    Text(
                        text = credential.document.metadata.displayName
                            ?: "No Document Title",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Companion.Bold
                    )
                    credential.document.metadata.typeDisplayName?.let {
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
    trustPoint: TrustPoint?,
) {
    if (trustPoint != null) {
        var text = if (requester.origin != null && isWebOrigin(requester.origin!!)) {
            stringResource(Res.string.credential_presentment_info_verifier_in_trust_list_website)
        } else if (requester.appId != null) {
            stringResource(Res.string.credential_presentment_info_verifier_in_trust_list_app)
        } else {
            stringResource(Res.string.credential_presentment_info_verifier_in_trust_list)
        }

        if (trustPoint.metadata.privacyPolicyUrl != null) {
            val privacyPolicyText = stringResource(
                Res.string.credential_presentment_privacy_policy,
                trustPoint.metadata.displayName ?: "",
                trustPoint.metadata.privacyPolicyUrl!!,
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
        Row {
            Icon(
                modifier = Modifier.padding(end = 12.dp),
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
            )
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
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonSection(
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit,
    showCancelAsBack: Boolean,
    sheetState: SheetState,
    scrollState: ScrollState
) {
    val coroutineScope = rememberCoroutineScope()

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        TextButton(onClick = {
            coroutineScope.launch {
                sheetState.hide()
                onCancel()
            }
        }) {
            Text(
                text = if (showCancelAsBack) {
                    stringResource(Res.string.credential_presentment_button_back)
                } else {
                    stringResource(Res.string.credential_presentment_button_cancel)
                }
            )
        }

        Button(
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
            if (scrollState.canScrollForward) {
                Text(text = stringResource(Res.string.credential_presentment_button_more))
            } else {
                Text(text = stringResource(Res.string.credential_presentment_button_share))
            }
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
        modifier = modifier.padding(8.dp),
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
    trustPoint: TrustPoint?,
    imageLoader: ImageLoader,
    onShowCertChain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val headlineText = if (requesterDisplayData.name != null) {
            stringResource(
                Res.string.credential_presentment_headline_share_with_known_requester,
                requesterDisplayData.name
            )
        } else {
            if (trustPoint != null && requester.certChain != null) {
                // If we have a trust point without `displayName` use the name in the root certificate.
                stringResource(
                    Res.string.credential_presentment_headline_share_with_known_requester,
                    trustPoint.certificate.subject.name
                )
            } else {
                if (requester.certChain != null) {
                    stringResource(Res.string.credential_presentment_headline_share_with_unknown_requester)
                } else {
                    stringResource(Res.string.credential_presentment_headline_share_with_anonymous_requester)
                }
            }
        }

        if (requesterDisplayData.icon != null) {
            Icon(
                modifier = Modifier.size(80.dp)
                    .clickable(enabled = requester.certChain != null) { onShowCertChain() },
                bitmap = requesterDisplayData.icon,
                contentDescription = stringResource(Res.string.credential_presentment_verifier_icon_description),
                tint = Color.Unspecified,
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else if (requesterDisplayData.iconUrl != null) {
            AsyncImage(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .clickable(enabled = requester.certChain != null) { onShowCertChain() },
                model = requesterDisplayData.iconUrl,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                contentDescription = null
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            modifier = Modifier
                .clickable(enabled = requester.certChain != null) { onShowCertChain() },
            text = headlineText,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun isWebOrigin(origin: String) = origin.startsWith("http://") || origin.startsWith("https://")