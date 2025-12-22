package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toHex
import org.multipaz.util.fromHex
import org.multipaz.util.truncateToWholeSeconds
import kotlin.experimental.xor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class CryptoTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun digests() = runTest {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Crypto.digest(Algorithm.SHA256, "".encodeToByteArray()).toHex()
        )
        assertEquals(
            "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e",
            Crypto.digest(Algorithm.SHA256, "Hello World".encodeToByteArray()).toHex()
        )
        assertEquals(
            "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b",
            Crypto.digest(Algorithm.SHA384, "".encodeToByteArray()).toHex()
        )
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            Crypto.digest(Algorithm.SHA512, "".encodeToByteArray()).toHex()
        )
    }

    @Test
    fun macs() = runTest {
        // These test vectors are from a zip file that can be downloaded from
        //
        //   https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program/message-authentication
        //

        // First item with L=32 and T=32
        assertEquals(
            "769f00d3e6a6cc1fb426a14a4f76c6462e6149726e0dee0ec0cf97a16605ac8b",
            Crypto.mac(
                Algorithm.HMAC_SHA256,
                "9779d9120642797f1747025d5b22b7ac607cab08e1758f2f3a46c8be1e25c53b8c6a8f58ffefa176".fromHex(),
                "b1689c2591eaf3c9e66070f8a77954ffb81749f1b00346f9dfe0b2ee905dcc288baf4a92de3f4001dd9f44c468c3d07d6c6ee82faceafc97c2fc0fc0601719d2dcd0aa2aec92d1b0ae933c65eb06a03c9c935c2bad0459810241347ab87e9f11adb30415424c6c7f5f22a003b8ab8de54f6ded0e3ab9245fa79568451dfa258e".fromHex()
            ).toHex()
        )

        // First item with L=48 and T=48
        assertEquals(
            "7cf5a06156ad3de5405a5d261de90275f9bb36de45667f84d08fbcb308ca8f53a419b07deab3b5f8ea231c5b036f8875",
            Crypto.mac(
                Algorithm.HMAC_SHA384,
                "5eab0dfa27311260d7bddcf77112b23d8b42eb7a5d72a5a318e1ba7e7927f0079dbb701317b87a3340e156dbcee28ec3a8d9".fromHex(),
                "f41380123ccbec4c527b425652641191e90a17d45e2f6206cf01b5edbe932d41cc8a2405c3195617da2f420535eed422ac6040d9cd65314224f023f3ba730d19db9844c71c329c8d9d73d04d8c5f244aea80488292dc803e772402e72d2e9f1baba5a6004f0006d822b0b2d65e9e4a302dd4f776b47a972250051a701fab2b70".fromHex()
            ).toHex()
        )

        // First item with L=64 and T=64
        assertEquals(
            "33c511e9bc2307c62758df61125a980ee64cefebd90931cb91c13742d4714c06de4003faf3c41c06aefc638ad47b21906e6b104816b72de6269e045a1f4429d4",
            Crypto.mac(
                Algorithm.HMAC_SHA512,
                "57c2eb677b5093b9e829ea4babb50bde55d0ad59fec34a618973802b2ad9b78e26b2045dda784df3ff90ae0f2cc51ce39cf54867320ac6f3ba2c6f0d72360480c96614ae66581f266c35fb79fd28774afd113fa5187eff9206d7cbe90dd8bf67c844e202".fromHex(),
                "2423dff48b312be864cb3490641f793d2b9fb68a7763b8e298c86f42245e4540eb01ae4d2d4500370b1886f23ca2cf9701704cad5bd21ba87b811daf7a854ea24a56565ced425b35e40e1acbebe03603e35dcf4a100e57218408a1d8dbcc3b99296cfea931efe3ebd8f719a6d9a15487b9ad67eafedf15559ca42445b0f9b42e".fromHex()
            ).toHex()
        )
    }

    // These test vectors are from a zip file that can be downloaded from
    //
    //   https://csrc.nist.gov/Projects/Cryptographic-Algorithm-Validation-Program/CAVP-TESTING-BLOCK-CIPHER-MODES#GCMVS
    //

    @Test
    fun encrypt() = runTest {
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A128GCM)) {
            assertEquals(
                "2ccda4a5415cb91e135c2a0f78c9b2fd" + "b36d1df9b9d5e596f83e8b7f52971cb3",
                Crypto.encrypt(
                    algorithm = Algorithm.A128GCM,
                    key = "7fddb57453c241d03efbed3ac44e371c".fromHex(),
                    nonce = "ee283a3fc75575e33efd4887".fromHex(),
                    messagePlaintext = "d5de42b461646c255c87bd2962d3b9a2".fromHex(),
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "69482957e6be5c54882d00314e0259cf" + "191e9f29bef63a26860c1e020a21137e",
                Crypto.encrypt(
                    algorithm = Algorithm.A192GCM,
                    key = "fbc0b4c56a714c83217b2d1bcadd2ed2e9efb0dcac6cc19f".fromHex(),
                    nonce = "5f4b43e811da9c470d6a9b01".fromHex(),
                    messagePlaintext = "d2ae38c4375954835d75b8e4c2f9bbb4".fromHex(),
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "fa4362189661d163fcd6a56d8bf0405a" + "d636ac1bbedd5cc3ee727dc2ab4a9489",
                Crypto.encrypt(
                    algorithm = Algorithm.A256GCM,
                    key = "31bdadd96698c204aa9ce1448ea94ae1fb4a9a0b3c9d773b51bb1822666b8f22".fromHex(),
                    nonce = "0d18e06c7c725ac9e362e1ce".fromHex(),
                    messagePlaintext = "2db5168e932556f8089a0622981d017d".fromHex(),
                ).toHex()
            )
        }
    }

    @Test
    fun encryptWithAad() = runTest {
        // These test vectors are from a zip file that can be downloaded from
        //
        //   https://csrc.nist.gov/Projects/Cryptographic-Algorithm-Validation-Program/CAVP-TESTING-BLOCK-CIPHER-MODES#GCMVS
        //
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A128GCM)) {
            assertEquals(
                "93fe7d9e9bfd10348a5606e5cafa7354" + "0032a1dc85f1c9786925a2e71d8272dd",
                Crypto.encrypt(
                    algorithm = Algorithm.A128GCM,
                    key = "c939cc13397c1d37de6ae0e1cb7c423c".fromHex(),
                    nonce = "b3d8cc017cbb89b39e0f67e2".fromHex(),
                    messagePlaintext = "c3b3c41f113a31b73d9a5cd432103069".fromHex(),
                    aad = "24825602bd12a984e0092d3e448eda5f".fromHex()
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "a54b5da33fc1196a8ef31a5321bfcaeb" + "1c198086450ae1834dd6c2636796bce2",
                Crypto.encrypt(
                    algorithm = Algorithm.A192GCM,
                    key = "6f44f52c2f62dae4e8684bd2bc7d16ee7c557330305a790d".fromHex(),
                    nonce = "9ae35825d7c7edc9a39a0732".fromHex(),
                    messagePlaintext = "37222d30895eb95884bbbbaee4d9cae1".fromHex(),
                    aad = "1b4236b846fc2a0f782881ba48a067e9".fromHex()
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "8995ae2e6df3dbf96fac7b7137bae67f" + "eca5aa77d51d4a0a14d9c51e1da474ab",
                Crypto.encrypt(
                    algorithm = Algorithm.A256GCM,
                    key = "92e11dcdaa866f5ce790fd24501f92509aacf4cb8b1339d50c9c1240935dd08b".fromHex(),
                    nonce = "ac93a1a6145299bde902f21a".fromHex(),
                    messagePlaintext = "2d71bcfa914e4ac045b2aa60955fad24".fromHex(),
                    aad = "1e0889016f67601c8ebea4943bc23ad6".fromHex()
                ).toHex()
            )
        }
    }

    @Test
    fun decrypt() = runTest {
        // These test vectors are from a zip file that can be downloaded from
        //
        //   https://csrc.nist.gov/Projects/Cryptographic-Algorithm-Validation-Program/CAVP-TESTING-BLOCK-CIPHER-MODES#GCMVS
        //
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A128GCM)) {
            assertEquals(
                "28286a321293253c3e0aa2704a278032",
                Crypto.decrypt(
                    algorithm = Algorithm.A128GCM,
                    key = "e98b72a9881a84ca6b76e0f43e68647a".fromHex(),
                    nonce = "8b23299fde174053f3d652ba".fromHex(),
                    messageCiphertext = ("5a3c1cf1985dbb8bed818036fdd5ab42" + "23c7ab0f952b7091cd324835043b5eb5").fromHex(),
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "99ae6f479b3004354ff18cd86c0b6efb",
                Crypto.decrypt(
                    algorithm = Algorithm.A192GCM,
                    key = "7a7c5b6a8a9ab5acae34a9f6e41f19a971f9c330023c0f0c".fromHex(),
                    nonce = "aa4c38bf587f94f99fee77d5".fromHex(),
                    messageCiphertext = ("132ae95bd359c44aaefa6348632cafbd" + "19d7c7d5809ad6648110f22f272e7d72").fromHex(),
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "7789b41cb3ee548814ca0b388c10b343",
                Crypto.decrypt(
                    algorithm = Algorithm.A256GCM,
                    key = "4c8ebfe1444ec1b2d503c6986659af2c94fafe945f72c1e8486a5acfedb8a0f8".fromHex(),
                    nonce = "473360e0ad24889959858995".fromHex(),
                    messageCiphertext = ("d2c78110ac7e8f107c0df0570bd7c90c" + "c26a379b6d98ef2852ead8ce83a833a7").fromHex(),
                ).toHex()
            )
        }
    }

    @Test
    fun decryptWithAad() = runTest {
        // These test vectors are from a zip file that can be downloaded from
        //
        //   https://csrc.nist.gov/Projects/Cryptographic-Algorithm-Validation-Program/CAVP-TESTING-BLOCK-CIPHER-MODES#GCMVS
        //
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A128GCM)) {
            assertEquals(
                "ecafe96c67a1646744f1c891f5e69427",
                Crypto.decrypt(
                    algorithm = Algorithm.A128GCM,
                    key = "816e39070410cf2184904da03ea5075a".fromHex(),
                    nonce = "32c367a3362613b27fc3e67e".fromHex(),
                    messageCiphertext = ("552ebe012e7bcf90fcef712f8344e8f1" + "ecaae9fc68276a45ab0ca3cb9dd9539f").fromHex(),
                    aad = "f2a30728ed874ee02983c294435d3c16".fromHex()
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "7e3a29d47de8668a74c249ed96f8f0d2a2d5e05359c116cbdcad74b8c5ddf72c503ee12824b4039b9bf8f2b6aea9b7105f351e",
                Crypto.decrypt(
                    algorithm = Algorithm.A192GCM,
                    key = "497ac0078bdfa10c7db2d49f978b1ac0610bb40aa60b5b29".fromHex(),
                    nonce = "e1608bae5ad218ae76633f9a".fromHex(),
                    messageCiphertext = ("225cddca92cf6438e69a4afcd8079a03cab65ae81f2631d14035a9656c6c68c699725fc374b909fab2709aab06037447e04cdb" + "a328f90905a4eb69d2c7be7942e7e24a").fromHex(),
                    aad = "fe71426fcb2cab1579a8adaee34fc790".fromHex()
                ).toHex()
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "85fc3dfad9b5a8d3258e4fc44571bd3b",
                Crypto.decrypt(
                    algorithm = Algorithm.A256GCM,
                    key = "54e352ea1d84bfe64a1011096111fbe7668ad2203d902a01458c3bbd85bfce14".fromHex(),
                    nonce = "df7c3bca00396d0c018495d9".fromHex(),
                    messageCiphertext = ("426e0efc693b7be1f3018db7ddbb7e4d" + "ee8257795be6a1164d7e1d2d6cac77a7").fromHex(),
                    aad = "7e968d71b50c1f11fd001f3fef49d045".fromHex()
                ).toHex()
            )
        }
    }

    @Test
    fun encryptDecrypt() = runTest {
        val key = "00000000000000000000000000000000".fromHex()
        val nonce = "000000000000000000000000".fromHex()
        val message = "Hello World".encodeToByteArray()
        val ciptherTextAndTag = Crypto.encrypt(Algorithm.A128GCM, key, nonce, message)
        val decryptedMessage = Crypto.decrypt(Algorithm.A128GCM, key, nonce, ciptherTextAndTag)
        assertContentEquals(decryptedMessage, message)
    }

    @Test
    fun decryptionFailure() = runTest {
        val key = "00000000000000000000000000000000".fromHex()
        val nonce = "000000000000000000000000".fromHex()
        val message = "Hello World".encodeToByteArray()
        val ciptherTextAndTag = Crypto.encrypt(Algorithm.A128GCM, key, nonce, message)
        // Tamper with the cipher text to induce failure.
        ciptherTextAndTag[3] = ciptherTextAndTag[8].xor(0xff.toByte())
        assertFailsWith(IllegalStateException::class) {
            Crypto.decrypt(Algorithm.A128GCM, key, nonce, ciptherTextAndTag)
        }
    }

    suspend fun testJwkEncodeDecode(curve: EcCurve) {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return
        }

        val privateKey = Crypto.createEcPrivateKey(curve)
        val publicKey = privateKey.publicKey

        // Test round tripping
        assertEquals(EcPublicKey.fromJwk(publicKey.toJwk()), publicKey)
        assertEquals(EcPrivateKey.fromJwk(privateKey.toJwk()), privateKey)
    }

    @Test fun testJwkEncodeDecode_P256() = runTest { testJwkEncodeDecode(EcCurve.P256) }
    @Test fun testJwkEncodeDecode_P384() = runTest { testJwkEncodeDecode(EcCurve.P384) }
    @Test fun testJwkEncodeDecode_P521() = runTest { testJwkEncodeDecode(EcCurve.P521) }
    @Test fun testJwkEncodeDecode_BRAINPOOLP256R1() = runTest { testJwkEncodeDecode(EcCurve.BRAINPOOLP256R1) }
    @Test fun testJwkEncodeDecode_BRAINPOOLP320R1() = runTest { testJwkEncodeDecode(EcCurve.BRAINPOOLP320R1) }
    @Test fun testJwkEncodeDecode_BRAINPOOLP384R1() = runTest { testJwkEncodeDecode(EcCurve.BRAINPOOLP384R1) }
    @Test fun testJwkEncodeDecode_BRAINPOOLP512R1() = runTest { testJwkEncodeDecode(EcCurve.BRAINPOOLP512R1) }
    @Test fun testJwkEncodeDecode_ED25519() = runTest { testJwkEncodeDecode(EcCurve.ED25519) }
    @Test fun testJwkEncodeDecode_X25519() = runTest { testJwkEncodeDecode(EcCurve.X25519) }
    @Test fun testJwkEncodeDecode_ED448() = runTest { testJwkEncodeDecode(EcCurve.ED448) }
    @Test fun testJwkEncodeDecode_X448() = runTest { testJwkEncodeDecode(EcCurve.X448) }

    // Grrr, no test vectors for EC keys in https://datatracker.ietf.org/doc/html/rfc7638#section-3.1
    // so we make our own in the following two tests...
    //

    @Test
    fun testJwkThumbprintEcDoubleCoordinate() = runTest {
        val x = "PvnLfXAKYSRIMLP8PjeBXK61-_nkl-DfkoGEvnRtK8A"
        val y = "7kO_op1NpbOBgZ9Y0bm7uFprjWVq8iNQQJhCwsM3hPk"
        val expectedJson = """
            {"crv":"P-256","kty":"EC","x":"$x","y":"$y"}
        """.trimIndent().trim()
        val expectedSha256Thumbprint = Crypto.digest(Algorithm.SHA256, expectedJson.encodeToByteArray())
        val key = EcPublicKeyDoubleCoordinate(
            curve = EcCurve.P256,
            x = x.fromBase64Url(),
            y = y.fromBase64Url()
        )
        assertContentEquals(expectedSha256Thumbprint, key.toJwkThumbprint(Algorithm.SHA256).toByteArray())
    }

    @Test
    fun testJwkThumbprintEcOkp() = runTest {
        val x = "TuUs-NklnyKcW_McTzPpVbMzW6C5wSdRDQcjeaoylHk"
        val expectedJson = """
            {"crv":"Ed25519","kty":"OKP","x":"$x"}
        """.trimIndent().trim()
        val expectedSha256Thumbprint = Crypto.digest(Algorithm.SHA256, expectedJson.encodeToByteArray())
        val key = EcPublicKeyOkp(
            curve = EcCurve.ED25519,
            x = x.fromBase64Url(),
        )
        assertContentEquals(expectedSha256Thumbprint, key.toJwkThumbprint(Algorithm.SHA256).toByteArray())
    }

    suspend fun testPemEncodeDecode(curve: EcCurve) {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return
        }

        val privateKey = Crypto.createEcPrivateKey(curve)
        val publicKey = privateKey.publicKey

        val pemPublicKey = publicKey.toPem()
        val publicKey2 = EcPublicKey.fromPem(pemPublicKey)
        assertEquals(curve, publicKey2.curve)
        assertEquals(publicKey2, publicKey)

        val pemPrivateKey = privateKey.toPem()
        val privateKey2 = EcPrivateKey.fromPem(pemPrivateKey, publicKey)
        assertEquals(privateKey2, privateKey)
    }

    @Test fun testPemEncodeDecode_P256() = runTest { testPemEncodeDecode(EcCurve.P256) }
    @Test fun testPemEncodeDecode_P384() = runTest { testPemEncodeDecode(EcCurve.P384) }
    @Test fun testPemEncodeDecode_P521() = runTest { testPemEncodeDecode(EcCurve.P521) }
    @Test fun testPemEncodeDecode_BRAINPOOLP256R1() = runTest { testPemEncodeDecode(EcCurve.BRAINPOOLP256R1) }
    @Test fun testPemEncodeDecode_BRAINPOOLP320R1() = runTest { testPemEncodeDecode(EcCurve.BRAINPOOLP320R1) }
    @Test fun testPemEncodeDecode_BRAINPOOLP384R1() = runTest { testPemEncodeDecode(EcCurve.BRAINPOOLP384R1) }
    @Test fun testPemEncodeDecode_BRAINPOOLP512R1() = runTest { testPemEncodeDecode(EcCurve.BRAINPOOLP512R1) }
    @Test fun testPemEncodeDecode_ED25519() = runTest { testPemEncodeDecode(EcCurve.ED25519) }
    @Test fun testPemEncodeDecode_X25519() = runTest { testPemEncodeDecode(EcCurve.X25519) }
    @Test fun testPemEncodeDecode_ED448() = runTest { testPemEncodeDecode(EcCurve.ED448) }
    @Test fun testPemEncodeDecode_X448() = runTest { testPemEncodeDecode(EcCurve.X448) }

    suspend fun testUncompressedFromTo(curve: EcCurve) {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return
        }

        val privateKey = Crypto.createEcPrivateKey(curve)
        val publicKey = privateKey.publicKey as EcPublicKeyDoubleCoordinate

        val uncompressedForm = publicKey.asUncompressedPointEncoding
        val key = EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(curve, uncompressedForm)

        assertEquals(key, publicKey)
    }

    @Test
    fun testUncompressedFromTo_P256() = runTest { testUncompressedFromTo(EcCurve.P256) }
    @Test
    fun testUncompressedFromTo_P384() = runTest { testUncompressedFromTo(EcCurve.P384) }
    @Test
    fun testUncompressedFromTo_P521() = runTest { testUncompressedFromTo(EcCurve.P521) }
    @Test
    fun testUncompressedFromTo_BRAINPOOLP256R1() = runTest { testUncompressedFromTo(EcCurve.BRAINPOOLP256R1) }
    @Test
    fun testUncompressedFromTo_BRAINPOOLP320R1() = runTest { testUncompressedFromTo(EcCurve.BRAINPOOLP320R1) }
    @Test
    fun testUncompressedFromTo_BRAINPOOLP384R1() = runTest { testUncompressedFromTo(EcCurve.BRAINPOOLP384R1) }
    @Test
    fun testUncompressedFromTo_BRAINPOOLP512R1() = runTest { testUncompressedFromTo(EcCurve.BRAINPOOLP512R1) }

    @Test
    fun testVerifyCertChainBasic() = runTest {
        // Note: As per the API contract, Crypto.validateCertChain() doesn't validate validity
        // times. Check this by creating chains in the future.
        val now = Clock.System.now()
        val validFrom = (now + 10.days).truncateToWholeSeconds()
        val validUntil = (now + 20.days).truncateToWholeSeconds()
        val key1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val key2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val key3 = Crypto.createEcPrivateKey(EcCurve.P256)
        val cert1 = buildX509Cert(
            publicKey = key1.publicKey,
            signingKey = AsymmetricKey.anonymous(key1),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Cert1"),
            issuer = X500Name.fromName("CN=Cert1"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val cert2 = buildX509Cert(
            publicKey = key2.publicKey,
            signingKey = AsymmetricKey.anonymous(key1),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Cert2"),
            issuer = X500Name.fromName("CN=Cert1"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(cert1)
        }
        val cert3 = buildX509Cert(
            publicKey = key3.publicKey,
            signingKey = AsymmetricKey.anonymous(key2),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Cert3"),
            issuer = X500Name.fromName("CN=Cert2"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(cert2)
        }

        assertTrue(Crypto.validateCertChain(
            X509CertChain(listOf(cert3, cert2, cert1))
        ))

        // Negative tests: In the reverse order, it shouldn't validate
        assertFalse(Crypto.validateCertChain(
            X509CertChain(listOf(cert1, cert2))
        ))
        assertFalse(Crypto.validateCertChain(
            X509CertChain(listOf(cert2, cert3))
        ))
        assertFalse(Crypto.validateCertChain(
            X509CertChain(listOf(cert1, cert3))
        ))
    }

    @Test
    fun testVerifyCertChainWithBothEcAndRsaKeys() = runTest {
        // This is the Web PKI certificate chain for www.multipaz.org as of late Dec 2025.
        // It includes certificates with both RSA and EC keys.
        val multipazOrgCertChain = X509CertChain(certificates = listOf(
            X509Cert.fromPem(
                """
                    -----BEGIN CERTIFICATE-----
                    MIIDjDCCAxOgAwIBAgISBjEjL0KsOxG2UNR/d0/YmQf0MAoGCCqGSM49BAMDMDIx
                    CzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1MZXQncyBFbmNyeXB0MQswCQYDVQQDEwJF
                    ODAeFw0yNTEyMjUxNjIxMjZaFw0yNjAzMjUxNjIxMjVaMBsxGTAXBgNVBAMTEHd3
                    dy5tdWx0aXBhei5vcmcwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARh+nleShTg
                    TjNQTM6X3n7Z0MyG9b80+8Hh+OjINsDeJh32obHyOITnabVRc+sGtMjjpHc+hDjJ
                    6mycC55vSB01o4ICHjCCAhowDgYDVR0PAQH/BAQDAgeAMB0GA1UdJQQWMBQGCCsG
                    AQUFBwMBBggrBgEFBQcDAjAMBgNVHRMBAf8EAjAAMB0GA1UdDgQWBBStjI5l1SkZ
                    bY3Gqt6jHxdjMSQOajAfBgNVHSMEGDAWgBSPDROi9i5+0VBsMxg4XVmOI3KRyjAy
                    BggrBgEFBQcBAQQmMCQwIgYIKwYBBQUHMAKGFmh0dHA6Ly9lOC5pLmxlbmNyLm9y
                    Zy8wGwYDVR0RBBQwEoIQd3d3Lm11bHRpcGF6Lm9yZzATBgNVHSAEDDAKMAgGBmeB
                    DAECATAuBgNVHR8EJzAlMCOgIaAfhh1odHRwOi8vZTguYy5sZW5jci5vcmcvMTE3
                    LmNybDCCAQMGCisGAQQB1nkCBAIEgfQEgfEA7wB2AMs49xWJfIShRF9bwd37yW7y
                    mlnNRwppBYWwyxTDFFjnAAABm1aGPZMAAAQDAEcwRQIgVSwcD9t/+WEcDLX8MG1N
                    VIZK8+i1mSGxUIYSl7MzAkwCIQDY3uft10sgc5EouK27Cd8+Ph7YqQw9obwyH5ej
                    LQKoqQB1ANFuqaVoB35mNaA/N6XdvAOlPEESFNSIGPXpMbMjy5UEAAABm1aGPlgA
                    AAQDAEYwRAIgLlYXPn2R4uBfeXlvD8yeM8BX/g5u74CZBEGauikpdEECIFEthLlE
                    vsm3z5Fz8Bd/m8lxeeaPJTUstRrYwTeldq2IMAoGCCqGSM49BAMDA2cAMGQCMBlf
                    iEEuWaCxLyb/xFAqJG5eubVwTlIxZPHw9Ok4gxkJU5I7MwKbn5EqR0+I6DN3igIw
                    IKt/FAH6/fkbJHzFJNLKKjWX8yC6M5TJ9tXyOxttbYLowx/Qt1QjY1KLoR8ruq/b
                    -----END CERTIFICATE-----
                """.trimIndent()
            ),
            X509Cert.fromPem(
                """
                    -----BEGIN CERTIFICATE-----
                    MIIEVjCCAj6gAwIBAgIQY5WTY8JOcIJxWRi/w9ftVjANBgkqhkiG9w0BAQsFADBP
                    MQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFy
                    Y2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMTAeFw0yNDAzMTMwMDAwMDBa
                    Fw0yNzAzMTIyMzU5NTlaMDIxCzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1MZXQncyBF
                    bmNyeXB0MQswCQYDVQQDEwJFODB2MBAGByqGSM49AgEGBSuBBAAiA2IABNFl8l7c
                    S7QMApzSsvru6WyrOq44ofTUOTIzxULUzDMMNMchIJBwXOhiLxxxs0LXeb5GDcHb
                    R6EToMffgSZjO9SNHfY9gjMy9vQr5/WWOrQTZxh7az6NSNnq3u2ubT6HTKOB+DCB
                    9TAOBgNVHQ8BAf8EBAMCAYYwHQYDVR0lBBYwFAYIKwYBBQUHAwIGCCsGAQUFBwMB
                    MBIGA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFI8NE6L2Ln7RUGwzGDhdWY4j
                    cpHKMB8GA1UdIwQYMBaAFHm0WeZ7tuXkAXOACIjIGlj26ZtuMDIGCCsGAQUFBwEB
                    BCYwJDAiBggrBgEFBQcwAoYWaHR0cDovL3gxLmkubGVuY3Iub3JnLzATBgNVHSAE
                    DDAKMAgGBmeBDAECATAnBgNVHR8EIDAeMBygGqAYhhZodHRwOi8veDEuYy5sZW5j
                    ci5vcmcvMA0GCSqGSIb3DQEBCwUAA4ICAQBnE0hGINKsCYWi0Xx1ygxD5qihEjZ0
                    RI3tTZz1wuATH3ZwYPIp97kWEayanD1j0cDhIYzy4CkDo2jB8D5t0a6zZWzlr98d
                    AQFNh8uKJkIHdLShy+nUyeZxc5bNeMp1Lu0gSzE4McqfmNMvIpeiwWSYO9w82Ob8
                    otvXcO2JUYi3svHIWRm3+707DUbL51XMcY2iZdlCq4Wa9nbuk3WTU4gr6LY8MzVA
                    aDQG2+4U3eJ6qUF10bBnR1uuVyDYs9RhrwucRVnfuDj29CMLTsplM5f5wSV5hUpm
                    Uwp/vV7M4w4aGunt74koX71n4EdagCsL/Yk5+mAQU0+tue0JOfAV/R6t1k+Xk9s2
                    HMQFeoxppfzAVC04FdG9M+AC2JWxmFSt6BCuh3CEey3fE52Qrj9YM75rtvIjsm/1
                    Hl+u//Wqxnu1ZQ4jpa+VpuZiGOlWrqSP9eogdOhCGisnyewWJwRQOqK16wiGyZeR
                    xs/Bekw65vwSIaVkBruPiTfMOo0Zh4gVa8/qJgMbJbyrwwG97z/PRgmLKCDl8z3d
                    tA0Z7qq7fta0Gl24uyuB05dqI5J1LvAzKuWdIjT1tP8qCoxSE/xpix8hX2dt3h+/
                    jujUgFPFZ0EVZ0xSyBNRF3MboGZnYXFUxpNjTWPKpagDHJQmqrAcDmWJnMsFY3jS
                    u1igv3OefnWjSQ==
                    -----END CERTIFICATE-----
                """.trimIndent()
            ),
            X509Cert.fromPem(
                """
                    -----BEGIN CERTIFICATE-----
                    MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
                    TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
                    cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
                    WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
                    ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
                    MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
                    h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
                    0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
                    A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
                    T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
                    B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
                    B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
                    KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
                    OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
                    jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
                    qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
                    rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
                    HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
                    hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
                    ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
                    3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
                    NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
                    ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
                    TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
                    jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
                    oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
                    4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
                    mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
                    emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
                    -----END CERTIFICATE-----
                """.trimIndent()
            ))
        )
        assertTrue(Crypto.validateCertChain(multipazOrgCertChain))
    }
}