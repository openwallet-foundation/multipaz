package org.multipaz.crypto

import js.array.jsArrayOf
import js.buffer.toByteArray
import js.objects.unsafeJso
import kotlinx.browser.window
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.OID
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toBufferSource
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
import kotlin.js.toJsString
import kotlin.js.unsafeCast

external interface XdhKeyDeriveParams : web.crypto.Algorithm {
    override var name: String
    var public: CryptoKey
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

    actual val supportedEncryptionAlgorithms = setOf(Algorithm.A128GCM, Algorithm.A256GCM)

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
        val hmacKey = crypto.subtle.importKey(
            format = KeyFormat.Companion.raw,
            keyData = key.toBufferSource(),
            algorithm = unsafeJso<HmacImportParams> {
                name = "HMAC"
                hash = hashAlgName.toJsString()
                length = key.size*8
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
        val algorithm = unsafeJso<AesGcmParams> {
            name = "AES-GCM"
            additionalData = (aad ?: byteArrayOf()).toBufferSource()
            iv = nonce.toBufferSource()
            tagLength = 128
        }
        val key = crypto.subtle.importKey(
            format = KeyFormat.Companion.raw,
            keyData = key.toBufferSource(),
            algorithm = algorithm,
            extractable = false,
            keyUsages = jsArrayOf(KeyUsage.encrypt)
        )
        return crypto.subtle.encrypt(
            algorithm = algorithm,
            key = key,
            data = messagePlaintext.toBufferSource()
        ).toByteArray()
    }

    actual suspend fun decrypt(
        algorithm: Algorithm,
        key: ByteArray,
        nonce: ByteArray,
        messageCiphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        val algorithm = unsafeJso<AesGcmParams> {
            name = "AES-GCM"
            additionalData = (aad ?: byteArrayOf()).toBufferSource()
            iv = nonce.toBufferSource()
            tagLength = 128
        }
        val key = crypto.subtle.importKey(
            format = KeyFormat.Companion.raw,
            keyData = key.toBufferSource(),
            algorithm = algorithm,
            extractable = false,
            keyUsages = jsArrayOf(KeyUsage.decrypt)
        )
        try {
            return crypto.subtle.decrypt(
                algorithm = algorithm,
                key = key,
                data = messageCiphertext.toBufferSource()
            ).toByteArray()
        } catch (e : Throwable) {
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
    internal actual suspend fun validateCertChain(certChain: X509CertChain): Boolean {
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

                OID.ED25519.oid -> unsafeJso<EcKeyImportParams> {
                    name = "EdDSA"
                    namedCurve = EcCurve.ED448.jwkName.toJsString()
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
                OID.SIGNATURE_RS512.oid -> {
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
