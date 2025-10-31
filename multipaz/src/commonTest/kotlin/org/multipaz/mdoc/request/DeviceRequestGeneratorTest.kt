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
package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// TODO: Add test which generates the exact bytes of TestVectors#ISO_18013_5_ANNEX_D_DEVICE_REQUEST
//

class DeviceRequestGeneratorTest {
    @Test
    fun testDeviceRequestBuilder() = runTest {
        val encodedSessionTranscript = Cbor.encode(Bstr(byteArrayOf(0x01, 0x02)))
        val mdlItemsToRequest =  mutableMapOf<String, Map<String, Boolean>>()
        val mdlNsItems = mutableMapOf<String, Boolean>()
        mdlNsItems["family_name"] = true
        mdlNsItems["portrait"] = false
        mdlItemsToRequest[MDL_NAMESPACE] = mdlNsItems
        val aamvaNsItems = mutableMapOf<String, Boolean>()
        aamvaNsItems["real_id"] = false
        mdlItemsToRequest[AAMVA_NAMESPACE] = aamvaNsItems
        val mvrItemsToRequest = mutableMapOf<String, Map<String, Boolean>>()
        val mvrNsItems = mutableMapOf<String, Boolean>()
        mvrNsItems["vehicle_number"] = true
        mvrItemsToRequest[MVR_NAMESPACE] = mvrNsItems
        val readerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val validFrom = Clock.System.now()
        val validUntil = Instant.fromEpochMilliseconds(
            validFrom.toEpochMilliseconds() + 30L * 24 * 60 * 60 * 1000
        )
        val readerCert = X509Cert.Builder(
            publicKey = readerKey.publicKey,
            signingKey = AsymmetricKey.anonymous(readerKey, Algorithm.ES256),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Test Key"),
            issuer = X500Name.fromName("CN=Test Key"),
            validFrom = validFrom,
            validUntil = validUntil
        ).build()
        val readerCertChain = X509CertChain(listOf(readerCert))
        val mdlRequestInfo = mutableMapOf<String, ByteArray>()
        mdlRequestInfo["foo"] = Cbor.encode(Tstr("bar"))
        mdlRequestInfo["bar"] = Cbor.encode(42.toDataItem())
        val encodedDeviceRequest = DeviceRequestGenerator(encodedSessionTranscript)
            .addDocumentRequest(
                MDL_DOCTYPE,
                mdlItemsToRequest,
                mdlRequestInfo,
                readerKey,
                readerKey.curve.defaultSigningAlgorithm,
                readerCertChain
            )
            .addDocumentRequest(
                MVR_DOCTYPE,
                mvrItemsToRequest,
                null,
                readerKey,
                readerKey.curve.defaultSigningAlgorithm,
                readerCertChain
            )
            .generate()
        val deviceRequest = DeviceRequestParser(
            encodedDeviceRequest,
            encodedSessionTranscript
        )
            .parse()
        assertEquals("1.0", deviceRequest.version)
        val documentRequests = deviceRequest.docRequests
        assertEquals(2, documentRequests.size.toLong())
        val it = deviceRequest.docRequests.iterator()
        var docRequest = it.next()
        assertTrue(docRequest.readerAuthenticated)
        assertEquals(MDL_DOCTYPE, docRequest.docType)
        assertEquals(2, docRequest.namespaces.size.toLong())
        assertEquals(2, docRequest.getEntryNames(MDL_NAMESPACE).size.toLong())
        assertTrue(docRequest.getIntentToRetain(MDL_NAMESPACE, "family_name"))
        assertFalse(docRequest.getIntentToRetain(MDL_NAMESPACE, "portrait"))
        assertFailsWith<IllegalArgumentException> {
            docRequest.getIntentToRetain(MDL_NAMESPACE, "non-existent")
        }
        assertEquals(1, docRequest.getEntryNames(AAMVA_NAMESPACE).size.toLong())
        assertFalse(docRequest.getIntentToRetain(AAMVA_NAMESPACE, "real_id"))
        assertFailsWith<IllegalArgumentException> {
            docRequest.getIntentToRetain("non-existent", "non-existent")
        }
        assertFailsWith<IllegalArgumentException> {
            docRequest.getEntryNames("non-existent")
        }
        assertEquals(1, docRequest.readerCertificateChain!!.certificates.size.toLong())
        assertEquals(readerCertChain, docRequest.readerCertificateChain)
        val requestInfo = docRequest.requestInfo
        assertNotNull(requestInfo)
        assertEquals(2, requestInfo.keys.size.toLong())
        assertContentEquals(Cbor.encode(Tstr("bar")), requestInfo["foo"])
        assertContentEquals(Cbor.encode(42.toDataItem()), requestInfo["bar"])
        docRequest = it.next()
        assertTrue(docRequest.readerAuthenticated)
        assertEquals(MVR_DOCTYPE, docRequest.docType)
        assertEquals(1, docRequest.namespaces.size.toLong())
        assertEquals(1, docRequest.getEntryNames(MVR_NAMESPACE).size.toLong())
        assertTrue(docRequest.getIntentToRetain(MVR_NAMESPACE, "vehicle_number"))
        assertEquals(1, docRequest.readerCertificateChain!!.certificates.size.toLong())
        assertEquals(readerCertChain, docRequest.readerCertificateChain)
        assertEquals(0, docRequest.requestInfo.size.toLong())
    }

    @Test
    fun testDeviceRequestBuilderSecureArea() = runTest {
        val encodedSessionTranscript = Cbor.encode(Bstr(byteArrayOf(0x01, 0x02)))
        val mdlItemsToRequest =  mutableMapOf<String, Map<String, Boolean>>()
        val mdlNsItems = mutableMapOf<String, Boolean>()
        mdlNsItems["family_name"] = true
        mdlNsItems["portrait"] = false
        mdlItemsToRequest[MDL_NAMESPACE] = mdlNsItems
        val aamvaNsItems = mutableMapOf<String, Boolean>()
        aamvaNsItems["real_id"] = false
        mdlItemsToRequest[AAMVA_NAMESPACE] = aamvaNsItems

        val secureArea = SoftwareSecureArea.create(EphemeralStorage())
        val testKeyInfo = secureArea.createKey(
            alias = "testKey",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )

        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(readerRootKey),
            subject = X500Name.fromName("CN=TEST Reader Root,C=XG-US,ST=MA"),
            serial = ASN1Integer(1),
            validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
            validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
            crlUrl = "http://www.example.com/issuer/crl"
        )
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                privateKey = readerRootKey,
                certChain = X509CertChain(listOf(readerRootCert)),
            ),
            readerKey = testKeyInfo.publicKey,
            subject = X500Name.fromName("CN=TEST Reader Certificate,C=XG-US,ST=MA"),
            serial = ASN1Integer(1),
            validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
            validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
        )
        val readerCertChain = X509CertChain(listOf(readerCert, readerRootCert))

        val encodedDeviceRequest = DeviceRequestGenerator(encodedSessionTranscript)
            .addDocumentRequest(
                docType = MDL_DOCTYPE,
                itemsToRequest = mdlItemsToRequest,
                requestInfo = null,
                readerKeySecureArea = secureArea,
                readerKeyAlias = "testKey",
                readerKeyCertificateChain = readerCertChain,
            )
            .generate()
        val deviceRequest = DeviceRequestParser(
            encodedDeviceRequest,
            encodedSessionTranscript
        )
            .parse()
        var docRequest = deviceRequest.docRequests[0]
        assertTrue(docRequest.readerAuthenticated)
        assertEquals(readerCertChain, docRequest.readerCertificateChain)
    }

    companion object {
        private const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
        private const val MDL_NAMESPACE = "org.iso.18013.5.1"
        private const val AAMVA_NAMESPACE = "org.iso.18013.5.1.aamva"
        private const val MVR_DOCTYPE = "org.iso.18013.7.1.mVR"
        private const val MVR_NAMESPACE = "org.iso.18013.7.1"
    }
}
