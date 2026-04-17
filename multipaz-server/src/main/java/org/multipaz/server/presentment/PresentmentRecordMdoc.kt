package org.multipaz.server.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.TransactionType
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.presentment.TransactionDataCbor
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.util.toBase64Url
import java.lang.IllegalArgumentException
import kotlin.time.Instant

/**
 * [PresentmentRecord] for ISO 18013-5 mdoc presentment.
 *
 * TODO: support recording Annex A, proximity and zero-knowledge presentations as well.
 *
 * @property response CBOR-encoded `DeviceResponse` as defined in ISO 18013-5.
 * @property sessionTranscript CBOR `SessionTranscript` used for presentment authentication.
 * @property encryptionInfo encryption info from the Digital Credentials API, used for nonce
 *     verification.
 * @property origin the web origin that initiated the presentment request.
 * @property transactionData optional transaction data entries keyed by transaction's
 *  [TransactionType.mdocRequestInfoKeyName].
 */
class PresentmentRecordMdoc(
    val response: DataItem,
    val sessionTranscript: DataItem,
    val encryptionInfo: ByteString,
    val origin: String?,
    val transactionData: List<Map<String, DataItem>>?
): PresentmentRecord() {
    override suspend fun verifyNonce(nonce: ByteString) {
        val info = Cbor.decode(encryptionInfo.toByteArray())
        if (nonce != ByteString(info.asArray[1]["nonce"].asBstr)) {
            throw InvalidRequestException("Nonce mismatch")
        }
    }

    override suspend fun verify(atTime: Instant): List<PresentmentResultMdoc> {
        // Validate encryptionInfo matching sessionTranscript (otherwise our nonce check is
        // meaningless)
        val dcapiInfo = buildCborArray {
            add(encryptionInfo.toByteArray().toBase64Url())
            add(origin!!)
        }
        val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
        if (!dcapiInfoDigest.contentEquals(sessionTranscript.asArray[2].asArray[1].asBstr)) {
            throw InvalidRequestException("Invalid session transcript")
        }

        val documentTypeRepository =
            BackendEnvironment.getInterface(DocumentTypeRepository::class)!!
        val trustManager = BackendEnvironment.getInterface(TrustManagerInterface::class)!!
        val deviceResponse = DeviceResponse.fromDataItem(response)
        val transactionResponses = deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            transactionDataList = getTransactionData(documentTypeRepository),
            atTime = atTime
        )
        return deviceResponse.documents.mapIndexed { index, document ->
            transactionResponses[index]
            PresentmentResultMdoc(
                id = null,
                trustResult = trustManager.verify(document.issuerCertChain.certificates, atTime),
                mdocDocument = document,
                transactionResults = transactionResponses[index]
            )
        }
    }

    /**
     * Parses and returns transaction data entries using the given [documentTypeRepository]
     * to resolve transaction types from their mdoc request info key names.
     *
     * @param documentTypeRepository repository used to look up transaction type definitions.
     * @return parsed transaction data, or an empty list if [transactionData] is `null`.
     */
    fun getTransactionData(
        documentTypeRepository: DocumentTypeRepository
    ): List<List<TransactionDataCbor>> =
        transactionData?.map { transactions ->
            transactions.map { (key, value) ->
                val transactionType = documentTypeRepository.transactionTypes.find {
                    it.mdocRequestInfoKeyName == key
                } ?: throw IllegalArgumentException("Unknown mdoc transaction type: '$key'")
                TransactionDataCbor(transactionType, value as Tagged)
            }
        } ?: emptyList()

    companion object
}