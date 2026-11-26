package org.multipaz.crypto

import com.nimbusds.jose.CompressionAlgorithm
import org.multipaz.util.toBase64Url
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.crypto.ECDHDecrypter
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.EncryptedJWT
import com.nimbusds.jwt.JWTClaimsSet
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.buildByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import org.multipaz.util.appendInt32
import org.multipaz.verification.VerificationUtil
import java.io.ByteArrayOutputStream
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.zip.Deflater
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonWebEncryptionTestsNimbus {

    // TODO: Check for other curves than just P-256.

    @Test fun testEncryptEcdhEs_A128GCM() = runTest { testEncryptEcdhEs(Algorithm.A128GCM, false) }
    @Test fun testEncryptEcdhEs_A192GCM() = runTest { testEncryptEcdhEs(Algorithm.A192GCM, false) }
    @Test fun testEncryptEcdhEs_A256GCM() = runTest { testEncryptEcdhEs(Algorithm.A256GCM, false) }

    @Test fun testEncryptEcdhEs_Compression() = runTest { testEncryptEcdhEs(Algorithm.A128GCM, true) }

    @Test fun testEncryptEcdhEs_NoPartyInfo() = runTest { testEncryptEcdhEs(Algorithm.A128GCM, false, usePartyInfo = false) }

    private suspend fun testEncryptEcdhEs(encAlg: Algorithm, useCompression: Boolean, usePartyInfo: Boolean = true) {
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)

        val claims = buildJsonObject {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }
        val encryptedJwt = JsonWebEncryption.encrypt(
            claimsSet = claims,
            recipientPublicKey = recipientKey.publicKey,
            encAlg = encAlg,
            apu = if (usePartyInfo) ByteString(1, 2, 3) else null,
            apv = if (usePartyInfo) ByteString(4, 5, 6) else null,
            compressionLevel = if (useCompression) 5 else null,
        )

        // Use Nimbus to decrypt
        val encryptedJWT = EncryptedJWT.parse(encryptedJwt)
        val encKey = ECKey(
            Curve.P_256,
            recipientKey.publicKey.javaPublicKey as ECPublicKey,
            recipientKey.javaPrivateKey as ECPrivateKey,
            null, null, null, null, null, null, null, null, null, null, null, null
        )
        val decrypter = ECDHDecrypter(encKey)
        encryptedJWT.decrypt(decrypter)
        val decryptedClaims = Json.decodeFromString<JsonObject>(encryptedJWT.jwtClaimsSet.toString())

        assertEquals(claims, decryptedClaims)
    }

    @Test fun testDecryptEcdhEs_A128GCM() = runTest { testDecryptEcdhEs(Algorithm.A128GCM, false) }
    @Test fun testDecryptEcdhEs_A192GCM() = runTest { testDecryptEcdhEs(Algorithm.A192GCM, false) }
    @Test fun testDecryptEcdhEs_A256GCM() = runTest { testDecryptEcdhEs(Algorithm.A256GCM, false) }

    @Test fun testDecryptEcdhEs_Compression() = runTest { testDecryptEcdhEs(Algorithm.A128GCM, true) }

    @Test fun testDecryptEcdhEs_NoPartyInfo() = runTest { testDecryptEcdhEs(Algorithm.A128GCM, false, usePartyInfo = false) }

    private fun testDecryptEcdhEs(encAlg: Algorithm, useCompression: Boolean, usePartyInfo: Boolean = true) = runTest {
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val claims = buildJsonObject {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        val apu = ByteString(1, 2, 3)
        val apv = ByteString(4, 5, 6)

        // Build the Encrypted JWT with Nimbus
        val responseEncryptionAlg = JWEAlgorithm.parse("ECDH-ES")
        val responseEncryptionMethod = EncryptionMethod.parse(encAlg.joseAlgorithmIdentifier)
        val builder = JWEHeader.Builder(responseEncryptionAlg, responseEncryptionMethod)
        if (usePartyInfo) {
            builder
                .agreementPartyUInfo(Base64URL(apu.toByteArray().toBase64Url()))
                .agreementPartyVInfo(Base64URL(apv.toByteArray().toBase64Url()))
        }
        if (useCompression) {
            builder.compressionAlgorithm(CompressionAlgorithm.DEF)
        }
        val jweHeader = builder.build()
        val keySet = JWKSet(JWK.parseFromPEMEncodedObjects(recipientKey.publicKey.toPem()))
        val claimSet = JWTClaimsSet.parse(claims.toString())
        val eJwt = EncryptedJWT(jweHeader, claimSet)
        eJwt.encrypt(ECDHEncrypter(keySet.keys[0] as ECKey))
        val encryptedJwt = eJwt.serialize()

        val decryptedClaims = JsonWebEncryption.decrypt(
            encryptedJwt = encryptedJwt,
            recipientKey = AsymmetricKey.anonymous(
                privateKey = recipientKey,
                algorithm = recipientKey.curve.defaultKeyAgreementAlgorithm
            )
        )

        assertEquals(claims, decryptedClaims)
    }

    @Test
    fun testOversizedZipDefBombRejectedByDecryptDcResponse() = runTest {
        val verifierKey = Crypto.createEcPrivateKey(EcCurve.P256)

        // Stream-deflate a 64 MiB JSON payload without ever holding 64 MiB uncompressed in RAM
        val baos = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap = */ true)
        try {
            val outBuf = ByteArray(8192)
            fun feedAndDrain(bytes: ByteArray) {
                deflater.setInput(bytes)
                while (!deflater.needsInput()) {
                    val n = deflater.deflate(outBuf)
                    if (n > 0) baos.write(outBuf, 0, n)
                }
            }
            feedAndDrain("{\"vp_token\":{\"pid\":[\"".encodeToByteArray())
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            repeat(1024) {
                feedAndDrain(chunk)
            }
            feedAndDrain("\"]}}".encodeToByteArray())
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(outBuf)
                if (n > 0) baos.write(outBuf, 0, n)
            }
        } finally {
            deflater.end()
        }
        val compressedPayload = baos.toByteArray()
        assertTrue(compressedPayload.size < 128 * 1024)

        val jwe = Crypto.createEcPrivateKey(EcCurve.P256).use { senderEphemeralKey ->
            val protectedHeader = buildJsonObject {
                put("alg", "ECDH-ES")
                put("enc", Algorithm.A128GCM.joseAlgorithmIdentifier)
                put("epk", senderEphemeralKey.publicKey.toJwk())
                put("zip", "DEF")
            }
            val protectedHeaderB64 = Json.encodeToString(protectedHeader).encodeToByteArray().toBase64Url()
            val algId = Algorithm.A128GCM.joseAlgorithmIdentifier!!.encodeToByteArray()
            val cek = Crypto.keyAgreement(senderEphemeralKey, verifierKey.publicKey).use { sharedSecret ->
                JsonWebEncryption.concatKDF(
                    sharedSecretZ = sharedSecret,
                    keyDataLenBits = 128,
                    algorithmId = buildByteString { appendInt32(algId.size); append(algId) },
                    partyUInfo = buildByteString { appendInt32(0) },
                    partyVInfo = buildByteString { appendInt32(0) },
                    suppPubInfo = buildByteString { appendInt32(128) }
                )
            }
            val nonce = Crypto.secureRandom.nextBytes(12)
            val cipherTextWithTag = cek.use { key ->
                Crypto.encrypt(
                    algorithm = Algorithm.A128GCM,
                    key = key,
                    nonce = nonce,
                    messagePlaintext = compressedPayload,
                    aad = protectedHeaderB64.encodeToByteArray(),
                )
            }
            val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextWithTag.size - 16)
            val authTag = cipherTextWithTag.copyOfRange(cipherTextWithTag.size - 16, cipherTextWithTag.size)
            "$protectedHeaderB64..${nonce.toBase64Url()}.${cipherText.toBase64Url()}.${authTag.toBase64Url()}"
        }

        val response = buildJsonObject {
            put("protocol", "openid4vp-v1-unsigned")
            putJsonObject("data") { put("response", jwe) }
        }
        assertFailsWith<IllegalArgumentException> {
            VerificationUtil.decryptDcResponse(
                response = response,
                nonce = ByteString(ByteArray(16)),
                origin = "https://verifier.invalid",
                responseEncryptionKey = AsymmetricKey.anonymous(
                    privateKey = verifierKey,
                    algorithm = verifierKey.curve.defaultKeyAgreementAlgorithm
                ),
            )
        }
    }
}
