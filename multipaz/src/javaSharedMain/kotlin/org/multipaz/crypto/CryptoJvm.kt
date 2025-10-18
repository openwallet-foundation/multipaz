package org.multipaz.crypto

import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.util.UUID
import org.multipaz.util.fromJavaUuid
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Cryptographic support routines.
 *
 * This object contains various cryptographic primitives and is a wrapper to a platform-
 * specific crypto library.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object Crypto {

    actual val supportedCurves: Set<EcCurve>
        get() {
            // TODO: we could probably come up with a better heuristic for which curves are supported
            //   but this works fine for now.
            val jcaProviders = Security.getProviders()
            return if (jcaProviders.size > 1 && jcaProviders.first().name == "BC") {
                setOf(
                    EcCurve.P256,
                    EcCurve.P384,
                    EcCurve.P521,
                    EcCurve.BRAINPOOLP256R1,
                    EcCurve.BRAINPOOLP320R1,
                    EcCurve.BRAINPOOLP384R1,
                    EcCurve.BRAINPOOLP512R1,
                    EcCurve.ED25519,
                    EcCurve.X25519,
                    EcCurve.ED448,
                    EcCurve.X448,
                )
            } else {
                setOf(
                    EcCurve.P256,
                    EcCurve.P384,
                    EcCurve.P521,
                    EcCurve.ED25519,
                    EcCurve.X25519,
                    EcCurve.ED448,
                    EcCurve.X448,
                )
            }
        }

    actual val provider: String
        get() {
            val sb = StringBuilder("JCA: ")
            val providers = Security.getProviders()
            for (n in providers.indices) {
                if (n > 0) {
                    sb.append("; ")
                }
                sb.append(providers[n].name)
            }
            return sb.toString()
        }

    init {
    }

    /**
     * Message digest function.
     *
     * @param algorithm must one of [Algorithm.INSECURE_SHA1], [Algorithm.SHA256], [Algorithm.SHA384], [Algorithm.SHA512].
     * @param message the message to get a digest of.
     * @return the digest.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     */
    actual fun digest(
        algorithm: Algorithm,
        message: ByteArray
    ): ByteArray {
        val algName = when (algorithm) {
            Algorithm.INSECURE_SHA1 -> "SHA-1"
            Algorithm.SHA256 -> "SHA-256"
            Algorithm.SHA384 -> "SHA-384"
            Algorithm.SHA512 -> "SHA-512"
            else -> {
                throw IllegalArgumentException("Unsupported algorithm $algorithm")
            }
        }
        return MessageDigest.getInstance(algName).digest(message)
    }

    /**
     * Message authentication code function.
     *
     * @param algorithm must be one of [Algorithm.HMAC_INSECURE_SHA1], [Algorithm.HMAC_SHA256],
     *   [Algorithm.HMAC_SHA384], [Algorithm.HMAC_SHA512].
     * @param key the secret key.
     * @param message the message to authenticate.
     * @return the message authentication code.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     */
    actual fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray
    ): ByteArray {
        val algName = when (algorithm) {
            Algorithm.HMAC_INSECURE_SHA1 -> "HmacSha1"
            Algorithm.HMAC_SHA256 -> "HmacSha256"
            Algorithm.HMAC_SHA384 -> "HmacSha384"
            Algorithm.HMAC_SHA512 -> "HmacSha512"
            else -> {
                throw IllegalArgumentException("Unsupported algorithm $algorithm")
            }
        }

        return Mac.getInstance(algName).run {
            init(SecretKeySpec(key, ""))
            update(message)
            doFinal()
        }
    }

    /**
     * Message encryption.
     *
     * @param algorithm must be one of [Algorithm.A128GCM], [Algorithm.A192GCM], [Algorithm.A256GCM].
     * @param key the encryption key.
     * @param nonce the nonce/IV.
     * @param messagePlaintext the message to encrypt.
     * @return the cipher text with the tag appended to it.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     */
    actual fun encrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messagePlaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        when (algorithm) {
            Algorithm.A128GCM -> {}
            Algorithm.A192GCM -> {}
            Algorithm.A256GCM -> {}
            else -> {
                throw IllegalArgumentException("Unsupported algorithm $algorithm")
            }
        }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            aad?.let { updateAAD(it) }
            doFinal(messagePlaintext)
        }
    }

    /**
     * Message decryption.
     *
     * @param algorithm must be one of [Algorithm.A128GCM], [Algorithm.A192GCM], [Algorithm.A256GCM].
     * @param key the encryption key.
     * @param nonce the nonce/IV.
     * @param messageCiphertext the message to decrypt with the tag at the end.
     * @return the plaintext.
     * @throws IllegalArgumentException if the given algorithm is not supported.
     * @throws IllegalStateException if decryption fails
     */
    actual fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        when (algorithm) {
            Algorithm.A128GCM -> {}
            Algorithm.A192GCM -> {}
            Algorithm.A256GCM -> {}
            else -> {
                throw IllegalArgumentException("Unsupported algorithm $algorithm")
            }
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce)
                )
                aad?.let { updateAAD(it) }
                doFinal(messageCiphertext)
            }
        } catch (e: Exception) {
            throw IllegalStateException("Error decrypting", e)
        }
    }

    /**
     * Checks signature validity.
     *
     * @param publicKey the public key the signature was made with.
     * @param message the data that was signed.
     * @param algorithm the signature algorithm to use.
     * @param signature the signature.
     */
    actual fun checkSignature(
        publicKey: EcPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: EcSignature
    ) {
        val signatureAlgorithm = when (algorithm) {
            Algorithm.UNSET -> throw IllegalArgumentException("Algorithm not set")
            Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256 -> "SHA256withECDSA"
            Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB320, Algorithm.ESB384 -> "SHA384withECDSA"
            Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512 -> "SHA512withECDSA"
            Algorithm.EDDSA -> {
                when (publicKey.curve) {
                    EcCurve.ED25519 -> "Ed25519"
                    EcCurve.ED448 -> "Ed448"
                    else -> throw IllegalArgumentException(
                        "Algorithm $algorithm incompatible with curve ${publicKey.curve}"
                    )
                }
            }
            Algorithm.ED25519 -> "Ed25519"
            Algorithm.ED448 -> "Ed448"

            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }

        val verified = try {
            val rawSignature = when (publicKey.curve) {
                EcCurve.ED25519, EcCurve.ED448 -> {
                    signature.r + signature.s
                }
                else -> {
                    signature.toDerEncoded()
                }
            }
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(publicKey.javaPublicKey)
                update(message)
                verify(rawSignature)
            }
        } catch (e: Throwable) {
            throw IllegalStateException("Error occurred verifying signature", e)
        }
        if (!verified) {
            throw SignatureVerificationException("Signature verification failed")
        }
    }

    /**
     * Creates an EC private key.
     *
     * @param curve the curve to use.
     */
    actual fun createEcPrivateKey(curve: EcCurve): EcPrivateKey =
        when (curve) {
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.BRAINPOOLP320R1,
            EcCurve.BRAINPOOLP384R1,
            EcCurve.BRAINPOOLP512R1 -> {
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(ECGenParameterSpec(curve.SECGName))
                val keyPair = kpg.generateKeyPair()
                val publicKey = keyPair.public.toEcPublicKey(curve)
                check(publicKey is EcPublicKeyDoubleCoordinate)
                val d = (keyPair.private as ECPrivateKey).s.toByteArray()
                EcPrivateKeyDoubleCoordinate(curve, d, publicKey.x, publicKey.y)
            }

            EcCurve.ED25519 -> {
                val kpg = KeyPairGenerator.getInstance("Ed25519")
                val keyPair = kpg.generateKeyPair()
                val publicKey = keyPair.public.toEcPublicKey(curve)
                check(publicKey is EcPublicKeyOkp)
                val d = getDerEncodedPrivateKeyFromPrivateKeyInfo(keyPair.private.encoded)
                EcPrivateKeyOkp(curve, d, publicKey.x)
            }

            EcCurve.X25519 -> {
                val kpg = KeyPairGenerator.getInstance("X25519")
                val keyPair = kpg.generateKeyPair()
                val publicKey = keyPair.public.toEcPublicKey(curve)
                check(publicKey is EcPublicKeyOkp)
                val d = getDerEncodedPrivateKeyFromPrivateKeyInfo(keyPair.private.encoded)
                EcPrivateKeyOkp(curve, d, publicKey.x)
            }

            EcCurve.ED448 -> {
                val kpg = KeyPairGenerator.getInstance("Ed448")
                val keyPair = kpg.generateKeyPair()
                val publicKey = keyPair.public.toEcPublicKey(curve)
                check(publicKey is EcPublicKeyOkp)
                val d = getDerEncodedPrivateKeyFromPrivateKeyInfo(keyPair.private.encoded)
                EcPrivateKeyOkp(curve, d, publicKey.x)
            }

            EcCurve.X448 -> {
                val kpg = KeyPairGenerator.getInstance("X448")
                val keyPair = kpg.generateKeyPair()
                val publicKey = keyPair.public.toEcPublicKey(curve)
                check(publicKey is EcPublicKeyOkp)
                val d = getDerEncodedPrivateKeyFromPrivateKeyInfo(keyPair.private.encoded)
                EcPrivateKeyOkp(curve, d, publicKey.x)
            }
        }

    /**
     * Signs data with a key.
     *
     * @param key the key to sign with.
     * @param signatureAlgorithm the signature algorithm to use.
     * @param message the data to sign.
     * @return the signature.
     */
    actual fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): EcSignature = when (key.curve) {
        EcCurve.P256,
        EcCurve.P384,
        EcCurve.P521,
        EcCurve.BRAINPOOLP256R1,
        EcCurve.BRAINPOOLP320R1,
        EcCurve.BRAINPOOLP384R1,
        EcCurve.BRAINPOOLP512R1 -> {
            val signatureAlgorithmName = when (signatureAlgorithm) {
                Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256 -> "SHA256withECDSA"
                Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB320, Algorithm.ESB384 -> "SHA384withECDSA"
                Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512 -> "SHA512withECDSA"
                else -> throw IllegalArgumentException(
                    "Unsupported signing algorithm $signatureAlgorithm for curve ${key.curve}"
                )
            }
            try {
                val derEncodedSignature = Signature.getInstance(signatureAlgorithmName).run {
                    initSign(key.javaPrivateKey)
                    update(message)
                    sign()
                }
                EcSignature.fromDerEncoded(key.curve.bitSize, derEncodedSignature)
            } catch (e: Throwable) {
                throw IllegalStateException("Unexpected Exception", e)
            }
        }

        EcCurve.ED25519 -> {
            require(signatureAlgorithm in setOf(Algorithm.EDDSA, Algorithm.ED25519))
            try {
                val rawSignature = Signature.getInstance("Ed25519").run {
                    initSign(key.javaPrivateKey)
                    update(message)
                    sign()
                }
                EcSignature(
                    rawSignature.sliceArray(IntRange(0, rawSignature.size/2 - 1)),
                    rawSignature.sliceArray(IntRange(rawSignature.size/2, rawSignature.size - 1))
                )
            } catch (e: Throwable) {
                throw IllegalStateException("Unexpected Exception", e)
            }
        }

        EcCurve.ED448 -> {
            require(signatureAlgorithm in setOf(Algorithm.EDDSA, Algorithm.ED448))
            try {
                val rawSignature = Signature.getInstance("Ed448").run {
                    initSign(key.javaPrivateKey)
                    update(message)
                    sign()
                }
                EcSignature(
                    rawSignature.sliceArray(IntRange(0, rawSignature.size/2 - 1)),
                    rawSignature.sliceArray(IntRange(rawSignature.size/2, rawSignature.size - 1))
                )
            } catch (e: Throwable) {
                throw IllegalStateException("Unexpected Exception", e)
            }
        }

        EcCurve.X25519,
        EcCurve.X448 -> {
            throw IllegalStateException("Key with curve ${key.curve} does not support signing")
        }
    }

    /**
     * Performs Key Agreement.
     *
     * @param key the key to use for key agreement.
     * @param otherKey the key from the other party.
     */
    actual fun keyAgreement(
        key: EcPrivateKey,
        otherKey: EcPublicKey
    ): ByteArray =
        when (key.curve) {
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.BRAINPOOLP320R1,
            EcCurve.BRAINPOOLP384R1,
            EcCurve.BRAINPOOLP512R1 -> {
                require(otherKey.curve == key.curve) { "Other key for ECDH is not ${key.curve.name}" }
                try {
                    val ka = KeyAgreement.getInstance("ECDH")
                    ka.init(key.javaPrivateKey)
                    ka.doPhase(otherKey.javaPublicKey, true)
                    ka.generateSecret()
                } catch (e: Throwable) {
                    throw IllegalStateException("Unexpected Exception", e)
                }
            }

            EcCurve.ED25519,
            EcCurve.ED448 -> {
                throw IllegalStateException("Key with curve ${key.curve} does not support key-agreement")
            }

            EcCurve.X25519 -> {
                require(otherKey.curve == EcCurve.X25519) { "Other key for ECDH is not Curve X25519" }
                try {
                    val ka = KeyAgreement.getInstance("X25519")
                    ka.init(key.javaPrivateKey)
                    ka.doPhase(otherKey.javaPublicKey, true)
                    ka.generateSecret()
                } catch (e: Throwable) {
                    throw IllegalStateException("Unexpected Exception", e)
                }
            }

            EcCurve.X448 -> {
                require(otherKey.curve == EcCurve.X448) { "Other key for ECDH is not Curve X448" }
                try {
                    val ka = KeyAgreement.getInstance("X448")
                    ka.init(key.javaPrivateKey)
                    ka.doPhase(otherKey.javaPublicKey, true)
                    ka.generateSecret()
                } catch (e: Throwable) {
                    throw IllegalStateException("Unexpected Exception", e)
                }
            }
        }

    @OptIn(ExperimentalEncodingApi::class)
    internal actual fun ecPublicKeyToPem(publicKey: EcPublicKey): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN PUBLIC KEY-----\n")
        sb.append(Base64.Mime.encode(publicKey.javaPublicKey.encoded))
        sb.append("\n-----END PUBLIC KEY-----\n")
        return sb.toString()
    }

    @OptIn(ExperimentalEncodingApi::class)
    internal actual fun ecPublicKeyFromPem(
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
    internal actual fun ecPrivateKeyToPem(privateKey: EcPrivateKey): String  {
        val sb = StringBuilder()
        sb.append("-----BEGIN PRIVATE KEY-----\n")
        sb.append(Base64.Mime.encode(privateKey.javaPrivateKey.encoded))
        sb.append("\n-----END PRIVATE KEY-----\n")
        return sb.toString()
    }

    @OptIn(ExperimentalEncodingApi::class)
    internal actual fun ecPrivateKeyFromPem(
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

    internal actual fun uuidGetRandom(): UUID {
        return UUID.fromJavaUuid(java.util.UUID.randomUUID())
    }

    internal actual fun validateCertChain(certChain: X509CertChain): Boolean {
        val javaCerts = certChain.javaX509Certificates
        for (n in javaCerts.indices) {
            if (n < javaCerts.size - 1) {
                val cert = javaCerts[n]
                val certSignedBy = javaCerts[n + 1]
                try {
                    cert.verify(certSignedBy.publicKey)
                } catch (_: Throwable) {
                    return false
                }
            }
        }
        return true
    }
}

/**
 * Gets the private key in a PrivateKeyInfo where its OCTET STRING representation
 * contains a DER encoded key.
 */
internal fun getDerEncodedPrivateKeyFromPrivateKeyInfo(privateKeyInfo: ByteArray): ByteArray {
    // PrivateKeyInfo is defined in https://datatracker.ietf.org/doc/html/rfc5208 but
    // also see https://datatracker.ietf.org/doc/html/rfc5958 which extends this.
    //
    //   PrivateKeyInfo ::= SEQUENCE {
    //        version                   Version,
    //        privateKeyAlgorithm       PrivateKeyAlgorithmIdentifier,
    //        privateKey                PrivateKey,
    //        attributes           [0]  IMPLICIT Attributes OPTIONAL
    //   }
    //
    //   Version ::= INTEGER
    //
    //   PrivateKeyAlgorithmIdentifier ::= AlgorithmIdentifier
    //
    //   PrivateKey ::= OCTET STRING
    //
    //   Attributes ::= SET OF Attribute
    //
    val seq = ASN1.decode(privateKeyInfo) as ASN1Sequence
    val derEncodedPrivateKeyOctetString = seq.elements[2] as ASN1OctetString
    val derEncodedPrivateKey = derEncodedPrivateKeyOctetString.value
    return (ASN1.decode(derEncodedPrivateKey) as ASN1OctetString).value
}

internal fun generatePrivateKeyInfo(
    algorithmOid: String,
    privateKey: ByteArray
): ByteArray {
    // Generates this according to https://datatracker.ietf.org/doc/html/rfc5208
    //
    val derEncodedPrivateKey = ASN1.encode(ASN1OctetString(privateKey))
    val seq = ASN1Sequence(listOf(
        ASN1Integer(0),
        ASN1Sequence(listOf(
            ASN1ObjectIdentifier(algorithmOid)
        )),
        ASN1OctetString(derEncodedPrivateKey)
    ))
    return ASN1.encode(seq)
}

private val EcCurve.javaKeyAlgorithm: String
    get() = when(this) {
        EcCurve.ED448, EcCurve.ED25519 -> "EdDSA"
        EcCurve.X25519, EcCurve.X448 -> "XDH"
        else -> "EC"
    }
