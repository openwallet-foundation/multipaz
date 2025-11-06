package org.multipaz.presentment.model

import kotlinx.coroutines.test.runTest
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
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.digitalcredentials.Default
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.digitalcredentials.calculateCredentialDatabase
import org.multipaz.openid.OpenID4VP
import org.multipaz.util.toBase64Url
import kotlin.random.Random

// This is like the tests in org.multipaz.openid.dcql.* in commonTest but this time for
// DCQL query execution logic in the matcher (c++) instead of the wallet app (Kotlin)...
//
class MatcherDcqlQueryExecuteTest {
    companion object {
        private const val TAG = "MatcherTest"

        private const val CLIENT_ID = "x509_san_dns:verifier.multipaz.org"
        private const val ORIGIN = "https://verifier.multipaz.org"

        init {
            System.loadLibrary("MatcherTest")
        }

        /** JNI visibility error is expected. */
        @JvmStatic
        external fun executeDcqlQuery(
            request: ByteArray,
            credentialDatabase: ByteArray,
        ): String
    }

    suspend fun testExecuteDcqlQuery(
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
            val readerRootCerts = harness.readerRootKey.certChain.certificates
            val cert = MdocUtil.generateReaderCertificate(
                readerRootKey = harness.readerRootKey,
                readerKey = key.publicKey,
                subject = X500Name.fromName("CN=Multipaz Reader Cert Single-Use key"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = readerRootCerts.first().validityNotBefore,
                validUntil = readerRootCerts.first().validityNotAfter
            )
            AsymmetricKey.X509CertifiedExplicit(
                privateKey = key,
                certChain = X509CertChain(listOf(cert) + readerRootCerts)
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

        var result = executeDcqlQuery(
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
    fun test_OpenID4VP_mDL_simple() = runTest {
        val dcqlExecutionDump = testExecuteDcqlQuery(
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
                DcqlResponse
                  CredentialSet
                    optional false
                    options
                      option
                        members
                          member
                            matches
                              match
                                credential mDL
                                claims
                                  claim
                                    claimName org.iso.18013.5.1.age_over_21
                                    displayName Older Than 21 Years
                                    value true
                                  claim
                                    claimName org.iso.18013.5.1.portrait
                                    displayName Photo of Holder
                                    value 5318 bytes
            """.trimIndent().trim() + "\n",
            dcqlExecutionDump
        )
    }

    @Test
    fun test_OpenID4VP_sdjwt_simple() = runTest {
        val dcqlExecutionDump = testExecuteDcqlQuery(
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
                DcqlResponse
                  CredentialSet
                    optional false
                    options
                      option
                        members
                          member
                            matches
                              match
                                credential EU PID
                                claims
                                  claim
                                    claimName age_equal_or_over.18
                                    displayName Older Than 18
                                    value true
                                  claim
                                    claimName picture
                                    displayName Photo of Holder
                                    value Image (5318 bytes)
                              match
                                credential EU PID 2
                                claims
                                  claim
                                    claimName age_equal_or_over.18
                                    displayName Older Than 18
                                    value true
                                  claim
                                    claimName picture
                                    displayName Photo of Holder
                                    value Image (5318 bytes)
            """.trimIndent().trim() + "\n",
            dcqlExecutionDump
        )
    }

    @Test
    fun test_mDL_or_PID() = runTest {
        val dcqlExecutionDump = testExecuteDcqlQuery(
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
                DcqlResponse
                  CredentialSet
                    optional false
                    options
                      option
                        members
                          member
                            matches
                              match
                                credential mDL
                                claims
                                  claim
                                    claimName org.iso.18013.5.1.given_name
                                    displayName Given Names
                                    value Erika
                                  claim
                                    claimName org.iso.18013.5.1.family_name
                                    displayName Family Name
                                    value Mustermann
                      option
                        members
                          member
                            matches
                              match
                                credential EU PID
                                claims
                                  claim
                                    claimName family_name
                                    displayName Family Name
                                    value Mustermann
                                  claim
                                    claimName given_name
                                    displayName Given Names
                                    value Erika
                              match
                                credential EU PID 2
                                claims
                                  claim
                                    claimName family_name
                                    displayName Family Name
                                    value Mustermann
                                  claim
                                    claimName given_name
                                    displayName Given Names
                                    value Max
            """.trimIndent().trim() + "\n",
            dcqlExecutionDump
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
                }
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
    fun test_complexQuery_haveAll() = runTest {
        val dcqlExecutionDump = testExecuteDcqlQuery(
            version = OpenID4VP.Version.DRAFT_29,
            signRequest = true,
            encryptionKey = null,
            harnessInitializer = { harness ->
                harness.initialize()
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
                DcqlResponse
                  CredentialSet
                    optional false
                    options
                      option
                        members
                          member
                            matches
                              match
                                credential my-pid
                                claims
                                  claim
                                    claimName given_name
                                    displayName given_name
                                    value Erika
                                  claim
                                    claimName family_name
                                    displayName family_name
                                    value Mustermann
                                  claim
                                    claimName address.street_address
                                    displayName street_address
                                    value "Sample Street 123"
                              match
                                credential my-pid-max
                                claims
                                  claim
                                    claimName given_name
                                    displayName given_name
                                    value Max
                                  claim
                                    claimName family_name
                                    displayName family_name
                                    value Mustermann
                                  claim
                                    claimName address.street_address
                                    displayName street_address
                                    value "Sample Street 456"
                      option
                        members
                          member
                            matches
                              match
                                credential my-other-pid
                                claims
                                  claim
                                    claimName given_name
                                    displayName given_name
                                    value Erika
                                  claim
                                    claimName family_name
                                    displayName family_name
                                    value Mustermann
                                  claim
                                    claimName address.street_address
                                    displayName street_address
                                    value "Sample Street 123"
                      option
                        members
                          member
                            matches
                              match
                                credential my-pid-reduced1
                                claims
                                  claim
                                    claimName family_name
                                    displayName family_name
                                    value Mustermann
                                  claim
                                    claimName given_name
                                    displayName given_name
                                    value Erika
                          member
                            matches
                              match
                                credential my-pid-reduced2
                                claims
                                  claim
                                    claimName postal_code
                                    displayName postal_code
                                    value 90210
                                  claim
                                    claimName locality
                                    displayName locality
                                    value Beverly Hills
                                  claim
                                    claimName region
                                    displayName region
                                    value Los Angeles Basin
                  CredentialSet
                    optional true
                    options
                      option
                        members
                          member
                            matches
                              match
                                credential my-reward-card
                                claims
                                  claim
                                    claimName rewards_number
                                    displayName rewards_number
                                    value 24601
            """.trimIndent().trim() + "\n",
            dcqlExecutionDump
        )
    }
}
