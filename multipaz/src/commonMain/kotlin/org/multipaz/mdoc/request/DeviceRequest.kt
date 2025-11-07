package org.multipaz.mdoc.request

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.mdocVersionCompareTo
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.SecureArea
import org.multipaz.util.Logger

/**
 * Top-level request in ISO 18013-5.
 *
 * To construct an instance use [buildDeviceRequest].
 *
 * For a request received from a remote mdoc reader use the [DeviceRequest.Companion.fromDataItem]
 * method. Note that you will need to manually call [verifyReaderAuthentication] before accessing
 * the [readerAuthAll] or [DocRequest.readerAuth] fields for instances created this way.
 *
 * @property version the version of the device request, e.g. `1.0` or `1.1`.
 * @property docRequests the document requests embedded in the request.
 * @property deviceRequestInfo a [DeviceRequestInfo] or `null`.
 * @property readerAuthAll zero or more signatures, each covering all document requests.
 */
@ConsistentCopyVisibility
data class DeviceRequest private constructor(
    val version: String,
    val docRequests: List<DocRequest>,
    val deviceRequestInfo: DeviceRequestInfo?,
    private val readerAuthAll_: List<CoseSign1>
) {
    internal var readerAuthAllVerified: Boolean = false

    /**
     * the ReaderAuthAll for the device request or empty if ReaderAuthAll is not used.
     *
     * @throws IllegalStateException if this is accessed before [verifyReaderAuthentication] is called
     *   for instances constructed ia [DeviceRequest.Companion.fromDataItem].
     */
    val readerAuthAll: List<CoseSign1>
        get() {
            if (!readerAuthAllVerified) {
                throw IllegalStateException("readerAuthAll not verified")
            }
            return readerAuthAll_
        }

    /**
     * Verifies reader authentication.
     *
     * @param sessionTranscript the session transcript to use.
     * @throws SignatureVerificationException if reader authentication fails.
     */
    fun verifyReaderAuthentication(sessionTranscript: DataItem) {
        if (readerAuthAll_.isNotEmpty()) {
            val readerAuthenticationAll = buildCborArray {
                add("ReaderAuthenticationAll")
                add(sessionTranscript)
                addCborArray {
                    docRequests.forEach { add(it.itemsRequestBytes) }
                }
            }
            val readerAuthenticationAllBytes =
                Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerAuthenticationAll))))
            readerAuthAll_.forEachIndexed { readerAuthAllIndex, readerAuthAllSignature ->
                try {
                    val certChain = (
                            readerAuthAllSignature.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                                ?: readerAuthAllSignature.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                            )!!.asX509CertChain
                    val alg = Algorithm.fromCoseAlgorithmIdentifier(
                        readerAuthAllSignature.protectedHeaders[
                            CoseNumberLabel(Cose.COSE_LABEL_ALG)
                        ]!!.asNumber.toInt()
                    )
                    Cose.coseSign1Check(
                        publicKey = certChain.certificates.first().ecPublicKey,
                        detachedData = readerAuthenticationAllBytes,
                        signature = readerAuthAllSignature,
                        signatureAlgorithm = alg
                    )
                } catch (e: Throwable) {
                    throw SignatureVerificationException(
                        message = "Error verifying ReaderAuthAll at index $readerAuthAllIndex",
                        cause = e
                    )
                }
            }
        }
        readerAuthAllVerified = true

        docRequests.forEachIndexed { docRequestIndex, docRequest ->
            if (docRequest.readerAuth_ != null) {
                try {
                    val readerAuthentication = buildCborArray {
                        add("ReaderAuthentication")
                        add(sessionTranscript)
                        add(docRequest.itemsRequestBytes)
                    }
                    val readerAuthenticationBytes =
                        Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerAuthentication))))
                    Cose.coseSign1Check(
                        publicKey = docRequest.readerAuthCertChain!!.certificates.first().ecPublicKey,
                        detachedData = readerAuthenticationBytes,
                        signature = docRequest.readerAuth_,
                        signatureAlgorithm = docRequest.readerAuthAlgorithm!!
                    )
                } catch (e: Throwable) {
                    throw SignatureVerificationException(
                        message = "Error verifying reader authentication for DocRequest at index $docRequestIndex",
                        cause = e
                    )
                }
            }
            docRequest.readerAuthVerified = true
        }
    }

    /**
     * Generates CBOR compliant with the CDDL for `DeviceRequest` according to ISO 18013-5.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        put("version", version)
        putCborArray("docRequests") {
            docRequests.forEach {
                add(it.toDataItem())
            }
        }
        deviceRequestInfo?.let {
            put("deviceRequestInfo", it.toDataItem())
        }
        if (readerAuthAll_.isNotEmpty()) {
            putCborArray("readerAuthAll") {
                readerAuthAll_.forEach {
                    add(it.toDataItem())
                }
            }
        }
    }

    companion object {
        private const val TAG = "DeviceRequest"

        /**
         * Parses CBOR compliant with the CDDL for `DeviceRequest` according to ISO 18013-5.
         */
        fun fromDataItem(dataItem: DataItem): DeviceRequest {
            val version = dataItem["version"].asTstr
            val docRequests = dataItem["docRequests"].asArray.map {
                DocRequest.fromDataItem(it)
            }
            val deviceRequestInfo = dataItem.getOrNull("deviceRequestInfo")?.let {
                if (version.mdocVersionCompareTo("1.1") >= 0) {
                    DeviceRequestInfo.fromDataItem(it)
                } else {
                    Logger.w(TAG, "Ignoring deviceRequestInfo field since version is less than 1.1")
                    null
                }
            }
            val readerAuthAll = dataItem.getOrNull("readerAuthAll")?.let {
                if (version.mdocVersionCompareTo("1.1") >= 0) {
                    it.asArray.map { elem -> elem.asCoseSign1 }
                } else {
                    Logger.w(TAG, "Ignoring readerAuthAll field since version is less than 1.1")
                    emptyList()
                }
            } ?: emptyList()
            return DeviceRequest(
                version = version,
                docRequests = docRequests,
                deviceRequestInfo = deviceRequestInfo,
                readerAuthAll_ = readerAuthAll
            )
        }
    }

    /**
     * A builder for [DeviceRequest].
     *
     * @property sessionTranscript the `SessionTranscript` CBOR.
     * @property deviceRequestInfo a [DeviceRequestInfo] or `null`.
     * @property version the version to use or `null` to automatically determine which version to use.
     */
    class Builder(
        val sessionTranscript: DataItem,
        val deviceRequestInfo: DeviceRequestInfo? = null,
        val version: String? = null,
    ) {
        private val docRequests = mutableListOf<DocRequest>()
        private val readerAuthAll = mutableListOf<CoseSign1>()
        private var usingSecondEdition = false

        /**
         * Adds a document request to the builder.
         *
         * @param docType the document type to request.
         * @param nameSpaces the namespaces, data elements, and intent-to-retain values.
         * @param docRequestInfo a [DocRequestInfo] with additional information or `null`.
         * @return the builder.
         */
        fun addDocRequest(
            docType: String,
            nameSpaces: Map<String, Map<String, Boolean>>,
            docRequestInfo: DocRequestInfo?,
        ): Builder = addDocRequestInternal(
            docType = docType,
            nameSpaces = nameSpaces,
            docRequestInfo = docRequestInfo
        )

        /**
         * Adds a document request to the builder.
         *
         * @param docType the document type to request.
         * @param nameSpaces the namespaces, data elements, and intent-to-retain values.
         * @param docRequestInfo a [DocRequestInfo] with additional information or `null`.
         * @param readerKey the key to sign with and its certificate chain
         * @return the builder.
         */
        suspend fun addDocRequest(
            docType: String,
            nameSpaces: Map<String, Map<String, Boolean>>,
            docRequestInfo: DocRequestInfo?,
            readerKey: AsymmetricKey.X509Compatible,
        ): Builder = addDocRequestInternalSuspend(
            docType = docType,
            nameSpaces = nameSpaces,
            docRequestInfo = docRequestInfo,
            signer = { dataToSign, protectedHeaders, unprotectedHeaders ->
                Cose.coseSign1Sign(
                    signingKey = readerKey,
                    message = dataToSign,
                    includeMessageInPayload = false,
                    protectedHeaders = protectedHeaders,
                    unprotectedHeaders = unprotectedHeaders,
                )
            },
            readerKeyCertificateChain = readerKey.certChain,
            signatureAlgorithm = readerKey.algorithm
        )

        private suspend fun addDocRequestInternalSuspend(
            docType: String,
            nameSpaces: Map<String, Map<String, Boolean>>,
            docRequestInfo: DocRequestInfo?,
            signer: (suspend (
                dataToSign: ByteArray,
                protectedHeaders: Map<CoseLabel, DataItem>,
                unprotectedHeaders: Map<CoseLabel, DataItem>
            ) -> CoseSign1?)?,
            readerKeyCertificateChain: X509CertChain?,
            signatureAlgorithm: Algorithm,
            ): Builder {
            check(readerAuthAll.isEmpty()) {
                "Cannot call addDocRequest() after addReaderAuthAll()"
            }
            val itemsRequest = buildCborMap {
                put("docType", docType)
                putCborMap("nameSpaces") {
                    for ((namespaceName, dataElementMap) in nameSpaces) {
                        putCborMap(namespaceName) {
                            for ((dataElementName, intentToRetain) in dataElementMap) {
                                put(dataElementName, intentToRetain)
                            }
                        }
                    }
                }
                docRequestInfo?.let {
                    val docRequestInfoDataItem = it.toDataItem()
                    if (docRequestInfoDataItem.asMap.isNotEmpty()) {
                        put("requestInfo", docRequestInfoDataItem)
                    }
                }
            }
            val itemsRequestBytes = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(itemsRequest)))
            val readerAuth = signer?.let {
                val readerAuthentication = buildCborArray {
                    add("ReaderAuthentication")
                    add(sessionTranscript)
                    add(itemsRequestBytes)
                }
                val readerAuthenticationBytes =
                    Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerAuthentication))))
                // TODO: include x5chain in protected header for v1.1?
                val protectedHeaders = mutableMapOf<CoseLabel, DataItem>()
                protectedHeaders.put(Cose.COSE_LABEL_ALG.toCoseLabel, signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem())
                val unprotectedHeaders = mutableMapOf<CoseLabel, DataItem>()
                if (readerKeyCertificateChain != null) {
                    unprotectedHeaders.put(Cose.COSE_LABEL_X5CHAIN.toCoseLabel, readerKeyCertificateChain.toDataItem())
                }
                it(readerAuthenticationBytes, protectedHeaders, unprotectedHeaders)
            }
            docRequests.add(DocRequest(
                docType = docType,
                nameSpaces = nameSpaces,
                docRequestInfo = docRequestInfo,
                readerAuth_ = readerAuth,
                itemsRequestBytes = itemsRequestBytes
            ))
            return this
        }

        private fun addDocRequestInternal(
            docType: String,
            nameSpaces: Map<String, Map<String, Boolean>>,
            docRequestInfo: DocRequestInfo?,
        ): Builder {
            check(readerAuthAll.isEmpty()) {
                "Cannot call addDocRequest() after addReaderAuthAll()"
            }
            val itemsRequest = buildCborMap {
                put("docType", docType)
                putCborMap("nameSpaces") {
                    for ((namespaceName, dataElementMap) in nameSpaces) {
                        putCborMap(namespaceName) {
                            for ((dataElementName, intentToRetain) in dataElementMap) {
                                put(dataElementName, intentToRetain)
                            }
                        }
                    }
                }
                docRequestInfo?.let {
                    val docRequestInfoDataItem = it.toDataItem()
                    if (docRequestInfoDataItem.asMap.isNotEmpty()) {
                        put("requestInfo", docRequestInfoDataItem)
                    }
                }
            }
            val itemsRequestBytes = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(itemsRequest)))
            docRequests.add(DocRequest(
                docType = docType,
                nameSpaces = nameSpaces,
                docRequestInfo = docRequestInfo,
                readerAuth_ = null,
                itemsRequestBytes = itemsRequestBytes
            ))
            return this
        }

        /**
         * Adds a signature over the entire request.
         *
         * After calling this, [addDocRequest] must not be called.
         *
         * @param readerKey the key to sign with and its certificate chain.
         * @return the builder.
         */
        suspend fun addReaderAuthAll(readerKey: AsymmetricKey.X509Compatible): Builder =
            addReaderAuthAllInternalSuspend(
                signer = { dataToSign, protectedHeaders, unprotectedHeaders ->
                    Cose.coseSign1Sign(
                        signingKey = readerKey,
                        message = dataToSign,
                        includeMessageInPayload = false,
                        protectedHeaders = protectedHeaders,
                        unprotectedHeaders = unprotectedHeaders,
                    )
                },
                readerKeyCertificateChain = readerKey.certChain,
                signatureAlgorithm = readerKey.algorithm
            )

        private suspend fun addReaderAuthAllInternalSuspend(
            signer: suspend (
                dataToSign: ByteArray,
                protectedHeaders: Map<CoseLabel, DataItem>,
                unprotectedHeaders: Map<CoseLabel, DataItem>
            ) -> CoseSign1,
            readerKeyCertificateChain: X509CertChain?,
            signatureAlgorithm: Algorithm,
        ): Builder {
            val readerAuthenticationAll = buildCborArray {
                add("ReaderAuthenticationAll")
                add(sessionTranscript)
                addCborArray {
                    docRequests.forEach {
                        add(it.itemsRequestBytes)
                    }
                }
            }
            val readerAuthenticationAllBytes =
                Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerAuthenticationAll))))
            // TODO: include x5chain in protected header for v1.1?
            val protectedHeaders = mutableMapOf<CoseLabel, DataItem>()
            protectedHeaders.put(Cose.COSE_LABEL_ALG.toCoseLabel, signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem())
            val unprotectedHeaders = mutableMapOf<CoseLabel, DataItem>()
            if (readerKeyCertificateChain != null) {
                unprotectedHeaders.put(Cose.COSE_LABEL_X5CHAIN.toCoseLabel, readerKeyCertificateChain.toDataItem())
            }
            val signature = signer(readerAuthenticationAllBytes, protectedHeaders, unprotectedHeaders)
            readerAuthAll.add(signature)
            return this
        }

        fun build(): DeviceRequest {
            val versionToUse = version ?: run {
                var docRequestsUsingSecondEditionFeature = false
                for (docRequest in docRequests) {
                    if (docRequest.docRequestInfo?.isUsingSecondEditionFeature() ?: false) {
                        docRequestsUsingSecondEditionFeature = true
                        break
                    }
                }
                if (readerAuthAll.isNotEmpty() || deviceRequestInfo != null || docRequestsUsingSecondEditionFeature) {
                    "1.1"
                } else {
                    "1.0"
                }
            }
            val deviceRequest = DeviceRequest(
                version = versionToUse,
                docRequests = docRequests,
                deviceRequestInfo = deviceRequestInfo,
                readerAuthAll_ = readerAuthAll
            )
            deviceRequest.readerAuthAllVerified = true
            deviceRequest.docRequests.forEach {
                it.readerAuthVerified = true
            }
            return deviceRequest
        }
    }
}

/**
 * Builds a [DeviceRequest].
 *
 * @param sessionTranscript the `SessionTranscript` CBOR.
 * @param deviceRequestInfo a [DeviceRequestInfo] or `null`.
 * @param version the version to use or `null` to automatically determine which version to use.
 * @param builderAction the builder action.
 * @return a [DeviceRequest].
 */
suspend fun buildDeviceRequest(
    sessionTranscript: DataItem,
    deviceRequestInfo: DeviceRequestInfo? = null,
    version: String? = null,
    builderAction: suspend DeviceRequest.Builder.() -> Unit
): DeviceRequest {
    val builder = DeviceRequest.Builder(
        version = version,
        deviceRequestInfo = deviceRequestInfo,
        sessionTranscript = sessionTranscript
    )
    builder.builderAction()
    return builder.build()
}

/**
 * Builds a [DeviceRequest].
 *
 * @param sessionTranscript the `SessionTranscript` CBOR.
 * @param deviceRequestInfo a [DeviceRequestInfo] or `null`.
 * @param version the version to use or `null` to automatically determine which version to use.
 * @param builderAction the builder action.
 * @return a [DeviceRequest].
 */
suspend fun buildDeviceRequestSuspend(
    sessionTranscript: DataItem,
    deviceRequestInfo: DeviceRequestInfo? = null,
    version: String? = null,
    builderAction: suspend DeviceRequest.Builder.() -> Unit
): DeviceRequest {
    val builder = DeviceRequest.Builder(
        version = version,
        deviceRequestInfo = deviceRequestInfo,
        sessionTranscript = sessionTranscript
    )
    builder.builderAction()
    return builder.build()
}
