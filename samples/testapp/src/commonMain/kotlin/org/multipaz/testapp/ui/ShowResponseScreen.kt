package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.multipaz.compose.scrollbar.ColumnWithScrollbar
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
    ColumnWithScrollbar(
        contentPadding = PaddingValues(8.dp)
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
