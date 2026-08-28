package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class Pkcs12Tests {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    // -------------------------------------------------------------------------
    // PBKDF2 RFC 6070 Test Vectors
    // -------------------------------------------------------------------------

    @Test
    fun testPbkdf2Rfc6070Test1() = runTest {
        val dk = Pbkdf2.deriveKey(
            prfAlgorithm = Algorithm.HMAC_INSECURE_SHA1,
            password = "password".encodeToByteArray(),
            salt = "salt".encodeToByteArray(),
            iterationCount = 1,
            keyLength = 20
        )
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", dk.toHex().lowercase())
    }

    @Test
    fun testPbkdf2Rfc6070Test2() = runTest {
        val dk = Pbkdf2.deriveKey(
            prfAlgorithm = Algorithm.HMAC_INSECURE_SHA1,
            password = "password".encodeToByteArray(),
            salt = "salt".encodeToByteArray(),
            iterationCount = 2,
            keyLength = 20
        )
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", dk.toHex().lowercase())
    }

    @Test
    fun testPbkdf2Rfc6070Test3() = runTest {
        val dk = Pbkdf2.deriveKey(
            prfAlgorithm = Algorithm.HMAC_INSECURE_SHA1,
            password = "password".encodeToByteArray(),
            salt = "salt".encodeToByteArray(),
            iterationCount = 4096,
            keyLength = 20
        )
        assertEquals("4b007901b765489abead49d926f721d065a429c1", dk.toHex().lowercase())
    }

    @Test
    fun testPbkdf2HmacSha256() = runTest {
        val dk = Pbkdf2.deriveKey(
            prfAlgorithm = Algorithm.HMAC_SHA256,
            password = "password".encodeToByteArray(),
            salt = "salt".encodeToByteArray(),
            iterationCount = 4096,
            keyLength = 32
        )
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", dk.toHex().lowercase())
    }

    // -------------------------------------------------------------------------
    // AES-CBC & PKCS#7 Padding
    // -------------------------------------------------------------------------

    @Test
    fun testAesCbcNistVectors() = runTest {
        // NIST SP 800-38A F.2.1 AES-128-CBC
        val key128 = "2b7e151628aed2a6abf7158809cf4f3c".fromHex()
        val iv = "000102030405060708090a0b0c0d0e0f".fromHex()
        val pt = "6bc1bee22e409f96e93d7e117393172a".fromHex()

        val ct = Crypto.encrypt(Algorithm.A128CBC, key128, iv, pt)
        val decrypted = Crypto.decrypt(Algorithm.A128CBC, key128, iv, ct)
        assertContentEquals(pt, decrypted)

        // AES-256-CBC
        val key256 = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".fromHex()
        val ct256 = Crypto.encrypt(Algorithm.A256CBC, key256, iv, pt)
        val decrypted256 = Crypto.decrypt(Algorithm.A256CBC, key256, iv, ct256)
        assertContentEquals(pt, decrypted256)
    }

    @Test
    fun testAesCbcInvalidPaddingThrows() = runTest {
        val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".fromHex()
        val iv = "000102030405060708090a0b0c0d0e0f".fromHex()
        val corruptedCt = ByteArray(32) { 0x42 }

        assertFailsWith<IllegalStateException> {
            Crypto.decrypt(Algorithm.A256CBC, key, iv, corruptedCt)
        }
    }

    @Test
    fun testAesCbcAlgorithmIdentifiers() {
        assertEquals(-65531, Algorithm.A128CBC.coseAlgorithmIdentifier)
        assertEquals(-65530, Algorithm.A192CBC.coseAlgorithmIdentifier)
        assertEquals(-65529, Algorithm.A256CBC.coseAlgorithmIdentifier)

        assertEquals("A128CBC", Algorithm.A128CBC.joseAlgorithmIdentifier)
        assertEquals("A192CBC", Algorithm.A192CBC.joseAlgorithmIdentifier)
        assertEquals("A256CBC", Algorithm.A256CBC.joseAlgorithmIdentifier)

        assertEquals(Algorithm.A128CBC, Algorithm.fromCoseAlgorithmIdentifier(-65531))
        assertEquals(Algorithm.A192CBC, Algorithm.fromCoseAlgorithmIdentifier(-65530))
        assertEquals(Algorithm.A256CBC, Algorithm.fromCoseAlgorithmIdentifier(-65529))

        assertEquals(Algorithm.A128CBC, Algorithm.fromJoseAlgorithmIdentifier("A128CBC"))
        assertEquals(Algorithm.A192CBC, Algorithm.fromJoseAlgorithmIdentifier("A192CBC"))
        assertEquals(Algorithm.A256CBC, Algorithm.fromJoseAlgorithmIdentifier("A256CBC"))
    }

    // -------------------------------------------------------------------------
    // OpenSSL Test Vectors Decoding
    // -------------------------------------------------------------------------

    @Test
    fun testDecodeBobBobson() = runTest {
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.bobBobsonBytes, "xyz123")
        assertEquals(EcCurve.P256, p12.privateKey.curve)
        assertEquals(2, p12.certChain.certificates.size)

        val leaf = p12.certChain.certificates[0]
        assertEquals("CN=Bob Bobson,O=Utopia Brewery,C=ZZ", leaf.subject.name)
        assertEquals("CN=Utopia Brewery Reader CA,O=Utopia Brewery,C=ZZ", leaf.issuer.name)
        assertEquals(p12.privateKey.publicKey, leaf.ecPublicKey)

        val ca = p12.certChain.certificates[1]
        assertEquals("CN=Utopia Brewery Reader CA,O=Utopia Brewery,C=ZZ", ca.subject.name)
    }

    @Test
    fun testDecodeBobBobsonWrongPassphraseThrows() = runTest {
        assertFailsWith<Pkcs12WrongPassphraseException> {
            Pkcs12.fromDer(Pkcs12TestVectors.bobBobsonBytes, "wrong_pass")
        }
    }

    @Test
    fun testDecodeP384Chain() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.P384)) return@runTest
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.p384ChainBytes, "secret384")
        assertEquals(EcCurve.P384, p12.privateKey.curve)
        assertEquals(2, p12.certChain.certificates.size)
        assertEquals("O=Multipaz Test,CN=P384 Leaf", p12.certChain.certificates[0].subject.name)
        assertEquals("O=Multipaz Test,CN=P384 Root CA", p12.certChain.certificates[1].subject.name)
        assertEquals(p12.privateKey.publicKey, p12.certChain.certificates[0].ecPublicKey)
    }

    @Test
    fun testDecodeP521Chain() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.P521)) return@runTest
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.p521ChainBytes, "secret521")
        assertEquals(EcCurve.P521, p12.privateKey.curve)
        assertEquals(3, p12.certChain.certificates.size)
        assertEquals("CN=P521 Leaf", p12.certChain.certificates[0].subject.name)
        assertEquals("CN=P521 Intermediate CA", p12.certChain.certificates[1].subject.name)
        assertEquals("CN=P521 Root CA", p12.certChain.certificates[2].subject.name)
        assertEquals(p12.privateKey.publicKey, p12.certChain.certificates[0].ecPublicKey)
    }

    @Test
    fun testDecodeEd25519() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.ED25519)) return@runTest
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.ed25519Bytes, "ed25519_pass")
        assertEquals(EcCurve.ED25519, p12.privateKey.curve)
        assertEquals(1, p12.certChain.certificates.size)
        assertEquals("CN=Ed25519 Test", p12.certChain.certificates[0].subject.name)
        assertEquals(p12.privateKey.publicKey, p12.certChain.certificates[0].ecPublicKey)
    }

    @Test
    fun testDecodeEd448() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.ED448)) return@runTest
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.ed448Bytes, "ed448_pass")
        assertEquals(EcCurve.ED448, p12.privateKey.curve)
        assertEquals(1, p12.certChain.certificates.size)
        assertEquals("CN=Ed448 Test", p12.certChain.certificates[0].subject.name)
        assertEquals(p12.privateKey.publicKey, p12.certChain.certificates[0].ecPublicKey)
    }

    @Test
    fun testDecodeBrainpoolP256() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.BRAINPOOLP256R1)) return@runTest
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.brainpoolP256Bytes, "brainpool_pass")
        assertEquals(EcCurve.BRAINPOOLP256R1, p12.privateKey.curve)
        assertEquals(1, p12.certChain.certificates.size)
        assertEquals("CN=Brainpool P256 Test", p12.certChain.certificates[0].subject.name)
        assertEquals(p12.privateKey.publicKey, p12.certChain.certificates[0].ecPublicKey)
    }

    @Test
    fun testDecodeEmptyPassphrase() = runTest {
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.emptyPassphraseBytes, "")
        assertEquals(EcCurve.P256, p12.privateKey.curve)
        assertEquals(1, p12.certChain.certificates.size)
        assertEquals("CN=Empty Pass Test", p12.certChain.certificates[0].subject.name)
    }

    @Test
    fun testDecodeNullPassphrase() = runTest {
        val p12Empty = Pkcs12.fromDer(Pkcs12TestVectors.emptyPassphraseBytes, null)
        assertEquals(EcCurve.P256, p12Empty.privateKey.curve)
        assertEquals("CN=Empty Pass Test", p12Empty.certChain.certificates[0].subject.name)

        val p12Plain = Pkcs12.fromDer(Pkcs12TestVectors.unencryptedPlainBytes, null)
        assertEquals(EcCurve.P256, p12Plain.privateKey.curve)
        assertEquals("CN=Plain Test", p12Plain.certChain.certificates[0].subject.name)
    }

    @Test
    fun testDecodeProtectedWithNullPassphraseThrows() = runTest {
        assertFailsWith<Pkcs12WrongPassphraseException> {
            Pkcs12.fromDer(Pkcs12TestVectors.bobBobsonBytes, null)
        }
    }

    @Test
    fun testDecodeAes128Pbes2() = runTest {
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.aes128Pbes2Bytes, "pass128")
        assertEquals(EcCurve.P256, p12.privateKey.curve)
        assertEquals(1, p12.certChain.certificates.size)
        assertEquals("CN=AES128 Test", p12.certChain.certificates[0].subject.name)
    }

    @Test
    fun testDecodeUnencryptedPlain() = runTest {
        val p12 = Pkcs12.fromDer(Pkcs12TestVectors.unencryptedPlainBytes, null)
        assertEquals(EcCurve.P256, p12.privateKey.curve)
        assertEquals(1, p12.certChain.certificates.size)
        assertEquals("CN=Plain Test", p12.certChain.certificates[0].subject.name)
    }

    @Test
    fun testDecodeInvalidDataThrows() = runTest {
        assertFailsWith<IllegalArgumentException> {
            Pkcs12.fromDer(ByteString("not a valid p12".encodeToByteArray()), "pass")
        }
    }

    // -------------------------------------------------------------------------
    // Generation & Round-Trip Tests
    // -------------------------------------------------------------------------

    private fun testRoundTripWithCurve(curve: EcCurve, passphrase: String?) = runTest {
        if (!Crypto.supportedCurves.contains(curve)) return@runTest

        val key = Crypto.createEcPrivateKey(curve)
        val now = Clock.System.now()
        val cert = buildX509Cert(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, curve.defaultSigningAlgorithmFullySpecified),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Roundtrip,O=Multipaz"),
            issuer = X500Name.fromName("CN=Test Roundtrip,O=Multipaz"),
            validFrom = now - 1.days,
            validUntil = now + 1.days
        ) {}

        val originalP12 = Pkcs12(key, X509CertChain(listOf(cert)))
        val encodedDer = originalP12.toDer(passphrase)

        val decodedP12 = Pkcs12.fromDer(encodedDer, passphrase)
        assertEquals(originalP12.privateKey.curve, decodedP12.privateKey.curve)
        assertContentEquals(originalP12.privateKey.d, decodedP12.privateKey.d)
        assertEquals(originalP12.certChain.certificates.size, decodedP12.certChain.certificates.size)
        assertEquals(originalP12.certChain.certificates[0].subject, decodedP12.certChain.certificates[0].subject)
        assertEquals(originalP12.certChain.certificates[0].encoded, decodedP12.certChain.certificates[0].encoded)

        // Verify that the decoded key can sign and the decoded cert verifies it
        if (curve.defaultSigningAlgorithmFullySpecified.isSigning) {
            val message = "Hello PKCS#12".encodeToByteArray()
            val signature = Crypto.sign(decodedP12.privateKey, curve.defaultSigningAlgorithmFullySpecified, message)
            Crypto.checkSignature(
                publicKey = decodedP12.certChain.certificates[0].ecPublicKey,
                message = message,
                algorithm = curve.defaultSigningAlgorithmFullySpecified,
                signature = signature
            )
        }

        // Verify wrong passphrase on encoded DER fails
        assertFailsWith<Pkcs12WrongPassphraseException> {
            Pkcs12.fromDer(encodedDer, (passphrase ?: "") + "_wrong")
        }
    }

    @Test fun testRoundTripP256() = testRoundTripWithCurve(EcCurve.P256, "p256_passphrase")
    @Test fun testRoundTripP384() = testRoundTripWithCurve(EcCurve.P384, "p384_passphrase")
    @Test fun testRoundTripP521() = testRoundTripWithCurve(EcCurve.P521, "p521_passphrase")
    @Test fun testRoundTripEd25519() = testRoundTripWithCurve(EcCurve.ED25519, "ed25519_passphrase")
    @Test fun testRoundTripEd448() = testRoundTripWithCurve(EcCurve.ED448, "ed448_passphrase")
    @Test fun testRoundTripBrainpoolP256() = testRoundTripWithCurve(EcCurve.BRAINPOOLP256R1, "bp_passphrase")
    @Test fun testRoundTripEmptyPassphrase() = testRoundTripWithCurve(EcCurve.P256, "")
    @Test fun testRoundTripNullPassphrase() = testRoundTripWithCurve(EcCurve.P256, null)

    @Test
    fun testMultiCertChainRoundTrip() = runTest {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()

        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = AsymmetricKey.anonymous(rootKey, EcCurve.P256.defaultSigningAlgorithmFullySpecified),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Root CA,O=Multipaz"),
            issuer = X500Name.fromName("CN=Root CA,O=Multipaz"),
            validFrom = now - 1.days,
            validUntil = now + 1.days
        ) {
            includeSubjectKeyIdentifier()
            setBasicConstraints(true, 1)
        }

        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = AsymmetricKey.anonymous(rootKey, EcCurve.P256.defaultSigningAlgorithmFullySpecified),
            serialNumber = ASN1Integer(2L),
            subject = X500Name.fromName("CN=Leaf Client,O=Multipaz"),
            issuer = X500Name.fromName("CN=Root CA,O=Multipaz"),
            validFrom = now - 1.days,
            validUntil = now + 1.days
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }

        val originalP12 = Pkcs12(leafKey, X509CertChain(listOf(leafCert, rootCert)))
        val encodedDer = originalP12.toDer("chain_pass")

        val decodedP12 = Pkcs12.fromDer(encodedDer, "chain_pass")
        assertEquals(2, decodedP12.certChain.certificates.size)
        assertEquals("CN=Leaf Client,O=Multipaz", decodedP12.certChain.certificates[0].subject.name)
        assertEquals("CN=Root CA,O=Multipaz", decodedP12.certChain.certificates[1].subject.name)
        assertContentEquals(leafKey.d, decodedP12.privateKey.d)
    }
}
