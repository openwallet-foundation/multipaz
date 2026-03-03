package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import org.multipaz.compose.document.VerticalDocumentList
import org.multipaz.compose.document.DocumentModel
import org.multipaz.document.DocumentStore
import org.multipaz.testapp.SettingsDestination
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.testapp.TestAppSettingsModel
import org.multipaz.util.Logger

private const val TAG = "DocumentListScreen"

private data class VisibilityOption(
    val displayName: String,
    val visibilityPercentage: Int
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VerticalDocumentListScreen(
    documentStore: DocumentStore,
    documentModel: DocumentModel,
    settingsModel: TestAppSettingsModel,
    onViewDocument: (documentId: String) -> Unit,
    onBackPressed: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showStackWhileFocused by remember { mutableStateOf(true) }
    var allowDocumentReordering by remember { mutableStateOf(true) }
    val visibilityOptions = listOf(
        VisibilityOption("25%", 25),
        VisibilityOption("50%", 50),
        VisibilityOption("75%", 75),
        VisibilityOption("100%", 100),
    )
    val visibilityOptionsExpanded = remember { mutableStateOf(false) }
    val visibilityOptionsSelected = remember { mutableStateOf(visibilityOptions[0]) }
    var focusedDocumentShowMoreInfo by rememberSaveable { mutableStateOf(false) }
    var focusedDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val focusedDocument = documentModel.documentInfos.collectAsState().value.find { documentInfo ->
        documentInfo.document.identifier == focusedDocumentId
    }

    // This hooks the back handler so we can close the focused document instead of going back.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = focusedDocumentId != null,
        onBackCompleted = {
            if (focusedDocumentShowMoreInfo) {
                focusedDocumentShowMoreInfo = false
            } else {
                focusedDocumentId = null
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val text = if (focusedDocumentId != null) {
                        if (focusedDocumentShowMoreInfo) {
                            "Document Focused (more)"
                        } else {
                            "Document Focused"
                        }
                    } else {
                        "Vertical Document List"
                    }
                    Text(text = text)},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        // Unfocus focused document instead of going back, like the back handler above
                        if (focusedDocumentId != null) {
                            if (focusedDocumentShowMoreInfo) {
                                focusedDocumentShowMoreInfo = false
                            } else {
                                focusedDocumentId = null
                            }
                        } else {
                            onBackPressed()
                        }
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
            ComboBox(
                headline = "Card visibility",
                options = visibilityOptions,
                comboBoxSelected = visibilityOptionsSelected,
                comboBoxExpanded = visibilityOptionsExpanded,
                getDisplayName = { it.displayName },
                onSelected = { index, value ->
                    visibilityOptionsSelected.value = visibilityOptions[index]
                }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = showStackWhileFocused,
                    onCheckedChange = { value ->
                        showStackWhileFocused = value
                    },
                )
                Text(
                    text = "Show stack while focused",
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = allowDocumentReordering,
                    onCheckedChange = { value ->
                        allowDocumentReordering = value
                    },
                )
                Text(
                    text = "Allow document reordering",
                )
            }

            val windowInfo = LocalWindowInfo.current
            val density = LocalDensity.current
            val maxCardHeight = with(density) {
                (windowInfo.containerSize.height / 3f).toDp()
            }

            VerticalDocumentList(
                documentModel = documentModel,
                focusedDocument = focusedDocument,
                unfocusedVisiblePercent = visibilityOptionsSelected.value.visibilityPercentage,
                allowDocumentReordering = allowDocumentReordering,
                showStackWhileFocused = showStackWhileFocused,
                cardMaxHeight = maxCardHeight,
                showDocumentInfo = { documentInfo ->
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${documentInfo.document.displayName} is focused")
                        Spacer(modifier = Modifier.weight(1.0f))
                        if (!focusedDocumentShowMoreInfo) {
                            Text("Tap card for more info!")
                        } else {
                            Button(onClick = {
                                onViewDocument(documentInfo.document.identifier)
                            }) {
                                Text("Even more info")
                            }
                        }
                    }
                },
                emptyDocumentContent = {
                    Text("No documents available.")
                },
                onDocumentFocused = { documentInfo ->
                    focusedDocumentShowMoreInfo = false
                    focusedDocumentId = documentInfo.document.identifier
                },
                onDocumentFocusedTapped = {
                    focusedDocumentShowMoreInfo = true
                },
                onDocumentFocusedStackTapped = {
                    focusedDocumentId = null
                },
                onDocumentReordered = { documentInfo, newIndex ->
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