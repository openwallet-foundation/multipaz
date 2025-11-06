package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.Hpke.serialize
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HpkeTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private fun parseKey(
        curve: EcCurve,
        encodedPriv: ByteArray,
        encodedPub: ByteArray
    ): EcPrivateKey {
        when (curve) {
            EcCurve.X25519,
            EcCurve.X448 -> {
                return EcPrivateKeyOkp(
                    curve = curve,
                    d = encodedPriv,
                    x = encodedPub,
                )
            }
            else -> {
                val pub = EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(
                    curve = curve,
                    encoded = encodedPub
                )
                return EcPrivateKeyDoubleCoordinate(
                    curve = curve,
                    d = encodedPriv,
                    x = pub.x,
                    y = pub.y
                )
            }
        }
    }

    @Test
    fun roundTrip() = runTest {
        val receiver = Crypto.createEcPrivateKey(EcCurve.P256)
        val plainText = "Hello World".encodeToByteArray()
        val aad = "123".encodeToByteArray()
        val info = "abc".encodeToByteArray()

        val encrypter = Hpke.getEncrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPublicKey = receiver.publicKey,
            info = info
        )
        val cipherText = encrypter.encrypt(
            plaintext = plainText,
            aad = aad
        )

        val decrypter = Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = AsymmetricKey.AnonymousExplicit(receiver),
            encapsulatedKey = encrypter.encapsulatedKey.toByteArray(),
            info = info
        )
        val decryptedText = decrypter.decrypt(
            ciphertext = cipherText,
            aad = aad
        )

        assertContentEquals(decryptedText, plainText)
    }

    data class TestDataSeqValues(
        val aadHex: String,
        val ptHex: String,
        val ctHex: String
    )

    data class ExportedValues(
        val exporterContext: String,
        val length: Int,
        val exportedValue: String,
    )

    data class TestData(
        val cipherSuite: Hpke.CipherSuite,
        val infoHex: String,
        val encPrivHex: String,
        val encPubHex: String,
        val receiverPrivHex: String,
        val receiverPubHex: String,
        val psk: String?,
        val pskId: String?,
        val authPrivHex: String?,
        val authPubHex: String?,
        val sequencesToCheck: Map<Int, TestDataSeqValues>,
        val exportedValuesToCheck: List<ExportedValues>,
    )

    // Test vector for mode_base for DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_1_1() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736",
                encPubHex = "37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431",
                receiverPrivHex = "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8",
                receiverPubHex = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d",
                psk = null,
                pskId = null,
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a9" +
                                "6d8770ac83d07bea87e13c512a"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "af2d7e9ac9ae7e270f46ba1f975be53c09f8d875bdc8535458c2494e8a6eab25" +
                                "1c03d0c22a56b8ca42c2063b84"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "498dfcabd92e8acedc281e85af1cb4e3e31c7dc394a1ca20e173cb7251649158" +
                                "8d96a19ad4a683518973dcc180"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "583bd32bc67a5994bb8ceaca813d369bca7b2a42408cddef5e22f880b631215a" +
                                "09fc0012bc69fccaa251c0246d"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "7175db9717964058640a3a11fb9007941a5d1757fda1a6935c805c21af32505b" +
                                "f106deefec4a49ac38d71c9e0a"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "957f9800542b0b8891badb026d79cc54597cb2d225b54c00c5238c25d05c30e3" +
                                "fbeda97d2e0e1aba483a2df9f2"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "3853fe2b4035195a573ffc53856e77058e15d9ea064de3e59f4961d0095250ee"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "2e8f0b54673c7029649d4eb9d5e33bf1872cf76d623ff164ac185da9e88c21a5"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "e9e43065102c3836401bed8c3c3c75ae46be1639869391d62c61f1ec7af54931"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_1_2() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "463426a9ffb42bb17dbe6044b9abd1d4e4d95f9041cef0e99d7824eef2b6f588",
                encPubHex = "0ad0950d9fb9588e59690b74f1237ecdf1d775cd60be2eca57af5a4b0471c91b",
                receiverPrivHex = "c5eb01eb457fe6c6f57577c5413b931550a162c71a03ac8d196babbd4e5ce0fd",
                receiverPubHex = "9fed7e8c17387560e92cc6462a68049657246a09bfa8ade7aefe589672016366",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "e52c6fed7f758d0cf7145689f21bc1be6ec9ea097fef4e959440012f4feb73fb" +
                                "611b946199e681f4cfc34db8ea"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "49f3b19b28a9ea9f43e8c71204c00d4a490ee7f61387b6719db765e948123b45" +
                                "b61633ef059ba22cd62437c8ba"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "257ca6a08473dc851fde45afd598cc83e326ddd0abe1ef23baa3baa4dd8cde99" +
                                "fce2c1e8ce687b0b47ead1adc9"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "a71d73a2cd8128fcccbd328b9684d70096e073b59b40b55e6419c9c68ae21069" +
                                "c847e2a70f5d8fb821ce3dfb1c"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "55f84b030b7f7197f7d7d552365b6b932df5ec1abacd30241cb4bc4ccea27bd2" +
                                "b518766adfa0fb1b71170e9392"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "c5bf246d4a790a12dcc9eed5eae525081e6fb541d5849e9ce8abd92a3bc15517" +
                                "76bea16b4a518f23e237c14b59"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "dff17af354c8b41673567db6259fd6029967b4e1aad13023c2ae5df8f4f43bf6"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "6a847261d8207fe596befb52928463881ab493da345b10e1dcc645e3b94e2d95"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "8aff52b45a1be3a734bc7a41e20b4e055ad4c4d22104b0c20285a7c4302401cd"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_1_3() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "ff4442ef24fbc3c1ff86375b0be1e77e88a0de1e79b30896d73411c5ff4c3518",
                encPubHex = "23fb952571a14a25e3d678140cd0e5eb47a0961bb18afcf85896e5453c312e76",
                receiverPrivHex = "fdea67cf831f1ca98d8e27b1f6abeb5b7745e9d35348b80fa407ff6958f9137e",
                receiverPubHex = "1632d5c2f71c2b38d0a8fcc359355200caa8b1ffdf28618080466c909cb69b2e",
                psk = null,
                pskId = null,
                authPrivHex = "dc4a146313cce60a278a5323d321f051c5707e9c45ba21a3479fecdf76fc69dd",
                authPubHex = "8b0c70873dc5aecb7f9ee4e62406a397b350e57012be45cf53b7105ae731790b",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "5fd92cc9d46dbf8943e72a07e42f363ed5f721212cd90bcfd072bfd9f44e06b8" +
                                "0fd17824947496e21b680c141b"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "d3736bb256c19bfa93d79e8f80b7971262cb7c887e35c26370cfed62254369a1" +
                                "b52e3d505b79dd699f002bc8ed"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "122175cfd5678e04894e4ff8789e85dd381df48dcaf970d52057df2c9acc3b12" +
                                "1313a2bfeaa986050f82d93645"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "dae12318660cf963c7bcbef0f39d64de3bf178cf9e585e756654043cc5059873" +
                                "bc8af190b72afc43d1e0135ada"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "55d53d85fe4d9e1e97903101eab0b4865ef20cef28765a47f840ff99625b7d69" +
                                "dee927df1defa66a036fc58ff2"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "42fa248a0e67ccca688f2b1d13ba4ba84755acf764bd797c8f7ba3b9b1dc3330" +
                                "326f8d172fef6003c79ec72319"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "28c70088017d70c896a8420f04702c5a321d9cbf0279fba899b59e51bac72c85"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "25dfc004b0892be1888c3914977aa9c9bbaf2c7471708a49e1195af48a6f29ce"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "5a0131813abc9a522cad678eb6bafaabc43389934adb8097d23c5ff68059eb64"
                    ),
                ),
            )
        )
    }

    @Test
    fun testVectorsAppendix_A_1_4() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "14de82a5897b613616a00c39b87429df35bc2b426bcfd73febcb45e903490768",
                encPubHex = "820818d3c23993492cc5623ab437a48a0a7ca3e9639c140fe1e33811eb844b7c",
                receiverPrivHex = "cb29a95649dc5656c2d054c1aa0d3df0493155e9d5da6d7e344ed8b6a64a9423",
                receiverPubHex = "1d11a3cd247ae48e901939659bd4d79b6b959e1f3e7d66663fbc9412dd4e0976",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = "fc1c87d2f3832adb178b431fce2ac77c7ca2fd680f3406c77b5ecdf818b119f4",
                authPubHex = "2bfb2eb18fcad1af0e4f99142a1c474ae74e21b9425fc5c589382c69b50cc57e",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "a84c64df1e11d8fd11450039d4fe64ff0c8a99fca0bd72c2d4c3e0400bc14a40" +
                                "f27e45e141a24001697737533e"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "4d19303b848f424fc3c3beca249b2c6de0a34083b8e909b6aa4c3688505c05ff" +
                                "e0c8f57a0a4c5ab9da127435d9"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "0c085a365fbfa63409943b00a3127abce6e45991bc653f182a80120868fc507e" +
                                "9e4d5e37bcc384fc8f14153b24"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "000a3cd3a3523bf7d9796830b1cd987e841a8bae6561ebb6791a3f0e34e89a4f" +
                                "b539faeee3428b8bbc082d2c1a"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "576d39dd2d4cc77d1a14a51d5c5f9d5e77586c3d8d2ab33bdec6379e28ce5c50" +
                                "2f0b1cbd09047cf9eb9269bb52"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "13239bab72e25e9fd5bb09695d23c90a24595158b99127505c8a9ff9f127e0d6" +
                                "57f71af59d67d4f4971da028f9"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "08f7e20644bb9b8af54ad66d2067457c5f9fcb2a23d9f6cb4445c0797b330067"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "52e51ff7d436557ced5265ff8b94ce69cf7583f49cdb374e6aad801fc063b010"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "a30c20370c026bbea4dca51cb63761695132d342bae33a6a11527d3e7679436d"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_3_1() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb",
                encPubHex = "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b32" +
                        "5ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4",
                receiverPrivHex = "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2",
                receiverPubHex = "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f70" +
                        "6a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0",
                psk = null,
                pskId = null,
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f" +
                                "9076ac232e3ab2523f39513434"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "fa6f037b47fc21826b610172ca9637e82d6e5801eb31cbd3748271affd4ecb06" +
                                "646e0329cbdf3c3cd655b28e82"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "895cabfac50ce6c6eb02ffe6c048bf53b7f7be9a91fc559402cbc5b8dcaeb52b" +
                                "2ccc93e466c28fb55fed7a7fec"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "8787491ee8df99bc99a246c4b3216d3d57ab5076e18fa27133f520703bc70ec9" +
                                "99dd36ce042e44f0c3169a6a8f"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "2ad71c85bf3f45c6eca301426289854b31448bcf8a8ccb1deef3ebd87f60848a" +
                                "a53c538c30a4dac71d619ee2cd"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "10f179686aa2caec1758c8e554513f16472bd0a11e2a907dde0b212cbe87d74f" +
                                "367f8ffe5e41cd3e9962a6afb2"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "5e9bc3d236e1911d95e65b576a8a86d478fb827e8bdfe77b741b289890490d4d"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "6cff87658931bda83dc857e6353efe4987a201b849658d9b047aab4cf216e796"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "d8f1ea7942adbba7412c6d431c62d01371ea476b823eb697e1f6e6cae1dab85a"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_psk for DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_3_2() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "57427244f6cc016cddf1c19c8973b4060aa13579b4c067fd5d93a5d74e32a90f",
                encPubHex = "04305d35563527bce037773d79a13deabed0e8e7cde61eecee403496959e89" +
                        "e4d0ca701726696d1485137ccb5341b3c1c7aaee90a4a02449725e744b1193b53b5f",
                receiverPrivHex = "438d8bcef33b89e0e9ae5eb0957c353c25a94584b0dd59c991372a75b43cb661",
                receiverPubHex = "040d97419ae99f13007a93996648b2674e5260a8ebd2b822e84899cd52d874" +
                        "46ea394ca76223b76639eccdf00e1967db10ade37db4e7db476261fcc8df97c5ffd1",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "90c4deb5b75318530194e4bb62f890b019b1397bbf9d0d6eb918890e1fb2be1a" +
                                "c2603193b60a49c2126b75d0eb"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "9e223384a3620f4a75b5a52f546b7262d8826dea18db5a365feb8b997180b22d" +
                                "72dc1287f7089a1073a7102c27"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "adf9f6000773035023be7d415e13f84c1cb32a24339a32eb81df02be9ddc6abc" +
                                "880dd81cceb7c1d0c7781465b2"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "1f4cc9b7013d65511b1f69c050b7bd8bbd5a5c16ece82b238fec4f30ba2400e7" +
                                "ca8ee482ac5253cffb5c3dc577"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "cdc541253111ed7a424eea5134dc14fc5e8293ab3b537668b8656789628e4589" +
                                "4e5bb873c968e3b7cdcbb654a4"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "faf985208858b1253b97b60aecd28bc18737b58d1242370e7703ec33b73a4c31" +
                                "a1afee300e349adef9015bbbfd"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "a115a59bf4dd8dc49332d6a0093af8efca1bcbfd3627d850173f5c4a55d0c185"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "4517eaede0669b16aac7c92d5762dd459c301fa10e02237cd5aeb9be969430c4"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "164e02144d44b607a7722e58b0f4156e67c0c2874d74cf71da6ca48a4cbdc5e0"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_auth for DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_3_3() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "6b8de0873aed0c1b2d09b8c7ed54cbf24fdf1dfc7a47fa501f918810642d7b91",
                encPubHex = "042224f3ea800f7ec55c03f29fc9865f6ee27004f818fcbdc6dc68932c1e52" +
                        "e15b79e264a98f2c535ef06745f3d308624414153b22c7332bc1e691cb4af4d53454",
                receiverPrivHex = "d929ab4be2e59f6954d6bedd93e638f02d4046cef21115b00cdda2acb2a4440e",
                receiverPubHex = "04423e363e1cd54ce7b7573110ac121399acbc9ed815fae03b72ffbd4c18b0" +
                        "1836835c5a09513f28fc971b7266cfde2e96afe84bb0f266920e82c4f53b36e1a78d",
                psk = null,
                pskId = null,
                authPrivHex = "1120ac99fb1fccc1e8230502d245719d1b217fe20505c7648795139d177f0de9",
                authPubHex = "04a817a0902bf28e036d66add5d544cc3a0457eab150f104285df1e293b5c1" +
                        "0eef8651213e43d9cd9086c80b309df22cf37609f58c1127f7607e85f210b2804f73",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "82ffc8c44760db691a07c5627e5fc2c08e7a86979ee79b494a17cc3405446ac2" +
                                "bdb8f265db4a099ed3289ffe19"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "b0a705a54532c7b4f5907de51c13dffe1e08d55ee9ba59686114b05945494d96" +
                                "725b239468f1229e3966aa1250"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "8dc805680e3271a801790833ed74473710157645584f06d1b53ad439078d880b" +
                                "23e25256663178271c80ee8b7c"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "04c8f7aae1584b61aa5816382cb0b834a5d744f420e6dffb5ddcec633a21b8b3" +
                                "472820930c1ea9258b035937a2"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "4a319462eaedee37248b4d985f64f4f863d31913fe9e30b6e13136053b69fe5d" +
                                "70853c84c60a84bb5495d5a678"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "28e874512f8940fafc7d06135e7589f6b4198bc0f3a1c64702e72c9e6abaf9f0" +
                                "5cb0d2f11b03a517898815c934"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "837e49c3ff629250c8d80d3c3fb957725ed481e59e2feb57afd9fe9a8c7c4497"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "594213f9018d614b82007a7021c3135bda7b380da4acd9ab27165c508640dbda"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "14fe634f95ca0d86e15247cca7de7ba9b73c9b9deb6437e1c832daf7291b79d5"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_auth_psk for DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_3_4() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "36f771e411cf9cf72f0701ef2b991ce9743645b472e835fe234fb4d6eb2ff5a0",
                encPubHex = "046a1de3fc26a3d43f4e4ba97dbe24f7e99181136129c48fbe872d4743e2b1" +
                        "31357ed4f29a7b317dc22509c7b00991ae990bf65f8b236700c82ab7c11a84511401",
                receiverPrivHex = "bdf4e2e587afdf0930644a0c45053889ebcadeca662d7c755a353d5b4e2a8394",
                receiverPubHex = "04d824d7e897897c172ac8a9e862e4bd820133b8d090a9b188b8233a64dfbc" +
                        "5f725aa0aa52c8462ab7c9188f1c4872f0c99087a867e8a773a13df48a627058e1b3",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = "b0ed8721db6185435898650f7a677affce925aba7975a582653c4cb13c72d240",
                authPubHex = "049f158c750e55d8d5ad13ede66cf6e79801634b7acadcad72044eac2ae1d0" +
                        "480069133d6488bf73863fa988c4ba8bde1c2e948b761274802b4d8012af4f13af9e",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "b9f36d58d9eb101629a3e5a7b63d2ee4af42b3644209ab37e0a272d44365407d" +
                                "b8e655c72e4fa46f4ff81b9246"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "51788c4e5d56276771032749d015d3eea651af0c7bb8e3da669effffed299ea1" +
                                "f641df621af65579c10fc09736"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "3b5a2be002e7b29927f06442947e1cf709b9f8508b03823127387223d7127034" +
                                "71c266efc355f1bc2036f3027c"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "8ddbf1242fe5c7d61e1675496f3bfdb4d90205b3dfbc1b12aab41395d71a8211" +
                                "8e095c484103107cf4face5123"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "6de25ceadeaec572fbaa25eda2558b73c383fe55106abaec24d518ef6724a7ce" +
                                "698f83ecdc53e640fe214d2f42"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "f380e19d291e12c5e378b51feb5cd50f6d00df6cb2af8393794c4df342126c2e" +
                                "29633fe7e8ce49587531affd4d"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "595ce0eff405d4b3bb1d08308d70a4e77226ce11766e0a94c4fdb5d90025c978"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "110472ee0ae328f57ef7332a9886a1992d2c45b9b8d5abc9424ff68630f7d38d"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "18ee4d001a9d83a4c67e76f88dd747766576cac438723bad0700a910a4d717e6"
                    ),
                ),
            )
        )
    }

    @Test
    fun testVectorsAppendix_A_4_1() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA512_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "2292bf14bb6e15b8c81a0f45b7a6e93e32d830e48cca702e0affcfb4d07e1b5c",
                encPubHex = "0493ed86735bdfb978cc055c98b45695ad7ce61ce748f4dd63c525a3b8d53a" +
                        "15565c6897888070070c1579db1f86aaa56deb8297e64db7e8924e72866f9a472580",
                receiverPrivHex = "3ac8530ad1b01885960fab38cf3cdc4f7aef121eaa239f222623614b4079fb38",
                receiverPubHex = "04085aa5b665dc3826f9650ccbcc471be268c8ada866422f739e2d531d4a88" +
                        "18a9466bc6b449357096232919ec4fe9070ccbac4aac30f4a1a53efcf7af90610edd",
                psk = null,
                pskId = null,
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "d3cf4984931484a080f74c1bb2a6782700dc1fef9abe8442e44a6f09044c8890" +
                                "7200b332003543754eb51917ba"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "d14414555a47269dfead9fbf26abb303365e40709a4ed16eaefe1f2070f1ddeb" +
                                "1bdd94d9e41186f124e0acc62d"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "9bba136cade5c4069707ba91a61932e2cbedda2d9c7bdc33515aa01dd0e0f7e9" +
                                "d3579bf4016dec37da4aafa800"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "a531c0655342be013bf32112951f8df1da643602f1866749519f5dcb09cc6843" +
                                "2579de305a77e6864e862a7600"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "be5da649469efbad0fb950366a82a73fefeda5f652ec7d3731fac6c4ffa21a70" +
                                "04d2ab8a04e13621bd3629547d"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "62092672f5328a0dde095e57435edf7457ace60b26ee44c9291110ec135cb0e1" +
                                "4b85594e4fea11247d937deb62"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "a32186b8946f61aeead1c093fe614945f85833b165b28c46bf271abf16b57208"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "84998b304a0ea2f11809398755f0abd5f9d2c141d1822def79dd15c194803c2a"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "93fb9411430b2cfa2cf0bed448c46922a5be9beff20e2e621df7e4655852edbc"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(P-256, HKDF-SHA256), HKDF-SHA512, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_4_2() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA512_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "a5901ff7d6931959c2755382ea40a4869b1dec3694ed3b009dda2d77dd488f18",
                encPubHex = "04a307934180ad5287f95525fe5bc6244285d7273c15e061f0f2efb211c350" +
                        "57f3079f6e0abae200992610b25f48b63aacfcb669106ddee8aa023feed301901371",
                receiverPrivHex = "bc6f0b5e22429e5ff47d5969003f3cae0f4fec50e23602e880038364f33b8522",
                receiverPubHex = "043f5266fba0742db649e1043102b8a5afd114465156719cea90373229aabd" +
                        "d84d7f45dabfc1f55664b888a7e86d594853a6cccdc9b189b57839cbbe3b90b55873",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "57624b6e320d4aba0afd11f548780772932f502e2ba2a8068676b2a0d3b5129a" +
                                "45b9faa88de39e8306da41d4cc"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "159d6b4c24bacaf2f5049b7863536d8f3ffede76302dace42080820fa51925d4" +
                                "e1c72a64f87b14291a3057e00a"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "bd24140859c99bf0055075e9c460032581dd1726d52cf980d308e9b20083ca62" +
                                "e700b17892bcf7fa82bac751d0"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "93ddd55f82e9aaaa3cfc06840575f09d80160b20538125c2549932977d1238dd" +
                                "e8126a4a91118faf8632f62cb8"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "377a98a3c34bf716581b05a6b3fdc257f245856384d5f2241c8840571c52f5c8" +
                                "5c21138a4a81655edab8fe227d"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "cc161f5a179831d456d119d2f2c19a6817289c75d1c61cd37ac8a450acd9efba" +
                                "02e0ac00d128c17855931ff69a"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "8158bea21a6700d37022bb7802866edca30ebf2078273757b656ef7fc2e428cf"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "6a348ba6e0e72bb3ef22479214a139ef8dac57be34509a61087a12565473da8d"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "2f6d4f7a18ec48de1ef4469f596aada4afdf6d79b037ed3c07e0118f8723bffc"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(P-256, HKDF-SHA256), HKDF-SHA512, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_4_3() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA512_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "93cddd5288e7ef4884c8fe321d075df01501b993ff49ffab8184116f39b3c655",
                encPubHex = "04fec59fa9f76f5d0f6c1660bb179cb314ed97953c53a60ab38f8e6ace60fd" +
                        "59178084d0dd66e0f79172992d4ddb2e91172ce24949bcebfff158dcc417f2c6e9c6",
                receiverPrivHex = "1ea4484be482bf25fdb2ed39e6a02ed9156b3e57dfb18dff82e4a048de990236",
                receiverPubHex = "04378bad519aab406e04d0e5608bcca809c02d6afd2272d4dd03e9357bd0ee" +
                        "e8adf84c8deba3155c9cf9506d1d4c8bfefe3cf033a75716cc3cc07295100ec96276",
                psk = null,
                pskId = null,
                authPrivHex = "02b266d66919f7b08f42ae0e7d97af4ca98b2dae3043bb7e0740ccadc1957579",
                authPubHex = "0404d3c1f9fca22eb4a6d326125f0814c35593b1da8ea0d11a640730b215a2" +
                        "59b9b98a34ad17e21617d19fe1d4fa39a4828bfdb306b729ec51c543caca3b2d9529",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "2480179d880b5f458154b8bfe3c7e8732332de84aabf06fc440f6b31f169e154" +
                                "157fa9eb44f2fa4d7b38a9236e"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "10cd81e3a816d29942b602a92884348171a31cbd0f042c3057c65cd93c540943" +
                                "a5b05115bd520c09281061935b"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "920743a88d8cf6a09e1a3098e8be8edd09db136e9d543f215924043af8c7410f" +
                                "68ce6aa64fd2b1a176e7f6b3fd"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "6b11380fcc708fc8589effb5b5e0394cbd441fa5e240b5500522150ca8265d65" +
                                "ff55479405af936e2349119dcd"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "d084eca50e7554bb97ba34c4482dfe32c9a2b7f3ab009c2d1b68ecbf97bee2d2" +
                                "8cd94b6c829b96361f2701772d"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "247da592cc4ce834a94de2c79f5730ee49342470a021e4a4bc2bb77c53b17413" +
                                "e94d94f57b4fdaedcf97cfe7b1"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "f03fbc82f321a0ab4840e487cb75d07aafd8e6f68485e4f7ff72b2f55ff24ad6"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "1ce0cadec0a8f060f4b5070c8f8888dcdfefc2e35819df0cd559928a11ff0891"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "70c405c707102fd0041ea716090753be47d68d238b111d542846bd0d84ba907c"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(P-256, HKDF-SHA256), HKDF-SHA512, AES-128-GCM
    @Test
    fun testVectorsAppendix_A_4_4() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA512_AES_128_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "778f2254ae5d661d5c7fca8c4a7495a25bd13f26258e459159f3899df0de76c1",
                encPubHex = "04801740f4b1b35823f7fb2930eac2efc8c4893f34ba111c0bb976e3c7d5dc" +
                        "0aef5a7ef0bf4057949a140285f774f1efc53b3860936b92279a11b68395d898d138",
                receiverPrivHex = "00510a70fde67af487c093234fc4215c1cdec09579c4b30cc8e48cb530414d0e",
                receiverPubHex = "04a4ca7af2fc2cce48edbf2f1700983e927743a4e85bb5035ad562043e25d9" +
                        "a111cbf6f7385fac55edc5c9d2ca6ed351a5643de95c36748e11dbec98730f4d43e9",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = "d743b20821e6326f7a26684a4beed7088b35e392114480ca9f6c325079dcf10b",
                authPubHex = "04b59a4157a9720eb749c95f842a5e3e8acdccbe834426d405509ac3191e23" +
                        "f2165b5bb1f07a6240dd567703ae75e13182ee0f69fc102145cdb5abf681ff126d60",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "840669634db51e28df54f189329c1b727fd303ae413f003020aff5e26276aaa9" +
                                "10fc4296828cb9d862c2fd7d16"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "d4680a48158d9a75fd09355878d6e33997a36ee01d4a8f22032b22373b795a94" +
                                "1b7b9c5205ff99e0ff284beef4"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "c45eb6597de2bac929a0f5d404ba9d2dc1ea031880930f1fd7a283f0a0cbebb3" +
                                "5eac1a9ee0d1225f5e0f181571"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "4ee2482ad8d7d1e9b7e651c78b6ca26d3c5314d0711710ca62c2fd8bb8996d7d" +
                                "8727c157538d5493da696b61f8"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "65596b731df010c76a915c6271a438056ce65696459432eeafdae7b4cadb6290" +
                                "dd61e68edd4e40b659d2a8cbcc"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "9f659482ebc52f8303f9eac75656d807ec38ce2e50c72e3078cd13d86b30e3f8" +
                                "90690a873277620f8a6a42d836"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "c8c917e137a616d3d4e4c9fcd9c50202f366cb0d37862376bc79f9b72e8a8db9"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "33a5d4df232777008a06d0684f23bb891cfaef702f653c8601b6ad4d08dddddf"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "bed80f2e54f1285895c4a3f3b3625e6206f78f1ed329a0cfb5864f7c139b3c6a"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(P-521, HKDF-SHA512), HKDF-SHA512, AES-256-GCM
    @Test
    fun testVectorsAppendix_A_6_1() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_256_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "014784c692da35df6ecde98ee43ac425dbdd0969c0c72b42f2e708ab9d5354" +
                        "15a8569bdacfcc0a114c85b8e3f26acf4d68115f8c91a66178cdbd03b7bcc5291e37" +
                        "4b",
                encPubHex = "040138b385ca16bb0d5fa0c0665fbbd7e69e3ee29f63991d3e9b5fa740aab8" +
                        "900aaeed46ed73a49055758425a0ce36507c54b29cc5b85a5cee6bae0cf1c21f2731" +
                        "ece2013dc3fb7c8d21654bb161b463962ca19e8c654ff24c94dd2898de12051f1ed0" +
                        "692237fb02b2f8d1dc1c73e9b366b529eb436e98a996ee522aef863dd5739d2f29b0",
                receiverPrivHex = "01462680369ae375e4b3791070a7458ed527842f6a98a79ff5e0d4cbde83c2" +
                        "7196a3916956655523a6a2556a7af62c5cadabe2ef9da3760bb21e005202f7b24628" +
                        "47",
                receiverPubHex = "0401b45498c1714e2dce167d3caf162e45e0642afc7ed435df7902ccae0e84" +
                        "ba0f7d373f646b7738bbbdca11ed91bdeae3cdcba3301f2457be452f271fa6837580" +
                        "e661012af49583a62e48d44bed350c7118c0d8dc861c238c72a2bda17f64704f464b" +
                        "57338e7f40b60959480c0e58e6559b190d81663ed816e523b6b6a418f66d2451ec64",
                psk = null,
                pskId = null,
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "170f8beddfe949b75ef9c387e201baf4132fa7374593dfafa90768788b7b2b20" +
                                "0aafcc6d80ea4c795a7c5b841a"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "d9ee248e220ca24ac00bbbe7e221a832e4f7fa64c4fbab3945b6f3af0c5ecd5e" +
                                "16815b328be4954a05fd352256"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "142cf1e02d1f58d9285f2af7dcfa44f7c3f2d15c73d460c48c6e0e506a3144ba" +
                                "e35284e7e221105b61d24e1c7a"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "3bb3a5a07100e5a12805327bf3b152df728b1c1be75a9fd2cb2bf5eac0cca1fb" +
                                "80addb37eb2a32938c7268e3e5"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "4f268d0930f8d50b8fd9d0f26657ba25b5cb08b308c92e33382f369c768b558e" +
                                "113ac95a4c70dd60909ad1adc7"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "dbbfc44ae037864e75f136e8b4b4123351d480e6619ae0e0ae437f036f2f8f1e" +
                                "f677686323977a1ccbb4b4f16a"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "05e2e5bd9f0c30832b80a279ff211cc65eceb0d97001524085d609ead60d0412"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "fca69744bb537f5b7a1596dbf34eaa8d84bf2e3ee7f1a155d41bd3624aa92b63"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "f389beaac6fcf6c0d9376e20f97e364f0609a88f1bc76d7328e9104df8477013"
                    ),
                ),
            )
        )
    }

    @Test
    fun testVectorsAppendix_A_6_2() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_256_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "012e5cfe0daf5fe2a1cd617f4c4bae7c86f1f527b3207f115e262a98cc6526" +
                        "8ec88cb8645aec73b7aa0a472d0292502d1078e762646e0c093cf873243d12c39915" +
                        "f6",
                encPubHex = "040085eff0835cc84351f32471d32aa453cdc1f6418eaaecf1c2824210eb1d" +
                        "48d0768b368110fab21407c324b8bb4bec63f042cfa4d0868d19b760eb4beba1bff7" +
                        "93b30036d2c614d55730bd2a40c718f9466faf4d5f8170d22b6df98dfe0c067d02b3" +
                        "49ae4a142e0c03418f0a1479ff78a3db07ae2c2e89e5840f712c174ba2118e90fdcb",
                receiverPrivHex = "011bafd9c7a52e3e71afbdab0d2f31b03d998a0dc875dd7555c63560e142bd" +
                        "e264428de03379863b4ec6138f813fa009927dc5d15f62314c56d4e7ff2b485753eb" +
                        "72",
                receiverPubHex = "04006917e049a2be7e1482759fb067ddb94e9c4f7f5976f655088dec452466" +
                        "14ff924ed3b385fc2986c0ecc39d14f907bf837d7306aada59dd5889086125ecd038" +
                        "ead400603394b5d81f89ebfd556a898cc1d6a027e143d199d3db845cb91c5289fb26" +
                        "c5ff80832935b0e8dd08d37c6185a6f77683347e472d1edb6daa6bd7652fea628fae",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "de69e9d943a5d0b70be3359a19f317bd9aca4a2ebb4332a39bcdfc97d5fe62f3" +
                                "a77702f4822c3be531aa7843a1"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "77a16162831f90de350fea9152cfc685ecfa10acb4f7994f41aed43fa5431f23" +
                                "82d078ec88baec53943984553e"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "f1d48d09f126b9003b4c7d3fe6779c7c92173188a2bb7465ba43d899a6398a33" +
                                "3914d2bb19fd769d53f3ec7336"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "829b11c082b0178082cd595be6d73742a4721b9ac05f8d2ef8a7704a53022d82" +
                                "bd0d8571f578c5c13b99eccff8"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "a3ee291e20f37021e82df14d41f3fbe98b27c43b318a36cacd8471a3b1051ab1" +
                                "2ee055b62ded95b72a63199a3f"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "eecc2173ce1ac14b27ee67041e90ed50b7809926e55861a579949c07f6d26137" +
                                "bf9cf0d097f60b5fd2fbf348ec"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "62691f0f971e34de38370bff24deb5a7d40ab628093d304be60946afcdb3a936"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "76083c6d1b6809da088584674327b39488eaf665f0731151128452e04ce81bff"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "0c7cfc0976e25ae7680cf909ae2de1859cd9b679610a14bec40d69b91785b2f6"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(P-521, HKDF-SHA512), HKDF-SHA512, AES-256-GCM
    @Test
    fun testVectorsAppendix_A_6_3() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_256_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "0185f03560de87bb2c543ef03607f3c33ac09980000de25eabe3b224312946" +
                        "330d2e65d192d3b4aa46ca92fc5ca50736b624402d95f6a80dc04d1f10ae95171372" +
                        "61",
                encPubHex = "04017de12ede7f72cb101dab36a111265c97b3654816dcd6183f809d4b3d11" +
                        "1fe759497f8aefdc5dbb40d3e6d21db15bdc60f15f2a420761bcaeef73b891c2b117" +
                        "e9cf01e29320b799bbc86afdc5ea97d941ea1c5bd5ebeeac7a784b3bab524746f3e6" +
                        "40ec26ee1bd91255f9330d974f845084637ee0e6fe9f505c5b87c86a4e1a6c3096dd",
                receiverPrivHex = "013ef326940998544a899e15e1726548ff43bbdb23a8587aa3bef9d1b85733" +
                        "8d87287df5667037b519d6a14661e9503cfc95a154d93566d8c84e95ce93ad05293a" +
                        "0b",
                receiverPubHex = "04007d419b8834e7513d0e7cc66424a136ec5e11395ab353da324e3586673e" +
                        "e73d53ab34f30a0b42a92d054d0db321b80f6217e655e304f72793767c4231785c4a" +
                        "4a6e008f31b93b7a4f2b8cd12e5fe5a0523dc71353c66cbdad51c86b9e0bdfcd9a45" +
                        "698f2dab1809ab1b0f88f54227232c858accc44d9a8d41775ac026341564a2d749f4",
                psk = null,
                pskId = null,
                authPrivHex = "001018584599625ff9953b9305849850d5e34bd789d4b81101139662fbea8b" +
                        "6508ddb9d019b0d692e737f66beae3f1f783e744202aaf6fea01506c27287e359fe7" +
                        "76",
                authPubHex = "04015cc3636632ea9a3879e43240beae5d15a44fba819282fac26a19c989fa" +
                        "fdd0f330b8521dff7dc393101b018c1e65b07be9f5fc9a28a1f450d6a541ee0d7622" +
                        "1133001e8f0f6a05ab79f9b9bb9ccce142a453d59c5abebb5674839d935a3ca1a3fb" +
                        "c328539a60b3bc3c05fed22838584a726b9c176796cad0169ba4093332cbd2dc3a9f",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "0116aeb3a1c405c61b1ce47600b7ecd11d89b9c08c408b7e2d1e00a4d64696d1" +
                                "2e6881dc61688209a8207427f9"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "37ece0cf6741f443e9d73b9966dc0b228499bb21fbf313948327231e70a18380" +
                                "e080529c0267f399ba7c539cc6"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "d17b045cac963e45d55fd3692ec17f100df66ac06d91f3b6af8efa7ed3c88955" +
                                "50eb753bc801fe4bd27005b4bd"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "50c523ae7c64cada96abea16ddf67a73d2914ec86a4cedb31a7e6257f7553ed2" +
                                "44626ef79a57198192b2323384"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "53d422295a6ce8fcc51e6f69e252e7195e64abf49252f347d8c25534f1865a6a" +
                                "17d949c65ce618ddc7d816111f"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "0dfcfc22ea768880b4160fec27ab10c75fb27766c6bb97aed373a9b6eae35d31" +
                                "afb08257401075cbb602ac5abb"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "8d78748d632f95b8ce0c67d70f4ad1757e61e872b5941e146986804b3990154b"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "80a4753230900ea785b6c80775092801fe91183746479f9b04c305e1db9d1f4d"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "620b176d737cf366bcc20d96adb54ec156978220879b67923689e6dca36210ed"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(P-521, HKDF-SHA512), HKDF-SHA512, AES-256-GCM
    @Test
    fun testVectorsAppendix_A_6_4() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_P521_HKDF_SHA512_HKDF_SHA512_AES_256_GCM,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "003430af19716084efeced1241bb1a5625b6c826f11ef31649095eb2795261" +
                        "9e36f62a79ea28001ac452fb20ddfbb66e62c6c0b1be03c0d28c97794a1fb638207a" +
                        "83",
                encPubHex = "04000a5096a6e6e002c83517b494bfc2e36bfb8632fae8068362852b70d0ff" +
                        "71e560b15aff96741ecffb63d8ac3090c3769679009ac59a99a1feb4713c5f090fc0" +
                        "dbed01ad73c45d29d369e36744e9ed37d12f80700c16d816485655169a5dd66e4ddf" +
                        "27f2acffe0f56f7f77ea2b473b4bf0518b975d9527009a3d14e5a4957e3e8a9074f8",
                receiverPrivHex = "0053c0bc8c1db4e9e5c3e3158bfdd7fc716aef12db13c8515adf821dd692ba" +
                        "3ca53041029128ee19c8556e345c4bcb840bb7fd789f97fe10f17f0e2c6c25280728" +
                        "43",
                receiverPubHex = "0401655b5d3b7cfafaba30851d25edc44c6dd17d99410efbed8591303b4dbe" +
                        "ea8cb1045d5255f9a60384c3bbd4a3386ae6e6fab341dc1f8db0eed5f0ab1aaac6d7" +
                        "838e00dadf8a1c2c64b48f89c633721e88369e54104b31368f26e35d04a442b0b428" +
                        "510fb23caada686add16492f333b0f7ba74c391d779b788df2c38d7a7f4778009d91",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = "003f64675fc8914ec9e2b3ecf13585b26dbaf3d5d805042ba487a5070b8c5a" +
                        "c1d39b17e2161771cc1b4d0a3ba6e866f4ea4808684b56af2a49b5e5111146d45d93" +
                        "26",
                authPubHex = "040013761e97007293d57de70962876b4926f69a52680b4714bee1d4236aa9" +
                        "6c19b840c57e80b14e91258f0a350e3f7ba59f3f091633aede4c7ec4fa8918323aa4" +
                        "5d5901076dec8eeb22899fda9ab9e1960003ff0535f53c02c40f2ae4cdc6070a3870" +
                        "b85b4bdd0bb77f1f889e7ee51f465a308f08c666ad3407f75dc046b2ff5a24dbe2ed",
                sequencesToCheck = mapOf(
                    0 to TestDataSeqValues(
                        aadHex = "436f756e742d30",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "942a2a92e0817cf032ce61abccf4f3a7c5d21b794ed943227e07b7df2d6dd92c" +
                                "9b8a9371949e65cca262448ab7"
                    ),
                    1 to TestDataSeqValues(
                        aadHex = "436f756e742d31",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "c0a83b5ec3d7933a090f681717290337b4fede5bfaa0a40ec29f93acad742888" +
                                "a1513c649104c391c78d1d7f29"
                    ),
                    2 to TestDataSeqValues(
                        aadHex = "436f756e742d32",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "2847b2e0ce0b9da8fca7b0e81ff389d1682ee1b388ed09579b145058b5af6a93" +
                                "a85dd50d9f417dc88f2c785312"
                    ),
                    4 to TestDataSeqValues(
                        aadHex = "436f756e742d34",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "fbd9948ab9ac4a9cb9e295c07273600e6a111a3a89241d3e2178f39d532a2ec5" +
                                "c15b9b0c6937ac84c88e0ca76f"
                    ),
                    255 to TestDataSeqValues(
                        aadHex = "436f756e742d323535",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "63113a870131b567db8f39a11b4541eafbd2d3cf3a9bf9e5c1cfcb41e52f9027" +
                                "310b82a4868215959131694d15"
                    ),
                    256 to TestDataSeqValues(
                        aadHex = "436f756e742d323536",
                        ptHex = "4265617574792069732074727574682c20747275746820626561757479",
                        ctHex = "24f9d8dadd2107376ccd143f70f9bafcd2b21d8117d45ff327e9a78f603a3260" +
                                "6e42a6a8bdb57a852591d20907"
                    ),
                ),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "a39502ef5ca116aa1317bd9583dd52f15b0502b71d900fc8a622d19623d0cb5d"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "749eda112c4cfdd6671d84595f12cd13198fc3ef93ed72369178f344fe6e09c3"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "f8b4e72cefbff4ca6c4eabb8c0383287082cfcbb953d900aed4959afd0017095"
                    ),
                ),
            )
        )
    }

    @Test
    fun testVectorsAppendix_A_7_1() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_EXPORT_ONLY,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "095182b502f1f91f63ba584c7c3ec473d617b8b4c2cec3fad5af7fa6748165ed",
                encPubHex = "e5e8f9bfff6c2f29791fc351d2c25ce1299aa5eaca78a757c0b4fb4bcd830918",
                receiverPrivHex = "33d196c830a12f9ac65d6e565a590d80f04ee9b19c83c87f2c170d972a812848",
                receiverPubHex = "194141ca6c3c3beb4792cd97ba0ea1faff09d98435012345766ee33aae2d7664",
                psk = null,
                pskId = null,
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = emptyMap(),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "7a36221bd56d50fb51ee65edfd98d06a23c4dc87085aa5866cb7087244bd2a36"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "d5535b87099c6c3ce80dc112a2671c6ec8e811a2f284f948cec6dd1708ee33f0"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "ffaabc85a776136ca0c378e5d084c9140ab552b78f039d2e8775f26efff4c70e"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, Export-Only AEAD

    @Test
    fun testVectorsAppendix_A_7_2() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_EXPORT_ONLY,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "1d72396121a6a826549776ef1a9d2f3a2907fc6a38902fa4e401afdb0392e627",
                encPubHex = "d3805a97cbcd5f08babd21221d3e6b362a700572d14f9bbeb94ec078d051ae3d",
                receiverPrivHex = "98f304d4ecb312689690b113973c61ffe0aa7c13f2fbe365e48f3ed09e5a6a0c",
                receiverPubHex = "d53af36ea5f58f8868bb4a1333ed4cc47e7a63b0040eb54c77b9c8ec456da824",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = null,
                authPubHex = null,
                sequencesToCheck = emptyMap(),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "be6c76955334376aa23e936be013ba8bbae90ae74ed995c1c6157e6f08dd5316"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "1721ed2aa852f84d44ad020c2e2be4e2e6375098bf48775a533505fd56a3f416"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "7c9d79876a288507b81a5a52365a7d39cc0fa3f07e34172984f96fec07c44cba"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, Export-Only AEAD
    @Test
    fun testVectorsAppendix_A_7_3() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_EXPORT_ONLY,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "83d3f217071bbf600ba6f081f6e4005d27b97c8001f55cb5ff6ea3bbea1d9295",
                encPubHex = "5ac1671a55c5c3875a8afe74664aa8bc68830be9ded0c5f633cd96400e8b5c05",
                receiverPrivHex = "ed88cda0e91ca5da64b6ad7fc34a10f096fa92f0b9ceff9d2c55124304ed8b4a",
                receiverPubHex = "ffd7ac24694cb17939d95feb7c4c6539bb31621deb9b96d715a64abdd9d14b10",
                psk = null,
                pskId = null,
                authPrivHex = "c85f136e06d72d28314f0e34b10aadc8d297e9d71d45a5662c2b7c3b9f9f9405",
                authPubHex = "89eb1feae431159a5250c5186f72a15962c8d0debd20a8389d8b6e4996e14306",
                sequencesToCheck = emptyMap(),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "83c1bac00a45ed4cb6bd8a6007d2ce4ec501f55e485c5642bd01bf6b6d7d6f0a"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "08a1d1ad2af3ef5bc40232a64f920650eb9b1034fac3892f729f7949621bf06e"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "ff3b0e37a9954247fea53f251b799e2edd35aac7152c5795751a3da424feca73"
                    ),
                ),
            )
        )
    }

    // Test vector for mode_base for DDHKEM(X25519, HKDF-SHA256), HKDF-SHA256, Export-Only AEAD
    @Test
    fun testVectorsAppendix_A_7_4() = runTest {
        checkTestVector(
            TestData(
                cipherSuite = Hpke.CipherSuite.DHKEM_X25519_HKDF_SHA256_EXPORT_ONLY,
                infoHex = "4f6465206f6e2061204772656369616e2055726e",
                encPrivHex = "a2b43f5c67d0d560ee04de0122c765ea5165e328410844db97f74595761bbb81",
                encPubHex = "81cbf4bd7eee97dd0b600252a1c964ea186846252abb340be47087cc78f3d87c",
                receiverPrivHex = "c4962a7f97d773a47bdf40db4b01dc6a56797c9e0deaab45f4ea3aa9b1d72904",
                receiverPubHex = "f47cd9d6993d2e2234eb122b425accfb486ee80f89607b087094e9f413253c2d",
                psk = "0247fd33b913760fa1fa51e1892d9f307fbe65eb171e8132c2af18555a738b82",
                pskId = "456e6e796e20447572696e206172616e204d6f726961",
                authPrivHex = "6175b2830c5743dff5b7568a7e20edb1fe477fb0487ca21d6433365be90234d0",
                authPubHex = "29a5bf3867a6128bbdf8e070abe7fe70ca5e07b629eba5819af73810ee20112f",
                sequencesToCheck = emptyMap(),
                exportedValuesToCheck = listOf(
                    ExportedValues(
                        exporterContext = "",
                        length = 32,
                        exportedValue = "dafd8beb94c5802535c22ff4c1af8946c98df2c417e187c6ccafe45335810b58"
                    ),
                    ExportedValues(
                        exporterContext = "00",
                        length = 32,
                        exportedValue = "7346bb0b56caf457bcc1aa63c1b97d9834644bdacac8f72dbbe3463e4e46b0dd"
                    ),
                    ExportedValues(
                        exporterContext = "54657374436f6e74657874",
                        length = 32,
                        exportedValue = "84f3466bd5a03bde6444324e63d7560e7ac790da4e5bbab01e7c4d575728c34a"
                    ),
                ),
            )
        )
    }

    suspend fun checkTestVector(td: TestData) {
        val curve = td.cipherSuite.kem.curve
        if(!Crypto.supportedCurves.contains(curve)){
            println("Skipping test: Curve $curve not supported on this platform")
            return
        }

        val info = td.infoHex.fromHex()

        val encapsulatedKey = parseKey(
            curve = td.cipherSuite.kem.curve,
            encodedPriv = td.encPrivHex.fromHex(),
            encodedPub = td.encPubHex.fromHex()
        )
        val receiverKey = parseKey(
            curve = td.cipherSuite.kem.curve,
            encodedPriv = td.receiverPrivHex.fromHex(),
            encodedPub = td.receiverPubHex.fromHex()
        )

        val authKey = td.authPrivHex?.let {
            parseKey(
                curve = td.cipherSuite.kem.curve,
                encodedPriv = td.authPrivHex.fromHex(),
                encodedPub = td.authPubHex!!.fromHex()
            )
        }

        val encrypter = Hpke.getEncrypterInternal(
            cipherSuite = td.cipherSuite,
            receiverPublicKey = receiverKey.publicKey,
            info = info,
            encapsulatedKey = encapsulatedKey,
            psk = td.psk?.fromHex(),
            pskId = td.pskId?.fromHex(),
            authKey = authKey?.let { AsymmetricKey.AnonymousExplicit(it) }
        )

        val decrypter = Hpke.getDecrypter(
            cipherSuite = td.cipherSuite,
            receiverPrivateKey = AsymmetricKey.AnonymousExplicit(receiverKey),
            encapsulatedKey = encapsulatedKey.publicKey.serialize(),
            info = info,
            psk = td.psk?.fromHex(),
            pskId = td.pskId?.fromHex(),
            authKey = authKey?.publicKey
        )

        for ((seq, seqData) in td.sequencesToCheck.entries) {
            encrypter.seq = seq.toLong()
            val ciphertext = encrypter.encrypt(
                plaintext = seqData.ptHex.fromHex(),
                aad = seqData.aadHex.fromHex()
            )
            assertEquals(seqData.ctHex, ciphertext.toHex())

            decrypter.seq = seq.toLong()
            val decrypted = decrypter.decrypt(
                ciphertext = seqData.ctHex.fromHex(),
                aad = seqData.aadHex.fromHex()
            )
            assertEquals(seqData.ptHex, decrypted.toHex())
        }

        for (e in td.exportedValuesToCheck) {
            val exportedValueEncrypter = encrypter.exportSecret(
                context = e.exporterContext.fromHex(),
                length = e.length
            )
            assertEquals(e.exportedValue, exportedValueEncrypter.toHex())

            val exportedValueDecrypter = decrypter.exportSecret(
                context = e.exporterContext.fromHex(),
                length = e.length
            )
            assertEquals(e.exportedValue, exportedValueDecrypter.toHex())
        }
    }

}