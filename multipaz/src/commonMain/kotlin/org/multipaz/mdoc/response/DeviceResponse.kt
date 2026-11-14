package org.multipaz.mdoc.response

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Uint
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.response.DeviceResponse.Companion.STATUS_OK
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.presentment.TransactionData
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.zlibInflate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Top-level device response in ISO 18013-5.
 *
 * To construct an instance use [buildDeviceResponse].
 *
 * For a response received from a remote mdoc use [DeviceResponse.Companion.fromDataItem].
 * Note that you have to manually call [verify] before accessing the [documents] field
 * for instances created this way.
 *
 * @property version the version of the device response (`"1.0"`).
 * @property status the status field containing for example [STATUS_OK] or [STATUS_GENERAL_ERROR].
 * @property documents a list of returned and verified documents.
 * @property zkDocuments a list of returned documents with ZKP.
 * @property encryptedDocuments a list of returned encrypted documents.
 * @property otherDocuments a list of returned documents in other formats, such as SD-JWT VC.
 * @property documentErrors a list of returned errors.
 */
@ConsistentCopyVisibility
data class DeviceResponse internal constructor(
    val version: String,
    val status: Int,
    private val documents_: List<MdocDocument>,
    val zkDocuments: List<ZkDocument>,
    val encryptedDocuments: List<EncryptedDocuments>,
    val otherDocuments: List<OtherDocument>,
    val documentErrors: List<Map<String, Int>>
) {
    private var numTimesVerifyCalled = 0

    val documents: List<MdocDocument>
        get() = if (numTimesVerifyCalled == 0) {
            throw IllegalStateException("verify() not yet called")
        } else {
            documents_
        }

    /**
     * Verifies the integrity of the returned documents, according to ISO/IEC 18013-5.
     *
     * The following checks are performed for each [MdocDocument] instance in [documents]:
     * - For [MdocDocument.issuerAuth] the signature is checked against the leaf certificate in the associated X.509 chain.
     * - The document type in the MSO matches the docType in the response.
     * - The MSO's signed date is within the validity period of the leaf certificate in the associated X.509 chain.
     * - If [rejectIfValidUntilAfterNotAfter] is true, the MSO's validUntil date does not exceed the leaf certificate's validity period.
     * - The MSO is validity period includes the passed-in [atTime].
     * - The data returned in [MdocDocument.issuerNamespaces] is checked against digests in the MSO.
     * - The device-authentication structures (ECDSA or MAC) are checked.
     * - If any data elements are returned as part of DeviceSigned, all those data elements or
     *   their namespace are included in the keyAuthorizations map in the DeviceKeyInfo map in the MSO.
     * - For each transaction data in the list, verifies that transaction hash is present in the
     *    response and matches the hash of the source transaction data
     *
     * The following checks are performed for each [OtherDocument] instance in [otherDocuments]:
     *  - For document format `dc+sd-jwt`:
     *    - The SD-JWT+KB is constructed from decompressing [OtherDocument.data]
     *    - Verification is done with [org.multipaz.sdjwt.SdJwtKb.verify] using the issuer signing key
     *      from the leaf certificate in the [org.multipaz.sdjwt.SdJwt.x5c], the nonce derived from
     *      the session transcript, creation-time is checked against the passed-in [atTime], and
     *      audience is checked to be derived from `ReaderAuthAll` or `ReaderAuth` for signed
     *      requests or `none` for unsigned requests.
     *    - The credential's validity period includes the passed-in [atTime].
     *
     * The following checks are expected to be done by the application:
     * - Determining whether the issuer's document signing certificate is trusted.
     *   An application can use [org.multipaz.trustmanagement.TrustManagerInterface] to do this.
     * - Checking whether the MSO is revoked, or any of the keys involved are revoked.
     * - Checking the integrity of any Zero-Knowledge Proofs for documents returned in [zkDocuments].
     *   An application can use [org.multipaz.mdoc.zkp.ZkSystem] to do this.
     * - Checking or decrypting any encrypted documents returned in [encryptedDocuments].
     *   Use [EncryptedDocuments.decrypt] to do this.
     *
     * @param sessionTranscript the session transcript to use.
     * @param eReaderKey the ephemeral reader key or `null` if not using session encryption.
     * @param deviceRequest the request to which this response is given; required when verifying
     *   `dc+sd-jwt` documents in [otherDocuments] or when transaction data was sent in the request.
     * @param documentTypeRepository repository that contains all known transaction types; must
     *   be given if [deviceRequest] is given and contains transaction data.
     * @param atTime the point in time for validating whether returned documents are valid.
     * @param rejectIfValidUntilAfterNotAfter if true, rejects the MSO if its `validUntil` date exceeds
     *   the Document Signer certificate validity period according to ISO/IEC 18013-5 Second Edition
     *   clause 12.8.1. This defaults to `false` as this check is discretionary for readers ("may reject")
     *   and official test vectors in ISO/IEC 18013-5:2021 Annex D have `validUntil` after `notAfter`.
     * @throws IllegalStateException if validation fails.
     */
    suspend fun verify(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey? = null,
        deviceRequest: DeviceRequest? = null,
        documentTypeRepository: DocumentTypeRepository? = null,
        atTime: Instant = Clock.System.now(),
        rejectIfValidUntilAfterNotAfter: Boolean = false,
    ) {
        numTimesVerifyCalled += 1
        if (deviceRequest != null &&
            deviceRequest.docRequests.any { it.hasTransactionData() } &&
            documentTypeRepository == null
        ) {
            throw IllegalStateException(
                "documentTypeRepository is required when DeviceRequest contains transaction data"
            )
        }
        documents_.forEach { document ->
            val transactionData = if (deviceRequest == null) {
                emptyList()
            } else {
                val explicitIds = document.docRequestIds
                if (explicitIds != null) {
                    if (explicitIds.distinct().size != explicitIds.size) {
                        throw IllegalStateException("Duplicate docRequestIds $explicitIds in MdocDocument")
                    }
                    for (id in explicitIds) {
                        val req = deviceRequest.docRequests.getOrNull(id)
                            ?: throw IllegalStateException("Invalid docRequestId $id in MdocDocument")
                        validateMdocAgainstDocRequest(document, id, req)
                    }
                    if (documentTypeRepository == null) {
                        emptyList()
                    } else {
                        validateSignedMdocTransactionDocRequestIds(
                            document = document,
                            allowedIds = explicitIds,
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                        resolveEncryptedMdocTransactions(
                            document = document,
                            reqIds = explicitIds,
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                    }
                } else {
                    val docRequestId = findDocRequestId(deviceRequest, documentTypeRepository, document)
                    if (docRequestId < 0) {
                        throw IllegalStateException(
                            "Cannot resolve DocRequest for document with docType '${document.docType}'"
                        )
                    }
                    if (!matchesMdocIssuerIdentifiers(document, deviceRequest.docRequests[docRequestId])) {
                        throw IllegalStateException(
                            "Document certificate chain does not match requested issuerIdentifiers for docType '${document.docType}'"
                        )
                    }
                    if (documentTypeRepository == null) {
                        emptyList()
                    } else {
                        resolveEncryptedMdocTransactions(
                            document = document,
                            reqIds = listOf(docRequestId),
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                    }
                }
            }
            document.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                transactionData = transactionData,
                atTime = atTime,
                rejectIfValidUntilAfterNotAfter = rejectIfValidUntilAfterNotAfter,
            )
        }
        if (deviceRequest != null) {
            zkDocuments.forEach { zkDocument ->
                val explicitIds = zkDocument.docRequestIds
                if (explicitIds != null) {
                    if (explicitIds.distinct().size != explicitIds.size) {
                        throw IllegalStateException("Duplicate docRequestIds $explicitIds in ZkDocument")
                    }
                    for (id in explicitIds) {
                        val req = deviceRequest.docRequests.getOrNull(id)
                            ?: throw IllegalStateException("Invalid docRequestId $id in ZkDocument")
                        validateZkDocAgainstDocRequest(zkDocument, id, req)
                    }
                } else {
                    val matchingZkRequests = deviceRequest.docRequests.filter { req ->
                        req.docType == zkDocument.documentData.docType &&
                            (req.docRequestInfo?.docFormat ?: "mso_mdoc") in listOf("mso_mdoc", "mso_mdoc_zk") &&
                            req.docRequestInfo?.zkRequest != null &&
                            req.docRequestInfo.zkRequest.systemSpecs.any { it.id == zkDocument.documentData.zkSystemSpecId }
                    }
                    if (matchingZkRequests.isEmpty()) {
                        throw IllegalStateException(
                            "Cannot resolve DocRequest with matching zkRequest for ZkDocument with docType '${zkDocument.documentData.docType}'"
                        )
                    }
                    if (matchingZkRequests.all {
                            it.hasTransactionData() && it.docRequestInfo?.alternativeDataElements.isNullOrEmpty()
                        }
                    ) {
                        throw IllegalStateException("Transaction data is not supported with ZkDocument")
                    }
                    val issuerMatchingZkRequests = matchingZkRequests.filter { req ->
                        matchesZkIssuerIdentifiers(zkDocument, req)
                    }
                    if (issuerMatchingZkRequests.isEmpty()) {
                        throw IllegalStateException(
                            "ZkDocument certificate chain does not match requested issuerIdentifiers for docType '${zkDocument.documentData.docType}'"
                        )
                    }
                }
            }
            encryptedDocuments.forEach { encDoc ->
                if (encDoc.docRequestIds.distinct().size != encDoc.docRequestIds.size) {
                    throw IllegalStateException("Duplicate docRequestIds ${encDoc.docRequestIds} in EncryptedDocuments")
                }
                for (reqId in encDoc.docRequestIds) {
                    val req = deviceRequest.docRequests.getOrNull(reqId)
                        ?: throw IllegalStateException("Invalid docRequestId $reqId for EncryptedDocuments")
                    if (req.docRequestInfo?.docResponseEncryption == null) {
                        throw IllegalStateException(
                            "Unexpected EncryptedDocuments for DocRequest $reqId without docResponseEncryption"
                        )
                    }
                }
            }
        }
        otherDocuments.forEach { otherDocument ->
            otherDocument.clearVerificationState()
            val explicitIds = otherDocument.docRequestIds
            if (deviceRequest != null && explicitIds != null) {
                if (explicitIds.distinct().size != explicitIds.size) {
                    throw IllegalStateException("Duplicate docRequestIds $explicitIds in OtherDocument")
                }
                for (id in explicitIds) {
                    if (id !in deviceRequest.docRequests.indices) {
                        throw IllegalStateException("Invalid docRequestId $id in OtherDocument")
                    }
                }
                val expectedAudience: String?
                val transactionData: List<TransactionData<*>>
                if (otherDocument.docFormat == "dc+sd-jwt") {
                    val sdJwtKb = SdJwtKb.fromCompactSerialization(
                        otherDocument.data.toByteArray().zlibInflate().decodeToString()
                    )
                    val vct = (sdJwtKb.sdJwt.jwtBody["vct"] as? JsonPrimitive)?.content
                        ?: throw IllegalStateException("OtherDocument is missing 'vct' claim")
                    for (id in explicitIds) {
                        validateSdJwtAgainstDocRequest(
                            sdJwtKb = sdJwtKb,
                            vct = vct,
                            reqId = id,
                            docRequest = deviceRequest.docRequests[id],
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                    }
                    val audiences = explicitIds.map { id ->
                        deviceRequest.getExpectedOtherDocumentAudience(deviceRequest.docRequests[id])
                    }.distinct()
                    if (audiences.size != 1) {
                        throw IllegalStateException("Inconsistent expected audiences across docRequestIds $explicitIds")
                    }
                    expectedAudience = audiences.single()
                    transactionData = if (documentTypeRepository == null) {
                        emptyList()
                    } else {
                        validateSignedSdJwtTransactionDocRequestIds(
                            sdJwtKb = sdJwtKb,
                            allowedIds = explicitIds,
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                        resolveEncryptedSdJwtTransactions(
                            otherDocument = otherDocument,
                            reqIds = explicitIds,
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                    }
                } else {
                    expectedAudience = null
                    transactionData = emptyList()
                }
                otherDocument.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = eReaderKey,
                    transactionData = transactionData,
                    atTime = atTime,
                    expectedAudience = expectedAudience
                )
            } else {
                val docRequestId = if (deviceRequest == null) {
                    -1
                } else {
                    findDocRequestId(deviceRequest, documentTypeRepository, otherDocument)
                }
                val docRequest = if (deviceRequest != null && docRequestId in deviceRequest.docRequests.indices) {
                    deviceRequest.docRequests[docRequestId]
                } else {
                    null
                }
                if (deviceRequest != null && docRequest == null && deviceRequest.docRequests.any { it.hasTransactionData() }) {
                    throw IllegalStateException("Cannot resolve DocRequest for otherDocument")
                }
                val transactionData = if (docRequest == null || documentTypeRepository == null) {
                    emptyList()
                } else {
                    resolveEncryptedSdJwtTransactions(
                        otherDocument = otherDocument,
                        reqIds = listOf(docRequestId),
                        deviceRequest = deviceRequest!!,
                        documentTypeRepository = documentTypeRepository
                    )
                }
                val expectedAudience = deviceRequest?.getExpectedOtherDocumentAudience(docRequest)
                otherDocument.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = eReaderKey,
                    transactionData = transactionData,
                    atTime = atTime,
                    expectedAudience = expectedAudience
                )
                if (docRequest != null && otherDocument.docFormat == "dc+sd-jwt") {
                    val sdJwtKb = SdJwtKb.fromCompactSerialization(
                        otherDocument.data.toByteArray().zlibInflate().decodeToString()
                    )
                    if (!matchesSdJwtIssuerIdentifiers(sdJwtKb, docRequest)) {
                        throw IllegalStateException(
                            "OtherDocument certificate chain does not match requested issuerIdentifiers"
                        )
                    }
                }
            }
        }
    }

    /**
     * Similar to [verify] but for simple responses containing only a single document.
     *
     * [DeviceResponse] must contain a single document. Parsed transaction data is supplied
     * using [transactionData] parameter instead of [DeviceRequest].
     *
     * @param sessionTranscript the session transcript to use.
     * @param transactionData transaction data that was associated with the request
     * @param atTime the point in time for validating whether returned documents are valid.
     * @param rejectIfValidUntilAfterNotAfter if true, rejects the MSO if its `validUntil` date exceeds
     *   the Document Signer certificate validity period according to ISO/IEC 18013-5 Second Edition
     *   clause 12.8.1. This defaults to `false` as this check is discretionary for readers ("may reject")
     *   and official test vectors in ISO/IEC 18013-5:2021 Annex D have `validUntil` after `notAfter`.
     * @throws IllegalStateException if validation fails.
     */
    suspend fun verifySingleDoc(
        sessionTranscript: DataItem,
        transactionData: List<TransactionData<*>>,
        atTime: Instant = Clock.System.now(),
        rejectIfValidUntilAfterNotAfter: Boolean = false,
    ) {
        if (otherDocuments.isNotEmpty() || encryptedDocuments.isNotEmpty()) {
            throw IllegalStateException("Not a single-document DeviceResponse")
        }
        if (documents_.size == 1 && zkDocuments.isEmpty()) {
            numTimesVerifyCalled += 1
            documents_.first().verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = transactionData,
                atTime = atTime,
                rejectIfValidUntilAfterNotAfter = rejectIfValidUntilAfterNotAfter,
            )
        } else if (zkDocuments.size == 1 && documents_.isEmpty()) {
            if (transactionData.isNotEmpty()) {
                throw IllegalStateException("Transaction data is not supported with ZkDocument")
            }
            numTimesVerifyCalled += 1
            // Zero-knowledge proof is verified when generating response
        } else {
            throw IllegalStateException("Not a single-document DeviceResponse")
        }
    }

    /**
     * Generates CBOR compliant with the CDDL for `DeviceResponse` according to ISO 18013-5.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        put("version", version)
        put("status", status)
        if (documents_.isNotEmpty()) {
            putCborArray("documents") {
                documents_.forEach { add(it.toDataItem()) }
            }
        }
        if (zkDocuments.isNotEmpty()) {
            putCborArray("zkDocuments") {
                zkDocuments.forEach { add(it.toDataItem()) }
            }
        }
        if (encryptedDocuments.isNotEmpty()) {
            putCborArray("encryptedDocuments") {
                encryptedDocuments.forEach { add(it.toDataItem()) }
            }
        }
        if (otherDocuments.isNotEmpty()) {
            putCborArray("otherDocuments") {
                otherDocuments.forEach { add(it.toDataItem()) }
            }
        }
        if (documentErrors.isNotEmpty()) {
            putCborArray("documentErrors") {
                documentErrors.forEach {
                    addCborMap {
                        it.entries.forEach { (docType, errorCode) ->
                            put(docType, errorCode)
                        }
                    }
                }
            }
        }
    }

    private fun findDocRequestId(
        deviceRequest: DeviceRequest,
        documentTypeRepository: DocumentTypeRepository?,
        doc: MdocDocument
    ): Int {
        if (documentTypeRepository != null) {
            var docRequestId: ULong? = null
            val presentTransactionIds = mutableListOf<String>()
            val data = doc.deviceNamespaces.data
            for (transactionType in documentTypeRepository.transactionTypes) {
                val transactionItem =
                    data[ISO_18013_TRANSACTION_DATA_NAMESPACE]?.get(transactionType.identifier) ?: continue
                presentTransactionIds.add(transactionType.identifier)
                val transactionDocRequestId = transactionItem.getOrNull("docRequestId") as? Uint
                    ?: throw IllegalStateException(
                        "'docRequestId' is missing or invalid for transaction '${transactionType.identifier}'"
                    )
                if (docRequestId == null) {
                    docRequestId = transactionDocRequestId.value
                } else if (docRequestId != transactionDocRequestId.value) {
                    throw IllegalStateException("inconsistent 'docRequestId' values")
                }
            }
            if (docRequestId != null) {
                val id = docRequestId.toInt()
                if (docRequestId != id.toULong() || id !in deviceRequest.docRequests.indices) {
                    throw IllegalStateException("Invalid 'docRequestId' value $docRequestId")
                }
                val req = deviceRequest.docRequests[id]
                if ((req.docRequestInfo?.docFormat ?: "mso_mdoc") != "mso_mdoc" || req.docType != doc.docType) {
                    throw IllegalStateException(
                        "DocRequest at index $id does not match document with docType '${doc.docType}'"
                    )
                }
                val requestedTxIds = req.getTransactionData(documentTypeRepository).map { it.type.identifier }.toSet()
                for (txId in presentTransactionIds) {
                    if (txId !in requestedTxIds) {
                        throw IllegalStateException("Transaction '$txId' was not requested in DocRequest $id")
                    }
                }
                return id
            }
        }
        val matchingIndices = deviceRequest.docRequests.indices.filter { index ->
            val req = deviceRequest.docRequests[index]
            req.docType == doc.docType && (req.docRequestInfo?.docFormat ?: "mso_mdoc") == "mso_mdoc"
        }
        if (matchingIndices.size == 1) {
            return matchingIndices.single()
        }
        if (matchingIndices.isNotEmpty() && matchingIndices.none { deviceRequest.docRequests[it].hasTransactionData() }) {
            return matchingIndices.first()
        }
        return -1
    }

    private suspend fun findDocRequestId(
        deviceRequest: DeviceRequest,
        documentTypeRepository: DocumentTypeRepository?,
        doc: OtherDocument
    ): Int {
        if (doc.docFormat != "dc+sd-jwt") {
            return -1
        }
        val sdJwtKb = SdJwtKb.fromCompactSerialization(
            compactSerialization = doc.data.toByteArray().zlibInflate().decodeToString()
        )
        val vct = (sdJwtKb.sdJwt.jwtBody["vct"] as? JsonPrimitive)?.content ?: return -1
        if (documentTypeRepository != null) {
            var docRequestId: Int? = null
            val presentTransactionIds = mutableListOf<String>()
            val sdJwtBody = sdJwtKb.jwtBody
            for (transactionType in documentTypeRepository.transactionTypes) {
                val transactionResponse = sdJwtBody[transactionType.kbJwtResponseClaimName] ?: continue
                presentTransactionIds.add(transactionType.identifier)
                val transactionDocRequestId =
                    ((transactionResponse as? JsonObject)?.get("doc_request_id") as? JsonPrimitive)?.intOrNull
                        ?: throw IllegalStateException(
                            "'doc_request_id' is missing or invalid for transaction '${transactionType.identifier}'"
                        )
                if (docRequestId == null) {
                    docRequestId = transactionDocRequestId
                } else if (docRequestId != transactionDocRequestId) {
                    throw IllegalStateException("inconsistent 'doc_request_id' values")
                }
            }
            if (docRequestId != null) {
                if (docRequestId !in deviceRequest.docRequests.indices) {
                    throw IllegalStateException("Invalid 'doc_request_id' value $docRequestId")
                }
                val req = deviceRequest.docRequests[docRequestId]
                if (req.docRequestInfo?.docFormat != "dc+sd-jwt") {
                    throw IllegalStateException("DocRequest at index $docRequestId is not a dc+sd-jwt request")
                }
                val requestedTxIds = req.getTransactionData(documentTypeRepository).map { it.type.identifier }.toSet()
                for (txId in presentTransactionIds) {
                    if (txId !in requestedTxIds) {
                        throw IllegalStateException(
                            "Transaction '$txId' was not requested in DocRequest $docRequestId"
                        )
                    }
                }
                val isAllowedAlias = documentTypeRepository.vctMatches(vct, req.docType) &&
                    deviceRequest.docRequests.none {
                        it.docRequestInfo?.docFormat == "dc+sd-jwt" && it.docType == vct
                    }
                if (vct != req.docType && !isAllowedAlias) {
                    throw IllegalStateException(
                        "OtherDocument vct '$vct' does not match DocRequest at index $docRequestId"
                    )
                }
                return docRequestId
            }
        }
        val sdJwtIndices = deviceRequest.docRequests.indices.filter { index ->
            deviceRequest.docRequests[index].docRequestInfo?.docFormat == "dc+sd-jwt"
        }
        if (sdJwtIndices.isEmpty()) {
            return -1
        }
        val exactMatchingIndices = sdJwtIndices.filter { index ->
            deviceRequest.docRequests[index].docType == vct
        }
        val candidates = exactMatchingIndices.ifEmpty {
            if (documentTypeRepository == null) {
                emptyList()
            } else {
                sdJwtIndices.filter { index ->
                    documentTypeRepository.vctMatches(vct, deviceRequest.docRequests[index].docType)
                }
            }
        }
        if (candidates.isEmpty()) {
            return -1
        }
        if (candidates.size == 1) {
            return candidates.single()
        }
        if (candidates.none { deviceRequest.docRequests[it].hasTransactionData() }) {
            val audiences = candidates.map { index ->
                deviceRequest.getExpectedOtherDocumentAudience(deviceRequest.docRequests[index])
            }.distinct()
            if (audiences.size == 1) {
                return candidates.first()
            }
        }
        return -1
    }

    companion object {
        private const val TAG = "DeviceResponse"

        /**
         * The status code for when documents are returned.
         *
         * This constant is intended to be used for the [status] property and the status parameter
         * in [buildDeviceResponse].
         *
         * This constant is defined in ISO/IEC 18013-5:2021 table 8.
         */
        const val STATUS_OK = 0

        /**
         * The status code for when the mdoc returns an error without any given reason.
         *
         * This constant is intended to be used for the [status] property and the status parameter
         * in [buildDeviceResponse].
         *
         * This constant is defined in ISO/IEC 18013-5:2021 table 8.
         */
        const val STATUS_GENERAL_ERROR = 10

        /**
         * The status code for when the mdoc indicates an error during CBOR decoding
         * that the data received is not valid CBOR.
         *
         * This constant is intended to be used for the [status] property and the status parameter
         * in [buildDeviceResponse].
         *
         * This constant is defined in ISO/IEC 18013-5:2021 table 8.
         */
        const val STATUS_CBOR_DECODING_ERROR = 11

        /**
         * The status code for when the mdoc indicates an error during CBOR validation, e.g. wrong CBOR structures.
         *
         * This constant is intended to be used for the [status] property and the status parameter
         * in [buildDeviceResponse].
         *
         * This constant is defined in ISO/IEC 18013-5:2021 table 8.
         */
        const val STATUS_CBOR_VALIDATION_ERROR = 12

        /**
         * An error code for data not returned.
         *
         * This constant is intended to be used in the errors parameter of the [Builder.addDocument]
         * and [EncryptedDocuments.Builder.addDocument] methods.
         *
         * This constant is defined in ISO/IEC 18013-5:2021 table 9.
         */
        const val ERROR_CODE_DATA_NOT_RETURNED = 0

        /**
         * Parses CBOR compliant with the CDDL for `DeviceResponse` according to ISO 18013-5.
         *
         * Note that you have to manually call [verify] before accessing the [documents] field
         * for instances created this way.
         *
         * @param dataItem a [DataItem] containing CBOR for `DeviceResponse`.
         * @return a [DeviceResponse].
         */
        suspend fun fromDataItem(dataItem: DataItem): DeviceResponse {
            val version = dataItem["version"].asTstr
            val status = dataItem["status"].asNumber.toInt()
            val documents = dataItem.getOrNull("documents")?.asArray?.map {
                MdocDocument.fromDataItem(it)
            }
            val zkDocuments = dataItem.getOrNull("zkDocuments")?.asArray?.map {
                ZkDocument.fromDataItem(it)
            }
            val encryptedDocuments = dataItem.getOrNull("encryptedDocuments")?.asArray?.map {
                EncryptedDocuments.fromDataItem(it)
            }
            val otherDocuments = dataItem.getOrNull("otherDocuments")?.asArray?.map {
                OtherDocument.fromDataItem(it)
            }
            val documentErrors = dataItem.getOrNull("documentErrors")?.asArray?.map {
                it.asMap.entries.associate { (docType, errorCode) ->
                    docType.asTstr to errorCode.asNumber.toInt()
                }
            }
            return DeviceResponse(
                version = version,
                status = status,
                documents_ = documents ?: emptyList(),
                zkDocuments = zkDocuments ?: emptyList(),
                encryptedDocuments = encryptedDocuments ?: emptyList(),
                otherDocuments = otherDocuments ?: emptyList(),
                documentErrors = documentErrors ?: emptyList()
            )
        }
    }

    /**
     * A builder for [DeviceResponse].
     *
     * @param sessionTranscript the session transcript to use.
     * @param status the status to use in the response, for example [STATUS_OK].
     * @param eReaderKey the ephemeral reader key or `null` if not using session encryption.
     * @param version the version to use or `null` to automatically select the version.
     */
    class Builder(
        internal val sessionTranscript: DataItem,
        private val status: Int,
        private val eReaderKey: EcPublicKey? = null,
        private val version: String? = null,
    ) {
        internal val documents = mutableListOf<MdocDocument>()
        internal val zkDocuments = mutableListOf<ZkDocument>()
        internal val encryptedDocuments = mutableListOf<EncryptedDocuments>()
        internal val otherDocuments = mutableListOf<OtherDocument>()
        internal val documentErrors = mutableListOf<Map<String, Int>>()

        /**
         * Low-level function to add a [MdocDocument] to the response.
         *
         * @param document the [MdocDocument] to add to the response.
         * @return the builder.
         */
        fun addDocument(document: MdocDocument) = apply {
            documents.add(document)
        }

        /**
         * Low-level function to add a [MdocDocument] to the response.
         *
         * @param docType the type of the document, e.g. "org.iso.18013.5.1.mDL".
         * @param issuerAuth the issuer-signed MSO.
         * @param issuerNamespaces the issuer-signed data elements to return.
         * @param deviceNamespaces the device-signed data elements to return.
         * @param deviceKey a [AsymmetricKey] used to generate a signature or MAC.
         * @param errors the errors to return.
         * @param docRequestIds optional list of `DocRequest` indices satisfied by this document.
         * @return the builder.
         */
        suspend fun addDocument(
            docType: String,
            issuerAuth: CoseSign1,
            issuerNamespaces: IssuerNamespaces,
            deviceNamespaces: DeviceNamespaces,
            deviceKey: AsymmetricKey,
            errors: Map<String, Map<String, Int>> = emptyMap(),
            docRequestIds: List<Int>? = null,
        ) = apply {
            documents.add(MdocDocument.fromNamespaces(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                docType = docType,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNamespaces,
                deviceNamespaces = deviceNamespaces,
                deviceKey = deviceKey,
                errors = errors,
                docRequestIds = docRequestIds,
            ))
        }

        /**
         * Adds an [MdocCredential] to the response.
         *
         * @param credential the [MdocCredential] to return
         * @param requestedClaims the claims in [credential] to return.
         * @param deviceNamespaces additional device-signed claims to return.
         * @param errors the errors to return.
         * @param docRequestIds optional list of `DocRequest` indices satisfied by this document.
         * @return the builder.
         */
        suspend fun addDocument(
            credential: MdocCredential,
            requestedClaims: List<MdocRequestedClaim>,
            deviceNamespaces: DeviceNamespaces = buildDeviceNamespaces {},
            errors: Map<String, Map<String, Int>> = emptyMap(),
            docRequestIds: List<Int>? = null,
        ) = apply {
            documents.add(MdocDocument.fromPresentment(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                credential = credential,
                requestedClaims = requestedClaims,
                deviceNamespaces = deviceNamespaces,
                errors = errors,
                docRequestIds = docRequestIds,
            ))
        }

        /**
         * Adds a Zero-Knowledge Proof to the response.
         *
         * @param zkDocument the object with the Zero-Knowledge Proof and associated data.
         * @return the builder.
         */
        fun addZkDocument(zkDocument: ZkDocument) = apply {
            zkDocuments.add(zkDocument)
        }

        /**
         * Adds encrypted documents to the response.
         *
         * @param encryptedDocuments an [EncryptedDocuments] structure.
         * @return the builder.
         */
        fun addEncryptedDocuments(encryptedDocuments: EncryptedDocuments) = apply {
            this.encryptedDocuments.add(encryptedDocuments)
        }

        /**
         * Add encrypted documents to a [DeviceResponse] being built.
         *
         * @param encryptionParameters the parameters to use including the recipient key.
         * @param docRequestIds the `DocRequest` indices satisfied by the encrypted documents.
         * @param builderAction the builder action.
         */
        suspend fun addEncryptedDocuments(
            encryptionParameters: EncryptionParameters,
            docRequestIds: List<Int>,
            builderAction: suspend EncryptedDocuments.Builder.() -> Unit
        ) {
            val builder = EncryptedDocuments.Builder(
                sessionTranscript = sessionTranscript,
                encryptionParameters = encryptionParameters,
                docRequestIds = docRequestIds
            )
            builder.builderAction()
            addEncryptedDocuments(builder.build())
        }

        /**
         * Adds a [OtherDocument] to the response.
         *
         * @param otherDocument an [OtherDocument].
         * @return the builder.
         */
        fun addOtherDocument(
            otherDocument: OtherDocument
        ) = apply {
            this.otherDocuments.add(otherDocument)
        }

        /**
         * Adds errors to the response.
         *
         * @param documentError A map from docType to error codes.
         * @return the builder.
         */
        fun addDocumentError(
            documentError: Map<String, Int>
        ) = apply {
            documentErrors.add(documentError)
        }

        /**
         * Builds the [DeviceResponse].
         *
         * @return a [DeviceResponse] object.
         */
        fun build(): DeviceResponse {
            val versionToUse = version ?: "1.0"

            val deviceResponse = DeviceResponse(
                version = versionToUse,
                status = status,
                documents_ = documents,
                zkDocuments = zkDocuments,
                encryptedDocuments = encryptedDocuments,
                otherDocuments = otherDocuments,
                documentErrors = documentErrors,
            )
            return deviceResponse
        }
    }
}

/**
 * Builds a [DeviceResponse].
 *
 * @param sessionTranscript the session transcript to use.
 * @param status the status to use in the response, for example [STATUS_OK].
 * @param eReaderKey the ephemeral reader key or `null` if not using session encryption.
 * @param version the version to use or `null` to automatically select the version.
 * @param builderAction the builder action.
 * @return a [DeviceResponse].
 */
inline fun buildDeviceResponse(
    sessionTranscript: DataItem,
    status: Int,
    eReaderKey: EcPublicKey? = null,
    version: String? = null,
    builderAction: DeviceResponse.Builder.() -> Unit
): DeviceResponse {
    val builder = DeviceResponse.Builder(
        sessionTranscript = sessionTranscript,
        status = status,
        eReaderKey = eReaderKey,
        version = version
    )
    builder.builderAction()
    return builder.build()
}
