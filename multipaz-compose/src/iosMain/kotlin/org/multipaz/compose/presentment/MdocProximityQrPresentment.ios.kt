package org.multipaz.compose.presentment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.PromptModel

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
) = MdocProximityQrPresentmentDefault(
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