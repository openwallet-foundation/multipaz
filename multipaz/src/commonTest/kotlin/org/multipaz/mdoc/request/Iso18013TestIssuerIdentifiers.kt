package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.prettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Iso18013TestIssuerIdentifiers {

    companion object {
        private val UNKNOWN_AKI = ByteString(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        )

        private suspend fun createSecondIssuer(harness: DocumentStoreTestHarness): Pair<ByteString, AsymmetricKey.X509Certified> {
            val iacaPrivateKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
            val iacaCert2 = MdocUtil.generateIacaCertificate(
                iacaKey = AsymmetricKey.anonymous(iacaPrivateKey2),
                subject = X500Name.fromName("C=US,CN=Issuer 2 IACA"),
                serial = ASN1Integer(2),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                issuerAltNameUrl = "https://example.com/issuer2",
                crlUrl = "https://example.com/issuer2/crl"
            )
            val iacaKey2 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert2)), iacaPrivateKey2)

            val dsPrivateKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
            val dsCert2 = MdocUtil.generateDsCertificate(
                iacaKey = iacaKey2,
                dsKey = dsPrivateKey2.publicKey,
                subject = X500Name.fromName("C=US,CN=Issuer 2 DS"),
                serial = ASN1Integer(2),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            )
            val dsKey2 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert2)), dsPrivateKey2)
            val aki2 = ByteString(iacaCert2.subjectKeyIdentifier!!)
            return Pair(aki2, dsKey2)
        }

        private suspend fun createIssuerWithIntermediate(harness: DocumentStoreTestHarness): Triple<ByteString, ByteString, AsymmetricKey.X509Certified> {
            val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val rootCert = MdocUtil.generateIacaCertificate(
                iacaKey = AsymmetricKey.anonymous(rootKey),
                subject = X500Name.fromName("C=US,CN=3-Tier Root IACA"),
                serial = ASN1Integer(10),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                issuerAltNameUrl = "https://example.com/root",
                crlUrl = "https://example.com/root/crl"
            )

            val intermediateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val intermediateCert = buildX509Cert(
                publicKey = intermediateKey.publicKey,
                signingKey = AsymmetricKey.anonymous(rootKey, rootKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(11),
                subject = X500Name.fromName("C=US,CN=3-Tier Intermediate CA"),
                issuer = rootCert.subject,
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            ) {
                includeSubjectKeyIdentifier()
                setAuthorityKeyIdentifierToCertificate(rootCert)
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                setBasicConstraints(true, null)
                addExtension(
                    OID.X509_EXTENSION_ISSUER_ALT_NAME.oid,
                    false,
                    rootCert.getExtensionValue(OID.X509_EXTENSION_ISSUER_ALT_NAME.oid)!!
                )
                addExtension(
                    OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid,
                    false,
                    rootCert.getExtensionValue(OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid)!!
                )
            }

            val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val dsCert = MdocUtil.generateDsCertificate(
                iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(intermediateCert, rootCert)), intermediateKey),
                dsKey = dsPrivateKey.publicKey,
                subject = X500Name.fromName("C=US,CN=3-Tier DS"),
                serial = ASN1Integer(12),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            )
            val dsKey = AsymmetricKey.X509CertifiedExplicit(
                X509CertChain(listOf(dsCert, intermediateCert)),
                dsPrivateKey
            )
            val skiRoot = ByteString(rootCert.subjectKeyIdentifier!!)
            val skiIntermediate = ByteString(intermediateCert.subjectKeyIdentifier!!)
            return Triple(skiRoot, skiIntermediate, dsKey)
        }

        private suspend fun addMdl(
            harness: DocumentStoreTestHarness,
            displayName: String,
            dsKey: AsymmetricKey.X509Certified = harness.dsKey
        ) {
            harness.provisionMdoc(
                displayName = displayName,
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_address" to Tstr("Sample Street 123"),
                    )
                ),
                dsKey = dsKey
            )
        }

        private suspend fun addPidSdJwtVc(
            harness: DocumentStoreTestHarness,
            displayName: String,
            dsKey: AsymmetricKey.X509Certified = harness.dsKey
        ) {
            harness.provisionSdJwtVc(
                displayName = displayName,
                vct = EUPersonalID.EUPID_VCT,
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("formatted", JsonPrimitive("Sample Street 123, CA 90210, US"))
                    }
                ),
                dsKey = dsKey
            )
        }

        private fun mdlQuery(issuerIdentifiers: List<ByteString>? = null): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf(
                            "given_name" to false,
                            "resident_address" to false
                        )
                    ),
                    docRequestInfo = issuerIdentifiers?.let {
                        DocRequestInfo(issuerIdentifiers = it)
                    }
                )
            }
        }

        private fun pidSdJwtQuery(issuerIdentifiers: List<ByteString>? = null): DeviceRequest {
            return buildDeviceRequest(
                sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            ) {
                addDocRequest(
                    docType = EUPersonalID.EUPID_VCT,
                    nameSpaces = mapOf(
                        "_" to mapOf(
                            "sdjwtvc_given_name" to false,
                            "sdjwtvc_resident_address" to false
                        )
                    ),
                    docRequestInfo = DocRequestInfo(
                        docFormat = "dc+sd-jwt",
                        dataElementIdentifierMapping = mapOf(
                            "sdjwtvc_given_name" to buildJsonArray { add("given_name") },
                            "sdjwtvc_resident_address" to buildJsonArray { add("address"); add("formatted") },
                        ),
                        issuerIdentifiers = issuerIdentifiers ?: emptyList()
                    )
                )
            }
        }
    }

    @Test
    fun mdocSingleMatchingIssuerIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl(harness, "my-mDL-Issuer1")

        val aki1 = ByteString(harness.iacaCert.subjectKeyIdentifier!!)
        val result = mdlQuery(issuerIdentifiers = listOf(aki1)).execute(
            presentmentSource = harness.presentmentSource
        )
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
                                  docId: my-mDL-Issuer1
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            result.prettyPrint().trim()
        )
    }

    @Test
    fun mdocMultipleIssuerIdentifiersOneMatching() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl(harness, "my-mDL-Issuer1")

        val aki1 = ByteString(harness.iacaCert.subjectKeyIdentifier!!)
        val result = mdlQuery(issuerIdentifiers = listOf(UNKNOWN_AKI, aki1)).execute(
            presentmentSource = harness.presentmentSource
        )
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
                                  docId: my-mDL-Issuer1
                                  claims:
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: given_name
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      nameSpace: ${DrivingLicense.MDL_NAMESPACE}
                                      dataElement: resident_address
                                      displayName: Resident address
                                      value: Sample Street 123
            """.trimIndent().trim(),
            result.prettyPrint().trim()
        )
    }

    @Test
    fun mdocNonMatchingIssuerIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl(harness, "my-mDL-Issuer1")

        val e = assertFailsWith(Iso18015ResponseException::class) {
            mdlQuery(issuerIdentifiers = listOf(UNKNOWN_AKI)).execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun mdocMultipleIssuersSelection() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki2, dsKey2) = createSecondIssuer(harness)
        val aki1 = ByteString(harness.iacaCert.subjectKeyIdentifier!!)

        addMdl(harness, "my-mDL-Issuer1", dsKey = harness.dsKey)
        addMdl(harness, "my-mDL-Issuer2", dsKey = dsKey2)

        // Query matching only Issuer 1
        val resultIssuer1 = mdlQuery(issuerIdentifiers = listOf(aki1)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIssuer1.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-Issuer1", resultIssuer1.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)

        // Query matching only Issuer 2
        val resultIssuer2 = mdlQuery(issuerIdentifiers = listOf(aki2)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIssuer2.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-Issuer2", resultIssuer2.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)

        // Query matching both Issuer 1 and Issuer 2
        val resultBoth = mdlQuery(issuerIdentifiers = listOf(aki1, aki2)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(2, resultBoth.credentialSets[0].options[0].members[0].matches.size)

        // Query with no issuer identifiers returns both
        val resultAll = mdlQuery(issuerIdentifiers = null).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(2, resultAll.credentialSets[0].options[0].members[0].matches.size)

        // Query matching unknown issuer fails
        assertFailsWith(Iso18015ResponseException::class) {
            mdlQuery(issuerIdentifiers = listOf(UNKNOWN_AKI)).execute(
                presentmentSource = harness.presentmentSource
            )
        }
    }

    @Test
    fun sdJwtVcSingleMatchingIssuerIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidSdJwtVc(harness, "my-PID-Issuer1")

        val aki1 = ByteString(harness.iacaCert.subjectKeyIdentifier!!)
        val result = pidSdJwtQuery(issuerIdentifiers = listOf(aki1)).execute(
            presentmentSource = harness.presentmentSource
        )
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
                                  docId: my-PID-Issuer1
                                  claims:
                                    claim:
                                      path: ["given_name"]
                                      displayName: Given names
                                      value: Erika
                                    claim:
                                      path: ["address","formatted"]
                                      displayName: Resident address
                                      value: Sample Street 123, CA 90210, US
            """.trimIndent().trim(),
            result.prettyPrint().trim()
        )
    }

    @Test
    fun sdJwtVcNonMatchingIssuerIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addPidSdJwtVc(harness, "my-PID-Issuer1")

        val e = assertFailsWith(Iso18015ResponseException::class) {
            pidSdJwtQuery(issuerIdentifiers = listOf(UNKNOWN_AKI)).execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun sdJwtVcMultipleIssuersSelection() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki2, dsKey2) = createSecondIssuer(harness)
        val aki1 = ByteString(harness.iacaCert.subjectKeyIdentifier!!)

        addPidSdJwtVc(harness, "my-PID-Issuer1", dsKey = harness.dsKey)
        addPidSdJwtVc(harness, "my-PID-Issuer2", dsKey = dsKey2)

        // Query matching only Issuer 1
        val resultIssuer1 = pidSdJwtQuery(issuerIdentifiers = listOf(aki1)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIssuer1.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-PID-Issuer1", resultIssuer1.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)

        // Query matching only Issuer 2
        val resultIssuer2 = pidSdJwtQuery(issuerIdentifiers = listOf(aki2)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIssuer2.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-PID-Issuer2", resultIssuer2.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)

        // Query matching both
        val resultBoth = pidSdJwtQuery(issuerIdentifiers = listOf(aki1, aki2)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(2, resultBoth.credentialSets[0].options[0].members[0].matches.size)

        // Query with unknown issuer
        assertFailsWith(Iso18015ResponseException::class) {
            pidSdJwtQuery(issuerIdentifiers = listOf(UNKNOWN_AKI)).execute(
                presentmentSource = harness.presentmentSource
            )
        }
    }

    @Test
    fun useCasesWithIssuerIdentifiers() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki2, dsKey2) = createSecondIssuer(harness)
        val aki1 = ByteString(harness.iacaCert.subjectKeyIdentifier!!)

        addMdl(harness, "my-mDL-Issuer1", dsKey = harness.dsKey)
        addMdl(harness, "my-mDL-Issuer2", dsKey = dsKey2)

        // UseCase requesting DocRequest 0 (which requires Issuer 2)
        val request = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("doesn't"); add("matter") },
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = mapOf()
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to false,
                    )
                ),
                docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(aki2))
            )
        }

        val result = request.execute(presentmentSource = harness.presentmentSource)
        assertEquals(1, result.credentialSets.size)
        assertEquals(1, result.credentialSets[0].options.size)
        val matches = result.credentialSets[0].options[0].members[0].matches
        assertEquals(1, matches.size)
        assertEquals("my-mDL-Issuer2", matches[0].credential.document.displayName)
    }

    @Test
    fun mdocIssuerIdentifierMatchesIntermediateCertificate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, dsKey) = createIssuerWithIntermediate(harness)

        addMdl(harness, "my-mDL-3Tier", dsKey = dsKey)

        // Matching via intermediate certificate's SKI (which matches DS cert's AKI)
        val resultIntermediate = mdlQuery(issuerIdentifiers = listOf(skiIntermediate)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIntermediate.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-3Tier", resultIntermediate.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun mdocIssuerIdentifierMatchesRootCertificateViaIntermediate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, dsKey) = createIssuerWithIntermediate(harness)

        addMdl(harness, "my-mDL-3Tier", dsKey = dsKey)

        // Matching via Root CA's SKI (which matches Intermediate CA cert's AKI)
        val resultRoot = mdlQuery(issuerIdentifiers = listOf(skiRoot)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultRoot.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-3Tier", resultRoot.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun sdJwtVcIssuerIdentifierMatchesIntermediateCertificate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, dsKey) = createIssuerWithIntermediate(harness)

        addPidSdJwtVc(harness, "my-PID-3Tier", dsKey = dsKey)

        // Matching via intermediate certificate's SKI (which matches DS cert's AKI)
        val resultIntermediate = pidSdJwtQuery(issuerIdentifiers = listOf(skiIntermediate)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIntermediate.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-PID-3Tier", resultIntermediate.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun sdJwtVcIssuerIdentifierMatchesRootCertificateViaIntermediate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, dsKey) = createIssuerWithIntermediate(harness)

        addPidSdJwtVc(harness, "my-PID-3Tier", dsKey = dsKey)

        // Matching via Root CA's SKI (which matches Intermediate CA cert's AKI)
        val resultRoot = pidSdJwtQuery(issuerIdentifiers = listOf(skiRoot)).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultRoot.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-PID-3Tier", resultRoot.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }
}
