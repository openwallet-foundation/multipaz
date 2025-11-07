/*
 * Copyright 2022 The Android Open Source Project
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
package org.multipaz.mdoc.response

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.mso.MobileSecurityObjectParser
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Instant
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Hkdf
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.mdoc.zkp.ZkDocument

/**
 * Helper class for parsing the bytes of `DeviceResponse`
 * [CBOR](http://cbor.io/)
 * as specified in ISO/IEC 18013-5:2021 section 8.3 Device Retrieval.
 *
 * @param encodedDeviceResponse the bytes of the DeviceResponse CBOR.
 * @param encodedSessionTranscript the bytes of the SessionTrancript CBOR.
 */
class DeviceResponseParser(
    val encodedDeviceResponse: ByteArray,
    val encodedSessionTranscript: ByteArray
) {
    private var eReaderKey: AsymmetricKey? = null

    /**
     * Sets the private part of the ephemeral key used in the session where the
     * `DeviceResponse` was obtained.
     *
     * This is only required if the `DeviceResponse` is using the MAC method for device
     * authentication.
     *
     * @param eReaderKey the reader ephemeral key.
     * @return the `DeviceResponseParser`.
     */
    fun setEphemeralReaderKey(eReaderKey: AsymmetricKey) = apply {
        this.eReaderKey = eReaderKey
    }

    /**
     * Parses the device response.
     *
     * If the response is using MAC for device authentication, [setEphemeralReaderKey] must also
     * have been called to set the reader key.
     *
     * This parser will successfully parse responses where issuer-signed data elements fails
     * the digest check against the MSO, where `DeviceSigned` authentication checks fail,
     * and where `IssuerSigned` authentication checks fail. The methods
     * [Document.getIssuerEntryDigestMatch],
     * [Document.deviceSignedAuthenticated], and
     * [Document.issuerSignedAuthenticated]
     * can be used to get additional information about this.
     *
     * @return a [DeviceResponseParser.DeviceResponse] with the parsed data.
     * @exception IllegalArgumentException if the given data isn't valid CBOR or not conforming
     * to the CDDL for its type.
     * @exception IllegalStateException if required data hasn't been set using the setter
     * methods on this class.
     */
    suspend fun parse(): DeviceResponse =
    // mEReaderKey may be omitted if the response is using ECDSA instead of MAC
        // for device authentication.
        DeviceResponse().apply {
            parse(encodedDeviceResponse, encodedSessionTranscript, eReaderKey)
        }


    /**
     * An object used to represent data parsed from `DeviceResponse`
     * [CBOR](http://cbor.io/)
     * as specified in *ISO/IEC 18013-5* section 8.3 *Device Retrieval*.
     */
    class DeviceResponse {

        // backing fields
        private val _documents = mutableListOf<Document>()
        private val _zkDocuments = mutableListOf<ZkDocument>()

        /**
         * The documents in the device response.
         */
        val documents: List<Document>
            get() = _documents

        /**
         * The ZK documents in the device response.
         */
        val zkDocuments: List<ZkDocument>
            get() = _zkDocuments

        /**
         * The version string set in the `DeviceResponse` CBOR.
         */
        lateinit var version: String

        // Returns the DeviceKey from the MSO
        //
        private fun parseIssuerSigned(
            expectedDocType: String,
            issuerSigned: DataItem,
            builder: Document.Builder
        ): EcPublicKey {
            val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1

            // 18013-5 clause "9.1.2.4 Signing method and structure for MSO" guarantees
            // that x5chain is in the unprotected headers and that alg is in the
            // protected headers...
            val issuerAuthorityCertChain =
                issuerAuth.unprotectedHeaders[
                    CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)
                ]!!.asX509CertChain
            val signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(
                issuerAuth.protectedHeaders[
                    CoseNumberLabel(Cose.COSE_LABEL_ALG)
                ]!!.asNumber.toInt()
            )
            val issuerSignedAuthenticated = if (issuerAuthorityCertChain.certificates.size > 0) {
                val documentSigningKey = issuerAuthorityCertChain.certificates[0].ecPublicKey
                 try {
                    Cose.coseSign1Check(
                        documentSigningKey,
                        null,
                        issuerAuth,
                        signatureAlgorithm
                    )
                    true
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
            val encodedMobileSecurityObject = Cbor.decode(issuerAuth.payload!!).asTagged.asBstr
            val parsedMso = MobileSecurityObjectParser(encodedMobileSecurityObject).parse()

            builder.apply {
                setIssuerSignedAuthenticated(issuerSignedAuthenticated)
                setIssuerCertificateChain(issuerAuthorityCertChain)
                setValidityInfoSigned(parsedMso.signed)
                setValidityInfoValidFrom(parsedMso.validFrom)
                setValidityInfoValidUntil(parsedMso.validUntil)
            }

            if (parsedMso.expectedUpdate != null) {
                builder.setValidityInfoExpectedUpdate(parsedMso.expectedUpdate!!)
            }

            /* don't care about version for now */
            check(parsedMso.digestAlgorithm in listOf(Algorithm.SHA256, Algorithm.SHA384, Algorithm.SHA512)) {
                "Unexpected digest algorithm ${parsedMso.digestAlgorithm}"
            }
            val msoDocType = parsedMso.docType
            require(msoDocType == expectedDocType) {
                ("docType in MSO '$msoDocType' does not match docType from Document")
            }
            val nameSpaceNames = parsedMso.valueDigestNamespaces
            val digestMapping: MutableMap<String, Map<Long, ByteArray>?> = HashMap()
            for (nameSpaceName in nameSpaceNames) {
                digestMapping[nameSpaceName] = parsedMso.getDigestIDs(nameSpaceName)
            }
            val deviceKey = parsedMso.deviceKey

            // nameSpaces may be absent...
            val nameSpaces = issuerSigned.getOrNull("nameSpaces")
            if (nameSpaces != null) {
                for (nameSpaceDataItem in nameSpaces.asMap.keys) {
                    val nameSpace = nameSpaceDataItem.asTstr
                    val innerDigestMapping = digestMapping[nameSpace]
                        ?: throw IllegalArgumentException(
                            "No digestID MSO entry for namespace $nameSpace"
                        )
                    val elementsDataItem = nameSpaces[nameSpaceDataItem]
                    for (elem in elementsDataItem.asArray) {
                        require(
                            elem is Tagged && elem.tagNumber == 24L &&
                                    elem.asTagged is Bstr
                        ) { "issuerSignedItemBytes is not a tagged ByteString" }

                        // We need the encoded representation with the tag.
                        val encodedIssuerSignedItemBytes = Cbor.encode(elem)
                        val expectedDigest =
                            Crypto.digest(parsedMso.digestAlgorithm, encodedIssuerSignedItemBytes)
                        val issuerSignedItem = Cbor.decode(elem.asTagged.asBstr)
                        val elementName = issuerSignedItem["elementIdentifier"].asTstr
                        val elementValue = issuerSignedItem["elementValue"]
                        val digestId = issuerSignedItem["digestID"].asNumber
                        val digest = innerDigestMapping[digestId]
                            ?: throw IllegalArgumentException(
                                "No digestID MSO entry for ID $digestId in namespace $nameSpace"
                            )
                        val digestMatch = expectedDigest contentEquals digest
                        if (!digestMatch) {
                            Logger.w(TAG, "hash mismatch for data element $nameSpace $elementName")
                        }
                        builder.addIssuerEntry(
                            nameSpace, elementName,
                            Cbor.encode(elementValue),
                            digestMatch
                        )
                    }
                }
            }
            return deviceKey
        }

        private suspend fun parseDeviceSigned(
            deviceSigned: DataItem,
            docType: String,
            encodedSessionTranscript: ByteArray,
            deviceKey: EcPublicKey,
            eReaderKey: AsymmetricKey?,
            builder: Document.Builder
        ) {
            val nameSpacesBytes = deviceSigned["nameSpaces"]
            val nameSpaces = nameSpacesBytes.asTaggedEncodedCbor
            val deviceAuth = deviceSigned["deviceAuth"]
            val deviceAuthentication = buildCborArray {
                add("DeviceAuthentication")
                add(RawCbor(encodedSessionTranscript))
                add(docType)
                add(nameSpacesBytes)
            }
            val deviceAuthenticationBytes = Cbor.encode(
                Tagged(24, Bstr(Cbor.encode(deviceAuthentication)))
            )
            val deviceSignedAuthenticated: Boolean
            val deviceSignature = deviceAuth.getOrNull("deviceSignature")
            if (deviceSignature != null) {
                val deviceSignatureCoseSign1 = deviceSignature.asCoseSign1

                // 18013-5 clause "9.1.3.6 mdoc ECDSA / EdDSA Authentication" guarantees
                // that alg is in the protected header
                //
                val signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(
                    deviceSignatureCoseSign1.protectedHeaders[
                        CoseNumberLabel(Cose.COSE_LABEL_ALG)
                    ]!!.asNumber.toInt()
                )
                deviceSignedAuthenticated = try {
                    Cose.coseSign1Check(
                        deviceKey,
                        deviceAuthenticationBytes,
                        deviceSignatureCoseSign1,
                        signatureAlgorithm
                    )
                    true
                } catch (_: SignatureVerificationException) {
                    false
                }
                builder.setDeviceSignedAuthenticatedViaSignature(true)
            } else {
                val deviceMacDataItem = deviceAuth.getOrNull("deviceMac")
                    ?: throw IllegalArgumentException(
                        "Neither deviceSignature nor deviceMac in deviceAuth"
                    )
                val tagInResponse = deviceMacDataItem.asCoseMac0.tag
                val sharedSecret = eReaderKey!!.keyAgreement(deviceKey)
                val sessionTranscriptBytes = Cbor.encode(Tagged(24, Bstr(encodedSessionTranscript)))
                val salt = Crypto.digest(Algorithm.SHA256, sessionTranscriptBytes)
                val info = "EMacKey".encodeToByteArray()
                val eMacKey = Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecret, salt, info, 32)
                val expectedTag = Cose.coseMac0(
                    Algorithm.HMAC_SHA256,
                    eMacKey,
                    deviceAuthenticationBytes,
                    false,
                    mapOf(
                        Pair(
                            CoseNumberLabel(Cose.COSE_LABEL_ALG),
                            Algorithm.HMAC_SHA256.coseAlgorithmIdentifier!!.toDataItem()
                        )
                    ),
                    mapOf()
                ).tag
                deviceSignedAuthenticated = expectedTag contentEquals tagInResponse
                if (deviceSignedAuthenticated) {
                    Logger.d(TAG, "Verified DeviceSigned using MAC")
                } else {
                    Logger.d(
                        TAG, "Device MAC mismatch, got ${tagInResponse.toHex()}"
                                + " expected ${expectedTag.toHex()}"
                    )
                }
            }
            builder.setDeviceSignedAuthenticated(deviceSignedAuthenticated)
            for (nameSpaceDataItem in nameSpaces.asMap.keys) {
                val nameSpace = nameSpaceDataItem.asTstr
                val innerMap = nameSpaces[nameSpaceDataItem]
                for (elementNameDataItem in innerMap.asMap.keys) {
                    val elementName = elementNameDataItem.asTstr
                    val elementValue = innerMap[elementNameDataItem]
                    builder.addDeviceEntry(nameSpace, elementName, Cbor.encode(elementValue))
                }
            }
        }

        internal suspend fun parse(
            encodedDeviceResponse: ByteArray?,
            encodedSessionTranscript: ByteArray,
            eReaderKey: AsymmetricKey?
        ) {
            val deviceResponse = Cbor.decode(encodedDeviceResponse!!)
            version = deviceResponse["version"].asTstr
            require(version.compareTo("1.0") >= 0) { "Given version '$version' not >= '1.0'" }

            // Try to get documents, either documents or zkDocuments can be populated, not both.
            val documentsDataItem = deviceResponse.getOrNull("documents")
            if (documentsDataItem != null) {
                for (documentItem in documentsDataItem.asArray) {
                    val docType = documentItem["docType"].asTstr
                    val builder = Document.Builder(docType)
                    val issuerSignedItem = documentItem["issuerSigned"]
                    val deviceKey = parseIssuerSigned(docType, issuerSignedItem, builder)
                    builder.setDeviceKey(deviceKey)
                    val deviceSigned = documentItem["deviceSigned"]
                    parseDeviceSigned(
                        deviceSigned,
                        docType,
                        encodedSessionTranscript,
                        deviceKey,
                        eReaderKey,
                        builder
                    )
                    _documents.add(builder.build())
                }
            }

            val zkDocumentDataItem = deviceResponse.getOrNull("zkDocuments")
            if (zkDocumentDataItem != null) {
                val zkDocumentsArray = zkDocumentDataItem.asArray

                for (zkDocumentDataItem in zkDocumentsArray) {
                    val zkDocument = ZkDocument.fromDataItem(zkDocumentDataItem)
                    _zkDocuments.add(zkDocument)
                }
            }
            status = deviceResponse["status"].asNumber

            // TODO: maybe also parse + convey "documentErrors" and "errors" keys in
            //  DeviceResponse map.
        }

        /**
         * Gets the top-level status in the `DeviceResponse` CBOR.
         *
         * Note that this value is not a result of parsing/validating the
         * `DeviceResponse` CBOR. It's a value which was part of
         * the CBOR and chosen by the remote device.
         *
         * @return One of [Constants.DEVICE_RESPONSE_STATUS_OK],
         * [Constants.DEVICE_RESPONSE_STATUS_GENERAL_ERROR],
         * [Constants.DEVICE_RESPONSE_STATUS_CBOR_DECODING_ERROR], or
         * [Constants.DEVICE_RESPONSE_STATUS_CBOR_VALIDATION_ERROR].
         */
        var status = Constants.DEVICE_RESPONSE_STATUS_OK
            private set

        companion object {
            const val TAG = "DeviceResponse"
        }
    }

    /**
     * An object used to represent data parsed from the `Document`
     * [CBOR](http://cbor.io/) (part of `DeviceResponse`)
     * as specified in *ISO/IEC 18013-5* section 8.3 *Device Retrieval*.
     */
    class Document {
        /**
         * The docType.
         */
        lateinit var docType: String

        /**
         * Returns the certificate chain for the issuer which signed the data in the document.
         */
        lateinit var issuerCertificateChain: X509CertChain

        private data class EntryData(var value: ByteArray, var digestMatch: Boolean)

        private var deviceData = mutableMapOf<String, MutableMap<String, EntryData>>()
        private var issuerData = mutableMapOf<String, MutableMap<String, EntryData>>()


        /**
         * The number of issuer entries for that didn't match the digest in the MSO.
         */
        var numIssuerEntryDigestMatchFailures = 0

        /**
         * Returns whether the `DeviceSigned` data was authenticated.
         *
         *
         * This returns `true` only if the returned device-signed data was properly
         * MACed or signed by a `DeviceKey` in the MSO.
         *
         * @return whether the `DeviceSigned` data was authenticated.
         */
        var deviceSignedAuthenticated = false

        /**
         * Returns whether the `IssuerSigned` data was authenticated.
         *
         *
         * This returns `true` only if the signature on the `MobileSecurityObject`
         * data was made with the public key in the leaf certificate returned by.
         * [.getIssuerCertificateChain]
         *
         * @return whether the `DeviceSigned` data was authenticated.
         */
        var issuerSignedAuthenticated = false

        /**
         * The `signed` date from the MSO.
         */
        lateinit var validityInfoSigned: Instant

        /**
         * The `validFrom` date from the MSO.
         */
        lateinit var validityInfoValidFrom: Instant

        /**
         * The `validUntil` date from the MSO.
         */
        lateinit var validityInfoValidUntil: Instant

        /**
         * The `expectedUpdate` date from the MSO or null if not set.
         */
        var validityInfoExpectedUpdate: Instant? = null

        /**
         * Returns the `DeviceKey` from the MSO.
         */
        lateinit var deviceKey: EcPublicKey

        /**
         * Returns whether `DeviceSigned` was authenticated using ECDSA signature or
         * using a MAC.
         *
         * @return `true` if ECDSA signature was used, `false` otherwise.
         */
        var deviceSignedAuthenticatedViaSignature = false

        /**
         * The names of namespaces with retrieved entries of the issuer-signed data.
         *
         * If the document doesn't contain any issuer-signed data, this returns the empty list.
         */
        val issuerNamespaces: List<String>
            get() = issuerData.keys.toList()

        /**
         * Gets the names of data elements in the given issuer-signed namespace.
         *
         * @param namespaceName the name of the namespace to get data element names from.
         * @return A collection of data element names for the namespace.
         * @exception IllegalArgumentException if the given namespace isn't in the data.
         */
        fun getIssuerEntryNames(namespaceName: String): List<String> {
            val innerMap = issuerData[namespaceName]
                ?: throw IllegalArgumentException("Namespace not in data")
            return innerMap.keys.toList()
        }

        /**
         * Gets whether the digest for the given entry matches the digest in the MSO.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the encoded CBOR data for the data element
         * @exception IllegalArgumentException if the given namespace or entry isn't in the data.
         */
        fun getIssuerEntryDigestMatch(
            namespaceName: String,
            name: String
        ): Boolean {
            val innerMap = issuerData[namespaceName]
                ?: throw IllegalArgumentException("Namespace not in data")
            val entryData = innerMap[name]
            require(entryData != null) { "Entry not in data" }
            return entryData.digestMatch
        }

        /**
         * Gets the raw CBOR data for the value of given data element in a given namespace in
         * issuer-signed data.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the encoded CBOR data for the data element
         * @exception IllegalArgumentException if the given namespace or entry isn't in the data.
         */
        fun getIssuerEntryData(
            namespaceName: String,
            name: String
        ): ByteArray {
            val innerMap = issuerData[namespaceName]
                ?: throw IllegalArgumentException("Namespace not in data")
            val entryData = innerMap[name]
            require(entryData != null) { "Entry not in data" }
            return entryData.value
        }

        /**
         * Like [getIssuerEntryData] but returns the CBOR decoded
         * as a string.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getIssuerEntryString(
            namespaceName: String,
            name: String
        ): String = getIssuerEntryData(namespaceName, name).let { value ->
            Cbor.decode(value).asTstr
        }

        /**
         * Like [getIssuerEntryData] but returns the CBOR decoded as a byte-string.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getIssuerEntryByteString(
            namespaceName: String,
            name: String
        ): ByteArray = getIssuerEntryData(namespaceName, name).let { value ->
            Cbor.decode(value).asBstr
        }

        /**
         * Like [getIssuerEntryData] but returns the CBOR decoded as a boolean.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getIssuerEntryBoolean(namespaceName: String, name: String): Boolean =
            getIssuerEntryData(namespaceName, name).let { value ->
                Cbor.decode(value).asBoolean
            }

        /**
         * Like [getIssuerEntryData] but returns the CBOR decoded as a long.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getIssuerEntryNumber(namespaceName: String, name: String): Long =
            getIssuerEntryData(namespaceName, name).let { value ->
                Cbor.decode(value).asNumber
            }

        /**
         * Like [getIssuerEntryData] but returns the CBOR decoded as an [Instant].
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getIssuerEntryDateTime(
            namespaceName: String,
            name: String
        ): Instant = getIssuerEntryData(namespaceName, name).let { value ->
            Cbor.decode(value).asDateTimeString
        }

        /**
         * The names of namespaces with retrieved entries of the device-signed data.
         *
         * If the document doesn't contain any device-signed data, this returns the empty list.
         */
        val deviceNamespaces: List<String>
            get() = deviceData.keys.toList()

        /**
         * Gets the names of data elements in the given device-signed namespace.
         *
         * @param namespaceName the name of the namespace to get data element names from.
         * @return A collection of data element names for the namespace.
         * @exception IllegalArgumentException if the given namespace isn't in the data.
         */
        fun getDeviceEntryNames(namespaceName: String): List<String> {
            val innerMap = deviceData[namespaceName]
                ?: throw IllegalArgumentException("Namespace not in data")
            return innerMap.keys.toList()
        }

        /**
         * Gets the raw CBOR data for the value of given data element in a given namespace in
         * device-signed data.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the encoded CBOR data for the data element
         * @exception IllegalArgumentException if the given namespace or entry isn't in the data.
         */
        fun getDeviceEntryData(
            namespaceName: String,
            name: String
        ): ByteArray {
            val innerMap = deviceData[namespaceName]
                ?: throw IllegalArgumentException("Namespace not in data")
            return innerMap[name]?.value
                ?: throw IllegalArgumentException("Entry not in data")
        }

        /**
         * Like [getDeviceEntryData] but returns the CBOR decoded as a string.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getDeviceEntryString(
            namespaceName: String,
            name: String
        ): String = getDeviceEntryData(namespaceName, name).let { value ->
            Cbor.decode(value).asTstr
        }

        /**
         * Like [getDeviceEntryData] but returns the CBOR decoded as a byte-string.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getDeviceEntryByteString(
            namespaceName: String,
            name: String
        ): ByteArray = getDeviceEntryData(namespaceName, name).let { value ->
            Cbor.decode(value).asBstr
        }

        /**
         * Like [getDeviceEntryData] but returns the CBOR decoded as a boolean.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getDeviceEntryBoolean(
            namespaceName: String,
            name: String
        ): Boolean = getDeviceEntryData(namespaceName, name).let { value ->
            Cbor.decode(value).asBoolean
        }

        /**
         * Like [getDeviceEntryData] but returns the CBOR decoded as a long.
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getDeviceEntryNumber(namespaceName: String, name: String): Long =
            getDeviceEntryData(namespaceName, name).let { value ->
                Cbor.decode(value).asNumber
            }

        /**
         * Like [getDeviceEntryData] but returns the CBOR decoded as an [Instant].
         *
         * @param namespaceName the name of the namespace to get a data element value from.
         * @param name the name of the data element in the given namespace.
         * @return the decoded data.
         * @exception IllegalArgumentException if the CBOR data isn't in data or not the right type.
         */
        fun getDeviceEntryDateTime(
            namespaceName: String,
            name: String
        ): Instant =
            getDeviceEntryData(namespaceName, name).let { value ->
                Cbor.decode(value).asDateTimeString
            }

        internal class Builder(docType: String) {
            private val result: Document = Document().apply {
                this.docType = docType
            }

            fun addIssuerEntry(
                namespaceName: String, name: String, value: ByteArray,
                digestMatch: Boolean
            ) = apply {
                var innerMap = result.issuerData[namespaceName]
                if (innerMap == null) {
                    innerMap = mutableMapOf()
                    result.issuerData[namespaceName] = innerMap
                }
                innerMap[name] = EntryData(
                    value,
                    digestMatch
                )
                if (!digestMatch) {
                    result.numIssuerEntryDigestMatchFailures += 1
                }
            }

            fun setIssuerCertificateChain(certificateChain: X509CertChain) {
                result.issuerCertificateChain = certificateChain
            }

            fun addDeviceEntry(namespaceName: String, name: String, value: ByteArray) = apply {
                var innerMap = result.deviceData[namespaceName]
                if (innerMap == null) {
                    innerMap = LinkedHashMap()
                    result.deviceData[namespaceName] = innerMap
                }
                innerMap[name] = EntryData(value, true)
            }

            fun setDeviceSignedAuthenticated(deviceSignedAuthenticated: Boolean) = apply {
                result.deviceSignedAuthenticated = deviceSignedAuthenticated
            }

            fun setIssuerSignedAuthenticated(issuerSignedAuthenticated: Boolean) = apply {
                result.issuerSignedAuthenticated = issuerSignedAuthenticated
            }

            fun setValidityInfoSigned(value: Instant) = apply {
                result.validityInfoSigned = value
            }

            fun setValidityInfoValidFrom(value: Instant) = apply {
                result.validityInfoValidFrom = value
            }

            fun setValidityInfoValidUntil(value: Instant) = apply {
                result.validityInfoValidUntil = value
            }

            fun setValidityInfoExpectedUpdate(value: Instant) = apply {
                result.validityInfoExpectedUpdate = value
            }

            fun setDeviceKey(deviceKey: EcPublicKey) = apply {
                result.deviceKey = deviceKey
            }

            fun setDeviceSignedAuthenticatedViaSignature(value: Boolean) = apply {
                result.deviceSignedAuthenticatedViaSignature = value
            }

            fun build(): Document = result
        }

        companion object {
            const val TAG = "Document"
        }
    }
}
