package org.multipaz.verification

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.presentment.TransactionDataCbor
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.toBase64Url
import kotlin.time.Instant

/**
 * [PresentmentRecord] for ISO 18013-5 mdoc presentment.
 *
 * TODO: support recording Annex A, proximity and zero-knowledge presentations as well.
 *
 * @property response CBOR-encoded `DeviceResponse` as defined in ISO 18013-5.
 * @property sessionTranscript CBOR `SessionTranscript` used for presentment authentication.
 * @property request original ISO 18013 request
 * @property eDeviceKey session encryption key (only required for proximity presentations).
 * @property requestDefinition original definition from which this request was created (e.g. DCQL)
 * @property encryptionInfo encryption info from the Digital Credentials API, only needed for nonce
 *     verification.
 * @property origin the web origin that initiated the presentment request, only needed for nonce
 *     verification.
 */
class MdocPresentmentRecord(
    val response: DataItem,
    val sessionTranscript: DataItem,
    val request: DataItem,
    val eDeviceKey: EcPrivateKey?,
    val requestDefinition: RequestDefinition?,
    val encryptionInfo: ByteString?,
    val origin: String?,
): PresentmentRecord() {
    override suspend fun verifyNonce(nonce: ByteString) {
        if (encryptionInfo == null) {
            throw InvalidRequestException("encryptionInfo is required for verifyNonce")
        }
        if (origin == null) {
            throw InvalidRequestException("origin is required for verifyNonce")
        }
        val info = Cbor.decode(encryptionInfo.toByteArray())
        if (nonce != ByteString(info.asArray[1]["nonce"].asBstr)) {
            throw InvalidRequestException("Nonce mismatch")
        }
        val dcapiInfo = buildCborArray {
            add(encryptionInfo.toByteArray().toBase64Url())
            add(origin)
        }
        val digestedInfo = Cbor.encode(dcapiInfo)
        val infoDigest = Crypto.digest(Algorithm.SHA256, digestedInfo)
        if (!infoDigest.contentEquals(sessionTranscript.asArray[2].asArray[1].asBstr)) {
            throw InvalidRequestException("Nonce does not match session transcript")
        }
    }

    override suspend fun verify(
        atTime: Instant,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?
    ): List<VerifiedPresentation> {
        // We could build QueryData from DCQL: or DeviceRequest. Prefer DCQL as this will give
        // us ids for documents and claims.
        val deviceRequest = DeviceRequest.fromDataItem(request)
        val queryData = if (requestDefinition is DcqlRequestDefinition) {
            QueryData.fromDcql(Json.parseToJsonElement(requestDefinition.dcql).jsonObject)
        } else {
            QueryData.fromDeviceRequest(deviceRequest)
        }
        return VerificationUtil.verifyMdocDeviceResponse(
            now = atTime,
            deviceResponse = response,
            sessionTranscript = sessionTranscript,
            eReaderKey = eDeviceKey?.let { AsymmetricKey.anonymous(it) },
            documentTypeRepository = documentTypeRepository,
            zkSystemRepository = zkSystemRepository,
            request = deviceRequest,
            queryData = queryData
        )
    }

    override fun getTransactionData(
        documentTypeRepository: DocumentTypeRepository
    ): Map<String, List<TransactionDataCbor>> {
        val deviceRequest = DeviceRequest.fromDataItem(request)
        deviceRequest.docRequests.map { it.getTransactionData(documentTypeRepository) }
        requestDefinition as? DcqlRequestDefinition
            ?: throw IllegalStateException("DcqlRequestDefinition is required")
        val queryData = QueryData.fromDcql(
            dcql = Json.parseToJsonElement(requestDefinition.dcql).jsonObject
        )
        return buildMap {
            for ((index, docQuery) in queryData.withIndex()) {
                val transactionData =
                    deviceRequest.docRequests[index].getTransactionData(documentTypeRepository)
                put(docQuery.id!!, transactionData)
            }
        }
    }

    companion object
}