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
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.context.applicationContext
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.util.toBase64Url

@Composable
actual fun MdocProximityQrPresentment(
    modifier: Modifier,
    source: PresentmentSource,
    promptModel: PromptModel,
    prepareSettings: @Composable (generateQrCode: (settings: MdocProximityQrSettings) -> Unit) -> Unit,
    showQrCode: @Composable (uri: String, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document>,
    eDeviceKeyCurve: EcCurve,
    transportFactory: MdocTransportFactory,
    disablePlatformSpecificImplementation: Boolean
) {
    if (disablePlatformSpecificImplementation) {
        MdocProximityQrPresentmentDefault(
            modifier = modifier,
            source = source,
            promptModel = promptModel,
            prepareSettings = prepareSettings,
            showQrCode = showQrCode,
            showTransacting = showTransacting,
            showCompleted = showCompleted,
            preselectedDocuments = preselectedDocuments,
            eDeviceKeyCurve = eDeviceKeyCurve,
            transportFactory = transportFactory
        )
    } else {
        MdocProximityQrPresentmentAndroid(
            modifier = modifier,
            source = source,
            promptModel = promptModel,
            prepareSettings = prepareSettings,
            showQrCode = showQrCode,
            showTransacting = showTransacting,
            showCompleted = showCompleted,
            preselectedDocuments = preselectedDocuments,
            eDeviceKeyCurve = eDeviceKeyCurve,
            transportFactory = transportFactory
        )
    }
}

private const val TAG = "MdocProximityQrPresentment"

private enum class StateAndroid {
    PREPARE_SETTINGS,
    SHOW_QR_CODE,
    TRANSACTING,
    COMPLETED
}

@Composable
private fun MdocProximityQrPresentmentAndroid(
    modifier: Modifier,
    source: PresentmentSource,
    promptModel: PromptModel,
    prepareSettings: @Composable (generateQrCode: (settings: MdocProximityQrSettings) -> Unit) -> Unit,
    showQrCode: @Composable (uri: String, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document>,
    eDeviceKeyCurve: EcCurve,
    transportFactory: MdocTransportFactory
) {
    val coroutineScope = rememberCoroutineScope { PresentmentActivity.promptModel }
    var state by remember { mutableStateOf<StateAndroid>(StateAndroid.PREPARE_SETTINGS) }
    var qrCodeToShow by remember { mutableStateOf<String?>(null) }
    var transactionError by remember { mutableStateOf<Throwable?>(null) }
    var transactionJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (state) {
            StateAndroid.PREPARE_SETTINGS -> {
                prepareSettings({ qrSettings ->
                    var listenForCancellationFromUiJob: Job? = null
                    transactionJob = coroutineScope.launch {

                        transactionError = null

                        try {
                            val eDeviceKey = Crypto.createEcPrivateKey(eDeviceKeyCurve)
                            val advertisedTransports = qrSettings.availableConnectionMethods.advertise(
                                role = MdocRole.MDOC,
                                transportFactory = transportFactory,
                                options = qrSettings.createTransportOptions,
                            )
                            val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
                                advertisedTransports.forEach { addConnectionMethod(it.connectionMethod) }
                            }.toDataItem()
                            val encodedDeviceEngagement = ByteString(Cbor.encode(deviceEngagement))
                            qrCodeToShow = "mdoc:" + encodedDeviceEngagement.toByteArray().toBase64Url()

                            state = StateAndroid.SHOW_QR_CODE

                            val transport = advertisedTransports.waitForConnection(
                                eSenderKey = eDeviceKey.publicKey,
                            )

                            PresentmentActivity.presentmentModel.reset(
                                source = source,
                                preselectedDocuments = preselectedDocuments
                            )
                            val intent = Intent(applicationContext, PresentmentActivity::class.java)
                            intent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
                            applicationContext.startActivity(intent)

                            state = StateAndroid.TRANSACTING

                            listenForCancellationFromUiJob = CoroutineScope(Dispatchers.Main).launch {
                                PresentmentActivity.presentmentModel.state.collect { state ->
                                    if (state == PresentmentModel.State.CanceledByUser) {
                                        transactionJob?.cancel()
                                        listenForCancellationFromUiJob?.cancel()
                                        listenForCancellationFromUiJob = null
                                    }
                                }
                            }

                            Iso18013Presentment(
                                transport = transport,
                                eDeviceKey = eDeviceKey,
                                deviceEngagement = deviceEngagement,
                                handover = Simple.NULL,
                                source = source,
                                keyAgreementPossible = listOf(eDeviceKeyCurve),
                                onWaitingForRequest = { PresentmentActivity.presentmentModel.setWaitingForReader() },
                                onWaitingForUserInput = { PresentmentActivity.presentmentModel.setWaitingForUserInput() },
                                onDocumentsInFocus = { documents ->
                                    PresentmentActivity.presentmentModel.setDocumentsSelected(selectedDocuments = documents)
                                },
                                onSendingResponse = { PresentmentActivity.presentmentModel.setSending() }
                            )
                            PresentmentActivity.presentmentModel.setCompleted(null)
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                PresentmentActivity.presentmentModel.setCompleted(
                                    PresentmentCanceledException("Presentment was cancelled")
                                )
                            } else {
                                PresentmentActivity.presentmentModel.setCompleted(e)
                            }
                            transactionError = e
                        } finally {
                            state = StateAndroid.COMPLETED
                            transactionJob = null
                            listenForCancellationFromUiJob?.cancel()
                            listenForCancellationFromUiJob = null
                        }
                    }
                })
            }
            StateAndroid.SHOW_QR_CODE -> {
                qrCodeToShow?.let {
                    showQrCode(it, { transactionJob?.cancel() })
                }
            }
            StateAndroid.TRANSACTING -> {
                showTransacting({ transactionJob?.cancel() })
            }
            StateAndroid.COMPLETED -> {
                showCompleted(
                    transactionError,
                    { state = StateAndroid.PREPARE_SETTINGS }
                )
            }
        }
    }
}