package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.compose.cards.VerticalCardList
import org.multipaz.compose.cards.VerticalCardListState
import org.multipaz.compose.cards.rememberVerticalCardListState
import org.multipaz.compose.document.DocumentModel
import org.multipaz.compose.document.DocumentInfo
import org.multipaz.document.DocumentStore
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.util.Logger
import kotlin.time.Duration.Companion.seconds

private const val TAG = "VerticalCardListScreen"

private data class VisibilityOption(
    val displayName: String,
    val visibilityPercentage: Int
)

@OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalHazeMaterialsApi::class
)
@Composable
fun VerticalCardListScreen(
    documentStore: DocumentStore,
    documentModel: DocumentModel,
    settingsModel: TestAppSettingsModel,
    focusedDocumentId: String?,
    animateListTransitions: Boolean = false,
    isPreviousScreenCardList: Boolean = false,
    state: VerticalCardListState = rememberVerticalCardListState(),
    onDocumentFocused: (documentId: String) -> Unit,
    onNavigateBack: () -> Unit,
    onViewDocument: (documentId: String) -> Unit,
    onFocusDocumentFollowing: (documentId: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    var isUnfocusing by remember { mutableStateOf(false) }

    val handleBack: () -> Unit = {
        if (!isUnfocusing) {
            if (isPreviousScreenCardList) {
                isUnfocusing = true
                coroutineScope.launch {
                    state.unfocus()
                    onNavigateBack()
                }
            } else {
                onNavigateBack()
            }
        }
    }

    val navState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navState,
        isBackEnabled = focusedDocumentId != null,
        onBackCompleted = {
            handleBack()
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = Color.Transparent,
                        tints = listOf(
                            HazeTint(MaterialTheme.colorScheme.primaryContainer)
                        ),
                        blurRadius = 24.dp
                    )
                ) {
                    progressive = HazeProgressive.verticalGradient(
                        startIntensity = 1f,
                        endIntensity = 0.5f
                    )
                },
                title = { Text(text = "Vertical Card List") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (focusedDocumentId != null) {
                            handleBack()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Top Composable",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Checkbox(
                                checked = state.showTopContent,
                                onCheckedChange = { state.showTopContent = it }
                            )
                        }
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    documentModel.setDocumentOrder(documentModel.documentOrder.shuffled())
                                }
                            }
                        ) {
                            Text("Shuffle")
                        }
                    }
                }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .padding(
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current)
                    // Omitting top padding so the card list extends up under the TopAppBar
                    // Omitting the bottom padding since we want to draw under the navigation bar
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val windowInfo = LocalWindowInfo.current
            val density = LocalDensity.current
            val maxCardHeight = with(density) {
                (windowInfo.containerSize.height / 3f).toDp()
            }

            val cardInfos by documentModel.documentInfos.collectAsState()
            val focusedCard = cardInfos.find { it.document.identifier == focusedDocumentId }
            VerticalCardList(
                cardInfos = cardInfos,
                focusedCard = focusedCard,
                unfocusedVisiblePercent = 25,
                allowCardReordering = true,
                showStackWhileFocused = true,
                cardMaxHeight = maxCardHeight,
                paddingTop = innerPadding.calculateTopPadding() + 16.dp,
                animateListTransitions = animateListTransitions,
                state = state,
                topContent = {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Top Composable Demo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "This composable appears above cards when no card is focused and is hidden when a card is focused.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                showCardInfo = { cardInfo ->
                    val documentInfo = cardInfo as DocumentInfo
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${documentInfo.document.displayName} is focused")
                        Button(onClick = {
                            onFocusDocumentFollowing(documentInfo.document.identifier)
                        }) {
                            Text("Next document")
                        }
                        Spacer(modifier = Modifier.weight(1.0f))
                        Button(onClick = {
                            onViewDocument(documentInfo.document.identifier)
                        }) {
                            Text("Document Info")
                        }
                    }
                },
                emptyContent = {
                    Text("No documents available.")
                },
                onCardFocused = { cardInfo ->
                    val documentInfo = cardInfo as DocumentInfo
                    onDocumentFocused(documentInfo.document.identifier)
                },
                onCardFocusedTapped = {
                    handleBack()
                },
                onCardFocusedStackTapped = {
                    handleBack()
                },
                onCardReordered = { cardInfo, newIndex ->
                    val documentInfo = cardInfo as DocumentInfo
                    coroutineScope.launch {
                        try {
                            documentModel.setDocumentPosition(
                                documentInfo = documentInfo,
                                position = newIndex
                            )
                        } catch (e: IllegalArgumentException) {
                            Logger.e(TAG, "Error setting document position", e)
                        }
                    }
                }
            )
        }
    }
}