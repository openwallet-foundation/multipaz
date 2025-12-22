package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EcPrivateKeyTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    fun createAndCheck(curve: EcCurve) = runTest {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return@runTest
        }

        val privateKey = Crypto.createEcPrivateKey(curve)

        val (signatureAlgorithm, keyAgreement) = when (curve) {
            EcCurve.P256 -> Pair(Algorithm.ES256, true)
            EcCurve.P384 -> Pair(Algorithm.ES384, true)
            EcCurve.P521 -> Pair(Algorithm.ES512, true)
            EcCurve.BRAINPOOLP256R1 -> Pair(Algorithm.ES256, true)
            EcCurve.BRAINPOOLP320R1 -> Pair(Algorithm.ES256, true)
            EcCurve.BRAINPOOLP384R1 -> Pair(Algorithm.ES384, true)
            EcCurve.BRAINPOOLP512R1 -> Pair(Algorithm.ES512, true)
            EcCurve.ED25519 -> Pair(Algorithm.EDDSA, false)
            EcCurve.X25519 -> Pair(Algorithm.UNSET, true)
            EcCurve.ED448 -> Pair(Algorithm.EDDSA, false)
            EcCurve.X448 -> Pair(Algorithm.UNSET, true)
        }

        // Check that serialization works
        assertEquals(privateKey.toCoseKey().ecPrivateKey, privateKey)

        if (signatureAlgorithm != Algorithm.UNSET) {
            val message = "Hello World".encodeToByteArray()
            val signature = Crypto.sign(privateKey, signatureAlgorithm, message)
            Crypto.checkSignature(
                privateKey.publicKey,
                message,
                signatureAlgorithm,
                signature
            )

            // Negative test: check that checkSignature() throws SecurityException
            val otherPrivateKey = Crypto.createEcPrivateKey(curve)
            val signatureFromOtherKey = Crypto.sign(otherPrivateKey, signatureAlgorithm, message)
            assertFailsWith(SignatureVerificationException::class) {
                Crypto.checkSignature(
                    privateKey.publicKey,
                    message,
                    signatureAlgorithm,
                    signatureFromOtherKey
                )
            }
        }

        if (keyAgreement) {
            val otherKey = Crypto.createEcPrivateKey(curve)
            val Zab = Crypto.keyAgreement(privateKey, otherKey.publicKey)
            // TODO: add test-cases with vectors to check correctness. This just checks
            //  that no exception is thrown and the operation completes, not that the
            //  Zab value is correct.
        }
    }

    @Test fun createAndCheck_P256() = createAndCheck(EcCurve.P256)
    @Test fun createAndCheck_P384() = createAndCheck(EcCurve.P384)
    @Test fun createAndCheck_P521() = createAndCheck(EcCurve.P521)
    @Test fun createAndCheck_BRAINPOOLP256R1() = createAndCheck(EcCurve.BRAINPOOLP256R1)
    @Test fun createAndCheck_BRAINPOOLP320R1() = createAndCheck(EcCurve.BRAINPOOLP320R1)
    @Test fun createAndCheck_BRAINPOOLP384R1() = createAndCheck(EcCurve.BRAINPOOLP384R1)
    @Test fun createAndCheck_BRAINPOOLP512R1() = createAndCheck(EcCurve.BRAINPOOLP512R1)
    @Test fun createAndCheck_ED25519() = createAndCheck(EcCurve.ED25519)
    @Test fun createAndCheck_X25519() = createAndCheck(EcCurve.X25519)
    @Test fun createAndCheck_ED448() = createAndCheck(EcCurve.ED448)
    @Test fun createAndCheck_X448() = createAndCheck(EcCurve.X448)
}