package org.multipaz.crypto

import org.multipaz.util.UUID

actual object Crypto {
    actual val supportedCurves: Set<EcCurve>
        get() = TODO("Not yet implemented")
    actual val provider: String
        get() = TODO("Not yet implemented")

    actual fun digest(
        algorithm: Algorithm,
        message: ByteArray
    ): ByteArray {
        TODO("Not yet implemented")
    }

    actual fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray
    ): ByteArray {
        TODO("Not yet implemented")
    }

    actual fun encrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messagePlaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        TODO("Not yet implemented")
    }

    actual fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        TODO("Not yet implemented")
    }

    actual fun checkSignature(
        publicKey: EcPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: EcSignature
    ) {
    }

    actual fun createEcPrivateKey(curve: EcCurve): EcPrivateKey {
        TODO("Not yet implemented")
    }

    actual fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): EcSignature {
        TODO("Not yet implemented")
    }

    actual fun keyAgreement(
        key: EcPrivateKey,
        otherKey: EcPublicKey
    ): ByteArray {
        TODO("Not yet implemented")
    }

    internal actual fun ecPublicKeyToPem(publicKey: EcPublicKey): String {
        TODO("Not yet implemented")
    }

    internal actual fun ecPublicKeyFromPem(
        pemEncoding: String,
        curve: EcCurve
    ): EcPublicKey {
        TODO("Not yet implemented")
    }

    internal actual fun ecPrivateKeyToPem(privateKey: EcPrivateKey): String {
        TODO("Not yet implemented")
    }

    internal actual fun ecPrivateKeyFromPem(
        pemEncoding: String,
        publicKey: EcPublicKey
    ): EcPrivateKey {
        TODO("Not yet implemented")
    }

    internal actual fun uuidGetRandom(): UUID {
        TODO("Not yet implemented")
    }

    internal actual fun validateCertChain(certChain: X509CertChain): Boolean {
        TODO("Not yet implemented")
    }
}