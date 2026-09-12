package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MlKemTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testCreateKeyAndEncapsulateDecapsulate() = runTest {
        val testAlgorithms = listOf(
            Triple(Algorithm.ML_KEM_512, 800, 768),
            Triple(Algorithm.ML_KEM_768, 1184, 1088),
            Triple(Algorithm.ML_KEM_1024, 1568, 1568),
        )

        for ((alg, expectedPubSize, expectedCiphertextSize) in testAlgorithms) {
            if (alg !in Crypto.supportedMlKemAlgorithms) {
                continue
            }
            val privKey = Crypto.createMlKemPrivateKey(alg)
            val pubKey = privKey.publicKey

            assertEquals(alg, pubKey.algorithm)
            assertEquals(alg, privKey.algorithm)
            assertEquals(expectedPubSize, pubKey.encoded.size)
            assertTrue(privKey.encoded.size > 0)

            // Encapsulate
            val kemResult = Crypto.kemEncapsulate(pubKey)
            assertEquals(32, kemResult.sharedSecret.size)
            assertEquals(expectedCiphertextSize, kemResult.ciphertext.size)

            // Decapsulate
            val decapsulatedSecret = Crypto.kemDecapsulate(privKey, kemResult.ciphertext)
            assertEquals(kemResult.sharedSecret, decapsulatedSecret)

            // Negative test 1: decapsulate with another private key (implicit rejection in ML-KEM)
            val otherPrivKey = Crypto.createMlKemPrivateKey(alg)
            val otherDecapsulatedSecret = Crypto.kemDecapsulate(otherPrivKey, kemResult.ciphertext)
            assertNotEquals(kemResult.sharedSecret, otherDecapsulatedSecret)

            // Negative test 2: decapsulate tampered ciphertext (implicit rejection in ML-KEM)
            val tamperedCiphertext = kemResult.ciphertext.copyOf()
            tamperedCiphertext[tamperedCiphertext.size - 1] =
                (tamperedCiphertext[tamperedCiphertext.size - 1].toInt() xor 0x01).toByte()
            val tamperedDecapsulatedSecret = Crypto.kemDecapsulate(privKey, tamperedCiphertext)
            assertNotEquals(kemResult.sharedSecret, tamperedDecapsulatedSecret)

            kemResult.close()
            decapsulatedSecret.close()
            otherDecapsulatedSecret.close()
            tamperedDecapsulatedSecret.close()
            privKey.close()
            otherPrivKey.close()
        }
    }

    @Test
    fun testSerializationRoundtrips() = runTest {
        for (alg in listOf(Algorithm.ML_KEM_512, Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024)) {
            if (alg !in Crypto.supportedMlKemAlgorithms) {
                continue
            }
            val privKey = Crypto.createMlKemPrivateKey(alg)
            val pubKey = privKey.publicKey

            // SubjectPublicKeyInfo roundtrip
            val spkiBytes = pubKey.toSubjectPublicKeyInfo()
            val pubFromSpki = MlKemPublicKey.fromSubjectPublicKeyInfo(spkiBytes)
            assertEquals(pubKey, pubFromSpki)

            // PrivateKeyInfo (PKCS#8) roundtrip
            val pkcs8Bytes = privKey.toPrivateKeyInfo()
            val privFromPkcs8 = MlKemPrivateKey.fromPrivateKeyInfo(pkcs8Bytes, pubKey)
            assertEquals(privKey, privFromPkcs8)

            // PEM roundtrip (MlKemPublicKey and PublicKey companion)
            val pubPem = pubKey.toPem()
            assertTrue(pubPem.startsWith("-----BEGIN PUBLIC KEY-----"))
            val pubFromPem = MlKemPublicKey.fromPem(pubPem)
            assertEquals(pubKey, pubFromPem)
            val pubFromGenericPem = PublicKey.fromPem(pubPem)
            assertEquals(pubKey, pubFromGenericPem)

            // PEM roundtrip (MlKemPrivateKey and PrivateKey companion)
            val privPem = privKey.toPem()
            assertTrue(privPem.startsWith("-----BEGIN PRIVATE KEY-----"))
            val privFromPem = MlKemPrivateKey.fromPem(privPem, pubKey)
            assertEquals(privKey, privFromPem)
            val privFromGenericPem = PrivateKey.fromPem(privPem, pubKey)
            assertEquals(privKey, privFromGenericPem)

            // JWK roundtrip (MlKemPublicKey and PublicKey companion)
            val pubJwk = pubKey.toJwk()
            val pubFromJwk = MlKemPublicKey.fromJwk(pubJwk)
            assertEquals(pubKey, pubFromJwk)
            val pubFromGenericJwk = PublicKey.fromJwk(pubJwk)
            assertEquals(pubKey, pubFromGenericJwk)

            // JWK roundtrip (MlKemPrivateKey and PrivateKey companion)
            val privJwk = privKey.toJwk()
            val privFromJwk = MlKemPrivateKey.fromJwk(privJwk)
            assertEquals(privKey, privFromJwk)
            val privFromGenericJwk = PrivateKey.fromJwk(privJwk)
            assertEquals(privKey, privFromGenericJwk)

            // COSE Key roundtrip (MlKemPublicKey and PublicKey companion)
            val pubCoseKey = pubKey.toCoseKey()
            val pubFromCose = MlKemPublicKey.fromCoseKey(pubCoseKey)
            assertEquals(pubKey, pubFromCose)
            val pubFromGenericCose = PublicKey.fromCoseKey(pubCoseKey)
            assertEquals(pubKey, pubFromGenericCose)
            assertEquals(pubKey, pubCoseKey.mlKemPublicKey)
            assertEquals(pubKey, pubCoseKey.publicKey)

            // COSE Key roundtrip (MlKemPrivateKey and PrivateKey companion)
            val privCoseKey = privKey.toCoseKey()
            val privFromCose = MlKemPrivateKey.fromCoseKey(privCoseKey)
            assertEquals(privKey, privFromCose)
            val privFromGenericCose = PrivateKey.fromCoseKey(privCoseKey)
            assertEquals(privKey, privFromGenericCose)
            assertEquals(privKey, privCoseKey.mlKemPrivateKey)
            assertEquals(privKey, privCoseKey.privateKey)

            // DataItem roundtrip
            val pubDataItem = pubKey.toDataItem()
            assertEquals(pubKey, MlKemPublicKey.fromDataItem(pubDataItem))
            assertEquals(pubKey, PublicKey.fromDataItem(pubDataItem))

            val privDataItem = privKey.toDataItem()
            assertEquals(privKey, MlKemPrivateKey.fromDataItem(privDataItem))
            assertEquals(privKey, PrivateKey.fromDataItem(privDataItem))
        }
    }

    @Test
    fun testX509CertWithMlKemPublicKey() = runTest {
        if (Algorithm.ML_KEM_768 !in Crypto.supportedMlKemAlgorithms) {
            return@runTest
        }
        val mlKemPrivKey = Crypto.createMlKemPrivateKey(Algorithm.ML_KEM_768)
        val mlKemPubKey = mlKemPrivKey.publicKey

        val caEcKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val caAsymmetricKey = AsymmetricKey.AnonymousExplicit(
            privateKey = caEcKey,
            algorithm = Algorithm.ES256
        )

        val now = Clock.System.now()
        val cert = X509Cert.Builder(
            publicKey = mlKemPubKey,
            signingKey = caAsymmetricKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=ML-KEM End Entity"),
            issuer = X500Name.fromName("CN=EC CA"),
            validFrom = now,
            validUntil = now + 30.days
        ).includeSubjectKeyIdentifier()
            .build()

        // Verify the extracted public key is MlKemPublicKey
        val extractedPubKey = cert.publicKey
        assertTrue(extractedPubKey is MlKemPublicKey)
        assertEquals(mlKemPubKey, extractedPubKey)

        // Verify cert signature using the CA's public key
        cert.verify(caEcKey.publicKey)
        cert.verify(caEcKey.publicKey as PublicKey)

        // Calling ecPublicKey on ML-KEM cert throws
        assertFailsWith<IllegalStateException> {
            cert.ecPublicKey
        }
    }

    @Test
    fun testSoftwareSecureAreaKemDecapsulate() = runTest {
        for (alg in listOf(Algorithm.ML_KEM_512, Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024)) {
            if (alg !in Crypto.supportedMlKemAlgorithms) {
                continue
            }
            val storage = EphemeralStorage()
            val ks = SoftwareSecureArea.create(storage)

            val alias = "testMlKemKey_${alg.name}"
            ks.createKey(
                alias,
                CreateKeySettings(algorithm = alg)
            )

            val keyInfo = ks.getKeyInfo(alias)
            assertEquals(alg, keyInfo.algorithm)
            assertTrue(keyInfo.publicKey is MlKemPublicKey)

            val kemResult = Crypto.kemEncapsulate(keyInfo.publicKey as MlKemPublicKey)
            assertEquals(32, kemResult.sharedSecret.size)

            val decapsulatedSecret = ks.kemDecapsulate(alias, kemResult.ciphertext)
            assertContentEquals(kemResult.sharedSecret.encoded, decapsulatedSecret)
            kemResult.close()
        }
    }
}
