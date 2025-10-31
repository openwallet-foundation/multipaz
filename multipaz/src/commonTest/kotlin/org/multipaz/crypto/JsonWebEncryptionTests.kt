package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.appendInt32
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonWebEncryptionTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test fun roundTrip_P256_A128GCM() = roundtrip(EcCurve.P256, Algorithm.A128GCM, false)
    @Test fun roundTrip_P384_A128GCM() = roundtrip(EcCurve.P384, Algorithm.A128GCM, false)
    @Test fun roundTrip_P521_A128GCM() = roundtrip(EcCurve.P521, Algorithm.A128GCM, false)
    @Test fun roundTrip_B256_A128GCM() = roundtrip(EcCurve.BRAINPOOLP256R1, Algorithm.A128GCM, false)
    @Test fun roundTrip_B320_A128GCM() = roundtrip(EcCurve.BRAINPOOLP320R1, Algorithm.A128GCM, false)
    @Test fun roundTrip_B384_A128GCM() = roundtrip(EcCurve.BRAINPOOLP384R1, Algorithm.A128GCM, false)
    @Test fun roundTrip_B512_A128GCM() = roundtrip(EcCurve.BRAINPOOLP512R1, Algorithm.A128GCM, false)
    @Test fun roundTrip_X25519_A128GCM() = roundtrip(EcCurve.X25519, Algorithm.A128GCM, false)
    @Test fun roundTrip_X448_A128GCM() = roundtrip(EcCurve.X448, Algorithm.A128GCM, false)

    @Test fun roundTrip_P256_A192GCM() = roundtrip(EcCurve.P256, Algorithm.A192GCM, false)
    @Test fun roundTrip_P384_A192GCM() = roundtrip(EcCurve.P384, Algorithm.A192GCM, false)
    @Test fun roundTrip_P521_A192GCM() = roundtrip(EcCurve.P521, Algorithm.A192GCM, false)
    @Test fun roundTrip_B256_A192GCM() = roundtrip(EcCurve.BRAINPOOLP256R1, Algorithm.A192GCM, false)
    @Test fun roundTrip_B320_A192GCM() = roundtrip(EcCurve.BRAINPOOLP320R1, Algorithm.A192GCM, false)
    @Test fun roundTrip_B384_A192GCM() = roundtrip(EcCurve.BRAINPOOLP384R1, Algorithm.A192GCM, false)
    @Test fun roundTrip_B512_A192GCM() = roundtrip(EcCurve.BRAINPOOLP512R1, Algorithm.A192GCM, false)
    @Test fun roundTrip_X25519_A192GCM() = roundtrip(EcCurve.X25519, Algorithm.A192GCM, false)
    @Test fun roundTrip_X448_A192GCM() = roundtrip(EcCurve.X448, Algorithm.A192GCM, false)

    @Test fun roundTrip_P256_A256GCM() = roundtrip(EcCurve.P256, Algorithm.A256GCM, false)
    @Test fun roundTrip_P384_A256GCM() = roundtrip(EcCurve.P384, Algorithm.A256GCM, false)
    @Test fun roundTrip_P521_A256GCM() = roundtrip(EcCurve.P521, Algorithm.A256GCM, false)
    @Test fun roundTrip_B256_A256GCM() = roundtrip(EcCurve.BRAINPOOLP256R1, Algorithm.A256GCM, false)
    @Test fun roundTrip_B320_A256GCM() = roundtrip(EcCurve.BRAINPOOLP320R1, Algorithm.A256GCM, false)
    @Test fun roundTrip_B384_A256GCM() = roundtrip(EcCurve.BRAINPOOLP384R1, Algorithm.A256GCM, false)
    @Test fun roundTrip_B512_A256GCM() = roundtrip(EcCurve.BRAINPOOLP512R1, Algorithm.A256GCM, false)
    @Test fun roundTrip_X25519_A256GCM() = roundtrip(EcCurve.X25519, Algorithm.A256GCM, false)
    @Test fun roundTrip_X448_A256GCM() = roundtrip(EcCurve.X448, Algorithm.A256GCM, false)

    @Test fun roundTripCompression() = roundtrip(EcCurve.P256, Algorithm.A128GCM, true)

    fun roundtrip(curve: EcCurve, encAlg: Algorithm, useCompression: Boolean) = runTest {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return@runTest
        }
        val recipientKey = Crypto.createEcPrivateKey(curve)
        val claims = buildJsonObject {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }
        val apu = ByteString(1, 2, 3)
        val apv = ByteString(4, 5, 6)
        val encryptedJwt = JsonWebEncryption.encrypt(
            claimsSet = claims,
            recipientPublicKey = recipientKey.publicKey,
            encAlg = encAlg,
            apu = apu,
            apv = apv,
            kid = "foobar",
            compressionLevel = if (useCompression) 5 else null
        )
        val decryptedClaims = JsonWebEncryption.decrypt(
            encryptedJwt = encryptedJwt,
            recipientKey = AsymmetricKey.anonymous(
                privateKey = recipientKey,
                algorithm = recipientKey.curve.defaultKeyAgreementAlgorithm
            )
        )
        assertEquals(decryptedClaims, claims)

        // Check protected header
        val splits = encryptedJwt.split('.')
        assertEquals(5, splits.size)
        val ph = Json.decodeFromString(JsonObject.serializer(), splits[0].fromBase64Url().decodeToString())
        assertEquals("ECDH-ES", ph["alg"]!!.jsonPrimitive.content)
        assertEquals(encAlg.joseAlgorithmIdentifier, ph["enc"]!!.jsonPrimitive.content)
        assertEquals("foobar", ph["kid"]!!.jsonPrimitive.content)
        if (useCompression) {
            assertEquals("DEF", ph["zip"]!!.jsonPrimitive.content)
        } else {
            assertNull(ph["zip"])
        }
        assertEquals(apu.toByteArray().toBase64Url(), ph["apu"]!!.jsonPrimitive.content)
        assertEquals(apv.toByteArray().toBase64Url(), ph["apv"]!!.jsonPrimitive.content)
    }

    @Test
    fun testConcatKdf() {
        // This checks our ConcatKdf() implementation works by using test vectors from
        //
        //  https://datatracker.ietf.org/doc/html/rfc7518#appendix-C
        //
        val bobEphemeralKey = EcPrivateKeyDoubleCoordinate(
            curve = EcCurve.P256,
            d = "VEmDZpDXXK8p8N0Cndsxs924q6nS1RXFASRl6BfUqdw".fromBase64Url(),
            x = "weNJy2HscCSM6AEDTDg04biOvhFhyyWvOHQfeF_PxMQ".fromBase64Url(),
            y = "e8lnCO-AlStT-NJVX-crhB7QRYhiix03illJOVAOyck".fromBase64Url()
        )
        val protectedHeader = Json.decodeFromString(JsonObject.serializer(),
            """
                {
                  "alg":"ECDH-ES",
                  "enc":"A128GCM",
                  "apu":"QWxpY2U",
                  "apv":"Qm9i",
                  "epk": {
                    "kty":"EC",
                    "crv":"P-256",
                    "x":"gI0GAILBdu7T53akrFmMyGcsF3n5dO7MmwNBHKW5SV0",
                    "y":"SLW_xSffzlPWrHEVI30DHM_4egVwt3NQqeUD7nMFpps"
                  }
                }
            """.trimIndent().trim()
        )

        val senderEphemeralKey = EcPublicKey.fromJwk(protectedHeader["epk"]!!.jsonObject)
        val sharedSecret = Crypto.keyAgreement(bobEphemeralKey, senderEphemeralKey)
        assertEquals(
            // This is the hex
            //
            //   [158, 86, 217, 29, 129, 113, 53, 211, 114, 131, 66, 131, 191, 132,
            //   38, 156, 251, 49, 110, 163, 218, 128, 106, 72, 246, 218, 167, 121,
            //   140, 254, 144, 196]
            //
            // from the example.
            //
            "9e56d91d817135d372834283bf84269cfb316ea3da806a48f6daa7798cfe90c4",
            sharedSecret.toHex()
        )

        val algId = Algorithm.A128GCM.joseAlgorithmIdentifier!!.encodeToByteString()
        val apu = "Alice".encodeToByteString()
        val apv = "Bob".encodeToByteString()
        val contentEncryptionKey = JsonWebEncryption.concatKDF(
            sharedSecretZ = ByteString(sharedSecret),
            keyDataLenBits = 128,
            algorithmId = buildByteString { appendInt32(algId.size); append(algId) },
            partyUInfo =  buildByteString { appendInt32(apu.size); append(apu) },
            partyVInfo =  buildByteString { appendInt32(apv.size); append(apv) },
            suppPubInfo = buildByteString { appendInt32(128) }
        )
        assertEquals(
            "VqqN6vgjbSBcIijNcacQGg",
            contentEncryptionKey.toBase64Url()
        )

    }
}