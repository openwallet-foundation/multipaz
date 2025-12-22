@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.multipaz.crypto

import org.multipaz.SwiftBridge
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.SecureEnclaveKeyUnlockData
import org.multipaz.util.UUID
import org.multipaz.util.toByteArray
import org.multipaz.util.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
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

    actual val supportedEncryptionAlgorithms = setOf(Algorithm.A128GCM, Algorithm.A192GCM, Algorithm.A256GCM)

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
        return SwiftBridge.aesGcmEncrypt(
            key.toNSData(),
            messagePlaintext.toNSData(),
            nonce.toNSData(),
            aad?.toNSData()
        ).toByteArray()
    }

    actual suspend fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        val ctLen = messageCiphertext.size
        val ct = messageCiphertext.sliceArray(IntRange(0, ctLen - 16 - 1))
        val tag = messageCiphertext.sliceArray(IntRange(ctLen - 16, ctLen - 1))
        return SwiftBridge.aesGcmDecrypt(
            key.toNSData(),
            ct.toNSData(),
            tag.toNSData(),
            nonce.toNSData(),
            aad?.toNSData()
        )?.toByteArray() ?: throw IllegalStateException("Decryption failed")
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

    internal actual suspend fun validateCertChain(certChain: X509CertChain): Boolean {
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
