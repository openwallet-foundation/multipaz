package org.multipaz.sdjwt.credential

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
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
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.sdjwt.SdJwt
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class SdJwtVcCredentialValidationTest {

    private lateinit var harness: DocumentStoreTestHarness
    private lateinit var storage: EphemeralStorage
    private lateinit var secureArea: SoftwareSecureArea
    private lateinit var documentStore: DocumentStore

    private suspend fun createIssuerKey(
        validFrom: Instant,
        validUntil: Instant
    ): AsymmetricKey.X509Certified {
        val ecKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val cert = X509Cert.Builder(
            publicKey = ecKey.publicKey,
            signingKey = AsymmetricKey.anonymous(ecKey, Algorithm.ES256),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Test Issuer"),
            issuer = X500Name.fromName("CN=Test Issuer"),
            validFrom = validFrom,
            validUntil = validUntil
        ).setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE)).build()
        return AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(cert)),
            privateKey = ecKey
        )
    }

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
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            secureArea = secureArea,
            vct = "https://example.com/vct",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        val result = credential.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("not certified") })
    }

    @Test
    fun testValidCertifiedKeyBoundCredential() = runTest {
        harness.provisionStandardDocuments()
        val credential = harness.docEuPid.getCredentials().filterIsInstance<KeyBoundSdJwtVcCredential>().first()
        val result = credential.validate(now = harness.validFrom + 5.days)
        assertFalse(result.hasErrors, "Validation errors: ${result.errors.map { it.message }}")
        assertFalse(result.hasWarnings, "Validation warnings: ${result.warnings.map { it.message }}")
    }

    @Test
    fun testValidCertifiedKeylessCredential() = runTest {
        val document = documentStore.createDocument()
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val credential = KeylessSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            vct = "https://example.com/vct"
        )
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "Jane")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", credential.vct)
                put("iat", now.epochSeconds)
                put("nbf", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        val candidateData = sdJwt.compactSerialization.encodeToByteString()
        val preCertifyResult = credential.validate(candidateData, now = now + 1.days)
        assertFalse(preCertifyResult.hasErrors, "Pre-certify errors: ${preCertifyResult.errors.map { it.message }}")

        credential.certify(candidateData)
        val result = credential.validate(now = now + 1.days)
        assertFalse(result.hasErrors, "Errors: ${result.errors.map { it.message }}")
        assertFalse(result.hasWarnings, "Warnings: ${result.warnings.map { it.message }}")
    }

    @Test
    fun testValidateCandidateDataBeforeCertifyKeyBound() = runTest {
        val document = documentStore.createDocument()
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            secureArea = secureArea,
            vct = "https://example.com/vct",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = credential.getAttestation().publicKey,
            claims = buildJsonObject {
                put("given_name", "Jane")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", credential.vct)
                put("cnf", buildJsonObject {
                    put("jwk", credential.getAttestation().publicKey.toJwk())
                })
                put("iat", now.epochSeconds)
                put("nbf", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        val candidateData = sdJwt.compactSerialization.encodeToByteString()
        val preCertifyResult = credential.validate(candidateData, now = now + 1.days)
        assertFalse(preCertifyResult.hasErrors, "Pre-certify errors: ${preCertifyResult.errors.map { it.message }}")

        credential.certify(candidateData)
        val postCertifyResult = credential.validate(now = now + 1.days)
        assertFalse(postCertifyResult.hasErrors, "Post-certify errors: ${postCertifyResult.errors.map { it.message }}")
    }

    @Test
    fun testVctMismatch() = runTest {
        val document = documentStore.createDocument()
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            secureArea = secureArea,
            vct = "https://example.com/vct_credential",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = secureArea.getKeyInfo(credential.alias).publicKey,
            claims = buildJsonObject {
                put("given_name", "Jane")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct_different") // mismatch!
                put("iat", now.epochSeconds)
                put("nbf", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        credential.certify(sdJwt.compactSerialization.encodeToByteString())
        val result = credential.validate(now = now + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("vct", ignoreCase = true) && it.message.contains("does not match") })
    }

    @Test
    fun testDeviceKeyMismatch() = runTest {
        val document = documentStore.createDocument()
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            secureArea = secureArea,
            vct = "https://example.com/vct",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        // Bind to a completely different key
        val differentKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = differentKey, // mismatch with secureArea.getKeyInfo(credential.alias).publicKey!
            claims = buildJsonObject {
                put("given_name", "Jane")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", credential.vct)
                put("iat", now.epochSeconds)
                put("nbf", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        credential.certify(sdJwt.compactSerialization.encodeToByteString())
        val result = credential.validate(now = now + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("does not match 'cnf.jwk'") })
    }

    @Test
    fun testMissingCnfOnKeyBoundCredential() = runTest {
        val document = documentStore.createDocument()
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val credential = KeyBoundSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            secureArea = secureArea,
            vct = "https://example.com/vct",
            createKeySettings = SoftwareCreateKeySettings.Builder().build()
        )
        // Create SD-JWT without kbKey (missing cnf)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "Jane")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", credential.vct)
                put("iat", now.epochSeconds)
                put("nbf", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        credential.certify(sdJwt.compactSerialization.encodeToByteString())
        val result = credential.validate(now = now + 1.days)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("missing 'cnf.jwk' key-binding") })
    }

    @Test
    fun testUnexpectedCnfOnKeylessCredential() = runTest {
        val document = documentStore.createDocument()
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val credential = KeylessSdJwtVcCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "sdjwt",
            vct = "https://example.com/vct"
        )
        // Create SD-JWT with kbKey (unexpected cnf on keyless)
        val arbitraryKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = arbitraryKey,
            claims = buildJsonObject {
                put("given_name", "Jane")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", credential.vct)
                put("iat", now.epochSeconds)
                put("nbf", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        credential.certify(sdJwt.compactSerialization.encodeToByteString())
        val result = credential.validate(now = now + 1.days)
        assertFalse(result.hasErrors)
        assertTrue(result.hasWarnings)
        assertTrue(result.warnings.any { it.message.contains("unexpected key-binding 'cnf'") })
    }
}
