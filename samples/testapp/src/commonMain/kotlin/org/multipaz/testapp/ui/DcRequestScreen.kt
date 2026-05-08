package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.multipaz.cbor.DataItem
import org.multipaz.compose.rememberUiBoundCoroutineScope
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.DocumentCannedRequest
import org.multipaz.documenttype.MultiDocumentCannedRequest
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.testapp.App
import org.multipaz.testapp.TestAppUtils
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.testapp.ShowResponseMetadata
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.utopia.knowntypes.wellKnownMultipleDocumentRequests
import org.multipaz.verification.DcqlRequestDefinition
import org.multipaz.verification.VerificationSession
import kotlin.random.Random
import kotlin.time.Clock

private const val TAG = "AppToAppReadingScreen"

private data class RequestEntry(
    val displayName: String,
    val request: DocumentCannedRequest
)

private enum class RequestProtocol(
    val displayName: String,
    val requestTypes: List<VerificationSession.RequestType>,
    val signRequest: Boolean,
) {
    W3C_DC_OPENID4VP_29(
        displayName = "OpenID4VP 1.0",
        requestTypes = listOf(VerificationSession.RequestType.DC_OPENID4VP),
        signRequest = true,
    ),
    W3C_DC_OPENID4VP_29_UNSIGNED(
        displayName = "OpenID4VP 1.0 (Unsigned)",
        requestTypes = listOf(VerificationSession.RequestType.DC_OPENID4VP),
        signRequest = false,
    ),
    W3C_DC_OPENID4VP_24(
        displayName = "OpenID4VP Draft 24",
        requestTypes = listOf(VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24),
        signRequest = true,
    ),
    W3C_DC_OPENID4VP_24_UNSIGNED(
        displayName = "OpenID4VP Draft 24 (Unsigned)",
        requestTypes = listOf(VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24),
        signRequest = false,
    ),
    W3C_DC_MDOC_API(
        displayName = "ISO 18013-7 Annex C",
        requestTypes = listOf(VerificationSession.RequestType.DC_ISO_18013),
        signRequest = true
    ),
    W3C_DC_MDOC_API_UNSIGNED(
        displayName = "ISO 18013-7 Annex C (Unsigned)",
        requestTypes = listOf(VerificationSession.RequestType.DC_ISO_18013),
        signRequest = false
    ),
    W3C_DC_MDOC_API_AND_OPENID4VP_29(
        displayName = "ISO 18013-7 Annex C + OpenID4VP 1.0",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_ISO_18013,
            VerificationSession.RequestType.DC_OPENID4VP
        ),
        signRequest = true
    ),
    W3C_DC_MDOC_API_AND_OPENID4VP_29_UNSIGNED(
        displayName = "ISO 18013-7 Annex C + OpenID4VP 1.0 (Unsigned)",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_ISO_18013,
            VerificationSession.RequestType.DC_OPENID4VP
        ),
        signRequest = false
    ),
    W3C_DC_MDOC_API_AND_OPENID4VP_24(
        displayName = "ISO 18013-7 Annex C + OpenID4VP Draft 24",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_ISO_18013,
            VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24
        ),
        signRequest = true
    ),
    W3C_DC_MDOC_API_AND_OPENID4VP_24_UNSIGNED(
        displayName = "ISO 18013-7 Annex C + OpenID4VP Draft 24 (Unsigned)",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_ISO_18013,
            VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24
        ),
        signRequest = false
    ),
    OPENID4VP_29_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP 1.0 + ISO 18013-7 Annex C",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_OPENID4VP,
            VerificationSession.RequestType.DC_ISO_18013
        ),
        signRequest = true
    ),
    OPENID4VP_29_UNSIGNED_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP 1.0 + ISO 18013-7 Annex C (Unsigned)",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_OPENID4VP,
            VerificationSession.RequestType.DC_ISO_18013
        ),
        signRequest = false
    ),
    OPENID4VP_24_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP Draft 24 + ISO 18013-7 Annex C",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24,
            VerificationSession.RequestType.DC_ISO_18013,
        ),
        signRequest = true
    ),
    OPENID4VP_24_UNSIGNED_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP Draft 24 + ISO 18013-7 Annex C (Unsigned)",
        requestTypes = listOf(
            VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24,
            VerificationSession.RequestType.DC_ISO_18013,
        ),
        signRequest = false
    ),
}

private enum class CredentialFormat(
    val displayName: String,
) {
    ISO_MDOC("ISO mdoc"),
    IETF_SDJWT("IETF SD-JWT"),
}

private var lastRequest: Int = 0
private var lastProtocol: Int = 0
private var lastFormat: Int = 0

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun DcRequestScreen(
    app: App,
    showToast: (message: String) -> Unit,
    showResponse: (
        vpToken: JsonObject?,
        deviceResponse: DataItem?,
        session: VerificationSession,
        eReaderKey: EcPrivateKey?,
        metadata: ShowResponseMetadata
    ) -> Unit
) {
    val requestOptions = mutableListOf<RequestEntry>()
    for (documentType in TestAppUtils.provisionedDocumentTypes) {
        for (sampleRequest in documentType.cannedRequests) {
            requestOptions.add(RequestEntry(
                displayName = "${documentType.displayName}: ${sampleRequest.displayName}",
                request = sampleRequest
            ))
        }
    }
    for (request in app.documentTypeRepository.extraSingleDocumentCannedRequests) {
        requestOptions.add(RequestEntry(
            displayName = request.displayName,
            request = request
        ))
    }
    for (request in wellKnownMultipleDocumentRequests) {
        requestOptions.add(RequestEntry(
            displayName = "Multi-doc: ${request.displayName}",
            request = request
        ))
    }
    val requestDropdownExpanded = remember { mutableStateOf(false) }
    val requestSelected = remember { mutableStateOf(requestOptions[lastRequest]) }
    val protocolOptions = RequestProtocol.entries
    val protocolDropdownExpanded = remember { mutableStateOf(false) }
    val protocolSelected = remember { mutableStateOf(protocolOptions[lastProtocol]) }
    val formatOptions = CredentialFormat.entries
    val formatDropdownExpanded = remember { mutableStateOf(false) }
    val formatSelected = remember { mutableStateOf(formatOptions[lastFormat]) }
    val coroutineScope = rememberUiBoundCoroutineScope { app.promptModel }

    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            ComboBox(
                headline = "Claims to request",
                options = requestOptions,
                comboBoxSelected = requestSelected,
                comboBoxExpanded = requestDropdownExpanded,
                getDisplayName = { it.displayName },
                onSelected = { index, value -> lastRequest = index }
            )
        }
        item {
            ComboBox(
                headline = "W3C Digital Credentials Protocol(s)",
                options = protocolOptions,
                comboBoxSelected = protocolSelected,
                comboBoxExpanded = protocolDropdownExpanded,
                getDisplayName = { it.displayName },
                onSelected = { index, value -> lastProtocol = index }
            )
        }
        item {
            ComboBox(
                headline = "Credential Format",
                options = formatOptions,
                comboBoxSelected = formatSelected,
                comboBoxExpanded = formatDropdownExpanded,
                getDisplayName = { it.displayName },
                onSelected = { index, value -> lastFormat = index }
            )
        }
        item {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        try {
                            doDcRequestFlow(
                                app = app,
                                request = requestSelected.value.request,
                                protocol = protocolSelected.value,
                                format = formatSelected.value,
                                showResponse = showResponse
                            )
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Logger.e(TAG, "Error requesting credentials", error)
                            showToast("Error: ${error.message}")
                        }
                    }
                },
                content = { Text("Request via OS CredentialManager API") }
            )
        }
    }
}

private suspend fun doDcRequestFlow(
    app: App,
    request: DocumentCannedRequest,
    protocol: RequestProtocol,
    format: CredentialFormat,
    showResponse: (
        vpToken: JsonObject?,
        deviceResponse: DataItem?,
        session: VerificationSession,
        eReaderKey: EcPrivateKey?,
        metadata: ShowResponseMetadata
    ) -> Unit
) {
    if (request is SingleDocumentCannedRequest) {
        when (format) {
            CredentialFormat.ISO_MDOC -> {
                require(request.mdocRequest != null) { "No ISO mdoc format in request" }
            }

            CredentialFormat.IETF_SDJWT -> {
                require(request.jsonRequest != null) { "No IETF SD-JWT format in request" }
            }
        }
    }

    val nonce = ByteString(Random.nextBytes(16))
    val origin = TestAppConfiguration.getAppToAppOrigin()
    // According to OpenID4VP, Client ID must be set for signed requests and not for unsigned requests
    val clientId = if (protocol.signRequest) {
        val cert = app.readerKey.certChain.certificates.getOrNull(0)
            ?: throw IllegalArgumentException("Certificate chain is missing or empty")
        val certHash = Crypto.digest(Algorithm.SHA256, cert.encoded.toByteArray()).toBase64Url()
        "x509_hash:$certHash"
    } else {
        null
    }

    val requestDefinition = when (request) {
        is SingleDocumentCannedRequest -> {
            when (format) {
                CredentialFormat.ISO_MDOC -> DcqlRequestDefinition(
                    dcql = request.mdocRequest!!
                        .toDcql(app.zkSystemRepository.getAllZkSystemSpecs()).toString(),
                    // VerificationUtils.calcDcqlMdoc currently always uses "calc1"
                    transactionData = request.toJsonTransactionData("cred1")
                )

                CredentialFormat.IETF_SDJWT -> DcqlRequestDefinition(
                    dcql = request.jsonRequest!!.toDcql().toString(),
                    transactionData = request.toJsonTransactionData("cred1")
                )
            }
        }
        is MultiDocumentCannedRequest -> {
            val transactions = request.transactionData?.let { data ->
                Json.parseToJsonElement(data).jsonArray
            }
            DcqlRequestDefinition(
                dcql = request.dcqlString,
                transactionData = transactions?.map { it.toString() } ?: emptyList(),
            )
        }
    }

    val session = VerificationSession.create(
        requestTypes = protocol.requestTypes,
        requestDefinition = requestDefinition,
        nonce = nonce,
        origin = origin,
        clientId = clientId,
        readerAuthenticationKey = if (protocol.signRequest) {
            app.readerKey
        } else {
            null
        },
        documentTypeRepository = app.documentTypeRepository,
    )

    val dcRequestObject = session.getDcRequest()

    Logger.i(TAG, "clientId: $clientId")
    Logger.i(TAG, "origin: $origin")
    Logger.iJson(TAG, "Request", dcRequestObject)
    val t0 = Clock.System.now()
    val dcResponseObject = app.digitalCredentials.request(dcRequestObject)
    Logger.iJson(TAG, "Response", dcResponseObject)

    val metadata = ShowResponseMetadata(
        engagementType = "OS-provided CredentialManager API",
        transferProtocol = "W3C Digital Credentials (${protocol.displayName})",
        requestSize = Json.encodeToString(dcRequestObject).length.toLong(),
        responseSize = Json.encodeToString(dcResponseObject).length.toLong(),
        durationMsecNfcTapToEngagement = null,
        durationMsecEngagementReceivedToRequestSent = null,
        durationMsecRequestSentToResponseReceived = (Clock.System.now() - t0).inWholeMilliseconds,
        nfcHybridTransportStats = null
    )

    showResponse(
        /* vpToken = */ dcResponseObject,
        /* deviceResponse = */ null,
        /* session = */ session,
        /* eReaderKey = */ null,
        /* metadata = */ metadata
    )
}
