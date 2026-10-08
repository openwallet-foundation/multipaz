/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.mdoc.credential

import kotlinx.io.bytestring.ByteString
import kotlin.time.Instant
import org.multipaz.asn1.OID
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborBuilder
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.cbor.Tagged
import org.multipaz.claim.MdocClaim
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mpzpass.MpzPass
import org.multipaz.mpzpass.MpzPassIsoMdoc
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.validation.ValidationResult
import org.multipaz.validation.buildValidationResult

/**
 * An mdoc credential, according to [ISO/IEC 18013-5:2021](https://www.iso.org/standard/69084.html).
 *
 * In this type, the key in [SecureAreaBoundCredential] plays the role of `DeviceKey` and the
 * issuer-signed data includes the `Mobile Security Object` which includes the authentication
 * key and is signed by the issuer. This is used for anti-cloning and to return data signed
 * by the device.
 *
 * The [issuerProvidedData] for a [MdocCredential] must be the bytes of `IssuerSigned` according
 * to ISO/IEC 18013-5:2021:
 * ```
 * IssuerSigned = {
 *   ? "nameSpaces" : IssuerNameSpaces,
 *   "issuerAuth" : IssuerAuth
 * }
 * ```
 */
class MdocCredential : SecureAreaBoundCredential {
    companion object {
        const val CREDENTIAL_TYPE: String = "MdocCredential"

        /**
         * Creates a batch of [MdocCredential] instances with keys created in a single batch operation.
         *
         * This method optimizes the key creation process by using the secure area's batch key creation
         * functionality. This can be significantly more efficient than creating keys individually,
         * especially for hardware-backed secure areas where multiple key operations might be expensive.
         *
         * @param numberOfCredentials The number of credentials to create in the batch.
         * @param document The document to add the credentials to.
         * @param domain The domain for all credentials in the batch.
         * @param secureArea The secure area to use for creating keys.
         * @param docType The docType for all credentials in the batch.
         * @param createKeySettings The settings to use for key creation, including algorithm parameters.
         * @return A pair containing:
         *   - A list of created [MdocCredential] instances, ready to be certified
         *   - An optional string containing the compact serialization of a JWS with OpenID4VCI key attestation
         *     data if supported by the secure area.
         */
        suspend fun createBatch(
            numberOfCredentials: Int,
            document: Document,
            domain: String,
            secureArea: SecureArea,
            docType: String,
            createKeySettings: CreateKeySettings
        ): Pair<List<MdocCredential>, String?> {
            val batchResult = secureArea.batchCreateKey(numberOfCredentials, createKeySettings)
            val credentials = batchResult.keyInfos
                .map { it.alias }
                .map { keyAlias ->
                    MdocCredential(
                        document = document,
                        asReplacementForIdentifier = null,
                        domain = domain,
                        secureArea = secureArea,
                        docType = docType,
                    ).apply {
                        useExistingKey(keyAlias)
                    }
                }
            return Pair(credentials, batchResult.openid4vciKeyAttestationJws)
        }

        /**
         * Create a [KeyBoundSdJwtVcCredential].
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param secureArea The [SecureArea] to use for creating a key.
         * @param docType The docType for the credential.
         * @param createKeySettings The settings to use for key creation, including algorithm parameters.
         * @return an uncertified [Credential] which has been added to [document].
         */
        suspend fun create(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            secureArea: SecureArea,
            docType: String,
            createKeySettings: CreateKeySettings
        ): MdocCredential {
            return MdocCredential(
                document,
                asReplacementForIdentifier,
                domain,
                secureArea,
                docType
            ).apply {
                generateKey(createKeySettings)
            }
        }

        /**
         * Create a [MdocCredential] using a key that already exists.
         *
         * @param document The document to add the credential to.
         * @param asReplacementForIdentifier the identifier for the [Credential] this will replace when certified.
         * @param domain The domain for the credential.
         * @param secureArea The [SecureArea] to use for creating a key.
         * @param docType The docType for the credential.
         * @param existingKeyAlias the alias for the existing key in [secureArea].
         * @return an uncertified [Credential] which has been added to [document].
         */
        suspend fun createForExistingAlias(
            document: Document,
            asReplacementForIdentifier: String?,
            domain: String,
            secureArea: SecureArea,
            docType: String,
            existingKeyAlias: String,
        ): MdocCredential {
            return MdocCredential(
                document,
                asReplacementForIdentifier,
                domain,
                secureArea,
                docType
            ).apply {
                useExistingKey(keyAlias = existingKeyAlias)
            }
        }
    }

    /**
     * Constructs a new [MdocCredential].
     *
     * [SecureAreaBoundCredential.generateKey] providing [CreateKeySettings] must be called before using
     * this object.
     *
     * @param document the document to add the credential to.
     * @param asReplacementFor the credential this credential will replace, if not null
     * @param domain the domain of the credential
     * @param secureArea the secure area for the authentication key associated with this credential.
     * @param docType the docType of the credential
     *
     * [SecureAreaBoundCredential.generateKey] must be called before using this object.
     */
    private constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String,
        secureArea: SecureArea,
        docType: String
    ) : super(document, asReplacementForIdentifier, domain, secureArea) {
        this.docType = docType
    }

    /**
     * Constructs a Credential from serialized data.
     *
     * [generateKey] providing actual serialized data must be called before using this object.
     *
     * @param document the [Document] that the credential belongs to.
     * @param dataItem the serialized data.
     */
    constructor(
        document: Document
    ) : super(document) {}

    override suspend fun deserialize(dataItem: DataItem) {
        super.deserialize(dataItem)
        docType = dataItem["docType"].asTstr
    }

    override suspend fun extractValidityFromIssuerData(): Pair<Instant, Instant> {
        return Pair(mso.validFrom, mso.validUntil)
    }

    override val credentialType: String
        get() = CREDENTIAL_TYPE

    /**
     * The docType of the credential as defined in
     * [ISO/IEC 18013-5:2021](https://www.iso.org/standard/69084.html).
     */
    lateinit var docType: String
        private set

    override fun addSerializedData(builder: MapBuilder<CborBuilder>) {
        super.addSerializedData(builder)
        builder.put("docType", docType)
    }

    /**
     * Performs deep validation of candidate issuer-provided static authentication data against this
     * credential according to ISO/IEC 18013-5:2021.
     *
     * This checks:
     * - Structure of `issuerProvidedAuthenticationData` (IssuerSigned)
     * - Validity and structure of `issuerNamespaces`
     * - Validity and structure of `mso`
     * - Validity of `issuerAuth` COSE_Sign1 signature and X.509 certificate chain
     * - Consistency between `docType` and MSO `docType`
     * - Consistency between the credential authentication key in [SecureArea] and MSO `deviceKey`
     * - Consistency between `issuerNamespaces` data elements and MSO `valueDigests`
     *
     * @param issuerProvidedAuthenticationData candidate issuer-provided static authentication data.
     * @param now reference time for checking expiration and validity intervals, or `null` to skip.
     * @return a [ValidationResult] containing any errors or warnings found.
     */
    override suspend fun validate(
        issuerProvidedAuthenticationData: ByteString,
        now: Instant?
    ): ValidationResult = buildValidationResult {
        val issuerSigned = try {
            Cbor.decode(issuerProvidedAuthenticationData.toByteArray())
        } catch (e: Throwable) {
            addError("issuerProvidedAuthenticationData is not valid CBOR: ${e.message}")
            return@buildValidationResult
        }

        if (issuerSigned !is CborMap) {
            addError("issuerProvidedAuthenticationData is not a CBOR map")
            return@buildValidationResult
        }

        if (!issuerSigned.hasKey("nameSpaces")) {
            addError("issuerProvidedAuthenticationData missing 'nameSpaces'")
        }
        if (!issuerSigned.hasKey("issuerAuth")) {
            addError("issuerProvidedAuthenticationData missing 'issuerAuth'")
        }
        val actualKeys = issuerSigned.asMap.keys.mapNotNull {
            try {
                it.asTstr
            } catch (_: Throwable) {
                null
            }
        }.toSet()
        val unexpectedKeys = actualKeys - setOf("nameSpaces", "issuerAuth")
        if (unexpectedKeys.isNotEmpty()) {
            addWarning("issuerProvidedAuthenticationData contains unexpected keys: ${unexpectedKeys.joinToString(", ")}")
        }

        var parsedIssuerNamespaces: IssuerNamespaces? = null
        if (issuerSigned.hasKey("nameSpaces")) {
            val nsDataItem = issuerSigned["nameSpaces"]
            addAll(IssuerNamespaces.validate(nsDataItem))
            try {
                parsedIssuerNamespaces = IssuerNamespaces.fromDataItem(nsDataItem)
                addAll(parsedIssuerNamespaces.validate())
            } catch (e: Throwable) {
                addError("Failed to parse IssuerNamespaces: ${e.message}")
            }
        }

        var parsedMso: MobileSecurityObject? = null
        if (issuerSigned.hasKey("issuerAuth")) {
            val authDataItem = issuerSigned["issuerAuth"]
            val coseSign1 = try {
                authDataItem.asCoseSign1
            } catch (e: Throwable) {
                addError("issuerAuth is not a valid COSE_Sign1: ${e.message}")
                null
            }
            if (coseSign1 != null) {
                val payload = coseSign1.payload
                if (payload == null) {
                    addError("issuerAuth payload is null (detached payload is not permitted in IssuerSigned)")
                } else {
                    val decodedPayload = try {
                        Cbor.decode(payload)
                    } catch (e: Throwable) {
                        addError("Failed to decode issuerAuth payload CBOR: ${e.message}")
                        null
                    }
                    if (decodedPayload != null) {
                        if (decodedPayload !is Tagged || decodedPayload.tagNumber != Tagged.ENCODED_CBOR) {
                            addError("issuerAuth payload is not tagged with CBOR tag 24")
                        } else if (decodedPayload.taggedItem !is Bstr) {
                            addError("issuerAuth payload tag 24 item is not a byte string")
                        } else {
                            val msoDataItem = try {
                                Cbor.decode(decodedPayload.taggedItem.asBstr)
                            } catch (e: Throwable) {
                                addError("Failed to decode MobileSecurityObject CBOR from issuerAuth payload: ${e.message}")
                                null
                            }
                            if (msoDataItem != null) {
                                addAll(MobileSecurityObject.validate(msoDataItem, now))
                                try {
                                    parsedMso = MobileSecurityObject.fromDataItem(msoDataItem)
                                } catch (e: Throwable) {
                                    addError("Failed to parse MobileSecurityObject from issuerAuth payload: ${e.message}")
                                }
                            }
                        }
                    }
                }

                val algLabel = CoseNumberLabel(Cose.COSE_LABEL_ALG)
                val algNumber = coseSign1.protectedHeaders[algLabel]?.let {
                    try {
                        it.asNumber.toInt()
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (algNumber == null) {
                    addError("issuerAuth missing 'alg' in protected headers")
                }
                val signatureAlgorithm = algNumber?.let {
                    try {
                        Algorithm.fromCoseAlgorithmIdentifier(it)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (algNumber != null && signatureAlgorithm == null) {
                    addError("issuerAuth has unsupported algorithm identifier: $algNumber")
                }

                val x5ChainLabel = Cose.COSE_LABEL_X5CHAIN.toCoseLabel
                val x5ChainDataItem = coseSign1.protectedHeaders[x5ChainLabel]
                    ?: coseSign1.unprotectedHeaders[x5ChainLabel]
                val certChain = if (x5ChainDataItem != null) {
                    try {
                        x5ChainDataItem.asX509CertChain
                    } catch (e: Throwable) {
                        addError("issuerAuth has invalid x5chain header: ${e.message}")
                        null
                    }
                } else {
                    addError("issuerAuth missing x5chain certificate chain")
                    null
                }

                if (certChain != null && certChain.certificates.isNotEmpty()) {
                    val dsCert = certChain.certificates[0]

                    if (dsCert.keyUsage.isEmpty()) {
                        addWarning("DS certificate in x5chain does not have Key Usage extension")
                    } else {
                        if (!dsCert.keyUsage.contains(X509KeyUsage.DIGITAL_SIGNATURE)) {
                            addError("DS certificate in x5chain does not have DIGITAL_SIGNATURE key usage")
                        }
                        if (dsCert.keyUsage.contains(X509KeyUsage.KEY_CERT_SIGN)) {
                            addWarning("DS certificate in x5chain has KEY_CERT_SIGN key usage (DS certificate should not be a CA)")
                        }
                    }

                    if (dsCert.basicConstraints?.first == true) {
                        addWarning("DS certificate in x5chain has Basic Constraints CA=true (DS certificate should not be a CA)")
                    }

                    val ekuExt = dsCert.getExtensionValue(OID.X509_EXTENSION_EXTENDED_KEY_USAGE.oid)
                    if (ekuExt == null) {
                        addWarning("DS certificate in x5chain does not have Extended Key Usage extension (expected mDL DS OID 1.0.18013.5.1.2)")
                    }

                    if (parsedMso != null) {
                        if (parsedMso.signedAt < dsCert.validityNotBefore) {
                            addError("MSO signed timestamp (${parsedMso.signedAt}) is before DS certificate validity period (notBefore: ${dsCert.validityNotBefore})")
                        }
                        if (parsedMso.signedAt > dsCert.validityNotAfter) {
                            addError("MSO signed timestamp (${parsedMso.signedAt}) is after DS certificate validity period (notAfter: ${dsCert.validityNotAfter})")
                        }
                        if (parsedMso.validUntil > dsCert.validityNotAfter) {
                            addWarning("MSO validUntil (${parsedMso.validUntil}) is after DS certificate validity period (notAfter: ${dsCert.validityNotAfter})")
                        }
                    }

                    if (now != null) {
                        if (now < dsCert.validityNotBefore) {
                            addWarning("DS certificate is not yet valid (notBefore: ${dsCert.validityNotBefore}, current time: $now)")
                        }
                        if (now > dsCert.validityNotAfter) {
                            addWarning("DS certificate is expired (notAfter: ${dsCert.validityNotAfter}, current time: $now)")
                        }
                    }

                    if (signatureAlgorithm != null) {
                        try {
                            Cose.coseSign1Check(
                                publicKey = dsCert.publicKey,
                                detachedData = null,
                                signature = coseSign1,
                                signatureAlgorithm = signatureAlgorithm
                            )
                        } catch (e: SignatureVerificationException) {
                            addError("Signature on MSO failed to verify: ${e.message}")
                        } catch (e: Throwable) {
                            addError("Error verifying signature on MSO: ${e.message}")
                        }
                    }
                }
            }
        }

        if (parsedMso != null) {
            if (docType != parsedMso.docType) {
                addError("Credential docType '$docType' does not match MSO docType '${parsedMso.docType}'")
            }

            val credKeyInfo = try {
                secureArea.getKeyInfo(alias)
            } catch (e: Throwable) {
                addError("Failed to get key info from SecureArea for alias '$alias': ${e.message}")
                null
            }
            if (credKeyInfo != null) {
                if (credKeyInfo.publicKey != parsedMso.deviceKey) {
                    addError("Credential authentication key in SecureArea does not match deviceKey in MSO (presentations will fail)")
                }
            }

            if (isCertified) {
                if (validFrom != parsedMso.validFrom) {
                    addError("Credential validFrom ($validFrom) does not match MSO validFrom (${parsedMso.validFrom})")
                }
                if (validUntil != parsedMso.validUntil) {
                    addError("Credential validUntil ($validUntil) does not match MSO validUntil (${parsedMso.validUntil})")
                }
            }

            if (parsedIssuerNamespaces != null) {
                addAll(parsedIssuerNamespaces.validateAgainstMso(parsedMso))
            }
        }
    }

    override suspend fun getClaims(
        documentTypeRepository: DocumentTypeRepository?
    ): List<MdocClaim> {
        val dt = documentTypeRepository?.getDocumentTypeForMdoc(docType)
        val namespaces = issuerSigned.getOrNull("nameSpaces")
            ?: return emptyList()
        val ret = mutableListOf<MdocClaim>()
        for ((namespaceName, innerMap) in IssuerNamespaces.fromDataItem(namespaces).data) {
            for ((dataElementName, issuerSignedItem) in innerMap) {
                val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
                val claim = MdocClaim(
                    displayName = mdocAttr?.attribute?.displayName ?: dataElementName,
                    attribute = mdocAttr?.attribute,
                    docType = docType,
                    namespaceName = namespaceName,
                    dataElementName = dataElementName,
                    value = issuerSignedItem.dataElementValue
                )
                ret.add(claim)
            }
        }
        return ret
    }

    /**
     * The `IssuerSigned` data according to ISO/IEC 18013-5:2021.
     */
    val issuerSigned: DataItem by lazy {
        Cbor.decode(issuerProvidedData.toByteArray())
    }

    /**
     * The `IssuerAuth` part of the issuer provided data.
     *
     * This contains the signed Mobile Security Object as its payload.
     */
    val issuerAuth: CoseSign1 by lazy {
        issuerSigned["issuerAuth"].asCoseSign1
    }

    /**
     * The issuer-signed data elements part of the issuer provided data.
     */
    val issuerNamespaces: IssuerNamespaces by lazy {
        IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])
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

    override suspend fun exportToMpzPass(keyUnlockData: KeyUnlockData?): MpzPass {
        check(secureArea is SoftwareSecureArea) {
            "You can only export a credential if it's using a SoftwareSecureArea"
        }
        val swSecureArea = secureArea as SoftwareSecureArea
        val keyInfo = swSecureArea.getKeyInfo(alias)
        val deviceKeyPrivate = swSecureArea.getPrivateKey(alias, keyUnlockData)
        val issuerNamespaces = IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])
        val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1
        return MpzPass(
            name = document.displayName,
            typeName = document.typeDisplayName,
            cardArt = document.cardArt,
            userAuthenticationRequired = keyInfo.isUserAuthenticationRequired,
            readerIdentifiers = document.readerIdentifiers,
            isoMdoc = listOf(MpzPassIsoMdoc(
                docType = docType,
                deviceKeyPrivate = deviceKeyPrivate as EcPrivateKey,
                issuerNamespaces = issuerNamespaces,
                issuerAuth = issuerAuth
            ))
        )
    }
}
