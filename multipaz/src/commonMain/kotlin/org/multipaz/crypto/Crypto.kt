@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.multipaz.crypto

import org.multipaz.util.UUID

/**
 * Cryptographic support routines.
 *
 * This object contains various cryptographic primitives and is a wrapper to a platform-
 * specific crypto library.
 */
expect object Crypto {

    /**
     * The Elliptic Curve Cryptography curves supported by the platform.
     */
    val supportedCurves: Set<EcCurve>

    /**
     * A human-readable description of the underlying library used.
     */
    val provider: String

    /**
     * Message digest function.
     *
     * @param algorithm must one of [Algorithm.SHA256], [Algorithm.SHA384], [Algorithm.SHA512].
     * @param message the message to get a digest of.
     * @return the digest.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     */
    fun digest(
        algorithm: Algorithm,
        message: ByteArray
    ): ByteArray

    /**
     * Message authentication code function.
     *
     * @param algorithm must be one of [Algorithm.HMAC_SHA256], [Algorithm.HMAC_SHA384],
     * [Algorithm.HMAC_SHA512].
     * @param key the secret key.
     * @param message the message to authenticate.
     * @return the message authentication code.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     */
    fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray
    ): ByteArray

    /**
     * Message encryption.
     *
     * @param algorithm must be one of [Algorithm.A128GCM], [Algorithm.A192GCM], or [Algorithm.A256GCM].
     * @param key the encryption key.
     * @param nonce the nonce/IV.
     * @param messagePlaintext the message to encrypt.
     * @param aad additional authenticated data or `null`.
     * @return the cipher text with the tag appended to it.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     */
    fun encrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messagePlaintext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray

    /**
     * Message decryption.
     *
     * @param algorithm must be one of [Algorithm.A128GCM], [Algorithm.A192GCM], or [Algorithm.A256GCM].
     * @param key the encryption key.
     * @param nonce the nonce/IV.
     * @param messageCiphertext the message to decrypt with the tag at the end.
     * @param aad additional authenticated data or `null`.
     * @return the plaintext.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     * @throws IllegalStateException if decryption fails
     */
    fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray

    /**
     * Checks signature validity.
     *
     * @param publicKey the public key the signature was made with.
     * @param message the data that was signed.
     * @param algorithm the signature algorithm to use.
     * @param signature the signature.
     * @throws SignatureVerificationException if the signature check fails.
     * @throws IllegalStateException if an error occurred during the check, for example if data is malformed.
     */
    fun checkSignature(
        publicKey: EcPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: EcSignature
    )

    /**
     * Creates an EC private key.
     *
     * @param curve the curve to use.
     */
    fun createEcPrivateKey(curve: EcCurve): EcPrivateKey

    /**
     * Signs data with a key.
     *
     * The signature is DER encoded except for curve Ed25519 and Ed448 where it's just
     * the raw R and S values.
     *
     * @param key the key to sign with.
     * @param signatureAlgorithm the signature algorithm to use.
     * @param message the data to sign.
     * @return the signature.
     */
    fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): EcSignature

    /**
     * Performs Key Agreement.
     *
     * @param key the key to use for key agreement.
     * @param otherKey the key from the other party.
     * @return the shared secret.
     */
    fun keyAgreement(
        key: EcPrivateKey,
        otherKey: EcPublicKey
    ): ByteArray

    internal fun ecPublicKeyToPem(publicKey: EcPublicKey): String

    internal fun ecPublicKeyFromPem(pemEncoding: String, curve: EcCurve): EcPublicKey

    internal fun ecPrivateKeyToPem(privateKey: EcPrivateKey): String

    internal fun ecPrivateKeyFromPem(pemEncoding: String, publicKey: EcPublicKey): EcPrivateKey

    internal fun uuidGetRandom(): UUID

    // TODO: replace with non-platform specific code
    internal fun validateCertChain(certChain: X509CertChain): Boolean
}