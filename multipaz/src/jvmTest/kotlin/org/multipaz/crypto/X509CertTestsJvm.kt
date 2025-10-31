package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Boolean
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.util.toHex
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

class X509CertTestsJvm {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    // This is Maryland's IACA certificate (IACA_Root_2024.cer) downloaded from
    //
    //  https://mva.maryland.gov/Pages/MDMobileID_Googlewallet.aspx
    //
    private val exampleX509Cert = X509Cert.fromPem(
        """
-----BEGIN CERTIFICATE-----
MIICxjCCAmygAwIBAgITJkV7El8K11IXqY7mz96n/EhiITAKBggqhkjOPQQDAjBq
MQ4wDAYDVQQIEwVVUy1NRDELMAkGA1UEBhMCVVMxFDASBgNVBAcTC0dsZW4gQnVy
bmllMRUwEwYDVQQKEwxNYXJ5bGFuZCBNVkExHjAcBgNVBAMTFUZhc3QgRW50ZXJw
cmlzZXMgUm9vdDAeFw0yNDAxMDUwNTAwMDBaFw0yOTAxMDQwNTAwMDBaMGoxDjAM
BgNVBAgTBVVTLU1EMQswCQYDVQQGEwJVUzEUMBIGA1UEBxMLR2xlbiBCdXJuaWUx
FTATBgNVBAoTDE1hcnlsYW5kIE1WQTEeMBwGA1UEAxMVRmFzdCBFbnRlcnByaXNl
cyBSb290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaWcKIqlAWboV93RAa5ad
0LJBn8W0/yYwtOyUlxuTxoo4SPkorKmOz3EhThC+U4WRrt13aSnCsJtK+waBFghX
u6OB8DCB7TAOBgNVHQ8BAf8EBAMCAQYwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNV
HQ4EFgQUTprRzaFBJ1SLjJsO01tlLCQ4YF0wPAYDVR0SBDUwM4EWbXZhY3NAbWRv
dC5zdGF0ZS5tZC51c4YZaHR0cHM6Ly9tdmEubWFyeWxhbmQuZ292LzBYBgNVHR8E
UTBPME2gS6BJhkdodHRwczovL215bXZhLm1hcnlsYW5kLmdvdjo1NDQzL01EUC9X
ZWJTZXJ2aWNlcy9DUkwvbURML3Jldm9jYXRpb25zLmNybDAQBgkrBgEEAYPFIQEE
A01EUDAKBggqhkjOPQQDAgNIADBFAiEAnX3+E4E5dQ+5G1rmStJTW79ZAiDTabyL
8lJuYL/nDxMCIHHkAyIJcQlQmKDUVkBr3heUd5N9Y8GWdbWnbHuwe7Om
-----END CERTIFICATE-----
        """.trimIndent())

    @Test
    fun testX509Parsing() {
        val cert = exampleX509Cert
        val javaCert = exampleX509Cert.javaX509Certificate

        // This checks that out own ASN.1 / X.509 routines agree with the ones in Java.
        //

        assertEquals(cert.subject.name, javaCert.subjectX500Principal.name)
        assertEquals(cert.issuer.name, javaCert.issuerX500Principal.name)
        assertEquals(cert.version, javaCert.version - 1)
        assertEquals(cert.serialNumber, ASN1Integer(javaCert.serialNumber.toByteArray()))
        assertEquals(cert.validityNotBefore, javaCert.notBefore.toInstant().toKotlinInstant())
        assertEquals(cert.validityNotAfter, javaCert.notAfter.toInstant().toKotlinInstant())

        assertContentEquals(cert.tbsCertificate, javaCert.tbsCertificate)
        assertContentEquals(cert.signature, javaCert.signature)
        assertEquals(cert.signatureAlgorithm, Algorithm.ES256)

        assertEquals(cert.criticalExtensionOIDs, javaCert.criticalExtensionOIDs)
        assertEquals(cert.nonCriticalExtensionOIDs, javaCert.nonCriticalExtensionOIDs)

        for (oid in cert.criticalExtensionOIDs + cert.nonCriticalExtensionOIDs) {
            // Note: Java API always return the value wrapped in an OCTET STRING. So we need to unwrap it.
            val ourValue = cert.getExtensionValue(oid)
            val javaValue = javaCert.getExtensionValue(oid)
            val javaValueUnwrapped = (ASN1.decode(javaValue) as ASN1OctetString).value
            assertContentEquals(ourValue, javaValueUnwrapped)
        }
        // Check non-existent extensions (1.2.3.4.5 as an example) return null
        assertNull(cert.getExtensionValue("1.2.3.4.5"))
        assertNull(javaCert.getExtensionValue("1.2.3.4.5"))
        assertEquals("4e9ad1cda14127548b8c9b0ed35b652c2438605d", cert.subjectKeyIdentifier!!.toHex())
    }

    // Checks that the Java X509Certificate.verify() works with certificates created by X509Cert.Builder
    private fun testJavaCertSignedWithCurve(curve: EcCurve) = runTest {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return@runTest
        }
        val key = Crypto.createEcPrivateKey(curve)
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val cert = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, key.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Foobar"),
            issuer = X500Name.fromName("CN=Foobar"),
            validFrom = now - 1.hours,
            validUntil = now + 1.hours
        ).build()
        val javaCert = cert.javaX509Certificate
        javaCert.verify(key.publicKey.javaPublicKey)
    }

    @Test fun testCertSignedWithCurve_P256() = testJavaCertSignedWithCurve(EcCurve.P256)
    @Test fun testCertSignedWithCurve_P384() = testJavaCertSignedWithCurve(EcCurve.P384)
    @Test fun testCertSignedWithCurve_P521() = testJavaCertSignedWithCurve(EcCurve.P521)
    @Test fun testCertSignedWithCurve_BRAINPOOLP256R1() = testJavaCertSignedWithCurve(EcCurve.BRAINPOOLP256R1)
    @Test fun testCertSignedWithCurve_BRAINPOOLP320R1() = testJavaCertSignedWithCurve(EcCurve.BRAINPOOLP320R1)
    @Test fun testCertSignedWithCurve_BRAINPOOLP384R1() = testJavaCertSignedWithCurve(EcCurve.BRAINPOOLP384R1)
    @Test fun testCertSignedWithCurve_BRAINPOOLP512R1() = testJavaCertSignedWithCurve(EcCurve.BRAINPOOLP512R1)
    @Test fun testCertSignedWithCurve_ED25519() = testJavaCertSignedWithCurve(EcCurve.ED25519)
    @Test fun testCertSignedWithCurve_ED448() = testJavaCertSignedWithCurve(EcCurve.ED448)

    @Test
    fun testGenerateAndValidate() = runTest {
        // OpenSSL does not believe certificates issued before this date (even if it recognizes it)
        val from = Instant.parse("1950-01-01T00:00:00Z")

        // This is selected to be the first instant when 4 digit year must be used (and 2 digit
        // year must be used in certificates before that).
        // See https://datatracker.ietf.org/doc/html/rfc5280#section-4.1.2.5
        val until = Instant.parse("2050-01-01T00:00:00Z")

        // Basic Constraints extension, marks certificate as Certificate Authority
        val caExt = ASN1Sequence(listOf(
            ASN1Boolean(true)
        ))
        val caExtBytes = ByteStringBuilder()
        caExt.encode(caExtBytes)

        val caKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val caCertificate =
            X509Cert.Builder(
                publicKey = caKey.publicKey,
                signingKey = AsymmetricKey.anonymous(caKey, caKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(112676L),
                subject = X500Name.fromName("CN=TestCA"),
                issuer = X500Name.fromName("CN=TestCA"),
                validFrom = from,
                validUntil = until
            )
                .includeSubjectKeyIdentifier()
                .addExtension("2.5.29.19", false, caExtBytes.toByteString().toByteArray())
                .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                .build()

        val leafKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCertificate = X509Cert.Builder(
            publicKey = leafKey.publicKey,
            signingKey = AsymmetricKey.X509CertifiedExplicit(
                privateKey = caKey,
                certChain = X509CertChain(listOf(caCertificate)),
                algorithm = caKey.curve.defaultSigningAlgorithm
            ),
            serialNumber = ASN1Integer(1222682L),
            subject = X500Name.fromName("CN=TestLeaf"),
            issuer = caCertificate.subject,
            validFrom = from + 1.seconds,
            validUntil = until - 1.seconds
        )
            .includeSubjectKeyIdentifier()
            .setAuthorityKeyIdentifierToCertificate(caCertificate)
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        assertTrue(X509CertChain(listOf(leafCertificate, caCertificate)).validate())

        // To manually test with OpenSSL:
        // (1) Uncomment the following two lines, adjusting folder name.
        // File("/Users/kmy/junk/leaf.pem").writeText(leafCertificate.toPem())
        // File("/Users/kmy/junk/root.pem").writeText(caCertificate.toPem())
        // (2) Make sure `openssl verify -verbose -CAfile root.pem leaf.pem` succeeds
    }
}