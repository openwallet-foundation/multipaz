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
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.mdoc.zkp.ZkDocument

/**
 * Helper class for building `DeviceResponse` [CBOR](http://cbor.io/)
 * as specified in *ISO/IEC 18013-5* section 8.3 *Device Retrieval*.
 *
 * @param statusCode the status code to use which must be one of
 * [Constants.DEVICE_RESPONSE_STATUS_OK],
 * [Constants.DEVICE_RESPONSE_STATUS_GENERAL_ERROR],
 * [Constants.DEVICE_RESPONSE_STATUS_CBOR_DECODING_ERROR], or
 * [Constants.DEVICE_RESPONSE_STATUS_CBOR_VALIDATION_ERROR].
 */
@Deprecated(message = "Deprecated, use DeviceResponse instead")
class DeviceResponseGenerator(private val mStatusCode: Long) {
    private val mDocumentsBuilder = CborArray.builder()
    private val mZkDocumentBuilder = CborArray.builder()
    private var mDocumentsAdded = false;

    /**
     * Adds a new document to the device response.
     *
     * Issuer-signed data is provided in `issuerNameSpaces` which
     * maps from namespaces into a list of bytes of IssuerSignedItemBytes CBOR as
     * defined in 18013-5 where each contains the digest-id, element name,
     * issuer-generated random value and finally the element value. Each IssuerSignedItemBytes
     * must be encoded so its digest matches with the digest in the
     * `MobileSecurityObject` in the `issuerAuth` parameter.
     *
     * The `encodedIssuerAuth` parameter contains the bytes of the
     * `IssuerAuth` CBOR as defined in *ISO/IEC 18013-5*
     * section 9.1.2.4 *Signing method and structure for MSO*. That is,
     * the payload for this `COSE_Sign1` must be set to the
     * `MobileSecurityObjectBytes` and the public key used to
     * sign the payload must be included in a `x5chain` unprotected
     * header element.
     *
     * For device-signed data, the parameters `encodedDeviceNamespaces`,
     * `encodedDeviceSignature`, and `encodedDeviceMac` are
     * used. Of the latter two, exactly one of them must be non-`null`.
     * The `DeviceNameSpaces` CBOR specified in *ISO/IEC 18013-5*
     * section 8.3.2.1 *Device retrieval* is to be set in
     * `encodedDeviceNamespaces`, and either a ECDSA signature or a MAC
     * over the `DeviceAuthentication` CBOR as defined in section 9.1.3
     * *mdoc authentication* should be set in `encodedDeviceSignature`
     * or `encodedDeviceMac` respectively. Values for all parameters can be
     * obtained from the `ResultData` class from either the Framework
     * or this library.
     *
     * If present, the `errors` parameter is a map from namespaces where each
     * value is a map from data elements in said namespace to an error code from
     * ISO/IEC 18013-5:2021 Table 9.
     *
     * @param docType the document type, for example `org.iso.18013.5.1.mDL`.
     * @param encodedDeviceNamespaces bytes of the `DeviceNameSpaces` CBOR.
     * @param encodedDeviceSignature bytes of a COSE_Sign1 for authenticating the device data.
     * @param encodedDeviceMac bytes of a COSE_Mac0 for authenticating the device data.
     * @param issuerNameSpaces the map described above.
     * @param errors a map with errors as described above.
     * @param encodedIssuerAuth the bytes of the `COSE_Sign1` described above.
     * @return the passed-in [DeviceResponseGenerator].
     */
    fun addDocument(
        docType: String,
        encodedDeviceNamespaces: ByteArray,
        encodedDeviceSignature: ByteArray?,
        encodedDeviceMac: ByteArray?,
        issuerNameSpaces: Map<String?, List<ByteArray?>>,
        errors: Map<String?, Map<String?, Long?>>?,
        encodedIssuerAuth: ByteArray
    ) = apply {
        val issuerSigned = buildCborMap {
            putCborMap("nameSpaces") {
                for ((ns, encodedIssuerSignedItemBytesList) in issuerNameSpaces) {
                    putCborArray(ns!!) {
                        for (encodedIssuerSignedItemBytes in encodedIssuerSignedItemBytesList) {
                            add(RawCbor(encodedIssuerSignedItemBytes!!))
                        }
                    }
                }
            }
            put("issuerAuth", RawCbor(encodedIssuerAuth))
        }

        val deviceAuthType: String
        val deviceAuth: ByteArray?
        require(!(encodedDeviceSignature != null && encodedDeviceMac != null)) {
            "Cannot specify both Signature and MAC"
        }
        if (encodedDeviceSignature != null) {
            deviceAuthType = "deviceSignature"
            deviceAuth = encodedDeviceSignature
        } else if (encodedDeviceMac != null) {
            deviceAuthType = "deviceMac"
            deviceAuth = encodedDeviceMac
        } else {
            throw IllegalArgumentException("No authentication mechanism used")
        }
        val deviceSigned = buildCborMap {
            put("nameSpaces", Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeviceNamespaces)))
            putCborMap("deviceAuth") {
                put(deviceAuthType, RawCbor(deviceAuth))
            }
        }

        mDocumentsBuilder.add(
            buildCborMap {
                put("docType", docType)
                put("issuerSigned", issuerSigned)
                put("deviceSigned", deviceSigned)
                if (errors != null) {
                    putCborMap("errors") {
                        for ((namespaceName, innerMap) in errors) {
                            putCborMap(namespaceName!!) {
                                for ((dataElementName, value) in innerMap) {
                                    put(dataElementName!!, value!!)
                                }
                            }
                        }
                    }
                }
            }
        )
        mDocumentsAdded = true
    }

    /**
     * Adds a new document to the device response.
     *
     * This can be used with the output [DocumentGenerator] for MDOC presentations.
     *
     * @param encodedDocument the bytes of `Document` CBOR as defined in ISO/IEC
     * 18013-5 section 8.3.2.1.2.2.
     * @return the generator.
     * @throws IllegalStateException if ZK documents have already been added.
     */
    fun addDocument(encodedDocument: ByteArray) = apply {
        mDocumentsBuilder.add(Cbor.decode(encodedDocument))
        mDocumentsAdded = true
    }

    /** Adds a new ZK document to the devices response.
     *
     * @params zkDocument the zkDocument to be added to the request.
     * @throws IllegalStateException if the non-ZK documents have already been added.
     */
    fun addZkDocument(zkDocument: ZkDocument) = apply {
        mZkDocumentBuilder.add(zkDocument.toDataItem())
        mDocumentsAdded = true
    }

    /**
     * Checks if any documents have been added to the device response.
     */
    fun isEmpty(): Boolean = mDocumentsBuilder.isEmpty() && mZkDocumentBuilder.isEmpty()

    /**
     * Builds the `DeviceResponse` CBOR.
     *
     * @return the bytes of `DeviceResponse` CBOR.
     */
    fun generate(): ByteArray = Cbor.encode(
        buildCborMap {
            put("version", "1.0")
            if (mDocumentsAdded) {
                if (!mDocumentsBuilder.isEmpty()) {
                    put("documents", mDocumentsBuilder.end().build())
                    // TODO: The documentErrors map entry should only be present if there is a non-zero
                    //  number of elements in the array. Right now we don't have a way for the application
                    //  to convey document errors but when we add that API we'll need to do something so
                    //  it is included here.
                }
                if (!mZkDocumentBuilder.isEmpty()) {
                    put("zkDocuments", mZkDocumentBuilder.end().build())
                }
            }

            put("status", mStatusCode)
        }
    )
}
