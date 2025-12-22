package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.testUtilSetupCryptoProvider
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CryptoTestsJvm {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    // Check that our Multiplatform implementation of PEM encoding agrees with the JVM one.
    // Roundtripping is checked in commonMain CryptoTests so no need to check decoding here.
    suspend fun testPem(curve: EcCurve) {
        val key = Crypto.createEcPrivateKey(curve)
        assertEquals(
            ecPublicKeyToPem(key.publicKey),
            key.publicKey.toPem()
        )
        assertEquals(
            ecPrivateKeyToPem(key),
            key.toPem()
        )
    }

    @Test fun testPem_P256() = runTest { testPem(EcCurve.P256) }
    @Test fun testPem_P384() = runTest { testPem(EcCurve.P384) }
    @Test fun testPem_P521() = runTest { testPem(EcCurve.P521) }
    @Test fun testPem_BRAINPOOLP256R1() = runTest { testPem(EcCurve.BRAINPOOLP256R1) }
    @Test fun testPem_BRAINPOOLP320R1() = runTest { testPem(EcCurve.BRAINPOOLP320R1) }
    @Test fun testPem_BRAINPOOLP384R1() = runTest { testPem(EcCurve.BRAINPOOLP384R1) }
    @Test fun testPem_BRAINPOOLP512R1() = runTest { testPem(EcCurve.BRAINPOOLP512R1) }
    @Test fun testPem_ED25519() = runTest { testPem(EcCurve.ED25519) }
    @Test fun testPem_X25519() = runTest { testPem(EcCurve.X25519) }
    @Test fun testPem_ED448() = runTest { testPem(EcCurve.ED448) }
    @Test fun testPem_X448() = runTest { testPem(EcCurve.X448) }


    @OptIn(ExperimentalEncodingApi::class)
    private fun ecPublicKeyToPem(publicKey: EcPublicKey): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN PUBLIC KEY-----\n")
        sb.append(Base64.Mime.encode(publicKey.javaPublicKey.encoded))
        sb.append("\n-----END PUBLIC KEY-----\n")
        return sb.toString()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ecPublicKeyFromPem(
        pemEncoding: String,
        curve: EcCurve
    ): EcPublicKey {
        val encoded = Base64.Mime.decode(pemEncoding
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .trim())
        val kf = KeyFactory.getInstance(curve.javaKeyAlgorithm)
        val spec = X509EncodedKeySpec(encoded)
        val publicKeyJava = kf.generatePublic(spec)
        return publicKeyJava.toEcPublicKey(curve)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ecPrivateKeyToPem(privateKey: EcPrivateKey): String  {
        val sb = StringBuilder()
        sb.append("-----BEGIN PRIVATE KEY-----\n")
        sb.append(Base64.Mime.encode(privateKey.javaPrivateKey.encoded))
        sb.append("\n-----END PRIVATE KEY-----\n")
        return sb.toString()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ecPrivateKeyFromPem(
        pemEncoding: String,
        publicKey: EcPublicKey
    ): EcPrivateKey {
        val encoded = Base64.Mime.decode(pemEncoding
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .trim())
        val kf = KeyFactory.getInstance(publicKey.curve.javaKeyAlgorithm)
        val spec = PKCS8EncodedKeySpec(encoded)
        val privateKeyJava = kf.generatePrivate(spec)
        return privateKeyJava.toEcPrivateKey(publicKey.javaPublicKey, publicKey.curve)
    }
}

private val EcCurve.javaKeyAlgorithm: String
    get() = when(this) {
        EcCurve.ED448, EcCurve.ED25519 -> "EdDSA"
        EcCurve.X25519, EcCurve.X448 -> "XDH"
        else -> "EC"
    }
