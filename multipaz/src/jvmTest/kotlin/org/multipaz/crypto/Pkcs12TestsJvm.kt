package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.testUtilSetupCryptoProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class Pkcs12TestsJvm {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testJavaKeyStoreCanLoadGeneratedPkcs12() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        val cert = buildX509Cert(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, EcCurve.P256.defaultSigningAlgorithmFullySpecified),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=JCA Interop Test,O=Multipaz"),
            issuer = X500Name.fromName("CN=JCA Interop Test,O=Multipaz"),
            validFrom = now - 1.days,
            validUntil = now + 1.days
        ) {}

        val p12 = Pkcs12(key, X509CertChain(listOf(cert)))
        val passphrase = "test_keystore_pass"
        val derBytes = p12.toDer(passphrase)

        // Load into standard Java KeyStore
        val keyStore = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(derBytes.toByteArray()).use { bis ->
            keyStore.load(bis, passphrase.toCharArray())
        }

        val aliases = keyStore.aliases().toList()
        assertTrue(aliases.isNotEmpty(), "KeyStore should contain at least one alias")
        val alias = aliases.first()
        assertTrue(keyStore.isKeyEntry(alias))

        // Check recovered certificate chain
        val javaCertChain = keyStore.getCertificateChain(alias)
        assertEquals(1, javaCertChain.size)
        val recoveredCert = javaCertChain[0] as X509Certificate
        assertContentEquals(cert.encoded.toByteArray(), recoveredCert.encoded)

        // Check recovered private key
        val javaKey = keyStore.getKey(alias, passphrase.toCharArray()) as PrivateKey
        val recoveredPrivateKey = javaKey.toEcPrivateKey(cert.ecPublicKey.javaPublicKey, EcCurve.P256)
        assertContentEquals(key.d, recoveredPrivateKey.d)
    }

    @Test
    fun testOpenSslCanVerifyGeneratedPkcs12NullPassphrase() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        val cert = buildX509Cert(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, EcCurve.P256.defaultSigningAlgorithmFullySpecified),
            serialNumber = ASN1Integer(100L),
            subject = X500Name.fromName("CN=OpenSSL Interop Test Null,O=Multipaz"),
            issuer = X500Name.fromName("CN=OpenSSL Interop Test Null,O=Multipaz"),
            validFrom = now - 1.days,
            validUntil = now + 1.days
        ) {}

        val p12 = Pkcs12(key, X509CertChain(listOf(cert)))
        val derBytes = p12.toDer(passphrase = null)

        val tempFile = File.createTempFile("multipaz_openssl_test_null", ".p12")
        try {
            tempFile.writeBytes(derBytes.toByteArray())

            val process = ProcessBuilder(
                "openssl", "pkcs12",
                "-in", tempFile.absolutePath,
                "-passin", "pass:",
                "-info",
                "-noout"
            ).start()

            val exitCode = process.waitFor()
            val errorText = process.errorStream.bufferedReader().readText()
            val outputText = process.inputStream.bufferedReader().readText()

            assertEquals(0, exitCode, "OpenSSL failed to verify generated PKCS#12 file:\n$errorText\n$outputText")
        } finally {
            tempFile.delete()
        }

        val decoded = Pkcs12.fromDer(derBytes, passphrase = null)
        assertContentEquals(key.d, decoded.privateKey.d)
    }

    @Test
    fun testOpenSslCanVerifyGeneratedPkcs12() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        val cert = buildX509Cert(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, EcCurve.P256.defaultSigningAlgorithmFullySpecified),
            serialNumber = ASN1Integer(100L),
            subject = X500Name.fromName("CN=OpenSSL Interop Test,O=Multipaz"),
            issuer = X500Name.fromName("CN=OpenSSL Interop Test,O=Multipaz"),
            validFrom = now - 1.days,
            validUntil = now + 1.days
        ) {}

        val p12 = Pkcs12(key, X509CertChain(listOf(cert)))
        val passphrase = "openssl_passphrase_123"
        val derBytes = p12.toDer(passphrase)

        val tempFile = File.createTempFile("multipaz_openssl_test", ".p12")
        try {
            tempFile.writeBytes(derBytes.toByteArray())

            val process = ProcessBuilder(
                "openssl", "pkcs12",
                "-in", tempFile.absolutePath,
                "-passin", "pass:$passphrase",
                "-info",
                "-noout"
            ).start()

            val exitCode = process.waitFor()
            val errorText = process.errorStream.bufferedReader().readText()
            val outputText = process.inputStream.bufferedReader().readText()

            assertEquals(0, exitCode, "OpenSSL failed to verify generated PKCS#12 file:\n$errorText\n$outputText")
        } finally {
            tempFile.delete()
        }
    }
}
