package org.multipaz.testapp.ui

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.DataItem
import org.multipaz.compose.rememberUiBoundCoroutineScope
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.digitalcredentials.Default
import org.multipaz.documenttype.DocumentCannedRequest
import org.multipaz.documenttype.DocumentType
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.verification.MdocApiDcResponse
import org.multipaz.verification.OpenID4VPDcResponse
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.testapp.App
import org.multipaz.testapp.TestAppUtils
import org.multipaz.testapp.getAppToAppOrigin
import org.multipaz.util.Logger
import org.multipaz.verification.VerificationUtil
import org.multipaz.testapp.ShowResponseMetadata
import kotlin.random.Random
import kotlin.time.Clock

private const val TAG = "AppToAppReadingScreen"

private data class RequestEntry(
    val displayName: String,
    val documentType: DocumentType,
    val sampleRequest: DocumentCannedRequest
)

private enum class RequestProtocol(
    val displayName: String,
    val exchangeProtocolNames: List<String>,
    val signRequest: Boolean,
) {
    W3C_DC_OPENID4VP_29(
        displayName = "OpenID4VP 1.0",
        exchangeProtocolNames = listOf("openid4vp-v1-signed"),
        signRequest = true,
    ),
    W3C_DC_OPENID4VP_29_UNSIGNED(
        displayName = "OpenID4VP 1.0 (Unsigned)",
        exchangeProtocolNames = listOf("openid4vp-v1-unsigned"),
        signRequest = false,
    ),
    W3C_DC_OPENID4VP_24(
        displayName = "OpenID4VP Draft 24",
        exchangeProtocolNames = listOf("openid4vp"),
        signRequest = true,
    ),
    W3C_DC_OPENID4VP_24_UNSIGNED(
        displayName = "OpenID4VP Draft 24 (Unsigned)",
        exchangeProtocolNames = listOf("openid4vp"),
        signRequest = false,
    ),
    W3C_DC_MDOC_API(
        displayName = "ISO 18013-7 Annex C",
        exchangeProtocolNames = listOf("org-iso-mdoc"),
        signRequest = true
    ),
    W3C_DC_MDOC_API_UNSIGNED(
        displayName = "ISO 18013-7 Annex C (Unsigned)",
        exchangeProtocolNames = listOf("org-iso-mdoc"),
        signRequest = false
    ),

    W3C_DC_MDOC_API_AND_OPENID4VP_29(
        displayName = "ISO 18013-7 Annex C + OpenID4VP 1.0",
        exchangeProtocolNames = listOf("org-iso-mdoc", "openid4vp-v1-signed"),
        signRequest = true
    ),
    W3C_DC_MDOC_API_AND_OPENID4VP_29_UNSIGNED(
        displayName = "ISO 18013-7 Annex C + OpenID4VP 1.0 (Unsigned)",
        exchangeProtocolNames = listOf("org-iso-mdoc", "openid4vp-v1-unsigned"),
        signRequest = false
    ),
    W3C_DC_MDOC_API_AND_OPENID4VP_24(
        displayName = "ISO 18013-7 Annex C + OpenID4VP Draft 24",
        exchangeProtocolNames = listOf("org-iso-mdoc", "openid4vp"),
        signRequest = true
    ),
    W3C_DC_MDOC_API_AND_OPENID4VP_24_UNSIGNED(
        displayName = "ISO 18013-7 Annex C + OpenID4VP Draft 24 (Unsigned)",
        exchangeProtocolNames = listOf("org-iso-mdoc", "openid4vp"),
        signRequest = false
    ),

    OPENID4VP_29_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP 1.0 + ISO 18013-7 Annex C",
        exchangeProtocolNames = listOf("openid4vp-v1-signed", "org-iso-mdoc"),
        signRequest = true
    ),
    OPENID4VP_29_UNSIGNED_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP 1.0 + ISO 18013-7 Annex C (Unsigned)",
        exchangeProtocolNames = listOf("openid4vp-v1-unsigned", "org-iso-mdoc"),
        signRequest = false
    ),
    OPENID4VP_24_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP Draft 24 + ISO 18013-7 Annex C",
        exchangeProtocolNames = listOf("openid4vp", "org-iso-mdoc"),
        signRequest = true
    ),
    OPENID4VP_24_UNSIGNED_AND_W3C_DC_MDOC_API(
        displayName = "OpenID4VP Draft 24 + ISO 18013-7 Annex C (Unsigned)",
        exchangeProtocolNames = listOf("openid4vp", "org-iso-mdoc"),
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
        sessionTranscript: DataItem,
        nonce: ByteString?,
        eReaderKey: EcPrivateKey?,
        metadata: ShowResponseMetadata
    ) -> Unit
) {
    val requestOptions = mutableListOf<RequestEntry>()
    for (documentType in TestAppUtils.provisionedDocumentTypes) {
        for (sampleRequest in documentType.cannedRequests) {
            requestOptions.add(RequestEntry(
                displayName = "${documentType.displayName}: ${sampleRequest.displayName}",
                documentType = documentType,
                sampleRequest = sampleRequest
            ))
        }
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
                availableRequests = requestOptions,
                comboBoxSelected = requestSelected,
                comboBoxExpanded = requestDropdownExpanded,
                getDisplayName = { it.displayName },
                onSelected = { index, value -> lastRequest = index }
            )
        }
        item {
            ComboBox(
                headline = "W3C Digital Credentials Protocol(s)",
                availableRequests = protocolOptions,
                comboBoxSelected = protocolSelected,
                comboBoxExpanded = protocolDropdownExpanded,
                getDisplayName = { it.displayName },
                onSelected = { index, value -> lastProtocol = index }
            )
        }
        item {
            ComboBox(
                headline = "Credential Format",
                availableRequests = formatOptions,
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
                                appReaderKey = app.readerKey,
                                request = requestSelected.value.sampleRequest,
                                protocol = protocolSelected.value,
                                format = formatSelected.value,
                                zkSystemRepository = app.zkSystemRepository,
                                showResponse = showResponse
                            )
                        } catch (error: Throwable) {
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
    appReaderKey: AsymmetricKey.X509Compatible,
    request: DocumentCannedRequest,
    protocol: RequestProtocol,
    format: CredentialFormat,
    zkSystemRepository: ZkSystemRepository,
    showResponse: (
        vpToken: JsonObject?,
        deviceResponse: DataItem?,
        sessionTranscript: DataItem,
        nonce: ByteString?,
        eReaderKey: EcPrivateKey?,
        metadata: ShowResponseMetadata
    ) -> Unit
) {
    when (format) {
        CredentialFormat.ISO_MDOC -> {
            require(request.mdocRequest != null) { "No ISO mdoc format in request" }
        }

        CredentialFormat.IETF_SDJWT -> {
            require(request.jsonRequest != null) { "No IETF SD-JWT format in request" }
        }
    }

    val nonce = ByteString(Random.Default.nextBytes(16))
    val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
    val origin = getAppToAppOrigin()
    // According to OpenID4VP, Client ID must be set for signed requests and not for unsigned requests
    val clientId = "web-origin:$origin"

    val dcRequestObject = when (format) {
        CredentialFormat.ISO_MDOC -> {
            val claims = mutableListOf<MdocRequestedClaim>()
            request.mdocRequest!!.namespacesToRequest.forEach { namespaceRequest ->
                namespaceRequest.dataElementsToRequest.forEach { (mdocDataElement, intentToRetain) ->
                    claims.add(
                        MdocRequestedClaim(
                            namespaceName = namespaceRequest.namespace,
                            dataElementName = mdocDataElement.attribute.identifier,
                            intentToRetain = intentToRetain
                        )
                    )
                }
            }
            VerificationUtil.generateDcRequestMdoc(
                exchangeProtocols = protocol.exchangeProtocolNames,
                docType = request.mdocRequest!!.docType,
                claims = claims,
                nonce = nonce,
                origin = origin,
                clientId = clientId,
                responseEncryptionKey = responseEncryptionKey.publicKey,
                readerAuthenticationKey = if (protocol.signRequest) {
                    appReaderKey
                } else {
                    null
                },
                zkSystemSpecs = if (request.mdocRequest!!.useZkp) {
                    zkSystemRepository.getAllZkSystemSpecs()
                } else {
                    emptyList()
                }
            )
        }

        CredentialFormat.IETF_SDJWT -> {
            val claims = request.jsonRequest!!.claimsToRequest.map { documentAttribute ->
                val path = mutableListOf<JsonElement>()
                documentAttribute.parentAttribute?.let {
                    path.add(JsonPrimitive(it.identifier))
                }
                path.add(JsonPrimitive(documentAttribute.identifier))
                JsonRequestedClaim(
                    claimPath = JsonArray(path),
                )
            }
            VerificationUtil.generateDcRequestSdJwt(
                exchangeProtocols = protocol.exchangeProtocolNames,
                vct = listOf(request.jsonRequest!!.vct),
                claims = claims,
                nonce = nonce,
                origin = origin,
                clientId = clientId,
                responseEncryptionKey = responseEncryptionKey.publicKey,
                readerAuthenticationKey = appReaderKey,
            )
        }
    }

    Logger.i(TAG, "clientId: $clientId")
    Logger.i(TAG, "origin: $origin")
    Logger.iJson(TAG, "Request", dcRequestObject)
    val t0 = Clock.System.now()
    val dcResponseObject = DigitalCredentials.Default.request(dcRequestObject)
    Logger.iJson(TAG, "Response", dcResponseObject)

    val dcResponse = VerificationUtil.decryptDcResponse(
        response = dcResponseObject,
        nonce = nonce,
        origin = origin,
        responseEncryptionKey = AsymmetricKey.anonymous(
            privateKey = responseEncryptionKey,
            algorithm = responseEncryptionKey.curve.defaultKeyAgreementAlgorithm
        )
    )
    val metadata = ShowResponseMetadata(
        engagementType = "OS-provided CredentialManager API",
        transferProtocol = "W3C Digital Credentials (${protocol.displayName})",
        requestSize = Json.encodeToString(dcRequestObject).length.toLong(),
        responseSize = Json.encodeToString(dcResponseObject).length.toLong(),
        durationMsecNfcTapToEngagement = null,
        durationMsecEngagementReceivedToRequestSent = null,
        durationMsecRequestSentToResponseReceived = (Clock.System.now() - t0).inWholeMilliseconds
    )
    when (dcResponse) {
        is MdocApiDcResponse -> {
            Logger.iCbor(TAG, "deviceResponse", dcResponse.deviceResponse)
            Logger.iCbor(TAG, "sessionTranscript", dcResponse.sessionTranscript)
            showResponse(
                /* vpToken = */ null,
                /* deviceResponse = */ dcResponse.deviceResponse,
                /* sessionTranscript = */ dcResponse.sessionTranscript,
                /* nonce = */ nonce,
                /* eReaderKey = */ null,
                /* metadata = */ metadata
            )
        }
        is OpenID4VPDcResponse -> {
            Logger.iJson(TAG, "vpToken", dcResponse.vpToken)
            Logger.iCbor(TAG, "sessionTranscript", dcResponse.sessionTranscript)
            showResponse(
                /* vpToken = */ dcResponse.vpToken,
                /* deviceResponse = */ null,
                /* sessionTranscript = */ dcResponse.sessionTranscript,
                /* nonce = */ nonce,
                /* eReaderKey = */ null,
                /* metadata = */ metadata
            )
        }
    }
}
