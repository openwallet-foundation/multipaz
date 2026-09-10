@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.multipaz.crypto

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
     * The encryption algorithms supported by the platform.
     *
     * This is a subset of [Algorithm.A128GCM], [Algorithm.A192GCM], [Algorithm.A256GCM].
     */
    val supportedEncryptionAlgorithms: Set<Algorithm>

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
    suspend fun digest(
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
    suspend fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray
    ): ByteArray

    /**
     * Message encryption.
     *
     * @param algorithm must be one of [Algorithm.A128GCM], [Algorithm.A192GCM], [Algorithm.A256GCM],
     *   [Algorithm.A128CBC], [Algorithm.A192CBC], [Algorithm.A256CBC].
     * @param key the encryption key.
     * @param nonce the nonce/IV.
     * @param messagePlaintext the message to encrypt.
     * @param aad additional authenticated data or `null` (not used for CBC mode).
     * @return the cipher text with the tag appended for GCM, or padded for CBC.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     */
    suspend fun encrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messagePlaintext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray

    /**
     * Message decryption.
     *
     * @param algorithm must be one of [Algorithm.A128GCM], [Algorithm.A192GCM], [Algorithm.A256GCM],
     *   [Algorithm.A128CBC], [Algorithm.A192CBC], [Algorithm.A256CBC].
     * @param key the encryption key.
     * @param nonce the nonce/IV.
     * @param messageCiphertext the message to decrypt with the tag at the end (for GCM) or padded (for CBC).
     * @param aad additional authenticated data or `null` (not used for CBC mode).
     * @return the plaintext.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     * @throws IllegalStateException if decryption fails
     */
    suspend fun decrypt(
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
     * @throws IllegalArgumentException if an error occurred during the check, for example if data is malformed.
     */
    suspend fun checkSignature(
        publicKey: EcPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: EcSignature
    )

    /**
     * Checks signature validity for an RSA key.
     *
     * @param publicKey the public key the signature was made with.
     * @param message the data that was signed.
     * @param algorithm the signature algorithm to use.
     * @param signature the signature.
     * @throws SignatureVerificationException if the signature check fails.
     * @throws IllegalArgumentException if an error occurred during the check, for example if data is malformed.
     */
    suspend fun checkSignature(
        publicKey: RsaPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: RsaSignature
    )

    /**
     * Creates an EC private key.
     *
     * @param curve the curve to use.
     */
    suspend fun createEcPrivateKey(curve: EcCurve): EcPrivateKey

    /**
     * Creates an RSA private key.
     *
     * @param keySizeBits the key size in bits (typically 2048, 3072, or 4096).
     * @return the newly created private key.
     */
    suspend fun createRsaPrivateKey(
        keySizeBits: Int = 2048
    ): RsaPrivateKey

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
    suspend fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): EcSignature

    /**
     * Signs data with an RSA key.
     *
     * @param key the key to sign with.
     * @param signatureAlgorithm the signature algorithm to use.
     * @param message the data to sign.
     * @return the signature.
     */
    suspend fun sign(
        key: RsaPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): RsaSignature

    /**
     * Performs Key Agreement.
     *
     * @param key the key to use for key agreement.
     * @param otherKey the key from the other party.
     * @return the shared secret.
     */
    suspend fun keyAgreement(
        key: EcPrivateKey,
        otherKey: EcPublicKey
    ): ByteArray

    /**
     * Validate that each certificate in the chain is signed by the next one.
     *
     * Note: some certificates may use RSA keys.
     *
     * TODO: replace with non-platform specific code
     */
    internal suspend fun validateCertChainSignatures(certChain: X509CertChain): Boolean
}


/**
 * Signs data with a private key.
 *
 * @param key the private key to sign with.
 * @param signatureAlgorithm the signature algorithm to use.
 * @param message the data to sign.
 * @return the signature.
 */
suspend fun Crypto.sign(
    key: PrivateKey,
    signatureAlgorithm: Algorithm,
    message: ByteArray
): Signature = when (key) {
    is EcPrivateKey -> sign(key, signatureAlgorithm, message)
    is RsaPrivateKey -> sign(key, signatureAlgorithm, message)
}

/**
 * Checks signature validity for a generic [PublicKey] and [Signature].
 *
 * @param publicKey the public key the signature was made with.
 * @param message the data that was signed.
 * @param algorithm the signature algorithm to use.
 * @param signature the signature.
 * @throws SignatureVerificationException if the signature check fails.
 * @throws IllegalArgumentException if an error occurred during the check, for example if data is malformed.
 */
suspend fun Crypto.checkSignature(
    publicKey: PublicKey,
    message: ByteArray,
    algorithm: Algorithm,
    signature: Signature
) {
    when (publicKey) {
        is EcPublicKey -> checkSignature(publicKey, message, algorithm, signature as EcSignature)
        is RsaPublicKey -> checkSignature(publicKey, message, algorithm, signature as RsaSignature)
    }
}