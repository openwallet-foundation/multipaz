package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toHex
import org.multipaz.util.fromHex
import kotlin.experimental.xor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        val b200Hex =
            "1374d53697f859ba1b7cdd3e9f0061c22384e546a70869ca2b8ced4eaf1071d2" +
                "3394f556b71879da3b9cfd5ebf2081e243a40566c72889ea4bac0d6ecf3091f2" +
                "53b41576d73899fa5bbc1d7edf40a10263c42586e748a90a6bcc2d8eef50b112" +
                "73d43596f758b91a7bdc3d9eff60c12283e445a60768c92a8bec4dae0f70d132" +
                "93f455b61778d93a9bfc5dbe1f80e142a30465c62788e94aab0c6dce2f90f152" +
                "b31475d63798f95abb1c7dde3fa00162c32485e647a8096acb2c8dee4fb01172" +
                "d33495f657b8197a"
        val b200 = b200Hex.fromHex()
        assertEquals(
            "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
            Crypto.digest(Algorithm.SHA3_256, "".encodeToByteArray()).toHex()
        )
        assertEquals(
            "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            Crypto.digest(Algorithm.SHA3_256, "abc".encodeToByteArray()).toHex()
        )
        assertEquals(
            "e167f68d6563d75bb25f3aa49c29ef612d41352dc00606de7cbd630bb2665f51",
            Crypto.digest(Algorithm.SHA3_256, "Hello World".encodeToByteArray()).toHex()
        )
        assertEquals(
            "cc2232b80618869478f737ce7a109b22edcdf7a8bdf86118eefa5e96f8ae79f5",
            Crypto.digest(Algorithm.SHA3_256, b200).toHex()
        )
        assertEquals(
            "0c63a75b845e4f7d01107d852e4c2485c51a50aaaa94fc61995e71bbee983a2a" +
                "c3713831264adb47fb6bd1e058d5f004",
            Crypto.digest(Algorithm.SHA3_384, "".encodeToByteArray()).toHex()
        )
        assertEquals(
            "ec01498288516fc926459f58e2c6ad8df9b473cb0fc08c2596da7cf0e49be4b2" +
                "98d88cea927ac7f539f1edf228376d25",
            Crypto.digest(Algorithm.SHA3_384, "abc".encodeToByteArray()).toHex()
        )
        assertEquals(
            "a78ec2851e991638ce505d4a44efa606dd4056d3ab274ec6fdbac00cde164782" +
                "63ef7213bad5a7db7044f58d637afdeb",
            Crypto.digest(Algorithm.SHA3_384, "Hello World".encodeToByteArray()).toHex()
        )
        assertEquals(
            "4262ec6971add00cdbdc882ac417b9e65890931d72d3517d7b457e82159b9f26" +
                "555118dd5db7b7e6d2e44984c492a140",
            Crypto.digest(Algorithm.SHA3_384, b200).toHex()
        )
        assertEquals(
            "a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a6" +
                "15b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26",
            Crypto.digest(Algorithm.SHA3_512, "".encodeToByteArray()).toHex()
        )
        assertEquals(
            "b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e" +
                "10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0",
            Crypto.digest(Algorithm.SHA3_512, "abc".encodeToByteArray()).toHex()
        )
        assertEquals(
            "3d58a719c6866b0214f96b0a67b37e51a91e233ce0be126a08f35fdf4c043c61" +
                "26f40139bfbc338d44eb2a03de9f7bb8eff0ac260b3629811e389a5fbee8a894",
            Crypto.digest(Algorithm.SHA3_512, "Hello World".encodeToByteArray()).toHex()
        )
        assertEquals(
            "98f4a4b25f0326bf3c227968098678c5bef552a295541387436b881fb24f443b" +
                "19fc3bf5a5f6d713c45b016341cc30cada89357a4aba16c8f474a908a5e39952",
            Crypto.digest(Algorithm.SHA3_512, b200).toHex()
        )
        assertEquals(Algorithm.SHA3_256, Algorithm.fromHashAlgorithmIdentifier("sha3-256"))
        assertEquals(Algorithm.SHA3_384, Algorithm.fromHashAlgorithmIdentifier("sha3-384"))
        assertEquals(Algorithm.SHA3_512, Algorithm.fromHashAlgorithmIdentifier("sha3-512"))
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
            SecretKey("9779d9120642797f1747025d5b22b7ac607cab08e1758f2f3a46c8be1e25c53b8c6a8f58ffefa176".fromHex()).use { key ->
                Crypto.mac(
                    Algorithm.HMAC_SHA256,
                    key,
                    "b1689c2591eaf3c9e66070f8a77954ffb81749f1b00346f9dfe0b2ee905dcc288baf4a92de3f4001dd9f44c468c3d07d6c6ee82faceafc97c2fc0fc0601719d2dcd0aa2aec92d1b0ae933c65eb06a03c9c935c2bad0459810241347ab87e9f11adb30415424c6c7f5f22a003b8ab8de54f6ded0e3ab9245fa79568451dfa258e".fromHex()
                ).toHex()
            }
        )

        // First item with L=48 and T=48
        assertEquals(
            "7cf5a06156ad3de5405a5d261de90275f9bb36de45667f84d08fbcb308ca8f53a419b07deab3b5f8ea231c5b036f8875",
            SecretKey("5eab0dfa27311260d7bddcf77112b23d8b42eb7a5d72a5a318e1ba7e7927f0079dbb701317b87a3340e156dbcee28ec3a8d9".fromHex()).use { key ->
                Crypto.mac(
                    Algorithm.HMAC_SHA384,
                    key,
                    "f41380123ccbec4c527b425652641191e90a17d45e2f6206cf01b5edbe932d41cc8a2405c3195617da2f420535eed422ac6040d9cd65314224f023f3ba730d19db9844c71c329c8d9d73d04d8c5f244aea80488292dc803e772402e72d2e9f1baba5a6004f0006d822b0b2d65e9e4a302dd4f776b47a972250051a701fab2b70".fromHex()
                ).toHex()
            }
        )

        // First item with L=64 and T=64
        assertEquals(
            "33c511e9bc2307c62758df61125a980ee64cefebd90931cb91c13742d4714c06de4003faf3c41c06aefc638ad47b21906e6b104816b72de6269e045a1f4429d4",
            SecretKey("57c2eb677b5093b9e829ea4babb50bde55d0ad59fec34a618973802b2ad9b78e26b2045dda784df3ff90ae0f2cc51ce39cf54867320ac6f3ba2c6f0d72360480c96614ae66581f266c35fb79fd28774afd113fa5187eff9206d7cbe90dd8bf67c844e202".fromHex()).use { key ->
                Crypto.mac(
                    Algorithm.HMAC_SHA512,
                    key,
                    "2423dff48b312be864cb3490641f793d2b9fb68a7763b8e298c86f42245e4540eb01ae4d2d4500370b1886f23ca2cf9701704cad5bd21ba87b811daf7a854ea24a56565ced425b35e40e1acbebe03603e35dcf4a100e57218408a1d8dbcc3b99296cfea931efe3ebd8f719a6d9a15487b9ad67eafedf15559ca42445b0f9b42e".fromHex()
                ).toHex()
            }
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
                SecretKey("7fddb57453c241d03efbed3ac44e371c".fromHex()).use { key ->
                    Crypto.encrypt(
                        algorithm = Algorithm.A128GCM,
                        key = key,
                        nonce = "ee283a3fc75575e33efd4887".fromHex(),
                        messagePlaintext = "d5de42b461646c255c87bd2962d3b9a2".fromHex(),
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "69482957e6be5c54882d00314e0259cf" + "191e9f29bef63a26860c1e020a21137e",
                SecretKey("fbc0b4c56a714c83217b2d1bcadd2ed2e9efb0dcac6cc19f".fromHex()).use { key ->
                    Crypto.encrypt(
                        algorithm = Algorithm.A192GCM,
                        key = key,
                        nonce = "5f4b43e811da9c470d6a9b01".fromHex(),
                        messagePlaintext = "d2ae38c4375954835d75b8e4c2f9bbb4".fromHex(),
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "fa4362189661d163fcd6a56d8bf0405a" + "d636ac1bbedd5cc3ee727dc2ab4a9489",
                SecretKey("31bdadd96698c204aa9ce1448ea94ae1fb4a9a0b3c9d773b51bb1822666b8f22".fromHex()).use { key ->
                    Crypto.encrypt(
                        algorithm = Algorithm.A256GCM,
                        key = key,
                        nonce = "0d18e06c7c725ac9e362e1ce".fromHex(),
                        messagePlaintext = "2db5168e932556f8089a0622981d017d".fromHex(),
                    ).toHex()
                }
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
                SecretKey("c939cc13397c1d37de6ae0e1cb7c423c".fromHex()).use { key ->
                    Crypto.encrypt(
                        algorithm = Algorithm.A128GCM,
                        key = key,
                        nonce = "b3d8cc017cbb89b39e0f67e2".fromHex(),
                        messagePlaintext = "c3b3c41f113a31b73d9a5cd432103069".fromHex(),
                        aad = "24825602bd12a984e0092d3e448eda5f".fromHex()
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "a54b5da33fc1196a8ef31a5321bfcaeb" + "1c198086450ae1834dd6c2636796bce2",
                SecretKey("6f44f52c2f62dae4e8684bd2bc7d16ee7c557330305a790d".fromHex()).use { key ->
                    Crypto.encrypt(
                        algorithm = Algorithm.A192GCM,
                        key = key,
                        nonce = "9ae35825d7c7edc9a39a0732".fromHex(),
                        messagePlaintext = "37222d30895eb95884bbbbaee4d9cae1".fromHex(),
                        aad = "1b4236b846fc2a0f782881ba48a067e9".fromHex()
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "8995ae2e6df3dbf96fac7b7137bae67f" + "eca5aa77d51d4a0a14d9c51e1da474ab",
                SecretKey("92e11dcdaa866f5ce790fd24501f92509aacf4cb8b1339d50c9c1240935dd08b".fromHex()).use { key ->
                    Crypto.encrypt(
                        algorithm = Algorithm.A256GCM,
                        key = key,
                        nonce = "ac93a1a6145299bde902f21a".fromHex(),
                        messagePlaintext = "2d71bcfa914e4ac045b2aa60955fad24".fromHex(),
                        aad = "1e0889016f67601c8ebea4943bc23ad6".fromHex()
                    ).toHex()
                }
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
                SecretKey("e98b72a9881a84ca6b76e0f43e68647a".fromHex()).use { key ->
                    Crypto.decrypt(
                        algorithm = Algorithm.A128GCM,
                        key = key,
                        nonce = "8b23299fde174053f3d652ba".fromHex(),
                        messageCiphertext = ("5a3c1cf1985dbb8bed818036fdd5ab42" + "23c7ab0f952b7091cd324835043b5eb5").fromHex(),
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "99ae6f479b3004354ff18cd86c0b6efb",
                SecretKey("7a7c5b6a8a9ab5acae34a9f6e41f19a971f9c330023c0f0c".fromHex()).use { key ->
                    Crypto.decrypt(
                        algorithm = Algorithm.A192GCM,
                        key = key,
                        nonce = "aa4c38bf587f94f99fee77d5".fromHex(),
                        messageCiphertext = ("132ae95bd359c44aaefa6348632cafbd" + "19d7c7d5809ad6648110f22f272e7d72").fromHex(),
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "7789b41cb3ee548814ca0b388c10b343",
                SecretKey("4c8ebfe1444ec1b2d503c6986659af2c94fafe945f72c1e8486a5acfedb8a0f8".fromHex()).use { key ->
                    Crypto.decrypt(
                        algorithm = Algorithm.A256GCM,
                        key = key,
                        nonce = "473360e0ad24889959858995".fromHex(),
                        messageCiphertext = ("d2c78110ac7e8f107c0df0570bd7c90c" + "c26a379b6d98ef2852ead8ce83a833a7").fromHex(),
                    ).toHex()
                }
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
                SecretKey("816e39070410cf2184904da03ea5075a".fromHex()).use { key ->
                    Crypto.decrypt(
                        algorithm = Algorithm.A128GCM,
                        key = key,
                        nonce = "32c367a3362613b27fc3e67e".fromHex(),
                        messageCiphertext = ("552ebe012e7bcf90fcef712f8344e8f1" + "ecaae9fc68276a45ab0ca3cb9dd9539f").fromHex(),
                        aad = "f2a30728ed874ee02983c294435d3c16".fromHex()
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A192GCM)) {
            assertEquals(
                "7e3a29d47de8668a74c249ed96f8f0d2a2d5e05359c116cbdcad74b8c5ddf72c503ee12824b4039b9bf8f2b6aea9b7105f351e",
                SecretKey("497ac0078bdfa10c7db2d49f978b1ac0610bb40aa60b5b29".fromHex()).use { key ->
                    Crypto.decrypt(
                        algorithm = Algorithm.A192GCM,
                        key = key,
                        nonce = "e1608bae5ad218ae76633f9a".fromHex(),
                        messageCiphertext = ("225cddca92cf6438e69a4afcd8079a03cab65ae81f2631d14035a9656c6c68c699725fc374b909fab2709aab06037447e04cdb" + "a328f90905a4eb69d2c7be7942e7e24a").fromHex(),
                        aad = "fe71426fcb2cab1579a8adaee34fc790".fromHex()
                    ).toHex()
                }
            )
        }
        if (Crypto.supportedEncryptionAlgorithms.contains(Algorithm.A256GCM)) {
            assertEquals(
                "85fc3dfad9b5a8d3258e4fc44571bd3b",
                SecretKey("54e352ea1d84bfe64a1011096111fbe7668ad2203d902a01458c3bbd85bfce14".fromHex()).use { key ->
                    Crypto.decrypt(
                        algorithm = Algorithm.A256GCM,
                        key = key,
                        nonce = "df7c3bca00396d0c018495d9".fromHex(),
                        messageCiphertext = ("426e0efc693b7be1f3018db7ddbb7e4d" + "ee8257795be6a1164d7e1d2d6cac77a7").fromHex(),
                        aad = "7e968d71b50c1f11fd001f3fef49d045".fromHex()
                    ).toHex()
                }
            )
        }
    }

    @Test
    fun encryptDecrypt() = runTest {
        SecretKey("00000000000000000000000000000000".fromHex()).use { key ->
            val nonce = "000000000000000000000000".fromHex()
            val message = "Hello World".encodeToByteArray()
            val ciptherTextAndTag = Crypto.encrypt(Algorithm.A128GCM, key, nonce, message)
            val decryptedMessage = Crypto.decrypt(Algorithm.A128GCM, key, nonce, ciptherTextAndTag)
            assertContentEquals(decryptedMessage, message)
        }
    }

    @Test
    fun decryptionFailure() = runTest {
        SecretKey("00000000000000000000000000000000".fromHex()).use { key ->
            val nonce = "000000000000000000000000".fromHex()
            val message = "Hello World".encodeToByteArray()
            val ciptherTextAndTag = Crypto.encrypt(Algorithm.A128GCM, key, nonce, message)
            // Tamper with the cipher text to induce failure.
            ciptherTextAndTag[3] = ciptherTextAndTag[8].xor(0xff.toByte())
            assertFailsWith(IllegalStateException::class) {
                Crypto.decrypt(Algorithm.A128GCM, key, nonce, ciptherTextAndTag)
            }
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
    fun testChaCha20Poly1305() = runTest {
        if (Algorithm.CHACHA20_POLY1305 !in Crypto.supportedEncryptionAlgorithms) {
            return@runTest
        }
        // RFC 8439 Section 2.8.2 Test Vector
        val keyBytes = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".fromHex()
        val nonce = "070000004041424344454647".fromHex()
        val aad = "50515253c0c1c2c3c4c5c6c7".fromHex()
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it."
            ).encodeToByteArray()
        val expectedCiphertextAndTag = (
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691"
            ).fromHex()

        SecretKey(keyBytes).use { key ->
            val ciphertext = Crypto.encrypt(Algorithm.CHACHA20_POLY1305, key, nonce, plaintext, aad)
            assertContentEquals(expectedCiphertextAndTag, ciphertext)
            val decrypted = Crypto.decrypt(Algorithm.CHACHA20_POLY1305, key, nonce, ciphertext, aad)
            assertContentEquals(plaintext, decrypted)

            // Round-trip without AAD and empty plaintext
            val ctNoAad = Crypto.encrypt(Algorithm.CHACHA20_POLY1305, key, nonce, plaintext, null)
            assertContentEquals(plaintext, Crypto.decrypt(Algorithm.CHACHA20_POLY1305, key, nonce, ctNoAad, null))
            val ctEmpty = Crypto.encrypt(Algorithm.CHACHA20_POLY1305, key, nonce, byteArrayOf(), aad)
            assertEquals(16, ctEmpty.size)
            assertContentEquals(byteArrayOf(), Crypto.decrypt(Algorithm.CHACHA20_POLY1305, key, nonce, ctEmpty, aad))

            // Negative cases: tampered tag, tampered ciphertext, wrong AAD, truncated, bad nonce/key lengths
            val badTag = ciphertext.copyOf()
            badTag[badTag.size - 1] = badTag[badTag.size - 1] xor 0x01
            assertFailsWith<IllegalStateException> {
                Crypto.decrypt(Algorithm.CHACHA20_POLY1305, key, nonce, badTag, aad)
            }
            val badCt = ciphertext.copyOf()
            badCt[0] = badCt[0] xor 0x01
            assertFailsWith<IllegalStateException> {
                Crypto.decrypt(Algorithm.CHACHA20_POLY1305, key, nonce, badCt, aad)
            }
            assertFailsWith<IllegalStateException> {
                Crypto.decrypt(Algorithm.CHACHA20_POLY1305, key, nonce, ciphertext, "wrong".encodeToByteArray())
            }
            assertFailsWith<IllegalStateException> {
                Crypto.decrypt(Algorithm.CHACHA20_POLY1305, key, nonce, ciphertext.copyOf(15), aad)
            }
            assertFailsWith<IllegalArgumentException> {
                Crypto.encrypt(Algorithm.CHACHA20_POLY1305, key, ByteArray(16), plaintext, aad)
            }
        }
        SecretKey(ByteArray(16)).use { shortKey ->
            assertFailsWith<IllegalArgumentException> {
                Crypto.encrypt(Algorithm.CHACHA20_POLY1305, shortKey, nonce, plaintext, aad)
            }
        }
    }
}