package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EcPrivateKeyTestsJvm {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    suspend fun conversion(curve: EcCurve) {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return
        }
        val key = Crypto.createEcPrivateKey(curve)
        val javaPrivateKey = key.javaPrivateKey
        val keyFromJava = javaPrivateKey.toEcPrivateKey(key.publicKey.javaPublicKey, curve)
        assertEquals(key, keyFromJava)
    }

    @Test fun conversion_P256() = runTest { conversion(EcCurve.P256) }
    @Test fun conversion_P384() = runTest { conversion(EcCurve.P384) }
    @Test fun conversion_P521() = runTest { conversion(EcCurve.P521) }
    @Test fun conversion_BRAINPOOLP256R1() = runTest { conversion(EcCurve.BRAINPOOLP256R1) }
    @Test fun conversion_BRAINPOOLP320R1() = runTest { conversion(EcCurve.BRAINPOOLP320R1) }
    @Test fun conversion_BRAINPOOLP384R1() = runTest { conversion(EcCurve.BRAINPOOLP384R1) }
    @Test fun conversion_BRAINPOOLP512R1() = runTest { conversion(EcCurve.BRAINPOOLP512R1) }
    @Test fun conversion_ED25519() = runTest { conversion(EcCurve.ED25519) }
    @Test fun conversion_X25519() = runTest { conversion(EcCurve.X25519) }
    @Test fun conversion_ED448() = runTest { conversion(EcCurve.ED448) }
    @Test fun conversion_X448() = runTest { conversion(EcCurve.X448) }

}