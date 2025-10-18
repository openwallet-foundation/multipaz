package org.multipaz.crypto

import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals

class HkdfTests {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private data class TestVector(
        val alg: Algorithm,
        val ikmHex: String,
        val saltHex: String?,
        val infoHex: String,
        val length: Int,
        val prkHex: String,
        val okmHex: String,
    )

    private fun testAgainstTestVector(t: TestVector) {
        assertContentEquals(
            t.prkHex.fromHex(),
            Hkdf.extract(
                algorithm = t.alg,
                ikm = t.ikmHex.fromHex(),
                salt = t.saltHex?.fromHex()
            )
        )

        assertContentEquals(
            t.okmHex.fromHex(),
            Hkdf.deriveKey(
                algorithm = t.alg,
                ikm = t.ikmHex.fromHex(),
                salt = t.saltHex?.fromHex(),
                info = t.infoHex.fromHex(),
                length = t.length
            )
        )
    }

    @Test
    fun testAppendix_A_1() = testAgainstTestVector(
        TestVector(
            alg = Algorithm.HMAC_SHA256,
            ikmHex = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b",
            saltHex = "000102030405060708090a0b0c",
            infoHex = "f0f1f2f3f4f5f6f7f8f9",
            length = 42,
            prkHex = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            okmHex = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        )
    )

    @Test
    fun testAppendix_A_2() = testAgainstTestVector(
        TestVector(
            alg = Algorithm.HMAC_SHA256,
            ikmHex = "000102030405060708090a0b0c0d0e0f" +
                    "101112131415161718191a1b1c1d1e1f" +
                    "202122232425262728292a2b2c2d2e2f" +
                    "303132333435363738393a3b3c3d3e3f" +
                    "404142434445464748494a4b4c4d4e4f",
            saltHex = "606162636465666768696a6b6c6d6e6f" +
                    "707172737475767778797a7b7c7d7e7f" +
                    "808182838485868788898a8b8c8d8e8f" +
                    "909192939495969798999a9b9c9d9e9f" +
                    "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
            infoHex = "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                    "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                    "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                    "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                    "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            length = 82,
            prkHex = "06a6b88c5853361a06104c9ceb35b45c" +
                    "ef760014904671014a193f40c15fc244",
            okmHex = "b11e398dc80327a1c8e7f78c596a4934" +
                    "4f012eda2d4efad8a050cc4c19afa97c" +
                    "59045a99cac7827271cb41c65e590e09" +
                    "da3275600c2f09b8367793a9aca3db71" +
                    "cc30c58179ec3e87c14c01d5c1f3434f" +
                    "1d87"
        )
    )

    @Test
    fun testAppendix_A_3() = testAgainstTestVector(
        TestVector(
            alg = Algorithm.HMAC_SHA256,
            ikmHex = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b",
            saltHex = null,
            infoHex = "",
            length = 42,
            prkHex = "19ef24a32c717b167f33a91d6f648bdf" +
                    "96596776afdb6377ac434c1c293ccb04",
            okmHex = "8da4e775a563c18f715f802a063c5a31" +
                    "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                    "9d201395faa4b61a96c8"
        )
    )

    @Test
    fun testAppendix_A_4() = testAgainstTestVector(
        TestVector(
            alg = Algorithm.HMAC_INSECURE_SHA1,
            ikmHex = "0b0b0b0b0b0b0b0b0b0b0b",
            saltHex = "000102030405060708090a0b0c",
            infoHex = "f0f1f2f3f4f5f6f7f8f9",
            length = 42,
            prkHex = "9b6c18c432a7bf8f0e71c8eb88f4b30baa2ba243",
            okmHex = "085a01ea1b10f36933068b56efa5ad81" +
                    "a4f14b822f5b091568a9cdd4f155fda2" +
                    "c22e422478d305f3f896"
        )
    )

    @Test
    fun testAppendix_A_5() = testAgainstTestVector(
        TestVector(
            alg = Algorithm.HMAC_INSECURE_SHA1,
            ikmHex = "000102030405060708090a0b0c0d0e0f" +
                    "101112131415161718191a1b1c1d1e1f" +
                    "202122232425262728292a2b2c2d2e2f" +
                    "303132333435363738393a3b3c3d3e3f" +
                    "404142434445464748494a4b4c4d4e4f",
            saltHex = "606162636465666768696a6b6c6d6e6f" +
                    "707172737475767778797a7b7c7d7e7f" +
                    "808182838485868788898a8b8c8d8e8f" +
                    "909192939495969798999a9b9c9d9e9f" +
                    "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
            infoHex = "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                    "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                    "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                    "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                    "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            length = 82,
            prkHex = "8adae09a2a307059478d309b26c4115a224cfaf6",
            okmHex = "0bd770a74d1160f7c9f12cd5912a06eb" +
                    "ff6adcae899d92191fe4305673ba2ffe" +
                    "8fa3f1a4e5ad79f3f334b3b202b2173c" +
                    "486ea37ce3d397ed034c7f9dfeb15c5e" +
                    "927336d0441f4c4300e2cff0d0900b52" +
                    "d3b4"
        )
    )

    /* Marked as @Ignore out because an empty salt (different from `null` salt) will use an empty
     * key for the HMAC operation and this is not supported on JVM because it's trivially not
     * secure. Note: It does work on iOS and the test does pass there,
     */
    @Ignore
    @Test
    fun testAppendix_A_6() = testAgainstTestVector(
        TestVector(
            alg = Algorithm.HMAC_INSECURE_SHA1,
            ikmHex = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b",
            saltHex = "",
            infoHex = "",
            length = 42,
            prkHex = "da8c8a73c7fa77288ec6f5e7c297786aa0d32d01",
            okmHex = "0ac1af7002b3d761d1e55298da9d0506" +
                    "b9ae52057220a306e07b6b87e8df21d0" +
                    "ea00033de03984d34918"
        )
    )

    @Test
    fun testAppendix_A_7() = testAgainstTestVector(
        TestVector(
            alg = Algorithm.HMAC_INSECURE_SHA1,
            ikmHex = "0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c",
            saltHex = null,
            infoHex = "",
            length = 42,
            prkHex = "2adccada18779e7c2077ad2eb19d3f3e731385dd",
            okmHex = "2c91117204d745f3500d636a62f64f0a" +
                    "b3bae548aa53d423b0d1f27ebba6f5e5" +
                    "673a081d70cce7acfc48"
        )
    )
}