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

class Iso18013TestReaderIdentifiers {

    companion object {
        private val UNKNOWN_AKI = ByteString(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        )

        private suspend fun createReader(
            harness: DocumentStoreTestHarness,
            cn: String,
            serial: Long = 1
        ): Pair<ByteString, AsymmetricKey.X509Certified> {
            val rootPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val rootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(rootPrivateKey),
                subject = X500Name.fromName("C=US,CN=$cn Root"),
                serial = ASN1Integer(serial),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                crlUrl = "https://example.com/reader/crl"
            )
            val rootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootPrivateKey)

            val readerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = rootKey,
                readerKey = readerPrivateKey.publicKey,
                subject = X500Name.fromName("C=US,CN=$cn Reader"),
                dnsName = null,
                serial = ASN1Integer(serial + 100),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            )
            val readerKey = AsymmetricKey.X509CertifiedExplicit(
                X509CertChain(listOf(readerCert, rootCert)),
                readerPrivateKey
            )
            val aki = ByteString(rootCert.subjectKeyIdentifier!!)
            return Pair(aki, readerKey)
        }

        private suspend fun createReaderWithIntermediate(
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
                dnsName = null,
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

        private suspend fun addMdl(
            harness: DocumentStoreTestHarness,
            displayName: String,
            readerIdentifiers: List<ByteString> = emptyList(),
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
                readerIdentifiers = readerIdentifiers,
            )
        }

        private suspend fun addPidSdJwtVc(
            harness: DocumentStoreTestHarness,
            displayName: String,
            readerIdentifiers: List<ByteString> = emptyList(),
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
                readerIdentifiers = readerIdentifiers,
            )
        }

        private suspend fun mdlQuery(
            readerKey: AsymmetricKey.X509Compatible? = null,
        ): DeviceRequest {
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
                    readerKey = readerKey
                )
            }
        }

        private suspend fun mdlQueryReaderAuthAll(
            readerKey: AsymmetricKey.X509Compatible,
        ): DeviceRequest {
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
                )
                addReaderAuthAll(readerKey)
            }
        }

        private suspend fun pidSdJwtQuery(
            readerKey: AsymmetricKey.X509Compatible? = null,
        ): DeviceRequest {
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
                        )
                    ),
                    readerKey = readerKey
                )
            }
        }
    }

    @Test
    fun mdocSingleMatchingReaderIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(aki1))

        val result = mdlQuery(readerKey = readerKey1).execute(
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
                                  docId: my-mDL-Restricted
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
    fun mdocMultipleReaderIdentifiersOneMatching() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(UNKNOWN_AKI, aki1))

        val result = mdlQuery(readerKey = readerKey1).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, result.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-Restricted", result.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun mdocNonMatchingReaderIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (_, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(UNKNOWN_AKI))

        val e = assertFailsWith(Iso18015ResponseException::class) {
            mdlQuery(readerKey = readerKey1).execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun mdocUnauthenticatedRequestBlockedWhenReaderIdentifiersSet() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, _) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(aki1))

        val e = assertFailsWith(Iso18015ResponseException::class) {
            mdlQuery(readerKey = null).execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun mdocUnauthenticatedRequestAllowedWhenReaderIdentifiersEmpty() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl(harness, "my-mDL-Public", readerIdentifiers = emptyList())

        val result = mdlQuery(readerKey = null).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, result.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-Public", result.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun mdocMultipleDocumentsSelectionByReader() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1", serial = 1)
        val (aki2, readerKey2) = createReader(harness, "Reader 2", serial = 2)

        addMdl(harness, "doc-Reader1-Only", readerIdentifiers = listOf(aki1))
        addMdl(harness, "doc-Reader2-Only", readerIdentifiers = listOf(aki2))
        addMdl(harness, "doc-Public", readerIdentifiers = emptyList())

        // Reader 1 should match doc-Reader1-Only and doc-Public
        val resultReader1 = mdlQuery(readerKey = readerKey1).execute(
            presentmentSource = harness.presentmentSource
        )
        val matches1 = resultReader1.credentialSets[0].options[0].members[0].matches
        assertEquals(2, matches1.size)
        assertEquals(
            listOf("doc-Public", "doc-Reader1-Only"),
            matches1.mapNotNull { it.credential.document.displayName }.sorted()
        )

        // Reader 2 should match doc-Reader2-Only and doc-Public
        val resultReader2 = mdlQuery(readerKey = readerKey2).execute(
            presentmentSource = harness.presentmentSource
        )
        val matches2 = resultReader2.credentialSets[0].options[0].members[0].matches
        assertEquals(2, matches2.size)
        assertEquals(
            listOf("doc-Public", "doc-Reader2-Only"),
            matches2.mapNotNull { it.credential.document.displayName }.sorted()
        )

        // Unknown Reader should match only doc-Public
        val (_, readerKeyUnknown) = createReader(harness, "Unknown Reader", serial = 3)
        val resultUnknown = mdlQuery(readerKey = readerKeyUnknown).execute(
            presentmentSource = harness.presentmentSource
        )
        val matchesUnknown = resultUnknown.credentialSets[0].options[0].members[0].matches
        assertEquals(1, matchesUnknown.size)
        assertEquals("doc-Public", matchesUnknown[0].credential.document.displayName)

        // Unauthenticated query should match only doc-Public
        val resultUnauth = mdlQuery(readerKey = null).execute(
            presentmentSource = harness.presentmentSource
        )
        val matchesUnauth = resultUnauth.credentialSets[0].options[0].members[0].matches
        assertEquals(1, matchesUnauth.size)
        assertEquals("doc-Public", matchesUnauth[0].credential.document.displayName)
    }

    @Test
    fun mdocReaderAuthAllWithMatchingReaderIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(aki1))

        val result = mdlQueryReaderAuthAll(readerKey = readerKey1).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, result.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-Restricted", result.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun mdocReaderAuthAllWithNonMatchingReaderIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (_, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(UNKNOWN_AKI))

        val e = assertFailsWith(Iso18015ResponseException::class) {
            mdlQueryReaderAuthAll(readerKey = readerKey1).execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun sdJwtVcMatchingReaderIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1")
        addPidSdJwtVc(harness, "my-PID-Restricted", readerIdentifiers = listOf(aki1))

        val result = pidSdJwtQuery(readerKey = readerKey1).execute(
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
                                  docId: my-PID-Restricted
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
    fun sdJwtVcNonMatchingReaderIdentifier() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (_, readerKey1) = createReader(harness, "Reader 1")
        addPidSdJwtVc(harness, "my-PID-Restricted", readerIdentifiers = listOf(UNKNOWN_AKI))

        val e = assertFailsWith(Iso18015ResponseException::class) {
            pidSdJwtQuery(readerKey = readerKey1).execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun sdJwtVcUnauthenticatedRequestBlocked() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, _) = createReader(harness, "Reader 1")
        addPidSdJwtVc(harness, "my-PID-Restricted", readerIdentifiers = listOf(aki1))

        val e = assertFailsWith(Iso18015ResponseException::class) {
            pidSdJwtQuery(readerKey = null).execute(
                presentmentSource = harness.presentmentSource
            )
        }
        assertEquals("No matching credentials for first DocRequest", e.message)
    }

    @Test
    fun mdocReaderIdentifierMatchesIntermediateCertificate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, readerKey) = createReaderWithIntermediate(harness)

        addMdl(harness, "my-mDL-3Tier", readerIdentifiers = listOf(skiIntermediate))

        // Matching via intermediate certificate's SKI (which matches Reader DS cert's AKI)
        val resultIntermediate = mdlQuery(readerKey = readerKey).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIntermediate.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-3Tier", resultIntermediate.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun mdocReaderIdentifierMatchesRootCertificateViaIntermediate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, readerKey) = createReaderWithIntermediate(harness)

        addMdl(harness, "my-mDL-3Tier", readerIdentifiers = listOf(skiRoot))

        // Matching via Root CA's SKI (which matches Intermediate CA cert's AKI)
        val resultRoot = mdlQuery(readerKey = readerKey).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultRoot.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-mDL-3Tier", resultRoot.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun sdJwtVcReaderIdentifierMatchesIntermediateCertificate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, readerKey) = createReaderWithIntermediate(harness)

        addPidSdJwtVc(harness, "my-PID-3Tier", readerIdentifiers = listOf(skiIntermediate))

        val resultIntermediate = pidSdJwtQuery(readerKey = readerKey).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultIntermediate.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-PID-3Tier", resultIntermediate.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun sdJwtVcReaderIdentifierMatchesRootCertificateViaIntermediate() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, readerKey) = createReaderWithIntermediate(harness)

        addPidSdJwtVc(harness, "my-PID-3Tier", readerIdentifiers = listOf(skiRoot))

        val resultRoot = pidSdJwtQuery(readerKey = readerKey).execute(
            presentmentSource = harness.presentmentSource
        )
        assertEquals(1, resultRoot.credentialSets[0].options[0].members[0].matches.size)
        assertEquals("my-PID-3Tier", resultRoot.credentialSets[0].options[0].members[0].matches[0].credential.document.displayName)
    }

    @Test
    fun useCasesWithReaderIdentifiers() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1", serial = 1)
        val (aki2, _) = createReader(harness, "Reader 2", serial = 2)

        addMdl(harness, "my-mDL-Reader1", readerIdentifiers = listOf(aki1))
        addMdl(harness, "my-mDL-Reader2", readerIdentifiers = listOf(aki2))

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
                readerKey = readerKey1
            )
        }

        val result = request.execute(presentmentSource = harness.presentmentSource)
        assertEquals(1, result.credentialSets.size)
        assertEquals(1, result.credentialSets[0].options.size)
        val matches = result.credentialSets[0].options[0].members[0].matches
        assertEquals(1, matches.size)
        assertEquals("my-mDL-Reader1", matches[0].credential.document.displayName)
    }
}
