package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Tstr
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseSign1
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.webtoken.WebTokenClaim
import org.multipaz.webtoken.WebTokenClaim.Companion.get
import org.multipaz.webtoken.WebTokenClaim.Companion.put
import org.multipaz.webtoken.buildCwt
import org.multipaz.webtoken.buildJwt
import org.multipaz.webtoken.validateCwt
import org.multipaz.webtoken.validateJwt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MlDsaTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testCreateKeyAndSignVerify() = runTest {
        val testAlgorithms = listOf(
            Triple(Algorithm.ML_DSA_44, 1312, 2420),
            Triple(Algorithm.ML_DSA_65, 1952, 3309),
            Triple(Algorithm.ML_DSA_87, 2592, 4627),
        )

        for ((alg, expectedPubSize, expectedSigSize) in testAlgorithms) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val privKey = Crypto.createMlDsaPrivateKey(alg)
            val pubKey = privKey.publicKey

            assertEquals(alg, pubKey.algorithm)
            assertEquals(alg, privKey.algorithm)
            assertEquals(expectedPubSize, pubKey.encoded.size)
            assertTrue(privKey.encoded.size > 0)

            val message = "Hello ML-DSA World with algorithm $alg!".encodeToByteArray()

            val signature = Crypto.sign(privKey, alg, message)
            assertEquals(expectedSigSize, signature.signature.size)

            // Verify with MlDsaPublicKey
            Crypto.checkSignature(pubKey, message, alg, signature)

            // Verify with superclass PublicKey and Signature
            val genericPubKey: PublicKey = pubKey
            assertTrue(genericPubKey is MlDsaPublicKey)
            val genericSignature: Signature = signature
            assertTrue(genericSignature is MlDsaSignature)
            Crypto.checkSignature(genericPubKey, message, alg, genericSignature)

            // Negative test 1: tampered message
            val tamperedMessage = "Tampered message!".encodeToByteArray()
            assertFailsWith<SignatureVerificationException> {
                Crypto.checkSignature(pubKey, tamperedMessage, alg, signature)
            }

            // Negative test 2: tampered signature
            val tamperedSigBytes = signature.signature.copyOf()
            tamperedSigBytes[tamperedSigBytes.size - 1] =
                (tamperedSigBytes[tamperedSigBytes.size - 1].toInt() xor 0x01).toByte()
            assertFailsWith<SignatureVerificationException> {
                Crypto.checkSignature(pubKey, message, alg, MlDsaSignature(tamperedSigBytes))
            }

            // Negative test 3: signature from another key
            val otherPrivKey = Crypto.createMlDsaPrivateKey(alg)
            val otherSignature = Crypto.sign(otherPrivKey, alg, message)
            assertFailsWith<SignatureVerificationException> {
                Crypto.checkSignature(pubKey, message, alg, otherSignature)
            }
        }
    }

    @Test
    fun testSerializationRoundtrips() = runTest {
        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val privKey = Crypto.createMlDsaPrivateKey(alg)
            val pubKey = privKey.publicKey

            // SubjectPublicKeyInfo roundtrip
            val spkiBytes = pubKey.toSubjectPublicKeyInfo()
            val pubFromSpki = MlDsaPublicKey.fromSubjectPublicKeyInfo(spkiBytes)
            assertEquals(pubKey, pubFromSpki)

            // PrivateKeyInfo (PKCS#8) roundtrip
            val pkcs8Bytes = privKey.toPrivateKeyInfo()
            val privFromPkcs8 = MlDsaPrivateKey.fromPrivateKeyInfo(pkcs8Bytes, pubKey)
            assertEquals(privKey, privFromPkcs8)

            // PEM roundtrip (MlDsaPublicKey and PublicKey companion)
            val pubPem = pubKey.toPem()
            assertTrue(pubPem.startsWith("-----BEGIN PUBLIC KEY-----"))
            val pubFromPem = MlDsaPublicKey.fromPem(pubPem)
            assertEquals(pubKey, pubFromPem)
            val pubFromGenericPem = PublicKey.fromPem(pubPem)
            assertEquals(pubKey, pubFromGenericPem)

            // PEM roundtrip (MlDsaPrivateKey and PrivateKey companion)
            val privPem = privKey.toPem()
            assertTrue(privPem.startsWith("-----BEGIN PRIVATE KEY-----"))
            val privFromPem = MlDsaPrivateKey.fromPem(privPem, pubKey)
            assertEquals(privKey, privFromPem)
            val privFromGenericPem = PrivateKey.fromPem(privPem, pubKey)
            assertEquals(privKey, privFromGenericPem)

            // JWK roundtrip (MlDsaPublicKey and PublicKey companion)
            val pubJwk = pubKey.toJwk()
            val pubFromJwk = MlDsaPublicKey.fromJwk(pubJwk)
            assertEquals(pubKey, pubFromJwk)
            val pubFromGenericJwk = PublicKey.fromJwk(pubJwk)
            assertEquals(pubKey, pubFromGenericJwk)

            // JWK roundtrip (MlDsaPrivateKey and PrivateKey companion)
            val privJwk = privKey.toJwk()
            val privFromJwk = MlDsaPrivateKey.fromJwk(privJwk)
            assertEquals(privKey, privFromJwk)
            val privFromGenericJwk = PrivateKey.fromJwk(privJwk)
            assertEquals(privKey, privFromGenericJwk)

            // COSE Key roundtrip (MlDsaPublicKey and PublicKey companion)
            val pubCoseKey = pubKey.toCoseKey()
            val pubFromCose = MlDsaPublicKey.fromCoseKey(pubCoseKey)
            assertEquals(pubKey, pubFromCose)
            val pubFromGenericCose = PublicKey.fromCoseKey(pubCoseKey)
            assertEquals(pubKey, pubFromGenericCose)
            assertEquals(pubKey, pubCoseKey.mlDsaPublicKey)
            assertEquals(pubKey, pubCoseKey.publicKey)

            // COSE Key roundtrip (MlDsaPrivateKey and PrivateKey companion)
            val privCoseKey = privKey.toCoseKey()
            val privFromCose = MlDsaPrivateKey.fromCoseKey(privCoseKey)
            assertEquals(privKey, privFromCose)
            val privFromGenericCose = PrivateKey.fromCoseKey(privCoseKey)
            assertEquals(privKey, privFromGenericCose)
            assertEquals(privKey, privCoseKey.mlDsaPrivateKey)
            assertEquals(privKey, privCoseKey.privateKey)

            // DataItem roundtrip
            val pubDataItem = pubKey.toDataItem()
            assertEquals(pubKey, MlDsaPublicKey.fromDataItem(pubDataItem))
            assertEquals(pubKey, PublicKey.fromDataItem(pubDataItem))

            val privDataItem = privKey.toDataItem()
            assertEquals(privKey, MlDsaPrivateKey.fromDataItem(privDataItem))
            assertEquals(privKey, PrivateKey.fromDataItem(privDataItem))
        }
    }

    @Test
    fun testCoseSign1() = runTest {
        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val privKey = Crypto.createMlDsaPrivateKey(alg)
            val pubKey = privKey.publicKey
            val payload = "COSE payload with ML-DSA $alg".encodeToByteArray()
            val signingKey = AsymmetricKey.AnonymousExplicit(privKey, alg)

            val coseSign1 = Cose.coseSign1Sign(
                signingKey = signingKey,
                message = payload,
                includeMessageInPayload = true
            )

            // Check signature passes
            Cose.coseSign1Check(
                publicKey = pubKey,
                detachedData = null,
                signature = coseSign1,
                signatureAlgorithm = alg
            )

            // Negative test: tampered payload
            val tamperedCoseSign1 = CoseSign1(
                protectedHeaders = coseSign1.protectedHeaders,
                unprotectedHeaders = coseSign1.unprotectedHeaders,
                signature = coseSign1.signature,
                payload = "Tampered payload".encodeToByteArray()
            )
            assertFailsWith<SignatureVerificationException> {
                Cose.coseSign1Check(
                    publicKey = pubKey,
                    detachedData = null,
                    signature = tamperedCoseSign1,
                    signatureAlgorithm = alg
                )
            }
        }
    }

    @Test
    fun testJwt() = runTest {
        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val privKey = Crypto.createMlDsaPrivateKey(alg)
            val pubKey = privKey.publicKey
            val signingKey = AsymmetricKey.AnonymousExplicit(privKey, alg)

            val jwt = buildJwt(
                type = "JWT",
                key = signingKey
            ) {
                put("sub", "user123")
                put("iss", "https://example.com")
            }

            val validated = validateJwt(
                jwt = jwt,
                jwtName = "test_jwt",
                publicKey = pubKey
            )
            assertEquals("user123", (validated["sub"] as JsonPrimitive).content)
            assertEquals("https://example.com", (validated["iss"] as JsonPrimitive).content)

            // Negative test: validate with another key
            val otherPrivKey = Crypto.createMlDsaPrivateKey(alg)
            assertFailsWith<IllegalArgumentException> {
                validateJwt(
                    jwt = jwt,
                    jwtName = "test_jwt",
                    publicKey = otherPrivKey.publicKey
                )
            }
        }
    }

    @Test
    fun testCwt() = runTest {
        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val privKey = Crypto.createMlDsaPrivateKey(alg)
            val pubKey = privKey.publicKey
            val signingKey = AsymmetricKey.AnonymousExplicit(privKey, alg)

            val cwt = buildCwt(
                type = "CWT",
                key = signingKey
            ) {
                put(WebTokenClaim.Sub, "user123")
                put(WebTokenClaim.Iss, "https://example.com")
            }

            val validated = validateCwt(
                cwt = cwt,
                cwtName = "test_cwt",
                publicKey = pubKey
            )
            assertEquals("user123", validated[WebTokenClaim.Sub])
            assertEquals("https://example.com", validated[WebTokenClaim.Iss])

            // Negative test: validate with another key
            val otherPrivKey = Crypto.createMlDsaPrivateKey(alg)
            assertFailsWith<IllegalArgumentException> {
                validateCwt(
                    cwt = cwt,
                    cwtName = "test_cwt",
                    publicKey = otherPrivKey.publicKey
                )
            }
        }
    }

    @Test
    fun testX509CertWithMlDsaPublicKey() = runTest {
        if (Algorithm.ML_DSA_65 !in Crypto.supportedMlDsaAlgorithms) {
            return@runTest
        }
        val mlDsaPrivKey = Crypto.createMlDsaPrivateKey(Algorithm.ML_DSA_65)
        val mlDsaPubKey = mlDsaPrivKey.publicKey

        val caEcKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val caAsymmetricKey = AsymmetricKey.AnonymousExplicit(
            privateKey = caEcKey,
            algorithm = Algorithm.ES256
        )

        val now = Clock.System.now()
        val cert = X509Cert.Builder(
            publicKey = mlDsaPubKey,
            signingKey = caAsymmetricKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=ML-DSA End Entity"),
            issuer = X500Name.fromName("CN=EC CA"),
            validFrom = now,
            validUntil = now + 30.days
        ).includeSubjectKeyIdentifier()
            .build()

        // Verify the extracted public key is MlDsaPublicKey
        val extractedPubKey = cert.publicKey
        assertTrue(extractedPubKey is MlDsaPublicKey)
        assertEquals(mlDsaPubKey, extractedPubKey)

        // Verify cert signature using the CA's public key
        cert.verify(caEcKey.publicKey)
        cert.verify(caEcKey.publicKey as PublicKey)

        // Calling ecPublicKey on ML-DSA cert throws
        assertFailsWith<IllegalStateException> {
            cert.ecPublicKey
        }
    }

    @Test
    fun testAsymmetricKeyMlDsaEphemeral() = runTest {
        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val key = AsymmetricKey.ephemeral(alg)
            assertEquals(alg, key.algorithm)
            assertTrue(key.publicKey is MlDsaPublicKey)
            assertEquals(key.publicKey, key.mlDsaPublicKey)
            assertFailsWith<IllegalStateException> { key.ecPublicKey }
            assertFailsWith<IllegalStateException> { key.rsaPublicKey }
            assertFailsWith<IllegalStateException> { key.signEc("test".encodeToByteArray()) }
            assertFailsWith<IllegalStateException> { key.signRsa("test".encodeToByteArray()) }
            assertFailsWith<UnsupportedOperationException> {
                key.keyAgreement(Crypto.createEcPrivateKey(EcCurve.P256).publicKey)
            }

            // Test signing
            val message = "Hello AsymmetricKey ML-DSA".encodeToByteArray()
            val signature = key.sign(message)
            assertTrue(signature is MlDsaSignature)
            Crypto.checkSignature(key.mlDsaPublicKey, message, alg, signature)
            // Also test signMlDsa convenience method
            val mlDsaSignature = key.signMlDsa(message)
            Crypto.checkSignature(key.mlDsaPublicKey, message, alg, mlDsaSignature)
        }
    }

    @Test
    fun testSoftwareSecureAreaMlDsaSigning() = runTest {
        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val storage = EphemeralStorage()
            val ks = SoftwareSecureArea.create(storage)

            val alias = "testMlDsaKey_${alg.name}"
            ks.createKey(
                alias,
                CreateKeySettings(algorithm = alg)
            )

            val keyInfo = ks.getKeyInfo(alias)
            assertEquals(alg, keyInfo.algorithm)
            assertTrue(keyInfo.publicKey is MlDsaPublicKey)

            val dataToSign = "Data to sign with ML-DSA $alg".encodeToByteArray()
            val signature = ks.sign(alias, dataToSign)
            assertTrue(signature is MlDsaSignature)

            Crypto.checkSignature(keyInfo.publicKey, dataToSign, alg, signature)
        }
    }
}
