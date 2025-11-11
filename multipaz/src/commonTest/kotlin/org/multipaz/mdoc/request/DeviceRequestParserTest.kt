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
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.TestVectors
import org.multipaz.util.fromHex
import kotlin.time.Clock
import kotlin.time.Instant
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHexByteString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceRequestParserTest {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testDeviceRequestParserWithVectors() {
        // Strip the #6.24 tag since our APIs expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val encodedSessionTranscript =
            Cbor.encode(Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor)
        val parser = DeviceRequestParser(
            TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex(),
            encodedSessionTranscript
        )
        val request = parser.parse()
        assertEquals("1.0", request.version)
        val docRequests = request.docRequests
        assertEquals(1, docRequests.size.toLong())
        val dr = docRequests.iterator().next()
        assertEquals(MDL_DOCTYPE, dr.docType)
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_ITEMS_REQUEST.fromHex(),
            dr.itemsRequest
        )
        val readerCertChain = dr.readerCertificateChain
        assertEquals(1, readerCertChain!!.certificates.size.toLong())
        assertEquals(
            TestVectors.ISO_18013_5_ANNEX_D_READER_CERT.fromHexByteString(),
            readerCertChain.certificates[0].encoded
        )
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_READER_AUTH.fromHex(),
            dr.readerAuth
        )
        assertTrue(dr.readerAuthenticated)
        assertContentEquals(arrayOf(MDL_NAMESPACE), dr.namespaces.toTypedArray())
        assertEquals(6, dr.getEntryNames(MDL_NAMESPACE).size.toLong())
        assertTrue(dr.getIntentToRetain(MDL_NAMESPACE, "family_name"))
        assertTrue(dr.getIntentToRetain(MDL_NAMESPACE, "document_number"))
        assertTrue(dr.getIntentToRetain(MDL_NAMESPACE, "driving_privileges"))
        assertTrue(dr.getIntentToRetain(MDL_NAMESPACE, "issue_date"))
        assertTrue(dr.getIntentToRetain(MDL_NAMESPACE, "expiry_date"))
        assertFalse(dr.getIntentToRetain(MDL_NAMESPACE, "portrait"))
        try {
            dr.getEntryNames("ns-was-not-requested")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
        try {
            dr.getIntentToRetain("ns-was-not-requested", "elem-was-not-requested")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
        try {
            dr.getIntentToRetain(MDL_NAMESPACE, "elem-was-not-requested")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun testDeviceRequestParserWithVectorsMalformedReaderSignature() {
        // Strip the #6.24 tag since our APIs expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val encodedSessionTranscript =
            Cbor.encode(Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor)

        // We know the COSE_Sign1 signature for reader authentication is at index 655 and
        // starts with 1f340006... Poison that so we can check whether signature verification
        // detects it...
        val encodedDeviceRequest = TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex()
        assertEquals(0x1f.toByte().toLong(), encodedDeviceRequest[655].toLong())
        encodedDeviceRequest[655] = 0x1e
        val parser = DeviceRequestParser(
            encodedDeviceRequest,
            encodedSessionTranscript
        )
        val request = parser.parse()
        assertEquals("1.0", request.version)
        val docRequests = request.docRequests
        assertEquals(1, docRequests.size.toLong())
        val dr = docRequests.iterator().next()
        assertEquals(MDL_DOCTYPE, dr.docType)
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_ITEMS_REQUEST.fromHex(),
            dr.itemsRequest
        )
        val readerCertChain = dr.readerCertificateChain
        assertEquals(1, readerCertChain!!.certificates.size.toLong())
        assertEquals(
            TestVectors.ISO_18013_5_ANNEX_D_READER_CERT.fromHexByteString(),
            readerCertChain.certificates[0].encoded
        )
        assertFalse(dr.readerAuthenticated)
    }

    fun testDeviceRequestParserReaderAuthHelper(curve: EcCurve) = runTest {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return@runTest
        }

        val encodedSessionTranscript = Cbor.encode(Bstr(byteArrayOf(0x01, 0x02)))
        val mdlItemsToRequest: MutableMap<String, Map<String, Boolean>> = HashMap()
        val mdlNsItems: MutableMap<String, Boolean> = HashMap()
        mdlNsItems["family_name"] = true
        mdlNsItems["portrait"] = false
        mdlItemsToRequest[MDL_NAMESPACE] = mdlNsItems
        val readerKey = Crypto.createEcPrivateKey(curve)
        val trustPoint = Crypto.createEcPrivateKey(EcCurve.P256)
        val validFrom = Clock.System.now()
        val validUntil = Instant.fromEpochMilliseconds(
            validFrom.toEpochMilliseconds() + 5L * 365 * 24 * 60 * 60 * 1000
        )
        val readerCert = X509Cert.Builder(
            publicKey = readerKey.publicKey,
            signingKey = AsymmetricKey.anonymous(readerKey, curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Test Key"),
            issuer = X500Name.fromName("CN=Test Key"),
            validFrom = validFrom,
            validUntil = validUntil
        ).build()
        val readerCertChain = X509CertChain(listOf(readerCert))
        val mdlRequestInfo: MutableMap<String, ByteArray> = HashMap()
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
            .generate()
        val deviceRequest = DeviceRequestParser(
            encodedDeviceRequest,
            encodedSessionTranscript
        ).parse()
        assertEquals("1.0", deviceRequest.version)
        val documentRequests = deviceRequest.docRequests
        assertTrue(documentRequests.get(0).readerAuthenticated);
    }

    @Test
    fun testDeviceRequestParserReaderAuth_P256() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.P256)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_P384() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.P384)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_P521() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.P521)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_brainpoolP256r1() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.BRAINPOOLP256R1)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_brainpoolP320r1() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.BRAINPOOLP320R1)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_brainpoolP384r1() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.BRAINPOOLP384R1)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_brainpoolP512r1() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.BRAINPOOLP512R1)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_Ed25519() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.ED25519)
    }

    @Test
    fun testDeviceRequestParserReaderAuth_Ed448() {
        testDeviceRequestParserReaderAuthHelper(EcCurve.ED448)
    }

    // TODO: Have a request signed by an unsupported curve and make sure DeviceRequestParser
    //   fails gracefully.. that is, should successfully parse the request message but the
    //   getReaderAuthenticated() method should return false.
    //

    companion object {
        private const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
        private const val MDL_NAMESPACE = "org.iso.18013.5.1"
    }
}
