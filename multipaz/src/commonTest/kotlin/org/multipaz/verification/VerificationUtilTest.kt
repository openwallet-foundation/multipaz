package org.multipaz.verification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.util.fromBase64Url

class VerificationUtilTest {

    private suspend fun createTestVerifierIdentity(): VerifierIdentity {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val validFrom = Clock.System.now()
        val validUntil = validFrom + 365.days
        val rootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(rootKey),
            subject = X500Name.fromName("CN=Test Reader Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil,
            crlUrl = "https://example.com/crl"
        )
        val readerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(rootCert)),
                privateKey = rootKey
            ),
            readerKey = readerKey.publicKey,
            subject = X500Name.fromName("CN=Test Reader"),
            dnsName = "example.com",
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil,
            extensions = emptyList()
        )
        return VerifierIdentity(
            key = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerCert, rootCert)),
                privateKey = readerKey
            ),
            clientId = "x509_san_dns:example.com"
        )
    }

    @Test
    fun generateDcRequestMdoc_version10() = runTest {
        val identity = createTestVerifierIdentity()
        val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val claims = listOf(
            MdocRequestedClaim(
                docType = "org.iso.18013.5.1.mDL",
                namespaceName = "org.iso.18013.5.1",
                dataElementName = "given_name",
                intentToRetain = false
            )
        )
        val requestJson = VerificationUtil.generateDcRequestMdoc(
            exchangeProtocols = listOf("org-iso-mdoc"),
            docType = "org.iso.18013.5.1.mDL",
            claims = claims,
            nonce = ByteString(Random.nextBytes(16)),
            origin = "https://example.com",
            responseEncryptionKey = responseEncryptionKey,
            verifierIdentities = listOf(identity),
            zkSystemSpecs = emptyList(),
            deviceRequestVersion = "1.0"
        )
        val requestEntry = requestJson["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == "org-iso-mdoc" }
            .jsonObject
        val base64DeviceRequest = requestEntry["data"]!!.jsonObject["deviceRequest"]!!.jsonPrimitive.content
        val dataItem = Cbor.decode(base64DeviceRequest.fromBase64Url())
        val dr = DeviceRequest.fromDataItem(dataItem)

        assertEquals("1.0", dr.version)
        assertEquals("1.0", dataItem["version"].asTstr)
        assertNull(dataItem.getOrNull("deviceRequestInfo"))
        assertNull(dataItem.getOrNull("readerAuthAll"))
        assertNull(dr.deviceRequestInfo)
        val docRequestDataItem = dataItem["docRequests"].asArray[0]
        val itemsRequest = docRequestDataItem["itemsRequest"].asTaggedEncodedCbor
        assertNull(itemsRequest.getOrNull("requestInfo"))
        assertNotNull(docRequestDataItem.getOrNull("readerAuth"))
    }

    @Test
    fun generateDcRequestMdoc_version11() = runTest {
        val identity = createTestVerifierIdentity()
        val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val claims = listOf(
            MdocRequestedClaim(
                docType = "org.iso.18013.5.1.mDL",
                namespaceName = "org.iso.18013.5.1",
                dataElementName = "given_name",
                intentToRetain = false
            )
        )
        val requestJson = VerificationUtil.generateDcRequestMdoc(
            exchangeProtocols = listOf("org-iso-mdoc"),
            docType = "org.iso.18013.5.1.mDL",
            claims = claims,
            nonce = ByteString(Random.nextBytes(16)),
            origin = "https://example.com",
            responseEncryptionKey = responseEncryptionKey,
            verifierIdentities = listOf(identity),
            zkSystemSpecs = emptyList(),
            issuerIdentifiers = listOf(ByteString(1, 2, 3)),
            deviceRequestVersion = "1.1"
        )
        val requestEntry = requestJson["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == "org-iso-mdoc" }
            .jsonObject
        val base64DeviceRequest = requestEntry["data"]!!.jsonObject["deviceRequest"]!!.jsonPrimitive.content
        val dataItem = Cbor.decode(base64DeviceRequest.fromBase64Url())
        val dr = DeviceRequest.fromDataItem(dataItem)

        assertEquals("1.1", dr.version)
        assertEquals("1.1", dataItem["version"].asTstr)
        assertNotNull(dataItem.getOrNull("deviceRequestInfo"))
        assertNotNull(dataItem.getOrNull("readerAuthAll"))
        assertNotNull(dr.deviceRequestInfo)
        val docRequestDataItem = dataItem["docRequests"].asArray[0]
        val itemsRequest = docRequestDataItem["itemsRequest"].asTaggedEncodedCbor
        assertNotNull(itemsRequest.getOrNull("requestInfo"))
    }

    @Test
    fun generateDcRequestMdoc_defaultVersion() = runTest {
        val identity = createTestVerifierIdentity()
        val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val claims = listOf(
            MdocRequestedClaim(
                docType = "org.iso.18013.5.1.mDL",
                namespaceName = "org.iso.18013.5.1",
                dataElementName = "given_name",
                intentToRetain = false
            )
        )
        // Omit deviceRequestVersion to test that null defaults to latest version ("1.1")
        val requestJson = VerificationUtil.generateDcRequestMdoc(
            exchangeProtocols = listOf("org-iso-mdoc"),
            docType = "org.iso.18013.5.1.mDL",
            claims = claims,
            nonce = ByteString(Random.nextBytes(16)),
            origin = "https://example.com",
            responseEncryptionKey = responseEncryptionKey,
            verifierIdentities = listOf(identity),
            zkSystemSpecs = emptyList(),
        )
        val requestEntry = requestJson["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == "org-iso-mdoc" }
            .jsonObject
        val base64DeviceRequest = requestEntry["data"]!!.jsonObject["deviceRequest"]!!.jsonPrimitive.content
        val dataItem = Cbor.decode(base64DeviceRequest.fromBase64Url())
        val dr = DeviceRequest.fromDataItem(dataItem)

        assertEquals("1.1", dr.version)
        assertEquals("1.1", dataItem["version"].asTstr)
        assertNotNull(dataItem.getOrNull("deviceRequestInfo"))
        assertNotNull(dataItem.getOrNull("readerAuthAll"))
    }

    @Test
    fun generateDcRequestDcql_version10() = runTest {
        val identity = createTestVerifierIdentity()
        val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val dcqlJson = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                  "id": "mDL",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "given_name"] }
                  ]
              }]
            }
            """.trimIndent()
        )
        val requestJson = VerificationUtil.generateDcRequestDcql(
            exchangeProtocols = listOf("org-iso-mdoc"),
            dcql = dcqlJson,
            nonce = ByteString(Random.nextBytes(16)),
            origin = "https://example.com",
            responseEncryptionKey = responseEncryptionKey,
            verifierIdentities = listOf(identity),
            deviceRequestVersion = "1.0"
        )
        val requestEntry = requestJson["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == "org-iso-mdoc" }
            .jsonObject
        val base64DeviceRequest = requestEntry["data"]!!.jsonObject["deviceRequest"]!!.jsonPrimitive.content
        val dataItem = Cbor.decode(base64DeviceRequest.fromBase64Url())
        val dr = DeviceRequest.fromDataItem(dataItem)

        assertEquals("1.0", dr.version)
        assertEquals("1.0", dataItem["version"].asTstr)
        assertNull(dataItem.getOrNull("deviceRequestInfo"))
        assertNull(dataItem.getOrNull("readerAuthAll"))
        assertNull(dr.deviceRequestInfo)
        val docRequestDataItem = dataItem["docRequests"].asArray[0]
        val itemsRequest = docRequestDataItem["itemsRequest"].asTaggedEncodedCbor
        assertNull(itemsRequest.getOrNull("requestInfo"))
    }

    @Test
    fun generateDcRequestDcql_version11() = runTest {
        val identity = createTestVerifierIdentity()
        val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val dcqlJson = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                  "id": "mDL",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "given_name"] }
                  ]
              }]
            }
            """.trimIndent()
        )
        val requestJson = VerificationUtil.generateDcRequestDcql(
            exchangeProtocols = listOf("org-iso-mdoc"),
            dcql = dcqlJson,
            nonce = ByteString(Random.nextBytes(16)),
            origin = "https://example.com",
            responseEncryptionKey = responseEncryptionKey,
            verifierIdentities = listOf(identity),
            deviceRequestVersion = "1.1"
        )
        val requestEntry = requestJson["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == "org-iso-mdoc" }
            .jsonObject
        val base64DeviceRequest = requestEntry["data"]!!.jsonObject["deviceRequest"]!!.jsonPrimitive.content
        val dataItem = Cbor.decode(base64DeviceRequest.fromBase64Url())
        val dr = DeviceRequest.fromDataItem(dataItem)

        assertEquals("1.1", dr.version)
        assertEquals("1.1", dataItem["version"].asTstr)
        assertNotNull(dataItem.getOrNull("deviceRequestInfo"))
        assertNotNull(dataItem.getOrNull("readerAuthAll"))
        assertNotNull(dr.deviceRequestInfo)
    }

    @Test
    fun generateDcRequestDcql_defaultVersion() = runTest {
        val identity = createTestVerifierIdentity()
        val responseEncryptionKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val dcqlJson = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                  "id": "mDL",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "claims": [
                    { "path": ["org.iso.18013.5.1", "given_name"] }
                  ]
              }]
            }
            """.trimIndent()
        )
        // Omit deviceRequestVersion to test that null defaults to latest version ("1.1")
        val requestJson = VerificationUtil.generateDcRequestDcql(
            exchangeProtocols = listOf("org-iso-mdoc"),
            dcql = dcqlJson,
            nonce = ByteString(Random.nextBytes(16)),
            origin = "https://example.com",
            responseEncryptionKey = responseEncryptionKey,
            verifierIdentities = listOf(identity),
        )
        val requestEntry = requestJson["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == "org-iso-mdoc" }
            .jsonObject
        val base64DeviceRequest = requestEntry["data"]!!.jsonObject["deviceRequest"]!!.jsonPrimitive.content
        val dataItem = Cbor.decode(base64DeviceRequest.fromBase64Url())
        val dr = DeviceRequest.fromDataItem(dataItem)

        assertEquals("1.1", dr.version)
        assertEquals("1.1", dataItem["version"].asTstr)
        assertNotNull(dataItem.getOrNull("deviceRequestInfo"))
        assertNotNull(dataItem.getOrNull("readerAuthAll"))
        assertNotNull(dr.deviceRequestInfo)
    }
}
