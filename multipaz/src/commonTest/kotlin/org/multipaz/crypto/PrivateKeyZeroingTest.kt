package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateKeyZeroingTest {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testSecureZeroByteArray() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        bytes.secureZero()
        assertContentEquals(ByteArray(8), bytes)
    }

    @Test
    fun testEcPrivateKeyDoubleCoordinateZeroing() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256) as EcPrivateKeyDoubleCoordinate
        assertFalse(key.isDestroyed)

        val dReference = key.d
        assertTrue(dReference.any { it != 0.toByte() })

        val message = "Hello, world!".encodeToByteArray()
        val signature = Crypto.sign(key, Algorithm.ES256, message)
        Crypto.checkSignature(key.publicKey, message, Algorithm.ES256, signature)

        // Close key and verify zeroing
        key.close()
        assertTrue(key.isDestroyed)

        // The backing array reference must now be all zeroes
        assertTrue(dReference.all { it == 0.toByte() })

        // Subsequent property accesses and crypto operations must fail
        assertFailsWith<IllegalStateException> { key.d }
        assertFailsWith<IllegalStateException> { key.toCoseKey() }
        assertFailsWith<IllegalStateException> { key.toPem() }
        assertFailsWith<IllegalStateException> { key.toJwk() }
        assertFailsWith<IllegalStateException> {
            Crypto.sign(key, Algorithm.ES256, message)
        }
    }

    @Test
    fun testEcPrivateKeyOkpZeroing() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.ED25519)) {
            // If Ed25519 is not supported on this platform, create directly with synthetic data
            val d = ByteArray(32) { (it + 1).toByte() }
            val x = ByteArray(32) { (it + 33).toByte() }
            val key = EcPrivateKeyOkp(EcCurve.ED25519, d, x)
            val dRef = key.d
            assertFalse(key.isDestroyed)

            key.close()
            assertTrue(key.isDestroyed)
            assertTrue(dRef.all { it == 0.toByte() })
            assertFailsWith<IllegalStateException> { key.d }
            assertFailsWith<IllegalStateException> { key.toCoseKey() }
            return@runTest
        }

        val key = Crypto.createEcPrivateKey(EcCurve.ED25519) as EcPrivateKeyOkp
        assertFalse(key.isDestroyed)

        val dReference = key.d
        assertTrue(dReference.any { it != 0.toByte() })

        key.close()
        assertTrue(key.isDestroyed)
        assertTrue(dReference.all { it == 0.toByte() })

        assertFailsWith<IllegalStateException> { key.d }
        assertFailsWith<IllegalStateException> { key.toCoseKey() }
    }

    @Test
    fun testRsaPrivateKeyZeroing() = runTest {
        val key = Crypto.createRsaPrivateKey(2048)
        assertFalse(key.isDestroyed)

        val privExp = key.privateExponent
        val p = key.p
        val q = key.q
        val dp = key.dp
        val dq = key.dq
        val qInv = key.qInv

        assertTrue(privExp.any { it != 0.toByte() })
        assertTrue(p?.any { it != 0.toByte() } == true)

        key.close()
        assertTrue(key.isDestroyed)

        assertTrue(privExp.all { it == 0.toByte() })
        p?.let { assertTrue(it.all { b -> b == 0.toByte() }) }
        q?.let { assertTrue(it.all { b -> b == 0.toByte() }) }
        dp?.let { assertTrue(it.all { b -> b == 0.toByte() }) }
        dq?.let { assertTrue(it.all { b -> b == 0.toByte() }) }
        qInv?.let { assertTrue(it.all { b -> b == 0.toByte() }) }

        assertFailsWith<IllegalStateException> { key.privateExponent }
        assertFailsWith<IllegalStateException> { key.p }
        assertFailsWith<IllegalStateException> { key.toCoseKey() }
        assertFailsWith<IllegalStateException> { key.toPem() }
        assertFailsWith<IllegalStateException> { key.toPkcs1() }
        assertFailsWith<IllegalStateException> { key.toPrivateKeyInfo() }
    }

    @Test
    fun testMlDsaPrivateKeyZeroing() = runTest {
        val encodedBytes = ByteArray(32) { (it + 1).toByte() }
        val pubBytes = ByteArray(1312) { 42.toByte() }
        val pubKey = MlDsaPublicKey(Algorithm.ML_DSA_44, ByteString(pubBytes))
        val key = MlDsaPrivateKey(Algorithm.ML_DSA_44, encodedBytes, pubKey)

        assertFalse(key.isDestroyed)
        val rawRef = key.encodedKeyMaterial
        assertTrue(rawRef.any { it != 0.toByte() })

        key.close()
        assertTrue(key.isDestroyed)
        assertTrue(rawRef.all { it == 0.toByte() })

        assertFailsWith<IllegalStateException> { key.encoded }
        assertFailsWith<IllegalStateException> { key.encodedKeyMaterial }
        assertFailsWith<IllegalStateException> { key.toCoseKey() }
        assertFailsWith<IllegalStateException> { key.toPkcs8() }
    }

    @Test
    fun testMlKemPrivateKeyZeroing() = runTest {
        val encodedBytes = ByteArray(64) { (it + 1).toByte() }
        val pubBytes = ByteArray(800) { 42.toByte() }
        val pubKey = MlKemPublicKey(Algorithm.ML_KEM_512, ByteString(pubBytes))
        val key = MlKemPrivateKey(Algorithm.ML_KEM_512, encodedBytes, pubKey)

        assertFalse(key.isDestroyed)
        val rawRef = key.encodedKeyMaterial
        assertTrue(rawRef.any { it != 0.toByte() })

        key.close()
        assertTrue(key.isDestroyed)
        assertTrue(rawRef.all { it == 0.toByte() })

        assertFailsWith<IllegalStateException> { key.encoded }
        assertFailsWith<IllegalStateException> { key.encodedKeyMaterial }
        assertFailsWith<IllegalStateException> { key.toCoseKey() }
        assertFailsWith<IllegalStateException> { key.toPkcs8() }
    }

    @Test
    fun testAutoCloseableUseBlock() = runTest {
        var keyRef: EcPrivateKey? = null
        var dRef: ByteArray? = null

        Crypto.createEcPrivateKey(EcCurve.P256).use { key ->
            keyRef = key
            dRef = key.d
            assertFalse(key.isDestroyed)
            assertTrue(dRef!!.any { it != 0.toByte() })
        }

        assertTrue(keyRef!!.isDestroyed)
        assertTrue(dRef!!.all { it == 0.toByte() })
    }

    @Test
    fun testAutoCloseableUseBlockOnException() = runTest {
        var keyRef: EcPrivateKey? = null
        var dRef: ByteArray? = null

        try {
            Crypto.createEcPrivateKey(EcCurve.P256).use { key ->
                keyRef = key
                dRef = key.d
                throw IllegalArgumentException("Deliberate test failure")
            }
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        assertTrue(keyRef!!.isDestroyed)
        assertTrue(dRef!!.all { it == 0.toByte() })
    }

    @Test
    fun testToStringDoesNotLeakSecrets() = runTest {
        val ecKey = Crypto.createEcPrivateKey(EcCurve.P256) as EcPrivateKeyDoubleCoordinate
        val rsaKey = Crypto.createRsaPrivateKey(2048)
        val mlDsaKey = MlDsaPrivateKey(
            Algorithm.ML_DSA_44,
            ByteArray(32) { 0xab.toByte() },
            MlDsaPublicKey(Algorithm.ML_DSA_44, ByteString(ByteArray(1312)))
        )

        val ecString = ecKey.toString()
        val rsaString = rsaKey.toString()
        val mlDsaString = mlDsaKey.toString()

        assertFalse(ecString.contains(" d="))
        assertFalse(ecString.contains(ecKey.d.joinToString()))

        assertFalse(rsaString.contains("privateExponent"))
        assertFalse(rsaString.contains("qInv"))

        assertFalse(mlDsaString.contains("ab"))
    }
}
