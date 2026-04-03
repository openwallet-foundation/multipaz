package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.claim.Claim
import org.multipaz.compose.decodeImage
import org.multipaz.compose.document.DocumentModel
import org.multipaz.compose.eventlogger.SimpleEventLoggerModel
import org.multipaz.compose.getOutlinedImageVector
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.compose.rememberUiBoundCoroutineScope
import org.multipaz.compose.sharemanager.ShareManager
import org.multipaz.compose.text.fromMarkdown
import org.multipaz.crypto.X509CertChain
import org.multipaz.datetime.FormatStyle
import org.multipaz.datetime.formatLocalized
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.Icon
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventPresentment
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsMdocApi
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsOpenID4VP
import org.multipaz.eventlogger.EventPresentmentIso18013AnnexA
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.eventlogger.EventPresentmentUriSchemeOpenID4VP
import org.multipaz.eventlogger.SimpleEventLogger
import org.multipaz.eventlogger.toDataItem
import org.multipaz.prompt.PromptModel
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class, FormatStringsInDatetimeFormats::class)
@Composable
fun EventViewerScreen(
    eventLogger: SimpleEventLogger,
    eventId: String,
    documentTypeRepository: DocumentTypeRepository,
    documentModel: DocumentModel,
    imageLoader: ImageLoader,
    onViewCertificateChain: (certChain: X509CertChain) -> Unit,
    onBack: () -> Unit,
    promptModel: PromptModel,
    showToast: (message: String) -> Unit
) {
    val coroutineScope = rememberUiBoundCoroutineScope { promptModel }
    val model = remember(eventLogger) { SimpleEventLoggerModel(eventLogger, coroutineScope) }
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
                            eventLogger.getEvents().find { it.identifier == eventId }?.let {
                                eventLogger.deleteEvent(it)
                                onBack()
                            }
                        }
                    }
                ) {
                    Text(text = "Delete")
                }
            },
            title = {
                Text(text = "Delete event?")
            },
            text = {
                Text(text = "This event will be permanently deleted. This action cannot be undone")
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "Event Viewer")
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
                        onClick = {
                            coroutineScope.launch {
                                val event = eventLogger.getEvents().find { it.identifier == eventId }
                                if (event != null) {
                                    // For TestApp, just do a text file for now. In the future we might define
                                    // a binary format and provide tools for offline analysis.
                                    val format = DateTimeComponents.Format {
                                        byUnicodePattern("yyyyMMdd-HHmmss")
                                    }
                                    val timeStampString = event.timestamp.format(
                                        format = format,
                                        offset = TimeZone.currentSystemDefault().offsetAt(Clock.System.now())
                                    )
                                    val shareManager = ShareManager()
                                    shareManager.shareDocument(
                                        content = Cbor.toDiagnostics(
                                            item = event.toDataItem(),
                                            options = setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
                                        ).encodeToByteArray(),
                                        filename = "mpztestapp-event-${timeStampString}.txt",
                                        mimeType = "text/plain",
                                        title = "Multipaz TestApp Event recorded ${event.timestamp}"
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = null
                        )
                    }
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

        val scrollState = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize()
            .verticalScroll(scrollState)
            .padding(innerPadding)
            .padding(8.dp)
        ) {
            when (val currentEvents = events) {
                null -> {
                    CircularProgressIndicator()
                }

                else -> {
                    val event = currentEvents.find { it.identifier == eventId }
                    if (event != null) {
                        EventViewer(
                            event = event,
                            documentTypeRepository = documentTypeRepository,
                            documentModel = documentModel,
                            imageLoader = imageLoader,
                            onViewCertificateChain = onViewCertificateChain
                        )
                    }
                }
            }
        }
    }
}

// TODO: Move to multipaz-compose when baked
@Composable
private fun EventViewer(
    event: Event,
    documentTypeRepository: DocumentTypeRepository,
    documentModel: DocumentModel,
    imageLoader: ImageLoader,
    onViewCertificateChain: (certChain: X509CertChain) -> Unit,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    modifier: Modifier = Modifier
) {
    val eventDateTime = event.timestamp.toLocalDateTime(timeZone = timeZone)
    val eventDateTimeString = eventDateTime.formatLocalized(
        dateStyle = FormatStyle.LONG,
        timeStyle = FormatStyle.LONG
    )

    // Right now the all events are presentment events. This will change in the future as we add
    // support for logging other events
    val presentmentData = (event as EventPresentment).presentmentData

    val protocol = when (event) {
        is EventPresentmentDigitalCredentialsMdocApi -> "W3C DC API w/ ISO/IEC 18013-7:2025 Annex C"
        is EventPresentmentDigitalCredentialsOpenID4VP -> "W3C DC API w/ OpenID4VP"
        is EventPresentmentUriSchemeOpenID4VP -> "URI scheme w/ OpenID4VP"
        is EventPresentmentIso18013AnnexA -> "URI scheme w/ ISO/IEC 18013-7:2025 Annex A"
        is EventPresentmentIso18013Proximity -> "Proximity w/ ISO/IEC 18013-5:2021"
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val imageSize = 80.dp
        presentmentData.trustMetadata?.displayIcon?.let {
            val bitmap = remember { decodeImage(it.toByteArray()) }
            Image(
                modifier = Modifier.size(imageSize),
                bitmap = bitmap,
                contentDescription = null
            )
        } ?: presentmentData.trustMetadata?.displayIconUrl?.let {
            AsyncImage(
                modifier = Modifier.size(imageSize),
                model = it,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                contentDescription = null
            )
        }

        Text(
            text = presentmentData.requesterName ?: "Unknown requester",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        FloatingItemList(title = null) {
            FloatingItemHeadingAndText(
                heading = "Date and time",
                text = eventDateTimeString
            )

            when (event) {
                is EventPresentmentDigitalCredentialsMdocApi -> {
                    OriginAndAppIdItem(event.origin, event.appId)
                }
                is EventPresentmentDigitalCredentialsOpenID4VP -> {
                    OriginAndAppIdItem(event.origin, event.appId)
                }
                is EventPresentmentIso18013AnnexA -> {
                    OriginAndAppIdItem(event.origin, event.appId)
                }
                is EventPresentmentUriSchemeOpenID4VP -> {
                    OriginAndAppIdItem(event.origin, event.appId)
                }
                is EventPresentmentIso18013Proximity -> {
                    val handover = event.sessionTranscript.asArray[2]
                    if (handover == Simple.NULL) {
                        FloatingItemHeadingAndText(
                            heading = "Shared in-person",
                            text = "Using QR code"
                        )
                    } else {
                        FloatingItemHeadingAndText(
                            heading = "Shared in-person",
                            text = "Using NFC"
                        )
                    }
                }
            }

            FloatingItemHeadingAndText(
                heading = "Presentment protocol",
                text =  protocol
            )

            if (presentmentData.trustMetadata != null) {
                FloatingItemHeadingAndText(
                    heading = "Requester trusted",
                    text =  "Yes, in trust list"
                )
            } else {
                FloatingItemHeadingAndText(
                    heading = "Requester trusted",
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.error)) {
                            append("No, not in a trust list")
                        }
                    }
                )
            }

            presentmentData.requesterCertChain?.let {
                FloatingItemHeadingAndText(
                    heading = "Requester certificate",
                    text = "Click to view",
                    modifier = Modifier.clickable {
                        onViewCertificateChain(it)
                    }
                )
            } ?: run {
                FloatingItemHeadingAndText(
                    heading = "Requester certificate",
                    text = "Not available",
                )
            }

            presentmentData.trustMetadata?.privacyPolicyUrl?.let {
                FloatingItemHeadingAndText(
                    heading = "Requester privacy policy",
                    text = AnnotatedString.fromMarkdown(
                        markdownString = "[$it]($it)"
                    )
                )
            }
        }

        presentmentData.requestedDocuments.forEach { requestedDocument ->
            val info = documentModel.documentInfos.collectAsState().value.find {
                it.document.identifier == requestedDocument.documentId
            }
            if (info != null) {
                Image(
                    modifier = Modifier.height(80.dp),
                    bitmap = info.cardArt,
                    contentDescription = null
                )
                Text(
                    text = info.document.displayName ?: "Unknown document",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            val sharedClaims = requestedDocument.claims.filter { (requestedClaim, _) ->
                if (requestedClaim is MdocRequestedClaim) !requestedClaim.intentToRetain else true
            }
            if (sharedClaims.isNotEmpty()) {
                FloatingItemList(title = "This info was shared") {
                    ExtractClaimsItems(sharedClaims, documentTypeRepository)
                }
            }

            val sharedAndStoredClaims = requestedDocument.claims.filter { (requestedClaim, _) ->
                if (requestedClaim is MdocRequestedClaim) requestedClaim.intentToRetain else false
            }
            if (sharedAndStoredClaims.isNotEmpty()) {
                FloatingItemList(title = "This info was shared and stored") {
                    ExtractClaimsItems(sharedAndStoredClaims, documentTypeRepository)
                }
            }
        }
    }
}

@Composable
private fun OriginAndAppIdItem(
    origin: String?,
    appId: String?,
) {
    if (origin != null && origin.isNotEmpty() && (origin.startsWith("http://") || origin.startsWith("https://"))) {
        FloatingItemHeadingAndText(
            heading = "Shared with website",
            text = AnnotatedString.fromMarkdown("[$origin]($origin)")
        )
    } else if (origin != null && origin.isNotEmpty()) {
        if (appId != null) {
            // TODO: look up details about the application
            FloatingItemHeadingAndText(
                heading = "Shared with application",
                text = appId
            )
        } else {
            FloatingItemHeadingAndText(
                heading = "Shared with application",
                text = "Unknown application"
            )
        }
    } else {
        FloatingItemHeadingAndText(
            heading = "Shared with website",
            text = "Unknown website"
        )
    }
}

@Composable
private fun ExtractClaimsItems(
    requestedClaims: Map<RequestedClaim, Claim>,
    documentTypeRepository: DocumentTypeRepository
) {
    requestedClaims.forEach { (requestedClaim, claim) ->
        // Make sure claim.attribute is set, if we know the document type
        val claim = Claim.fromDataItem(
            dataItem = claim.toDataItem(),
            documentTypeRepository = documentTypeRepository
        )
        FloatingItemHeadingAndText(
            heading = claim.displayName,
            text = claim.render(),
            image = {
                val icon = claim.attribute?.icon ?: Icon.PERSON
                Icon(
                    imageVector = icon.getOutlinedImageVector(),
                    contentDescription = null
                )
            }
        )
    }
}