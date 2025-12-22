package org.multipaz.mdoc.response

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
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
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceAuth
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.issuersigned.IssuerSignedItem
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.request.MdocRequestedClaim
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A document in a [DeviceResponse].
 *
 * @property docType the type of the document, e.g. "org.iso.18013.5.1.mDL".
 * @property issuerAuth the issuer-signed Mobile Security Object.
 * @property issuerNamespaces the issuer-signed data elements.
 * @property deviceAuth a structure with the device signed signature or MAC.
 * @property deviceNamespaces the device-signed data elements.
 * @property errors the errors in the document.
 */
data class MdocDocument(
    val docType: String,
    val issuerAuth: CoseSign1,
    val issuerNamespaces: IssuerNamespaces,
    val deviceAuth: DeviceAuth,
    val deviceNamespaces: DeviceNamespaces,
    val errors: Map<String, Map<String, Int>>,
    private val issuerNamespaceDigests: Map<String, Map<String, ByteString>>? = null
) {

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
        return true
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
        issuerAuth.unprotectedHeaders[
            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)
        ]!!.asX509CertChain
    }

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
        putCborMap("deviceSigned") {
            putCborMap("deviceAuth") {
                when (deviceAuth) {
                    is DeviceAuth.Ecdsa -> {
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
        eReaderKey: AsymmetricKey? = null,
        atTime: Instant = Clock.System.now(),
    ) {
        // First check the issuer signature..
        val issuerAuthorityCertChain =
            issuerAuth.unprotectedHeaders[
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)
            ]!!.asX509CertChain
        val signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(
            issuerAuth.protectedHeaders[
                CoseNumberLabel(Cose.COSE_LABEL_ALG)
            ]!!.asNumber.toInt()
        )
        val documentSigningKey = issuerAuthorityCertChain.certificates[0].ecPublicKey
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
        if (atTime < mso.validFrom) {
            throw IllegalStateException("MSO is not yet valid")
        }
        if (atTime > mso.validUntil) {
            throw IllegalStateException("MSO is not valid anymore")
        }

        // Check DeviceAuth
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
            is DeviceAuth.Ecdsa -> {
                try {
                    Cose.coseSign1Check(
                        publicKey = mso.deviceKey,
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
                val sharedSecret = eReaderKey.keyAgreement(mso.deviceKey)
                val sessionTranscriptBytes = Cbor.encode(Tagged(
                    Tagged.ENCODED_CBOR,
                    Bstr(Cbor.encode(sessionTranscript)))
                )
                val salt = Crypto.digest(Algorithm.SHA256, sessionTranscriptBytes)
                val info = "EMacKey".encodeToByteArray()
                val eMacKey = Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecret, salt, info, 32)
                val expectedTag = Cose.coseMac0(
                    algorithm = Algorithm.HMAC_SHA256,
                    key = eMacKey,
                    message = deviceAuthenticationBytes,
                    includeMessageInPayload = false,
                    protectedHeaders = mapOf(
                        CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.HMAC_SHA256.coseAlgorithmIdentifier!!.toDataItem()
                    ),
                    unprotectedHeaders = mapOf()
                ).tag
                if (!(expectedTag contentEquals deviceAuth.mac.tag)) {
                    throw IllegalStateException("Device authentication MAC failed to verify")
                }
            }
        }

        // Check Digests
        issuerNamespaces.data.forEach { (namespace, innerMap) ->
            val digestIds = mso.valueDigests[namespace]
                ?: throw IllegalStateException("No digests IDs for namespace $namespace")
            innerMap.forEach { (dataElementName, issuerSignedItem) ->
                require(dataElementName == issuerSignedItem.dataElementIdentifier)
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
                        val issuerSignedItem = IssuerSignedItem.fromDataItem(issuerSignedItemBytes.asTaggedEncodedCbor)
                        val digest = Crypto.digest(
                            algorithm = mso.digestAlgorithm,
                            message = Cbor.encode(issuerSignedItemBytes)
                        )
                        innerMap.put(issuerSignedItem.dataElementIdentifier, ByteString(digest))
                    }
                    issuerNamespaceDigests.put(namespaceName, innerMap)
                }
                IssuerNamespaces.fromDataItem(it)
            } ?: buildIssuerNamespaces {}

            val deviceSigned = dataItem["deviceSigned"]
            val deviceAuthDataItem = deviceSigned["deviceAuth"]
            val deviceAuth = if (deviceAuthDataItem.hasKey("deviceSignature")) {
                DeviceAuth.Ecdsa(deviceAuthDataItem["deviceSignature"].asCoseSign1)
            } else {
                DeviceAuth.Mac(deviceAuthDataItem["deviceMac"].asCoseMac0)
            }
            val deviceNamespaces = DeviceNamespaces.fromDataItem(deviceSigned["nameSpaces"].asTaggedEncodedCbor)
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
                issuerNamespaceDigests = issuerNamespaceDigests
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
            errors: Map<String, Map<String, Int>> = emptyMap()
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
                val sharedSecret = deviceKey.keyAgreement(eReaderKey)
                val sessionTranscriptBytes = Cbor.encode(
                    Tagged(Tagged.ENCODED_CBOR, Bstr(encodedSessionTranscript))
                )
                val salt = Crypto.digest(Algorithm.SHA256, sessionTranscriptBytes)
                val info = "EMacKey".encodeToByteArray()
                val eMacKey = Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecret, salt, info, 32)
                val deviceMac = Cose.coseMac0(
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
                DeviceAuth.Mac(deviceMac)
            } else {
                // Make sure we're not using fully-specified algorithms
                val coseAlg = deviceKey.algorithm.curve!!.defaultSigningAlgorithm
                val deviceSignature = Cose.coseSign1Sign(
                    signingKey = deviceKey,
                    message = deviceAuthenticationBytes,
                    includeMessageInPayload = false,
                    protectedHeaders = mapOf(
                        Cose.COSE_LABEL_ALG.toCoseLabel to coseAlg.coseAlgorithmIdentifier!!.toDataItem()
                    ),
                    unprotectedHeaders = mapOf(),
                )
                DeviceAuth.Ecdsa(deviceSignature)
            }
            return MdocDocument(
                docType = docType,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNamespaces,
                deviceAuth = deviceAuth,
                deviceNamespaces = deviceNamespaces,
                errors = errors,
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
         * @return a [MdocDocument].
         */
        suspend fun fromPresentment(
            sessionTranscript: DataItem,
            eReaderKey: EcPublicKey? = null,
            credential: MdocCredential,
            requestedClaims: List<MdocRequestedClaim>,
            deviceNamespaces: DeviceNamespaces = buildDeviceNamespaces {},
            errors: Map<String, Map<String, Int>> = emptyMap(),
        ): MdocDocument {
            val issuerAuth = credential.issuerAuth
            val filteredIssuerNamespaces = credential.issuerNamespaces.filter(requestedClaims)
            return fromNamespaces(
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
                errors = errors
            )
        }
    }
}
