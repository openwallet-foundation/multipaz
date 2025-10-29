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
package org.multipaz.mdoc.response

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.document.NameSpacedData
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Hkdf
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.UnlockReason
import org.multipaz.securearea.SecureArea

/**
 * Helper class for building `Document` [CBOR](http://cbor.io/)
 * as specified in ISO/IEC 18013-5:2021 section 8.3.
 *
 * @param docType the document type.
 * @param encodedIssuerAuth bytes of `IssuerAuth` CBOR, as per ISO/IEC 18013-5:2021 section 9.1.2.4.
 * @param encodedSessionTranscript bytes of `SessionTranscript` CBOR as per ISO/IEC 18013-5:2021
 * section 9.1.5.1.
 */
class DocumentGenerator
    (
    private val docType: String,
    private val encodedIssuerAuth: ByteArray,
    private val encodedSessionTranscript: ByteArray
) {
    private var errors: Map<String, Map<String, Long>>? = null
    private var issuerNamespaces: Map<String, List<ByteArray>>? = null
    private var deviceSigned: DataItem? = null
    private var issuerNamespacesNew: IssuerNamespaces? = null

    /**
     * Sets document errors.
     *
     * The `errors` parameter is a map from namespaces where each value is a map from
     * data elements in said namespace to an error code from ISO/IEC 18013-5:2021 Table 9.
     *
     * @param errors the map described above.
     * @return the generator.
     */
    fun setErrors(errors: Map<String, Map<String, Long>>): DocumentGenerator = apply {
        this.errors = errors
    }

    /**
     * Sets issuer-signed data elements to return.
     *
     * Since a document response may not contain issuer signed data elements, this is
     * optional to call.
     *
     * @param issuerNameSpaces a map from name spaces into a list of `IssuerSignedItemBytes`.
     * @return the generator.
     */
    fun setIssuerNamespaces(issuerNameSpaces: Map<String, List<ByteArray>>?) = apply {
        issuerNamespaces = issuerNameSpaces
    }

    fun setIssuerNamespaces(issuerNamespaces: IssuerNamespaces) {
        this.issuerNamespacesNew = issuerNamespaces
    }

    private suspend fun setDeviceNamespaces(
        dataElements: NameSpacedData,
        secureArea: SecureArea,
        keyAlias: String,
        unlockReason: UnlockReason,
        useMac: Boolean,
        eReaderKey: EcPublicKey?
    ) = apply {
        val encodedDeviceNameSpaces = Cbor.encode(
            buildCborMap {
                for (nameSpaceName in dataElements.nameSpaceNames) {
                    putCborMap(nameSpaceName) {
                        for (dataElementName in dataElements.getDataElementNames(nameSpaceName)) {
                            put(dataElementName, RawCbor(dataElements.getDataElement(nameSpaceName, dataElementName)))
                        }
                    }
                }
            }
        )

        val deviceAuthentication = Cbor.encode(
            buildCborArray {
                add("DeviceAuthentication")
                add(RawCbor(encodedSessionTranscript))
                add(docType)
                addTaggedEncodedCbor(encodedDeviceNameSpaces)
            }
        )
        val deviceAuthenticationBytes = Cbor.encode(Tagged(24, Bstr(deviceAuthentication)))
        var encodedDeviceSignature: ByteArray? = null
        var encodedDeviceMac: ByteArray? = null
        if (!useMac) {
            encodedDeviceSignature = Cbor.encode(
                Cose.coseSign1Sign(
                    secureArea,
                    keyAlias,
                    deviceAuthenticationBytes,
                    false,
                    mapOf(),
                    mapOf(),
                    unlockReason
                ).toDataItem()
            )
        } else {
            val sharedSecret = secureArea.keyAgreement(
                keyAlias,
                eReaderKey!!,
                unlockReason
            )
            val sessionTranscriptBytes = Cbor.encode(Tagged(24, Bstr(encodedSessionTranscript)))
            val salt = Crypto.digest(Algorithm.SHA256, sessionTranscriptBytes)
            val info = "EMacKey".encodeToByteArray()
            val eMacKey = Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecret, salt, info, 32)
            encodedDeviceMac = Cbor.encode(
                Cose.coseMac0(
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
                ).toDataItem()
            )
        }
        val deviceAuthType: String
        val deviceAuthDataItem: DataItem
        if (encodedDeviceSignature != null) {
            deviceAuthType = "deviceSignature"
            deviceAuthDataItem = Cbor.decode(encodedDeviceSignature)
        } else {
            deviceAuthType = "deviceMac"
            deviceAuthDataItem = Cbor.decode(encodedDeviceMac!!)
        }
        deviceSigned = buildCborMap {
            putTaggedEncodedCbor("nameSpaces", encodedDeviceNameSpaces)
            putCborMap("deviceAuth") {
                put(deviceAuthType, deviceAuthDataItem)
            }
        }
    }

    /**
     * Sets device-signed data elements to return.
     *
     * This variant produces an EC signature as per ISO/IEC 18013-5:2021 section 9.1.3.6
     * mdoc ECDSA / EdDSA Authentication.
     *
     * @param dataElements the data elements to return in `DeviceSigned`.
     * @param secureArea the [SecureArea] for the authentication key to sign with.
     * @param keyAlias the alias for the authentication key to sign with.
     * @param keyUnlockData unlock data for the authentication key, or `null`.
     * @param signatureAlgorithm the signature algorithm to use.
     * @return the generator.
     * @throws KeyLockedException if the authentication key is locked.
     */
    suspend fun setDeviceNamespacesSignature(
        dataElements: NameSpacedData,
        secureArea: SecureArea,
        keyAlias: String,
        unlockReason: UnlockReason
    ) = apply {
        setDeviceNamespaces(
            dataElements,
            secureArea,
            keyAlias,
            unlockReason,
            useMac = false,
            eReaderKey = null
        )
    }

    /**
     * Sets device-signed data elements to return.
     *
     * This variant produces a MAC as per ISO/IEC 18013-5:2021 section 9.1.3.5
     * mdoc MAC Authentication.
     *
     * @param dataElements the data elements to return in `DeviceSigned`.
     * @param secureArea the [SecureArea] for the authentication key to sign with.
     * @param keyAlias the alias for the authentication key to sign with.
     * @param keyUnlockData unlock data for the authentication key, or `null`.
     * @param eReaderKey the ephemeral public key used by the remote reader.
     * @return the generator.
     * @throws KeyLockedException if the authentication key is locked.
     */
    suspend fun setDeviceNamespacesMac(
        dataElements: NameSpacedData,
        secureArea: SecureArea,
        keyAlias: String,
        eReaderKey: EcPublicKey,
        unlockReason: UnlockReason = UnlockReason.Unspecified
    ) = apply {
        setDeviceNamespaces(
            dataElements,
            secureArea,
            keyAlias,
            unlockReason,
            useMac = true,
            eReaderKey = eReaderKey
        )
    }

    /**
     * Generates CBOR.
     *
     * This generates the bytes of the `Document` CBOR according to ISO/IEC 18013-5:2021
     * section 8.3.2.1.2.2.
     *
     * @return the bytes described above.
     * @throws IllegalStateException if one of [.setDeviceNamespacesSignature]
     * or [.setDeviceNamespacesMac] hasn't been called on the generator.
     */
    fun generate(): ByteArray {
        checkNotNull(deviceSigned) { "DeviceSigned isn't set" }
        return Cbor.encode(buildCborMap {
            put("docType", docType)
            putCborMap("issuerSigned") {
                if (issuerNamespacesNew != null) {
                    putCborMap("nameSpaces") {
                        for ((namespace, innerMap) in issuerNamespacesNew!!.data) {
                            putCborArray(namespace) {
                                for ((_, issuerSignedItem) in innerMap) {
                                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(issuerSignedItem.toDataItem()))))
                                }
                            }
                        }
                    }
                } else if (issuerNamespaces != null) {
                    putCborMap("nameSpaces") {
                        for (ns in issuerNamespaces!!.keys) {
                            putCborArray(ns) {
                                for (encodedIssuerSignedItemBytes in issuerNamespaces!![ns]!!) {
                                    add(RawCbor(encodedIssuerSignedItemBytes))
                                }
                            }
                        }
                    }
                }
                put("issuerAuth", RawCbor(encodedIssuerAuth))
            }
            put("deviceSigned", deviceSigned!!)
            if (errors != null) {
                putCborMap("errors") {
                    for ((namespaceName, innerMap) in errors!!) {
                        putCborMap(namespaceName) {
                            for (dataElementName in innerMap.keys) {
                                val value = innerMap[dataElementName]!!
                                put(dataElementName, value)
                            }
                        }
                    }
                }
            }
        })
    }
}
