package org.multipaz.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkeParams
import com.google.crypto.tink.proto.HpkePrivateKey
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.proto.OutputPrefixType
import com.google.crypto.tink.shaded.protobuf.ByteString as TinkByteString
import com.google.crypto.tink.subtle.EllipticCurves
import kotlinx.coroutines.test.runTest
import org.multipaz.testUtilSetupCryptoProvider
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class HpkeTestsAgainstTink {
    @BeforeTest
    fun setup() {
        testUtilSetupCryptoProvider()
        TinkConfig.register()
        HybridConfig.register()
    }

    @Test
    fun testHpkeEncryptAgainstTink() = runTest {
        val receiver = Crypto.createEcPrivateKey(EcCurve.P256)
        val plaintext = "Hello World".encodeToByteArray()
        val aad = "".encodeToByteArray()  // Tink doesn't seem to support AAD
        val info = "abc".encodeToByteArray()

        val encrypter = Hpke.getEncrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPublicKey = receiver.publicKey,
            info = info
        )
        val cipherText = encrypter.encrypt(
            plaintext = plaintext,
            aad = aad
        )

        val decryptedText = tinkHpkeDecrypt(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = receiver,
            cipherText = cipherText,
            enc = encrypter.encapsulatedKey.toByteArray(),
            aad = aad,
            info = info,
        )

        assertContentEquals(decryptedText, plaintext)
    }

    @Test
    fun testHpkeDecryptAgainstTink() = runTest {
        val receiver = Crypto.createEcPrivateKey(EcCurve.P256)
        val plaintext = "Hello World".encodeToByteArray()
        val aad = "".encodeToByteArray()  // Tink doesn't seem to support AAD
        val info = "abc".encodeToByteArray()

        val (ciphertext, enc) = tinkHpkeEncrypt(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPublicKey = receiver.publicKey,
            plainText = plaintext,
            aad = aad,
            info = info
        )

        val decrypter = Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = AsymmetricKey.AnonymousExplicit(receiver),
            encapsulatedKey = enc,
            info = info
        )
        val decryptedtext = decrypter.decrypt(
            ciphertext = ciphertext,
            aad = aad,
        )

        assertContentEquals(decryptedtext, plaintext)
    }

    companion object {
        private fun hpkeGetKeysetHandles(
            publicKey: EcPublicKey,
            privateKey: EcPrivateKey?
        ): Pair<KeysetHandle, KeysetHandle?> {
            val primaryKeyId = 1

            val params = HpkeParams.newBuilder()
                .setAead(HpkeAead.AES_128_GCM)
                .setKdf(HpkeKdf.HKDF_SHA256)
                .setKem(HpkeKem.DHKEM_P256_HKDF_SHA256)
                .build()

            val javaPublicKey = publicKey.javaPublicKey as ECPublicKey
            val encodedKey = EllipticCurves.pointEncode(
                EllipticCurves.CurveType.NIST_P256,
                EllipticCurves.PointFormatType.UNCOMPRESSED,
                javaPublicKey.w
            )

            val hpkePublicKey = HpkePublicKey.newBuilder()
                .setVersion(0)
                .setPublicKey(TinkByteString.copyFrom(encodedKey))
                .setParams(params)
                .build()

            val publicKeyData = KeyData.newBuilder()
                .setKeyMaterialType(KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC)
                .setTypeUrl("type.googleapis.com/google.crypto.tink.HpkePublicKey")
                .setValue(hpkePublicKey.toByteString())
                .build()

            val publicKeysetKey = Keyset.Key.newBuilder()
                .setKeyId(primaryKeyId)
                .setKeyData(publicKeyData)
                .setOutputPrefixType(OutputPrefixType.RAW)
                .setStatus(KeyStatusType.ENABLED)
                .build()

            val publicKeyset = Keyset.newBuilder()
                .setPrimaryKeyId(primaryKeyId)
                .addKey(publicKeysetKey)
                .build()

            val publicKeysetHandle = TinkProtoKeysetFormat.parseKeyset(
                publicKeyset.toByteArray(),
                InsecureSecretKeyAccess.get()
            )
            var privateKeysetHandle: KeysetHandle? = null

            if (privateKey != null) {
                val javaPrivateKey = privateKey.javaPrivateKey as ECPrivateKey
                val hpkePrivateKey = HpkePrivateKey.newBuilder()
                    .setPublicKey(hpkePublicKey)
                    .setPrivateKey(TinkByteString.copyFrom(javaPrivateKey.s.toByteArray()))
                    .build()

                val privateKeyData = KeyData.newBuilder()
                    .setKeyMaterialType(KeyData.KeyMaterialType.ASYMMETRIC_PRIVATE)
                    .setTypeUrl("type.googleapis.com/google.crypto.tink.HpkePrivateKey")
                    .setValue(hpkePrivateKey.toByteString())
                    .build()

                val privateKeysetKey = Keyset.Key.newBuilder()
                    .setKeyId(primaryKeyId)
                    .setKeyData(privateKeyData)
                    .setOutputPrefixType(OutputPrefixType.RAW)
                    .setStatus(KeyStatusType.ENABLED)
                    .build()
                val privateKeyset = Keyset.newBuilder()
                    .setPrimaryKeyId(primaryKeyId)
                    .addKey(privateKeysetKey)
                    .build()
                privateKeysetHandle = TinkProtoKeysetFormat.parseKeyset(
                    privateKeyset.toByteArray(),
                    InsecureSecretKeyAccess.get()
                )
            }

            return Pair(publicKeysetHandle, privateKeysetHandle)
        }

        private fun tinkHpkeEncrypt(
            cipherSuite: Hpke.CipherSuite,
            receiverPublicKey: EcPublicKey,
            plainText: ByteArray,
            aad: ByteArray,
            info: ByteArray
        ): Pair<ByteArray, ByteArray> {
            require(cipherSuite == Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM) {
                "Only Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM is supported right now"
            }
            require(receiverPublicKey.curve == EcCurve.P256)
            require(aad.isEmpty()) {
                // TODO: Looks like there's no way to set the AAD with Tink...
                "Only supporting empty AADs right now"
            }

            val (publicKeysetHandle, _) = hpkeGetKeysetHandles(receiverPublicKey, null)

            val encryptor = publicKeysetHandle.getPrimitive(
                /* configuration = */ RegistryConfiguration.get(),
                /* targetClassObject = */ HybridEncrypt::class.java
            )
            val output = encryptor.encrypt(plainText, info)

            // Output from Tink is (serialized encapsulated key || ciphertext) so we need to break it
            // up ourselves

            receiverPublicKey as EcPublicKeyDoubleCoordinate
            val coordinateSize = (receiverPublicKey.curve.bitSize + 7)/8
            val encapsulatedPublicKeySize = 1 + 2*coordinateSize

            val enc = output.sliceArray(IntRange(0, encapsulatedPublicKeySize - 1))
            val cipherText = output.sliceArray(IntRange(encapsulatedPublicKeySize, output.size - 1))

            return Pair(cipherText, enc)
        }

        private fun tinkHpkeDecrypt(
            cipherSuite: Hpke.CipherSuite,
            receiverPrivateKey: EcPrivateKey,
            cipherText: ByteArray,
            enc: ByteArray,
            aad: ByteArray,
            info: ByteArray,
        ): ByteArray {
            require(cipherSuite == Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM) {
                "Only Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM is supported right now"
            }
            require(receiverPrivateKey.curve == EcCurve.P256)
            require(aad.isEmpty()) {
                // TODO: Looks like there's no way to set the AAD with Tink...
                "Only supporting empty AADs right now"
            }

            val (_, privateKeysetHandle) =
                hpkeGetKeysetHandles(receiverPrivateKey.publicKey, receiverPrivateKey)

            val decryptor = privateKeysetHandle!!.getPrimitive(
                /* configuration = */ RegistryConfiguration.get(),
                /* targetClassObject = */ HybridDecrypt::class.java
            )

            // Tink expects the input to be (serialized encapsulated key || ciphertext)
            return decryptor.decrypt(enc + cipherText, info)
        }
    }
}