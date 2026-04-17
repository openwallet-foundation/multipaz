package org.multipaz.server.presentment

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.presentment.TransactionDataJson
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.time.Instant

/**
 * [PresentmentRecord] for OpenID4VP presentations.
 *
 * Supports both `mso_mdoc` and `dc+sd-jwt` credential formats within a single VP token.
 *
 * @property nonce nonce that was included in the authorization request.
 * @property formats map from credential ID to its format string (`mso_mdoc` or `dc+sd-jwt`).
 * @property vpToken JSON-encoded VP token containing the credential responses, structured as
 *     a JSON object mapping credential IDs to arrays of credential response strings.
 * @property jsonTransactionData optional list of JSON-encoded transaction data entries.
 * @property mdocSessionTranscript CBOR `SessionTranscript` used for mdoc device authentication;
 *     required when any credential uses `mso_mdoc` format.
 */
class PresentmentRecordOpenID4VP(
    val nonce: String,
    val formats: Map<String, String>,
    val vpToken: String,
    val jsonTransactionData: List<String>?,
    val mdocSessionTranscript: DataItem?,
): PresentmentRecord() {
    override suspend fun verifyNonce(nonce: ByteString) {
        if (nonce.toByteArray().toBase64Url() != this.nonce) {
            throw InvalidRequestException("Nonce mismatch")
        }
    }

    override suspend fun verify(atTime: Instant): List<PresentmentResult> {
        val documentTypeRepository =
            BackendEnvironment.getInterface(DocumentTypeRepository::class)!!
        val trustManager = BackendEnvironment.getInterface(TrustManagerInterface::class)!!
        val transactionMap = getTransactionData(documentTypeRepository)
        return Json.parseToJsonElement(vpToken).jsonObject.flatMap { (id, value ) ->
            value.jsonArray.map { credentialResponse ->
                val responseText = credentialResponse.jsonPrimitive.content
                val transactionData = transactionMap[id] ?: listOf()
                when (formats[id]) {
                    "mso_mdoc" -> {
                        val decoded = Cbor.decode(responseText.fromBase64Url())
                        val deviceResponse = DeviceResponse.fromDataItem(decoded)
                        val transactionResponses = deviceResponse.verify(
                            sessionTranscript = mdocSessionTranscript!!,
                            eReaderKey = null,
                            transactionDataList = listOf(transactionData),
                            atTime = atTime
                        )
                        check(deviceResponse.documents.size == 1)
                        val certChain = deviceResponse.documents.first().issuerCertChain
                        PresentmentResultMdoc(
                            id = id,
                            trustResult = trustManager.verify(certChain.certificates, atTime),
                            mdocDocument = deviceResponse.documents.first(),
                            transactionResults = transactionResponses.firstOrNull()
                        )
                    }

                    "dc+sd-jwt" -> {
                        val (sdJwt, sdJwtKb) = if (responseText.endsWith("~")) {
                            Pair(SdJwt.fromCompactSerialization(responseText), null)
                        } else {
                            val sdJwtKb = SdJwtKb.fromCompactSerialization(responseText)
                            Pair(sdJwtKb.sdJwt, sdJwtKb)
                        }
                        if (sdJwtKb == null && sdJwt.jwtBody["cnf"] != null) {
                            throw InvalidRequestException("`cnf` claim present but we got a SD-JWT, not a SD-JWT+KB")
                        }
                        // TODO: check SD-JWT validity at the given time!
                        val certChain = sdJwt.x5c?.certificates
                            ?: throw InvalidRequestException("'x5c' not found")
                        val claimMap = sdJwtKb?.verify(
                            issuerKey = certChain.first().ecPublicKey,
                            checkNonce = { it == nonce },
                            // TODO: check audience, and creationTime
                            checkAudience = { audience -> true },
                            checkCreationTime = { creationTime -> true },
                            transactionData = transactionData
                        ) ?: sdJwt.verify(certChain.first().ecPublicKey)
                        val transactionResults = if (transactionData.isEmpty() || sdJwtKb == null) {
                            null
                        } else {
                            buildMap<String, JsonElement> {
                                for (transaction in transactionData) {
                                    sdJwtKb.jwtBody[transaction.type.kbJwtResponseClaimName]?.let {
                                        put(transaction.type.identifier, it)
                                    }
                                }
                            }
                        }
                        PresentmentResultSdJwt(
                            id = id,
                            trustResult = trustManager.verify(sdJwt.x5c!!.certificates, atTime),
                            vct = sdJwt.credentialType
                                ?: throw java.lang.IllegalArgumentException("Credential '$id': no vct"),
                            claimMap = claimMap,
                            transactionResults = transactionResults
                        )
                    }
                    else ->
                        throw IllegalArgumentException("Unknown or unspecified format for '$id'")
                }
            }
        }
    }

    /**
     * Parses and returns transaction data entries using the given [documentTypeRepository]
     * to resolve transaction types.
     *
     * @param documentTypeRepository repository used to look up transaction type definitions.
     * @return map from credential ID to parsed transaction data entries.
     */
    fun getTransactionData(
        documentTypeRepository: DocumentTypeRepository
    ): Map<String, List<TransactionDataJson>> =
        TransactionDataJson.parse(
            base64UrlEncodedJson = jsonTransactionData?.map {
                it.encodeToByteArray().toBase64Url()
            } ?: emptyList(),
            documentTypeRepository = documentTypeRepository
        )

    companion object
}