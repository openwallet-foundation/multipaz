package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.compose.document.DocumentModel
import org.multipaz.compose.eventlogger.SimpleEventLoggerModel
import org.multipaz.compose.items.FloatingItemCenteredText
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.compose.items.FloatingItemText
import org.multipaz.datetime.formatLocalized
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventPresentment
import org.multipaz.eventlogger.SimpleEventLogger
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsMdocApi
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsOpenID4VP
import org.multipaz.eventlogger.EventPresentmentIso18013AnnexA
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.eventlogger.EventPresentmentUriSchemeOpenID4VP

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventLoggerScreen(
    eventLogger: SimpleEventLogger,
    imageLoader: ImageLoader,
    documentModel: DocumentModel,
    onEventClicked: (event: Event) -> Unit,
    onBack: () -> Unit,
    showToast: (message: String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val model = SimpleEventLoggerModel(eventLogger, coroutineScope)
    val events by model.events.collectAsState()
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }

    if (showDeleteConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmationDialog = false },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmationDialog = false }
                ) {
                    Text(text = "Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            showDeleteConfirmationDialog = false
                            eventLogger.deleteAllEvents()
                        }
                    }
                ) {
                    Text(text = "Delete all")
                }
            },
            title = {
                Text(text = "Delete all logged events?")
            },
            text = {
                Text(text = "All logged events will be permanently deleted. This action cannot be undone")
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "Event Log")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteConfirmationDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null
                        )
                    }
                }
            )
        },
    ) { innerPadding ->

        // TODO: with many events Column might be too slow, consider using LazyColumn instead.
        //
        val scrollState = rememberScrollState()
        Column(modifier = Modifier
            .verticalScroll(scrollState)
            .fillMaxSize()
            .padding(innerPadding)
            .padding(8.dp)
        ) {
            FloatingItemList(title = "Events") {
                when (val currentEvents = events) {
                    null -> {
                        CircularProgressIndicator()
                    }

                    emptyList<Event>() -> {
                        FloatingItemCenteredText(
                            text = "No events recorded yet",
                        )
                    }

                    else -> {
                        currentEvents.forEach { event ->
                            EventItem(
                                modifier = Modifier
                                    .clickable { onEventClicked(event) },
                                event = event,
                                imageLoader = imageLoader,
                                documentModel = documentModel
                            )
                        }
                    }
                }
            }
        }
    }
}

// TODO: Move to multipaz-compose when baked
@Composable
private fun EventItem(
    event: Event,
    imageLoader: ImageLoader,
    documentModel: DocumentModel,
    imageSize: Dp = 40.dp,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    modifier: Modifier = Modifier
) {
    // Right now the all events are presentment events. This will change in the future as we add
    // support for logging other events
    val presentmentData = (event as EventPresentment).presentmentData

    val sharingType = when (event) {
        is EventPresentmentDigitalCredentialsMdocApi -> getSharingType(event.origin)
        is EventPresentmentDigitalCredentialsOpenID4VP -> getSharingType(event.origin)
        is EventPresentmentIso18013AnnexA -> getSharingType(event.origin)
        is EventPresentmentIso18013Proximity -> "Shared in-person"
        is EventPresentmentUriSchemeOpenID4VP ->getSharingType(event.origin)
    }

    val firstDoc = presentmentData.requestedDocuments.firstOrNull()
    val firstDocInfo = firstDoc?.let { requestedDocument ->
        documentModel.documentInfos.collectAsState().value.find {
            it.document.identifier == requestedDocument.documentId
        }
    }

    val eventDateTimeString = event.timestamp.toLocalDateTime(timeZone = timeZone).formatLocalized()
    val text = "$eventDateTimeString • $sharingType"
    FloatingItemText(
        modifier = modifier,
        image = {
            firstDocInfo?.cardArt?.let {
                Image(
                    modifier = modifier.size(imageSize),
                    bitmap = it,
                    contentDescription = null
                )
            } ?: Spacer(modifier = Modifier.size(imageSize))
        },
        text = firstDocInfo?.document?.displayName ?: firstDoc?.documentName ?: "Unknown document",
        secondary = text,
    )
}

private fun getSharingType(origin: String?): String {
    if (origin != null) {
        if (origin.isNotEmpty() && (origin.startsWith("http://") || origin.startsWith("https://"))) {
            return "Shared with website"
        } else if (origin.isNotEmpty()) {
            return "Shared with application"
        } else {
            return "Shared with website"
        }
    }
    return "Shared with website"
}