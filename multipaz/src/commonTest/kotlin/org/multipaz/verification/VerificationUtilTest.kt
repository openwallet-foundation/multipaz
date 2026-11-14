package org.multipaz.verification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.toDataItem
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.ZkRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.zkp.ProofVerificationFailureException
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.mdoc.zkp.ZkDocumentData
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.truncateToWholeSeconds

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

    @Test
    fun dcqlProcessedResponse_includesDeviceSignedClaims() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now()
        val presentation = MdocVerifiedPresentation(
            documentSignerCertChain = identity.key.certChain,
            issuerSignedClaims = listOf(
                MdocClaim(
                    displayName = "Given Name",
                    attribute = null,
                    docType = "org.iso.18013.5.1.mDL",
                    namespaceName = "org.iso.18013.5.1",
                    dataElementName = "given_name",
                    value = "Erika".toDataItem(),
                )
            ),
            deviceSignedClaims = listOf(
                MdocClaim(
                    displayName = "Credential Holder Verification",
                    attribute = null,
                    docType = "org.iso.18013.5.1.mDL",
                    namespaceName = "org.iso.23220.5.1",
                    dataElementName = "CHV_1",
                    value = Simple.TRUE,
                )
            ),
            zkpUsed = false,
            validFrom = now,
            validUntil = now + 365.days,
            expectedUpdate = null,
            signedAt = now,
            docType = "org.iso.18013.5.1.mDL",
            transactionResponses = null,
            vpTokenIdentifier = null,
            transactionData = emptyList(),
        )

        val dcql = """
            {
              "credentials": [{
                  "id": "my_mdl",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "claims": [
                    { "id": "claim_given_name", "path": ["org.iso.18013.5.1", "given_name"] },
                    { "id": "claim_chv1", "path": ["org.iso.23220.5.1", "CHV_1"] }
                  ]
              }]
            }
        """.trimIndent()

        val processed = DcqlProcessedResponse(dcql, listOf(presentation))
        val cred = processed.credentials["my_mdl"]
        assertNotNull(cred)
        assertEquals(1, cred.issuerSignedClaims.size)
        assertTrue(cred.issuerSignedClaims.containsKey("claim_given_name"))

        val givenNameClaim = cred.issuerSignedClaims["claim_given_name"] as MdocClaim
        assertEquals("given_name", givenNameClaim.dataElementName)
        assertEquals("Erika", givenNameClaim.value.asTstr)

        assertEquals(1, cred.deviceSignedClaims.size)
        assertTrue(cred.deviceSignedClaims.containsKey("claim_chv1"))

        val chv1Claim = cred.deviceSignedClaims["claim_chv1"] as MdocClaim
        assertEquals("CHV_1", chv1Claim.dataElementName)
        assertEquals(Simple.TRUE, chv1Claim.value)
    }

    private fun createMockZkSystem(specs: List<ZkSystemSpec>): ZkSystem = object : ZkSystem {
        override val name: String = specs.first().system
        override val systemSpecs: List<ZkSystemSpec> = specs
        override fun generateProof(
            zkSystemSpec: ZkSystemSpec,
            document: MdocDocument,
            sessionTranscript: DataItem,
            timestamp: Instant,
        ): ZkDocument = throw UnsupportedOperationException()

        override fun verifyProof(
            zkDocument: ZkDocument,
            zkSystemSpec: ZkSystemSpec,
            sessionTranscript: DataItem,
            atTime: Instant,
            maxTimestampDrift: Duration,
        ) {}

        override fun getMatchingSystemSpec(
            zkSystemSpecs: List<ZkSystemSpec>,
            requestedClaims: List<RequestedClaim>,
        ): ZkSystemSpec = specs.first()
    }

    @Test
    fun verifyMdocDeviceResponse_zkDocumentSeparatesDeviceSignedClaims() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now().truncateToWholeSeconds()
        val spec = ZkSystemSpec(id = "mock_spec_1", system = "mock_zk")
        val zkRepo = ZkSystemRepository().add(createMockZkSystem(listOf(spec)))
        val zkDoc = ZkDocument(
            documentData = ZkDocumentData(
                zkSystemSpecId = spec.id,
                docType = "org.iso.18013.5.1.mDL",
                timestamp = now,
                issuerSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf("given_name" to "Erika".toDataItem())
                ),
                deviceSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf("given_name" to "Mallory".toDataItem()),
                    "org.iso.23220.5.1" to mapOf("CHV_1" to Simple.TRUE),
                ),
                msoX5chain = identity.key.certChain,
            ),
            proof = ByteString(1, 2, 3),
        )
        val sessionTranscript = Simple.NULL
        val response = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addZkDocument(zkDoc)
        }
        val presentations = VerificationUtil.verifyMdocDeviceResponse(
            now = now,
            deviceResponse = response.toDataItem(),
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            documentTypeRepository = null,
            zkSystemRepository = zkRepo,
            request = null,
        )
        assertEquals(1, presentations.size)
        val vp = presentations.single() as MdocVerifiedPresentation
        assertTrue(vp.zkpUsed)
        assertEquals(spec.id, vp.zkSystemSpecId)
        assertEquals(1, vp.issuerSignedClaims.size)
        assertEquals("Erika", vp.issuerSignedClaims.single().value.asTstr)
        assertEquals(2, vp.deviceSignedClaims.size)

        val dcql = """
            {
              "credentials": [{
                  "id": "my_mdl",
                  "format": "mso_mdoc_zk",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "claims": [
                    { "id": "claim_given_name", "path": ["org.iso.18013.5.1", "given_name"] },
                    { "id": "claim_chv1", "path": ["org.iso.23220.5.1", "CHV_1"] }
                  ]
              }]
            }
        """.trimIndent()
        val processed = DcqlProcessedResponse(dcql, presentations)
        val cred = processed.credentials["my_mdl"]!!
        assertEquals("Erika", (cred.issuerSignedClaims["claim_given_name"] as MdocClaim).value.asTstr)
        assertEquals(Simple.TRUE, (cred.deviceSignedClaims["claim_chv1"] as MdocClaim).value)
    }

    @Test
    fun verifyMdocDeviceResponse_rejectsZkTimestampDrift() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now().truncateToWholeSeconds()
        val spec = ZkSystemSpec(id = "mock_spec_1", system = "mock_zk")
        val zkRepo = ZkSystemRepository().add(createMockZkSystem(listOf(spec)))
        val zkDoc = ZkDocument(
            documentData = ZkDocumentData(
                zkSystemSpecId = spec.id,
                docType = "org.iso.18013.5.1.mDL",
                timestamp = now,
                issuerSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf("given_name" to "Erika".toDataItem())
                ),
                deviceSigned = emptyMap(),
                msoX5chain = identity.key.certChain,
            ),
            proof = ByteString(1, 2, 3),
        )
        val sessionTranscript = Simple.NULL
        val responseDataItem = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addZkDocument(zkDoc)
        }.toDataItem()

        assertFailsWith<ProofVerificationFailureException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now + 10.minutes,
                deviceResponse = responseDataItem,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = null,
            )
        }
        assertFailsWith<ProofVerificationFailureException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now - 10.minutes,
                deviceResponse = responseDataItem,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = null,
            )
        }
    }

    @Test
    fun dcqlAndOpenId4Vp_enforcesZkFormatAndSystemSpec() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now().truncateToWholeSeconds()
        val spec1 = ZkSystemSpec(id = "mock_spec_1", system = "mock_zk")
        val spec2 = ZkSystemSpec(id = "mock_spec_2", system = "mock_zk")
        val zkRepo = ZkSystemRepository().add(createMockZkSystem(listOf(spec1, spec2)))

        val nonZkPresentation = MdocVerifiedPresentation(
            documentSignerCertChain = identity.key.certChain,
            issuerSignedClaims = listOf(
                MdocClaim(
                    displayName = "Given Name",
                    attribute = null,
                    docType = "org.iso.18013.5.1.mDL",
                    namespaceName = "org.iso.18013.5.1",
                    dataElementName = "given_name",
                    value = "Erika".toDataItem(),
                )
            ),
            deviceSignedClaims = emptyList(),
            zkpUsed = false,
            validFrom = now,
            validUntil = now + 365.days,
            expectedUpdate = null,
            signedAt = now,
            docType = "org.iso.18013.5.1.mDL",
            transactionResponses = null,
            vpTokenIdentifier = "my_mdl",
            transactionData = emptyList(),
        )

        val zkDocSpec2 = ZkDocument(
            documentData = ZkDocumentData(
                zkSystemSpecId = spec2.id,
                docType = "org.iso.18013.5.1.mDL",
                timestamp = now,
                issuerSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf("given_name" to "Erika".toDataItem())
                ),
                deviceSigned = emptyMap(),
                msoX5chain = identity.key.certChain,
            ),
            proof = ByteString(1, 2, 3),
        )
        val sessionTranscript = Simple.NULL
        val zkResponseSpec2Bytes = Cbor.encode(
            buildDeviceResponse(
                sessionTranscript = sessionTranscript,
                status = DeviceResponse.STATUS_OK,
            ) {
                addZkDocument(zkDocSpec2)
            }.toDataItem()
        ).toBase64Url()

        val zkDocSpec1 = ZkDocument(
            documentData = zkDocSpec2.documentData.copy(zkSystemSpecId = spec1.id),
            proof = ByteString(1, 2, 3),
        )
        val zkResponseSpec1Bytes = Cbor.encode(
            buildDeviceResponse(
                sessionTranscript = sessionTranscript,
                status = DeviceResponse.STATUS_OK,
            ) {
                addZkDocument(zkDocSpec1)
            }.toDataItem()
        ).toBase64Url()

        val nonZkDcql = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                  "id": "my_mdl",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "claims": [{ "path": ["org.iso.18013.5.1", "given_name"] }]
              }]
            }
            """.trimIndent()
        )
        val zkDcqlRestricted = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                  "id": "my_mdl",
                  "format": "mso_mdoc_zk",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL",
                    "zk_system_type": [{ "system": "mock_zk", "id": "mock_spec_1" }]
                  },
                  "claims": [{ "path": ["org.iso.18013.5.1", "given_name"] }]
              }]
            }
            """.trimIndent()
        )

        // mso_mdoc_zk DCQL rejects non-ZK presentation
        assertFailsWith<IllegalStateException> {
            DcqlProcessedResponse(zkDcqlRestricted.toString(), listOf(nonZkPresentation))
        }

        // OpenID4VP mso_mdoc rejects ZK presentation
        assertFailsWith<Exception> {
            VerificationUtil.verifyOpenID4VPResponse(
                now = now,
                vpToken = buildJsonObject {
                    put("my_mdl", JsonArray(listOf(JsonPrimitive(zkResponseSpec1Bytes))))
                },
                sessionTranscript = sessionTranscript,
                nonce = "nonce123",
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                queryData = QueryData.fromDcql(nonZkDcql).associateBy { it.id!! },
                expectedAudience = "https://example.com",
                dcqlQuery = DcqlQuery.fromJson(nonZkDcql),
            )
        }

        // OpenID4VP mso_mdoc_zk rejects unrequested zk_system_type
        assertFailsWith<Exception> {
            VerificationUtil.verifyOpenID4VPResponse(
                now = now,
                vpToken = buildJsonObject {
                    put("my_mdl", JsonArray(listOf(JsonPrimitive(zkResponseSpec2Bytes))))
                },
                sessionTranscript = sessionTranscript,
                nonce = "nonce123",
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                queryData = QueryData.fromDcql(zkDcqlRestricted).associateBy { it.id!! },
                expectedAudience = "https://example.com",
                dcqlQuery = DcqlQuery.fromJson(zkDcqlRestricted),
            )
        }

        // OpenID4VP mso_mdoc_zk accepts matching zk_system_type
        val verified = VerificationUtil.verifyOpenID4VPResponse(
            now = now,
            vpToken = buildJsonObject {
                put("my_mdl", JsonArray(listOf(JsonPrimitive(zkResponseSpec1Bytes))))
            },
            sessionTranscript = sessionTranscript,
            nonce = "nonce123",
            documentTypeRepository = null,
            zkSystemRepository = zkRepo,
            queryData = QueryData.fromDcql(zkDcqlRestricted).associateBy { it.id!! },
            expectedAudience = "https://example.com",
            dcqlQuery = DcqlQuery.fromJson(zkDcqlRestricted),
        )
        assertEquals(1, verified.size)
        val zkPresentation = verified.single() as MdocVerifiedPresentation
        assertTrue(zkPresentation.zkpUsed)
        assertEquals(spec1.id, zkPresentation.zkSystemSpecId)

        // mso_mdoc DCQL rejects ZK presentation
        assertFailsWith<IllegalStateException> {
            DcqlProcessedResponse(nonZkDcql.toString(), verified)
        }
    }

    @Test
    fun decryptDcResponse_enforcesEncryptionAndSupportsOpenId4VpModes() = runTest {
        val identity = createTestVerifierIdentity()
        val encryptionPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val responseEncryptionKey = AsymmetricKey.anonymous(encryptionPrivateKey)
        val nonce = ByteString(Random.nextBytes(16))
        val origin = "https://example.com"

        // 1. generateDcRequestDcql supports openid4vp-v1-multisigned
        val dcqlJson = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [{
                  "id": "my_mdl",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "claims": [{ "path": ["org.iso.18013.5.1", "given_name"] }]
              }]
            }
            """.trimIndent()
        )
        val multisignedReq = VerificationUtil.generateDcRequestDcql(
            exchangeProtocols = listOf("openid4vp-v1-multisigned"),
            dcql = dcqlJson,
            nonce = nonce,
            origin = origin,
            responseEncryptionKey = null,
            verifierIdentities = listOf(identity),
        )
        assertEquals(
            "openid4vp-v1-multisigned",
            multisignedReq["requests"]!!.jsonArray.single().jsonObject["protocol"]!!.jsonPrimitive.content
        )

        // 2. Plaintext 3-part JWT downgrade is rejected when responseEncryptionKey != null
        val headerB64 = """{"alg":"none"}""".encodeToByteArray().toBase64Url()
        val payloadB64 = """{"vp_token":{"my_mdl":["abc"]}}""".encodeToByteArray().toBase64Url()
        val unsecuredJwt = "$headerB64.$payloadB64."
        val downgradeResponse = buildJsonObject {
            put("protocol", "openid4vp-v1-signed")
            putJsonObject("data") {
                put("response", unsecuredJwt)
            }
        }
        assertFailsWith<Exception> {
            VerificationUtil.decryptDcResponse(
                response = downgradeResponse,
                nonce = nonce,
                origin = origin,
                responseEncryptionKey = responseEncryptionKey,
            )
        }

        // 3. Plaintext vp_token without 'response' is rejected when responseEncryptionKey != null
        val unencryptedV1Response = buildJsonObject {
            put("protocol", "openid4vp-v1-multisigned")
            putJsonObject("data") {
                putJsonObject("vp_token") {
                    put("my_mdl", JsonArray(listOf(JsonPrimitive("abc"))))
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            VerificationUtil.decryptDcResponse(
                response = unencryptedV1Response,
                nonce = nonce,
                origin = origin,
                responseEncryptionKey = responseEncryptionKey,
            )
        }

        // 4. Unencrypted OpenID4VP 1.0 dc_api (vp_token directly in data) succeeds when key == null
        val parsedUnencrypted = VerificationUtil.decryptDcResponse(
            response = unencryptedV1Response,
            nonce = nonce,
            origin = origin,
            responseEncryptionKey = null,
        ) as OpenID4VPDcResponse
        assertEquals("abc", parsedUnencrypted.vpToken["my_mdl"]!!.jsonArray.single().jsonPrimitive.content)

        // 5. Draft 24 explicit clientId changes sessionTranscript handover vs web-origin default
        val draft24Response = buildJsonObject {
            put("protocol", "openid4vp")
            putJsonObject("data") {
                put("response", unsecuredJwt)
            }
        }
        val defaultClientTranscript = (VerificationUtil.decryptDcResponse(
            response = draft24Response,
            nonce = nonce,
            origin = origin,
            responseEncryptionKey = null,
        ) as OpenID4VPDcResponse).sessionTranscript
        val customClientTranscript = (VerificationUtil.decryptDcResponse(
            response = draft24Response,
            nonce = nonce,
            origin = origin,
            responseEncryptionKey = null,
            clientId = "x509_san_dns:example.com",
        ) as OpenID4VPDcResponse).sessionTranscript
        assertNotEquals(Cbor.encode(defaultClientTranscript).toList(), Cbor.encode(customClientTranscript).toList())
    }

    @Test
    fun dcqlProcessedResponse_enforcesTrustedAuthoritiesAki() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now()
        val matchingAki = identity.key.certChain.certificates.first().authorityKeyIdentifier!!
            .toBase64Url()
        val wrongAki = ByteArray(20) { 0x42 }.toBase64Url()

        val mdocPresentation = MdocVerifiedPresentation(
            documentSignerCertChain = identity.key.certChain,
            issuerSignedClaims = listOf(
                MdocClaim(
                    displayName = "Given Name",
                    attribute = null,
                    docType = "org.iso.18013.5.1.mDL",
                    namespaceName = "org.iso.18013.5.1",
                    dataElementName = "given_name",
                    value = "Erika".toDataItem(),
                )
            ),
            deviceSignedClaims = emptyList(),
            zkpUsed = false,
            validFrom = now,
            validUntil = now + 365.days,
            expectedUpdate = null,
            signedAt = now,
            docType = "org.iso.18013.5.1.mDL",
            transactionResponses = null,
            vpTokenIdentifier = null,
            transactionData = emptyList(),
        )

        fun mdocDcqlWithAki(aki: String) = """
            {
              "credentials": [{
                  "id": "my_mdl",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                  "trusted_authorities": [{ "type": "aki", "values": ["$aki"] }],
                  "claims": [{ "path": ["org.iso.18013.5.1", "given_name"] }]
              }]
            }
        """.trimIndent()

        val matchedMdoc = DcqlProcessedResponse(mdocDcqlWithAki(matchingAki), listOf(mdocPresentation))
        assertNotNull(matchedMdoc.credentials["my_mdl"])

        assertFailsWith<IllegalStateException> {
            DcqlProcessedResponse(mdocDcqlWithAki(wrongAki), listOf(mdocPresentation))
        }

        val jsonPresentation = JsonVerifiedPresentation(
            documentSignerCertChain = identity.key.certChain,
            issuerSignedClaims = listOf(
                JsonClaim(
                    displayName = "given_name",
                    attribute = null,
                    vct = "urn:eudi:pid:1",
                    claimPath = JsonArray(listOf(JsonPrimitive("given_name"))),
                    value = JsonPrimitive("Erika"),
                )
            ),
            deviceSignedClaims = emptyList(),
            zkpUsed = false,
            validFrom = now,
            validUntil = now + 365.days,
            signedAt = now,
            expectedUpdate = null,
            vct = "urn:eudi:pid:1",
            transactionResponses = null,
            vpTokenIdentifier = null,
            transactionData = emptyList(),
        )

        fun sdJwtDcqlWithAki(aki: String) = """
            {
              "credentials": [{
                  "id": "my_pid",
                  "format": "dc+sd-jwt",
                  "meta": { "vct_values": ["urn:eudi:pid:1"] },
                  "trusted_authorities": [{ "type": "aki", "values": ["$aki"] }],
                  "claims": [{ "path": ["given_name"] }]
              }]
            }
        """.trimIndent()

        val matchedJson = DcqlProcessedResponse(sdJwtDcqlWithAki(matchingAki), listOf(jsonPresentation))
        assertNotNull(matchedJson.credentials["my_pid"])

        assertFailsWith<IllegalStateException> {
            DcqlProcessedResponse(sdJwtDcqlWithAki(wrongAki), listOf(jsonPresentation))
        }
    }

    @Test
    fun dcqlProcessedResponse_mapsNestedJsonClaimIds() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now()
        val addressObj = buildJsonObject {
            put("country", "US")
            put("street_address", "123 Main St")
        }
        val jsonPresentation = JsonVerifiedPresentation(
            documentSignerCertChain = identity.key.certChain,
            issuerSignedClaims = listOf(
                JsonClaim(
                    displayName = "address",
                    attribute = null,
                    vct = "urn:eudi:pid:1",
                    claimPath = JsonArray(listOf(JsonPrimitive("address"))),
                    value = addressObj,
                )
            ),
            deviceSignedClaims = emptyList(),
            zkpUsed = false,
            validFrom = now,
            validUntil = now + 365.days,
            signedAt = now,
            expectedUpdate = null,
            vct = "urn:eudi:pid:1",
            transactionResponses = null,
            vpTokenIdentifier = null,
            transactionData = emptyList(),
        )

        val dcql = """
            {
              "credentials": [{
                  "id": "my_pid",
                  "format": "dc+sd-jwt",
                  "meta": { "vct_values": ["urn:eudi:pid:1"] },
                  "claims": [
                    { "id": "resident_country", "path": ["address", "country"] },
                    { "path": ["address", "street_address"] }
                  ]
              }]
            }
        """.trimIndent()

        val processed = DcqlProcessedResponse(dcql, listOf(jsonPresentation))
        val cred = processed.credentials["my_pid"]!!
        val countryClaim = cred.issuerSignedClaims["resident_country"] as JsonClaim
        assertEquals("US", countryClaim.value.jsonPrimitive.content)
        val streetClaim = cred.issuerSignedClaims["street_address"] as JsonClaim
        assertEquals("123 Main St", streetClaim.value.jsonPrimitive.content)
    }

    @Test
    fun verifyMdocDeviceResponse_enforcesDeviceRequestConstraints() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now().truncateToWholeSeconds()
        val spec = ZkSystemSpec(id = "mock_spec_1", system = "mock_zk")
        val zkRepo = ZkSystemRepository().add(createMockZkSystem(listOf(spec)))
        val sessionTranscript = Simple.NULL

        val partialZkDoc = ZkDocument(
            documentData = ZkDocumentData(
                zkSystemSpecId = spec.id,
                docType = "org.iso.18013.5.1.mDL",
                timestamp = now,
                issuerSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf("given_name" to "Erika".toDataItem())
                ),
                deviceSigned = emptyMap(),
                msoX5chain = identity.key.certChain,
            ),
            proof = ByteString(1, 2, 3),
        )
        val fullZkDoc = ZkDocument(
            documentData = partialZkDoc.documentData.copy(
                issuerSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf(
                        "given_name" to "Erika".toDataItem(),
                        "family_name" to "Mustermann".toDataItem(),
                    )
                )
            ),
            proof = ByteString(1, 2, 3),
        )

        // 1. Non-OK DeviceResponse status is rejected
        val errorStatusResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_GENERAL_ERROR,
        ) {}.toDataItem()
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = errorStatusResponse,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = null,
            )
        }

        // 2. Non-ZK DeviceRequest rejects ZkDocument
        val nonZkRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf("org.iso.18013.5.1" to mapOf("given_name" to false)),
            )
        }
        val partialZkResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addZkDocument(partialZkDoc)
        }.toDataItem()
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = partialZkResponse,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = nonZkRequest,
            )
        }

        // 3. ZK DeviceRequest requesting given_name + family_name rejects partial disclosure
        val zkRequestTwoClaims = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf(
                    "org.iso.18013.5.1" to mapOf(
                        "given_name" to false,
                        "family_name" to false,
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    zkRequest = ZkRequest(systemSpecs = listOf(spec), zkRequired = true)
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = partialZkResponse,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = zkRequestTwoClaims,
            )
        }

        // 4. Full disclosure satisfies the ZK DeviceRequest
        val fullZkResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addZkDocument(fullZkDoc)
        }.toDataItem()
        val verified = VerificationUtil.verifyMdocDeviceResponse(
            now = now,
            deviceResponse = fullZkResponse,
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            documentTypeRepository = null,
            zkSystemRepository = zkRepo,
            request = zkRequestTwoClaims,
        )
        assertEquals(1, verified.size)
    }

    @Test
    fun verifyMdocDeviceResponse_andDcqlProcessedResponse_respectDocRequestIds() = runTest {
        val identity = createTestVerifierIdentity()
        val now = Clock.System.now().truncateToWholeSeconds()
        val spec = ZkSystemSpec(id = "mock_spec_1", system = "mock_zk")
        val zkRepo = ZkSystemRepository().add(createMockZkSystem(listOf(spec)))
        val sessionTranscript = Simple.NULL

        val partialZkDoc = ZkDocument(
            documentData = ZkDocumentData(
                zkSystemSpecId = spec.id,
                docType = "org.iso.18013.5.1.mDL",
                timestamp = now,
                issuerSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf("given_name" to "Erika".toDataItem())
                ),
                deviceSigned = emptyMap(),
                msoX5chain = identity.key.certChain,
            ),
            proof = ByteString(1, 2, 3),
        )
        val fullZkDoc = partialZkDoc.copy(
            documentData = partialZkDoc.documentData.copy(
                issuerSigned = mapOf(
                    "org.iso.18013.5.1" to mapOf(
                        "given_name" to "Erika".toDataItem(),
                        "family_name" to "Mustermann".toDataItem(),
                    )
                )
            )
        )

        // Request with UseCase allowing [0] OR [1], where 0 asks for given_name and 1 asks for given_name + family_name
        val eitherOrRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0)), DocumentSet(listOf(1))),
                        purposeHints = emptyMap(),
                    )
                )
            )
        ) {
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf("org.iso.18013.5.1" to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(zkRequest = ZkRequest(listOf(spec), zkRequired = true))
            )
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf(
                    "org.iso.18013.5.1" to mapOf("given_name" to false, "family_name" to false)
                ),
                docRequestInfo = DocRequestInfo(zkRequest = ZkRequest(listOf(spec), zkRequired = true))
            )
        }

        // Partial disclosure claiming docRequestIds = [1] is rejected even though [0] would have matched
        val wrongDeclaredIdResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addZkDocument(partialZkDoc.copy(docRequestIds = listOf(1)))
        }.toDataItem()
        val wrongIdError = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = wrongDeclaredIdResponse,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = zkRepo,
                request = eitherOrRequest,
            )
        }
        assertEquals("Presented credential does not satisfy declared DocRequest at index 1", wrongIdError.message)

        // Partial disclosure claiming docRequestIds = [0] succeeds
        val rightDeclaredIdResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addZkDocument(partialZkDoc.copy(docRequestIds = listOf(0)))
        }.toDataItem()
        val verifiedSingle = VerificationUtil.verifyMdocDeviceResponse(
            now = now,
            deviceResponse = rightDeclaredIdResponse,
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            documentTypeRepository = null,
            zkSystemRepository = zkRepo,
            request = eitherOrRequest,
        )
        assertEquals(listOf(0), verifiedSingle.single().docRequestIds)

        // Request requiring BOTH [0, 1] satisfied by a single presentation declaring docRequestIds = [0, 1]
        val bothRequiredRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0, 1))),
                        purposeHints = emptyMap(),
                    )
                )
            )
        ) {
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf("org.iso.18013.5.1" to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(zkRequest = ZkRequest(listOf(spec), zkRequired = true))
            )
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf("org.iso.18013.5.1" to mapOf("family_name" to false)),
                docRequestInfo = DocRequestInfo(zkRequest = ZkRequest(listOf(spec), zkRequired = true))
            )
        }
        val sharedPresentationResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addZkDocument(fullZkDoc.copy(docRequestIds = listOf(0, 1)))
        }.toDataItem()
        val verifiedShared = VerificationUtil.verifyMdocDeviceResponse(
            now = now,
            deviceResponse = sharedPresentationResponse,
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            documentTypeRepository = null,
            zkSystemRepository = zkRepo,
            request = bothRequiredRequest,
        )
        assertEquals(listOf(0, 1), verifiedShared.single().docRequestIds)

        // DcqlProcessedResponse maps duplicate docType queries via docRequestIds (both separate and shared [0, 1])
        val duplicateMdocDcql = """
            {
              "credentials": [
                {
                  "id": "mdl_first",
                  "format": "mso_mdoc_zk",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL",
                    "zk_system_type": [{ "system": "mock_zk", "id": "mock_spec_1" }]
                  },
                  "claims": [{ "id": "first_given", "path": ["org.iso.18013.5.1", "given_name"] }]
                },
                {
                  "id": "mdl_second",
                  "format": "mso_mdoc_zk",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL",
                    "zk_system_type": [{ "system": "mock_zk", "id": "mock_spec_1" }]
                  },
                  "claims": [{ "id": "second_family", "path": ["org.iso.18013.5.1", "family_name"] }]
                }
              ]
            }
        """.trimIndent()

        // Without docRequestIds, duplicate docType fails
        val withoutIds = (verifiedShared.single() as MdocVerifiedPresentation).copy(docRequestIds = null)
        assertFailsWith<IllegalStateException> {
            DcqlProcessedResponse(duplicateMdocDcql, listOf(withoutIds))
        }

        // With docRequestIds = [0, 1], single presentation maps to both mdl_first and mdl_second
        val processedShared = DcqlProcessedResponse(duplicateMdocDcql, verifiedShared)
        assertEquals(
            "Erika",
            (processedShared.credentials["mdl_first"]!!.issuerSignedClaims["first_given"] as MdocClaim).value.asTstr
        )
        assertEquals(
            "Mustermann",
            (processedShared.credentials["mdl_second"]!!.issuerSignedClaims["second_family"] as MdocClaim).value.asTstr
        )

        // Duplicate VCT dc+sd-jwt queries with distinct presentations carrying docRequestIds = [0] and [1]
        val duplicateSdJwtDcql = """
            {
              "credentials": [
                {
                  "id": "pid_primary",
                  "format": "dc+sd-jwt",
                  "meta": { "vct_values": ["urn:eudi:pid:1"] },
                  "claims": [{ "path": ["given_name"] }]
                },
                {
                  "id": "pid_secondary",
                  "format": "dc+sd-jwt",
                  "meta": { "vct_values": ["urn:eudi:pid:1"] },
                  "claims": [{ "path": ["given_name"] }]
                }
              ]
            }
        """.trimIndent()
        fun makePidPresentation(name: String, reqIds: List<Int>?) = JsonVerifiedPresentation(
            documentSignerCertChain = identity.key.certChain,
            issuerSignedClaims = listOf(
                JsonClaim("given_name", null, "urn:eudi:pid:1", JsonArray(listOf(JsonPrimitive("given_name"))), JsonPrimitive(name))
            ),
            deviceSignedClaims = emptyList(),
            zkpUsed = false,
            validFrom = now,
            validUntil = now + 365.days,
            signedAt = now,
            expectedUpdate = null,
            vct = "urn:eudi:pid:1",
            transactionResponses = null,
            vpTokenIdentifier = null,
            transactionData = emptyList(),
            docRequestIds = reqIds,
        )
        val processedSdJwt = DcqlProcessedResponse(
            duplicateSdJwtDcql,
            listOf(
                makePidPresentation("Alice", listOf(0)),
                makePidPresentation("Bob", listOf(1)),
            )
        )
        assertEquals(
            "Alice",
            (processedSdJwt.credentials["pid_primary"]!!.issuerSignedClaims["given_name"] as JsonClaim).value.jsonPrimitive.content
        )
        assertEquals(
            "Bob",
            (processedSdJwt.credentials["pid_secondary"]!!.issuerSignedClaims["given_name"] as JsonClaim).value.jsonPrimitive.content
        )
    }
}

