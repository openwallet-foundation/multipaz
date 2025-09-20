package org.multipaz.compose.presentment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import coil3.ImageLoader
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.models.presentment.MdocPresentmentMechanism
import org.multipaz.models.presentment.PresentmentModel
import org.multipaz.models.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.util.toBase64Url

/**
 * A composable for presentment with QR engagement according to ISO/IEC 18013-5:2021.
 *
 * This composable shows three different things, depending on state of [presentmentModel]:
 * - in the [PresentmentModel.State.IDLE] state the composable returned by the [showQrButton] parameter is shown.
 *   Once the user clicks the "show QR code" button (e.g. the `onQrButtonClicked` callback is called) the presentment
 *   process starts and [presentmentModel] transitions to [PresentmentModel.State.CONNECTING].
 * - in the [PresentmentModel.State.CONNECTING] state the the composable [showQrCode] is shown which is
 *   renders the QR code. When a reader scans the QR code and connects the presentment process moves on to the
 *   next step and [presentmentModel] transitions to [PresentmentModel.State.WAITING_FOR_SOURCE] and eventually
 *   other states.
 * - in the other states, the [Presentment] composable is shown until the reader disconnect. This
 *   includes showing consent and authentication dialogs. When the reader disconnects [presentmentModel]
 *   goes to state [PresentmentModel.State.IDLE] and [showQrButton] composable is shown.
 *
 * @param modifier a [Modifier].
 * @param appName the name of the application.
 * @param appIconPainter the icon for the application.
 * @param presentmentModel the [PresentmentModel] to use which must have a [PromptModel] associated with it.
 * @param presentmentSource an object for application to provide data and policy.
 * @param promptModel a [PromptModel]
 * @param documentTypeRepository a [DocumentTypeRepository] used to find metadata about documents being requested.
 * @param imageLoader an [ImageLoader] for loading images from the network.
 * @param allowMultipleRequests if true, multiple requests in a single session will be allowed.
 * @param showQrButton a composable to show for a button to generate a QR code. It should call [onQrButtonClicked]
 *   when the user presses the button and pass a [MdocProximityQrSettings] which contains the settings for what
 *   kind of [org.multipaz.mdoc.transport.MdocTransport] instances to advertise and what options to use when
 *   creating the transports.
 * @param showQrCode a composable which shows the QR code and asks the user to scan it.
 * @param transportFactory the [MdocTransportFactory] to use for creating a transport.
 */
@Composable
fun MdocProximityQrPresentment(
    modifier: Modifier = Modifier,
    appName: String,
    appIconPainter: Painter,
    presentmentModel: PresentmentModel,
    presentmentSource: PresentmentSource,
    promptModel: PromptModel,
    documentTypeRepository: DocumentTypeRepository,
    imageLoader: ImageLoader,
    allowMultipleRequests: Boolean,
    showQrButton: @Composable (onQrButtonClicked: (settings: MdocProximityQrSettings) -> Unit) -> Unit,
    showQrCode: @Composable (uri: String) -> Unit,
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val state = presentmentModel.state.collectAsState()
    var qrCodeToShow = remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (state.value) {
            PresentmentModel.State.IDLE -> {
                showQrButton({ qrSettings ->
                    presentmentModel.reset()
                    presentmentModel.setConnecting()
                    coroutineScope.launch {
                        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
                        val advertisedTransports = qrSettings.availableConnectionMethods.advertise(
                            role = MdocRole.MDOC,
                            transportFactory = transportFactory,
                            options = MdocTransportOptions(bleUseL2CAP = true),
                        )
                        val encodedDeviceEngagement = ByteString(Cbor.encode(
                            buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
                                advertisedTransports.map { addConnectionMethod(it.connectionMethod) }
                            }.toDataItem()
                        ))
                        qrCodeToShow.value = "mdoc:" + encodedDeviceEngagement.toByteArray().toBase64Url()

                        val transport = advertisedTransports.waitForConnection(
                            eSenderKey = eDeviceKey.publicKey,
                            coroutineScope = coroutineScope
                        )
                        presentmentModel.setMechanism(
                            MdocPresentmentMechanism(
                                transport = transport,
                                eDeviceKey = eDeviceKey,
                                encodedDeviceEngagement = encodedDeviceEngagement,
                                handover = Simple.NULL,
                                engagementDuration = null,
                                allowMultipleRequests = allowMultipleRequests
                            )
                        )
                        qrCodeToShow.value = null
                    }
                })
            }

            PresentmentModel.State.CONNECTING -> {
                qrCodeToShow.value?.let {
                    showQrCode(it)
                }
            }

            PresentmentModel.State.WAITING_FOR_SOURCE,
            PresentmentModel.State.PROCESSING,
            PresentmentModel.State.WAITING_FOR_CONSENT,
            PresentmentModel.State.COMPLETED -> {
                Presentment(
                    appName = appName,
                    appIconPainter = appIconPainter,
                    presentmentModel = presentmentModel,
                    presentmentSource = presentmentSource,
                    documentTypeRepository = documentTypeRepository,
                    onPresentmentComplete = {
                        presentmentModel.reset()
                    },
                    imageLoader = imageLoader,
                    onlyShowConsentPrompt = false,
                    showCancelAsBack = false,
                )
            }
        }
    }
}