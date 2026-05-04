package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VerticalCardListScreen(
    documentStore: DocumentStore,
    documentModel: DocumentModel,
    settingsModel: TestAppSettingsModel,
    focusedDocumentId: String?,
    state: VerticalCardListState = rememberVerticalCardListState(),
    onDocumentFocused: (documentId: String) -> Unit,
    onDocumentUnfocused: () -> Unit,
    onViewDocument: (documentId: String) -> Unit,
    onFocusDocumentFollowing: (documentId: String) -> Unit,
    onBackPressed: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val text = if (focusedDocumentId != null) {
                        "Vertical Card List (doc focused)"
                    } else {
                        "Vertical Card List"
                    }
                    Text(text = text)},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        onBackPressed()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = innerPadding.calculateEndPadding(LocalLayoutDirection.current)
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
                state = state,
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
                    onDocumentUnfocused()
                },
                onCardFocusedStackTapped = {
                    onDocumentUnfocused()
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