package org.multipaz.verification

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.DataItem
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.presentment.TransactionDataJson
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.toBase64Url
import kotlin.time.Instant

/**
 * [PresentmentRecord] for OpenID4VP presentations.
 *
 * Supports both `mso_mdoc` and `dc+sd-jwt` credential formats within a single VP token.
 *
 * @property vpToken JSON-encoded VP token containing the credential responses, structured as
 *  a JSON object mapping credential IDs to arrays of credential response strings.
 * @property vpRequest raw OpenID4VP request (extracted from signed JWT enveloped if needed).
 * @property mdocSessionTranscript CBOR `SessionTranscript` used for mdoc device authentication;
 *     required when any credential uses `mso_mdoc` format.
 */
class OpenID4VPPresentmentRecord(
    val vpToken: String,
    val vpRequest: String,
    val mdocSessionTranscript: DataItem?,
): PresentmentRecord() {
    override suspend fun verifyNonce(nonce: ByteString) {
        val vpNonce = Json.parseToJsonElement(vpRequest)
            .jsonObject["nonce"]!!.jsonPrimitive.content
        if (nonce.toByteArray().toBase64Url() != vpNonce) {
            throw InvalidRequestException("Nonce mismatch")
        }
    }

    override suspend fun verify(
        atTime: Instant,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?
    ): List<VerifiedPresentation> {
        val request = Json.parseToJsonElement(vpRequest).jsonObject
        val transactionDataMap = getTransactionData(request, documentTypeRepository)
        val nonce = request["nonce"]!!.jsonPrimitive.content
        val queryData = QueryData.fromDcql(request["dcql_query"]!!.jsonObject)
        return VerificationUtil.verifyOpenID4VPResponse(
            now = atTime,
            vpToken = Json.parseToJsonElement(vpToken).jsonObject,
            sessionTranscript = mdocSessionTranscript,
            nonce = nonce,
            documentTypeRepository = documentTypeRepository,
            zkSystemRepository = zkSystemRepository,
            transactionDataMap = transactionDataMap,
            queryData = queryData.associateBy { it.id!! }
        )
    }

    override fun getTransactionData(
        documentTypeRepository: DocumentTypeRepository
    ): Map<String, List<TransactionDataJson>> {
        val request = Json.parseToJsonElement(vpRequest).jsonObject
        return getTransactionData(request, documentTypeRepository)
    }

    companion object {
        private fun getTransactionData(
            request: JsonObject,
            documentTypeRepository: DocumentTypeRepository?
        ): Map<String, List<TransactionDataJson>> {
            val transactionData = request["transaction_data"]?.jsonArray
            return if (transactionData.isNullOrEmpty()) {
                emptyMap()
            } else {
                TransactionDataJson.parse(
                    transactionData = transactionData,
                    documentTypeRepository = documentTypeRepository!!
                )
            }
        }
    }
}