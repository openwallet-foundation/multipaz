package org.multipaz.compose.presentment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel

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
) = MdocProximityReaderQrPresentmentDefault(
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
