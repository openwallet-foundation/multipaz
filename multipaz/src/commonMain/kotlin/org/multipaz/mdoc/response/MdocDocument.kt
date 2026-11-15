package org.multipaz.mdoc.response

import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.OID
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Hkdf
import org.multipaz.crypto.SecretKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.documenttype.TransactionType
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
import org.multipaz.mdoc.credential.KeylessMdocCredential
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceAuth
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.issuersigned.IssuerSignedItem
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.util.asDocRequestIds
import org.multipaz.mdoc.util.validateDocRequestIds
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.request.MdocRequestedClaim
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A document in a [DeviceResponse].
 *
 * Note that verification of this document (via [DeviceResponse.verify]) does not check X.509 CRL/OCSP
 * revocation on [issuerCertChain] or MSO revocation via [MobileSecurityObject.revocationStatus];
 * the caller is expected to check issuer trust, certificate revocation, and MSO revocation status
 * (e.g., via `RevocationStatus.check`) separately when required.
 *
 * @property docType the type of the document, e.g. "org.iso.18013.5.1.mDL".
 * @property issuerAuth the issuer-signed Mobile Security Object.
 * @property issuerNamespaces the issuer-signed data elements.
 * @property deviceAuth a structure with the device signed signature or MAC, or `null` for a non-key-bound mdoc.
 * @property deviceNamespaces the device-signed data elements.
 * @property errors the errors in the document.
 * @property docRequestIds optional list of `DocRequest` indices in `DeviceRequest.docRequests`
 *   that this document responds to, or `null` if not specified.
 */
class MdocDocument(
    val docType: String,
    val issuerAuth: CoseSign1,
    val issuerNamespaces: IssuerNamespaces,
    val deviceAuth: DeviceAuth?,
    val deviceNamespaces: DeviceNamespaces,
    val errors: Map<String, Map<String, Int>>,
    val docRequestIds: List<Int>? = null,
    private val issuerNamespaceDigests: Map<String, Map<String, ByteString>>? = null
) {
    init {
        if (docRequestIds != null) {
            validateDocRequestIds(docRequestIds)
        }
        if (deviceAuth == null && deviceNamespaces.data.isNotEmpty()) {
            throw IllegalArgumentException("deviceNamespaces must be empty when deviceAuth is null")
        }
    }
    /**
     * Convenience property for accessing the [MobileSecurityObject] from [issuerAuth].
     */
    val mso: MobileSecurityObject by lazy {
        val encodedMobileSecurityObject = Cbor.decode(issuerAuth.payload!!).asTagged.asBstr
        MobileSecurityObject.fromDataItem(Cbor.decode(encodedMobileSecurityObject))
    }

    /**
     * Convenience property for accessing the X.509 certificate chain for the issuer signature from [issuerAuth].
     */
    val issuerCertChain: X509CertChain by lazy {
        (issuerAuth.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
            ?: issuerAuth.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel])!!.asX509CertChain
    }

    /**
     * List of verified transaction data which was sent in the request
     */
    lateinit var transactionData: List<TransactionData<*>>
    /**
     * Transaction response indexed by [TransactionType.identifier]
     */
    lateinit var transactionResponse: Map<String, Map<String, DataItem>>

    /**
     * Generates CBOR compliant with the CDDL for `Document` according to ISO 18013-5.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        put("docType", docType)
        putCborMap("issuerSigned") {
            put("issuerAuth", issuerAuth.toDataItem())
            put("nameSpaces", issuerNamespaces.toDataItem())
        }
        if (deviceAuth != null) {
            putCborMap("deviceSigned") {
                putCborMap("deviceAuth") {
                    when (deviceAuth) {
                        is DeviceAuth.Signature -> {
                            put("deviceSignature", deviceAuth.signature.toDataItem())
                        }
                        is DeviceAuth.Mac -> {
                            put("deviceMac", deviceAuth.mac.toDataItem())
                        }
                    }
                }
                put("nameSpaces", Tagged(
                    tagNumber = Tagged.ENCODED_CBOR,
                    taggedItem = Bstr(Cbor.encode(deviceNamespaces.toDataItem()))
                ))
            }
        }
        if (docRequestIds != null) {
            putCborArray("docRequestIds") {
                docRequestIds.forEach { add(it) }
            }
        }
        if (errors.isNotEmpty()) {
            putCborMap("errors") {
                errors.entries.forEach { (namespaceName, errorItems) ->
                    putCborMap(namespaceName) {
                        errorItems.entries.forEach { (dataElementName, errorCode) ->
                            put(dataElementName, errorCode)
                        }
                    }
                }
            }
        }
    }

    internal suspend fun verify(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        transactionData: List<TransactionData<*>>,
        atTime: Instant,
        rejectIfValidUntilAfterNotAfter: Boolean = false,
        requireHolderBinding: Boolean = true,
    ) {
        // First check the issuer signature..
        val issuerAuthorityCertChain = issuerCertChain
        if (issuerAuthorityCertChain.certificates.isEmpty()) {
            throw IllegalStateException("Issuer certificate chain is empty")
        }
        if (issuerAuthorityCertChain.certificates.size > 1) {
            try {
                issuerAuthorityCertChain.validate(
                    validateValidity = false,
                    validateCaValidity = false
                )
            } catch (e: Exception) {
                throw IllegalStateException("Issuer certificate chain validation failed", e)
            }
        }
        val x5tLabel = Cose.COSE_LABEL_X5T.toCoseLabel
        if (x5tLabel in issuerAuth.protectedHeaders && x5tLabel in issuerAuth.unprotectedHeaders) {
            throw IllegalStateException("COSE x5t cannot appear in both protected and unprotected headers of IssuerAuth")
        }
        (issuerAuth.protectedHeaders[x5tLabel] ?: issuerAuth.unprotectedHeaders[x5tLabel])?.let { x5tItem ->
            try {
                issuerAuthorityCertChain.certificates[0].verifyCoseX5t(x5tItem)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Invalid x5t header in IssuerAuth", e)
            }
        }
        val signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(
            issuerAuth.protectedHeaders[
                CoseNumberLabel(Cose.COSE_LABEL_ALG)
            ]!!.asNumber.toInt()
        )
        val documentSigningKey = issuerAuthorityCertChain.certificates[0].publicKey
        try {
            Cose.coseSign1Check(
                documentSigningKey,
                null,
                issuerAuth,
                signatureAlgorithm
            )
        } catch (e: SignatureVerificationException) {
            throw IllegalStateException("Signature on MSO failed to verify", e)
        }

        // Check DocType
        if (docType != mso.docType) {
            throw IllegalStateException("Mismatch between docType in document and MSO")
        }

        // Check validity
        val dsCert = issuerAuthorityCertChain.certificates[0]
        if (X509KeyUsage.DIGITAL_SIGNATURE !in dsCert.keyUsage) {
            throw IllegalStateException("DS certificate does not have digitalSignature key usage")
        }
        if (docType == "org.iso.18013.5.1.mDL" && OID.ISO_18013_5_MDL_DS.oid !in dsCert.extendedKeyUsage) {
            throw IllegalStateException("mDL DS certificate is missing mDL DS Extended Key Usage")
        }
        if (mso.signedAt < dsCert.validityNotBefore || mso.signedAt > dsCert.validityNotAfter) {
            throw IllegalStateException("MSO signed date is outside DS certificate validity period")
        }
        if (rejectIfValidUntilAfterNotAfter && mso.validUntil > dsCert.validityNotAfter) {
            throw IllegalStateException("MSO validUntil is after DS certificate validity period")
        }
        val msoValidation = mso.validate(now = null)
        if (msoValidation.hasErrors) {
            throw IllegalStateException("Invalid MSO: ${msoValidation.errors.first().message}")
        }
        if (atTime < mso.validFrom) {
            throw IllegalStateException("MSO is not yet valid")
        }
        if (atTime > mso.validUntil) {
            throw IllegalStateException("MSO is not valid anymore")
        }

        // Check DeviceAuth / Holder Binding
        val msoDeviceKey = mso.deviceKey
        if (docType == "org.iso.18013.5.1.mDL" && msoDeviceKey == null) {
            throw IllegalStateException("mDL (org.iso.18013.5.1.mDL) must be key-bound and contain deviceKeyInfo in MSO")
        }
        if (msoDeviceKey != null && deviceAuth == null) {
            throw IllegalStateException("Key-bound mdoc (MSO contains deviceKeyInfo) is missing deviceSigned")
        }
        if (msoDeviceKey == null && deviceAuth != null) {
            throw IllegalStateException("Keyless mdoc (MSO has no deviceKeyInfo) must not include deviceSigned")
        }
        if (deviceAuth == null) {
            if (requireHolderBinding) {
                throw IllegalStateException("Keyless mdoc is not permitted when nonKeyBoundSupported is not true")
            }
            if (transactionData.isNotEmpty()) {
                throw IllegalStateException("Transaction data is not supported with keyless mdoc")
            }
        } else {
            val deviceAuthentication = buildCborArray {
                add("DeviceAuthentication")
                add(sessionTranscript)
                add(docType)
                add(Tagged(
                    tagNumber = Tagged.ENCODED_CBOR,
                    taggedItem = Bstr(Cbor.encode(deviceNamespaces.toDataItem()))
                ))
            }
            val deviceAuthenticationBytes = Cbor.encode(Tagged(
                Tagged.ENCODED_CBOR,
                Bstr(Cbor.encode(deviceAuthentication))
            ))
            when (deviceAuth) {
                is DeviceAuth.Signature -> {
                    try {
                        Cose.coseSign1Check(
                            publicKey = msoDeviceKey!!,
                            detachedData = deviceAuthenticationBytes,
                            signature = deviceAuth.signature,
                            signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(
                                deviceAuth.signature.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
                            )
                        )
                    } catch (e: SignatureVerificationException) {
                        throw IllegalStateException("Device authentication signature failed to verify", e)
                    }
                }
                is DeviceAuth.Mac -> {
                    if (eReaderKey == null) {
                        throw IllegalArgumentException("Device authentication is MAC but eReaderKey was not set")
                    }
                    val ecMsoDeviceKey = msoDeviceKey as? EcPublicKey
                        ?: throw IllegalStateException("Device authentication MAC requires an EC deviceKey")
                    val sessionTranscriptBytes = Cbor.encode(Tagged(
                        Tagged.ENCODED_CBOR,
                        Bstr(Cbor.encode(sessionTranscript)))
                    )
                    val salt = Crypto.digest(Algorithm.SHA256, sessionTranscriptBytes)
                    val info = "EMacKey".encodeToByteArray()
                    val expectedTag = eReaderKey.keyAgreement(ecMsoDeviceKey).use { sharedSecretKey ->
                        Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecretKey, salt, info, 32).use { eMacKey ->
                            Cose.coseMac0(
                                algorithm = Algorithm.HMAC_SHA256,
                                key = eMacKey,
                                message = deviceAuthenticationBytes,
                                includeMessageInPayload = false,
                                protectedHeaders = mapOf(
                                    CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.HMAC_SHA256.coseAlgorithmIdentifier!!.toDataItem()
                                ),
                                unprotectedHeaders = mapOf()
                            ).tag
                        }
                    }
                    if (!(expectedTag contentEquals deviceAuth.mac.tag)) {
                        throw IllegalStateException("Device authentication MAC failed to verify")
                    }
                }
            }
        }

        // Check Digests
        issuerNamespaces.data.forEach { (namespace, innerMap) ->
            val digestIds = mso.valueDigests[namespace]
                ?: throw IllegalStateException("No digests IDs for namespace $namespace")
            innerMap.forEach { (dataElementName, issuerSignedItem) ->
                require(dataElementName == issuerSignedItem.dataElementIdentifier)
                if (issuerSignedItem.random.size < 16) {
                    throw IllegalStateException(
                        "IssuerSignedItem random for $dataElementName in $namespace must be at least 16 bytes"
                    )
                }
                if (issuerSignedItem.digestId !in 0..0x7fff_ffffL) {
                    throw IllegalStateException(
                        "IssuerSignedItem digestID for $dataElementName in $namespace out of range"
                    )
                }
                // Note: If this instance was created via fromDataItem() use the digest from there.
                val digest = if (issuerNamespaceDigests != null) {
                    issuerNamespaceDigests[namespace]!![dataElementName]!!
                } else {
                    issuerSignedItem.calculateDigest(mso.digestAlgorithm)
                }
                val expectedDigest = digestIds[issuerSignedItem.digestId]
                    ?: throw IllegalStateException("No digests ID for data element $dataElementName in $namespace")
                if (expectedDigest != digest) {
                    throw IllegalStateException("Digest mismatch for data element $dataElementName in $namespace")
                }
            }
        }

        // Check DeviceSigned key authorizations
        for ((namespaceName, innerMap) in deviceNamespaces.data) {
            for ((dataElementName, _) in innerMap) {
                val authorizedByNamespace = mso.deviceKeyAuthorizedNamespaces.contains(namespaceName)
                val authorizedByElement = mso.deviceKeyAuthorizedDataElements[namespaceName]?.contains(dataElementName) == true
                if (!authorizedByNamespace && !authorizedByElement) {
                    throw IllegalStateException(
                        "Device-signed data element '$dataElementName' in namespace '$namespaceName' is not authorized by MSO"
                    )
                }
            }
        }

        // Check mDL-specific cross-checks per ISO/IEC 18013-5 Second Edition Clauses 13.1 and 13.4.11
        if (docType == "org.iso.18013.5.1.mDL") {
            val mdlElements = issuerNamespaces.data["org.iso.18013.5.1"]
                ?.mapValues { it.value.dataElementValue }
                .orEmpty() + deviceNamespaces.data["org.iso.18013.5.1"].orEmpty()
            mdlElements["issuing_country"]?.let { countryItem ->
                val issuingCountry = countryItem.asTstr
                if (dsCert.subject.countryName == null || issuingCountry != dsCert.subject.countryName) {
                    throw IllegalStateException(
                        "mDL issuing_country '$issuingCountry' does not match DS certificate countryName '${dsCert.subject.countryName}'"
                    )
                }
            }
            mdlElements["issuing_jurisdiction"]?.let { jurisdictionItem ->
                val issuingJurisdiction = jurisdictionItem.asTstr
                val dsState = dsCert.subject.stateOrProvinceName
                if (dsState != null && issuingJurisdiction != dsState) {
                    throw IllegalStateException(
                        "mDL issuing_jurisdiction '$issuingJurisdiction' does not match DS certificate stateOrProvinceName '$dsState'"
                    )
                }
            }
            mdlElements["expiry_date_after_validuntil"]?.let { afterValidUntilItem ->
                if (afterValidUntilItem != Simple.TRUE) {
                    throw IllegalStateException(
                        "mDL expiry_date_after_validuntil must be encoded as CBOR Simple.TRUE"
                    )
                }
                mdlElements["expiry_date"]?.let { expiryItem ->
                    if (expiryItem is Tagged && expiryItem.tagNumber == Tagged.DATE_TIME_STRING) {
                        val expiryInstant = expiryItem.asDateTimeString
                        if (expiryInstant <= mso.validUntil) {
                            throw IllegalStateException(
                                "mDL expiry_date ($expiryInstant) is not after MSO validUntil (${mso.validUntil}) despite expiry_date_after_validuntil"
                            )
                        }
                    } else if (expiryItem is Tagged && expiryItem.tagNumber == Tagged.FULL_DATE_STRING) {
                        val latestExpiryEndOfDay = expiryItem.asDateString.atStartOfDayIn(TimeZone.of("UTC-12")) + 1.days
                        if (latestExpiryEndOfDay <= mso.validUntil) {
                            throw IllegalStateException(
                                "mDL expiry_date (${expiryItem.asDateString}) is not after MSO validUntil (${mso.validUntil}) despite expiry_date_after_validuntil"
                            )
                        }
                    }
                }
            }
        }

        // Check transaction data and return transaction processing responses
        this.transactionData = transactionData
        transactionResponse = buildMap {
            for (transaction in transactionData) {
                val response: Map<String, DataItem> = when (transaction.protocol) {
                    TransactionProtocol.ISO_18013_5 -> {
                        val ns = transaction.type.getMdocResponseNamespace(TransactionProtocol.ISO_18013_5)
                        val namespaceMap = deviceNamespaces.data[ns]
                            ?: throw IllegalStateException("No transaction response namespace '$ns'")
                        val responseItem = namespaceMap[transaction.type.identifier]
                            ?: throw IllegalStateException("No transaction response for '${transaction.type.identifier}'")
                        responseItem.asMap.entries.associate { (k, v) -> Pair(k.asTstr, v) }
                    }
                    TransactionProtocol.OPENID4VP -> {
                        val ns = transaction.type.getMdocResponseNamespace(TransactionProtocol.OPENID4VP)
                        val namespaceMap = deviceNamespaces.data[ns]
                            ?: throw IllegalStateException(
                                "No transaction response for '${transaction.type.identifier}' in namespace '$ns'"
                            )
                        namespaceMap
                    }
                }
                transaction.verifyMdocResponse(response)
                put(transaction.type.identifier, response)
            }
        }
    }

    // This should only be used for testing
    // Don't include issuerNamespaceDigests in comparison
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MdocDocument) return false
        if (docType != other.docType) return false
        if (issuerAuth != other.issuerAuth) return false
        if (issuerNamespaces != other.issuerNamespaces) return false
        if (deviceAuth != other.deviceAuth) return false
        if (deviceNamespaces != other.deviceNamespaces) return false
        if (errors != other.errors) return false
        if (docRequestIds != other.docRequestIds) return false
        return true
    }

    override fun hashCode(): Int {
        var result = docType.hashCode()
        result = 31 * result + issuerAuth.hashCode()
        result = 31 * result + issuerNamespaces.hashCode()
        result = 31 * result + (deviceAuth?.hashCode() ?: 0)
        result = 31 * result + deviceNamespaces.hashCode()
        result = 31 * result + errors.hashCode()
        result = 31 * result + (docRequestIds?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Parses CBOR compliant with the CDDL for `Document` according to ISO 18013-5.
         *
         * @param dataItem a [DataItem] containing CBOR for `Document`.
         * @return a [MdocDocument].
         */
        suspend fun fromDataItem(dataItem: DataItem): MdocDocument {
            val docType = dataItem["docType"].asTstr
            val issuerSigned = dataItem["issuerSigned"]
            val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1

            // Note: for IssuerNamespaces we calculate the digests ahead of time to avoid
            // having to save the binary representation
            //
            val issuerNamespaceDigests = mutableMapOf<String, Map<String, ByteString>>()
            val issuerNamespaces = issuerSigned.getOrNull("nameSpaces")?.let {
                val encodedMobileSecurityObject = Cbor.decode(issuerAuth.payload!!).asTagged.asBstr
                val mso = MobileSecurityObject.fromDataItem(Cbor.decode(encodedMobileSecurityObject))
                for ((namespaceDataItemKey, namespaceDataItemValue) in it.asMap) {
                    val namespaceName = namespaceDataItemKey.asTstr
                    val innerMap = mutableMapOf<String, ByteString>()
                    for (issuerSignedItemBytes in namespaceDataItemValue.asArray) {
                        val issuerSignedItem = IssuerSignedItem(issuerSignedItemBytes.asTaggedEncodedCbor)
                        val digest = Crypto.digest(
                            algorithm = mso.digestAlgorithm,
                            message = Cbor.encode(issuerSignedItemBytes)
                        )
                        innerMap[issuerSignedItem.dataElementIdentifier] = ByteString(digest)
                    }
                    issuerNamespaceDigests[namespaceName] = innerMap
                }
                IssuerNamespaces.fromDataItem(it)
            } ?: buildIssuerNamespaces {}

            val deviceSigned = dataItem.getOrNull("deviceSigned")
            val deviceAuth: DeviceAuth?
            val deviceNamespaces: DeviceNamespaces
            if (deviceSigned != null) {
                val deviceAuthDataItem = deviceSigned["deviceAuth"]
                deviceAuth = if (deviceAuthDataItem.hasKey("deviceSignature")) {
                    DeviceAuth.Signature(deviceAuthDataItem["deviceSignature"].asCoseSign1)
                } else {
                    DeviceAuth.Mac(deviceAuthDataItem["deviceMac"].asCoseMac0)
                }
                deviceNamespaces = DeviceNamespaces.fromDataItem(deviceSigned["nameSpaces"].asTaggedEncodedCbor)
            } else {
                deviceAuth = null
                deviceNamespaces = buildDeviceNamespaces {}
            }
            val docRequestIds = dataItem.getOrNull("docRequestIds")?.asDocRequestIds()
            val errors = dataItem.getOrNull("errors")?.asMap?.entries?.associate { (namespace, errorItems) ->
                namespace.asTstr to errorItems.asMap.entries.associate { (dataElementName, errorCode) ->
                    dataElementName.asTstr to errorCode.asNumber.toInt()
                }
            }
            return MdocDocument(
                docType = docType,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNamespaces,
                deviceAuth = deviceAuth,
                deviceNamespaces = deviceNamespaces,
                errors = errors ?: emptyMap(),
                docRequestIds = docRequestIds,
                issuerNamespaceDigests = issuerNamespaceDigests,
            )
        }

        /**
         * Creates a [MdocDocument] from [IssuerNamespaces] and [DeviceNamespaces].
         *
         * @param sessionTranscript the session transcript to use.
         * @param eReaderKey the ephemeral reader key or `null` if not using session encryption.
         * @param docType the type of the document, e.g. "org.iso.18013.5.1.mDL".
         * @param issuerAuth the issuer-signed MSO.
         * @param issuerNamespaces the issuer-signed data elements to return.
         * @param deviceNamespaces the device-signed data elements to return.
         * @param deviceKey a [AsymmetricKey] used to generate a signature or MAC.
         * @param errors the errors to return.
         * @param docRequestIds optional list of `DocRequest` indices satisfied by this document.
         * @return a [MdocDocument].
         */
        suspend fun fromNamespaces(
            sessionTranscript: DataItem,
            eReaderKey: EcPublicKey? = null,
            docType: String,
            issuerAuth: CoseSign1,
            issuerNamespaces: IssuerNamespaces,
            deviceNamespaces: DeviceNamespaces,
            deviceKey: AsymmetricKey,
            errors: Map<String, Map<String, Int>> = emptyMap(),
            docRequestIds: List<Int>? = null,
        ): MdocDocument {
            val deviceAuthentication = buildCborArray {
                add("DeviceAuthentication")
                add(sessionTranscript)
                add(docType)
                addTaggedEncodedCbor(Cbor.encode(deviceNamespaces.toDataItem()))
            }
            val deviceAuthenticationBytes = Cbor.encode(
                Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceAuthentication)))
            )
            val encodedSessionTranscript = Cbor.encode(sessionTranscript)
            val deviceAuth = if (deviceKey.algorithm.isKeyAgreement) {
                if (eReaderKey == null) {
                    throw IllegalStateException("Trying to add a document with MACing but eReaderKey not specified")
                }
                val sessionTranscriptBytes = Cbor.encode(
                    Tagged(Tagged.ENCODED_CBOR, Bstr(encodedSessionTranscript))
                )
                val salt = Crypto.digest(Algorithm.SHA256, sessionTranscriptBytes)
                val info = "EMacKey".encodeToByteArray()
                val deviceMac = deviceKey.keyAgreement(eReaderKey).use { sharedSecretKey ->
                    Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecretKey, salt, info, 32).use { eMacKey ->
                        Cose.coseMac0(
                            algorithm = Algorithm.HMAC_SHA256,
                            key = eMacKey,
                            message = deviceAuthenticationBytes,
                            includeMessageInPayload = false,
                            protectedHeaders = mapOf(
                                Pair(
                                    CoseNumberLabel(Cose.COSE_LABEL_ALG),
                                    Algorithm.HMAC_SHA256.coseAlgorithmIdentifier!!.toDataItem()
                                )
                            ),
                            unprotectedHeaders = mapOf()
                        )
                    }
                }
                DeviceAuth.Mac(deviceMac)
            } else {
                // Make sure we're not using fully-specified algorithms for EC curves (e.g. -7 instead of -9)
                val coseAlg = deviceKey.algorithm.curve?.defaultSigningAlgorithm ?: deviceKey.algorithm
                val deviceSignature = Cose.coseSign1Sign(
                    signingKey = deviceKey,
                    message = deviceAuthenticationBytes,
                    includeMessageInPayload = false,
                    protectedHeaders = mapOf(
                        Cose.COSE_LABEL_ALG.toCoseLabel to coseAlg.coseAlgorithmIdentifier!!.toDataItem()
                    ),
                    unprotectedHeaders = mapOf(),
                )
                DeviceAuth.Signature(deviceSignature)
            }
            return MdocDocument(
                docType = docType,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNamespaces,
                deviceAuth = deviceAuth,
                deviceNamespaces = deviceNamespaces,
                errors = errors,
                docRequestIds = docRequestIds,
                issuerNamespaceDigests = null
            )
        }

        /**
         * Creates a non-key-bound [MdocDocument] from [IssuerNamespaces] without `deviceSigned`.
         *
         * @param docType the type of the document, e.g. `"org.multipaz.movieticket.1"`.
         * @param issuerAuth the issuer-signed MSO.
         * @param issuerNamespaces the issuer-signed data elements to return.
         * @param errors the errors to return.
         * @param docRequestIds optional list of `DocRequest` indices satisfied by this document.
         * @return a [MdocDocument].
         */
        fun fromKeylessNamespaces(
            docType: String,
            issuerAuth: CoseSign1,
            issuerNamespaces: IssuerNamespaces,
            errors: Map<String, Map<String, Int>> = emptyMap(),
            docRequestIds: List<Int>? = null,
        ): MdocDocument {
            return MdocDocument(
                docType = docType,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNamespaces,
                deviceAuth = null,
                deviceNamespaces = buildDeviceNamespaces {},
                errors = errors,
                docRequestIds = docRequestIds,
                issuerNamespaceDigests = null
            )
        }

        /**
         * Creates a [MdocDocument] by presenting claims from an [MdocCredential].
         *
         * @param sessionTranscript the session transcript to use.
         * @param eReaderKey the ephemeral reader key or `null` if not using session encryption.
         * @param credential the [MdocCredential] to present.
         * @param requestedClaims the claims in [credential] to present.
         * @param deviceNamespaces additional device-signed claims to present.
         * @param errors the errors to return.
         * @param docRequestIds optional list of `DocRequest` indices satisfied by this document.
         * @param iso18013AgeOverSubstitution `true` to apply ISO/IEC 18013-5 Clause 13.4.6 nearest
         *   `age_over_NN` substitution and max-2 capping for mDL (`org.iso.18013.5.1.mDL`), `false`
         *   for exact claim path protocols such as OpenID4VP DCQL.
         * @return a [MdocDocument].
         */
        suspend fun fromPresentment(
            sessionTranscript: DataItem,
            eReaderKey: EcPublicKey? = null,
            credential: MdocCredential,
            requestedClaims: List<MdocRequestedClaim>,
            deviceNamespaces: DeviceNamespaces = buildDeviceNamespaces {},
            errors: Map<String, Map<String, Int>> = emptyMap(),
            docRequestIds: List<Int>? = null,
            iso18013AgeOverSubstitution: Boolean = true,
        ): MdocDocument {
            val issuerAuth = credential.issuerAuth
            val filteredIssuerNamespaces = credential.issuerNamespaces.filter(
                requestedClaims = requestedClaims,
                iso18013AgeOverSubstitution = iso18013AgeOverSubstitution &&
                    credential.docType == "org.iso.18013.5.1.mDL",
            )
            return when (credential) {
                is KeyBoundMdocCredential -> fromNamespaces(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = eReaderKey,
                    docType = credential.docType,
                    issuerAuth = issuerAuth,
                    issuerNamespaces = filteredIssuerNamespaces,
                    deviceNamespaces = deviceNamespaces,
                    deviceKey = AsymmetricKey.AnonymousSecureAreaBased(
                        alias = credential.alias,
                        secureArea = credential.secureArea,
                        keyInfo = credential.secureArea.getKeyInfo(credential.alias),
                        unlockReason = PresentmentUnlockReason(credential),
                    ),
                    errors = errors,
                    docRequestIds = docRequestIds,
                )
                is KeylessMdocCredential -> {
                    require(deviceNamespaces.data.isEmpty()) {
                        "Keyless mdoc credentials cannot include device-signed data elements"
                    }
                    fromKeylessNamespaces(
                        docType = credential.docType,
                        issuerAuth = issuerAuth,
                        issuerNamespaces = filteredIssuerNamespaces,
                        errors = errors,
                        docRequestIds = docRequestIds,
                    )
                }
                else -> throw IllegalArgumentException(
                    "Unsupported MdocCredential implementation: ${credential::class}"
                )
            }
        }
    }
}
