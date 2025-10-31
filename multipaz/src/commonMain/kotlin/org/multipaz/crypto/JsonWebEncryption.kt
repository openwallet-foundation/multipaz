package org.multipaz.crypto

import io.ktor.utils.io.core.toByteArray
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.buildByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.util.appendInt32
import org.multipaz.util.deflate
import org.multipaz.util.fromBase64Url
import org.multipaz.util.inflate
import org.multipaz.util.toBase64Url
import kotlin.math.ceil
import kotlin.random.Random

/**
 *  JSON Web Encryption (JWE) support routines
 */
object JsonWebEncryption {
    /**
     * Encrypts a claim set using ECDH-ES.
     *
     * Reference: [RFC 7518 Section 4.6 Key Agreement with Elliptic Curve Diffie-Hellman Ephemeral Static (ECDH-ES)](https://datatracker.ietf.org/doc/html/rfc7518#section-4.6)
     *
     * @param claimsSet the claims set to encrypt.
     * @param recipientPublicKey the public key to encrypt to.
     * @param encAlg the encryption algorithm, [Algorithm.A128GCM], [Algorithm.A192GCM], or [Algorithm.A256GCM].
     * @param apu agreement PartyUInfo (apu) parameter or `null`.
     * @param apv agreement PartyVInfo (apv) parameter or `null`.
     * @param random the [Random] used to generate a nonce.
     * @param kid if not `null`, this will be included as the value for the `kid` parameter in the header.
     * @param compressionLevel The compression level to use for DEFLATE compression or `null` to not compress.
     * @return the compact serialization of the JWE.
     */
    fun encrypt(
        claimsSet: JsonObject,
        recipientPublicKey: EcPublicKey,
        encAlg: Algorithm,
        apu: ByteString?,
        apv: ByteString?,
        random: Random = Random.Default,
        kid: String? = null,
        compressionLevel: Int? = null
    ): String {
        val keyDataLenBits = when (encAlg) {
            Algorithm.A128GCM -> 128
            Algorithm.A192GCM -> 192
            Algorithm.A256GCM -> 256
            else -> throw IllegalArgumentException("encAlg $encAlg not supported")
        }

        val senderEphemeralKey = Crypto.createEcPrivateKey(recipientPublicKey.curve)

        val protectedHeader = buildJsonObject {
            put("alg", "ECDH-ES")
            put("enc", encAlg.joseAlgorithmIdentifier)
            apu?.let { put("apu", it.toByteArray().toBase64Url()) }
            apv?.let { put("apv", it.toByteArray().toBase64Url()) }
            put("epk", senderEphemeralKey.publicKey.toJwk())
            kid?.let { put("kid", it) }
            if (compressionLevel != null) {
                put("zip", "DEF")
            }
        }
        val protectedHeaderB64 = Json.encodeToString(protectedHeader).encodeToByteArray().toBase64Url()

        val sharedSecret = Crypto.keyAgreement(senderEphemeralKey, recipientPublicKey)

        val algId = encAlg.joseAlgorithmIdentifier!!.toByteArray()
        val contentEncryptionKey = concatKDF(
            sharedSecretZ = ByteString(sharedSecret),
            keyDataLenBits = keyDataLenBits,
            algorithmId = buildByteString { appendInt32(algId.size); append(algId) },
            partyUInfo =  buildByteString { apu?.let { appendInt32(it.size); append(it) } },
            partyVInfo =  buildByteString { apv?.let { appendInt32(it.size); append(it) } },
            suppPubInfo = buildByteString { appendInt32(keyDataLenBits) }
        )
        // 96 bits (12 bytes) is a recommended IV size, but AndroidOpenSSL provider requires
        // it, so just go with that recommendation.
        val nonce = random.nextBytes(12)
        val messageToEncrypt = if (compressionLevel != null) {
            deflate(
                data = Json.encodeToString(claimsSet).encodeToByteArray(),
                compressionLevel = compressionLevel,
            )
        } else {
            Json.encodeToString(claimsSet).encodeToByteArray()
        }
        val cipherTextWithTag = Crypto.encrypt(
            algorithm = encAlg,
            key = contentEncryptionKey,
            nonce = nonce,
            messagePlaintext = messageToEncrypt,
            aad = protectedHeaderB64.toByteArray(),
        )
        // Auth tag is a single block which is always 16 bytes long for AES, irrespective of
        // the key length.
        val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextWithTag.size - 16)
        val authTag = cipherTextWithTag.copyOfRange(cipherTextWithTag.size - 16, cipherTextWithTag.size)
        return protectedHeaderB64 + "." + "." + nonce.toBase64Url() + "." + cipherText.toBase64Url() + "." + authTag.toBase64Url()
    }

    /**
     * Decrypts an encrypted JWT using ECDH-ES.
     *
     * Reference: [RFC 7518 Section 4.6 Key Agreement with Elliptic Curve Diffie-Hellman Ephemeral Static (ECDH-ES)](https://datatracker.ietf.org/doc/html/rfc7518#section-4.6)
     *
     * @param encryptedJwt the compact serialization of the JWE.
     * @param recipientKey the recipients private key corresponding to the public key this was encrypted to.
     * @return the decrypted claims set.
     */
    suspend fun decrypt(
        encryptedJwt: String,
        recipientKey: AsymmetricKey
    ): JsonObject {
        val splits = encryptedJwt.split(".")
        require(splits.size == 5)
        val (protectedHeaderB64, encryptedKeyB64, ivB64, cipherTextB64, authenticationTagB64) = splits
        val protectedHeader = Json.decodeFromString(JsonObject.serializer(), protectedHeaderB64.fromBase64Url().decodeToString())

        require(protectedHeader["alg"]!!.jsonPrimitive.content == "ECDH-ES")

        val encAlg = Algorithm.fromJoseAlgorithmIdentifier(protectedHeader["enc"]!!.jsonPrimitive.content)
        val keyDataLenBits = when (encAlg) {
            Algorithm.A128GCM -> 128
            Algorithm.A192GCM -> 192
            Algorithm.A256GCM -> 256
            else -> throw IllegalArgumentException("encAlg $encAlg not supported")
        }

        val senderEphemeralKey = EcPublicKey.fromJwk(protectedHeader["epk"]!!.jsonObject)

        val apu = ByteString(protectedHeader["apu"]?.jsonPrimitive?.content?.fromBase64Url() ?: byteArrayOf())
        val apv = ByteString(protectedHeader["apv"]?.jsonPrimitive?.content?.fromBase64Url() ?: byteArrayOf())

        val sharedSecret = recipientKey.keyAgreement(senderEphemeralKey)

        val algId = encAlg.joseAlgorithmIdentifier!!.toByteArray()
        val contentEncryptionKey = concatKDF(
            sharedSecretZ = ByteString(sharedSecret),
            keyDataLenBits = keyDataLenBits,
            algorithmId = buildByteString { appendInt32(algId.size); append(algId) },
            partyUInfo =  buildByteString { appendInt32(apu.size); append(apu) },
            partyVInfo =  buildByteString { appendInt32(apv.size); append(apv) },
            suppPubInfo = buildByteString { appendInt32(keyDataLenBits) }
        )
        val clearText = Crypto.decrypt(
            algorithm = encAlg,
            key = contentEncryptionKey,
            nonce = ivB64.fromBase64Url(),
            aad = protectedHeaderB64.toByteArray(),
            messageCiphertext = cipherTextB64.fromBase64Url() + authenticationTagB64.fromBase64Url()
        )

        val compressionAlgorithm = protectedHeader["zip"]?.jsonPrimitive?.content
        if (compressionAlgorithm == null) {
            return Json.decodeFromString(JsonObject.serializer(), clearText.decodeToString())
        }
        when (compressionAlgorithm) {
            "DEF" -> {
                val decompressedClearText = inflate(clearText)
                return Json.decodeFromString(JsonObject.serializer(), decompressedClearText.decodeToString())
            }
            else -> throw IllegalArgumentException("Unsupported compression algorithm $compressionAlgorithm")
        }
    }

    /**
     * Implements Concat KDF as specified in RFC 7518, Section 4.6.2.
     *
     * KDF(Z, keydatalen, AlgorithmID, PartyUInfo, PartyVInfo, SuppPubInfo, SuppPrivInfo)
     * For ECDH-ES+AxxxKW, SuppPrivInfo is empty.
     * For ECDH-ES, this KDF is used to derive the KEK for AES Key Wrap.
     */
    internal fun concatKDF(
        sharedSecretZ: ByteString,
        keyDataLenBits: Int, // Desired output key length in bits (e.g., 128, 192, 256 for AES Key Wrap)
        algorithmId: ByteString,
        partyUInfo: ByteString,
        partyVInfo: ByteString,
        suppPubInfo: ByteString // e.g., keyDataLenBits as a 32-bit big-endian integer
    ): ByteArray {
        val keyDataLenBytes = keyDataLenBits / 8
        val reps = ceil(keyDataLenBytes.toDouble() / 32.0).toInt() // Assuming SHA-256 (32 bytes output)
        var round = 1

        var derivedKeySize = 0
        val derivedKey = buildByteString {
            while (derivedKeySize < keyDataLenBytes && round <= reps) {
                val toDigest = buildByteString {
                    appendInt32(round)
                    append(sharedSecretZ)
                    append(algorithmId)
                    append(partyUInfo)
                    append(partyVInfo)
                    append(suppPubInfo)
                }
                // SuppPrivInfo is omitted for ECDH-ES+AxxxKW as per RFC 7518
                append(Crypto.digest(Algorithm.SHA256, toDigest.toByteArray()))
                derivedKeySize += 32
                round++
            }
        }
        return derivedKey.toByteArray(startIndex = 0, endIndex = keyDataLenBytes)
    }

}