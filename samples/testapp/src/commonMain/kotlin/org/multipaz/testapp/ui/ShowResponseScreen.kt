package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.testapp.ShowResponseMetadata
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.verification.VerificationSession

private const val TAG = "ShowResponseScreen"

@Composable
fun ShowResponseScreen(
    vpToken: JsonObject?,
    deviceResponse: DataItem?,
    session: VerificationSession,
    eReaderKey: EcPrivateKey?,
    metadata: ShowResponseMetadata,
    issuerTrustManager: TrustManagerInterface,
    documentTypeRepository: DocumentTypeRepository?,
    zkSystemRepository: ZkSystemRepository?,
    onViewCertChain: (certChain: X509CertChain) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth().padding(8.dp)
            .verticalScroll(scrollState)
    ) {
        ShowResponse(
            vpToken = vpToken,
            deviceResponse = deviceResponse,
            session = session,
            eReaderKey = eReaderKey,
            metadata = metadata,
            issuerTrustManager = issuerTrustManager,
            documentTypeRepository = documentTypeRepository,
            zkSystemRepository = zkSystemRepository,
            onViewCertChain = onViewCertChain
        )
    }
}
