package org.multipaz.mdoc.response

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
 * @property version the version of the device response, e.g. `1.0` or `1.1`.
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
     * - The MSO is validity period includes the passed-in [atTime].
     * - The data returned in [MdocDocument.issuerNamespaces] is checked against digests in the MSO.
     * - The device-authentication structures (ECDSA or MAC) are checked.
     * - For each transaction data in the list, verifies that transaction hash is present in the
     *    response and matches the hash of the source transaction data
     *
     * The following checks are performed for each [OtherDocument] instance in [otherDocuments]:
     *  - For document format `sd-jwt+kb`:
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
     * @param deviceRequest optional request to which this response is given; optional if no
     *   transaction data was sent in the request
     * @param documentTypeRepository repository that contains all known transaction types; must
     *   be given if [deviceRequest] is given
     * @param atTime the point in time for validating the whether returned documents are valid.
     * @throws IllegalStateException if validation fails.
     */
    suspend fun verify(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey? = null,
        deviceRequest: DeviceRequest? = null,
        documentTypeRepository: DocumentTypeRepository? = null,
        atTime: Instant = Clock.System.now(),
    ) {
        numTimesVerifyCalled += 1
        documents_.forEach { document ->
            val transactionData = if (deviceRequest == null) {
                emptyList()
            } else {
                val docRequestId = if (deviceRequest.docRequests.size == 1) {
                    0
                } else {
                    findDocRequestId(documentTypeRepository, document)
                }
                if (docRequestId < 0) {
                    emptyList()
                } else {
                    deviceRequest.docRequests[docRequestId].getTransactionData(
                        documentTypeRepository!!
                    )
                }
            }
            document.verify(sessionTranscript, eReaderKey, transactionData, atTime)
        }
        otherDocuments.forEach { otherDocument ->
            val transactionData = if (deviceRequest == null) {
                emptyList()
            } else {
                val docRequestId = if (deviceRequest.docRequests.size == 1) {
                    0
                } else {
                    findDocRequestId(documentTypeRepository, otherDocument)
                }
                if (docRequestId < 0) {
                    emptyList()
                } else {
                    deviceRequest.docRequests[docRequestId].getTransactionData(
                        documentTypeRepository!!
                    )
                }
            }
            otherDocument.verify(sessionTranscript, eReaderKey, transactionData, atTime)
        }
    }

    /**
     * Variant of [verify] that is intended for use with [DeviceResponse] data embedded in
     * non-ISO/IEC-18013 verification response (such as OpenID4VP).
     *
     * [DeviceResponse] must contain a single document. Parsed transaction data is supplied
     * using [transactionData] parameter instead of [DeviceRequest].
     *
     * @param sessionTranscript the session transcript to use.
     * @param transactionData transaction data that was associated with the request
     * @param atTime the point in time for validating the whether returned documents are valid.
     * @throws IllegalStateException if validation fails.
     */
    suspend fun verifySingleDoc(
        sessionTranscript: DataItem,
        transactionData: List<TransactionData<*>>,
        atTime: Instant = Clock.System.now(),
    ) {
        if (documents_.size == 1 && zkDocuments.isEmpty()) {
            numTimesVerifyCalled += 1
            documents_.first().verify(sessionTranscript, null, transactionData, atTime)
        } else if (zkDocuments.size == 1 && documents_.isEmpty()) {
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
        documentTypeRepository: DocumentTypeRepository?,
        doc: MdocDocument
    ): Int {
        if (documentTypeRepository == null) {
            return -1
        }
        var docRequestId: ULong? = null
        val data = doc.deviceNamespaces.data
        for (transactionType in documentTypeRepository.transactionTypes) {
            val transactionResponse = data[transactionType.mdocResponseNamespace] ?: continue
            val transactionDocRequestId = transactionResponse["doc_request_id"] as? Uint
                ?: throw IllegalStateException(
                    "'doc_request_id' is missing or invalid for transaction '${transactionType.identifier}'")
            if (docRequestId == null) {
                docRequestId = transactionDocRequestId.value
            } else if(docRequestId != transactionDocRequestId.value) {
                throw IllegalStateException("inconsistent 'doc_request_id' values")
            }
        }
        return docRequestId?.toInt() ?: -1
    }

    private suspend fun findDocRequestId(
        documentTypeRepository: DocumentTypeRepository?,
        doc: OtherDocument
    ): Int {
        if (documentTypeRepository == null || doc.docFormat != "sd-jwt+kb") {
            return -1
        }
        var docRequestId: Int? = null
        val sdJwtBody =  SdJwtKb.fromCompactSerialization(
            compactSerialization = doc.data.toByteArray().zlibInflate().decodeToString()
        ).jwtBody
        for (transactionType in documentTypeRepository.transactionTypes) {
            val transactionResponse = sdJwtBody[transactionType.kbJwtResponseClaimName] ?: continue
            val transactionDocRequestId =
                ((transactionResponse as? JsonObject)?.get("doc_request_id") as? JsonPrimitive)?.intOrNull
                    ?: throw IllegalStateException(
                        "'doc_request_id' is missing or invalid for transaction '${transactionType.identifier}'")
            if (docRequestId == null) {
                docRequestId = transactionDocRequestId
            } else if(docRequestId != transactionDocRequestId) {
                throw IllegalStateException("inconsistent 'doc_request_id' values")
            }
        }
        return docRequestId ?: -1
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
         * @return the builder.
         */
        suspend fun addDocument(
            docType: String,
            issuerAuth: CoseSign1,
            issuerNamespaces: IssuerNamespaces,
            deviceNamespaces: DeviceNamespaces,
            deviceKey: AsymmetricKey,
            errors: Map<String, Map<String, Int>> = emptyMap()
        ) = apply {
            documents.add(MdocDocument.fromNamespaces(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                docType = docType,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNamespaces,
                deviceNamespaces = deviceNamespaces,
                deviceKey = deviceKey,
                errors = errors
            ))
        }

        /**
         * Adds an [MdocCredential] to the response.
         *
         * @param credential the [MdocCredential] to return
         * @param requestedClaims the claims in [credential] to return.
         * @param deviceNamespaces additional device-signed claims to return.
         * @param errors the errors to return.
         * @return the builder.
         */
        suspend fun addDocument(
            credential: MdocCredential,
            requestedClaims: List<MdocRequestedClaim>,
            deviceNamespaces: DeviceNamespaces = buildDeviceNamespaces {},
            errors: Map<String, Map<String, Int>> = emptyMap()
        ) = apply {
            documents.add(MdocDocument.fromPresentment(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                credential = credential,
                requestedClaims = requestedClaims,
                deviceNamespaces = deviceNamespaces,
                errors = errors
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
         * @param docRequestId the document request ID.
         * @param builderAction the builder action.
         */
        suspend fun addEncryptedDocuments(
            encryptionParameters: EncryptionParameters,
            docRequestId: Int,
            builderAction: suspend EncryptedDocuments.Builder.() -> Unit
        ) {
            val builder = EncryptedDocuments.Builder(
                sessionTranscript = sessionTranscript,
                encryptionParameters = encryptionParameters,
                docRequestId = docRequestId
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
            val versionToUse = version ?: if (
                zkDocuments.isNotEmpty() ||
                encryptedDocuments.isNotEmpty() ||
                otherDocuments.isNotEmpty()
            ) "1.1" else "1.0"

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
