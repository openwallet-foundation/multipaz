@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.multipaz.crypto

import org.multipaz.SwiftBridge
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.SecureEnclaveKeyUnlockData
import org.multipaz.util.UUID
import org.multipaz.util.toByteArray
import org.multipaz.util.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toNSData
import platform.Foundation.NSData
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
actual object Crypto {

    /**
     * CryptoKit supports the following curves from [EcCurve].
     *
     * TODO: CryptoKit actually supports ED25519 and X25519, add support for this too.
     */
    actual val supportedCurves: Set<EcCurve> = setOf(
        EcCurve.P256,
        EcCurve.P384,
        EcCurve.P521,
    )

    actual val supportedEncryptionAlgorithms = setOf(
        Algorithm.A128GCM,
        Algorithm.A192GCM,
        Algorithm.A256GCM,
        Algorithm.A128CBC,
        Algorithm.A192CBC,
        Algorithm.A256CBC
    )

    actual val supportedMlDsaAlgorithms = setOf(
        Algorithm.ML_DSA_65,
        Algorithm.ML_DSA_87
    )

    actual val supportedMlKemAlgorithms = setOf(
        Algorithm.ML_KEM_768,
        Algorithm.ML_KEM_1024
    )

    actual val provider: String = "CryptoKit"

    actual suspend fun digest(
        algorithm: Algorithm,
        message: ByteArray
    ): ByteArray {
        return when (algorithm) {
            Algorithm.INSECURE_SHA1 -> SwiftBridge.sha1(message.toNSData()).toByteArray()
            Algorithm.SHA256 -> SwiftBridge.sha256(message.toNSData()).toByteArray()
            Algorithm.SHA384 -> SwiftBridge.sha384(message.toNSData()).toByteArray()
            Algorithm.SHA512 -> SwiftBridge.sha512(message.toNSData()).toByteArray()
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
    }

    actual suspend fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray
    ): ByteArray {
        return when (algorithm) {
            Algorithm.HMAC_INSECURE_SHA1 -> SwiftBridge.hmacSha1(key.toNSData(), message.toNSData()).toByteArray()
            Algorithm.HMAC_SHA256 -> SwiftBridge.hmacSha256(key.toNSData(), message.toNSData()).toByteArray()
            Algorithm.HMAC_SHA384 -> SwiftBridge.hmacSha384(key.toNSData(), message.toNSData()).toByteArray()
            Algorithm.HMAC_SHA512 -> SwiftBridge.hmacSha512(key.toNSData(), message.toNSData()).toByteArray()
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
    }

    actual suspend fun encrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messagePlaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        when (algorithm) {
            Algorithm.A128GCM, Algorithm.A128CBC -> require(key.size == 16) { "Key size must be 16 bytes" }
            Algorithm.A192GCM, Algorithm.A192CBC -> require(key.size == 24) { "Key size must be 24 bytes" }
            Algorithm.A256GCM, Algorithm.A256CBC -> require(key.size == 32) { "Key size must be 32 bytes" }
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
        return when (algorithm) {
            Algorithm.A128GCM, Algorithm.A192GCM, Algorithm.A256GCM -> {
                SwiftBridge.aesGcmEncrypt(
                    key.toNSData(),
                    messagePlaintext.toNSData(),
                    nonce.toNSData(),
                    aad?.toNSData()
                ).toByteArray()
            }
            Algorithm.A128CBC, Algorithm.A192CBC, Algorithm.A256CBC -> {
                SwiftBridge.aesCbcEncrypt(
                    key.toNSData(),
                    messagePlaintext.toNSData(),
                    nonce.toNSData()
                ).toByteArray()
            }
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
    }

    actual suspend fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        return when (algorithm) {
            Algorithm.A128GCM, Algorithm.A192GCM, Algorithm.A256GCM -> {
                val ctLen = messageCiphertext.size
                val ct = messageCiphertext.sliceArray(IntRange(0, ctLen - 16 - 1))
                val tag = messageCiphertext.sliceArray(IntRange(ctLen - 16, ctLen - 1))
                SwiftBridge.aesGcmDecrypt(
                    key.toNSData(),
                    ct.toNSData(),
                    tag.toNSData(),
                    nonce.toNSData(),
                    aad?.toNSData()
                )?.toByteArray() ?: throw IllegalStateException("Decryption failed")
            }
            Algorithm.A128CBC, Algorithm.A192CBC, Algorithm.A256CBC -> {
                SwiftBridge.aesCbcDecrypt(
                    key.toNSData(),
                    messageCiphertext.toNSData(),
                    nonce.toNSData()
                )?.toByteArray() ?: throw IllegalStateException("Decryption failed")
            }
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
    }

    actual suspend fun checkSignature(
        publicKey: EcPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: EcSignature
    ) {
        val raw = when (publicKey) {
            is EcPublicKeyDoubleCoordinate -> publicKey.x + publicKey.y
            is EcPublicKeyOkp -> publicKey.x
        }
        if (!SwiftBridge.ecVerifySignature(
            publicKey.curve.coseCurveIdentifier.toLong(),
            raw.toNSData(),
            message.toNSData(),
            (signature.r + signature.s).toNSData()
        )) {
            throw SignatureVerificationException("Signature verification failed")
        }
    }

    actual suspend fun checkSignature(
        publicKey: RsaPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: RsaSignature
    ) {
        val algName = when (algorithm.joseAlgorithmIdentifier) {
            "RS256" -> "RS256"
            "RS384" -> "RS384"
            "RS512" -> "RS512"
            "PS256" -> "PS256"
            "PS384" -> "PS384"
            "PS512" -> "PS512"
            else -> throw IllegalArgumentException("Unsupported RSA algorithm $algorithm")
        }
        val verified = SwiftBridge.rsaVerifySignature(
            publicKey.toPkcs1().toNSData(),
            algName,
            message.toNSData(),
            signature.signature.toNSData()
        )
        if (!verified) {
            throw SignatureVerificationException("Signature verification failed")
        }
    }

    actual suspend fun checkSignature(
        publicKey: MlDsaPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: MlDsaSignature
    ) {
        require(algorithm == publicKey.algorithm) {
            "Signature algorithm $algorithm doesn't match key algorithm ${publicKey.algorithm}"
        }
        val algName = when (algorithm) {
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm $algorithm")
        }
        val verified = SwiftBridge.mldsaVerifySignature(
            algName,
            publicKey.encoded.toByteArray().toNSData(),
            message.toNSData(),
            signature.signature.toNSData()
        )
        if (!verified) {
            throw SignatureVerificationException("ML-DSA signature verification failed")
        }
    }

    actual suspend fun createEcPrivateKey(curve: EcCurve): EcPrivateKey {
        val ret = SwiftBridge.createEcPrivateKey(curve.coseCurveIdentifier.toLong())
        if (ret.isEmpty()) {
            throw UnsupportedOperationException("Curve is not supported")
        }
        val privKeyBytes = (ret[0] as NSData).toByteArray()
        val pubKeyBytes = (ret[1] as NSData).toByteArray()
        val x = pubKeyBytes.sliceArray(IntRange(0, pubKeyBytes.size/2 - 1))
        val y = pubKeyBytes.sliceArray(IntRange(pubKeyBytes.size/2, pubKeyBytes.size - 1))
        return EcPrivateKeyDoubleCoordinate(curve, privKeyBytes, x, y)
    }

    actual suspend fun createRsaPrivateKey(keySizeBits: Int): RsaPrivateKey {
        val ret = SwiftBridge.rsaCreatePrivateKey(keySizeBits.toLong())
        if (ret.isEmpty()) {
            throw IllegalStateException("Failed to generate RSA key")
        }
        val privKeyBytes = (ret[0] as NSData).toByteArray()
        val pubKeyBytes = (ret[1] as NSData).toByteArray()
        val pubKey = RsaPublicKey.fromPkcs1(pubKeyBytes)
        return RsaPrivateKey.fromPkcs1(privKeyBytes, pubKey)
    }

    actual suspend fun createMlDsaPrivateKey(
        algorithm: Algorithm
    ): MlDsaPrivateKey {
        val algName = when (algorithm) {
            Algorithm.ML_DSA_44 -> throw IllegalArgumentException("ML-DSA-44 is not supported on iOS")
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm $algorithm")
        }
        val ret = SwiftBridge.mldsaCreatePrivateKey(algName)
        if (ret.isEmpty()) {
            throw IllegalStateException("Failed to generate ML-DSA key (requires iOS 26+)")
        }
        val seed = (ret[0] as NSData).toByteArray()
        val pubBytes = (ret[1] as NSData).toByteArray()
        val publicKey = MlDsaPublicKey(algorithm, ByteString(pubBytes))
        return MlDsaPrivateKey(algorithm, ByteString(seed), publicKey)
    }

    actual suspend fun createMlKemPrivateKey(
        algorithm: Algorithm
    ): MlKemPrivateKey {
        val algName = when (algorithm) {
            Algorithm.ML_KEM_512 -> throw IllegalArgumentException("ML-KEM-512 is not supported on iOS")
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm $algorithm")
        }
        val ret = SwiftBridge.mlkemCreatePrivateKey(algName)
        if (ret.isEmpty()) {
            throw IllegalStateException("Failed to generate ML-KEM key (requires iOS 26+)")
        }
        val seed = (ret[0] as NSData).toByteArray()
        val pubBytes = (ret[1] as NSData).toByteArray()
        val publicKey = MlKemPublicKey(algorithm, ByteString(pubBytes))
        return MlKemPrivateKey(algorithm, ByteString(seed), publicKey)
    }

    actual suspend fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): EcSignature {
        val rawSignature = SwiftBridge.ecSign(
            key.curve.coseCurveIdentifier.toLong(),
            key.d.toNSData(),
            message.toNSData()
        )?.toByteArray() ?: throw UnsupportedOperationException("Curve is not supported")

        val r = rawSignature.sliceArray(IntRange(0, rawSignature.size/2 - 1))
        val s = rawSignature.sliceArray(IntRange(rawSignature.size/2, rawSignature.size - 1))
        return EcSignature(r, s)
    }

    actual suspend fun sign(
        key: RsaPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): RsaSignature {
        val algName = when (signatureAlgorithm.joseAlgorithmIdentifier) {
            "RS256" -> "RS256"
            "RS384" -> "RS384"
            "RS512" -> "RS512"
            "PS256" -> "PS256"
            "PS384" -> "PS384"
            "PS512" -> "PS512"
            else -> throw IllegalArgumentException("Unsupported RSA signing algorithm $signatureAlgorithm")
        }
        val signature = SwiftBridge.rsaSign(
            key.toPkcs1().toNSData(),
            algName,
            message.toNSData()
        )?.toByteArray() ?: throw IllegalStateException("RSA signing failed")
        return RsaSignature(signature)
    }

    actual suspend fun sign(
        key: MlDsaPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): MlDsaSignature {
        require(signatureAlgorithm == key.algorithm) {
            "Signature algorithm $signatureAlgorithm doesn't match key algorithm ${key.algorithm}"
        }
        val algName = when (key.algorithm) {
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm ${key.algorithm}")
        }
        val sig = SwiftBridge.mldsaSign(
            algName,
            key.encoded.toByteArray().toNSData(),
            key.publicKey.encoded.toByteArray().toNSData(),
            message.toNSData()
        )?.toByteArray() ?: throw IllegalStateException("ML-DSA signing failed (requires iOS 26+)")
        return MlDsaSignature(sig)
    }

    actual suspend fun kemEncapsulate(
        recipientPublicKey: MlKemPublicKey
    ): KemResult {
        val algName = when (recipientPublicKey.algorithm) {
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm ${recipientPublicKey.algorithm}")
        }
        val ret = SwiftBridge.mlkemEncapsulate(
            algName,
            recipientPublicKey.encoded.toByteArray().toNSData()
        ) ?: throw IllegalStateException("ML-KEM encapsulation failed (requires iOS 26+)")
        val secret = (ret[0] as NSData).toByteArray()
        val ciphertext = (ret[1] as NSData).toByteArray()
        return KemResult(sharedSecret = secret, ciphertext = ciphertext)
    }

    actual suspend fun kemDecapsulate(
        key: MlKemPrivateKey,
        ciphertext: ByteArray
    ): ByteArray {
        val algName = when (key.algorithm) {
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm ${key.algorithm}")
        }
        return SwiftBridge.mlkemDecapsulate(
            algName,
            key.encoded.toByteArray().toNSData(),
            key.publicKey.encoded.toByteArray().toNSData(),
            ciphertext.toNSData()
        )?.toByteArray() ?: throw IllegalStateException("ML-KEM decapsulation failed (requires iOS 26+)")
    }

    actual suspend fun keyAgreement(
        key: EcPrivateKey,
        otherKey: EcPublicKey
    ): ByteArray {
        require(otherKey.curve == key.curve) { "Other key for ECDH is not ${key.curve.name}" }
        val otherKeyRaw = when (otherKey) {
            is EcPublicKeyDoubleCoordinate -> otherKey.x + otherKey.y
            is EcPublicKeyOkp -> otherKey.x
        }
        return SwiftBridge.ecKeyAgreement(
            key.curve.coseCurveIdentifier.toLong(),
            key.d.toNSData(),
            otherKeyRaw.toNSData()
        )?.toByteArray() ?: throw UnsupportedOperationException("Curve is not supported")
    }

    internal fun secureEnclaveCreateEcPrivateKey(
        algorithm: Algorithm,
        accessControlCreateFlags: Long
    ): Pair<ByteArray, EcPublicKey> {
        val ret = SwiftBridge.secureEnclaveCreateEcPrivateKey(
            algorithm.isKeyAgreement,
            accessControlCreateFlags
        )
        if (ret.isEmpty()) {
            // iOS simulator doesn't support authentication
            throw IllegalStateException("Error creating EC key - on iOS simulator?")
        }
        val keyBlob = (ret[0] as NSData).toByteArray()
        val pubKeyBytes = (ret[1] as NSData).toByteArray()
        val x = pubKeyBytes.sliceArray(IntRange(0, pubKeyBytes.size/2 - 1))
        val y = pubKeyBytes.sliceArray(IntRange(pubKeyBytes.size/2, pubKeyBytes.size - 1))
        val pubKey = EcPublicKeyDoubleCoordinate(EcCurve.P256, x, y)
        return Pair(keyBlob, pubKey)
    }

    internal fun secureEnclaveEcSign(
        keyBlob: ByteArray,
        message: ByteArray,
        keyUnlockData: SecureEnclaveKeyUnlockData?
    ): EcSignature {
        val rawSignature = SwiftBridge.secureEnclaveEcSign(
            keyBlob.toNSData(),
            message.toNSData(),
            keyUnlockData?.authenticationContext as objcnames.classes.LAContext?
        )?.toByteArray() ?: throw KeyLockedException("Unable to unlock key")
        val r = rawSignature.sliceArray(IntRange(0, rawSignature.size/2 - 1))
        val s = rawSignature.sliceArray(IntRange(rawSignature.size/2, rawSignature.size - 1))
        return EcSignature(r, s)
    }

    internal fun secureEnclaveEcKeyAgreement(
        keyBlob: ByteArray,
        otherKey: EcPublicKey,
        keyUnlockData: SecureEnclaveKeyUnlockData?
    ): ByteArray {
        val otherKeyRaw = when (otherKey) {
            is EcPublicKeyDoubleCoordinate -> otherKey.x + otherKey.y
            is EcPublicKeyOkp -> otherKey.x
        }
        return SwiftBridge.secureEnclaveEcKeyAgreement(
            keyBlob.toNSData(),
            otherKeyRaw.toNSData(),
            keyUnlockData?.authenticationContext as objcnames.classes.LAContext?
        )?.toByteArray() ?: throw KeyLockedException("Unable to unlock key")
    }

    internal val secureEnclaveIsPqcSupported: Boolean
        get() = SwiftBridge.secureEnclaveIsPqcSupported()

    internal fun secureEnclaveCreateMlDsaPrivateKey(
        algorithm: Algorithm,
        accessControlCreateFlags: Long
    ): Pair<ByteArray, MlDsaPublicKey> {
        val algName = when (algorithm) {
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm $algorithm")
        }
        val ret = SwiftBridge.secureEnclaveCreateMlDsaPrivateKey(
            algName,
            accessControlCreateFlags
        )
        if (ret.isEmpty()) {
            throw IllegalStateException("Error creating ML-DSA key - on iOS simulator?")
        }
        val keyBlob = (ret[0] as NSData).toByteArray()
        val pubKeyBytes = (ret[1] as NSData).toByteArray()
        val pubKey = MlDsaPublicKey(algorithm, ByteString(pubKeyBytes))
        return Pair(keyBlob, pubKey)
    }

    internal fun secureEnclaveMlDsaSign(
        algorithm: Algorithm,
        keyBlob: ByteArray,
        message: ByteArray,
        keyUnlockData: SecureEnclaveKeyUnlockData?
    ): MlDsaSignature {
        val algName = when (algorithm) {
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm $algorithm")
        }
        val signature = SwiftBridge.secureEnclaveMlDsaSign(
            algName,
            keyBlob.toNSData(),
            message.toNSData(),
            keyUnlockData?.authenticationContext as objcnames.classes.LAContext?
        )?.toByteArray() ?: throw KeyLockedException("Unable to unlock key")
        return MlDsaSignature(signature)
    }

    internal fun secureEnclaveCreateMlKemPrivateKey(
        algorithm: Algorithm,
        accessControlCreateFlags: Long
    ): Pair<ByteArray, MlKemPublicKey> {
        val algName = when (algorithm) {
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm $algorithm")
        }
        val ret = SwiftBridge.secureEnclaveCreateMlKemPrivateKey(
            algName,
            accessControlCreateFlags
        )
        if (ret.isEmpty()) {
            throw IllegalStateException("Error creating ML-KEM key - on iOS simulator?")
        }
        val keyBlob = (ret[0] as NSData).toByteArray()
        val pubKeyBytes = (ret[1] as NSData).toByteArray()
        val pubKey = MlKemPublicKey(algorithm, ByteString(pubKeyBytes))
        return Pair(keyBlob, pubKey)
    }

    internal fun secureEnclaveMlKemDecapsulate(
        algorithm: Algorithm,
        keyBlob: ByteArray,
        ciphertext: ByteArray,
        keyUnlockData: SecureEnclaveKeyUnlockData?
    ): ByteArray {
        val algName = when (algorithm) {
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm $algorithm")
        }
        return SwiftBridge.secureEnclaveMlKemDecapsulate(
            algName,
            keyBlob.toNSData(),
            ciphertext.toNSData(),
            keyUnlockData?.authenticationContext as objcnames.classes.LAContext?
        )?.toByteArray() ?: throw KeyLockedException("Unable to unlock key")
    }

    internal actual suspend fun validateCertChainSignatures(certChain: X509CertChain): Boolean {
        val certificates = certChain.certificates
        for (i in 1..certificates.lastIndex) {
            val toVerify = certificates[i - 1]
            val err = SwiftBridge.verifySignature(
                certificates[i].encoded.toNSData(),
                toVerify.tbsCertificate.toNSData(),
                toVerify.signatureAlgorithmOid,
                toVerify.signature.toNSData()
            )
            if (err != null) {
                return false
            }
        }
        return true
    }
}
