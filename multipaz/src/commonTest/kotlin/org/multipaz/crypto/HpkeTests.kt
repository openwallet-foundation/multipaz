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

    suspend fun checkTestVector(td: TestData) {
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