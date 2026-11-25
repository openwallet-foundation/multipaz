package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.toCoseLabel
import org.multipaz.cbor.toDataItem
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HybridKemTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testRoundTripsAllHybridKems() = runTest {
        val cases = listOf(
            Triple(Algorithm.MLKEM768_P256, 1249, 1153),
            Triple(Algorithm.MLKEM1024_P384, 1665, 1665),
            Triple(Algorithm.MLKEM768_X25519, 1216, 1120),
        )
        for ((alg, expectedPkSize, expectedCtSize) in cases) {
            if (alg !in Crypto.supportedHybridKemAlgorithms) {
                continue
            }
            val privKey = Crypto.createHybridKemPrivateKey(alg)
            val pubKey = privKey.publicKey
            assertEquals(alg, privKey.algorithm)
            assertEquals(alg, pubKey.algorithm)
            assertEquals(expectedPkSize, pubKey.encoded.size)
            assertEquals(Crypto.supportsSeedBackedHybridKem, privKey.hasSeed)
            if (privKey.hasSeed) {
                assertEquals(HybridKem.SEED_SIZE, privKey.encoded.size)
            }

            val kemResult = Crypto.kemEncapsulate(pubKey)
            assertEquals(32, kemResult.sharedSecret.size)
            assertEquals(expectedCtSize, kemResult.ciphertext.size)

            val decapsulated = Crypto.kemDecapsulate(privKey, kemResult.ciphertext)
            assertContentEquals(kemResult.sharedSecret.encoded, decapsulated.encoded)

            val dup = privKey.duplicate()
            if (privKey.hasSeed) {
                // Reconstruct from raw seed and verify identical decapsulation
                val copyFromSeed = HybridKemPrivateKey(alg, privKey.encoded, pubKey)
                val decapsSeed = Crypto.kemDecapsulate(copyFromSeed, kemResult.ciphertext)
                assertContentEquals(kemResult.sharedSecret.encoded, decapsSeed.encoded)
                decapsSeed.close()
                copyFromSeed.close()
            }
            privKey.close()
            val decapsDup = Crypto.kemDecapsulate(dup, kemResult.ciphertext)
            assertContentEquals(kemResult.sharedSecret.encoded, decapsDup.encoded)

            kemResult.close()
            decapsulated.close()
            decapsDup.close()
            dup.close()
        }
    }

    @Test
    fun testSerializationRoundTrips() = runTest {
        for (alg in listOf(Algorithm.MLKEM768_P256, Algorithm.MLKEM1024_P384, Algorithm.MLKEM768_X25519)) {
            if (alg !in Crypto.supportedHybridKemAlgorithms) {
                continue
            }
            val privKey = Crypto.createHybridKemPrivateKey(alg)
            val pubKey = privKey.publicKey

            assertFailsWith<UnsupportedOperationException> { pubKey.toPem() }
            assertFailsWith<UnsupportedOperationException> { privKey.toPem() }

            // COSE Key roundtrips
            val pubCose = pubKey.toCoseKey()
            assertEquals(pubKey, HybridKemPublicKey.fromCoseKey(pubCose))
            assertEquals(pubKey, PublicKey.fromCoseKey(pubCose))
            assertEquals(pubKey, pubCose.hybridKemPublicKey)
            assertEquals(pubKey, pubCose.publicKey)

            // JWK & thumbprint roundtrips
            val pubJwk = pubKey.toJwk()
            assertEquals(pubKey, HybridKemPublicKey.fromJwk(pubJwk))
            assertEquals(pubKey, PublicKey.fromJwk(pubJwk))
            val canonicalThumbJson =
                """{"alg":"${alg.joseAlgorithmIdentifier}","kty":"AKP",""" +
                    """"pub":"${pubKey.encoded.toByteArray().toBase64Url()}"}"""
            assertContentEquals(
                Crypto.digest(Algorithm.SHA256, canonicalThumbJson.encodeToByteArray()),
                pubKey.toJwkThumbprint(Algorithm.SHA256).toByteArray()
            )

            // Public DataItem roundtrip
            val pubDataItem = pubKey.toDataItem()
            assertEquals(pubKey, HybridKemPublicKey.fromDataItem(pubDataItem))
            assertEquals(pubKey, PublicKey.fromDataItem(pubDataItem))

            if (privKey.hasSeed) {
                val privCose = privKey.toCoseKey()
                assertEquals(privKey, HybridKemPrivateKey.fromCoseKey(privCose))
                // Decoding twice ensures caller's CoseKey buffer was not zeroed in place
                assertEquals(privKey, PrivateKey.fromCoseKey(privCose))
                assertEquals(privKey, privCose.hybridKemPrivateKey)
                assertEquals(privKey, privCose.privateKey)

                val privJwk = privKey.toJwk()
                assertEquals(privKey, HybridKemPrivateKey.fromJwk(privJwk))
                assertEquals(privKey, PrivateKey.fromJwk(privJwk))

                val privDataItem = privKey.toDataItem()
                assertEquals(privKey, HybridKemPrivateKey.fromDataItem(privDataItem))
                assertEquals(privKey, PrivateKey.fromDataItem(privDataItem))
            } else {
                assertFailsWith<UnsupportedOperationException> { privKey.encoded }
                assertFailsWith<UnsupportedOperationException> { privKey.toCoseKey() }
                assertFailsWith<UnsupportedOperationException> { privKey.toJwk() }
            }

            privKey.close()
        }
    }

    @Test
    fun testSoftwareSecureAreaAndAsymmetricKey() = runTest {
        val ks = SoftwareSecureArea.create(EphemeralStorage())
        for (alg in listOf(Algorithm.MLKEM768_P256, Algorithm.MLKEM1024_P384, Algorithm.MLKEM768_X25519)) {
            if (alg !in Crypto.supportedHybridKemAlgorithms) {
                continue
            }
            assertEquals(Crypto.supportsSeedBackedHybridKem, alg in ks.supportedAlgorithms)
            if (alg in ks.supportedAlgorithms) {
                val alias = "hybrid_${alg.name}"
                ks.createKey(alias, CreateKeySettings(algorithm = alg))
                val keyInfo = ks.getKeyInfo(alias)
                assertEquals(alg, keyInfo.algorithm)
                assertTrue(keyInfo.publicKey is HybridKemPublicKey)

                val asymSaKey = AsymmetricKey.AnonymousSecureAreaBased(alias, ks, keyInfo)
                val res1 = Crypto.kemEncapsulate(keyInfo.publicKey as HybridKemPublicKey)
                ks.kemDecapsulate(alias, res1.ciphertext).use { ss1 ->
                    assertContentEquals(res1.sharedSecret.encoded, ss1.encoded)
                }
                asymSaKey.kemDecapsulate(res1.ciphertext).use { ss2 ->
                    assertContentEquals(res1.sharedSecret.encoded, ss2.encoded)
                }
                res1.close()
            }

            val ephKey = AsymmetricKey.ephemeral(alg)
            val res2 = Crypto.kemEncapsulate(ephKey.hybridKemPublicKey)
            ephKey.kemDecapsulate(res2.ciphertext).use { ss3 ->
                assertContentEquals(res2.sharedSecret.encoded, ss3.encoded)
            }
            res2.close()
            ephKey.close()
        }
        val ecKey = AsymmetricKey.ephemeral(Algorithm.ESP256)
        assertFailsWith<UnsupportedOperationException> {
            ecKey.kemDecapsulate(ByteArray(32))
        }
        ecKey.close()
    }

    private data class ConcreteKatVector(
        val algorithm: Algorithm,
        val seedHex: String,
        val randomnessHex: String,
        val decapsulationKeyHex: String,
        val encapsulationKeyHex: String,
        val ciphertextHex: String,
        val sharedSecretHex: String,
    )

    @Test
    fun testConcreteHybridKemsAppendixB() = runTest {
        val vectors = listOf(
            ConcreteKatVector(
                algorithm = Algorithm.MLKEM768_P256,
                seedHex = "0909090909090909090909090909090909090909090909090909090909090909",
                randomnessHex = "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d" +
                    "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d" +
                    "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d" +
                    "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d" +
                    "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d",
                decapsulationKeyHex = "0909090909090909090909090909090909090909090909090909090909090909",
                encapsulationKeyHex = "460393d6222435d92a68eb6b2384ae414ba04199ac14e561f6711facf87180a4" +
                    "af6156a5cf589c8ec7983054764231646f07c57ae4247de37bb73758a60b4d33" +
                    "34985238563005ab337c6075b81416c241fce764505acd0fe7b0fbb859e41723" +
                    "90e670089414107549ed298bca658a229079866b8b960a7c7fe46cc47a177301" +
                    "0f106b6f5cc9c2f906465851a7716649b6a67ff073807cfc7be4cac44cd1af6a" +
                    "372cb04161d4d39861780c6f62051dfcc39c143dcbe49bbf5b58a8769b927173" +
                    "e4c7096c91310a91484cc80c55217b76124d0db0965f84bef016040cf19947d6" +
                    "1e05a14f322bbd918392f5083be540a122a3b1d7bc7c49a868f6384dd5259850" +
                    "d427166752bf286a3ae729e3a5a5e76209de5b9c0783a4c70504a338af3d178e" +
                    "bd114b7ef700cc17919b258682d21a1244a002fbca209a80cb2a94e4476aaeb7" +
                    "cd002929ef999095c893624667ae2bc8712b20e35815599196de478f674b50e2" +
                    "8313d7695ce2ba3dc02594171a1ba7a095a4351a0f3306f51b4c09ccafea5876" +
                    "93a9519f4b4976260445421a865a400a63b0a03a8d9a35ce3335bec1912460e1" +
                    "a5b4685f8fb039a5cb444ca21f87c522c7e6554f29007c0562e5f02eee849f29" +
                    "d39bc15b58ff58b252581adbb17a42f2357ce62746b1347a4123d02aa5c3974e" +
                    "ed863eb4f309c5b265d038aa6f994e8de94da384321fc65bda704a1521645146" +
                    "5f9d44b7e8a9b7e6d356e8a399e7eb39d0b46d85ec0cc93c56a6d570c9a8865a" +
                    "a6c74f358ff1c03b80042567796c41f418747126bb4ac29d738372fca1a9646a" +
                    "d6e7901581c6845c91c3989058a82bd80117e7c73bd2da15cff522fbf599fa68" +
                    "40317608e410b52b4c477827877ff612cf89cf6c87c8a72b175b6c8627e0a10b" +
                    "937cc672c1af54b64328c733c22585b88c54d30c1a22bfb6a2559b178db7a0aa" +
                    "b6001ddac9250416ae5c11c0a5a26a09d38518b28f8e3a7f2eb51e0d679f1d92" +
                    "8b77e103f4fa6218f68a1de0af5ea0bd1a26bb624879ab47a28e9c40042b29cb" +
                    "d678f7ab65353807d41993bfe11607313430e5aa8c27b9af3a5f10531aecb1a1" +
                    "c3710f2b992c99b604f7b2a3269a49091a3a56dc8b78487ad9ac24e36c97d351" +
                    "855ee1715083a3e9c32f52f735a76cb008714fa83bc70e4caab72c485ac8431c" +
                    "c97cfba727fcc0af9c542d7d9199bc4b50a9ba83db77c7dc206fda0547e2795a" +
                    "8dc36fd1b33d2ddab089d95eb16b15da94a74ed8b7e8daadf8180721e0a1e310" +
                    "02497c83ce1747fbe78cac291ffd967f23f47a8a30485aa352cfd4c6907cb8b2" +
                    "01bc3b9281eb51985e11cbb4b5250ef7ca13c70674f395c52b177541b57b3aac" +
                    "77e382d5bc6e0c32aade73c74a163c0f43a59c616d76393f39166de8318fef1b" +
                    "2c47075c19885ecea7b4acc674d1c72a2e4ca70d28325f12cc33ac19aa9c5e5a" +
                    "b31c7ac6933c1a0a79065fa1e0237cd90a13c7b759980262378d4a17b85c184f" +
                    "84761e071a973784b1c30a9358b76a7af10727f36960f21c6e078d33fb528373" +
                    "a436fb237f87317b81c04926245982aea0b39c24a67effe65a91b211ad7334f8" +
                    "e4858a3b7d0ef21b3e443c0cb64e089408006b103f3841cab70b046b98e2c8ae" +
                    "d94ad683208e265424b077389c2ac6571f216fac52e35cd280e51aaf66328162" +
                    "04c82a04f09e4fda885db34fcc3be3ff41f95700e4593bf825f93ab30469733d" +
                    "373e450b125db222ab72f2ade96db31fee093fcf254511547ac1ca7e926778f1" +
                    "33",
                ciphertextHex = "a857a7425c6077dafaef133c62869e523a4b41110cededea6ee66b9a6bba634d" +
                    "92cb41d5fbef2ccd6f5f057951fe1b44e224cd2314c9ffcdf4c358979d6d065f" +
                    "1f12a51033640c07965e6d4342afd7b57d528786b398e13b6eca581b96fe7777" +
                    "8d73bfa2422e43093f7c96434103b18cbdf0aa6549da8e169b66f42921b89ac8" +
                    "267de35446008948d6026a0bcbe61357e97c7fe7a95337dfa3177ca6167c3a2a" +
                    "f8dc38e3d708665824acf97d6d7b152bff781451c587a282adce031b3efa26b6" +
                    "d8bf4b49733c03dcc4300076049e278d3cd872174bc374d77038c75203ba45d3" +
                    "0bd4110b62346c3f0bb1d8563d0e2d58cac839fa693ca96a56eeab1aa4d26cc9" +
                    "5e013674eec4f8f08095059f081a0d4dfc0735653f3097f1a37da334007082ba" +
                    "6619844547e0d3296669eeceb02ecbf0c5c5a88d5d1c432398361529e0d91a62" +
                    "8e568cd9a18cb3e8ca41315ccbd8ff9cbbc0677150f2e06274ec9e2733f3111c" +
                    "d4f1b900c7a3a80b517a7d6917fd0753f59e8bf03267e3a43d60e1857d4922cd" +
                    "0f14b6d28602792dbfe2b4488e807023ff4a8b65f75fe1a0826d5ff490bac7be" +
                    "071b9545f6e7ee758caf460e7e92e52eb83dcafb5bedeecf44a5f5ef23690bd8" +
                    "3aa5ed2ce0420c697dfe4eaeff5d64be78fd3d9c96f66fcfbe015d8a1e75e93e" +
                    "ed734c267fa515220e9937fdf8df271f69c8e3d6dec76152404d862434211bd1" +
                    "e4b53cb9951c48b796fbf4287d3386c65217b41f2c414daada2023c43c08f8b6" +
                    "edbecc7f750968c4f16c3c983a95d73c25d3cd4aac5bd5941fa376e1934436ac" +
                    "ecf9bb9bd4409d0944d393d852aa15d6363bbd83cc24b1ea841e00b99dbe9f7b" +
                    "9fc2a415063865b43ecc9db0a95336820d40ec4d7325d5066eeb2a144ed3c27f" +
                    "5072c9a2e596d395bd069fdb8871521a08d0e9c1810467a271b4067a54d9d81e" +
                    "ca8f26d18acb41a7de599aab1f79ddd0128c07e44cf2ccef72368cb4474db47e" +
                    "f43917940a80d05aea5e8c933a0ef8a8516a6dc2a50df273288d97a9788629f0" +
                    "01e487434e6a4192466e63f0e185810665267a021896967d833c1f108645a5d1" +
                    "7334d5dde8fd617c786665d7df9762b8ec0676af697dca63e62b597aa2cf89c5" +
                    "58accc872ce9a1277f88c5ac160b2d13a27de6b4027353adc3bf596d3d652753" +
                    "2e66225fa53e2546f6ceb240435bb93d10e4677208e990f622276d04ab8f2e4c" +
                    "e2e5180a436d33adbf29c2cef8704a41074ed16c164ffcd1c8f09a2f752c098c" +
                    "aab09da3db75d84ca510542c3adea1757a553bf53657f03feec803950747c8dd" +
                    "ceeb31bd658072f01b7972df731af5ff53bf8a8361cc918c6a00693134fe6e38" +
                    "5cc70484d2e3e6d365c14fbe6e2332eaf86b7afb8da619dd4c13e86f62ed63da" +
                    "3b99895d6ee47b440e16a3615f341d4be41239336b020174819681732503f275" +
                    "bf8bc494c251e5448ff32bffb3fe0787482b796c37fd8d25a28ea78620046b19" +
                    "2ce46fa4c798c0feed1819614413326eb373e622834afeaff81aff308caaf835" +
                    "04d31b9898e848b396bc86cc55c519f1a01125995311ba37d96aa59ff464baf8" +
                    "885f3bdd10b1c92f0b286990048d58105a7ab5ec1755763ecb4d20fd1817d6b2" +
                    "fd",
                sharedSecretHex = "a0071e94b4d0275ed91dfe4f3fe87dd4a534269c7a5991fdc27ac31933fad24c",
            ),
            ConcreteKatVector(
                algorithm = Algorithm.MLKEM768_X25519,
                seedHex = "0909090909090909090909090909090909090909090909090909090909090909",
                randomnessHex = "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d" +
                    "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d",
                decapsulationKeyHex = "0909090909090909090909090909090909090909090909090909090909090909",
                encapsulationKeyHex = "460393d6222435d92a68eb6b2384ae414ba04199ac14e561f6711facf87180a4" +
                    "af6156a5cf589c8ec7983054764231646f07c57ae4247de37bb73758a60b4d33" +
                    "34985238563005ab337c6075b81416c241fce764505acd0fe7b0fbb859e41723" +
                    "90e670089414107549ed298bca658a229079866b8b960a7c7fe46cc47a177301" +
                    "0f106b6f5cc9c2f906465851a7716649b6a67ff073807cfc7be4cac44cd1af6a" +
                    "372cb04161d4d39861780c6f62051dfcc39c143dcbe49bbf5b58a8769b927173" +
                    "e4c7096c91310a91484cc80c55217b76124d0db0965f84bef016040cf19947d6" +
                    "1e05a14f322bbd918392f5083be540a122a3b1d7bc7c49a868f6384dd5259850" +
                    "d427166752bf286a3ae729e3a5a5e76209de5b9c0783a4c70504a338af3d178e" +
                    "bd114b7ef700cc17919b258682d21a1244a002fbca209a80cb2a94e4476aaeb7" +
                    "cd002929ef999095c893624667ae2bc8712b20e35815599196de478f674b50e2" +
                    "8313d7695ce2ba3dc02594171a1ba7a095a4351a0f3306f51b4c09ccafea5876" +
                    "93a9519f4b4976260445421a865a400a63b0a03a8d9a35ce3335bec1912460e1" +
                    "a5b4685f8fb039a5cb444ca21f87c522c7e6554f29007c0562e5f02eee849f29" +
                    "d39bc15b58ff58b252581adbb17a42f2357ce62746b1347a4123d02aa5c3974e" +
                    "ed863eb4f309c5b265d038aa6f994e8de94da384321fc65bda704a1521645146" +
                    "5f9d44b7e8a9b7e6d356e8a399e7eb39d0b46d85ec0cc93c56a6d570c9a8865a" +
                    "a6c74f358ff1c03b80042567796c41f418747126bb4ac29d738372fca1a9646a" +
                    "d6e7901581c6845c91c3989058a82bd80117e7c73bd2da15cff522fbf599fa68" +
                    "40317608e410b52b4c477827877ff612cf89cf6c87c8a72b175b6c8627e0a10b" +
                    "937cc672c1af54b64328c733c22585b88c54d30c1a22bfb6a2559b178db7a0aa" +
                    "b6001ddac9250416ae5c11c0a5a26a09d38518b28f8e3a7f2eb51e0d679f1d92" +
                    "8b77e103f4fa6218f68a1de0af5ea0bd1a26bb624879ab47a28e9c40042b29cb" +
                    "d678f7ab65353807d41993bfe11607313430e5aa8c27b9af3a5f10531aecb1a1" +
                    "c3710f2b992c99b604f7b2a3269a49091a3a56dc8b78487ad9ac24e36c97d351" +
                    "855ee1715083a3e9c32f52f735a76cb008714fa83bc70e4caab72c485ac8431c" +
                    "c97cfba727fcc0af9c542d7d9199bc4b50a9ba83db77c7dc206fda0547e2795a" +
                    "8dc36fd1b33d2ddab089d95eb16b15da94a74ed8b7e8daadf8180721e0a1e310" +
                    "02497c83ce1747fbe78cac291ffd967f23f47a8a30485aa352cfd4c6907cb8b2" +
                    "01bc3b9281eb51985e11cbb4b5250ef7ca13c70674f395c52b177541b57b3aac" +
                    "77e382d5bc6e0c32aade73c74a163c0f43a59c616d76393f39166de8318fef1b" +
                    "2c47075c19885ecea7b4acc674d1c72a2e4ca70d28325f12cc33ac19aa9c5e5a" +
                    "b31c7ac6933c1a0a79065fa1e0237cd90a13c7b759980262378d4a17b85c184f" +
                    "84761e071a973784b1c30a9358b76a7af10727f36960f21c6e078d33fb528373" +
                    "a436fb237f87317b81c04926245982aea0b39c24a67effe65a91b211ad7334f8" +
                    "e4858a3b7d0ef21b3e443c0cb64e089408006b103f3841cab70b046b98e2c8ae" +
                    "d94ad683208e265424b077389c2ac6571f216fac52e35cd280e51aaf66328162" +
                    "012edaa88f37a3f6e9c0c89861d6df5d8a3a453cca19d8746cdbee424f00c338",
                ciphertextHex = "a857a7425c6077dafaef133c62869e523a4b41110cededea6ee66b9a6bba634d" +
                    "92cb41d5fbef2ccd6f5f057951fe1b44e224cd2314c9ffcdf4c358979d6d065f" +
                    "1f12a51033640c07965e6d4342afd7b57d528786b398e13b6eca581b96fe7777" +
                    "8d73bfa2422e43093f7c96434103b18cbdf0aa6549da8e169b66f42921b89ac8" +
                    "267de35446008948d6026a0bcbe61357e97c7fe7a95337dfa3177ca6167c3a2a" +
                    "f8dc38e3d708665824acf97d6d7b152bff781451c587a282adce031b3efa26b6" +
                    "d8bf4b49733c03dcc4300076049e278d3cd872174bc374d77038c75203ba45d3" +
                    "0bd4110b62346c3f0bb1d8563d0e2d58cac839fa693ca96a56eeab1aa4d26cc9" +
                    "5e013674eec4f8f08095059f081a0d4dfc0735653f3097f1a37da334007082ba" +
                    "6619844547e0d3296669eeceb02ecbf0c5c5a88d5d1c432398361529e0d91a62" +
                    "8e568cd9a18cb3e8ca41315ccbd8ff9cbbc0677150f2e06274ec9e2733f3111c" +
                    "d4f1b900c7a3a80b517a7d6917fd0753f59e8bf03267e3a43d60e1857d4922cd" +
                    "0f14b6d28602792dbfe2b4488e807023ff4a8b65f75fe1a0826d5ff490bac7be" +
                    "071b9545f6e7ee758caf460e7e92e52eb83dcafb5bedeecf44a5f5ef23690bd8" +
                    "3aa5ed2ce0420c697dfe4eaeff5d64be78fd3d9c96f66fcfbe015d8a1e75e93e" +
                    "ed734c267fa515220e9937fdf8df271f69c8e3d6dec76152404d862434211bd1" +
                    "e4b53cb9951c48b796fbf4287d3386c65217b41f2c414daada2023c43c08f8b6" +
                    "edbecc7f750968c4f16c3c983a95d73c25d3cd4aac5bd5941fa376e1934436ac" +
                    "ecf9bb9bd4409d0944d393d852aa15d6363bbd83cc24b1ea841e00b99dbe9f7b" +
                    "9fc2a415063865b43ecc9db0a95336820d40ec4d7325d5066eeb2a144ed3c27f" +
                    "5072c9a2e596d395bd069fdb8871521a08d0e9c1810467a271b4067a54d9d81e" +
                    "ca8f26d18acb41a7de599aab1f79ddd0128c07e44cf2ccef72368cb4474db47e" +
                    "f43917940a80d05aea5e8c933a0ef8a8516a6dc2a50df273288d97a9788629f0" +
                    "01e487434e6a4192466e63f0e185810665267a021896967d833c1f108645a5d1" +
                    "7334d5dde8fd617c786665d7df9762b8ec0676af697dca63e62b597aa2cf89c5" +
                    "58accc872ce9a1277f88c5ac160b2d13a27de6b4027353adc3bf596d3d652753" +
                    "2e66225fa53e2546f6ceb240435bb93d10e4677208e990f622276d04ab8f2e4c" +
                    "e2e5180a436d33adbf29c2cef8704a41074ed16c164ffcd1c8f09a2f752c098c" +
                    "aab09da3db75d84ca510542c3adea1757a553bf53657f03feec803950747c8dd" +
                    "ceeb31bd658072f01b7972df731af5ff53bf8a8361cc918c6a00693134fe6e38" +
                    "5cc70484d2e3e6d365c14fbe6e2332eaf86b7afb8da619dd4c13e86f62ed63da" +
                    "3b99895d6ee47b440e16a3615f341d4be41239336b020174819681732503f275" +
                    "bf8bc494c251e5448ff32bffb3fe0787482b796c37fd8d25a28ea78620046b19" +
                    "2ce46fa4c798c0feed1819614413326eb373e622834afeaff81aff308caaf835" +
                    "635de5eb039fc126a017b9e789e1db721cdd51f7ed3f5515d18e2f4e2dd7851a",
                sharedSecretHex = "d1b5f13316ff420d4ca22c9a8e3f93d27f735d1da53ebc979cce23f9747e3261",
            ),
            ConcreteKatVector(
                algorithm = Algorithm.MLKEM1024_P384,
                seedHex = "0909090909090909090909090909090909090909090909090909090909090909",
                randomnessHex = "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d" +
                    "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d" +
                    "6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d6d",
                decapsulationKeyHex = "0909090909090909090909090909090909090909090909090909090909090909",
                encapsulationKeyHex = "831a957ee82bba8605154b0aab3c3d4b43b304050a958a78a4f983ea4505b041" +
                    "652b5c26c0a11b74881a9512119a4678d53641ad16819b85901111530d0308ad" +
                    "6a0b7b419ca6d001edb967ceea6faba594ce482a1a177ff8f2b01453add28a96" +
                    "dfd2358ec6ca7cda8f2c1308f4b6cd1596a14c326e51e527b1c3b2e99c8bb4a6" +
                    "7630634089a5b1469977b440ccfe2a49545716da34b55eb73d9559a36d828ff9" +
                    "36a71fe223abe7cb646b3b3955acd0e19ef2bb5d9e847cd3c7bcb18952e567b1" +
                    "55e67c0b39948646ccc2547b43f1bf05451f90abb63bca0d0517bbc23aa67835" +
                    "52a915051222824f3739eba438ad1278e1a6778505456fe86a8d280cd0f88dd5" +
                    "2c06ffb28bfdd61825d626f946c531e63364c6a4a5162bf5eb6628e8c0174099" +
                    "6573215b20ad80964ecbdca13ad2311a2046da84c7cd2412faccc736f5998c57" +
                    "23c482120c2662244aa0c5847dcbb7652907c36571ab3d4b93618973d2d066fb" +
                    "1bba9b8587c9e017ac737c1cc40872e11e0ee55db5d4bde221226df7822022b7" +
                    "eb0b80ca0428b6b0b94bc0bd15a2873f3058425c0d7b2665c464499cba8a8163" +
                    "a08801269712b76fe36a46c1250ba113f5596d782bc0315a5dd45c2b77674238" +
                    "b59d0b0347498b1a11b5a7fa94864b14bf5fc20d762232b98b5b70797e411444" +
                    "df938dd52114575c3bc9b01f6812396ba69b6e89b33a304d3cf235d70582ed55" +
                    "ac9f043103b71b3b9845a7684057f79400f954f4087b86481b9b80ce1d37972f" +
                    "438954d37a03bb5b9a35277af5911d91b6611858460354a4945feb1756a9d08e" +
                    "932887d02c32b6284e86829a6554a5916a642da448f97543fb159aa108661b30" +
                    "cd34d35c6c3358b888490dc17ff3e731239a69e4916e48993569989a2e55575d" +
                    "29c9bfa501beb674a1fb5cbd5b4e4916bd389c2996a761e123147f385f18fa81" +
                    "df417178c58a1826233b8a9825c1bc3143069ea0404d468994669b95bb5ac27c" +
                    "41849a8ce1352be857a2200970ef46aa2be28e5568b071012775f294fa6a8d2b" +
                    "e12d6a8227ae719b47346b0eb268afa43e8acb90a114a4a4b2ba771112204833" +
                    "3eeaa5a4141a4682a0220b5ec7369e2c64307d4173fa80715d69b3cd4835adb5" +
                    "072796891a6780b930540c5779f23b00f6b2964506c4d79a50b387080a5a1608" +
                    "a09737071f2432aca003bccfb56df1689433b4532ddba87d8078428bc128a660" +
                    "9f35a917022936c4ae5085c040149620904f1a1571ce42b98b335cc2814b25fb" +
                    "90f0c3991808ab61154c12864922dc4acf75a74304174a68795f858d4a8a3bd1" +
                    "06bdcbfb3d87b6a6b4f93992b39ac2f131f78998e069b7da46c11b0a99d5b525" +
                    "5146412b15491e1b8ec8d01e61161b54d9cf01ec2f84cba60d986d91229f09c4" +
                    "2400d5203c3c5164abbed8064f79d4cfa491c940982b2dc2bba8493975227aa8" +
                    "5145334b470e88900447aa4e042fc0b06655f9668167aa93c0c8ab8018491821" +
                    "516cb4085b1148d912c4668d1c72076c311142b80e7f112c466c3e19f72418e0" +
                    "3adbb0c8bbb885306294e70435d4546f8c6095b3b591120c45b759c9a7d15532" +
                    "8b1a09b424aaa8b528484b48e689a8d54f86e383a8c25603707c3e174001ea2a" +
                    "c78ba008f81bed4a8963573eb06c1f4f19091117a9f4211b107b2c4c05220ac2" +
                    "bcca67634b7cac8f51bb6a258b706c6cb07c5b8bd48b12877b743877c10b52d4" +
                    "363f03b21413365aee80c1f5f18380f5b6afe5a2e911542af0022cb3c95d3a8b" +
                    "57b7c1fdb74f3b51c6f2e8a4aab20918fc588928468d498ab7c749b54140ed26" +
                    "288bb804a9637482f56ce0a69706fc89ecdb4cf3ab6a30f7c595a18076da50a8" +
                    "131d240049ac999630b0af01b8ad12350e132a7b9d81c3d5082b68e61f242a22" +
                    "4a42553c3886e0261d115b8ae11705959c20c1c62611ba2c6b30622afa91a33a" +
                    "216c65539823621e84bb897c2133c4c5fb01cde505a7d114466206a23fac8938" +
                    "613fecb3c9609796df50caa701081ee76bf63893435a8101e03f0c135824e9b4" +
                    "88962f2356a15b0427c4e41f2ba9be16056d79844b708c4140c3232915c06c83" +
                    "be4ba83f9132b279d1bb8b0c9d5fd61f6af29252dc2401f17b830b5538723c04" +
                    "6629cc913209cc9ebf24aee9083ae883a98d04b27be8164801135f0cc37c2220" +
                    "295109f6068201b721b549a15e0be0377089050bb02aaecefa9fe5c3207eaa05" +
                    "0436a60b2e64e53c71ee75186bab64a48919279b2b54453125fb735c0ca68e62" +
                    "92f6d9f4943a06c086ea570fd604cc99b906e282a752e1681bea526ba45e6c6d" +
                    "90edfcd90fb8a1c3a7b5d6d91cbf751fcf8059d1ab34a422cd5ae881969f40e0" +
                    "65",
                ciphertextHex = "aacb765d071dd63516706b226b20856e386e89bc8225d8b9235ea9e796ba9ab2" +
                    "70f7376f22b2a1433d533ddca26a95a68597ba80fab3af861fa45be6e1bb58e1" +
                    "564ef6159b9f7c3c30588020efb44fa2b2769ab163fb969cdc80714075adc8d7" +
                    "1433117ce68252c6dffbb5294b0977a1eecc27393a3f33c8f818b0ac1f229f82" +
                    "e1f50a7510b164402cc017df95be6715544ef5b0780208a67377d5fc06e505a1" +
                    "22d188d649074029634b69f9a62d2b6abc3e7d28bfb80cdb09336bcba0fade46" +
                    "662a5e62f43fee55a3a01e9c9cc86b2390de4bce484b04ccb0dfe644c66de375" +
                    "a0e4c082409062a4d030a3c1fa919985016a3f30a8c37b064579b3223396e1a2" +
                    "0be912426d754a5b6e3313c78964b96d6bb55fdee97ca88553c31dce9229138f" +
                    "ac659f0d12edfe8853383c4b30001bb24e5216c2c437eac7c0bbaf68a03a4461" +
                    "9b3bf9e009503631c98b2a81544780d3f376f09a0574f236772d59517918c6d8" +
                    "c2a7ed0a09497393dfa23e178aeb7ac9ff92b3843da99fa6d4a6e9e6c2ed4f2b" +
                    "e3566104683a4965538356456614504f825eceb488e625f3c9384106f50b9ce8" +
                    "8b0f310ac30922298f53b1631159bfc069f26de61b40577f6f42b517b76bc0db" +
                    "e5781de35f1950b6309236d8b0cc8f53430726c084c01af7d670c27b34d77086" +
                    "324a849d260ca00a4f2faa4e1cc65f34680e3b96993c1ed13a93d2107e5bb757" +
                    "085e55c27a2a9830db6f3ded55916a18b074673e3f97fe37db600c75897a1e6b" +
                    "b1c4cb3874e25793493cca4194b97103d8f75a1510452e2401bd27982db0269d" +
                    "f85fba994f0b011e405b710e655a007f6e73f14c3191438a39c96f8304e237f7" +
                    "ae3c7e7ab239f8b313e7c1b9d757bd87dd19ecb91e1db5cc3e827490f8f1be70" +
                    "fa51eecfbc4e42a84f4ec8680b8f6b25d247e131ceef22758d9a34314aa681fd" +
                    "0a9de4139f9931acbe7ebbcd2bf576e9e87d2ea9287e96f4e54098eb263d4802" +
                    "4aeabdf056739450139b159c3885c8c05caba47038e044155792dad58acff4eb" +
                    "601a82eddb6dcb351f00522218fbc5979d6c7f3bab9d3cb407540c10cd593e85" +
                    "6d91e543eadd0696808698bfae3c14baf2d43c5af4f8da9c9b8e7c3ddc88b8f5" +
                    "761576c039fa381da89e58966f5bbe00b6d8549f8f3b611f8dc736aca21bb3e2" +
                    "666953c96b43b037026125bde594273d2cc409ef3d3ab1882ae4ee1b745f1861" +
                    "36046b57df284cc2ccf05c42b9eb6ccb402b26cf218a423803ad8d9f9089cb27" +
                    "bc0d7e3c1c9b21d6cdd8c3fa672b7b1d9eabe8be1ddf59b34fa7394296f727e4" +
                    "8f85cef69ae1eddd76e085232f141cd9809d5ffbbb8c43f571aaa8ea2f513502" +
                    "89d37db5b66b0ff5ab5c14e05756369ba307be2f25c63b64768ee5ecc5d20604" +
                    "25674164eab8daa03da7348034cd7a88cf3ad6131acab875ab9f3a5cd6cb66c9" +
                    "9297abd477c699e30675cf5d51c1baa8c1900633e4acdd6214968c3134af3012" +
                    "5d5afeb8bb37e0d9cd6dd92d5399c4c319a35c83eb66c91ce90b2c4813063d42" +
                    "0b06cd3c91355c744e5f854b7907696953a9af76ea8fa6ff4ceedb46106303a5" +
                    "e59e00c3824d66083e93f27a0a7caee64fead39528c716b0b98400995038ec40" +
                    "bb880635dd2b143426ff52d0412a1a911928f6ac98ba8d38bd4099f9e01265a1" +
                    "debc25ae47780d0a5d51db0c641f952dbd85498a40db10a8051f48b537cde45a" +
                    "29da4cca985631f2445be724ee4039baae8ac7cb3d0527402e0a7c8bf60e26f5" +
                    "37aae00b7f8236466bf131dfec2e3c3a09f12a45c77d8b1eff124e33bde64b02" +
                    "864b73464331a654b2bfb3d6e5c9796e120df8502015047089257f549b8d79af" +
                    "8d05b0f18acc0141a352e6d07119532c4eafdbe826e13ad11a7ff57e542ef398" +
                    "b155ed325d239206c71cb1ee052be6a7d63efedeb5f0f32c88492f330b2e2030" +
                    "3d55337723457c1c9b72ca110f628ab3751c99e467b977a32c4cdc5904a53aad" +
                    "0ec46e2f4dea22c14493fa5a35ec9eefcf8134f1ecf2424eeaae71509279b591" +
                    "14056ec4f0e19268e098d5285aaa05dbc56669fdd7abddc7ecfb62e357abe51b" +
                    "507cbf2bab79a8f447e81632d185d989f2cd67fb83323f6f5f59eefc63359d16" +
                    "68a4cb86b77faaad7aa487b7b1726418fa872869b0279c54588075a59f7952ab" +
                    "a6836748c28fa0419a3fe3e9e9e0bc38cf0931792e6cbc6c9f8c87f5d5ee6924" +
                    "048a871dea160c961093cb257c1e1c3c4f7ee0f37304caba13d3eb6f03539c7b" +
                    "92a2eab2f4ac9bc96ea7eced9db4885ef5a79517bd153037c7a5b719a2e71721" +
                    "e95e8635464576e833114ded11f2304b549ed682dd8e1ff40f93e5dfa49ce9b1" +
                    "9e",
                sharedSecretHex = "3722070676e89cc0c97613a0207dbd89222219920caf5e9a8c6aa9d1e1a42bd5",
            ),
        )
        if (!Crypto.supportsSeedBackedHybridKem) {
            assertEquals(false, Crypto.provider.startsWith("JCA:"))
            return@runTest
        }
        for (v in vectors) {
            if (v.algorithm !in Crypto.supportedHybridKemAlgorithms) {
                continue
            }
            val privKey = HybridKem.createPrivateKeyFromSeed(v.algorithm, v.seedHex.fromHex())
            assertEquals(v.decapsulationKeyHex.lowercase(), privKey.encoded.toByteArray().toHex())
            assertEquals(v.encapsulationKeyHex.lowercase(), privKey.publicKey.encoded.toByteArray().toHex())

            val encap = try {
                HybridKem.encapsulateDerand(privKey.publicKey, v.randomnessHex.fromHex())
            } catch (e: UnsupportedOperationException) {
                if (Crypto.provider.startsWith("JCA:")) {
                    throw e
                }
                null
            }
            if (encap != null) {
                assertEquals(v.ciphertextHex.lowercase(), encap.ciphertext.toHex())
                assertEquals(v.sharedSecretHex.lowercase(), encap.sharedSecret.encoded.toHex())
                encap.close()
            }

            val decapsulated = HybridKem.decapsulate(privKey, v.ciphertextHex.fromHex())
            assertEquals(v.sharedSecretHex.lowercase(), decapsulated.encoded.toHex())

            decapsulated.close()
            privKey.close()
        }
    }

    @Test
    fun testNegativeCases() = runTest {
        if (Algorithm.MLKEM768_X25519 in Crypto.supportedHybridKemAlgorithms) {
            val privKey = Crypto.createHybridKemPrivateKey(Algorithm.MLKEM768_X25519)
            val pubKey = privKey.publicKey
            val valid = Crypto.kemEncapsulate(pubKey)

            // Wrong ciphertext sizes
            assertFailsWith<IllegalArgumentException> {
                Crypto.kemDecapsulate(privKey, valid.ciphertext.copyOf(valid.ciphertext.size - 1))
            }
            assertFailsWith<IllegalArgumentException> {
                Crypto.kemDecapsulate(privKey, valid.ciphertext + byteArrayOf(0))
            }

            // Tampered PQ ciphertext -> implicit rejection yields different secret
            val badPq = valid.ciphertext.copyOf()
            badPq[10] = (badPq[10].toInt() xor 0x01).toByte()
            Crypto.kemDecapsulate(privKey, badPq).use { ssBadPq ->
                assertNotEquals(valid.sharedSecret, ssBadPq)
            }

            // Tampered traditional ciphertext -> different secret
            val badTrad = valid.ciphertext.copyOf()
            badTrad[badTrad.size - 1] = (badTrad[badTrad.size - 1].toInt() xor 0x01).toByte()
            Crypto.kemDecapsulate(privKey, badTrad).use { ssBadTrad ->
                assertNotEquals(valid.sharedSecret, ssBadTrad)
            }

            // Low-order / all-zero X25519 points rejected explicitly
            val zeroX25519Ct = valid.ciphertext.copyOf()
            for (i in 1088 until 1120) zeroX25519Ct[i] = 0
            assertFailsWith<IllegalArgumentException> {
                Crypto.kemDecapsulate(privKey, zeroX25519Ct)
            }
            val order2X25519Ct = valid.ciphertext.copyOf()
            for (i in 1088 until 1120) order2X25519Ct[i] = 0
            order2X25519Ct[1088] = 1
            assertFailsWith<IllegalArgumentException> {
                Crypto.kemDecapsulate(privKey, order2X25519Ct)
            }

            // Closed key rejects operations
            val hasSeed = privKey.hasSeed
            privKey.close()
            assertFailsWith<IllegalStateException> { privKey.duplicate() }
            assertFailsWith<IllegalStateException> { Crypto.kemDecapsulate(privKey, valid.ciphertext) }
            if (hasSeed) {
                assertFailsWith<IllegalStateException> { privKey.encoded }
                assertFailsWith<IllegalStateException> { privKey.toCoseKey() }
                assertFailsWith<IllegalStateException> { privKey.toJwk() }
            }
            valid.close()
        }

        // Off-curve P-256 and P-384 points rejected on public key & ciphertext deserialization
        for (alg in listOf(Algorithm.MLKEM768_P256, Algorithm.MLKEM1024_P384)) {
            if (alg !in Crypto.supportedHybridKemAlgorithms) {
                continue
            }
            val privKey = Crypto.createHybridKemPrivateKey(alg)
            val badPubBytes = privKey.publicKey.encoded.toByteArray().copyOf()
            badPubBytes[badPubBytes.size - 1] = (badPubBytes[badPubBytes.size - 1].toInt() xor 0x01).toByte()
            assertFailsWith<IllegalArgumentException> {
                HybridKemPublicKey(alg, ByteString(badPubBytes))
            }
            val valid = Crypto.kemEncapsulate(privKey.publicKey)
            val badCt = valid.ciphertext.copyOf()
            badCt[badCt.size - 1] = (badCt[badCt.size - 1].toInt() xor 0x01).toByte()
            assertFailsWith<IllegalArgumentException> {
                Crypto.kemDecapsulate(privKey, badCt)
            }
            valid.close()
            privKey.close()
        }
    }
}
