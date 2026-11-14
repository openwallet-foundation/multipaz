package org.multipaz.mdoc.response

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Hpke
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DocRequest
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.util.asDocRequestIds
import org.multipaz.mdoc.util.validateDocRequestIds
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.presentment.TransactionData
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.zlibInflate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A structure for holding encrypted documents returned in a [DeviceResponse].
 *
 * @property enc the encapsulated key.
 * @property ciphertext the ciphertext.
 * @property docRequestIds the `DocRequest` indices in `DeviceRequest.docRequests` satisfied by
 *   the encrypted documents.
 */
@ConsistentCopyVisibility
data class EncryptedDocuments internal constructor(
    val enc: ByteString,
    val ciphertext: ByteString,
    val docRequestIds: List<Int>,
) {
    init {
        validateDocRequestIds(docRequestIds)
    }

    internal fun toDataItem() = buildCborMap {
        put("enc", enc.toByteArray())
        put("cipherText", ciphertext.toByteArray())
        putCborArray("docRequestIds") {
            docRequestIds.forEach { add(it) }
        }
    }

    /**
     * Decrypts the encrypted documents.
     *
     * After decryption, the same verification checks as in [DeviceResponse.verify] are performed to verify the
     * integrity of the returned documents.
     *
     * @param recipientPrivateKey the private key used for decryption.
     * @param encryptionParameters the same [EncryptionParameters] as transferred in the [DeviceRequest].
     * @param sessionTranscript the session transcript.
     * @param transactionDataList list of transaction for each document in this [EncryptedDocuments]
     * @param atTime the point in time for validating whether returned documents are valid.
     * @param deviceRequest the [DeviceRequest] to which this response is given; required when
     *   decrypting `dc+sd-jwt` documents in [EncryptedDocumentsPlaintext.otherDocuments].
     * @param documentTypeRepository optional [DocumentTypeRepository] used to resolve verifier-approved
     *   `dc+sd-jwt` `vct` aliases via [org.multipaz.documenttype.JsonDocumentType.otherVcts].
     * @return a [EncryptedDocumentsPlaintext].
     */
    suspend fun decrypt(
        recipientPrivateKey: AsymmetricKey,
        encryptionParameters: EncryptionParameters,
        sessionTranscript: DataItem,
        transactionDataList: List<List<TransactionData<*>>> = emptyList(),
        atTime: Instant = Clock.System.now(),
        deviceRequest: DeviceRequest? = null,
        documentTypeRepository: DocumentTypeRepository? = null,
    ): EncryptedDocumentsPlaintext {
        if (docRequestIds.distinct().size != docRequestIds.size) {
            throw IllegalStateException("Duplicate docRequestIds $docRequestIds in EncryptedDocuments")
        }
        val encSessionTranscript = buildCborArray {
            add(sessionTranscript.asArray[0])
            add(Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(encryptionParameters.dataItem))
            ))
            add(sessionTranscript.asArray[2])
        }
        val decrypter = Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = recipientPrivateKey,
            encapsulatedKey = enc.toByteArray(),
            info = Cbor.encode(encSessionTranscript)
        )
        val plaintext = decrypter.decrypt(
            ciphertext = ciphertext.toByteArray(),
            aad = byteArrayOf()
        )
        val encDocsPt = EncryptedDocumentsPlaintext.fromDataItem(Cbor.decode(plaintext))

        if (deviceRequest != null) {
            val hasAnyDocResponseEncryption = deviceRequest.docRequests.any {
                it.docRequestInfo?.docResponseEncryption != null
            }
            for (reqId in docRequestIds) {
                val req = deviceRequest.docRequests.getOrNull(reqId)
                    ?: throw IllegalStateException("Invalid docRequestId $reqId for DeviceRequest")
                if (hasAnyDocResponseEncryption &&
                    req.docRequestInfo?.docResponseEncryption != encryptionParameters
                ) {
                    throw IllegalStateException("EncryptionParameters do not match DocRequest at index $reqId")
                }
            }
        }

        encDocsPt.documents.forEachIndexed { index, document ->
            try {
                val matchedReqIds = if (deviceRequest != null) {
                    val innerIds = document.docRequestIds
                    if (innerIds != null) {
                        validateInnerDocRequestIds(innerIds)
                        for (reqId in innerIds) {
                            validateMdocAgainstDocRequest(document, reqId, deviceRequest.docRequests[reqId])
                        }
                        innerIds
                    } else if (docRequestIds.size == 1) {
                        val reqId = docRequestIds.single()
                        validateMdocAgainstDocRequest(document, reqId, deviceRequest.docRequests[reqId])
                        docRequestIds
                    } else {
                        val candidates = docRequestIds.filter { reqId ->
                            val req = deviceRequest.docRequests[reqId]
                            (req.docRequestInfo?.docFormat ?: "mso_mdoc") == "mso_mdoc" &&
                                req.docType == document.docType &&
                                matchesMdocIssuerIdentifiers(document, req)
                        }
                        if (candidates.isEmpty()) {
                            throw IllegalStateException(
                                "Cannot resolve DocRequest for document with docType '${document.docType}' in EncryptedDocuments"
                            )
                        }
                        candidates
                    }
                } else {
                    emptyList()
                }
                val transactionData = if (index < transactionDataList.size) {
                    transactionDataList[index]
                } else if (deviceRequest != null && documentTypeRepository != null && matchedReqIds.isNotEmpty()) {
                    resolveEncryptedMdocTransactions(document, matchedReqIds, deviceRequest, documentTypeRepository)
                } else {
                    emptyList()
                }
                if (deviceRequest != null && documentTypeRepository != null) {
                    validateSignedMdocTransactionDocRequestIds(
                        document = document,
                        allowedIds = document.docRequestIds ?: matchedReqIds,
                        deviceRequest = deviceRequest,
                        documentTypeRepository = documentTypeRepository
                    )
                }
                document.verify(encSessionTranscript, null, transactionData, atTime)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw IllegalStateException("Error verifying document $index in decrypted DeviceResponse", e)
            }
        }

        if (deviceRequest != null) {
            encDocsPt.zkDocuments.forEachIndexed { index, zkDocument ->
                try {
                    val innerIds = zkDocument.docRequestIds
                    if (innerIds != null) {
                        validateInnerDocRequestIds(innerIds)
                        for (reqId in innerIds) {
                            validateZkDocAgainstDocRequest(zkDocument, reqId, deviceRequest.docRequests[reqId])
                        }
                    } else {
                        val candidates = docRequestIds.filter { reqId ->
                            val req = deviceRequest.docRequests[reqId]
                            req.docType == zkDocument.documentData.docType &&
                                (req.docRequestInfo?.docFormat ?: "mso_mdoc") in listOf("mso_mdoc", "mso_mdoc_zk") &&
                                req.docRequestInfo?.zkRequest != null &&
                                req.docRequestInfo.zkRequest.systemSpecs.any {
                                    it.id == zkDocument.documentData.zkSystemSpecId
                                }
                        }
                        if (candidates.isEmpty()) {
                            throw IllegalStateException(
                                "Cannot resolve DocRequest with matching zkRequest for ZkDocument with docType '${zkDocument.documentData.docType}'"
                            )
                        }
                        if (candidates.all { reqId ->
                                val req = deviceRequest.docRequests[reqId]
                                req.hasTransactionData() && req.docRequestInfo?.alternativeDataElements.isNullOrEmpty()
                            }
                        ) {
                            throw IllegalStateException("Transaction data is not supported with ZkDocument")
                        }
                        val issuerMatching = candidates.filter { reqId ->
                            matchesZkIssuerIdentifiers(zkDocument, deviceRequest.docRequests[reqId])
                        }
                        if (issuerMatching.isEmpty()) {
                            throw IllegalStateException(
                                "ZkDocument certificate chain does not match requested issuerIdentifiers for docType '${zkDocument.documentData.docType}'"
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw IllegalStateException("Error verifying zkDocument $index in decrypted DeviceResponse", e)
                }
            }
        }

        encDocsPt.otherDocuments.forEachIndexed { index, document ->
            try {
                var matchedReqIds: List<Int> = emptyList()
                val expectedAudience = if (deviceRequest != null && document.docFormat == "dc+sd-jwt") {
                    val sdJwtKb = SdJwtKb.fromCompactSerialization(
                        document.data.toByteArray().zlibInflate().decodeToString()
                    )
                    val vct = (sdJwtKb.sdJwt.jwtBody["vct"] as? JsonPrimitive)?.content
                        ?: throw IllegalStateException("OtherDocument is missing 'vct' claim")
                    val innerIds = document.docRequestIds
                    val targetIds = if (innerIds != null) {
                        validateInnerDocRequestIds(innerIds)
                        for (reqId in innerIds) {
                            validateSdJwtAgainstDocRequest(
                                sdJwtKb = sdJwtKb,
                                vct = vct,
                                reqId = reqId,
                                docRequest = deviceRequest.docRequests[reqId],
                                deviceRequest = deviceRequest,
                                documentTypeRepository = documentTypeRepository
                            )
                        }
                        innerIds
                    } else if (docRequestIds.size == 1) {
                        val reqId = docRequestIds.single()
                        validateSdJwtAgainstDocRequest(
                            sdJwtKb = sdJwtKb,
                            vct = vct,
                            reqId = reqId,
                            docRequest = deviceRequest.docRequests[reqId],
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                        docRequestIds
                    } else {
                        val candidates = docRequestIds.filter { reqId ->
                            val req = deviceRequest.docRequests[reqId]
                            req.docRequestInfo?.docFormat == "dc+sd-jwt" &&
                                sdJwtVctMatchesDocRequest(vct, req, deviceRequest, documentTypeRepository) &&
                                matchesSdJwtIssuerIdentifiers(sdJwtKb, req)
                        }
                        if (candidates.isEmpty()) {
                            throw IllegalStateException(
                                "Cannot resolve DocRequest for OtherDocument with vct '$vct' in EncryptedDocuments"
                            )
                        }
                        candidates
                    }
                    matchedReqIds = targetIds
                    val audiences = targetIds.map { reqId ->
                        deviceRequest.getExpectedOtherDocumentAudience(deviceRequest.docRequests[reqId])
                    }.distinct()
                    if (audiences.size != 1) {
                        throw IllegalStateException(
                            "Inconsistent expected audiences across docRequestIds $targetIds"
                        )
                    }
                    if (documentTypeRepository != null) {
                        validateSignedSdJwtTransactionDocRequestIds(
                            sdJwtKb = sdJwtKb,
                            allowedIds = document.docRequestIds ?: targetIds,
                            deviceRequest = deviceRequest,
                            documentTypeRepository = documentTypeRepository
                        )
                    }
                    audiences.single()
                } else {
                    null
                }
                val transactionData = if (index < transactionDataList.size) {
                    transactionDataList[index]
                } else if (deviceRequest != null && documentTypeRepository != null && matchedReqIds.isNotEmpty()) {
                    resolveEncryptedSdJwtTransactions(document, matchedReqIds, deviceRequest, documentTypeRepository)
                } else {
                    emptyList()
                }
                document.verify(encSessionTranscript, null, transactionData, atTime, expectedAudience)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw IllegalStateException("Error verifying otherDocument $index in decrypted DeviceResponse", e)
            }
        }

        return encDocsPt
    }

    private fun validateInnerDocRequestIds(innerIds: List<Int>) {
        if (innerIds.distinct().size != innerIds.size) {
            throw IllegalStateException("Duplicate docRequestIds $innerIds in decrypted document")
        }
        if (!innerIds.all { it in docRequestIds }) {
            throw IllegalStateException(
                "Decrypted document docRequestIds $innerIds is not a subset of EncryptedDocuments docRequestIds $docRequestIds"
            )
        }
    }

    /**
     * A builder for [EncryptedDocuments].
     *
     * @param sessionTranscript the session transcript to use.
     * @param encryptionParameters the [EncryptionParameters] to use.
     * @param docRequestIds the `DocRequest` indices satisfied by the encrypted documents.
     */
    class Builder(
        private val sessionTranscript: DataItem,
        private val encryptionParameters: EncryptionParameters,
        private val docRequestIds: List<Int>,
    ) {
        private val encSessionTranscript = buildCborArray {
            add(sessionTranscript.asArray[0])
            add(Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(encryptionParameters.dataItem))
            ))
            add(sessionTranscript.asArray[2])
        }
        private val builder = DeviceResponse.Builder(
            sessionTranscript = encSessionTranscript,
            status = DeviceResponse.STATUS_OK,
        )

        /**
         * Low-level function to add a [MdocDocument] to an encrypted documents structure.
         *
         * @param document the [MdocDocument] to add to the encrypted documents structure.
         * @return the builder.
         */
        fun addDocument(document: MdocDocument) = builder.addDocument(document = document)

        /**
         * Low-level function to add a [MdocDocument] to an encrypted documents structure.
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
        ) = builder.addDocument(
            docType = docType,
            issuerAuth = issuerAuth,
            issuerNamespaces = issuerNamespaces,
            deviceNamespaces = deviceNamespaces,
            deviceKey = deviceKey,
            errors = errors,
            docRequestIds = docRequestIds,
        )

        /**
         * Adds an [MdocCredential] to an encrypted documents structure.
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
        ) = builder.addDocument(
            credential = credential,
            requestedClaims = requestedClaims,
            deviceNamespaces = deviceNamespaces,
            errors = errors,
            docRequestIds = docRequestIds,
        )

        /**
         * Adds a Zero-Knowledge Proof to an encrypted documents structure.
         *
         * @param zkDocument the object with the Zero-Knowledge Proof and associated data.
         * @return the builder.
         */
        fun addZkDocument(zkDocument: ZkDocument) = builder.addZkDocument(zkDocument)

        /**
         * Adds a [OtherDocument] to an encrypted documents structure.
         *
         * @param otherDocument an [OtherDocument].
         * @return the builder.
         */
        fun addOtherDocument(
            otherDocument: OtherDocument
        ) = builder.addOtherDocument(otherDocument)

        /**
         * Builds the [EncryptedDocuments] structure.
         *
         * @return a [EncryptedDocuments] structure.
         */
        suspend fun build(): EncryptedDocuments {
            val encryptedDocumentsPlaintext = EncryptedDocumentsPlaintext(
                documents = builder.documents,
                zkDocuments = builder.zkDocuments,
                otherDocuments = builder.otherDocuments,
            )

            val encrypter = Hpke.getEncrypter(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                receiverPublicKey = encryptionParameters.recipientPublicKey,
                info = Cbor.encode(encSessionTranscript),
            )
            val ciphertext = encrypter.encrypt(
                plaintext = Cbor.encode(encryptedDocumentsPlaintext.toDataItem()),
                aad = byteArrayOf()
            )
            return EncryptedDocuments(
                enc = encrypter.encapsulatedKey,
                ciphertext = ByteString(ciphertext),
                docRequestIds = docRequestIds
            )
        }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): EncryptedDocuments {
            val encItem = dataItem.getOrNull("enc")
                ?: throw IllegalArgumentException("Missing 'enc' in EncryptedDocuments")
            val cipherTextItem = dataItem.getOrNull("cipherText")
                ?: throw IllegalArgumentException("Missing 'cipherText' in EncryptedDocuments")
            val docRequestIdsItem = dataItem.getOrNull("docRequestIds")
                ?: throw IllegalArgumentException("Missing 'docRequestIds' in EncryptedDocuments")
            return EncryptedDocuments(
                enc = ByteString(encItem.asBstr),
                ciphertext = ByteString(cipherTextItem.asBstr),
                docRequestIds = docRequestIdsItem.asDocRequestIds()
            )
        }
    }
}

internal fun matchesMdocIssuerIdentifiers(
    document: MdocDocument,
    docRequest: DocRequest
): Boolean {
    val reqIssuerIds = docRequest.docRequestInfo?.issuerIdentifiers
    if (reqIssuerIds.isNullOrEmpty()) return true
    val requiredAkis = reqIssuerIds.toSet()
    return document.issuerCertChain.certificates.any { cert ->
        cert.authorityKeyIdentifier?.let { requiredAkis.contains(ByteString(it)) } ?: false
    }
}

internal fun validateMdocAgainstDocRequest(
    document: MdocDocument,
    reqId: Int,
    docRequest: DocRequest
) {
    if ((docRequest.docRequestInfo?.docFormat ?: "mso_mdoc") != "mso_mdoc") {
        throw IllegalStateException("DocRequest at index $reqId is not an mso_mdoc request")
    }
    if (document.docType != docRequest.docType) {
        throw IllegalStateException(
            "Document docType '${document.docType}' does not match DocRequest docType '${docRequest.docType}'"
        )
    }
    if (!matchesMdocIssuerIdentifiers(document, docRequest)) {
        throw IllegalStateException(
            "Document certificate chain does not match requested issuerIdentifiers for docType '${document.docType}'"
        )
    }
}

internal fun matchesZkIssuerIdentifiers(
    zkDocument: ZkDocument,
    docRequest: DocRequest
): Boolean {
    val reqIssuerIds = docRequest.docRequestInfo?.issuerIdentifiers
    if (reqIssuerIds.isNullOrEmpty()) return true
    val requiredAkis = reqIssuerIds.toSet()
    return zkDocument.documentData.msoX5chain?.certificates?.any { cert ->
        cert.authorityKeyIdentifier?.let { requiredAkis.contains(ByteString(it)) } ?: false
    } == true
}

internal fun validateZkDocAgainstDocRequest(
    zkDocument: ZkDocument,
    reqId: Int,
    docRequest: DocRequest
) {
    if (docRequest.docType != zkDocument.documentData.docType ||
        (docRequest.docRequestInfo?.docFormat ?: "mso_mdoc") !in listOf("mso_mdoc", "mso_mdoc_zk") ||
        docRequest.docRequestInfo?.zkRequest == null ||
        docRequest.docRequestInfo.zkRequest.systemSpecs.none { it.id == zkDocument.documentData.zkSystemSpecId }
    ) {
        throw IllegalStateException(
            "Cannot resolve DocRequest at index $reqId with matching zkRequest for ZkDocument with docType '${zkDocument.documentData.docType}'"
        )
    }
    if (docRequest.hasTransactionData() && docRequest.docRequestInfo.alternativeDataElements.isEmpty()) {
        throw IllegalStateException("Transaction data is not supported with ZkDocument")
    }
    if (!matchesZkIssuerIdentifiers(zkDocument, docRequest)) {
        throw IllegalStateException(
            "ZkDocument certificate chain does not match requested issuerIdentifiers for docType '${zkDocument.documentData.docType}'"
        )
    }
}

internal fun sdJwtVctMatchesDocRequest(
    vct: String,
    docRequest: DocRequest,
    deviceRequest: DeviceRequest,
    documentTypeRepository: DocumentTypeRepository?
): Boolean {
    if (vct == docRequest.docType) return true
    return documentTypeRepository?.vctMatches(vct, docRequest.docType) == true &&
        deviceRequest.docRequests.none {
            it.docRequestInfo?.docFormat == "dc+sd-jwt" && it.docType == vct
        }
}

internal fun matchesSdJwtIssuerIdentifiers(
    sdJwtKb: SdJwtKb,
    docRequest: DocRequest
): Boolean {
    val reqIssuerIds = docRequest.docRequestInfo?.issuerIdentifiers
    if (reqIssuerIds.isNullOrEmpty()) return true
    val requiredAkis = reqIssuerIds.toSet()
    return sdJwtKb.sdJwt.x5c?.certificates?.any { cert ->
        cert.authorityKeyIdentifier?.let { requiredAkis.contains(ByteString(it)) } ?: false
    } == true
}

internal fun validateSdJwtAgainstDocRequest(
    sdJwtKb: SdJwtKb,
    vct: String,
    reqId: Int,
    docRequest: DocRequest,
    deviceRequest: DeviceRequest,
    documentTypeRepository: DocumentTypeRepository?
) {
    if (docRequest.docRequestInfo?.docFormat != "dc+sd-jwt") {
        throw IllegalStateException("DocRequest at index $reqId is not a dc+sd-jwt request")
    }
    if (!sdJwtVctMatchesDocRequest(vct, docRequest, deviceRequest, documentTypeRepository)) {
        throw IllegalStateException(
            "OtherDocument vct '$vct' does not match DocRequest docType '${docRequest.docType}'"
        )
    }
    if (!matchesSdJwtIssuerIdentifiers(sdJwtKb, docRequest)) {
        throw IllegalStateException(
            "OtherDocument certificate chain does not match requested issuerIdentifiers"
        )
    }
}

internal fun validateSignedMdocTransactionDocRequestIds(
    document: MdocDocument,
    allowedIds: List<Int>,
    deviceRequest: DeviceRequest,
    documentTypeRepository: DocumentTypeRepository
) {
    val txMap = document.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE] ?: return
    for (transactionType in documentTypeRepository.transactionTypes) {
        val transactionItem = txMap[transactionType.identifier] ?: continue
        val transactionDocRequestId = transactionItem.getOrNull("docRequestId") as? Uint
            ?: throw IllegalStateException(
                "'docRequestId' is missing or invalid for transaction '${transactionType.identifier}'"
            )
        val id = transactionDocRequestId.value.toInt()
        if (transactionDocRequestId.value != id.toULong() || id !in deviceRequest.docRequests.indices) {
            throw IllegalStateException("Invalid 'docRequestId' value ${transactionDocRequestId.value}")
        }
        if (id !in allowedIds) {
            throw IllegalStateException(
                "Signed transaction 'docRequestId' $id is not in document docRequestIds $allowedIds"
            )
        }
        val requestedTxIds = deviceRequest.docRequests[id].getTransactionData(documentTypeRepository)
            .map { it.type.identifier }
            .toSet()
        if (transactionType.identifier !in requestedTxIds) {
            throw IllegalStateException("Transaction '${transactionType.identifier}' was not requested in DocRequest $id")
        }
    }
}

internal fun resolveEncryptedMdocTransactions(
    document: MdocDocument,
    reqIds: List<Int>,
    deviceRequest: DeviceRequest,
    documentTypeRepository: DocumentTypeRepository
): List<TransactionData<*>> {
    val returnedTxMap = document.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE]
    val returnedTxIdentifiers = returnedTxMap?.keys
    val result = mutableListOf<TransactionData<*>>()
    for (reqId in reqIds) {
        val req = deviceRequest.docRequests[reqId]
        val allTx = req.getTransactionData(documentTypeRepository)
        val requiredTx = if (req.docRequestInfo?.alternativeDataElements?.isNotEmpty() == true) {
            if (returnedTxIdentifiers != null) {
                allTx.filter { returnedTxIdentifiers.contains(it.type.identifier) }
            } else {
                emptyList()
            }
        } else {
            allTx
        }
        for (tx in requiredTx) {
            val responseItem = returnedTxMap?.get(tx.type.identifier)
            val signedId = (responseItem?.getOrNull("docRequestId") as? Uint)?.value
            if (signedId != null && signedId != reqId.toULong()) {
                throw IllegalStateException(
                    "Transaction '${tx.type.identifier}' response docRequestId $signedId does not match DocRequest $reqId"
                )
            }
            result.add(tx)
        }
    }
    return result
}

internal fun validateSignedSdJwtTransactionDocRequestIds(
    sdJwtKb: SdJwtKb,
    allowedIds: List<Int>,
    deviceRequest: DeviceRequest,
    documentTypeRepository: DocumentTypeRepository
) {
    val sdJwtBody = sdJwtKb.jwtBody
    for (transactionType in documentTypeRepository.transactionTypes) {
        val transactionResponse = sdJwtBody[transactionType.kbJwtResponseClaimName] ?: continue
        val transactionDocRequestId =
            ((transactionResponse as? JsonObject)?.get("doc_request_id") as? JsonPrimitive)?.intOrNull
                ?: throw IllegalStateException(
                    "'doc_request_id' is missing or invalid for transaction '${transactionType.identifier}'"
                )
        if (transactionDocRequestId !in deviceRequest.docRequests.indices) {
            throw IllegalStateException("Invalid 'doc_request_id' value $transactionDocRequestId")
        }
        if (transactionDocRequestId !in allowedIds) {
            throw IllegalStateException(
                "Signed transaction 'doc_request_id' $transactionDocRequestId is not in document docRequestIds $allowedIds"
            )
        }
        val requestedTxIds = deviceRequest.docRequests[transactionDocRequestId]
            .getTransactionData(documentTypeRepository)
            .map { it.type.identifier }
            .toSet()
        if (transactionType.identifier !in requestedTxIds) {
            throw IllegalStateException(
                "Transaction '${transactionType.identifier}' was not requested in DocRequest $transactionDocRequestId"
            )
        }
    }
}

internal suspend fun resolveEncryptedSdJwtTransactions(
    otherDocument: OtherDocument,
    reqIds: List<Int>,
    deviceRequest: DeviceRequest,
    documentTypeRepository: DocumentTypeRepository
): List<TransactionData<*>> {
    val sdJwtKb = try {
        SdJwtKb.fromCompactSerialization(otherDocument.data.toByteArray().zlibInflate().decodeToString())
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
    val result = mutableListOf<TransactionData<*>>()
    for (reqId in reqIds) {
        val req = deviceRequest.docRequests[reqId]
        val allTx = req.getTransactionData(documentTypeRepository)
        val requiredTx = if (req.docRequestInfo?.alternativeDataElements?.isNotEmpty() == true && sdJwtKb != null) {
            allTx.filter { sdJwtKb.jwtBody.containsKey(it.type.kbJwtResponseClaimName) }
        } else {
            allTx
        }
        for (tx in requiredTx) {
            val signedId = ((sdJwtKb?.jwtBody?.get(tx.type.kbJwtResponseClaimName) as? JsonObject)
                ?.get("doc_request_id") as? JsonPrimitive)?.intOrNull
            if (signedId != null && signedId != reqId) {
                throw IllegalStateException(
                    "Transaction '${tx.type.identifier}' response doc_request_id $signedId does not match DocRequest $reqId"
                )
            }
            result.add(tx)
        }
    }
    return result
}
