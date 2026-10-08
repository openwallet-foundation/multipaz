package org.multipaz.mdoc.credential

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class MdocCredentialValidationTest {

    private lateinit var harness: DocumentStoreTestHarness
    private lateinit var storage: EphemeralStorage
    private lateinit var secureArea: SoftwareSecureArea
    private lateinit var documentStore: DocumentStore

    @BeforeTest
    fun setup() = runTest {
        harness = DocumentStoreTestHarness()
        harness.initialize()

        storage = EphemeralStorage()
        secureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder()
            .add(secureArea)
            .build()
        documentStore = buildDocumentStore(storage = storage, secureAreaRepository = secureAreaRepository) {}
    }

    @Test
    fun testUncertifiedCredential() = runTest {
        val document = documentStore.createDocument()
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = secureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        val result = credential.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("not certified") })
    }

    @Test
    fun testValidateCandidateDataBeforeCertify() = runTest {
        val document = documentStore.createDocument()
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = secureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )

        val namespaces = buildIssuerNamespaces {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("given_name", "John".toDataItem())
            }
        }
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = "org.iso.18013.5.1.mDL",
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = namespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = credential.getAttestation().ecPublicKey
        )
        val taggedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val issuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey = harness.dsKey,
                message = taggedMso,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()),
                unprotectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to harness.dsKey.certChain.toDataItem())
            ).toDataItem()
        )
        val candidateData = ByteString(
            Cbor.encode(
                buildCborMap {
                    put("nameSpaces", namespaces.toDataItem())
                    put("issuerAuth", RawCbor(issuerAuth))
                }
            )
        )

        // Validate candidate data BEFORE certifying
        val preCertifyResult = credential.validate(candidateData, now = harness.validFrom + 1.days)
        assertFalse(preCertifyResult.hasErrors)

        // Now certify and validate certified credential
        credential.certify(candidateData)
        val postCertifyResult = credential.validate(now = harness.validFrom + 1.days)
        assertFalse(postCertifyResult.hasErrors)
    }

    @Test
    fun testValidCertifiedCredential() = runTest {
        harness.provisionStandardDocuments()
        val credential = harness.docMdl.getCredentials().first() as MdocCredential
        val result = credential.validate(now = harness.validFrom + 10.days)
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun testDocTypeMismatch() = runTest {
        val document = documentStore.createDocument()
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = secureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )

        // Create MSO with different docType
        val namespaces = buildIssuerNamespaces {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("given_name", "John".toDataItem())
            }
        }
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = "different.doc.type", // mismatch!
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = namespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = credential.getAttestation().ecPublicKey
        )

        val taggedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val issuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey = harness.dsKey,
                message = taggedMso,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()),
                unprotectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to harness.dsKey.certChain.toDataItem())
            ).toDataItem()
        )
        val issuerProvidedData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", namespaces.toDataItem())
                put("issuerAuth", RawCbor(issuerAuth))
            }
        )

        credential.certify(ByteString(issuerProvidedData))
        val result = credential.validate(now = harness.validFrom + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("Credential docType") && it.message.contains("does not match MSO docType") })
    }

    @Test
    fun testDeviceKeyMismatch() = runTest {
        val document = documentStore.createDocument()
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = secureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )

        // Create MSO bound to a different public key
        val namespaces = buildIssuerNamespaces {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("given_name", "John".toDataItem())
            }
        }
        val differentPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = "org.iso.18013.5.1.mDL",
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = namespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = differentPrivateKey.publicKey // mismatch!
        )

        val taggedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val issuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey = harness.dsKey,
                message = taggedMso,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()),
                unprotectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to harness.dsKey.certChain.toDataItem())
            ).toDataItem()
        )
        val issuerProvidedData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", namespaces.toDataItem())
                put("issuerAuth", RawCbor(issuerAuth))
            }
        )

        credential.certify(ByteString(issuerProvidedData))
        val result = credential.validate(now = harness.validFrom + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("does not match deviceKey in MSO") })
    }

    @Test
    fun testCorruptedMsoSignature() = runTest {
        val document = documentStore.createDocument()
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = secureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        val namespaces = buildIssuerNamespaces {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("given_name", "John".toDataItem())
            }
        }
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = "org.iso.18013.5.1.mDL",
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = namespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = credential.getAttestation().ecPublicKey
        )

        val taggedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val coseSign1 = Cose.coseSign1Sign(
            signingKey = harness.dsKey,
            message = taggedMso,
            includeMessageInPayload = true,
            protectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()),
            unprotectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to harness.dsKey.certChain.toDataItem())
        )
        // Corrupt signature bytes
        val badSignatureBytes = coseSign1.signature.copyOf().apply { this[0] = (this[0] + 1).toByte() }
        val badCoseSign1 = Cose.coseSign1Sign(
            signingKey = harness.dsKey,
            message = taggedMso,
            includeMessageInPayload = true,
            protectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()),
            unprotectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to harness.dsKey.certChain.toDataItem())
        )
        val badIssuerAuth = Cbor.encode(
            org.multipaz.cbor.buildCborArray {
                add(badCoseSign1.toDataItem().asArray[0])
                add(badCoseSign1.toDataItem().asArray[1])
                add(badCoseSign1.toDataItem().asArray[2])
                add(badSignatureBytes)
            }
        )
        val issuerProvidedData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", namespaces.toDataItem())
                put("issuerAuth", RawCbor(badIssuerAuth))
            }
        )

        credential.certify(ByteString(issuerProvidedData))
        val result = credential.validate(now = harness.validFrom + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("Signature on MSO failed to verify") })
    }

    @Test
    fun testDsCertMissingDigitalSignatureKeyUsage() = runTest {
        val document = documentStore.createDocument()
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = secureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        val namespaces = buildIssuerNamespaces {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("given_name", "John".toDataItem())
            }
        }
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = "org.iso.18013.5.1.mDL",
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = namespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = credential.getAttestation().ecPublicKey
        )

        // Generate DS cert with KEY_AGREEMENT instead of DIGITAL_SIGNATURE
        val dsKeyPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        val badDsCert = X509Cert.Builder(
            publicKey = dsKeyPriv.publicKey,
            signingKey = harness.iacaKey,
            serialNumber = ASN1Integer(12345L),
            subject = X500Name.fromName("CN=Bad DS"),
            issuer = harness.iacaCert.subject,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil
        )
            .setKeyUsage(setOf(X509KeyUsage.KEY_AGREEMENT)) // Missing DIGITAL_SIGNATURE!
            .build()

        val badDsKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(badDsCert, harness.iacaCert)),
            privateKey = dsKeyPriv
        )

        val taggedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val issuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey = badDsKey,
                message = taggedMso,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()),
                unprotectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to badDsKey.certChain.toDataItem())
            ).toDataItem()
        )
        val issuerProvidedData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", namespaces.toDataItem())
                put("issuerAuth", RawCbor(issuerAuth))
            }
        )

        credential.certify(ByteString(issuerProvidedData))
        val result = credential.validate(now = harness.validFrom + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("DIGITAL_SIGNATURE") })
    }

    @Test
    fun testMsoSignedTimestampOutsideDsCertValidity() = runTest {
        val document = documentStore.createDocument()
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc",
            secureArea = secureArea,
            docType = "org.iso.18013.5.1.mDL",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        val namespaces = buildIssuerNamespaces {
            addNamespace("org.iso.18013.5.1") {
                addDataElement("given_name", "John".toDataItem())
            }
        }
        // MSO signedAt is earlier than DS cert validFrom
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = "org.iso.18013.5.1.mDL",
            signedAt = harness.validFrom - 10.days, // Earlier than DS cert validFrom!
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = namespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = credential.getAttestation().ecPublicKey
        )

        val taggedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
        val issuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey = harness.dsKey,
                message = taggedMso,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_ALG) to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()),
                unprotectedHeaders = mapOf(CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to harness.dsKey.certChain.toDataItem())
            ).toDataItem()
        )
        val issuerProvidedData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", namespaces.toDataItem())
                put("issuerAuth", RawCbor(issuerAuth))
            }
        )

        credential.certify(ByteString(issuerProvidedData))
        val result = credential.validate(now = harness.validFrom + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("before DS certificate validity period") })
    }
}
