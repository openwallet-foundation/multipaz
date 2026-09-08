package org.multipaz.openid.dcql

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class TestTrustedAuthorities {

    @Test
    fun testTrustedAuthoritiesSerialization() {
        val jsonString = """
            {
              "credentials": [
                {
                  "id": "cred0",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "org.iso.18013.5.1.mDL"
                  },
                  "trusted_authorities": [
                    {
                      "type": "aki",
                      "values": [
                        "AQIDBA==",
                        "BQYHCA=="
                      ]
                    }
                  ],
                  "claims": [
                    {
                      "path": ["org.iso.18013.5.1", "given_name"]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val query = DcqlQuery.fromJson(Json.parseToJsonElement(jsonString).jsonObject)
        assertEquals(1, query.credentialQueries.size)
        val credQuery = query.credentialQueries[0]
        assertEquals(2, credQuery.issuerIdentifiers.size)
        assertEquals(ByteString(byteArrayOf(1, 2, 3, 4)), credQuery.issuerIdentifiers[0])
        assertEquals(ByteString(byteArrayOf(5, 6, 7, 8)), credQuery.issuerIdentifiers[1])

        val serializedJson = query.toJson()
        val reParsedQuery = DcqlQuery.fromJson(serializedJson)
        assertEquals(credQuery.issuerIdentifiers, reParsedQuery.credentialQueries[0].issuerIdentifiers)
    }

    @Test
    fun testMdlTrustedAuthoritiesMatch() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        val iacaSki = harness.iacaCert.subjectKeyIdentifier!!.toBase64Url()
        val query = DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl_cred",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": ["$iacaSki"]
                            }
                          ],
                          "claims": [
                            {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                            {"path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"]}
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ).jsonObject
        )

        val result = query.execute(harness.presentmentSource)
        assertEquals(
            """
                credentialSets:
                  credentialSet:
                    optional: false
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: MdocCredential
                                  docId: mDL
                                  claims:
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: org.iso.18013.5.1
                                      dataElement: family_name
                                      displayName: Family name
                                      value: Mustermann
            """.trimIndent().trim() + "\n",
            result.prettyPrint()
        )
    }

    @Test
    fun testMdlTrustedAuthoritiesNoMatch() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        val wrongSki = byteArrayOf(1, 2, 3, 4, 5).toBase64Url()
        val query = DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl_cred",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": ["$wrongSki"]
                            }
                          ],
                          "claims": [
                            {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                            {"path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"]}
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ).jsonObject
        )

        assertFailsWith<DcqlCredentialQueryException> {
            query.execute(harness.presentmentSource)
        }
    }

    @Test
    fun testMultipleIssuers() = runTest {
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

        val harness = DocumentStoreTestHarness()
        harness.initialize()

        // Doc 1 from Issuer 1
        harness.dsKey = certifiedDsKey1
        val doc1 = harness.provisionMdoc(
            displayName = "mDL-Issuer1",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "family_name" to "Mustermann".toDataItem()
                )
            )
        )

        // Doc 2 from Issuer 2
        harness.dsKey = certifiedDsKey2
        val doc2 = harness.provisionMdoc(
            displayName = "mDL-Issuer2",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Max".toDataItem(),
                    "family_name" to "Mustermann".toDataItem()
                )
            )
        )

        val ski1 = iacaCert1.subjectKeyIdentifier!!.toBase64Url()
        val ski2 = iacaCert2.subjectKeyIdentifier!!.toBase64Url()

        // 1. Query for Issuer 1 only
        val query1 = DcqlQuery.fromJson(
            Json.parseToJsonElement(
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
                """.trimIndent()
            ).jsonObject
        )
        val res1 = query1.execute(harness.presentmentSource).prettyPrint()
        assertTrue(res1.contains("docId: mDL-Issuer1"))
        assertFalse(res1.contains("docId: mDL-Issuer2"))

        // 2. Query for Issuer 2 only
        val query2 = DcqlQuery.fromJson(
            Json.parseToJsonElement(
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
                """.trimIndent()
            ).jsonObject
        )
        val res2 = query2.execute(harness.presentmentSource).prettyPrint()
        assertFalse(res2.contains("docId: mDL-Issuer1"))
        assertTrue(res2.contains("docId: mDL-Issuer2"))

        // 3. Query for both issuers
        val queryBoth = DcqlQuery.fromJson(
            Json.parseToJsonElement(
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
                """.trimIndent()
            ).jsonObject
        )
        val resBoth = queryBoth.execute(harness.presentmentSource).prettyPrint()
        assertTrue(resBoth.contains("docId: mDL-Issuer1"))
        assertTrue(resBoth.contains("docId: mDL-Issuer2"))
    }

    @Test
    fun testIntermediateCaMatch() = runTest {
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

        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.dsKey = fullChainDsKey
        val doc = harness.provisionMdoc(
            displayName = "mDL-3Tier",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "family_name" to "Mustermann".toDataItem()
                )
            )
        )

        val rootSki = rootCert.subjectKeyIdentifier!!.toBase64Url()
        val intermediateSki = intermediateCert.subjectKeyIdentifier!!.toBase64Url()
        val unrelatedSki = byteArrayOf(9, 9, 9, 9).toBase64Url()

        // Match intermediate SKI
        val queryIntermediate = DcqlQuery.fromJson(
            Json.parseToJsonElement(
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
                """.trimIndent()
            ).jsonObject
        )
        val resIntermediate = queryIntermediate.execute(harness.presentmentSource).prettyPrint()
        assertTrue(resIntermediate.contains("docId: mDL-3Tier"))

        // Match root SKI
        val queryRoot = DcqlQuery.fromJson(
            Json.parseToJsonElement(
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
                """.trimIndent()
            ).jsonObject
        )
        val resRoot = queryRoot.execute(harness.presentmentSource).prettyPrint()
        assertTrue(resRoot.contains("docId: mDL-3Tier"))

        // Unrelated SKI
        val queryUnrelated = DcqlQuery.fromJson(
            Json.parseToJsonElement(
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
                """.trimIndent()
            ).jsonObject
        )
        assertFailsWith<DcqlCredentialQueryException> {
            queryUnrelated.execute(harness.presentmentSource)
        }
    }

    @Test
    fun testSdJwtTrustedAuthoritiesMatch() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        val iacaSki = harness.iacaCert.subjectKeyIdentifier!!.toBase64Url()
        val query = DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "pid_cred",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["${EUPersonalID.EUPID_VCT}"]
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": ["$iacaSki"]
                            }
                          ],
                          "claims": [
                            {"path": ["given_name"]},
                            {"path": ["family_name"]}
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ).jsonObject
        )

        val result = query.execute(harness.presentmentSource)
        assertEquals(
            """
                credentialSets:
                  credentialSet:
                    optional: false
                    options:
                      option:
                        members:
                          member:
                            matches:
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: EU PID
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      path: ["family_name"]
                                      displayName: Family name
                                      value: Mustermann
                              match:
                                credential:
                                  type: KeyBoundSdJwtVcCredential
                                  docId: EU PID 2
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given names
                                      value: Max
                                    claim:
                                      path: ["family_name"]
                                      displayName: Family name
                                      value: Mustermann
            """.trimIndent().trim() + "\n",
            result.prettyPrint()
        )
    }

    @Test
    fun testSdJwtTrustedAuthoritiesNoMatch() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        val wrongSki = byteArrayOf(1, 2, 3, 4, 5).toBase64Url()
        val query = DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "pid_cred",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["${EUPersonalID.EUPID_VCT}"]
                          },
                          "trusted_authorities": [
                            {
                              "type": "aki",
                              "values": ["$wrongSki"]
                            }
                          ],
                          "claims": [
                            {"path": ["given_name"]},
                            {"path": ["family_name"]}
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ).jsonObject
        )

        assertFailsWith<DcqlCredentialQueryException> {
            query.execute(harness.presentmentSource)
        }
    }
}
