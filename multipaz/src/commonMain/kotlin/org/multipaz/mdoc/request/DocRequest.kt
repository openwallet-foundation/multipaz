package org.multipaz.mdoc.request

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol

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

    /**
     * Compares two [DocRequest] instances and checks if they are similar in structure,
     * including signing structure (protected headers and unprotected header keys), ignoring
     * session-transcript-dependent signatures and ephemeral differences (such as certificate
     * chains when the reader uses single-use keys).
     *
     * @param otherDocRequest the other document request to compare against.
     * @return `true` if structurally equivalent, `false` otherwise.
     */
    fun isStructurallyEquivalent(otherDocRequest: DocRequest): Boolean {
        if (docType != otherDocRequest.docType) return false
        if (nameSpaces != otherDocRequest.nameSpaces) return false
        if (docRequestInfo != otherDocRequest.docRequestInfo) return false
        if ((readerAuth_ != null) != (otherDocRequest.readerAuth_ != null)) return false
        if (readerAuth_ != null && otherDocRequest.readerAuth_ != null) {
            if (!readerAuth_.isReaderAuthStructurallyEquivalent(otherDocRequest.readerAuth_)) return false
        }
        return true
    }

    internal fun toDataItem(): DataItem {
        return buildCborMap {
            put("itemsRequest", itemsRequestBytes)
            readerAuth_?.let {
                put("readerAuth", it.toDataItem())
            }
        }
    }

    internal fun hasTransactionData(): Boolean {
        if (docRequestInfo?.transactionData?.data?.isNotEmpty() == true) {
            return true
        }
        if (nameSpaces[ISO_18013_TRANSACTION_DATA_NAMESPACE]?.isNotEmpty() == true) {
            return true
        }
        return docRequestInfo?.alternativeDataElements?.any { altSet ->
            altSet.requestedElement.namespace == ISO_18013_TRANSACTION_DATA_NAMESPACE ||
                altSet.alternativeElementSets.any { elementRefs ->
                    elementRefs.any { it.namespace == ISO_18013_TRANSACTION_DATA_NAMESPACE }
                }
        } == true
    }

    /**
     * Returns parsed transaction data associated with this document request that was
     * requested by data elements.
     *
     * @param documentTypeRepository repository that contains all supported transaction data types
     * @return list of transaction data
     */
    fun getTransactionData(
        documentTypeRepository: DocumentTypeRepository
    ): List<TransactionData<*>> = buildList {
        val requestedTxIdentifiers = mutableSetOf<String>()
        nameSpaces[ISO_18013_TRANSACTION_DATA_NAMESPACE]?.keys?.let { requestedTxIdentifiers.addAll(it) }
        docRequestInfo?.alternativeDataElements?.forEach { altSet ->
            if (altSet.requestedElement.namespace == ISO_18013_TRANSACTION_DATA_NAMESPACE) {
                requestedTxIdentifiers.add(altSet.requestedElement.dataElement)
            }
            altSet.alternativeElementSets.forEach { elementRefs ->
                elementRefs.forEach { elementRef ->
                    if (elementRef.namespace == ISO_18013_TRANSACTION_DATA_NAMESPACE) {
                        requestedTxIdentifiers.add(elementRef.dataElement)
                    }
                }
            }
        }
        for (transactionType in documentTypeRepository.transactionTypes) {
            val typeId = transactionType.iso18013RequestInfoIdentifier
            if (!requestedTxIdentifiers.contains(typeId)) {
                continue
            }
            docRequestInfo?.transactionData?.data[typeId]?.let { data ->
                add(transactionType.parseCbor(data))
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

internal fun CoseSign1.isReaderAuthStructurallyEquivalent(other: CoseSign1): Boolean {
    val x5chainLabel = Cose.COSE_LABEL_X5CHAIN.toCoseLabel
    if (protectedHeaders.minus(x5chainLabel) != other.protectedHeaders.minus(x5chainLabel)) return false
    if (unprotectedHeaders.minus(x5chainLabel) != other.unprotectedHeaders.minus(x5chainLabel)) return false
    if ((x5chainLabel in protectedHeaders) != (x5chainLabel in other.protectedHeaders)) return false
    if ((x5chainLabel in unprotectedHeaders) != (x5chainLabel in other.unprotectedHeaders)) return false

    val certChain1 = (protectedHeaders[x5chainLabel] ?: unprotectedHeaders[x5chainLabel])?.asX509CertChain
    val certChain2 = (other.protectedHeaders[x5chainLabel] ?: other.unprotectedHeaders[x5chainLabel])?.asX509CertChain
    if ((certChain1 != null) != (certChain2 != null)) return false
    if (certChain1 != null && certChain2 != null) {
        val certs1 = certChain1.certificates
        val certs2 = certChain2.certificates
        if (certs1.size != certs2.size || certs1.isEmpty()) return false
        if (certs1.drop(1) != certs2.drop(1)) return false
        val leaf1 = certs1[0]
        val leaf2 = certs2[0]
        if (leaf1 != leaf2) {
            // Single-use leaf keys must still be issued by the same CA (not self-signed)
            // and identify the same reader subject.
            if (leaf1.subject == leaf1.issuer || leaf2.subject == leaf2.issuer) return false
            if (leaf1.issuer != leaf2.issuer) return false
            if (leaf1.subject != leaf2.subject) return false
            if (!leaf1.authorityKeyIdentifier.contentEquals(leaf2.authorityKeyIdentifier)) return false
        }
    }
    return true
}