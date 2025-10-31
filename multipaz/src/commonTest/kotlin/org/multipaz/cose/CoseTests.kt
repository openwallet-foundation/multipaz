package org.multipaz.cose

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import kotlin.test.BeforeTest

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoseTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun coseKeyDecode() {
        // This checks we can decode the first key from the Example set in
        //
        //   https://datatracker.ietf.org/doc/html/rfc9052#name-public-keys
        //
        val x = "65eda5a12577c2bae829437fe338701a10aaa375e1bb5b5de108de439c08551d".fromHex()
        val y = "1e52ed75701163f7f9e40ddf9f341b3dc9ba860af7e0ca7ca7e9eecd0084d19c".fromHex()
        val id = "meriadoc.brandybuck@buckland.example".encodeToByteArray()
        val item = buildCborMap {
            put(-1, 1)
            put(-2, x)
            put(-3, y)
            put(1, 2)
            put(2, id)
        }
        val coseKey = item.asCoseKey
        assertEquals(Cose.COSE_KEY_TYPE_EC2, coseKey.keyType.asNumber)
        assertContentEquals(id, coseKey.labels[Cose.COSE_KEY_KID.toCoseLabel]!!.asBstr)
        assertContentEquals(x, coseKey.labels[Cose.COSE_KEY_PARAM_X.toCoseLabel]!!.asBstr)
        assertContentEquals(y, coseKey.labels[Cose.COSE_KEY_PARAM_Y.toCoseLabel]!!.asBstr)

        // Also check we can get an EcPublicKey from this
        val key = coseKey.ecPublicKey as EcPublicKeyDoubleCoordinate
        assertEquals(EcCurve.P256, key.curve)
        assertContentEquals(x, key.x)
        assertContentEquals(y, key.y)
    }

    @Test
    fun coseSign1() {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val dataToSign = "This is the data to sign.".encodeToByteArray()
        val coseSignature = Cose.coseSign1Sign(
            key,
            dataToSign,
            true,
            Algorithm.ES256,
            emptyMap(),
            emptyMap(),
        )

        Cose.coseSign1Check(
            key.publicKey,
            null,
            coseSignature,
            Algorithm.ES256
        )
    }

    @Test
    fun coseSign1TestVector() {
        // This is the COSE_Sign1 example from
        //
        //  https://datatracker.ietf.org/doc/html/rfc9052#appendix-C.2.1
        //
        // The key being signed with is the one with kid '11`, see
        //
        //  https://datatracker.ietf.org/doc/html/rfc9052#name-public-keys
        //
        val x = "bac5b11cad8f99f9c72b05cf4b9e26d244dc189f745228255a219a86d6a09eff".fromHex()
        val y = "20138bf82dc1b6d562be0fa54ab7804a3a64b6d72ccfed6b6fb6ed28bbfc117e".fromHex()
        val coseKey = buildCborMap {
            put(-1, 1)
            put(-2, x)
            put(-3, y)
            put(1, 2)
        }.asCoseKey
        val coseSign1 = CoseSign1(
            mutableMapOf(
                Pair(1L.toCoseLabel, (-7).toDataItem())
            ),
            mutableMapOf(
                Pair(11L.toCoseLabel, byteArrayOf(1, 1).toDataItem())
            ),
            ("8eb33e4ca31d1c465ab05aac34cc6b23d58fef5c083106c4" +
                    "d25a91aef0b0117e2af9a291aa32e14ab834dc56ed2a223444547e01f11d3b0916e5" +
                    "a4c345cacb36").fromHex(),
            "This is the content.".encodeToByteArray()
        )

        Cose.coseSign1Check(
            coseKey.ecPublicKey,
            null,
            coseSign1,
            Algorithm.ES256
        )
    }

    fun coseSign1_helper(curve: EcCurve) {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return
        }

        val privateKey = Crypto.createEcPrivateKey(curve)
        val signatureAlgorithm = privateKey.curve.defaultSigningAlgorithm
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                Cose.COSE_LABEL_ALG.toCoseLabel,
                signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem()
            )
        )
        val message = "Hello World".encodeToByteArray()
        val coseSignature = Cose.coseSign1Sign(
            privateKey,
            message,
            true,
            signatureAlgorithm,
            protectedHeaders,
            mapOf()
        )

        Cose.coseSign1Check(
            privateKey.publicKey,
            null,
            coseSignature,
            signatureAlgorithm
        )
    }

    @Test fun coseSign1_P256() = coseSign1_helper(EcCurve.P256)
    @Test fun coseSign1_P384() = coseSign1_helper(EcCurve.P384)
    @Test fun coseSign1_P521() = coseSign1_helper(EcCurve.P521)
    @Test fun coseSign1_BRAINPOOLP256R1() = coseSign1_helper(EcCurve.BRAINPOOLP256R1)
    @Test fun coseSign1_BRAINPOOLP320R1() = coseSign1_helper(EcCurve.BRAINPOOLP320R1)
    @Test fun coseSign1_BRAINPOOLP384R1() = coseSign1_helper(EcCurve.BRAINPOOLP384R1)
    @Test fun coseSign1_BRAINPOOLP512R1() = coseSign1_helper(EcCurve.BRAINPOOLP512R1)
    @Test fun coseSign1_ED25519() = coseSign1_helper(EcCurve.ED25519)
    @Test fun coseSign1_ED448() = coseSign1_helper(EcCurve.ED448)

    fun coseSign1_SecureArea_helper(algorithm: Algorithm) = runTest {
        assertTrue(algorithm.fullySpecified)

        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(algorithm.curve!!)) {
            println("Curve ${algorithm.curve} not supported on platform")
            return@runTest
        }

        val storage = EphemeralStorage()
        val sa = SoftwareSecureArea.create(storage)

        sa.createKey("testKey", CreateKeySettings(algorithm, ByteString()))
        val message = "Hello World".encodeToByteArray()

        // First check that coseSign1Sign() puts the EcCurve.defaultSigningAlgorithm in
        // the protected headed as COSE_LABEL_ALG
        val coseSignNoExplicitHeaderSet = Cose.coseSign1Sign(
            secureArea = sa,
            alias = "testKey",
            message = message,
            includeMessageInPayload = true,
            protectedHeaders = mapOf(),
            unprotectedHeaders = mapOf()
        )
        Cose.coseSign1Check(
            sa.getKeyInfo("testKey").publicKey,
            null,
            coseSignNoExplicitHeaderSet,
            algorithm
        )
        assertEquals(
            algorithm.curve.defaultSigningAlgorithm.coseAlgorithmIdentifier!!,
            coseSignNoExplicitHeaderSet.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        // Second, check we can override it if we e.g. want the fully-specified algorithm there
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Cose.COSE_LABEL_ALG.toCoseLabel to algorithm.coseAlgorithmIdentifier!!.toDataItem()
        )
        val coseSignExplicitHeaderSet = Cose.coseSign1Sign(
            secureArea = sa,
            alias = "testKey",
            message = message,
            includeMessageInPayload = true,
            protectedHeaders = protectedHeaders,
            unprotectedHeaders = mapOf(),
        )
        Cose.coseSign1Check(
            sa.getKeyInfo("testKey").publicKey,
            null,
            coseSignExplicitHeaderSet,
            algorithm
        )
        assertEquals(
            algorithm.coseAlgorithmIdentifier,
            coseSignExplicitHeaderSet.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )
    }

    @Test fun coseSign1_SecureArea_ESP256() = coseSign1_SecureArea_helper(Algorithm.ESP256)
    @Test fun coseSign1_SecureArea_ESP384() = coseSign1_SecureArea_helper(Algorithm.ESP384)
    @Test fun coseSign1_SecureArea_ESP521() = coseSign1_SecureArea_helper(Algorithm.ESP512)
    @Test fun coseSign1_SecureArea_ESB256() = coseSign1_SecureArea_helper(Algorithm.ESB256)
    @Test fun coseSign1_SecureArea_ESB320() = coseSign1_SecureArea_helper(Algorithm.ESB320)
    @Test fun coseSign1_SecureArea_ESB384() = coseSign1_SecureArea_helper(Algorithm.ESB384)
    @Test fun coseSign1_SecureArea_ESB512() = coseSign1_SecureArea_helper(Algorithm.ESB512)
    @Test fun coseSign1_SecureArea_ED25519() = coseSign1_SecureArea_helper(Algorithm.ED25519)
    @Test fun coseSign1_SecureArea_ED448() = coseSign1_SecureArea_helper(Algorithm.ED448)

    fun coseSign1_SigningKey_helper(algorithm: Algorithm) = runTest {
        assertTrue(algorithm.fullySpecified)

        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(algorithm.curve!!)) {
            println("Curve ${algorithm.curve} not supported on platform")
            return@runTest
        }

        val storage = EphemeralStorage()
        val sa = SoftwareSecureArea.create(storage)

        sa.createKey("testKey", CreateKeySettings(algorithm, ByteString()))
        val message = "Hello World".encodeToByteArray()

        // First check that coseSign1Sign() puts the EcCurve.defaultSigningAlgorithm in
        // the protected headed as COSE_LABEL_ALG
        val coseSignNoExplicitHeaderSet = Cose.coseSign1Sign(
            signingKey = AsymmetricKey.anonymous(
                secureArea = sa,
                alias = "testKey"
            ),
            message = message,
            includeMessageInPayload = true,
            protectedHeaders = mapOf(),
            unprotectedHeaders = mapOf()
        )
        Cose.coseSign1Check(
            sa.getKeyInfo("testKey").publicKey,
            null,
            coseSignNoExplicitHeaderSet,
            algorithm
        )
        assertEquals(
            algorithm.coseAlgorithmIdentifier,
            coseSignNoExplicitHeaderSet.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        // Second, check we can override it if we e.g. want the fully-specified algorithm there
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Cose.COSE_LABEL_ALG.toCoseLabel to algorithm.coseAlgorithmIdentifier!!.toDataItem()
        )
        val coseSignExplicitHeaderSet = Cose.coseSign1Sign(
            signingKey = AsymmetricKey.anonymous(sa, "testKey"),
            message = message,
            includeMessageInPayload = true,
            protectedHeaders = protectedHeaders,
            unprotectedHeaders = mapOf(),
        )
        Cose.coseSign1Check(
            sa.getKeyInfo("testKey").publicKey,
            null,
            coseSignExplicitHeaderSet,
            algorithm
        )
        assertEquals(
            algorithm.coseAlgorithmIdentifier!!,
            coseSignExplicitHeaderSet.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )
    }

    @Test fun coseSign1_SigningKey_ESP256() = coseSign1_SigningKey_helper(Algorithm.ESP256)
    @Test fun coseSign1_SigningKey_ESP384() = coseSign1_SigningKey_helper(Algorithm.ESP384)
    @Test fun coseSign1_SigningKey_ESP521() = coseSign1_SigningKey_helper(Algorithm.ESP512)
    @Test fun coseSign1_SigningKey_ESB256() = coseSign1_SigningKey_helper(Algorithm.ESB256)
    @Test fun coseSign1_SigningKey_ESB320() = coseSign1_SigningKey_helper(Algorithm.ESB320)
    @Test fun coseSign1_SigningKey_ESB384() = coseSign1_SigningKey_helper(Algorithm.ESB384)
    @Test fun coseSign1_SigningKey_ESB512() = coseSign1_SigningKey_helper(Algorithm.ESB512)
    @Test fun coseSign1_SigningKey_ED25519() = coseSign1_SigningKey_helper(Algorithm.ED25519)
    @Test fun coseSign1_SigningKey_ED448() = coseSign1_SigningKey_helper(Algorithm.ED448)

    @Test
    fun coseSign1X5Chain() {
        // This is a test vector from ISO/IEC 18013-5:2021 Annex D.5.2 Issuer data authentication
        val issuerAuth =
            "8443a10126a118215901f3308201ef30820195a00302010202143c4416eed784f3b413e48" +
                    "f56f075abfa6d87eb84300a06082a8648ce3d04030230233114301206035504030c0b7574" +
                    "6f7069612069616361310b3009060355040613025553301e170d323031303031303030303" +
                    "0305a170d3231313030313030303030305a30213112301006035504030c0975746f706961" +
                    "206473310b30090603550406130255533059301306072a8648ce3d020106082a8648ce3d0" +
                    "3010703420004ace7ab7340e5d9648c5a72a9a6f56745c7aad436a03a43efea77b5fa7b88" +
                    "f0197d57d8983e1b37d3a539f4d588365e38cbbf5b94d68c547b5bc8731dcd2f146ba381a" +
                    "83081a5301e0603551d120417301581136578616d706c65406578616d706c652e636f6d30" +
                    "1c0603551d1f041530133011a00fa00d820b6578616d706c652e636f6d301d0603551d0e0" +
                    "416041414e29017a6c35621ffc7a686b7b72db06cd12351301f0603551d23041830168014" +
                    "54fa2383a04c28e0d930792261c80c4881d2c00b300e0603551d0f0101ff0404030207803" +
                    "0150603551d250101ff040b3009060728818c5d050102300a06082a8648ce3d0403020348" +
                    "00304502210097717ab9016740c8d7bcdaa494a62c053bbdecce1383c1aca72ad08dbc04c" +
                    "bb202203bad859c13a63c6d1ad67d814d43e2425caf90d422422c04a8ee0304c0d3a68d59" +
                    "03a2d81859039da66776657273696f6e63312e306f646967657374416c676f726974686d6" +
                    "75348412d3235366c76616c756544696765737473a2716f72672e69736f2e31383031332e" +
                    "352e31ad00582075167333b47b6c2bfb86eccc1f438cf57af055371ac55e1e359e20f254a" +
                    "dcebf01582067e539d6139ebd131aef441b445645dd831b2b375b390ca5ef6279b205ed45" +
                    "710258203394372ddb78053f36d5d869780e61eda313d44a392092ad8e0527a2fbfe55ae0" +
                    "358202e35ad3c4e514bb67b1a9db51ce74e4cb9b7146e41ac52dac9ce86b8613db5550458" +
                    "20ea5c3304bb7c4a8dcb51c4c13b65264f845541341342093cca786e058fac2d59055820f" +
                    "ae487f68b7a0e87a749774e56e9e1dc3a8ec7b77e490d21f0e1d3475661aa1d0658207d83" +
                    "e507ae77db815de4d803b88555d0511d894c897439f5774056416a1c7533075820f0549a1" +
                    "45f1cf75cbeeffa881d4857dd438d627cf32174b1731c4c38e12ca936085820b68c8afcb2" +
                    "aaf7c581411d2877def155be2eb121a42bc9ba5b7312377e068f660958200b3587d1dd0c2" +
                    "a07a35bfb120d99a0abfb5df56865bb7fa15cc8b56a66df6e0c0a5820c98a170cf36e11ab" +
                    "b724e98a75a5343dfa2b6ed3df2ecfbb8ef2ee55dd41c8810b5820b57dd036782f7b14c6a" +
                    "30faaaae6ccd5054ce88bdfa51a016ba75eda1edea9480c5820651f8736b18480fe252a03" +
                    "224ea087b5d10ca5485146c67c74ac4ec3112d4c3a746f72672e69736f2e31383031332e3" +
                    "52e312e5553a4005820d80b83d25173c484c5640610ff1a31c949c1d934bf4cf7f18d5223" +
                    "b15dd4f21c0158204d80e1e2e4fb246d97895427ce7000bb59bb24c8cd003ecf94bf35bbd" +
                    "2917e340258208b331f3b685bca372e85351a25c9484ab7afcdf0d2233105511f778d98c2" +
                    "f544035820c343af1bd1690715439161aba73702c474abf992b20c9fb55c36a336ebe01a8" +
                    "76d6465766963654b6579496e666fa1696465766963654b6579a40102200121582096313d" +
                    "6c63e24e3372742bfdb1a33ba2c897dcd68ab8c753e4fbd48dca6b7f9a2258201fb3269ed" +
                    "d418857de1b39a4e4a44b92fa484caa722c228288f01d0c03a2c3d667646f635479706575" +
                    "6f72672e69736f2e31383031332e352e312e6d444c6c76616c6964697479496e666fa3667" +
                    "369676e6564c074323032302d31302d30315431333a33303a30325a6976616c696446726f" +
                    "6dc074323032302d31302d30315431333a33303a30325a6a76616c6964556e74696cc0743" +
                    "23032312d31302d30315431333a33303a30325a584059e64205df1e2f708dd6db0847aed7" +
                    "9fc7c0201d80fa55badcaf2e1bcf5902e1e5a62e4832044b890ad85aa53f129134775d733" +
                    "754d7cb7a413766aeff13cb2e"
        val coseSign1 = Cbor.decode(issuerAuth.fromHex()).asCoseSign1

        val signatureAlgorithm = coseSign1.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber
        assertEquals(signatureAlgorithm, Algorithm.ES256.coseAlgorithmIdentifier!!.toLong())

        val x5chain = coseSign1.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]!!.asX509CertChain
        assertEquals(1, x5chain.certificates.size)
        val cert = x5chain.certificates[0]

        assertEquals("C=US,CN=utopia iaca", cert.issuer.name)
        assertEquals("C=US,CN=utopia ds", cert.subject.name)

        val x = "ace7ab7340e5d9648c5a72a9a6f56745c7aad436a03a43efea77b5fa7b88f019".fromHex()
        val y = "7d57d8983e1b37d3a539f4d588365e38cbbf5b94d68c547b5bc8731dcd2f146b".fromHex()
        val publicKey = EcPublicKeyDoubleCoordinate(EcCurve.P256, x, y)
        assertEquals(publicKey, cert.ecPublicKey)
    }
}
