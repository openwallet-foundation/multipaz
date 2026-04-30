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

package org.multipaz.documenttype

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlin.time.Instant
import org.multipaz.cbor.DataItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.random.Random

/**
 * Class representing the metadata of a Document Type.
 *
 * Currently ISO mdoc and JSON-based data models are supported. More document formats may be added in the future.
 *
 * A Document Type has different attributes. Each attribute has a displayName which is short (1-3 words) and suitable
 * for displaying in the UI. There is also a description which is a longer description of the attribute, typically no
 * more than one paragraph.
 *
 * @param displayName the name suitable for display, e.g. "Driving License".
 * @param cannedRequests sample [DocumentCannedRequest] for the Document Type.
 * @param mdocDocumentType metadata of a ISO mdoc Document Type (optional).
 * @param jsonDocumentType metadata of a JSON-based Document Type (optional).
 *
 */
class DocumentType private constructor(
    val displayName: String,
    val cannedRequests: List<SingleDocumentCannedRequest>,
    val mdocDocumentType: MdocDocumentType?,
    val jsonDocumentType: JsonDocumentType?
) {

    /**
     * Builder class for class [DocumentType]
     *
     * @param displayName the name suitable for display of the Document Type.
     * @param mdocBuilder a builder for the [MdocDocumentType].
     * @param jsonBuilder a builder for the [JsonDocumentType].
     */
    data class Builder(
        val displayName: String,
        var mdocBuilder: MdocDocumentType.Builder? = null,
        var jsonBuilder: JsonDocumentType.Builder? = null
    ) {
        private val sampleRequests = mutableListOf<SingleDocumentCannedRequest>()

        /**
         * Initialize the [mdocBuilder].
         *
         * @param mdocDocType the DocType of the ISO mdoc.
         */
        fun addMdocDocumentType(mdocDocType: String) = apply {
            mdocBuilder = MdocDocumentType.Builder(mdocDocType)
        }

        /**
         * Initialize the [jsonBuilder].
         *
         * @param type the document type.
         * @param keyBound whether credentials should be bound to a key residing on the device.
         */
        fun addJsonDocumentType(
            type: String,
            keyBound: Boolean,
        ) = apply {
            jsonBuilder = JsonDocumentType.Builder(type, keyBound = keyBound)
        }

        /**
         * Adds an existing namespace to this document type.
         *
         * @param namespace the existing namespace to add.
         * @return the builder.
         */
        fun addMdocNamespace(
            namespace: MdocNamespace
        ) = apply {
            mdocBuilder?.addNamespace(namespace)
        }

        /**
         * Add an attribute for both ISO mdoc and JSON-based document, using the same identifier.
         *
         * @param type the datatype of this attribute.
         * @param identifier the identifier of this attribute for both ISO mdoc and JSON-based credentials.
         * @param displayName a name suitable for display of the attribute.
         * @param description a description of the attribute.
         * @param mandatory indication whether the ISO mdoc attribute is mandatory.
         * @param mdocNamespace the namespace of the ISO mdoc attribute.
         * @param sensitivity the sensitivity of the attribute.
         * @param icon the icon, if available.
         * @param sampleValueMdoc a sample value for the attribute for ISO mdoc credentials, if available.
         * @param sampleValueJson a sample value for the attribute for JSON-based credentials, if available.
         */
        fun addAttribute(
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            mandatory: Boolean,
            mdocNamespace: String,
            sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
            icon: Icon? = null,
            sampleValueMdoc: DataItem? = null,
            sampleValueJson: JsonElement? = null,
        ) = apply {
            addMdocAttribute(
                type = type,
                identifier = identifier,
                displayName = displayName,
                description = description,
                mandatory = mandatory,
                mdocNamespace = mdocNamespace,
                sensitivity = sensitivity,
                icon = icon,
                sampleValue = sampleValueMdoc
            )
            addJsonAttribute(
                type = type,
                identifier = identifier,
                displayName = displayName,
                description = description,
                sensitivity = sensitivity,
                icon = icon,
                sampleValue = sampleValueJson
            )
        }

        /**
         * Add an attribute for both ISO mdoc and JSON-based, using a different identifier.
         *
         * @param type the datatype of this attribute.
         * @param mdocIdentifier the identifier of this attribute for ISO mdoc credentials, e.g. `age_over_18`.
         * @param jsonIdentifier the identifier of this attribute for JSON-based credentials using `.` to separate
         *   path components, e.g. `age_equal_or_over.18`.
         * @param displayName a name suitable for display of the attribute.
         * @param description a description of the attribute.
         * @param mandatory indication whether the ISO mdoc attribute is mandatory.
         * @param mdocNamespace the namespace of the ISO mdoc attribute.
         * @param sensitivity the sensitivity of the attribute.
         * @param icon the icon, if available.
         * @param sampleValueMdoc a sample value for the attribute for ISO mdoc credentials, if available.
         * @param sampleValueJson a sample value for the attribute for JSON-based credentials, if available.
         */
        fun addAttribute(
            type: DocumentAttributeType,
            mdocIdentifier: String,
            jsonIdentifier: String,
            displayName: String,
            description: String,
            mandatory: Boolean,
            mdocNamespace: String,
            sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
            icon: Icon? = null,
            sampleValueMdoc: DataItem? = null,
            sampleValueJson: JsonElement? = null,
        ) = apply {
            addMdocAttribute(
                type = type,
                identifier = mdocIdentifier,
                displayName = displayName,
                description = description,
                mandatory = mandatory,
                mdocNamespace = mdocNamespace,
                sensitivity = sensitivity,
                icon = icon,
                sampleValue = sampleValueMdoc
            )
            addJsonAttribute(
                type = type,
                identifier = jsonIdentifier,
                displayName = displayName,
                description = description,
                sensitivity = sensitivity,
                icon = icon,
                sampleValue = sampleValueJson
            )
        }

        /**
         * Add an attribute for ISO mdoc only.
         *
         * @param type the datatype of this attribute.
         * @param identifier the identifier of this attribute.
         * @param displayName a name suitable for display of the attribute.
         * @param description a description of the attribute.
         * @param mandatory indication whether the ISO mdoc attribute is mandatory.
         * @param mdocNamespace the namespace of the ISO mdoc attribute.
         * @param sensitivity the sensitivity of the attribute.
         * @param icon the icon, if available.
         * @param sampleValue a sample value for the attribute, if available.
         */
        fun addMdocAttribute(
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            mandatory: Boolean,
            mdocNamespace: String,
            sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
            icon: Icon? = null,
            sampleValue: DataItem? = null
        ) = apply {
            mdocBuilder?.addDataElement(
                namespace = mdocNamespace,
                type = type,
                identifier = identifier,
                displayName = displayName,
                description = description,
                mandatory = mandatory,
                sensitivity = sensitivity,
                icon = icon,
                sampleValue = sampleValue
            ) ?: throw Exception("The ISO mdoc Document Type was not initialized")
        }

        /**
         * Add an attribute for JSON-based only.
         *
         * @param type the datatype of this attribute.
         * @param identifier the identifier of this attribute using `.` to separate path components, e.g.
         * `age_equal_or_over.18`.
         * @param displayName a name suitable for display of the attribute.
         * @param description a description of the attribute.
         * @param sensitivity the sensitivity of the attribute.
         * @param icon the icon, if available.
         * @param sampleValue a sample value for the attribute, if available.
         */
        fun addJsonAttribute(
            type: DocumentAttributeType,
            identifier: String,
            displayName: String,
            description: String,
            sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
            icon: Icon? = null,
            sampleValue: JsonElement? = null
        ) = apply {
            val splits = identifier.split(".")
            when (splits.size ) {
                1 -> {
                    jsonBuilder?.addClaim(type, identifier, displayName, description, icon, sampleValue)
                        ?: throw Exception("The JSON Document Type was not initialized")
                }
                2 -> {
                    jsonBuilder?.addEmbeddedAttribute(
                        parentIdentifier = splits[0],
                        type = type,
                        identifier = splits[1],
                        displayName = displayName,
                        description = description,
                        sensitivity = sensitivity,
                        icon = icon,
                        sampleValue = sampleValue
                    ) ?: throw Exception("The JSON Document Type was not initialized")
                }
                else -> throw Exception("Invalid identifier $identifier, can have at max one period")
            }
        }

        /**
         * Adds a sample request to the document.
         *
         * @param id an identifier for the request.
         * @param displayName a short name explaining the request.
         * @param mdocDataElements the mdoc data elements in the request, per namespace, with the intent to retain
         *   value. If the list of a namespace is empty, all defined data elements will be included with intent to
         *   retain set to false.
         * @param mdocUseZkp `true` if the sample request should indicate a preference for use of Zero-Knowledge Proofs.
         * @param jsonClaims the claim names for JSON-based credentials in the request. If the list is empty, all
         *   defined claims will be included. Each claim name must use `.` to separate path components, e.g.
         *   `age_equal_or_over.18`.
         * @param cannedTransactionData transaction data list for the request
         */
        fun addSampleRequest(
            id: String,
            displayName: String,
            mdocDataElements: Map<String, Map<String, Boolean>>? = null,
            mdocUseZkp: Boolean = false,
            jsonClaims: List<String>? = null,
            cannedTransactionData: List<CannedTransactionData> = listOf()
        ) = apply {
            val mdocRequest = if (mdocDataElements == null) {
                null
            } else {
                val nsRequests = mutableListOf<MdocNamespaceRequest>()
                for ((namespace, dataElements) in mdocDataElements) {
                    val mdocNsBuilder = mdocBuilder!!.namespaces[namespace]!!
                    val map = mutableMapOf<MdocDataElement, Boolean>()
                    if (dataElements.isEmpty()) {
                        mdocNsBuilder.dataElements.values.map { map.put(it, false) }
                    } else {
                        for ((dataElement, intentToRetain) in dataElements) {
                            map.put(mdocNsBuilder.dataElements[dataElement]!!, intentToRetain)
                        }
                    }
                    nsRequests.add(MdocNamespaceRequest(namespace, map))
                }
                MdocCannedRequest(mdocBuilder!!.docType, mdocUseZkp, nsRequests)
            }
            val jsonRequest = if (jsonClaims == null) {
                null
            } else {
                val claims = if (jsonClaims.isEmpty()) {
                    jsonBuilder!!.claims.mapValues { (_, v) -> v.first }.values.toList()
                } else {
                    val list = mutableListOf<DocumentAttribute>()
                    for (claimName in jsonClaims) {
                        val splits = claimName.split(".")
                        when (splits.size) {
                            1 -> {
                                list.add(jsonBuilder!!.claims[claimName]!!.first)
                            }
                            2 -> {
                                val parentAttribute = jsonBuilder!!.claims[splits[0]]!!.first
                                val embeddedAttribute = parentAttribute.embeddedAttributes.find {
                                    it.identifier == splits[1]
                                } ?: throw Exception("No attribute with identifier ${splits[1]} on parent attribute ${splits[0]}")
                                list.add(embeddedAttribute)
                            }
                            else -> throw Exception("Invalid claim name $claimName, can have at max one period")
                        }
                    }
                    list
                }
                JsonCannedRequest(jsonBuilder!!.vct, claims)
            }
            sampleRequests.add(
                SingleDocumentCannedRequest(
                    id = id,
                    displayName = displayName,
                    mdocRequest = mdocRequest,
                    jsonRequest = jsonRequest,
                    transactionData = cannedTransactionData
                ))
        }

        /**
         * Build the [DocumentType].
         */
        fun build() = DocumentType(
            displayName,
            sampleRequests,
            mdocBuilder?.build(),
            jsonBuilder?.build())
    }

    /**
     * Adds a [MdocCredential] to a [Document] with sample data for the document type.
     *
     * @param document the [Document] to add the credential to.
     * @param secureArea the [SecureArea] to use for `DeviceKey`.
     * @param createKeySettings the [CreateKeySettings] to use.
     * @param dsKey the key to sign the MSO with and its certificate chain.
     * @param signedAt the time the MSO was signed.
     * @param validFrom the time at which the credential is valid from.
     * @param validUntil the time at which the credential is valid until.
     * @param expectedUpdate the time at which to expect an update, or `null`.
     * @param domain the domain to use for the credential.
     * @return the [MdocCredential] that was added to [document].
     */
    suspend fun createMdocCredentialWithSampleData(
        document: Document,
        secureArea: SecureArea,
        createKeySettings: CreateKeySettings,
        dsKey: AsymmetricKey.X509Certified,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        expectedUpdate: Instant? = null,
        domain: String = "mdoc",
        randomProvider: Random = Random,
    ): MdocCredential {
        require(mdocDocumentType != null)

        val issuerNamespaces = buildIssuerNamespaces(
            randomProvider = randomProvider
        ) {
            for ((nsName, ns) in mdocDocumentType.namespaces) {
                addNamespace(nsName) {
                    for ((deName, de) in ns.dataElements) {
                        val sampleValue = de.attribute.sampleValueMdoc
                        if (sampleValue != null) {
                            addDataElement(deName, sampleValue)
                        }
                    }
                }
            }
        }

        val mdocCredential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = domain,
            secureArea = secureArea,
            docType = mdocDocumentType.docType,
            createKeySettings = createKeySettings
        )

        // Generate an MSO and issuer-signed data for this authentication key.
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = mdocDocumentType.docType,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = mdocCredential.getAttestation().publicKey,
        )
        val taggedEncodedMso = Cbor.encode(Tagged(
            Tagged.ENCODED_CBOR,
            Bstr(Cbor.encode(mso.toDataItem())))
        )

        // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
        //
        // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
        //
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_ALG),
                Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
            )
        )
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                dsKey.certChain.toDataItem()
            )
        )
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                dsKey,
                taggedEncodedMso,
                true,
                protectedHeaders,
                unprotectedHeaders
            ).toDataItem()
        )
        val issuerProvidedAuthenticationData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", RawCbor(encodedIssuerAuth))
            }
        )

        // Now that we have issuer-provided authentication data we ccan ertify the authentication key.
        mdocCredential.certify(ByteString(issuerProvidedAuthenticationData))
        return mdocCredential
    }

    /**
     * Adds a [KeylessSdJwtVcCredential] to a [Document] with sample data for the document type.
     *
     * @param document the [Document] to add the credential to.
     * @param dsKey the key to sign the MSO with and its certificate chain.
     * @param signedAt the time the MSO was signed.
     * @param validFrom the time at which the credential is valid from.
     * @param validUntil the time at which the credential is valid until.
     * @param domain the domain to use for the credential.
     * @return the [MdocCredential] that was added to [document].
     */
    suspend fun createKeylessSdJwtVcCredentialWithSampleData(
        document: Document,
        dsKey: AsymmetricKey.X509Certified,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        domain: String = "sdjwt",
        randomProvider: Random = Random,
    ): KeylessSdJwtVcCredential {
        require(jsonDocumentType != null)

        val identityAttributes = buildJsonObject {
            for ((claimName, attribute) in jsonDocumentType.claims) {
                // Skip sub-claims.
                if (claimName.contains('.')) {
                    continue
                }
                attribute.sampleValueJson?.let {
                    put(claimName, it)
                }
            }
        }
        val credential = KeylessSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = domain,
            vct = jsonDocumentType.vct,
        )

        val sdJwt = SdJwt.create(
            issuerKey = dsKey,
            kbKey = null,
            claims = identityAttributes,
            nonSdClaims = buildJsonObject {
                put("iss", "https://example-issuer.com")
                put("vct", credential.vct)
                put("iat", signedAt.epochSeconds)
                put("nbf", validFrom.epochSeconds)
                put("exp", validUntil.epochSeconds)
            },
            random = randomProvider
        )
        credential.certify(sdJwt.compactSerialization.encodeToByteString())
        return credential
    }

    /**
     * Adds a [KeyBoundSdJwtVcCredential] to a [Document] with sample data for the document type.
     *
     * @param document the [Document] to add the credential to.
     * @param secureArea the [SecureArea] to use for `DeviceKey`.
     * @param createKeySettings the [CreateKeySettings] to use.
     * @param dsKey the key to sign the MSO with and its certificate chain.
     * @param signedAt the time the MSO was signed.
     * @param validFrom the time at which the credential is valid from.
     * @param validUntil the time at which the credential is valid until.
     * @param domain the domain to use for the credential.
     * @return the [MdocCredential] that was added to [document].
     */
    suspend fun createKeyBoundSdJwtVcCredentialWithSampleData(
        document: Document,
        secureArea: SecureArea,
        createKeySettings: CreateKeySettings,
        dsKey: AsymmetricKey.X509Certified,
        signedAt: Instant,
        validFrom: Instant,
        validUntil: Instant,
        domain: String = "sdjwt",
        randomProvider: Random = Random,
    ): KeyBoundSdJwtVcCredential {
        require(jsonDocumentType != null)

        val identityAttributes = buildJsonObject {
            for ((claimName, attribute) in jsonDocumentType.claims) {
                // Skip sub-claims.
                if (claimName.contains('.')) {
                    continue
                }
                attribute.sampleValueJson?.let {
                    put(claimName, it)
                }
            }
        }
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = domain,
            secureArea = secureArea,
            vct = jsonDocumentType.vct,
            createKeySettings = createKeySettings
        )

        val sdJwt = SdJwt.create(
            issuerKey = dsKey,
            kbKey = credential.getAttestation().publicKey,
            claims = identityAttributes,
            nonSdClaims = buildJsonObject {
                put("iss", "https://example-issuer.com")
                put("vct", credential.vct)
                put("iat", signedAt.epochSeconds)
                put("nbf", validFrom.epochSeconds)
                put("exp", validUntil.epochSeconds)
            },
            random = randomProvider
        )
        credential.certify(sdJwt.compactSerialization.encodeToByteString())
        return credential
    }
}