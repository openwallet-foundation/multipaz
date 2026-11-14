package org.multipaz.compose.presentment

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.multipaz.cbor.DataItem
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.context.applicationContext
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.presentment.Iso18013ReaderQrPresentment
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentProtocol
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.PresentmentTelemetry
import org.multipaz.presentment.classifyPresentmentException
import org.multipaz.prompt.PromptModel
import kotlin.time.Clock

@Composable
actual fun MdocProximityReaderQrPresentment(
    modifier: Modifier,
    source: PresentmentSource,
    promptModel: PromptModel,
    prepareSettings: @Composable (scanQrCode: (settings: MdocProximityReaderQrSettings) -> Unit) -> Unit,
    scanQrCode: @Composable (onQrCodeScanned: (uri: String) -> Boolean, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document>,
    capabilities: Map<Capability, DataItem>,
    supportedCurves: List<EcCurve>,
    transportFactory: MdocTransportFactory,
    disablePlatformSpecificImplementation: Boolean,
) {
    if (disablePlatformSpecificImplementation) {
        MdocProximityReaderQrPresentmentDefault(
            modifier = modifier,
            source = source,
            promptModel = promptModel,
            prepareSettings = prepareSettings,
            scanQrCode = scanQrCode,
            showTransacting = showTransacting,
            showCompleted = showCompleted,
            preselectedDocuments = preselectedDocuments,
            capabilities = capabilities,
            supportedCurves = supportedCurves,
            transportFactory = transportFactory,
        )
    } else {
        MdocProximityReaderQrPresentmentAndroid(
            modifier = modifier,
            source = source,
            prepareSettings = prepareSettings,
            scanQrCode = scanQrCode,
            showTransacting = showTransacting,
            showCompleted = showCompleted,
            preselectedDocuments = preselectedDocuments,
            capabilities = capabilities,
            supportedCurves = supportedCurves,
            transportFactory = transportFactory,
        )
    }
}

private enum class ReaderQrStateAndroid {
    PREPARE_SETTINGS,
    SCAN_QR_CODE,
    TRANSACTING,
    COMPLETED,
}

@Composable
private fun MdocProximityReaderQrPresentmentAndroid(
    modifier: Modifier,
    source: PresentmentSource,
    prepareSettings: @Composable (scanQrCode: (settings: MdocProximityReaderQrSettings) -> Unit) -> Unit,
    scanQrCode: @Composable (onQrCodeScanned: (uri: String) -> Boolean, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document>,
    capabilities: Map<Capability, DataItem>,
    supportedCurves: List<EcCurve>,
    transportFactory: MdocTransportFactory,
) {
    val coroutineScope = rememberCoroutineScope { PresentmentActivity.promptModel }
    var state by remember { mutableStateOf(ReaderQrStateAndroid.PREPARE_SETTINGS) }
    var activeSettings by remember { mutableStateOf(MdocProximityReaderQrSettings()) }
    var transactionError by remember { mutableStateOf<Throwable?>(null) }
    var transactionJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            ReaderQrStateAndroid.PREPARE_SETTINGS -> {
                prepareSettings { readerQrSettings ->
                    activeSettings = readerQrSettings
                    transactionError = null
                    state = ReaderQrStateAndroid.SCAN_QR_CODE
                }
            }
            ReaderQrStateAndroid.SCAN_QR_CODE -> {
                scanQrCode(
                    { uri ->
                        if (transactionJob != null || !isCandidateReaderQrUri(uri)) {
                            false
                        } else {
                            val settingsSnapshot = activeSettings
                            var listenForCancellationFromUiJob: Job? = null
                            state = ReaderQrStateAndroid.TRANSACTING
                            transactionJob = coroutineScope.launch {
                                transactionError = null
                                val timeStarted = Clock.System.now()
                                var enteredPresentment = false
                                var lastTelemetry: PresentmentTelemetry? = null

                                try {
                                    PresentmentActivity.presentmentModel.reset(
                                        source = source,
                                        preselectedDocuments = preselectedDocuments,
                                    )
                                    PresentmentActivity.presentmentModel.setConnecting()
                                    val intent = Intent(applicationContext, PresentmentActivity::class.java)
                                    intent.addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                                    )
                                    applicationContext.startActivity(intent)

                                    listenForCancellationFromUiJob = CoroutineScope(Dispatchers.Main).launch {
                                        PresentmentActivity.presentmentModel.state.collect { modelState ->
                                            if (modelState == PresentmentModel.State.CanceledByUser) {
                                                transactionJob?.cancel()
                                                listenForCancellationFromUiJob?.cancel()
                                                listenForCancellationFromUiJob = null
                                            }
                                        }
                                    }

                                    Iso18013ReaderQrPresentment(
                                        uri = uri,
                                        source = source,
                                        transportFactory = transportFactory,
                                        options = settingsSnapshot.createTransportOptions,
                                        preferredConnectionMethodOrder = settingsSnapshot.preferredConnectionMethodOrder,
                                        bleDirectL2capEnabled = settingsSnapshot.bleDirectL2capEnabled,
                                        supportedCurves = supportedCurves,
                                        capabilities = capabilities,
                                        preselectedDocuments = preselectedDocuments,
                                        onConnecting = { PresentmentActivity.presentmentModel.setConnecting() },
                                        onWaitingForRequest = {
                                            enteredPresentment = true
                                            PresentmentActivity.presentmentModel.setWaitingForReader()
                                        },
                                        onWaitingForUserInput = {
                                            PresentmentActivity.presentmentModel.setWaitingForUserInput()
                                        },
                                        onDocumentsInFocus = { documents ->
                                            PresentmentActivity.presentmentModel.setDocumentsSelected(
                                                selectedDocuments = documents
                                            )
                                        },
                                        onSendingResponse = { PresentmentActivity.presentmentModel.setSending() },
                                        onTelemetry = { lastTelemetry = it },
                                    )
                                    PresentmentActivity.presentmentModel.setCompleted(null, lastTelemetry)
                                } catch (e: Exception) {
                                    val resolvedTelemetry = lastTelemetry ?: PresentmentTelemetry(
                                        protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
                                        engagementType = EngagementType.READER_QR_CODE,
                                        outcome = classifyPresentmentException(
                                            e,
                                            waitingForConnection = !enteredPresentment
                                        ),
                                        durationTotal = Clock.System.now() - timeStarted,
                                    )
                                    if (e is CancellationException) {
                                        PresentmentActivity.presentmentModel.setCompleted(
                                            PresentmentCanceledException("Presentment was cancelled"),
                                            resolvedTelemetry
                                        )
                                    } else {
                                        PresentmentActivity.presentmentModel.setCompleted(e, resolvedTelemetry)
                                    }
                                    transactionError = e
                                    if (e is CancellationException) throw e
                                } finally {
                                    state = ReaderQrStateAndroid.COMPLETED
                                    transactionJob = null
                                    listenForCancellationFromUiJob?.cancel()
                                    listenForCancellationFromUiJob = null
                                }
                            }
                            true
                        }
                    },
                    {
                        transactionJob?.cancel()
                        transactionJob = null
                        state = ReaderQrStateAndroid.PREPARE_SETTINGS
                    }
                )
            }
            ReaderQrStateAndroid.TRANSACTING -> {
                showTransacting {
                    transactionJob?.cancel()
                }
            }
            ReaderQrStateAndroid.COMPLETED -> {
                showCompleted(
                    transactionError,
                    { state = ReaderQrStateAndroid.PREPARE_SETTINGS }
                )
            }
        }
    }
}
