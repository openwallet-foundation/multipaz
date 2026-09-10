@file:OptIn(ExperimentalWasmJsInterop::class)

package org.multipaz.crypto

import js.array.jsArrayOf
import js.buffer.toByteArray
import js.objects.unsafeJso
import js.promise.Promise
import js.promise.await
import js.typedarrays.Uint8Array
import js.typedarrays.toUint8Array
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.OID
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toBufferSource
import web.crypto.AesCbcParams
import web.crypto.AesGcmParams
import web.crypto.CryptoKey
import web.crypto.CryptoKeyPair
import web.crypto.EcKeyGenParams
import web.crypto.EcKeyImportParams
import web.crypto.EcdhKeyDeriveParams
import web.crypto.EcdsaParams
import web.crypto.HmacImportParams
import web.crypto.JsonWebKey
import web.crypto.KeyFormat
import web.crypto.KeyUsage
import web.crypto.RsaHashedImportParams
import web.crypto.crypto
import web.crypto.decrypt
import web.crypto.deriveBits
import web.crypto.digest
import web.crypto.encrypt
import web.crypto.exportKey
import web.crypto.generateKey
import web.crypto.importKey
import web.crypto.jwk
import web.crypto.raw
import web.crypto.sign
import web.crypto.spki
import web.crypto.verify
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsException
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsString
import kotlin.js.unsafeCast

external interface XdhKeyDeriveParams : web.crypto.Algorithm {
    override var name: String
    var public: CryptoKey
}

@OptIn(ExperimentalWasmJsInterop::class)
external interface RsaHashedKeyGenParams : web.crypto.Algorithm {
    override var name: String
    var modulusLength: Int
    var publicExponent: Uint8Array<*>
    var hash: JsAny
}

@OptIn(ExperimentalWasmJsInterop::class)
external interface RsaPssParams : web.crypto.Algorithm {
    override var name: String
    var saltLength: Int
}

@OptIn(ExperimentalWasmJsInterop::class)
external interface EncapsulatedBits : JsAny {
    val sharedKey: js.buffer.ArrayBufferLike
    val ciphertext: js.buffer.ArrayBufferLike
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun subtleEncapsulateBitsAsync(
    subtle: web.crypto.SubtleCrypto,
    algorithm: web.crypto.Algorithm,
    key: CryptoKey
): Promise<EncapsulatedBits> =
    js("subtle.encapsulateBits(algorithm, key)")

@OptIn(ExperimentalWasmJsInterop::class)
private fun subtleDecapsulateBitsAsync(
    subtle: web.crypto.SubtleCrypto,
    algorithm: web.crypto.Algorithm,
    key: CryptoKey,
    ciphertext: js.buffer.BufferSource
): Promise<js.buffer.ArrayBufferLike> =
    js("subtle.decapsulateBits(algorithm, key, ciphertext)")

@OptIn(ExperimentalWasmJsInterop::class)
private fun checkSubtleSupports(operation: JsString, algorithm: JsString): Boolean =
    js("((typeof SubtleCrypto !== 'undefined' && typeof SubtleCrypto.supports === 'function') ? SubtleCrypto.supports(operation, algorithm) : ((typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined' && typeof crypto.subtle.supports === 'function') ? crypto.subtle.supports(operation, algorithm) : false))")

@OptIn(ExperimentalWasmJsInterop::class)
private fun hasSubtleSupports(): Boolean =
    js("(typeof SubtleCrypto !== 'undefined' && typeof SubtleCrypto.supports === 'function') || (typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined' && typeof crypto.subtle.supports === 'function')")

@OptIn(ExperimentalWasmJsInterop::class)
private fun hasSubtleEncapsulateBits(): Boolean =
    js("typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined' && typeof crypto.subtle.encapsulateBits === 'function'")

@OptIn(ExperimentalWasmJsInterop::class)
private fun isOperationSupported(operation: String, algorithm: String): Boolean {
    if (hasSubtleSupports()) {
        try {
            return checkSubtleSupports(operation.toJsString(), algorithm.toJsString())
        } catch (_: Throwable) {
        }
    }
    return hasSubtleEncapsulateBits()
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun EcPublicKey.toJsonWebKey(keyOp: String): JsonWebKey {
    when (this) {
        is EcPublicKeyDoubleCoordinate -> {
            return unsafeJso<JsonWebKey> {
                crv = curve.jwkName
                kty = "EC"
                x = this@toJsonWebKey.x.toBase64Url()
                y = this@toJsonWebKey.y.toBase64Url()
                ext = true
                key_ops = jsArrayOf(keyOp.toJsString())
            }
        }
        is EcPublicKeyOkp -> {
            return unsafeJso<JsonWebKey> {
                crv = curve.jwkName
                kty = "OKP"
                x = this@toJsonWebKey.x.toBase64Url()
                ext = true
                key_ops = jsArrayOf(keyOp.toJsString())
            }
        }
    }
}

private fun EcPrivateKey.toJsonWebKey(keyOp: String): JsonWebKey {
    when (this) {
        is EcPrivateKeyDoubleCoordinate -> {
            return unsafeJso<JsonWebKey> {
                crv = curve.jwkName
                d = this@toJsonWebKey.d.toBase64Url()
                kty = "EC"
                x = this@toJsonWebKey.x.toBase64Url()
                y = this@toJsonWebKey.y.toBase64Url()
                ext = true
                key_ops = jsArrayOf(keyOp.toJsString())
            }
        }
        is EcPrivateKeyOkp -> {
            return unsafeJso<JsonWebKey> {
                crv = curve.jwkName
                d = this@toJsonWebKey.d.toBase64Url()
                kty = "OKP"
                x = this@toJsonWebKey.x.toBase64Url()
                ext = true
                key_ops = jsArrayOf(keyOp.toJsString())
            }
        }
    }
}

actual object Crypto {
    // The values of `supportedCurves` and `supportedEncryptionAlgorithms` is currently
    // based on what Chrome supports. Maybe make it based on runtime-detection of what
    // works...

    actual val supportedCurves: Set<EcCurve>
        get() = setOf(
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.ED25519,
            EcCurve.X25519,
        )

    actual val supportedEncryptionAlgorithms = setOf(
        Algorithm.A128GCM,
        Algorithm.A256GCM,
        Algorithm.A128CBC,
        Algorithm.A256CBC
    )

    actual val supportedMlDsaAlgorithms: Set<Algorithm>
        get() = setOf(
            Algorithm.ML_DSA_44 to "ML-DSA-44",
            Algorithm.ML_DSA_65 to "ML-DSA-65",
            Algorithm.ML_DSA_87 to "ML-DSA-87"
        ).filter { isOperationSupported("sign", it.second) }
            .map { it.first }
            .toSet()

    actual val supportedMlKemAlgorithms: Set<Algorithm>
        get() = setOf(
            Algorithm.ML_KEM_512 to "ML-KEM-512",
            Algorithm.ML_KEM_768 to "ML-KEM-768",
            Algorithm.ML_KEM_1024 to "ML-KEM-1024"
        ).filter { isOperationSupported("encapsulateBits", it.second) }
            .map { it.first }
            .toSet()

    actual val provider: String by lazy {
        "Web Crypto (${window.navigator.userAgent})"
    }

    actual suspend fun digest(
        algorithm: Algorithm,
        message: ByteArray
    ): ByteArray {
        val algName = when (algorithm) {
            Algorithm.INSECURE_SHA1 -> "SHA-1"
            Algorithm.SHA256 -> "SHA-256"
            Algorithm.SHA384 -> "SHA-384"
            Algorithm.SHA512 -> "SHA-512"
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
        return crypto.subtle.digest(algName, message.toBufferSource()).toByteArray()
    }

    actual suspend fun mac(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray
    ): ByteArray {
        val hashAlgName = when (algorithm) {
            Algorithm.HMAC_INSECURE_SHA1 -> "SHA-1"
            Algorithm.HMAC_SHA256 -> "SHA-256"
            Algorithm.HMAC_SHA384 -> "SHA-384"
            Algorithm.HMAC_SHA512 -> "SHA-512"
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
        val effectiveKey = if (key.isEmpty()) byteArrayOf(0) else key
        val hmacKey = crypto.subtle.importKey(
            format = KeyFormat.Companion.raw,
            keyData = effectiveKey.toBufferSource(),
            algorithm = unsafeJso<HmacImportParams> {
                name = "HMAC"
                hash = hashAlgName.toJsString()
                length = effectiveKey.size*8
            },
            extractable = false,
            keyUsages = jsArrayOf(KeyUsage.sign, KeyUsage.verify)
        )
        val signature = crypto.subtle.sign(
            algorithm = "HMAC",
            key = hmacKey,
            data = message.toBufferSource()
        )
        return signature.toByteArray()
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
        val (webAlgorithm, webImportAlgorithm) = when (algorithm) {
            Algorithm.A128GCM, Algorithm.A192GCM, Algorithm.A256GCM -> {
                val params = unsafeJso<AesGcmParams> {
                    name = "AES-GCM"
                    additionalData = (aad ?: byteArrayOf()).toBufferSource()
                    iv = nonce.toBufferSource()
                    tagLength = 128
                }
                Pair(params, params)
            }
            Algorithm.A128CBC, Algorithm.A192CBC, Algorithm.A256CBC -> {
                val params = unsafeJso<AesCbcParams> {
                    name = "AES-CBC"
                    iv = nonce.toBufferSource()
                }
                Pair(params, params)
            }
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
        val cryptoKey = crypto.subtle.importKey(
            format = KeyFormat.Companion.raw,
            keyData = key.toBufferSource(),
            algorithm = webImportAlgorithm,
            extractable = false,
            keyUsages = jsArrayOf(KeyUsage.encrypt)
        )
        return crypto.subtle.encrypt(
            algorithm = webAlgorithm,
            key = cryptoKey,
            data = messagePlaintext.toBufferSource()
        ).toByteArray()
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        val (webAlgorithm, webImportAlgorithm) = when (algorithm) {
            Algorithm.A128GCM, Algorithm.A192GCM, Algorithm.A256GCM -> {
                val params = unsafeJso<AesGcmParams> {
                    name = "AES-GCM"
                    additionalData = (aad ?: byteArrayOf()).toBufferSource()
                    iv = nonce.toBufferSource()
                    tagLength = 128
                }
                Pair(params, params)
            }
            Algorithm.A128CBC, Algorithm.A192CBC, Algorithm.A256CBC -> {
                val params = unsafeJso<AesCbcParams> {
                    name = "AES-CBC"
                    iv = nonce.toBufferSource()
                }
                Pair(params, params)
            }
            else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
        val cryptoKey = crypto.subtle.importKey(
            format = KeyFormat.Companion.raw,
            keyData = key.toBufferSource(),
            algorithm = webImportAlgorithm,
            extractable = false,
            keyUsages = jsArrayOf(KeyUsage.decrypt)
        )
        try {
            return crypto.subtle.decrypt(
                algorithm = webAlgorithm,
                key = cryptoKey,
                data = messageCiphertext.toBufferSource()
            ).toByteArray()
        } catch (e : JsException) {
            throw IllegalStateException("Error decrypting", e)
        } catch (e : Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException("Error decrypting", e)
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun checkSignature(
        publicKey: EcPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: EcSignature
    ) {
        when (publicKey.curve) {
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.BRAINPOOLP320R1,
            EcCurve.BRAINPOOLP384R1,
            EcCurve.BRAINPOOLP512R1 -> {
                val hashAlgorithmName = when (algorithm) {
                    Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256 -> "SHA-256"
                    Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB384 -> "SHA-384"
                    Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512 -> "SHA-512"
                    else -> throw IllegalArgumentException("Unsupported signature algorithm $algorithm")
                }
                val importedKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.raw,
                    keyData = (publicKey as EcPublicKeyDoubleCoordinate).asUncompressedPointEncoding.toBufferSource(),
                    algorithm = unsafeJso<EcKeyImportParams> {
                        name = "ECDSA"
                        namedCurve = publicKey.curve.jwkName.toJsString()
                    },
                    extractable = false,
                    keyUsages = jsArrayOf(KeyUsage.verify)
                )
                if (!crypto.subtle.verify(
                        algorithm = unsafeJso<EcdsaParams> {
                            name = "ECDSA"
                            hash = hashAlgorithmName.toJsString()
                        },
                        key = importedKey,
                        signature = (signature.r + signature.s).toBufferSource(),
                        data = message.toBufferSource(),
                    )
                ) {
                    throw SignatureVerificationException("Signature verification failed")
                }
            }

            EcCurve.ED448,
            EcCurve.ED25519 -> {
                val importedKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.jwk,
                    keyData = publicKey.toJsonWebKey("verify"),
                    algorithm = publicKey.curve.jwkName,
                    extractable = false,
                    keyUsages = jsArrayOf(KeyUsage.verify)
                )
                if (!crypto.subtle.verify(
                        algorithm = publicKey.curve.jwkName,
                        key = importedKey,
                        signature = (signature.r + signature.s).toBufferSource(),
                        data = message.toBufferSource(),
                    )
                ) {
                    throw SignatureVerificationException("Signature verification failed")
                }
            }

            EcCurve.X25519,
            EcCurve.X448 -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun checkSignature(
        publicKey: RsaPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: RsaSignature
    ) {
        val (name, hashName, saltLength) = when (algorithm.joseAlgorithmIdentifier) {
            "RS256" -> Triple("RSASSA-PKCS1-v1_5", "SHA-256", 0)
            "RS384" -> Triple("RSASSA-PKCS1-v1_5", "SHA-384", 0)
            "RS512" -> Triple("RSASSA-PKCS1-v1_5", "SHA-512", 0)
            "PS256" -> Triple("RSA-PSS", "SHA-256", 32)
            "PS384" -> Triple("RSA-PSS", "SHA-384", 48)
            "PS512" -> Triple("RSA-PSS", "SHA-512", 64)
            else -> throw IllegalArgumentException("Unsupported RSA algorithm $algorithm")
        }
        val importedKey = crypto.subtle.importKey(
            format = KeyFormat.Companion.spki,
            keyData = publicKey.toSubjectPublicKeyInfo().toBufferSource(),
            algorithm = unsafeJso<RsaHashedImportParams> {
                this.name = name
                hash = hashName.toJsString()
            },
            extractable = false,
            keyUsages = jsArrayOf(KeyUsage.verify)
        )
        val verificationAlgorithm: web.crypto.Algorithm = if (name == "RSA-PSS") {
            unsafeJso<RsaPssParams> {
                this.name = name
                this.saltLength = saltLength
            }
        } else {
            unsafeJso<web.crypto.Algorithm> {
                this.name = name
            }
        }
        if (!crypto.subtle.verify(
                algorithm = verificationAlgorithm,
                key = importedKey,
                signature = signature.signature.toBufferSource(),
                data = message.toBufferSource(),
            )
        ) {
            throw SignatureVerificationException("Signature verification failed")
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun checkSignature(
        publicKey: MlDsaPublicKey,
        message: ByteArray,
        algorithm: Algorithm,
        signature: MlDsaSignature
    ) {
        require(algorithm == publicKey.algorithm) {
            "Signature algorithm $algorithm doesn't match key algorithm ${publicKey.algorithm}"
        }
        val algName = when (publicKey.algorithm) {
            Algorithm.ML_DSA_44 -> "ML-DSA-44"
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm ${publicKey.algorithm}")
        }
        val importedKey = try {
            crypto.subtle.importKey(
                format = KeyFormat.Companion.spki,
                keyData = publicKey.toSubjectPublicKeyInfo().toBufferSource(),
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = false,
                keyUsages = jsArrayOf(KeyUsage.verify)
            )
        } catch (_: Throwable) {
            crypto.subtle.importKey(
                format = KeyFormat.Companion.raw,
                keyData = publicKey.encoded.toByteArray().toBufferSource(),
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = false,
                keyUsages = jsArrayOf(KeyUsage.verify)
            )
        }
        val verified = crypto.subtle.verify(
            algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
            key = importedKey,
            signature = signature.signature.toBufferSource(),
            data = message.toBufferSource()
        )
        if (!verified) {
            throw SignatureVerificationException("ML-DSA signature verification failed")
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun createEcPrivateKey(curve: EcCurve): EcPrivateKey {
        when (curve) {
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.BRAINPOOLP320R1,
            EcCurve.BRAINPOOLP384R1,
            EcCurve.BRAINPOOLP512R1 -> {
                val key = crypto.subtle.generateKey(
                    algorithm = unsafeJso<EcKeyGenParams> {
                        name = "ECDSA"
                        namedCurve = curve.jwkName.toJsString()
                    },
                    extractable = true,
                    keyUsages = jsArrayOf(KeyUsage.sign, KeyUsage.verify)
                )
                val publicKeyUncompressedPointEncoding = crypto.subtle.exportKey(
                    format = KeyFormat.Companion.raw,
                    key = key.publicKey
                ).toByteArray()
                val privateKeyJwk = crypto.subtle.exportKey(
                    format = KeyFormat.Companion.jwk,
                    key = key.privateKey
                )
                val publicKey = EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(
                    curve = curve,
                    encoded = publicKeyUncompressedPointEncoding
                )
                return EcPrivateKeyDoubleCoordinate(
                    curve = curve,
                    d = privateKeyJwk.d!!.fromBase64Url(),
                    x = publicKey.x,
                    y = publicKey.y
                )
            }
            EcCurve.ED448,
            EcCurve.ED25519 -> {
                val key = crypto.subtle.generateKey(
                    algorithm = curve.jwkName,
                    extractable = true,
                    keyUsages = jsArrayOf(KeyUsage.sign, KeyUsage.verify)
                ).unsafeCast<CryptoKeyPair>()
                val x = crypto.subtle.exportKey(
                    format = KeyFormat.Companion.raw,
                    key = key.publicKey
                ).toByteArray()
                val privateKeyJwk = crypto.subtle.exportKey(
                    format = KeyFormat.Companion.jwk,
                    key = key.privateKey
                )
                return EcPrivateKeyOkp(
                    curve = curve,
                    d = privateKeyJwk.d!!.fromBase64Url(),
                    x = x,
                )
            }
            EcCurve.X25519,
            EcCurve.X448 -> {
                val key = crypto.subtle.generateKey(
                    algorithm = curve.jwkName,
                    extractable = true,
                    keyUsages = jsArrayOf(KeyUsage.deriveBits)
                ).unsafeCast<CryptoKeyPair>()
                val x = crypto.subtle.exportKey(
                    format = KeyFormat.Companion.raw,
                    key = key.publicKey
                ).toByteArray()
                val privateKeyJwk = crypto.subtle.exportKey(
                    format = KeyFormat.Companion.jwk,
                    key = key.privateKey
                )
                return EcPrivateKeyOkp(
                    curve = curve,
                    d = privateKeyJwk.d!!.fromBase64Url(),
                    x = x,
                )
            }
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun createRsaPrivateKey(keySizeBits: Int): RsaPrivateKey {
        val key = crypto.subtle.generateKey(
            algorithm = unsafeJso<RsaHashedKeyGenParams> {
                name = "RSASSA-PKCS1-v1_5"
                modulusLength = keySizeBits
                publicExponent = byteArrayOf(1, 0, 1).toUint8Array()
                hash = "SHA-256".toJsString()
            },
            extractable = true,
            keyUsages = jsArrayOf(KeyUsage.sign, KeyUsage.verify)
        ).unsafeCast<CryptoKeyPair>()
        val pkcs8Bytes = crypto.subtle.exportKey(
            format = "pkcs8".toJsString().unsafeCast<KeyFormat>(),
            key = key.privateKey
        ).unsafeCast<js.buffer.ArrayBufferLike>().toByteArray()
        val spkiBytes = crypto.subtle.exportKey(
            format = KeyFormat.Companion.spki,
            key = key.publicKey
        ).toByteArray()
        val pubKey = RsaPublicKey.fromSubjectPublicKeyInfo(spkiBytes)
        return RsaPrivateKey.fromPrivateKeyInfo(pkcs8Bytes, pubKey)
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun createMlDsaPrivateKey(
        algorithm: Algorithm
    ): MlDsaPrivateKey {
        val algName = when (algorithm) {
            Algorithm.ML_DSA_44 -> "ML-DSA-44"
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm $algorithm")
        }
        val key = crypto.subtle.generateKey(
            algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
            extractable = true,
            keyUsages = jsArrayOf(KeyUsage.sign, KeyUsage.verify)
        ).unsafeCast<CryptoKeyPair>()
        val pubKey = try {
            val spkiBytes = crypto.subtle.exportKey(
                format = KeyFormat.Companion.spki,
                key = key.publicKey
            ).toByteArray()
            MlDsaPublicKey.fromSubjectPublicKeyInfo(spkiBytes)
        } catch (_: Throwable) {
            val rawBytes = crypto.subtle.exportKey(
                format = KeyFormat.Companion.raw,
                key = key.publicKey
            ).toByteArray()
            MlDsaPublicKey(algorithm, ByteString(rawBytes))
        }
        val privKey = try {
            val pkcs8Bytes = crypto.subtle.exportKey(
                format = "pkcs8".toJsString().unsafeCast<KeyFormat>(),
                key = key.privateKey
            ).unsafeCast<js.buffer.ArrayBufferLike>().toByteArray()
            MlDsaPrivateKey.fromPrivateKeyInfo(pkcs8Bytes, pubKey)
        } catch (_: Throwable) {
            val rawBytes = crypto.subtle.exportKey(
                format = KeyFormat.Companion.raw,
                key = key.privateKey
            ).toByteArray()
            MlDsaPrivateKey(algorithm, ByteString(rawBytes), pubKey)
        }
        return privKey
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun createMlKemPrivateKey(
        algorithm: Algorithm
    ): MlKemPrivateKey {
        val algName = when (algorithm) {
            Algorithm.ML_KEM_512 -> "ML-KEM-512"
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm $algorithm")
        }
        val encUsage = "encapsulateBits".toJsString().unsafeCast<KeyUsage>()
        val decUsage = "decapsulateBits".toJsString().unsafeCast<KeyUsage>()
        val key = try {
            crypto.subtle.generateKey(
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = true,
                keyUsages = jsArrayOf(encUsage, decUsage)
            ).unsafeCast<CryptoKeyPair>()
        } catch (_: Throwable) {
            val encKeyUsage = "encapsulateKey".toJsString().unsafeCast<KeyUsage>()
            val decKeyUsage = "decapsulateKey".toJsString().unsafeCast<KeyUsage>()
            crypto.subtle.generateKey(
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = true,
                keyUsages = jsArrayOf(encKeyUsage, decKeyUsage)
            ).unsafeCast<CryptoKeyPair>()
        }
        val pubKey = try {
            val spkiBytes = crypto.subtle.exportKey(
                format = KeyFormat.Companion.spki,
                key = key.publicKey
            ).toByteArray()
            MlKemPublicKey.fromSubjectPublicKeyInfo(spkiBytes)
        } catch (_: Throwable) {
            val rawBytes = crypto.subtle.exportKey(
                format = KeyFormat.Companion.raw,
                key = key.publicKey
            ).toByteArray()
            MlKemPublicKey(algorithm, ByteString(rawBytes))
        }
        val privKey = try {
            val pkcs8Bytes = crypto.subtle.exportKey(
                format = "pkcs8".toJsString().unsafeCast<KeyFormat>(),
                key = key.privateKey
            ).unsafeCast<js.buffer.ArrayBufferLike>().toByteArray()
            MlKemPrivateKey.fromPrivateKeyInfo(pkcs8Bytes, pubKey)
        } catch (_: Throwable) {
            val rawBytes = crypto.subtle.exportKey(
                format = KeyFormat.Companion.raw,
                key = key.privateKey
            ).toByteArray()
            MlKemPrivateKey(algorithm, ByteString(rawBytes), pubKey)
        }
        return privKey
    }

    actual suspend fun sign(
        key: EcPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): EcSignature {
        val signature = when (key.curve) {
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.BRAINPOOLP320R1,
            EcCurve.BRAINPOOLP384R1,
            EcCurve.BRAINPOOLP512R1 -> {
                val hashAlgorithmName = when (signatureAlgorithm) {
                    Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256 -> "SHA-256"
                    Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB384 -> "SHA-384"
                    Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512 -> "SHA-512"
                    else -> throw IllegalArgumentException("Unsupported signature algorithm $signatureAlgorithm")
                }
                val importedKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.jwk,
                    keyData = key.toJsonWebKey("sign"),
                    algorithm = unsafeJso<EcKeyImportParams> {
                        name = "ECDSA"
                        namedCurve = key.curve.jwkName.toJsString()
                    },
                    extractable = false,
                    keyUsages = jsArrayOf(KeyUsage.sign)
                )
                crypto.subtle.sign(
                    algorithm = unsafeJso<EcdsaParams> {
                        name = "ECDSA"
                        hash = hashAlgorithmName.toJsString()
                    },
                    key = importedKey,
                    data = message.toBufferSource(),
                ).toByteArray()
            }
            EcCurve.ED448,
            EcCurve.ED25519 -> {
                val importedKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.jwk,
                    keyData = key.toJsonWebKey("sign"),
                    algorithm = key.curve.jwkName,
                    extractable = false,
                    keyUsages = jsArrayOf(KeyUsage.sign)
                )
                crypto.subtle.sign(
                    algorithm = key.curve.jwkName,
                    key = importedKey,
                    data = message.toBufferSource(),
                ).toByteArray()
            }
            EcCurve.X25519,
            EcCurve.X448 -> {
                throw IllegalStateException("Key with curve ${key.curve} does not support signing")
            }
        }
        val len = signature.size
        val r = signature.sliceArray(IntRange(0, len/2 - 1))
        val s = signature.sliceArray(IntRange(len/2, len - 1))
        return EcSignature(r, s)
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun sign(
        key: RsaPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): RsaSignature {
        val (name, hashName, saltLength) = when (signatureAlgorithm.joseAlgorithmIdentifier) {
            "RS256" -> Triple("RSASSA-PKCS1-v1_5", "SHA-256", 0)
            "RS384" -> Triple("RSASSA-PKCS1-v1_5", "SHA-384", 0)
            "RS512" -> Triple("RSASSA-PKCS1-v1_5", "SHA-512", 0)
            "PS256" -> Triple("RSA-PSS", "SHA-256", 32)
            "PS384" -> Triple("RSA-PSS", "SHA-384", 48)
            "PS512" -> Triple("RSA-PSS", "SHA-512", 64)
            else -> throw IllegalArgumentException("Unsupported RSA signing algorithm $signatureAlgorithm")
        }
        val importedKey = crypto.subtle.importKey(
            format = "pkcs8".toJsString().unsafeCast<KeyFormat>(),
            keyData = key.toPrivateKeyInfo().toBufferSource(),
            algorithm = unsafeJso<RsaHashedImportParams> {
                this.name = name
                hash = hashName.toJsString()
            },
            extractable = false,
            keyUsages = jsArrayOf(KeyUsage.sign)
        )
        val signingAlgorithm: web.crypto.Algorithm = if (name == "RSA-PSS") {
            unsafeJso<RsaPssParams> {
                this.name = name
                this.saltLength = saltLength
            }
        } else {
            unsafeJso<web.crypto.Algorithm> {
                this.name = name
            }
        }
        return RsaSignature(
            crypto.subtle.sign(
                algorithm = signingAlgorithm,
                key = importedKey,
                data = message.toBufferSource()
            ).toByteArray()
        )
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun sign(
        key: MlDsaPrivateKey,
        signatureAlgorithm: Algorithm,
        message: ByteArray
    ): MlDsaSignature {
        require(signatureAlgorithm == key.algorithm) {
            "Signature algorithm $signatureAlgorithm doesn't match key algorithm ${key.algorithm}"
        }
        val algName = when (key.algorithm) {
            Algorithm.ML_DSA_44 -> "ML-DSA-44"
            Algorithm.ML_DSA_65 -> "ML-DSA-65"
            Algorithm.ML_DSA_87 -> "ML-DSA-87"
            else -> throw IllegalArgumentException("Unsupported ML-DSA algorithm ${key.algorithm}")
        }
        val importedKey = try {
            crypto.subtle.importKey(
                format = "pkcs8".toJsString().unsafeCast<KeyFormat>(),
                keyData = key.toPkcs8().toBufferSource(),
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = false,
                keyUsages = jsArrayOf(KeyUsage.sign)
            )
        } catch (_: Throwable) {
            crypto.subtle.importKey(
                format = KeyFormat.Companion.raw,
                keyData = key.encoded.toByteArray().toBufferSource(),
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = false,
                keyUsages = jsArrayOf(KeyUsage.sign)
            )
        }
        val sig = crypto.subtle.sign(
            algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
            key = importedKey,
            data = message.toBufferSource()
        ).toByteArray()
        return MlDsaSignature(sig)
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun kemEncapsulate(
        recipientPublicKey: MlKemPublicKey
    ): KemResult {
        val algName = when (recipientPublicKey.algorithm) {
            Algorithm.ML_KEM_512 -> "ML-KEM-512"
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm ${recipientPublicKey.algorithm}")
        }
        val encUsage = "encapsulateBits".toJsString().unsafeCast<KeyUsage>()
        val importedKey = try {
            crypto.subtle.importKey(
                format = KeyFormat.Companion.spki,
                keyData = recipientPublicKey.toSubjectPublicKeyInfo().toBufferSource(),
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = false,
                keyUsages = jsArrayOf(encUsage)
            )
        } catch (_: Throwable) {
            try {
                crypto.subtle.importKey(
                    format = KeyFormat.Companion.raw,
                    keyData = recipientPublicKey.encoded.toByteArray().toBufferSource(),
                    algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                    extractable = false,
                    keyUsages = jsArrayOf(encUsage)
                )
            } catch (_: Throwable) {
                val encKeyUsage = "encapsulateKey".toJsString().unsafeCast<KeyUsage>()
                try {
                    crypto.subtle.importKey(
                        format = KeyFormat.Companion.spki,
                        keyData = recipientPublicKey.toSubjectPublicKeyInfo().toBufferSource(),
                        algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                        extractable = false,
                        keyUsages = jsArrayOf(encKeyUsage)
                    )
                } catch (_: Throwable) {
                    crypto.subtle.importKey(
                        format = KeyFormat.Companion.raw,
                        keyData = recipientPublicKey.encoded.toByteArray().toBufferSource(),
                        algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                        extractable = false,
                        keyUsages = jsArrayOf(encKeyUsage)
                    )
                }
            }
        }
        val alg = unsafeJso<web.crypto.Algorithm> { this.name = algName }
        val encapBits = subtleEncapsulateBitsAsync(crypto.subtle, alg, importedKey).await()
        return KemResult(
            sharedSecret = encapBits.sharedKey.toByteArray(),
            ciphertext = encapBits.ciphertext.toByteArray()
        )
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual suspend fun kemDecapsulate(
        key: MlKemPrivateKey,
        ciphertext: ByteArray
    ): ByteArray {
        val algName = when (key.algorithm) {
            Algorithm.ML_KEM_512 -> "ML-KEM-512"
            Algorithm.ML_KEM_768 -> "ML-KEM-768"
            Algorithm.ML_KEM_1024 -> "ML-KEM-1024"
            else -> throw IllegalArgumentException("Unsupported ML-KEM algorithm ${key.algorithm}")
        }
        val decUsage = "decapsulateBits".toJsString().unsafeCast<KeyUsage>()
        val importedKey = try {
            crypto.subtle.importKey(
                format = "pkcs8".toJsString().unsafeCast<KeyFormat>(),
                keyData = key.toPkcs8().toBufferSource(),
                algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                extractable = false,
                keyUsages = jsArrayOf(decUsage)
            )
        } catch (_: Throwable) {
            try {
                crypto.subtle.importKey(
                    format = KeyFormat.Companion.raw,
                    keyData = key.encoded.toByteArray().toBufferSource(),
                    algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                    extractable = false,
                    keyUsages = jsArrayOf(decUsage)
                )
            } catch (_: Throwable) {
                val decKeyUsage = "decapsulateKey".toJsString().unsafeCast<KeyUsage>()
                try {
                    crypto.subtle.importKey(
                        format = "pkcs8".toJsString().unsafeCast<KeyFormat>(),
                        keyData = key.toPkcs8().toBufferSource(),
                        algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                        extractable = false,
                        keyUsages = jsArrayOf(decKeyUsage)
                    )
                } catch (_: Throwable) {
                    crypto.subtle.importKey(
                        format = KeyFormat.Companion.raw,
                        keyData = key.encoded.toByteArray().toBufferSource(),
                        algorithm = unsafeJso<web.crypto.Algorithm> { this.name = algName },
                        extractable = false,
                        keyUsages = jsArrayOf(decKeyUsage)
                    )
                }
            }
        }
        val alg = unsafeJso<web.crypto.Algorithm> { this.name = algName }
        val sharedKeyBuf = subtleDecapsulateBitsAsync(crypto.subtle, alg, importedKey, ciphertext.toBufferSource()).await()
        return sharedKeyBuf.toByteArray()
    }

    actual suspend fun keyAgreement(
        key: EcPrivateKey,
        otherKey: EcPublicKey
    ): ByteArray {
        require(otherKey.curve == key.curve) { "Other key for ECDH is not ${key.curve.name}" }
        return when (key.curve) {
            EcCurve.P256,
            EcCurve.P384,
            EcCurve.P521,
            EcCurve.BRAINPOOLP256R1,
            EcCurve.BRAINPOOLP320R1,
            EcCurve.BRAINPOOLP384R1,
            EcCurve.BRAINPOOLP512R1 -> {
                val importedKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.jwk,
                    keyData = key.toJsonWebKey("deriveBits"),
                    algorithm = unsafeJso<EcKeyImportParams> {
                        name = "ECDH"
                        namedCurve = key.curve.jwkName.toJsString()
                    },
                    extractable = false,
                    keyUsages = jsArrayOf(KeyUsage.deriveBits)
                )
                val importedOtherKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.raw,
                    keyData = (otherKey as EcPublicKeyDoubleCoordinate).asUncompressedPointEncoding.toBufferSource(),
                    algorithm = unsafeJso<EcKeyImportParams> {
                        name = "ECDH"
                        namedCurve = otherKey.curve.jwkName.toJsString()
                    },
                    extractable = false,
                    keyUsages = jsArrayOf()
                )
                crypto.subtle.deriveBits(
                    algorithm = unsafeJso<EcdhKeyDeriveParams> {
                        name = "ECDH"
                        public = importedOtherKey
                    },
                    baseKey = importedKey
                ).toByteArray()
            }
            EcCurve.X448,
            EcCurve.X25519 -> {
                val importedKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.jwk,
                    keyData = key.toJsonWebKey("deriveBits"),
                    algorithm = key.curve.jwkName,
                    extractable = false,
                    keyUsages = jsArrayOf(KeyUsage.deriveBits)
                )
                val importedOtherKey = crypto.subtle.importKey(
                    format = KeyFormat.Companion.jwk,
                    keyData = otherKey.toJsonWebKey("deriveBits"),
                    algorithm = otherKey.curve.jwkName,
                    extractable = false,
                    keyUsages = jsArrayOf()
                )
                val foo = crypto.subtle.deriveBits(
                    algorithm = unsafeJso<XdhKeyDeriveParams> {
                        name = key.curve.jwkName
                        public = importedOtherKey
                    },
                    baseKey = importedKey
                ).toByteArray()
                foo
            }
            EcCurve.ED448,
            EcCurve.ED25519 -> {
                throw IllegalStateException("Key with curve ${key.curve} does not support key-agreement")
            }
        }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    internal actual suspend fun validateCertChainSignatures(certChain: X509CertChain): Boolean {
        val certificates = certChain.certificates
        for (n in 1..certificates.lastIndex) {
            val toVerify = certificates[n - 1]
            val verifier = certificates[n]

            val toVerifyCert = ASN1.decode(toVerify.encoded) as ASN1Sequence
            val toVerifyTbsCert = toVerifyCert.elements[0] as ASN1Sequence
            val toVerifySignatureAlgorithmOid =
                ((toVerifyTbsCert.elements[2] as ASN1Sequence).elements[0] as ASN1ObjectIdentifier).oid

            val verifierCert = ASN1.decode(verifier.encoded) as ASN1Sequence
            val verifierTbsCert = verifierCert.elements[0] as ASN1Sequence
            val verifierSubjectPublicKeyInfo = verifierTbsCert.elements[6] as ASN1Sequence

            val verifierSpkiAlgorithmIdentifier =
                verifierSubjectPublicKeyInfo.elements[0] as ASN1Sequence
            val verifierSpkiAlgorithmOid =
                (verifierSpkiAlgorithmIdentifier.elements[0] as ASN1ObjectIdentifier).oid
            val verifierKeyImportParams = when (verifierSpkiAlgorithmOid) {
                // https://datatracker.ietf.org/doc/html/rfc5480#section-2.1.1
                OID.EC_PUBLIC_KEY.oid -> {
                    val ecCurveString =
                        (verifierSpkiAlgorithmIdentifier.elements[1] as ASN1ObjectIdentifier).oid
                    when (ecCurveString) {
                        OID.EC_CURVE_P256.oid -> unsafeJso<EcKeyImportParams> {
                            name = "ECDSA"
                            namedCurve = EcCurve.P256.jwkName.toJsString()
                        }

                        OID.EC_CURVE_P384.oid -> unsafeJso<EcKeyImportParams> {
                            name = "ECDSA"
                            namedCurve = EcCurve.P384.jwkName.toJsString()
                        }

                        OID.EC_CURVE_P521.oid -> unsafeJso<EcKeyImportParams> {
                            name = "ECDSA"
                            namedCurve = EcCurve.P521.jwkName.toJsString()
                        }

                        else -> throw IllegalStateException("Unexpected curve OID $ecCurveString")
                    }
                }

                OID.ED25519.oid -> unsafeJso<EcKeyImportParams> {
                    name = "EdDSA"
                    namedCurve = EcCurve.ED25519.jwkName.toJsString()
                }

                OID.ED448.oid -> unsafeJso<EcKeyImportParams> {
                    name = "EdDSA"
                    namedCurve = EcCurve.ED448.jwkName.toJsString()
                }

                OID.ML_DSA_44.oid -> unsafeJso<web.crypto.Algorithm> {
                    name = "ML-DSA-44"
                }

                OID.ML_DSA_65.oid -> unsafeJso<web.crypto.Algorithm> {
                    name = "ML-DSA-65"
                }

                OID.ML_DSA_87.oid -> unsafeJso<web.crypto.Algorithm> {
                    name = "ML-DSA-87"
                }
                // https://datatracker.ietf.org/doc/html/rfc8017#appendix-A.2.2
                "1.2.840.113549.1.1.1" ->
                    when (toVerifySignatureAlgorithmOid) {
                        OID.SIGNATURE_RS256.oid -> unsafeJso<RsaHashedImportParams> {
                            name = "RSASSA-PKCS1-v1_5"
                            hash = "SHA-256".toJsString()
                        }

                        OID.SIGNATURE_RS384.oid -> unsafeJso<RsaHashedImportParams> {
                            name = "RSASSA-PKCS1-v1_5"
                            hash = "SHA-384".toJsString()
                        }

                        OID.SIGNATURE_RS512.oid -> unsafeJso<RsaHashedImportParams> {
                            name = "RSASSA-PKCS1-v1_5"
                            hash = "SHA-512".toJsString()
                        }

                        else -> throw IllegalStateException("Unexpected Signature Algorithm OID $toVerifySignatureAlgorithmOid")
                    }

                else -> throw IllegalStateException("Unexpected Algorithm OID $verifierSpkiAlgorithmOid")
            }

            val verifierPublicKey = crypto.subtle.importKey(
                format = KeyFormat.Companion.spki,
                keyData = ASN1.encode(verifierSubjectPublicKeyInfo).toBufferSource(),
                algorithm = verifierKeyImportParams,
                extractable = false,
                keyUsages = jsArrayOf(KeyUsage.verify)
            )
            val signatureDerEncodedBytes = (toVerifyCert.elements[2] as ASN1BitString).value
            val data = ASN1.encode(toVerifyTbsCert)
            val verificationAlgorithm = when (toVerifySignatureAlgorithmOid) {
                OID.SIGNATURE_ECDSA_SHA256.oid -> unsafeJso<EcdsaParams> {
                    name = "ECDSA"
                    hash = "SHA-256".toJsString()
                }
                OID.SIGNATURE_ECDSA_SHA384.oid -> unsafeJso<EcdsaParams> {
                    name = "ECDSA"
                    hash = "SHA-384".toJsString()
                }
                OID.SIGNATURE_ECDSA_SHA512.oid -> unsafeJso<EcdsaParams> {
                    name = "ECDSA"
                    hash = "SHA-512".toJsString()
                }
                OID.SIGNATURE_RS256.oid,
                OID.SIGNATURE_RS384.oid,
                OID.SIGNATURE_RS512.oid -> unsafeJso<web.crypto.Algorithm> {
                    name = "RSASSA-PKCS1-v1_5"
                }
                OID.ML_DSA_44.oid -> unsafeJso<web.crypto.Algorithm> {
                    name = "ML-DSA-44"
                }
                OID.ML_DSA_65.oid -> unsafeJso<web.crypto.Algorithm> {
                    name = "ML-DSA-65"
                }
                OID.ML_DSA_87.oid -> unsafeJso<web.crypto.Algorithm> {
                    name = "ML-DSA-87"
                }
                else -> throw IllegalStateException("Unexpected Signature Algorithm OID $toVerifySignatureAlgorithmOid")
            }
            val signatureBytes = when (toVerifySignatureAlgorithmOid) {
                OID.SIGNATURE_ECDSA_SHA256.oid,
                OID.SIGNATURE_ECDSA_SHA384.oid,
                OID.SIGNATURE_ECDSA_SHA512.oid -> {
                    val ecCurveString = (verifierSpkiAlgorithmIdentifier.elements[1] as ASN1ObjectIdentifier).oid
                    val keySizeBits = when (ecCurveString) {
                        OID.EC_CURVE_P256.oid -> 256
                        OID.EC_CURVE_P384.oid -> 384
                        OID.EC_CURVE_P521.oid -> 521
                        else -> throw IllegalStateException("Unexpected curve OID $ecCurveString")
                    }
                    val signature = EcSignature.fromDerEncoded(keySizeBits, signatureDerEncodedBytes)
                    signature.r + signature.s
                }
                OID.SIGNATURE_RS256.oid,
                OID.SIGNATURE_RS384.oid,
                OID.SIGNATURE_RS512.oid,
                OID.ML_DSA_44.oid,
                OID.ML_DSA_65.oid,
                OID.ML_DSA_87.oid -> {
                    signatureDerEncodedBytes
                }
                else -> throw IllegalStateException("Unexpected Signature Algorithm OID $toVerifySignatureAlgorithmOid")
            }
            if (!crypto.subtle.verify(
                    algorithm = verificationAlgorithm,
                    key = verifierPublicKey,
                    signature = signatureBytes.toBufferSource(),
                    data = data.toBufferSource(),
                )
            ) {
                return false
            }
        }
        return true
    }
}
