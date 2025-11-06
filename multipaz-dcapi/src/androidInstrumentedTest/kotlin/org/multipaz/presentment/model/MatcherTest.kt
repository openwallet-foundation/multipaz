package org.multipaz.presentment.model

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert
import org.junit.Test
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.digitalcredentials.Default
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.UtopiaMovieTicket
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.digitalcredentials.calculateCredentialDatabase
import org.multipaz.openid.OpenID4VP
import org.multipaz.util.toBase64Url
import kotlin.random.Random

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
    ): String {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harnessInitializer(harness)

        val nonce = Random.Default.nextBytes(16).toBase64Url()
        val readerAuthKey = if (signRequest) {
            val key = Crypto.createEcPrivateKey(EcCurve.P256)
            val readerRootCert = harness.readerRootKey.certChain.certificates.first()
            val cert = MdocUtil.generateReaderCertificate(
                readerRootKey = harness.readerRootKey,
                readerKey = key.publicKey,
                subject = X500Name.fromName("CN=Multipaz Reader Cert Single-Use key"),
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
            clientId = CLIENT_ID,
            nonce = nonce,
            responseEncryptionKey = encryptionKey?.publicKey,
            requestSigningKey = readerAuthKey,
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dclqQuery = Json.decodeFromString(JsonObject.serializer(), dcql)
        )
        val protocolName = when (version) {
            OpenID4VP.Version.DRAFT_24 -> "openid4vp"
            OpenID4VP.Version.DRAFT_29 -> if (signRequest) "openid4vp-v1-signed" else "openid4vp-v1-unsigned"
        }

        val credentialDatabase = calculateCredentialDatabase(
            appName = "Test App",
            selectedProtocols = DigitalCredentials.Default.supportedProtocols,
            stores = listOf(Pair(harness.documentStore, harness.documentTypeRepository))
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
        for (docId in harness.documentStore.listDocuments()) {
            val doc = harness.documentStore.lookupDocument(docId)!!
            result = result.replace(docId, "__${doc.metadata.displayName!!}__")
        }
        return result
    }

    // TODO: add tests for more complicated DCQL, including requests for multiple credentials...
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
                    Older Than 21 Years: true
                    Photo of Holder: 5318 bytes
                """.trimIndent().trim() + "\n",
            matcherResult
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
                    Older Than 18: true
                    Photo of Holder: Image (5318 bytes)
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Older Than 18: true
                    Photo of Holder: Image (5318 bytes)
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_mDL_or_PID() = runTest {
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
                    Given Names: Erika
                    Family Name: Mustermann
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Family Name: Mustermann
                    Given Names: Erika
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Family Name: Mustermann
                    Given Names: Max
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_age_mdocs() = runTest {
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
                            "doctype_value": "org.iso.23220.photoID.1"
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
                    Older Than 18: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Older Than 18: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __mDL__
                    Older Than 18 Years: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID__
                    Older Than 18 Years: true
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID 2__
                    Older Than 18 Years: true
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
    fun testMatcher_IDs_and_MovieTickets() = runTest {
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
                            "doctype_value": "org.iso.23220.photoID.1"
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
                    Family Name: Mustermann
                    Given Names: Erika
                    Photo of Holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID__
                    Family Name: Mustermann
                    Given Names: Erika
                    Photo of Holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __EU PID 2__
                    Family Name: Mustermann
                    Given Names: Max
                    Photo of Holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID__
                    Family Name: Mustermann
                    Given Names: Erika
                    Photo of Holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 0 openid4vp-v1-signed __Photo ID 2__
                    Family Name: Mustermann
                    Given Names: Max
                    Photo of Holder: 5318 bytes
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
    fun testMatcher_IDs_and_MovieTickets_alternative() = runTest {
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
                            "doctype_value": "org.iso.23220.photoID.1"
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
                    Family Name: Mustermann
                    Given Names: Erika
                    Photo of Holder: 5318 bytes
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
                    Family Name: Mustermann
                    Given Names: Erika
                    Photo of Holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 1 openid4vp-v1-signed __EU PID 2__
                    Family Name: Mustermann
                    Given Names: Max
                    Photo of Holder: 5318 bytes
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
                    Family Name: Mustermann
                    Given Names: Erika
                    Photo of Holder: 5318 bytes
                  SetEntry set_index 0
                    cred_id 2 openid4vp-v1-signed __Photo ID 2__
                    Family Name: Mustermann
                    Given Names: Max
                    Photo of Holder: 5318 bytes
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
    fun testMatcher_ClaimSet_With_AgeOver() = runTest {
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
                Given Names: David
                Older Than 18 Years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_ClaimSet_With_AgeInYears() = runTest {
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
                    Given Names: David
                    Age in Years: 48
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_ClaimSet_With_BirthDate() = runTest {
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
                    Given Names: David
                    Date of Birth: 1976-03-02
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_ClaimSet_With_NoAgeInfo() = runTest {
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
    fun testMatcher_complex_query() = runTest {
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
    fun testMatcher_value_matching_mdoc_String() = runTest {
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
                        "doctype_value": "org.iso.23220.photoID.1"
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
                    Given Names: Erika
                    Family Name: Mustermann
                    Sex: Female
                    Older Than 25 Years: false
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_value_matching_mdoc_Int() = runTest {
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
                        "doctype_value": "org.iso.23220.photoID.1"
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
                    Given Names: Max
                    Family Name: Mustermann
                    Sex: Male
                    Older Than 25 Years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_value_matching_mdoc_Bool_True() = runTest {
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
                        "doctype_value": "org.iso.23220.photoID.1"
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
                    Given Names: Max
                    Family Name: Mustermann
                    Sex: Male
                    Older Than 25 Years: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_value_matching_mdoc_Bool_False() = runTest {
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
                        "doctype_value": "org.iso.23220.photoID.1"
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
                    Given Names: Erika
                    Family Name: Mustermann
                    Sex: Female
                    Older Than 25 Years: false
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_value_matching_sdjwt_String() = runTest {
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
                    Given Names: Erika
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_value_matching_sdjwt_Int() = runTest {
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
                    Given Names: Max
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
    fun testMatcher_value_matching_sdjwt_Bool_True() = runTest {
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
                    Given Names: Max
                    Older Than 18: true
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }

    @Test
    fun testMatcher_value_matching_sdjwt_Bool_False() = runTest {
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
                    Given Names: Erika
                    Older Than 18: false
            """.trimIndent().trim() + "\n",
            matcherResult
        )
    }
}