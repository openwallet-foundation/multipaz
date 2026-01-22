package org.multipaz.compose.presentment

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.presentment.model.Iso18013Presentment
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.util.toBase64Url

private const val TAG = "MdocProximityQrPresentment"

private enum class State {
    PREPARE_SETTINGS,
    SHOW_QR_CODE,
    TRANSACTING,
    COMPLETED
}

/**
 * A composable for presentment with QR engagement according to ISO/IEC 18013-5:2021.
 *
 * Some platforms might implement this using platform-specific mechanism, for example on Android
 * it will use [PresentmentActivity]. This can be inhibited by passing `true` to
 * [disablePlatformSpecificImplementation].
 *
 * @param modifier a [Modifier].
 * @param source the source of truth for what is being presented.
 * @param promptModel a [PromptModel].
 * @param prepareSettings a composable to show an UI to start sharing, for example a button the user
 *   can press that says "Present mDL with QR code", if applicable. This should call [generateQrCode]
 *   when e.g. the user presses the button and pass a [MdocProximityQrSettings] which contains the settings
 *   for what kind of [org.multipaz.mdoc.transport.MdocTransport] instances to advertise and what options to
 *   use when creating the transports.
 * @param showQrCode a composable which shows the QR code and asks the user to scan it.
 * @param showTransacting a composable which will be shown when transacting with a remote reader.
 * @param showCompleted a composable which will be shown when the transaction is complete. This should should
 *   feedback (either success or error, depending on the error parameter) and call the passed-in reset() lambda
 *   when ready to reset the state and go back and show a QR button.
 * @param preselectedDocuments a list of documents the user may have preselected or the empty list.
 * @param eDeviceKeyCurve the curve to use for session encryption.
 * @param transportFactory the [MdocTransportFactory] to use for creating transports.
 * @param disablePlatformSpecificImplementation set to `true` to not use platform-specific implementations.
 */
@Composable
expect fun MdocProximityQrPresentment(
    modifier: Modifier = Modifier,
    source: PresentmentSource,
    promptModel: PromptModel,
    prepareSettings: @Composable (generateQrCode: (settings: MdocProximityQrSettings) -> Unit) -> Unit,
    showQrCode: @Composable (uri: String, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document> = emptyList(),
    eDeviceKeyCurve: EcCurve = EcCurve.P256,
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
    disablePlatformSpecificImplementation: Boolean = false
)

@Composable
internal fun MdocProximityQrPresentmentDefault(
    modifier: Modifier = Modifier,
    source: PresentmentSource,
    promptModel: PromptModel,
    prepareSettings: @Composable (generateQrCode: (settings: MdocProximityQrSettings) -> Unit) -> Unit,
    showQrCode: @Composable (uri: String, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document> = emptyList(),
    eDeviceKeyCurve: EcCurve = EcCurve.P256,
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    var state by remember { mutableStateOf<State>(State.PREPARE_SETTINGS) }
    var qrCodeToShow by remember { mutableStateOf<String?>(null) }
    var transactionError by remember { mutableStateOf<Throwable?>(null) }
    var transactionJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (state) {
            State.PREPARE_SETTINGS -> {
                prepareSettings({ qrSettings ->
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

                            state = State.SHOW_QR_CODE

                            val transport = advertisedTransports.waitForConnection(
                                eSenderKey = eDeviceKey.publicKey,
                            )

                            state = State.TRANSACTING

                            Iso18013Presentment(
                                transport = transport,
                                eDeviceKey = eDeviceKey,
                                deviceEngagement = deviceEngagement,
                                handover = Simple.NULL,
                                source = source,
                                keyAgreementPossible = listOf(eDeviceKeyCurve),
                            )

                        } catch (e: Throwable) {
                            transactionError = e
                        } finally {
                            state = State.COMPLETED
                            transactionJob = null
                        }
                    }
                })
            }
            State.SHOW_QR_CODE -> {
                qrCodeToShow?.let {
                    showQrCode(it, { transactionJob?.cancel() })
                }
            }
            State.TRANSACTING -> {
                showTransacting({ transactionJob?.cancel() })
            }
            State.COMPLETED -> {
                showCompleted(
                    transactionError,
                    { state = State.PREPARE_SETTINGS }
                )
            }
        }
    }
}
