package org.multipaz.mdoc.request

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.presentment.TransactionData

/**
 * Document request according to ISO 18013-5.
 *
 * @property docType the document type.
 * @property nameSpaces the namespaces and data items to request, with intentToRetain.
 * @property docRequestInfo a [DocRequestInfo] or `null`.
 * @property docRequestId the index of the [DocRequest] in [DeviceRequest].
 */
@ConsistentCopyVisibility
data class DocRequest internal constructor(
    val docType: String,
    val nameSpaces: Map<String, Map<String, Boolean>>,
    val docRequestInfo: DocRequestInfo?,
    val docRequestId: Int,
    internal val readerAuth_: CoseSign1?,
    internal val itemsRequestBytes: DataItem,
) {
    internal var readerAuthVerified: Boolean = false

    /**
     * Reader authentication for the document request or `null` if reader authentication is not used.
     *
     * @throws IllegalStateException if this is accessed before [DeviceRequest.verifyReaderAuthentication] is called.
     */
    val readerAuth: CoseSign1?
        get() {
            if (!readerAuthVerified) {
                throw IllegalStateException("readerAuth not verified")
            }
            return readerAuth_
        }

    internal val readerAuthCertChain: X509CertChain?
        get() = readerAuth_?.let {
            // x5chain can be in both protected or unprotected header, prefer protected
            val certChainDataItem = it.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                ?: it.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
            certChainDataItem?.asX509CertChain
        }

    internal val readerAuthAlgorithm: Algorithm?
        get() = readerAuth_?.let {
            Algorithm.fromCoseAlgorithmIdentifier(
                it.protectedHeaders[
                    CoseNumberLabel(Cose.COSE_LABEL_ALG)
                ]!!.asNumber.toInt()
            )
        }

    internal fun toDataItem(): DataItem {
        return buildCborMap {
            put("itemsRequest", itemsRequestBytes)
            readerAuth_?.let {
                put("readerAuth", it.toDataItem())
            }
        }
    }

    /**
     * Returns parsed transaction data associated with this document request.
     *
     * @param documentTypeRepository repository that contains all supported transaction data types
     * @return list of transaction data
     */
    fun getTransactionData(
        documentTypeRepository: DocumentTypeRepository
    ): List<TransactionData<*>> = buildList {
        for (transactionType in documentTypeRepository.transactionTypes) {
            docRequestInfo?.otherInfo[transactionType.mdocRequestInfoKeyName]?.let { data ->
                add(transactionType.parseCbor(ByteString((data as Tagged).taggedItem.asBstr)))
            }
        }
    }

    companion object {
        internal fun fromDataItem(
            dataItem: DataItem,
            docRequestId: Int
        ): DocRequest {
            val itemsRequestBytes = dataItem["itemsRequest"]
            val itemsRequest = itemsRequestBytes.asTaggedEncodedCbor
            val readerAuth = dataItem.getOrNull("readerAuth")?.asCoseSign1
            val docType = itemsRequest["docType"].asTstr
            val nameSpaces = buildMap {
                for ((nameSpaceName, dataElements) in itemsRequest["nameSpaces"].asMap) {
                    put(nameSpaceName.asTstr, buildMap {
                        for ((dataElement, intentToRetain) in dataElements.asMap) {
                            put(dataElement.asTstr, intentToRetain.asBoolean)
                        }
                    })
                }
            }
            val docRequestInfo = itemsRequest.getOrNull("requestInfo")?.let {
                DocRequestInfo.fromDataItem(it)
            }
            return DocRequest(
                docType = docType,
                nameSpaces = nameSpaces,
                docRequestInfo = docRequestInfo,
                docRequestId = docRequestId,
                readerAuth_ = readerAuth,
                itemsRequestBytes = itemsRequestBytes,
            )
        }
    }
}