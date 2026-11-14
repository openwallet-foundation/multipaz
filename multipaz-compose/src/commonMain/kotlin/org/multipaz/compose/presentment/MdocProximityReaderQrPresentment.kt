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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.presentment.Iso18013ReaderQrPresentment
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel

private enum class ReaderQrState {
    PREPARE_SETTINGS,
    SCAN_QR_CODE,
    TRANSACTING,
    COMPLETED,
}

/**
 * A composable for holder-side presentment using ISO/IEC 18013-5 Second Edition **Reader QR**
 * engagement (section 9.7).
 *
 * Some platforms might implement this using platform-specific mechanisms, for example on Android
 * it will use [org.multipaz.compose.prompt.PresentmentActivity]. This can be inhibited by passing
 * `true` to [disablePlatformSpecificImplementation].
 *
 * @param modifier a [Modifier].
 * @param source the source of truth for what is being presented.
 * @param promptModel a [PromptModel].
 * @param prepareSettings a composable to show UI to start scanning a Reader QR code, calling
 *   `scanQrCode` with [MdocProximityReaderQrSettings] when initiated.
 * @param scanQrCode a composable which scans a Reader QR code and passes candidate URIs to
 *   `onQrCodeScanned`, which returns `true` if the URI is an `mdoc:` Reader QR URI and presentment
 *   has begun, or `false` to continue scanning.
 * @param showTransacting a composable which will be shown while connecting and transacting with the reader.
 * @param showCompleted a composable which will be shown when the transaction is complete.
 * @param preselectedDocuments a list of documents the user may have preselected or the empty list.
 * @param capabilities the capabilities to convey to the mdoc reader in `DeviceEngagement`.
 * @param supportedCurves the ECDH curves supported for session encryption.
 * @param transportFactory the [MdocTransportFactory] to use for creating transports.
 * @param disablePlatformSpecificImplementation set to `true` to not use platform-specific implementations.
 */
@Composable
expect fun MdocProximityReaderQrPresentment(
    modifier: Modifier = Modifier,
    source: PresentmentSource,
    promptModel: PromptModel,
    prepareSettings: @Composable (scanQrCode: (settings: MdocProximityReaderQrSettings) -> Unit) -> Unit,
    scanQrCode: @Composable (onQrCodeScanned: (uri: String) -> Boolean, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document> = emptyList(),
    capabilities: Map<Capability, DataItem> = mapOf(
        Capability.READER_AUTH_ALL_SUPPORT to true.toDataItem(),
        Capability.EXTENDED_REQUEST_SUPPORT to true.toDataItem(),
    ),
    supportedCurves: List<EcCurve> = Crypto.supportedCurves.filter { it.supportsKeyAgreement },
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
    disablePlatformSpecificImplementation: Boolean = false,
)

internal fun isCandidateReaderQrUri(uri: String): Boolean =
    uri.startsWith("mdoc:") && !uri.startsWith("mdoc://") && uri.length > "mdoc:".length

@Composable
internal fun MdocProximityReaderQrPresentmentDefault(
    modifier: Modifier = Modifier,
    source: PresentmentSource,
    promptModel: PromptModel,
    prepareSettings: @Composable (scanQrCode: (settings: MdocProximityReaderQrSettings) -> Unit) -> Unit,
    scanQrCode: @Composable (onQrCodeScanned: (uri: String) -> Boolean, reset: () -> Unit) -> Unit,
    showTransacting: @Composable (reset: () -> Unit) -> Unit,
    showCompleted: @Composable (error: Throwable?, reset: () -> Unit) -> Unit,
    preselectedDocuments: List<Document> = emptyList(),
    capabilities: Map<Capability, DataItem> = mapOf(
        Capability.READER_AUTH_ALL_SUPPORT to true.toDataItem(),
        Capability.EXTENDED_REQUEST_SUPPORT to true.toDataItem(),
    ),
    supportedCurves: List<EcCurve> = Crypto.supportedCurves.filter { it.supportsKeyAgreement },
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    var state by remember { mutableStateOf(ReaderQrState.PREPARE_SETTINGS) }
    var activeSettings by remember { mutableStateOf(MdocProximityReaderQrSettings()) }
    var transactionError by remember { mutableStateOf<Throwable?>(null) }
    var transactionJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            ReaderQrState.PREPARE_SETTINGS -> {
                prepareSettings { readerQrSettings ->
                    activeSettings = readerQrSettings
                    transactionError = null
                    state = ReaderQrState.SCAN_QR_CODE
                }
            }
            ReaderQrState.SCAN_QR_CODE -> {
                scanQrCode(
                    { uri ->
                        if (transactionJob != null || !isCandidateReaderQrUri(uri)) {
                            false
                        } else {
                            val settingsSnapshot = activeSettings
                            state = ReaderQrState.TRANSACTING
                            transactionJob = coroutineScope.launch {
                                transactionError = null
                                try {
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
                                    )
                                } catch (e: Exception) {
                                    transactionError = e
                                    if (e is CancellationException) throw e
                                } finally {
                                    state = ReaderQrState.COMPLETED
                                    transactionJob = null
                                }
                            }
                            true
                        }
                    },
                    {
                        transactionJob?.cancel()
                        transactionJob = null
                        state = ReaderQrState.PREPARE_SETTINGS
                    }
                )
            }
            ReaderQrState.TRANSACTING -> {
                showTransacting {
                    transactionJob?.cancel()
                }
            }
            ReaderQrState.COMPLETED -> {
                showCompleted(
                    transactionError,
                    { state = ReaderQrState.PREPARE_SETTINGS }
                )
            }
        }
    }
}
