package org.multipaz.presentment

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert
import org.junit.Test
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.utopia.knowntypes.PingTransaction
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.TransactionsInfo
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.digitalcredentials.calculateCredentialDatabase
import org.multipaz.digitalcredentials.getDefault
import org.multipaz.document.setAndroidCredmanExchangeProtocols
import org.multipaz.mdoc.request.buildDeviceRequestFromDcql
import org.multipaz.openid.OpenID4VP
import org.multipaz.util.Logger
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import org.multipaz.verification.VerifierIdentity
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

// Tests for the matcher in multipaz-models/src/androidMain/matcher ...
class MatcherTest {
    companion object {
        private const val TAG = "MatcherTest"

        private const val CLIENT_ID = "x509_san_dns:verifier.multipaz.org"
        private const val ORIGIN = "https://verifier.multipaz.org"
    }

    init {
        System.loadLibrary("MatcherTest")
    }

    external fun runMatcher(
        request: ByteArray,
        credentialDatabase: ByteArray,
    ): String

    suspend fun testMatcherDcql(
        version: OpenID4VP.Version,
        signRequest: Boolean,
        encryptionKey: EcPrivateKey?,
        harnessInitializer: suspend (harness: DocumentStoreTestHarness) -> Unit,
        dcql: String,
        readerAuthKeyProvider: ((harness: DocumentStoreTestHarness) -> AsymmetricKey.X509Certified?)? = null,
    ): String {
        return testMatcherDcql(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            harnessInitializer = harnessInitializer,
            dcqlProvider = { dcql },
            readerAuthKeyProvider = readerAuthKeyProvider
        )
    }

    suspend fun testMatcherDcql(
        version: OpenID4VP.Version,
        signRequest: Boolean,
        encryptionKey: EcPrivateKey?,
        harnessInitializer: suspend (harness: DocumentStoreTestHarness) -> Unit,
        dcqlProvider: (harness: DocumentStoreTestHarness) -> String,
        readerAuthKeyProvider: ((harness: DocumentStoreTestHarness) -> AsymmetricKey.X509Certified?)? = null,
    ): String {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harnessInitializer(harness)

        val dcql = dcqlProvider(harness)
        val nonce = Random.nextBytes(16).toBase64Url()
        val readerAuthKey = if (readerAuthKeyProvider != null) {
            readerAuthKeyProvider(harness)
        } else if (signRequest) {
            val key = Crypto.createEcPrivateKey(EcCurve.P256)
            val readerRootCert = harness.readerRootKey.certChain.certificates.first()
            val cert = MdocUtil.generateReaderCertificate(
                readerRootKey = harness.readerRootKey,
                readerKey = key.publicKey,
                subject = X500Name.fromName("CN=Multipaz Reader Cert Single-Use key"),
                dnsName = "localhost",
                serial = ASN1Integer.fromRandom(128),
                validFrom = readerRootCert.validityNotBefore,
                validUntil = readerRootCert.validityNotAfter
            )
            AsymmetricKey.X509CertifiedExplicit(
                privateKey = key,
                certChain = X509CertChain(listOf(cert) + harness.readerRootKey.certChain.certificates)
            )
        } else {
            null
        }

        val requestData = OpenID4VP.generateRequest(
            version = version,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = encryptionKey?.publicKey,
            verifierIdentities = buildList {
                readerAuthKey?.let { add(VerifierIdentity(it, CLIENT_ID)) }
            },
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = Json.decodeFromString(JsonObject.serializer(), dcql)
        )
        val protocolName = when (version) {
            OpenID4VP.Version.DRAFT_24 -> "openid4vp"
            OpenID4VP.Version.DRAFT_29 -> if (signRequest) "openid4vp-v1-signed" else "openid4vp-v1-unsigned"
        }

        val credentialDatabase = calculateCredentialDatabase(
            appName = "Test App",
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            selectedProtocols = DigitalCredentials.getDefault().supportedProtocols
        )

        var result = runMatcher(
            request = buildJsonObject {
                putJsonArray("requests") {
                    addJsonObject {
                        put("protocol", protocolName)
                        put("data", requestData)
                    }
                }
            }.toString().encodeToByteArray(),
            credentialDatabase = Cbor.encode(credentialDatabase)
        )
        // To get stable output, replace all document IDs with displayName
        for (docId in harness.documentStore.listDocumentIds()) {
            val doc = harness.documentStore.lookupDocument(docId)!!
            result = result.replace(docId, "__${doc.displayName!!}__")
        }
        return result
    }

    suspend fun testMatcherIso18013(
        harnessInitializer: suspend (harness: DocumentStoreTestHarness) -> Unit,
        deviceRequestBuilder: suspend (harness: DocumentStoreTestHarness, sessionTranscript: DataItem) -> DeviceRequest,
    ): String {
        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harnessInitializer(harness)

        val nonce = Random.nextBytes(16).toBase64Url()
        val encryptionInfo = buildCborArray {
            add("dcapi")
            addCborMap {
                put("nonce", nonce.toByteArray())
                put("recipientPublicKey", encryptionKey.toCoseKey().toDataItem())
            }
        }
        val base64EncryptionInfo = Cbor.encode(encryptionInfo).toBase64Url()
        val dcapiInfo = buildCborArray {
            add(base64EncryptionInfo)
            add(ORIGIN)
        }
        val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
        val sessionTranscript = buildCborArray {
            add(Simple.NULL) // DeviceEngagementBytes
            add(Simple.NULL) // EReaderKeyBytes
            addCborArray {
                add("dcapi")
                add(dcapiInfoDigest)
            }
        }

        val deviceRequest = deviceRequestBuilder(harness, sessionTranscript)
        val base64DeviceRequest = Cbor.encode(deviceRequest.toDataItem()).toBase64Url()
        Logger.iCbor(TAG, "deviceRequest", deviceRequest.toDataItem())

        val credentialDatabase = calculateCredentialDatabase(
            appName = "Test App",
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            selectedProtocols = DigitalCredentials.getDefault().supportedProtocols,
        )

        var result = runMatcher(
            request = buildJsonObject {
                putJsonArray("requests") {
                    addJsonObject {
                        put("protocol", "org-iso-mdoc")
                        putJsonObject("data") {
                            put("deviceRequest", base64DeviceRequest)
                            put("encryptionInfo", base64EncryptionInfo)
                        }
                    }
                }
            }.toString().encodeToByteArray(),
            credentialDatabase = Cbor.encode(credentialDatabase)
        )
        // To get stable output, replace all document IDs with displayName
        for (docId in harness.documentStore.listDocumentIds()) {
            val doc = harness.documentStore.lookupDocument(docId)!!
            result = result.replace(docId, "__${doc.displayName!!}__")
        }
        return result
    }

    suspend fun testMatcherIso18013(
        signRequest: Boolean,
        harnessInitializer: suspend (harness: DocumentStoreTestHarness) -> Unit,
        dcql: String,
        readerAuthKeyProvider: ((harness: DocumentStoreTestHarness) -> AsymmetricKey.X509Certified?)? = null,
    ): String {
        return testMatcherIso18013(harnessInitializer) { harness, sessionTranscript ->
            val readerAuthKey = if (readerAuthKeyProvider != null) {
                readerAuthKeyProvider(harness)
            } else if (signRequest) {
                val key = Crypto.createEcPrivateKey(EcCurve.P256)
                val readerRootCert = harness.readerRootKey.certChain.certificates.first()
                val cert = MdocUtil.generateReaderCertificate(
                    readerRootKey = harness.readerRootKey,
                    readerKey = key.publicKey,
                    subject = X500Name.fromName("CN=Multipaz Reader Cert Single-Use key"),
                    dnsName = "localhost",
                    serial = ASN1Integer.fromRandom(128),
                    validFrom = readerRootCert.validityNotBefore,
                    validUntil = readerRootCert.validityNotAfter
                )
                AsymmetricKey.X509CertifiedExplicit(
                    privateKey = key,
                    certChain = X509CertChain(listOf(cert) + harness.readerRootKey.certChain.certificates)
                )
            } else {
                null
            }

            buildDeviceRequestFromDcql(
                sessionTranscript = sessionTranscript,
                dcqlString = dcql,
            ) {
                if (readerAuthKey != null) {
                    addReaderAuthAll(readerAuthKey)
                }
            }
        }
    }

    @Test
    fun testMatcher_OpenID4VP_mDL_simple() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
                """
                    {
                      "credentials": [{
                          "id": "mDL",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                          "claims": [
                            { "path": ["org.iso.18013.5.1", "age_over_21"] },
                            { "path": ["org.iso.18013.5.1", "portrait"] }
                    ]}]}
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __mDL__
                    Older than 21 years: true
                    Photo of holder: 5318 bytes
                """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    /**
     * When a request lists several protocols as alternatives, a document must still be offered for a
     * supported protocol even if a recognized-but-unsupported protocol is listed first. Here the mDL
     * is exported for org-iso-mdoc only, and the request lists the (draft) openid4vp protocol before
     * org-iso-mdoc. The matcher must fall through to org-iso-mdoc and offer the mDL, instead of
     * stopping at the first recognized protocol (which matches no credential here).
     */
    @Test
    fun testMatcher_protocolOrder_fallsThroughToSupported() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()
        // The mDL is exported for the ISO mdoc protocol only, not the (draft) openid4vp protocol.
        harness.docMdl.setAndroidCredmanExchangeProtocols(listOf("org-iso-mdoc"))

        val dcql =
            """
                {
                  "credentials": [{
                      "id": "mdl",
                      "format": "mso_mdoc",
                      "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                      "claims": [
                        { "path": ["org.iso.18013.5.1", "given_name"] },
                        { "path": ["org.iso.18013.5.1", "family_name"] }
                ]}]}
            """.trimIndent().trim()

        val nonce = Random.nextBytes(16).toBase64Url()

        // First alternative: the (draft) openid4vp protocol (unsigned) — not supported by the mDL.
        val openid4vpData = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_24,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = Json.decodeFromString(JsonObject.serializer(), dcql)
        )

        // Second alternative: the org-iso-mdoc protocol — supported by the mDL.
        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionInfo = buildCborArray {
            add("dcapi")
            addCborMap {
                put("nonce", nonce.toByteArray())
                put("recipientPublicKey", encryptionKey.toCoseKey().toDataItem())
            }
        }
        val base64EncryptionInfo = Cbor.encode(encryptionInfo).toBase64Url()
        val dcapiInfo = buildCborArray {
            add(base64EncryptionInfo)
            add(ORIGIN)
        }
        val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            addCborArray {
                add("dcapi")
                add(dcapiInfoDigest)
            }
        }
        val deviceRequest = buildDeviceRequestFromDcql(
            sessionTranscript = sessionTranscript,
            dcqlString = dcql,
        ) {}
        val base64DeviceRequest = Cbor.encode(deviceRequest.toDataItem()).toBase64Url()

        val credentialDatabase = calculateCredentialDatabase(
            appName = "Test App",
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            selectedProtocols = DigitalCredentials.getDefault().supportedProtocols,
        )

        var result = runMatcher(
            request = buildJsonObject {
                putJsonArray("requests") {
                    // (draft) openid4vp FIRST — recognized but matches no credential here
                    addJsonObject {
                        put("protocol", "openid4vp")
                        put("data", openid4vpData)
                    }
                    // org-iso-mdoc SECOND — the mDL supports this
                    addJsonObject {
                        put("protocol", "org-iso-mdoc")
                        putJsonObject("data") {
                            put("deviceRequest", base64DeviceRequest)
                            put("encryptionInfo", base64EncryptionInfo)
                        }
                    }
                }
            }.toString().encodeToByteArray(),
            credentialDatabase = Cbor.encode(credentialDatabase)
        )
        for (docId in harness.documentStore.listDocumentIds()) {
            val doc = harness.documentStore.lookupDocument(docId)!!
            result = result.replace(docId, "__${doc.displayName!!}__")
        }

        // The matcher falls through from the unmatched openid4vp entry to org-iso-mdoc and offers the
        // mDL. Without the fall-through fix, this result would be empty.
        Assert.assertTrue(
            "Expected the mDL to be offered over org-iso-mdoc, but the matcher output was:\n$result",
            result.contains("org-iso-mdoc") && result.contains("__mDL__")
        )
    }

    @Test
    fun testMatcher_OpenID4VP_sdjwt_simple() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            {
                              "path": [
                                "age_equal_or_over",
                                "18"
                              ]
                            },
                            {
                              "path": [
                                "picture"
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Older than 18: true
                    Photo of holder: Image (5318 bytes)
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Older than 18: true
                    Photo of holder: Image (5318 bytes)
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_mDL_or_PID() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            {
                              "path": [
                                "family_name"
                              ]
                            },
                            {
                              "path": [
                                "given_name"
                              ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [
                              "mdl"
                            ],
                            [
                              "pid"
                            ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __mDL__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Family name: Mustermann
                    Given names: Erika
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Family name: Mustermann
                    Given names: Max
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_mDL_and_PID() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            {
                              "path": [
                                "family_name"
                              ]
                            },
                            {
                              "path": [
                                "given_name"
                              ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [
                              "mdl", "pid"
                            ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __mDL__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 1
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Family name: Mustermann
                    Given names: Erika
                  SetEntry set_index 1
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Family name: Mustermann
                    Given names: Max
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_age_mdocs() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "eu.europa.ec.eudi.pid.1"
                          },
                          "claims": [
                            {
                              "path": [ "eu.europa.ec.eudi.pid.1", "age_over_18" ],
                              "values": [ true ]
                            }
                          ]
                        },
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": ["org.iso.18013.5.1", "age_over_18" ],
                              "values": [ true ]
                            }
                          ]
                        },
                        {
                          "id": "photoid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.23220.photoid.1"
                          },
                          "claims": [
                            {
                              "path": [ "org.iso.23220.1", "age_over_18" ],
                              "values": [ true ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [ "pid" ],
                            [ "mdl" ],
                            [ "photoid" ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Older than 18: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Older than 18: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __mDL__
                    Older than 18 years: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID__
                    Older than 18 years: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID 2__
                    Older than 18 years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    suspend fun addMovieTicket1(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-movie-ticket-1",
            vct = UtopiaMovieTicket.MOVIE_TICKET_VCT,
            data = listOf(
                "ticket_number" to JsonPrimitive("12345"),
                "cinema_id" to JsonPrimitive("abcd")
            ),
        )
    }

    suspend fun addMovieTicket2(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-movie-ticket-2",
            vct = UtopiaMovieTicket.MOVIE_TICKET_VCT,
            data = listOf(
                "ticket_number" to JsonPrimitive("67890"),
                "cinema_id" to JsonPrimitive("efgh")
            ),
        )
    }

    @Test
    fun testMatcher_OpenID4VP_IDs_and_MovieTickets() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
                addMovieTicket1(harness)
                addMovieTicket2(harness)
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            { "path": ["org.iso.18013.5.1", "family_name" ] },
                            { "path": ["org.iso.18013.5.1", "given_name" ] },
                            { "path": ["org.iso.18013.5.1", "portrait" ] }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "eu.europa.ec.eudi.pid.1"
                          },
                          "claims": [
                            { "path": ["eu.europa.ec.eudi.pid.1", "family_name" ] },
                            { "path": ["eu.europa.ec.eudi.pid.1", "given_name" ] },
                            { "path": ["eu.europa.ec.eudi.pid.1", "portrait" ] }
                          ]
                        },
                        {
                          "id": "photoid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.23220.photoid.1"
                          },
                          "claims": [
                            { "path": ["org.iso.23220.1", "family_name" ] },
                            { "path": ["org.iso.23220.1", "given_name" ] },
                            { "path": ["org.iso.23220.1", "portrait" ] }
                          ]
                        },
                        {
                          "id": "movieticket",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://utopia.example.com/vct/movieticket"]
                          },
                          "claims": [
                            {"path": ["ticket_number"]},
                            {"path": ["cinema_id"]}
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [ "mdl" ],
                            [ "pid" ],
                            [ "photoid" ]
                          ]
                        },
                        {
                          "options": [
                            [ "movieticket" ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __mDL__
                    Family name: Mustermann
                    Given names: Erika
                    Photo of holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Family name: Mustermann
                    Given names: Erika
                    Photo of holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Family name: Mustermann
                    Given names: Max
                    Photo of holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID__
                    Family name: Mustermann
                    Given names: Erika
                    Photo of holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID 2__
                    Family name: Mustermann
                    Given names: Max
                    Photo of holder: 5318 bytes
                  SetEntry set_index 1
                    cred_id 0 openid4vp-v1-signed __my-movie-ticket-1__
                    ticket_number: 12345
                    cinema_id: abcd
                  SetEntry set_index 1
                    cred_id 0 openid4vp-v1-signed __my-movie-ticket-2__
                    ticket_number: 67890
                    cinema_id: efgh
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    // Like testMatcher_IDs_and_MovieTickets() but uses
    //
    //                       "credential_sets": [
    //                        {
    //                          "options": [
    //                            [ "mdl", "movieticket" ],
    //                            [ "pid", "movieticket" ],
    //                            [ "photoid", "movieticket" ]
    //                          ]
    //                        }
    //                      ]
    //
    // instead of
    //
    //                       "credential_sets": [
    //                        {
    //                          "options": [
    //                            [ "mdl" ],
    //                            [ "pid" ],
    //                            [ "photoid" ]
    //                          ]
    //                        },
    //                        {
    //                          "options": [
    //                            [ "movieticket" ]
    //                          ]
    //                        }
    //                      ]
    //
    // which leads to a different experience in the Credential Picker b/c of how
    // our set-construction logic is implemented.
    //
    @Test
    fun testMatcher_OpenID4VP_IDs_and_MovieTickets_alternative() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
                addMovieTicket1(harness)
                addMovieTicket2(harness)
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            { "path": ["org.iso.18013.5.1", "family_name" ] },
                            { "path": ["org.iso.18013.5.1", "given_name" ] },
                            { "path": ["org.iso.18013.5.1", "portrait" ] }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "eu.europa.ec.eudi.pid.1"
                          },
                          "claims": [
                            { "path": ["eu.europa.ec.eudi.pid.1", "family_name" ] },
                            { "path": ["eu.europa.ec.eudi.pid.1", "given_name" ] },
                            { "path": ["eu.europa.ec.eudi.pid.1", "portrait" ] }
                          ]
                        },
                        {
                          "id": "photoid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.23220.photoid.1"
                          },
                          "claims": [
                            { "path": ["org.iso.23220.1", "family_name" ] },
                            { "path": ["org.iso.23220.1", "given_name" ] },
                            { "path": ["org.iso.23220.1", "portrait" ] }
                          ]
                        },
                        {
                          "id": "movieticket",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://utopia.example.com/vct/movieticket"]
                          },
                          "claims": [
                            {"path": ["ticket_number"]},
                            {"path": ["cinema_id"]}
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [ "mdl", "movieticket" ],
                            [ "pid", "movieticket" ],
                            [ "photoid", "movieticket" ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __mDL__
                    Family name: Mustermann
                    Given names: Erika
                    Photo of holder: 5318 bytes
                  SetEntry set_index 1
                    cred_id 0 openid4vp-v1-signed __my-movie-ticket-1__
                    ticket_number: 12345
                    cinema_id: abcd
                  SetEntry set_index 1
                    cred_id 0 openid4vp-v1-signed __my-movie-ticket-2__
                    ticket_number: 67890
                    cinema_id: efgh
                Set
                  set_id 1 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 1 openid4vp-v1-signed __EU PID__
                    Family name: Mustermann
                    Given names: Erika
                    Photo of holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 1 openid4vp-v1-signed __EU PID 2__
                    Family name: Mustermann
                    Given names: Max
                    Photo of holder: 5318 bytes
                  SetEntry set_index 1
                    cred_id 1 openid4vp-v1-signed __my-movie-ticket-1__
                    ticket_number: 12345
                    cinema_id: abcd
                  SetEntry set_index 1
                    cred_id 1 openid4vp-v1-signed __my-movie-ticket-2__
                    ticket_number: 67890
                    cinema_id: efgh
                Set
                  set_id 2 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 2 openid4vp-v1-signed __Photo ID__
                    Family name: Mustermann
                    Given names: Erika
                    Photo of holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 2 openid4vp-v1-signed __Photo ID 2__
                    Family name: Mustermann
                    Given names: Max
                    Photo of holder: 5318 bytes
                  SetEntry set_index 1
                    cred_id 2 openid4vp-v1-signed __my-movie-ticket-1__
                    ticket_number: 12345
                    cinema_id: abcd
                  SetEntry set_index 1
                    cred_id 2 openid4vp-v1-signed __my-movie-ticket-2__
                    ticket_number: 67890
                    cinema_id: efgh
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    suspend fun addMdl_with_AgeOver_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = "org.iso.18013.5.1.mDL",
            data = mapOf(
                "org.iso.18013.5.1" to listOf(
                    "given_name" to Tstr("David"),
                    "age_over_18" to true.toDataItem(),
                    "age_in_years" to 48.toDataItem(),
                    "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate()
                )
            )
        )
    }

    suspend fun addMdl_with_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-mDL-no-age-over",
            docType = "org.iso.18013.5.1.mDL",
            data = mapOf(
                "org.iso.18013.5.1" to listOf(
                    "given_name" to Tstr("David"),
                    "age_in_years" to 48.toDataItem(),
                    "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate()
                )
            )
        )
    }

    suspend fun addMdl_with_BirthDate(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-mDL-only-birth-date",
            docType = "org.iso.18013.5.1.mDL",
            data = mapOf(
                "org.iso.18013.5.1" to listOf(
                    "given_name" to Tstr("David"),
                    "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate()
                )
            )
        )
    }

    suspend fun addMdl_with_OnlyName(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-mDL-only-name",
            docType = "org.iso.18013.5.1.mDL",
            data = mapOf(
                "org.iso.18013.5.1" to listOf(
                    "given_name" to Tstr("David"),
                )
            )
        )
    }

    private fun ageMdlQuery(): String {
        return """
            {
              "credentials": [
                {
                  "id": "my_credential",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    {"id": "a", "path": ["org.iso.18013.5.1", "given_name"]},
                    {"id": "b", "path": ["org.iso.18013.5.1", "age_over_18"]},
                    {"id": "c", "path": ["org.iso.18013.5.1", "age_in_years"]},
                    {"id": "d", "path": ["org.iso.18013.5.1", "birth_date"]}
                  ],
                  "claim_sets": [
                    ["a", "b"],
                    ["a", "c"],
                    ["a", "d"]
                  ]
                }
              ]
            }
        """
    }

    @Test
    fun testMatcher_OpenID4VP_ClaimSet_With_AgeOver() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                addMdl_with_AgeOver_AgeInYears_BirthDate(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
            Set
              set_id 0 openid4vp-v1-signed
              SetEntry set_index 0
                cred_id 0 openid4vp-v1-signed __my-mDL__
                Given names: David
                Older than 18 years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_ClaimSet_With_AgeInYears() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                addMdl_with_AgeInYears_BirthDate(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __my-mDL-no-age-over__
                    Given names: David
                    Age in years: 48
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_ClaimSet_With_BirthDate() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                addMdl_with_BirthDate(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __my-mDL-only-birth-date__
                    Given names: David
                    Date of birth: 1976-03-02
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_ClaimSet_With_NoAgeInfo() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                addMdl_with_OnlyName(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
            """.trimIndent().trim(),
            matcherResult
        )
    }

    private suspend fun addCredPid(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-pid",
            vct = "https://credentials.example.com/identity_credential",
            data = listOf(
                "given_name" to JsonPrimitive("Erika"),
                "family_name" to JsonPrimitive("Mustermann"),
                "address" to buildJsonObject {
                    put("street_address", JsonPrimitive("Sample Street 123"))
                },
            )
        )
    }

    private suspend fun addCredPidMax(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-pid-max",
            vct = "https://credentials.example.com/identity_credential",
            data = listOf(
                "given_name" to JsonPrimitive("Max"),
                "family_name" to JsonPrimitive("Mustermann"),
                "address" to buildJsonObject {
                    put("street_address", JsonPrimitive("Sample Street 456"))
                }
            )
        )
    }

    private suspend fun addCredOtherPid(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-other-pid",
            vct = "https://othercredentials.example/pid",
            data = listOf(
                "given_name" to JsonPrimitive("Erika"),
                "family_name" to JsonPrimitive("Mustermann"),
                "address" to buildJsonObject {
                    put("street_address", JsonPrimitive("Sample Street 123"))
                }
            )
        )
    }

    private suspend fun addCredPidReduced1(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-pid-reduced1",
            vct = "https://credentials.example.com/reduced_identity_credential",
            data = listOf(
                "given_name" to JsonPrimitive("Erika"),
                "family_name" to JsonPrimitive("Mustermann"),
            )
        )
    }

    private suspend fun addCredPidReduced2(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-pid-reduced2",
            vct = "https://cred.example/residence_credential",
            data = listOf(
                "postal_code" to JsonPrimitive(90210),
                "locality" to JsonPrimitive("Beverly Hills"),
                "region" to JsonPrimitive("Los Angeles Basin"),
            )
        )
    }

    private suspend fun addCredCompanyRewards(harness: DocumentStoreTestHarness) {
        harness.provisionSdJwtVc(
            displayName = "my-reward-card",
            vct = "https://company.example/company_rewards",
            data = listOf(
                "rewards_number" to JsonPrimitive(24601),
            )
        )
    }

    @Test
    fun testMatcher_OpenID4VP_complex_query() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                addCredPid(harness)
                addCredPidMax(harness)
                addCredOtherPid(harness)
                addCredPidReduced1(harness)
                addCredPidReduced2(harness)
                addCredCompanyRewards(harness)
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://credentials.example.com/identity_credential"]
                          },
                          "claims": [
                            {"path": ["given_name"]},
                            {"path": ["family_name"]},
                            {"path": ["address", "street_address"]}
                          ]
                        },
                        {
                          "id": "other_pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://othercredentials.example/pid"]
                          },
                          "claims": [
                            {"path": ["given_name"]},
                            {"path": ["family_name"]},
                            {"path": ["address", "street_address"]}
                          ]
                        },
                        {
                          "id": "pid_reduced_cred_1",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://credentials.example.com/reduced_identity_credential"]
                          },
                          "claims": [
                            {"path": ["family_name"]},
                            {"path": ["given_name"]}
                          ]
                        },
                        {
                          "id": "pid_reduced_cred_2",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://cred.example/residence_credential"]
                          },
                          "claims": [
                            {"path": ["postal_code"]},
                            {"path": ["locality"]},
                            {"path": ["region"]}
                          ]
                        },
                        {
                          "id": "nice_to_have",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["https://company.example/company_rewards"]
                          },
                          "claims": [
                            {"path": ["rewards_number"]}
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [ "pid" ],
                            [ "other_pid" ],
                            [ "pid_reduced_cred_1", "pid_reduced_cred_2" ]
                          ]
                        },
                        {
                          "required": false,
                          "options": [
                            [ "nice_to_have" ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __my-pid__
                    given_name: Erika
                    family_name: Mustermann
                    street_address: "Sample Street 123"
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __my-pid-max__
                    given_name: Max
                    family_name: Mustermann
                    street_address: "Sample Street 456"
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __my-other-pid__
                    given_name: Erika
                    family_name: Mustermann
                    street_address: "Sample Street 123"
                  SetEntry set_index 1
                    cred_id 0 openid4vp-v1-signed __my-reward-card__
                    rewards_number: 24601
                Set
                  set_id 1 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 1 openid4vp-v1-signed __my-pid__
                    given_name: Erika
                    family_name: Mustermann
                    street_address: "Sample Street 123"
                  SetEntry set_index 0
                    cred_id 1 openid4vp-v1-signed __my-pid-max__
                    given_name: Max
                    family_name: Mustermann
                    street_address: "Sample Street 456"
                  SetEntry set_index 0
                    cred_id 1 openid4vp-v1-signed __my-other-pid__
                    given_name: Erika
                    family_name: Mustermann
                    street_address: "Sample Street 123"
                Set
                  set_id 2 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 2 openid4vp-v1-signed __my-pid-reduced1__
                    family_name: Mustermann
                    given_name: Erika
                  SetEntry set_index 1
                    cred_id 2 openid4vp-v1-signed __my-pid-reduced2__
                    postal_code: 90210
                    locality: Beverly Hills
                    region: Los Angeles Basin
                  SetEntry set_index 2
                    cred_id 2 openid4vp-v1-signed __my-reward-card__
                    rewards_number: 24601
                Set
                  set_id 3 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 3 openid4vp-v1-signed __my-pid-reduced1__
                    family_name: Mustermann
                    given_name: Erika
                  SetEntry set_index 1
                    cred_id 3 openid4vp-v1-signed __my-pid-reduced2__
                    postal_code: 90210
                    locality: Beverly Hills
                    region: Los Angeles Basin
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_mdoc_String() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                {
                  "credentials": [
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.iso.23220.photoid.1"
                      },
                      "claims": [
                        {"path": ["org.iso.23220.1", "given_name"], "values": ["Erika"]},
                        {"path": ["org.iso.23220.1", "family_name"]},
                        {"path": ["org.iso.23220.1", "sex"]},
                        {"path": ["org.iso.23220.1", "age_over_25"]}
                      ]
                    }
                  ]
                }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID__
                    Given names: Erika
                    Family name: Mustermann
                    Sex: Female
                    Older than 25 years: false
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_mdoc_Int() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                {
                  "credentials": [
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.iso.23220.photoid.1"
                      },
                      "claims": [
                        {"path": ["org.iso.23220.1", "given_name"]},
                        {"path": ["org.iso.23220.1", "family_name"]},
                        {"path": ["org.iso.23220.1", "sex"], "values": [1]},
                        {"path": ["org.iso.23220.1", "age_over_25"]}
                      ]
                    }
                  ]
                }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID 2__
                    Given names: Max
                    Family name: Mustermann
                    Sex: Male
                    Older than 25 years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_mdoc_Bool_True() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                {
                  "credentials": [
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.iso.23220.photoid.1"
                      },
                      "claims": [
                        {"path": ["org.iso.23220.1", "given_name"]},
                        {"path": ["org.iso.23220.1", "family_name"]},
                        {"path": ["org.iso.23220.1", "sex"]},
                        {"path": ["org.iso.23220.1", "age_over_25"], "values": [true]}
                      ]
                    }
                  ]
                }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID 2__
                    Given names: Max
                    Family name: Mustermann
                    Sex: Male
                    Older than 25 years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_mdoc_Bool_False() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                {
                  "credentials": [
                    {
                      "id": "photoid",
                      "format": "mso_mdoc",
                      "meta": {
                        "doctype_value": "org.iso.23220.photoid.1"
                      },
                      "claims": [
                        {"path": ["org.iso.23220.1", "given_name"]},
                        {"path": ["org.iso.23220.1", "family_name"]},
                        {"path": ["org.iso.23220.1", "sex"]},
                        {"path": ["org.iso.23220.1", "age_over_25"], "values": [false]}
                      ]
                    }
                  ]
                }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID__
                    Given names: Erika
                    Family name: Mustermann
                    Sex: Female
                    Older than 25 years: false
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_sdjwt_String() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            { "path": ["sex"] },
                            { "path": ["given_name"], "values": ["Erika"] }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Sex: Female
                    Given names: Erika
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_sdjwt_Int() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            { "path": ["sex"], "values": [1] },
                            { "path": ["given_name"] }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Sex: Male
                    Given names: Max
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    private suspend fun addEuPid(
        harness: DocumentStoreTestHarness,
        documentDisplayName: String,
        givenName: String,
        ageOver18: Boolean
    ) {
        harness.provisionSdJwtVc(
            displayName = documentDisplayName,
            vct = EUPersonalID.EUPID_VCT,
            data = listOf(
                "given_name" to JsonPrimitive(givenName),
                "age_equal_or_over" to buildJsonObject {
                    put("18", JsonPrimitive(ageOver18))
                },
            )
        )
    }

    private suspend fun addEuPidAgeDocs(harness: DocumentStoreTestHarness) {
        addEuPid(
            harness = harness,
            documentDisplayName = "EU PID Erika",
            givenName = "Erika",
            ageOver18 = false
        )
        addEuPid(
            harness = harness,
            documentDisplayName = "EU PID Max",
            givenName = "Max",
            ageOver18 = true
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_sdjwt_Bool_True() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                addEuPidAgeDocs(harness)
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            { "path": ["given_name"] },
                            { "path": ["age_equal_or_over", "18"], "values": [true] }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID Max__
                    Given names: Max
                    Older than 18: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_value_matching_sdjwt_Bool_False() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                addEuPidAgeDocs(harness)
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            { "path": ["given_name"] },
                            { "path": ["age_equal_or_over", "18"], "values": [false] }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID Erika__
                    Given names: Erika
                    Older than 18: false
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_mDL_simple() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
                """
                    {
                      "credentials": [{
                          "id": "mDL",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                          "claims": [
                            { "path": ["org.iso.18013.5.1", "age_over_21"] },
                            { "path": ["org.iso.18013.5.1", "portrait"] }
                    ]}]}
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __mDL__
                    Older than 21 years: true
                    Photo of holder: 5318 bytes
                """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_mDL_or_PID() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "eu.europa.ec.eudi.pid.1"
                          },
                          "claims": [
                            {
                              "path": [
                                "eu.europa.ec.eudi.pid.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "eu.europa.ec.eudi.pid.1",
                                "family_name"
                              ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [
                              "mdl"
                            ],
                            [
                              "pid"
                            ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __mDL__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __EU PID__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __EU PID 2__
                    Given names: Max
                    Family name: Mustermann
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_mDL_and_PID() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "eu.europa.ec.eudi.pid.1"
                          },
                          "claims": [
                            {
                              "path": [
                                "eu.europa.ec.eudi.pid.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "eu.europa.ec.eudi.pid.1",
                                "family_name"
                              ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [
                              "mdl", "pid"
                            ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __mDL__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 1
                    cred_id 0 org-iso-mdoc __EU PID__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 1
                    cred_id 0 org-iso-mdoc __EU PID 2__
                    Given names: Max
                    Family name: Mustermann
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_age_mdocs() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "eu.europa.ec.eudi.pid.1"
                          },
                          "claims": [
                            {
                              "path": [ "eu.europa.ec.eudi.pid.1", "age_over_18" ],
                              "values": [ true ]
                            }
                          ]
                        },
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": ["org.iso.18013.5.1", "age_over_18" ],
                              "values": [ true ]
                            }
                          ]
                        },
                        {
                          "id": "photoid",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.23220.photoid.1"
                          },
                          "claims": [
                            {
                              "path": [ "org.iso.23220.1", "age_over_18" ],
                              "values": [ true ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [ "pid" ],
                            [ "mdl" ],
                            [ "photoid" ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __EU PID__
                    Older than 18: true
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __EU PID 2__
                    Older than 18: true
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __mDL__
                    Older than 18 years: true
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __Photo ID__
                    Older than 18 years: true
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __Photo ID 2__
                    Older than 18 years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_ClaimSet_With_AgeOver() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                addMdl_with_AgeOver_AgeInYears_BirthDate(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
            Set
              set_id 0 org-iso-mdoc
              SetEntry set_index 0
                cred_id 0 org-iso-mdoc __my-mDL__
                Given names: David
                Older than 18 years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_ClaimSet_With_AgeInYears() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                addMdl_with_AgeInYears_BirthDate(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __my-mDL-no-age-over__
                    Given names: David
                    Age in years: 48
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_ClaimSet_With_BirthDate() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                addMdl_with_BirthDate(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __my-mDL-only-birth-date__
                    Given names: David
                    Date of birth: 1976-03-02
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_ClaimSet_With_NoAgeInfo() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                addMdl_with_OnlyName(harness)
            },
            dcql = ageMdlQuery()
        )
        Assert.assertEquals(
            """
            """.trimIndent().trim(),
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_sdjwt_simple() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
            """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            {
                              "path": [
                                "age_equal_or_over",
                                "18"
                              ]
                            },
                            {
                              "path": [
                                "picture"
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __EU PID__
                    Older than 18: true
                    Photo of holder: Image (5318 bytes)
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __EU PID 2__
                    Older than 18: true
                    Photo of holder: Image (5318 bytes)
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_mDL_and_sdjwt() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            dcql =
            """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            {
                              "path": [
                                "family_name"
                              ]
                            },
                            {
                              "path": [
                                "given_name"
                              ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [
                              "mdl", "pid"
                            ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __mDL__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 1
                    cred_id 0 org-iso-mdoc __EU PID__
                    Family name: Mustermann
                    Given names: Erika
                  SetEntry set_index 1
                    cred_id 0 org-iso-mdoc __EU PID 2__
                    Family name: Mustermann
                    Given names: Max
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_OpenID4VP_ExchangeProtocols() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
                // docMdl will only support org-iso-mdoc, so it should NOT match for openid4vp-v1-signed
                harness.docMdl.setAndroidCredmanExchangeProtocols(listOf("org-iso-mdoc"))
                // docEuPid supports openid4vp-v1-signed, so it should match
                harness.docEuPid.setAndroidCredmanExchangeProtocols(listOf("openid4vp-v1-signed"))
                // docEuPid2 is not modified, so it supports all protocols by default, so it should match
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            {
                              "path": [
                                "family_name"
                              ]
                            },
                            {
                              "path": [
                                "given_name"
                              ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [
                              "mdl"
                            ],
                            [
                              "pid"
                            ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 openid4vp-v1-signed
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Family name: Mustermann
                    Given names: Erika
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Family name: Mustermann
                    Given names: Max
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_ExchangeProtocols() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
                // docMdl will only support org-iso-mdoc, so it should match
                harness.docMdl.setAndroidCredmanExchangeProtocols(listOf("org-iso-mdoc"))
                // docEuPid supports openid4vp-v1-signed, so it should NOT match
                harness.docEuPid.setAndroidCredmanExchangeProtocols(listOf("openid4vp-v1-signed"))
                // docEuPid2 has no protocols configured, so it should match
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        },
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "claims": [
                            {
                              "path": [
                                "family_name"
                              ]
                            },
                            {
                              "path": [
                                "given_name"
                              ]
                            }
                          ]
                        }
                      ],
                      "credential_sets": [
                        {
                          "options": [
                            [
                              "mdl"
                            ],
                            [
                              "pid"
                            ]
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim(),
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __mDL__
                    Given names: Erika
                    Family name: Mustermann
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __EU PID 2__
                    Family name: Mustermann
                    Given names: Max
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_mDL_issuerIdentifier_matching() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            deviceRequestBuilder = { harness, sessionTranscript ->
                val iacaSki = harness.iacaCert.subjectKeyIdentifier!!
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(
                            issuerIdentifiers = listOf(ByteString(iacaSki))
                        )
                    )
                    .build()
            }
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __mDL__
                    Given names: Erika
                    Family name: Mustermann
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_mDL_issuerIdentifier_nonMatching() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(
                            issuerIdentifiers = listOf(ByteString(byteArrayOf(1, 2, 3, 4, 5)))
                        )
                    )
                    .build()
            }
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Iso18013_v10_partialMatch() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "my-mDL-only-name",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to "Erika".toDataItem(),
                        )
                    )
                )
            },
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript, version = "1.0")
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "resident_address" to false
                            )
                        )
                    )
                    .build()
            }
        )
        Assert.assertEquals(
            """
                Set
                  set_id 0 org-iso-mdoc
                  SetEntry set_index 0
                    cred_id 0 org-iso-mdoc __my-mDL-only-name__
                    Given names: Erika
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_Iso18013_v11_partialMatch_fails() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "my-mDL-only-name",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to "Erika".toDataItem(),
                        )
                    )
                )
            },
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript, version = "1.1")
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "resident_address" to false
                            )
                        )
                    )
                    .build()
            }
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Iso18013_v10_zeroMatch_fails() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "my-mDL-only-family-name",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "family_name" to "Mustermann".toDataItem(),
                        )
                    )
                )
            },
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript, version = "1.0")
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "resident_address" to false
                            )
                        )
                    )
                    .build()
            }
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Iso18013_v10_multipleCredentials() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "mDL-full",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to "Erika".toDataItem(),
                            "resident_address" to "Sample Street 123".toDataItem()
                        )
                    )
                )
                harness.provisionMdoc(
                    displayName = "mDL-partial",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to "Max".toDataItem()
                        )
                    )
                )
            },
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript, version = "1.0")
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "resident_address" to false
                            )
                        )
                    )
                    .build()
            }
        )
        Assert.assertTrue(matcherResult.contains("__mDL-full__"))
        Assert.assertTrue(matcherResult.contains("__mDL-partial__"))
        Assert.assertTrue(matcherResult.contains("Sample Street 123"))
    }

    @Test
    fun testMatcher_Iso18013_multipleIssuers_mDL() = runTest {
        val iacaKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert1 = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey1),
            subject = X500Name.fromName("CN=Issuer 1"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val dsKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert1 = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert1)), iacaKey1),
            dsKey = dsKey1.publicKey,
            subject = X500Name.fromName("CN=DS 1"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days
        )
        val certifiedDsKey1 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert1)), dsKey1)

        val iacaKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert2 = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey2),
            subject = X500Name.fromName("CN=Issuer 2"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val dsKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert2 = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert2)), iacaKey2),
            dsKey = dsKey2.publicKey,
            subject = X500Name.fromName("CN=DS 2"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days
        )
        val certifiedDsKey2 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert2)), dsKey2)

        val initializer: suspend (DocumentStoreTestHarness) -> Unit = { harness ->
            harness.initialize()
            // Provision doc 1 with dsKey1
            harness.dsKey = certifiedDsKey1
            harness.provisionMdoc(
                displayName = "mDL-Issuer1",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to "Erika".toDataItem(),
                        "family_name" to "Mustermann".toDataItem()
                    )
                )
            )
            // Provision doc 2 with dsKey2
            harness.dsKey = certifiedDsKey2
            harness.provisionMdoc(
                displayName = "mDL-Issuer2",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to "Max".toDataItem(),
                        "family_name" to "Mustermann".toDataItem()
                    )
                )
            )
        }

        val ski1 = ByteString(iacaCert1.subjectKeyIdentifier!!)
        val ski2 = ByteString(iacaCert2.subjectKeyIdentifier!!)

        // 1. Query for Issuer 1 only
        val result1 = testMatcherIso18013(
            harnessInitializer = initializer,
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(ski1))
                    )
                    .build()
            }
        )
        Assert.assertTrue(result1.contains("__mDL-Issuer1__"))
        Assert.assertFalse(result1.contains("__mDL-Issuer2__"))

        // 2. Query for Issuer 2 only
        val result2 = testMatcherIso18013(
            harnessInitializer = initializer,
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(ski2))
                    )
                    .build()
            }
        )
        Assert.assertFalse(result2.contains("__mDL-Issuer1__"))
        Assert.assertTrue(result2.contains("__mDL-Issuer2__"))

        // 3. Query for both issuers
        val resultBoth = testMatcherIso18013(
            harnessInitializer = initializer,
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(ski1, ski2))
                    )
                    .build()
            }
        )
        Assert.assertTrue(resultBoth.contains("__mDL-Issuer1__"))
        Assert.assertTrue(resultBoth.contains("__mDL-Issuer2__"))
    }

    @Test
    fun testMatcher_Iso18013_intermediateCA_matching() = runTest {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(rootKey),
            subject = X500Name.fromName("CN=Root CA"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val intermediateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val intermediateCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootKey),
            subject = X500Name.fromName("CN=Intermediate CA"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(intermediateCert, rootCert)), intermediateKey),
            dsKey = dsKey.publicKey,
            subject = X500Name.fromName("CN=DS Key"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days
        )
        val fullChainDsKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(dsCert, intermediateCert, rootCert)),
            dsKey
        )

        val initializer: suspend (DocumentStoreTestHarness) -> Unit = { harness ->
            harness.initialize()
            harness.dsKey = fullChainDsKey
            harness.provisionMdoc(
                displayName = "mDL-3Tier",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to "Erika".toDataItem(),
                        "family_name" to "Mustermann".toDataItem()
                    )
                )
            )
        }

        val rootSki = ByteString(rootCert.subjectKeyIdentifier!!)
        val intermediateSki = ByteString(intermediateCert.subjectKeyIdentifier!!)
        val unrelatedSki = ByteString(byteArrayOf(9, 9, 9, 9))

        // Match against intermediate SKI (which is AKI on DS cert)
        val resultIntermediate = testMatcherIso18013(
            harnessInitializer = initializer,
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(intermediateSki))
                    )
                    .build()
            }
        )
        Assert.assertTrue(resultIntermediate.contains("__mDL-3Tier__"))

        // Match against root SKI (which is AKI on Intermediate cert)
        val resultRoot = testMatcherIso18013(
            harnessInitializer = initializer,
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(rootSki))
                    )
                    .build()
            }
        )
        Assert.assertTrue(resultRoot.contains("__mDL-3Tier__"))

        // Non-match with unrelated SKI
        val resultUnrelated = testMatcherIso18013(
            harnessInitializer = initializer,
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                                "family_name" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(unrelatedSki))
                    )
                    .build()
            }
        )
        Assert.assertEquals("", resultUnrelated)
    }

    @Test
    fun testMatcher_Iso18013_sdjwt_issuerIdentifier_matching() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            deviceRequestBuilder = { harness, sessionTranscript ->
                val iacaSki = harness.iacaCert.subjectKeyIdentifier!!
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = EUPersonalID.EUPID_VCT,
                        nameSpaces = mapOf(
                            "_" to mapOf(
                                "sdjwtkb_given_name" to false,
                                "sdjwtkb_sex" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(
                            docFormat = "dc+sd-jwt",
                            dataElementIdentifierMapping = mapOf(
                                "sdjwtkb_given_name" to Json.decodeFromString("""["given_name"]"""),
                                "sdjwtkb_sex" to Json.decodeFromString("""["sex"]""")
                            ),
                            issuerIdentifiers = listOf(ByteString(iacaSki))
                        )
                    )
                    .build()
            }
        )
        Assert.assertTrue(matcherResult.contains("__EU PID__"))
    }

    @Test
    fun testMatcher_Iso18013_sdjwt_issuerIdentifier_nonMatching() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = EUPersonalID.EUPID_VCT,
                        nameSpaces = mapOf(
                            "_" to mapOf(
                                "sdjwtkb_given_name" to false,
                                "sdjwtkb_sex" to false
                            )
                        ),
                        docRequestInfo = DocRequestInfo(
                            docFormat = "dc+sd-jwt",
                            dataElementIdentifierMapping = mapOf(
                                "sdjwtkb_given_name" to Json.decodeFromString("""["given_name"]"""),
                                "sdjwtkb_sex" to Json.decodeFromString("""["sex"]""")
                            ),
                            issuerIdentifiers = listOf(ByteString(byteArrayOf(1, 2, 3, 4)))
                        )
                    )
                    .build()
            }
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Iso18013_sdjwt_ping_transaction() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness -> harness.provisionStandardDocuments() },
            deviceRequestBuilder = { harness, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = EUPersonalID.EUPID_VCT,
                        nameSpaces = mapOf(
                            "_" to mapOf(
                                "sdjwtkb_family_name" to false,
                                "sdjwtkb_given_name" to false,
                                "sdjwtkb_birthdate" to false,
                            ),
                            ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(
                                PingTransaction.identifier to true
                            )
                        ),
                        docRequestInfo = DocRequestInfo(
                            docFormat = "dc+sd-jwt",
                            dataElementIdentifierMapping = mapOf(
                                "sdjwtkb_family_name" to Json.decodeFromString("""["family_name"]"""),
                                "sdjwtkb_given_name" to Json.decodeFromString("""["given_name"]"""),
                                "sdjwtkb_birthdate" to Json.decodeFromString("""["birthdate"]"""),
                            ),
                            transactionData = TransactionsInfo(
                                mapOf(
                                    PingTransaction.identifier to buildCborMap {
                                        put("string", "string data")
                                        put("blob", byteArrayOf(1, 2, 3).toDataItem())
                                    }
                                )
                            )
                        )
                    )
                    .build()
            }
        )
        Assert.assertTrue(matcherResult.contains("__EU PID__"))
        Assert.assertFalse(matcherResult.contains("org.multipaz.transaction.ping"))
    }

    @Test
    fun testMatcher_OpenID4VP_mDL_trustedAuthorities_matching() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcqlProvider = { harness ->
                val iacaSkiBase64Url = harness.iacaCert.subjectKeyIdentifier!!.toBase64Url()
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": [
                                "$iacaSkiBase64Url"
                              ]
                            }
                          ],
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim()
            }
        )
        Assert.assertTrue(matcherResult.contains("__mDL__"))
    }

    @Test
    fun testMatcher_OpenID4VP_mDL_trustedAuthorities_nonMatching() = runTest {
        val wrongSkiBase64Url = byteArrayOf(1, 2, 3, 4, 5).toBase64Url()
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "org.iso.18013.5.1.mDL"
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": [
                                "$wrongSkiBase64Url"
                              ]
                            }
                          ],
                          "claims": [
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "given_name"
                              ]
                            },
                            {
                              "path": [
                                "org.iso.18013.5.1",
                                "family_name"
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_OpenID4VP_multipleIssuers_mDL() = runTest {
        val iacaKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert1 = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey1),
            subject = X500Name.fromName("CN=Issuer 1"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val dsKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert1 = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert1)), iacaKey1),
            dsKey = dsKey1.publicKey,
            subject = X500Name.fromName("CN=DS 1"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days
        )
        val certifiedDsKey1 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert1)), dsKey1)

        val iacaKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert2 = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey2),
            subject = X500Name.fromName("CN=Issuer 2"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val dsKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert2 = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert2)), iacaKey2),
            dsKey = dsKey2.publicKey,
            subject = X500Name.fromName("CN=DS 2"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days
        )
        val certifiedDsKey2 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert2)), dsKey2)

        val initializer: suspend (DocumentStoreTestHarness) -> Unit = { harness ->
            harness.dsKey = certifiedDsKey1
            harness.provisionMdoc(
                displayName = "mDL-Issuer1",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to "Erika".toDataItem(),
                        "family_name" to "Mustermann".toDataItem()
                    )
                )
            )
            harness.dsKey = certifiedDsKey2
            harness.provisionMdoc(
                displayName = "mDL-Issuer2",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to "Max".toDataItem(),
                        "family_name" to "Mustermann".toDataItem()
                    )
                )
            )
        }

        val ski1 = iacaCert1.subjectKeyIdentifier!!.toBase64Url()
        val ski2 = iacaCert2.subjectKeyIdentifier!!.toBase64Url()

        // 1. Query for Issuer 1 only
        val result1 = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                          "trusted_authorities": [ { "type": "aki", "values": ["$ski1"] } ],
                          "claims": [ {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]} ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertTrue(result1.contains("__mDL-Issuer1__"))
        Assert.assertFalse(result1.contains("__mDL-Issuer2__"))

        // 2. Query for Issuer 2 only
        val result2 = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                          "trusted_authorities": [ { "type": "aki", "values": ["$ski2"] } ],
                          "claims": [ {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]} ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertFalse(result2.contains("__mDL-Issuer1__"))
        Assert.assertTrue(result2.contains("__mDL-Issuer2__"))

        // 3. Query for both issuers
        val resultBoth = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                          "trusted_authorities": [ { "type": "aki", "values": ["$ski1", "$ski2"] } ],
                          "claims": [ {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]} ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertTrue(resultBoth.contains("__mDL-Issuer1__"))
        Assert.assertTrue(resultBoth.contains("__mDL-Issuer2__"))
    }

    @Test
    fun testMatcher_OpenID4VP_intermediateCA_matching() = runTest {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(rootKey),
            subject = X500Name.fromName("CN=Root CA"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val intermediateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val intermediateCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootKey),
            subject = X500Name.fromName("CN=Intermediate CA"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
        val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(intermediateCert, rootCert)), intermediateKey),
            dsKey = dsKey.publicKey,
            subject = X500Name.fromName("CN=DS Key"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = Clock.System.now(),
            validUntil = Clock.System.now() + 365.days
        )
        val fullChainDsKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(dsCert, intermediateCert, rootCert)),
            dsKey
        )

        val initializer: suspend (DocumentStoreTestHarness) -> Unit = { harness ->
            harness.dsKey = fullChainDsKey
            harness.provisionMdoc(
                displayName = "mDL-3Tier",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to "Erika".toDataItem(),
                        "family_name" to "Mustermann".toDataItem()
                    )
                )
            )
        }

        val rootSki = rootCert.subjectKeyIdentifier!!.toBase64Url()
        val intermediateSki = intermediateCert.subjectKeyIdentifier!!.toBase64Url()
        val unrelatedSki = byteArrayOf(9, 9, 9, 9).toBase64Url()

        // Match intermediate SKI
        val resultIntermediate = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                          "trusted_authorities": [ { "type": "aki", "values": ["$intermediateSki"] } ],
                          "claims": [ {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]} ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertTrue(resultIntermediate.contains("__mDL-3Tier__"))

        // Match root SKI
        val resultRoot = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                          "trusted_authorities": [ { "type": "aki", "values": ["$rootSki"] } ],
                          "claims": [ {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]} ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertTrue(resultRoot.contains("__mDL-3Tier__"))

        // Non-match with unrelated SKI
        val resultUnrelated = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                          "trusted_authorities": [ { "type": "aki", "values": ["$unrelatedSki"] } ],
                          "claims": [ {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]} ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertEquals("", resultUnrelated)
    }

    @Test
    fun testMatcher_OpenID4VP_sdjwt_trustedAuthorities_matching() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcqlProvider = { harness ->
                val iacaSkiBase64Url = harness.iacaCert.subjectKeyIdentifier!!.toBase64Url()
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": [
                                "$iacaSkiBase64Url"
                              ]
                            }
                          ],
                          "claims": [
                            {
                              "path": [
                                "given_name"
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim()
            }
        )
        Assert.assertTrue(matcherResult.contains("__EU PID__"))
    }

    @Test
    fun testMatcher_OpenID4VP_sdjwt_trustedAuthorities_nonMatching() = runTest {
        val wrongSkiBase64Url = byteArrayOf(1, 2, 3, 4, 5).toBase64Url()
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionStandardDocuments()
            },
            dcql =
                """
                    {
                      "credentials": [
                        {
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": [
                              "urn:eudi:pid:1"
                            ]
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": [
                                "$wrongSkiBase64Url"
                              ]
                            }
                          ],
                          "claims": [
                            {
                              "path": [
                                "given_name"
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent().trim()
        )
        Assert.assertEquals("", matcherResult)
    }

    private suspend fun create3TierReaderKey(
        harness: DocumentStoreTestHarness
    ): Triple<ByteString, ByteString, AsymmetricKey.X509Certified> {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(rootKey),
            subject = X500Name.fromName("C=US,CN=3-Tier Reader Root"),
            serial = ASN1Integer(10),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            crlUrl = "https://example.com/reader/root/crl"
        )

        val intermediateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val intermediateCert = buildX509Cert(
            publicKey = intermediateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(rootKey, rootKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(11),
            subject = X500Name.fromName("C=US,CN=3-Tier Reader Intermediate CA"),
            issuer = rootCert.subject,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.CRL_SIGN))
            setBasicConstraints(true, 0)
            addExtension(
                OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid,
                false,
                rootCert.getExtensionValue(OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid)!!
            )
        }

        val readerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val intermediateX509Key = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(intermediateCert, rootCert)),
            intermediateKey
        )
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = intermediateX509Key,
            readerKey = readerPrivateKey.publicKey,
            subject = X500Name.fromName("C=US,CN=3-Tier Reader DS"),
            dnsName = "localhost",
            serial = ASN1Integer(12),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        )
        val readerKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(readerCert, intermediateCert, rootCert)),
            readerPrivateKey
        )
        val skiRoot = ByteString(rootCert.subjectKeyIdentifier!!)
        val skiIntermediate = ByteString(intermediateCert.subjectKeyIdentifier!!)
        return Triple(skiRoot, skiIntermediate, readerKey)
    }

    private val mdlDcqlString = """
        {
          "credentials": [
            {
              "id": "mdl",
              "format": "mso_mdoc",
              "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
              "claims": [ {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]} ]
            }
          ]
        }
    """.trimIndent().trim()

    private val pidDcqlString = """
        {
          "credentials": [
            {
              "id": "pid",
              "format": "dc+sd-jwt",
              "meta": { "vct_values": ["${EUPersonalID.EUPID_VCT}"] },
              "claims": [ {"path": ["given_name"]} ]
            }
          ]
        }
    """.trimIndent().trim()

    @Test
    fun testMatcher_OpenID4VP_mdoc_readerIdentifiers_matching() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerAki),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertTrue(matcherResult.contains("__Driving License__"))
    }

    @Test
    fun testMatcher_OpenID4VP_mdoc_readerIdentifiers_nonMatching() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(ByteString(byteArrayOf(9, 9, 9, 9))),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_OpenID4VP_mdoc_readerIdentifiers_unsignedBlocked() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = false,
            encryptionKey = null,
            harnessInitializer = { harness ->
                val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerAki),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_OpenID4VP_mdoc_readerIdentifiers_unsignedAllowed_whenEmpty() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = false,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = emptyList<ByteString>(),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertTrue(matcherResult.contains("__Driving License__"))
    }

    @Test
    fun testMatcher_OpenID4VP_sdjwt_readerIdentifiers_matching() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
                harness.provisionSdJwtVc(
                    displayName = "EU PID",
                    vct = EUPersonalID.EUPID_VCT,
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                    ),
                    readerIdentifiers = listOf(readerAki),
                )
            },
            dcql = pidDcqlString
        )
        Assert.assertTrue(matcherResult.contains("__EU PID__"))
    }

    @Test
    fun testMatcher_OpenID4VP_sdjwt_readerIdentifiers_nonMatching() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionSdJwtVc(
                    displayName = "EU PID",
                    vct = EUPersonalID.EUPID_VCT,
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                    ),
                    readerIdentifiers = listOf(ByteString(byteArrayOf(9, 9, 9, 9))),
                )
            },
            dcql = pidDcqlString
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_OpenID4VP_sdjwt_readerIdentifiers_unsignedBlocked() = runTest {
        val matcherResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = false,
            encryptionKey = null,
            harnessInitializer = { harness ->
                val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
                harness.provisionSdJwtVc(
                    displayName = "EU PID",
                    vct = EUPersonalID.EUPID_VCT,
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                    ),
                    readerIdentifiers = listOf(readerAki),
                )
            },
            dcql = pidDcqlString
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_OpenID4VP_3Tier_readerIdentifiers() = runTest {
        lateinit var readerKeyTriple: Triple<ByteString, ByteString, AsymmetricKey.X509Certified>

        // 1. Match intermediate SKI
        val resultIntermediate = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                readerKeyTriple = create3TierReaderKey(harness)
                harness.provisionMdoc(
                    displayName = "mDL-3Tier",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerKeyTriple.second),
                )
            },
            dcql = mdlDcqlString,
            readerAuthKeyProvider = { readerKeyTriple.third }
        )
        Assert.assertTrue(resultIntermediate.contains("__mDL-3Tier__"))

        // 2. Match root SKI
        val resultRoot = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                readerKeyTriple = create3TierReaderKey(harness)
                harness.provisionMdoc(
                    displayName = "mDL-3Tier",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerKeyTriple.first),
                )
            },
            dcql = mdlDcqlString,
            readerAuthKeyProvider = { readerKeyTriple.third }
        )
        Assert.assertTrue(resultRoot.contains("__mDL-3Tier__"))

        // 3. Non-matching unrelated SKI
        val resultUnrelated = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                readerKeyTriple = create3TierReaderKey(harness)
                harness.provisionMdoc(
                    displayName = "mDL-3Tier",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(ByteString(byteArrayOf(9, 9, 9, 9))),
                )
            },
            dcql = mdlDcqlString,
            readerAuthKeyProvider = { readerKeyTriple.third }
        )
        Assert.assertEquals("", resultUnrelated)
    }

    @Test
    fun testMatcher_Iso18013_mdoc_readerIdentifiers_matching() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerAki),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertTrue(matcherResult.contains("__Driving License__"))
    }

    @Test
    fun testMatcher_Iso18013_mdoc_readerIdentifiers_nonMatching() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(ByteString(byteArrayOf(9, 9, 9, 9))),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Iso18013_mdoc_readerIdentifiers_unsignedBlocked() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = false,
            harnessInitializer = { harness ->
                val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerAki),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Iso18013_mdoc_readerIdentifiers_unsignedAllowed_whenEmpty() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = false,
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "Driving License",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = emptyList<ByteString>(),
                )
            },
            dcql = mdlDcqlString
        )
        Assert.assertTrue(matcherResult.contains("__Driving License__"))
    }

    @Test
    fun testMatcher_Iso18013_sdjwt_readerIdentifiers_matching() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
                harness.provisionSdJwtVc(
                    displayName = "EU PID",
                    vct = EUPersonalID.EUPID_VCT,
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                    ),
                    readerIdentifiers = listOf(readerAki),
                )
            },
            dcql = pidDcqlString
        )
        Assert.assertTrue(matcherResult.contains("__EU PID__"))
    }

    @Test
    fun testMatcher_Iso18013_sdjwt_readerIdentifiers_nonMatching() = runTest {
        val matcherResult = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                harness.provisionSdJwtVc(
                    displayName = "EU PID",
                    vct = EUPersonalID.EUPID_VCT,
                    data = listOf(
                        "given_name" to JsonPrimitive("Erika"),
                    ),
                    readerIdentifiers = listOf(ByteString(byteArrayOf(9, 9, 9, 9))),
                )
            },
            dcql = pidDcqlString
        )
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Iso18013_3Tier_readerIdentifiers() = runTest {
        lateinit var readerKeyTriple: Triple<ByteString, ByteString, AsymmetricKey.X509Certified>

        // 1. Match intermediate SKI
        val resultIntermediate = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                readerKeyTriple = create3TierReaderKey(harness)
                harness.provisionMdoc(
                    displayName = "mDL-3Tier",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerKeyTriple.second),
                )
            },
            dcql = mdlDcqlString,
            readerAuthKeyProvider = { readerKeyTriple.third }
        )
        Assert.assertTrue(resultIntermediate.contains("__mDL-3Tier__"))

        // 2. Match root SKI
        val resultRoot = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                readerKeyTriple = create3TierReaderKey(harness)
                harness.provisionMdoc(
                    displayName = "mDL-3Tier",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(readerKeyTriple.first),
                )
            },
            dcql = mdlDcqlString,
            readerAuthKeyProvider = { readerKeyTriple.third }
        )
        Assert.assertTrue(resultRoot.contains("__mDL-3Tier__"))

        // 3. Non-matching unrelated SKI
        val resultUnrelated = testMatcherIso18013(
            signRequest = true,
            harnessInitializer = { harness ->
                readerKeyTriple = create3TierReaderKey(harness)
                harness.provisionMdoc(
                    displayName = "mDL-3Tier",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    readerIdentifiers = listOf(ByteString(byteArrayOf(9, 9, 9, 9))),
                )
            },
            dcql = mdlDcqlString,
            readerAuthKeyProvider = { readerKeyTriple.third }
        )
        Assert.assertEquals("", resultUnrelated)
    }

    @Test
    fun testMatcher_MultiDocument_readerIdentifiers_discrimination() = runTest {
        val aki1 = ByteString(byteArrayOf(1, 1, 1, 1))
        val aki2 = ByteString(byteArrayOf(2, 2, 2, 2))

        val initializer: suspend (DocumentStoreTestHarness) -> Unit = { harness ->
            val readerAki = ByteString(harness.readerRootKey.certChain.certificates.first().subjectKeyIdentifier!!)
            harness.provisionMdoc(
                displayName = "mDL-Matching",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(DrivingLicense.MDL_NAMESPACE to listOf("given_name" to Tstr("Erika"))),
                readerIdentifiers = listOf(readerAki),
            )
            harness.provisionMdoc(
                displayName = "mDL-OtherReader",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(DrivingLicense.MDL_NAMESPACE to listOf("given_name" to Tstr("Erika"))),
                readerIdentifiers = listOf(aki2),
            )
            harness.provisionMdoc(
                displayName = "mDL-Public",
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(DrivingLicense.MDL_NAMESPACE to listOf("given_name" to Tstr("Erika"))),
                readerIdentifiers = emptyList(),
            )
        }

        // 1. Signed request matching mDL-Matching -> returns mDL-Matching and mDL-Public, not mDL-OtherReader
        val signedResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql = mdlDcqlString
        )
        Assert.assertTrue(signedResult.contains("__mDL-Matching__"))
        Assert.assertTrue(signedResult.contains("__mDL-Public__"))
        Assert.assertFalse(signedResult.contains("__mDL-OtherReader__"))

        // 2. Unsigned request -> returns only mDL-Public
        val unsignedResult = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = false,
            encryptionKey = null,
            harnessInitializer = initializer,
            dcql = mdlDcqlString
        )
        Assert.assertFalse(unsignedResult.contains("__mDL-Matching__"))
        Assert.assertTrue(unsignedResult.contains("__mDL-Public__"))
        Assert.assertFalse(unsignedResult.contains("__mDL-OtherReader__"))
    }

    @Test
    fun testMatcher_Iso18013_keyAuthorizations_namespace_matching() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "mDL-with-auth",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    keyAuthorizedNamespaces = listOf(ISO_18013_TRANSACTION_DATA_NAMESPACE)
                )
                harness.provisionMdoc(
                    displayName = "mDL-no-auth",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    keyAuthorizedNamespaces = emptyList()
                )
            },
            deviceRequestBuilder = { _, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                            ),
                            ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(
                                "payment_transaction" to true,
                            )
                        )
                    )
                    .build()
            }
        )
        // Only mDL-with-auth matches, NOT mDL-no-auth
        Assert.assertTrue(matcherResult.contains("__mDL-with-auth__"))
        Assert.assertFalse(matcherResult.contains("__mDL-no-auth__"))
        // Device-signed / transaction claims must not be displayed in Credman field entries
        Assert.assertFalse(matcherResult.contains("payment_transaction"))
        Assert.assertTrue(matcherResult.contains("Given names: Erika"))
    }

    @Test
    fun testMatcher_Iso18013_keyAuthorizations_dataElements_matching() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "mDL-payment-auth",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    keyAuthorizedDataElements = mapOf(
                        ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf("payment_transaction")
                    )
                )
                harness.provisionMdoc(
                    displayName = "mDL-ping-auth",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    keyAuthorizedDataElements = mapOf(
                        ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf("ping_transaction")
                    )
                )
            },
            deviceRequestBuilder = { _, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                            ),
                            ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(
                                "payment_transaction" to true,
                            )
                        )
                    )
                    .build()
            }
        )
        // Only mDL-payment-auth matches, NOT mDL-ping-auth
        Assert.assertTrue(matcherResult.contains("__mDL-payment-auth__"))
        Assert.assertFalse(matcherResult.contains("__mDL-ping-auth__"))
        Assert.assertFalse(matcherResult.contains("payment_transaction"))
    }

    @Test
    fun testMatcher_Iso18013_keyAuthorizations_only_transaction_data() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "PaymentCard",
                    docType = "org.multipaz.payment.sca.1",
                    data = mapOf(
                        "org.multipaz.payment.sca.1" to listOf(
                            "issuer_name" to Tstr("Utopia Bank"),
                        )
                    ),
                    keyAuthorizedNamespaces = listOf(ISO_18013_TRANSACTION_DATA_NAMESPACE)
                )
            },
            deviceRequestBuilder = { _, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = "org.multipaz.payment.sca.1",
                        nameSpaces = mapOf(
                            ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(
                                "payment_transaction" to true,
                            )
                        )
                    )
                    .build()
            }
        )
        // Entry is offered without any field items (since only device-signed data was requested)
        Assert.assertTrue(matcherResult.contains("__PaymentCard__"))
        Assert.assertFalse(matcherResult.contains("payment_transaction"))
    }

    @Test
    fun testMatcher_Iso18013_keyAuthorizations_unauthorized_fails() = runTest {
        val matcherResult = testMatcherIso18013(
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "mDL-no-auth",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    )
                )
            },
            deviceRequestBuilder = { _, sessionTranscript ->
                DeviceRequest.Builder(sessionTranscript)
                    .addDocRequest(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        nameSpaces = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "given_name" to false,
                            ),
                            ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(
                                "payment_transaction" to true,
                            )
                        )
                    )
                    .build()
            }
        )
        // Credential does not match because payment_transaction is unauthorized
        Assert.assertEquals("", matcherResult)
    }

    @Test
    fun testMatcher_Dcql_keyAuthorizations_matching() = runTest {
        val dcql = """
            {
              "credentials": [
                {
                  "id": "cred1",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "claims": [
                    {"path": ["org.iso.18013.5.1", "given_name"]},
                    {"path": ["org.example.devicesigned", "device_attestation"]}
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = testMatcherDcql(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = false,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.provisionMdoc(
                    displayName = "mDL-with-auth",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    keyAuthorizedNamespaces = listOf("org.example.devicesigned")
                )
                harness.provisionMdoc(
                    displayName = "mDL-no-auth",
                    docType = DrivingLicense.MDL_DOCTYPE,
                    data = mapOf(
                        DrivingLicense.MDL_NAMESPACE to listOf(
                            "given_name" to Tstr("Erika"),
                        )
                    ),
                    keyAuthorizedNamespaces = emptyList()
                )
            },
            dcql = dcql
        )
        Assert.assertTrue(result.contains("__mDL-with-auth__"))
        Assert.assertFalse(result.contains("__mDL-no-auth__"))
        Assert.assertFalse(result.contains("device_attestation"))
    }

    @Test
    fun testMatcher_Iso18013_paymentSca_reproduce_user_case() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addDocumentType(DigitalPaymentCredential.getDocumentType())
        harness.provisionMdoc(
            displayName = "Erika's Payment Card Credential",
            docType = "org.multipaz.payment.sca.1",
            data = mapOf(
                "org.multipaz.payment.sca.1" to listOf(
                    "issuer_name" to Tstr("Utopia Bank"),
                    "payment_instrument_id" to Tstr("pi-77AABBCC"),
                    "masked_account_reference" to Tstr("****1234"),
                    "holder_name" to Tstr("Erika Mustermann"),
                    "issue_date" to LocalDate.parse("2018-08-09").toDataItemFullDate(),
                    "expiry_date" to LocalDate.parse("2028-08-09").toDataItemFullDate(),
                )
            ),
            keyAuthorizedNamespaces = listOf(ISO_18013_TRANSACTION_DATA_NAMESPACE)
        )

        val certPem = """
          -----BEGIN CERTIFICATE-----
          MIICNzCCAb6gAwIBAgIRAJOb6d0HEjTDBzbBCXrggQAwCgYIKoZIzj0EAwMwKzEpMCcGA1UEAwwg
          T1dGIE11bHRpcGF6IFRlc3RBcHAgUmVhZGVyIFJvb3QwHhcNMjQxMjAxMDAwMDAwWhcNMzQxMjAx
          MDAwMDAwWjArMSkwJwYDVQQDDCBPV0YgTXVsdGlwYXogVGVzdEFwcCBSZWFkZXIgQ2VydDBZMBMG
          ByqGSM49AgEGCCqGSM49AwEHA0IABO0B+FZdNKysCNn0M4xtFiwVNQpjEZTYTchA/rUJ7IPhN2RQ
          fVh/89cL5bPH0MZzMvQrzfqwZSunyz1thGXXE12jgcIwgb8wHwYDVR0jBBgwFoAUq2Ub4FbCkFPx
          3X9s5Ie+aN5gyfUwDgYDVR0PAQH/BAQDAgeAMBUGA1UdJQEB/wQLMAkGByiBjF0FAQYwVgYDVR0f
          BE8wTTBLoEmgR4ZFaHR0cHM6Ly9naXRodWIuY29tL29wZW53YWxsZXQtZm91bmRhdGlvbi1sYWJz
          L2lkZW50aXR5LWNyZWRlbnRpYWwvY3JsMB0GA1UdDgQWBBRZxxCijOoawu7s4peLtCElWPnNkjAK
          BggqhkjOPQQDAwNnADBkAjAPvNx3CiNFWHr3VekrOYlUz4iCzEHcEzpoIegW/ClpSRHhpG5VNiMo
          GTlcvbRIRiMCMGFYQ8MNpj5nJMd8OmEys4mxxZMbHK2QdnNPsENkYtHvi6YB5ShPY6gO5ARvEU2B
          UA==
          -----END CERTIFICATE-----
        """.trimIndent()
        val cert = org.multipaz.crypto.X509Cert.fromPem(certPem)

        val itemsRequest = buildCborMap {
            put("docType", "org.multipaz.payment.sca.1")
            put("nameSpaces", buildCborMap {
                put("org.multipaz.payment.sca.1", buildCborMap {
                    put("issuer_name", false)
                    put("payment_instrument_id", false)
                    put("masked_account_reference", false)
                    put("holder_name", false)
                    put("issue_date", false)
                    put("expiry_date", false)
                })
                put(ISO_18013_TRANSACTION_DATA_NAMESPACE, buildCborMap {
                    put("urn:eudi:sca:payment:1", true)
                })
            })
            put("requestInfo", buildCborMap {
                put("transactionData", buildCborMap {
                    put("urn:eudi:sca:payment:1", buildCborMap {
                        put("payload", buildCborMap {
                            put("transactionId", "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9")
                            put("currency", "USD")
                            put("amount", 123.25)
                            put("payee", buildCborMap {
                                put("name", "Linux Foundation")
                                put("id", "01234")
                            })
                            put("tipRequested", true)
                        })
                    })
                })
            })
        }

        val deviceRequestCbor = buildCborMap {
            put("version", "1.1")
            put("docRequests", buildCborArray {
                add(buildCborMap {
                    put("itemsRequest", org.multipaz.cbor.Tagged(24, org.multipaz.cbor.Bstr(Cbor.encode(itemsRequest))))
                })
            })
            val drInfo = buildCborMap {
                put("useCases", buildCborArray {
                    add(buildCborMap {
                        put("mandatory", true)
                        put("documentSets", buildCborArray {
                            add(buildCborArray {
                                add(0)
                            })
                        })
                    })
                })
            }
            put("deviceRequestInfo", org.multipaz.cbor.Tagged(24, org.multipaz.cbor.Bstr(Cbor.encode(drInfo))))
            put("readerAuthAll", buildCborArray {
                add(buildCborArray {
                    add(org.multipaz.cbor.Bstr(Cbor.encode(buildCborMap {
                        put(1, -7)
                    })))
                    add(buildCborMap {
                        put(33, cert.encoded.toByteArray())
                    })
                    add(Simple.NULL)
                    add("014943a50387c150da7de3b517d8800efcf52f62b0e81cdc333e4a2d971a9e4e04c82c6ac214189586d6689b8e6028ff7a6c5dff6d3fbcb7eaec727f62bf89f3".fromHex())
                })
            })
        }

        val deviceRequest = DeviceRequest.fromDataItem(deviceRequestCbor)
        val base64DeviceRequest = Cbor.encode(deviceRequest.toDataItem()).toBase64Url()

        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val nonce = Random.nextBytes(16).toBase64Url()
        val encryptionInfo = buildCborArray {
            add("dcapi")
            addCborMap {
                put("nonce", nonce.toByteArray())
                put("recipientPublicKey", encryptionKey.toCoseKey().toDataItem())
            }
        }
        val base64EncryptionInfo = Cbor.encode(encryptionInfo).toBase64Url()

        val credentialDatabase = calculateCredentialDatabase(
            appName = "Test App",
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            selectedProtocols = DigitalCredentials.getDefault().supportedProtocols,
        )

        var result = runMatcher(
            request = buildJsonObject {
                putJsonArray("requests") {
                    addJsonObject {
                        put("protocol", "org-iso-mdoc")
                        putJsonObject("data") {
                            put("deviceRequest", base64DeviceRequest)
                            put("encryptionInfo", base64EncryptionInfo)
                        }
                    }
                }
            }.toString().encodeToByteArray(),
            credentialDatabase = Cbor.encode(credentialDatabase)
        )
        println("Matcher result: '$result'")
        Assert.assertTrue("Expected match but got: '$result'", result.isNotEmpty())
    }
}