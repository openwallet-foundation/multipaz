package org.multipaz.crypto

import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha3Tests {

    @Test
    fun testSha3_256KnownAnswers() {
        val cases = listOf(
            Pair(
                "",
                "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"
            ),
            Pair(
                "616263",
                "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"
            ),
            Pair(
                "031425364758697a8b9cadbecfe0f102132435465768798a9bacbdcedff00112" +
                "233445566778899aabbccddeef00112233445566778899aabbccddeeff102132" +
                "435465768798a9bacbdcedfe0f2031425364758697a8b9cadbecfd0e1f304152" +
                "63748596a7b8c9daebfc0d1e2f405162738495a6b7c8d9eafb0c1d2e3f506172" +
                "8394a5b6c7d8e9",
                "91f8d744cdf6c88796d2daa6772af7c4605163441b63bcaf6601650e35663d95"
            ),
            Pair(
                "0726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8" +
                "e70625446382a1c0dffe1d3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8" +
                "c7e60524436281a0bfdefd1c3b5a7998b7d6f51433527190afceed0c2b4a6988" +
                "a7c6e504234261809fbeddfc1b3a597897b6d5f4133251708faecdec0b2a4968" +
                "87a6c5e403224160",
                "bf55dd8c0b4dd0f226d019f9b85d74e3d77cd13409e1306665a44cc688a93d6e"
            ),
            Pair(
                "0b4075aadf14497eb3e81d5287bcf1265b90c5fa2f6499ce03386da2d70c4176" +
                "abe0154a7fb4e91e5388bdf2275c91c6fb30659acf04396ea3d80d4277ace116" +
                "4b80b5ea1f5489bef3285d92c7fc31669bd0053a6fa4d90e4378ade2174c81b6" +
                "eb20558abff4295e93c8fd32679cd1063b70a5da0f4479aee3184d82b7ec2156" +
                "8bc0f52a5f94c9fe33",
                "c6844c6143db20aa01945f9ca6bea533e7d860f3c09b60c336fe978bc3a9f86c"
            ),
            Pair(
                "1374d53697f859ba1b7cdd3e9f0061c22384e546a70869ca2b8ced4eaf1071d2" +
                "3394f556b71879da3b9cfd5ebf2081e243a40566c72889ea4bac0d6ecf3091f2" +
                "53b41576d73899fa5bbc1d7edf40a10263c42586e748a90a6bcc2d8eef50b112" +
                "73d43596f758b91a7bdc3d9eff60c12283e445a60768c92a8bec4dae0f70d132" +
                "93f455b61778d93a9bfc5dbe1f80e142a30465c62788e94aab0c6dce2f90f152" +
                "b31475d63798f95abb1c7dde3fa00162c32485e647a8096acb2c8dee4fb01172" +
                "d33495f657b8197a",
                "cc2232b80618869478f737ce7a109b22edcdf7a8bdf86118eefa5e96f8ae79f5"
            )
        )
        for ((inputHex, expectedHex) in cases) {
            assertEquals(expectedHex, Sha3.sha3_256(inputHex.fromHex()).toHex())
        }
    }

    @Test
    fun testSha3_384KnownAnswers() {
        val cases = listOf(
            Pair(
                "",
                "0c63a75b845e4f7d01107d852e4c2485c51a50aaaa94fc61995e71bbee983a2a" +
                    "c3713831264adb47fb6bd1e058d5f004"
            ),
            Pair(
                "616263",
                "ec01498288516fc926459f58e2c6ad8df9b473cb0fc08c2596da7cf0e49be4b2" +
                    "98d88cea927ac7f539f1edf228376d25"
            ),
            Pair(
                "05182b3e5164778a9db0c3d6e9fc0f2235485b6e8194a7bacde0f306192c3f52" +
                    "65788b9eb1c4d7eafd102336495c6f8295a8bbcee1f4071a2d405366798c9fb2" +
                    "c5d8ebfe1124374a5d708396a9bccfe2f5081b2e4154677a8da0b3c6d9ecff12" +
                    "25384b5e718497",
                "04ac859cca82e3d1c87c4cb8b5318c888a2c18d16d8d6588ee688c19b42a0948" +
                    "de2c39ddcd90a4fab647a2ba3f91b4eb"
            ),
            Pair(
                "071e354c637a91a8bfd6ed041b324960778ea5bcd3ea01182f465d748ba2b9d0" +
                    "e7fe152c435a71889fb6cde4fb122940576e859cb3cae1f80f263d546b8299b0" +
                    "c7def50c233a51687f96adc4dbf20920374e657c93aac1d8ef061d344b627990" +
                    "a7bed5ec031a3148",
                "67e1356fd8a616341fab635b881df32ed1840d96173eb7af7ac43c27855e322b" +
                    "e1c0604fd0a702f5e658f15673a3d8eb"
            ),
            Pair(
                "0b2845627f9cb9d6f3102d4a6784a1bedbf815324f6c89a6c3e0fd1a3754718e" +
                    "abc8e5021f3c597693b0cdea0724415e7b98b5d2ef0c294663809dbad7f4112e" +
                    "4b6885a2bfdcf91633506d8aa7c4e1fe1b3855728facc9e603203d5a7794b1ce" +
                    "eb0825425f7c99b6d3",
                "26415b2628efa5a24b0126e2133a0135e46d873812a0575414b704d1ec236169" +
                    "4ce790788bcdb02b940aca94527b4bb7"
            ),
            Pair(
                "1374d53697f859ba1b7cdd3e9f0061c22384e546a70869ca2b8ced4eaf1071d2" +
                    "3394f556b71879da3b9cfd5ebf2081e243a40566c72889ea4bac0d6ecf3091f2" +
                    "53b41576d73899fa5bbc1d7edf40a10263c42586e748a90a6bcc2d8eef50b112" +
                    "73d43596f758b91a7bdc3d9eff60c12283e445a60768c92a8bec4dae0f70d132" +
                    "93f455b61778d93a9bfc5dbe1f80e142a30465c62788e94aab0c6dce2f90f152" +
                    "b31475d63798f95abb1c7dde3fa00162c32485e647a8096acb2c8dee4fb01172" +
                    "d33495f657b8197a",
                "4262ec6971add00cdbdc882ac417b9e65890931d72d3517d7b457e82159b9f26" +
                    "555118dd5db7b7e6d2e44984c492a140"
            ),
        )
        for ((inputHex, expectedHex) in cases) {
            assertEquals(expectedHex, Sha3.sha3_384(inputHex.fromHex()).toHex())
        }
    }

    @Test
    fun testSha3_512KnownAnswers() {
        val cases = listOf(
            Pair(
                "",
                "a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a6" +
                    "15b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26"
            ),
            Pair(
                "616263",
                "b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e" +
                    "10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0"
            ),
            Pair(
                "03101d2a3744515e6b7885929facb9c6d3e0edfa0714212e3b4855626f7c8996" +
                    "a3b0bdcad7e4f1fe0b1825323f4c596673808d9aa7b4c1cedbe8f5020f1c2936" +
                    "43505d6a778491",
                "8cdcc277978e918c910ea8f1e84c3801adca0b0e7ad23eb245828bc9b9c20973" +
                    "869b8c1b6054d8abe91a077cd9975a9681fa29f3bfbd7c095ffe6df8a4d54465"
            ),
            Pair(
                "091a2b3c4d5e6f8091a2b3c4d5e6f708192a3b4c5d6e7f90a1b2c3d4e5f60718" +
                    "293a4b5c6d7e8fa0b1c2d3e4f5061728394a5b6c7d8e9fb0c1d2e3f405162738" +
                    "495a6b7c8d9eafc0",
                "90043ab278080c3855368c732984fd7f295890c604bb24b19918bc1a4e685e60" +
                    "a9d76b8d9970fe2f62c2a64d16ae3558cec579b45eecdaf1c1c7fdf6423452a8"
            ),
            Pair(
                "0d2c4b6a89a8c7e60524436281a0bfdefd1c3b5a7998b7d6f51433527190afce" +
                    "ed0c2b4a6988a7c6e504234261809fbeddfc1b3a597897b6d5f4133251708fae" +
                    "cdec0b2a496887a6c5",
                "d24e807fddb5f6fc3ca112f8ebaeaa6bb0bed3313f9586f2605e3f59e3969b56" +
                    "26b04ca151cc385c5069122bfd3d6f54d154abdf100c4fb92398e09a4cdd2333"
            ),
            Pair(
                "1374d53697f859ba1b7cdd3e9f0061c22384e546a70869ca2b8ced4eaf1071d2" +
                    "3394f556b71879da3b9cfd5ebf2081e243a40566c72889ea4bac0d6ecf3091f2" +
                    "53b41576d73899fa5bbc1d7edf40a10263c42586e748a90a6bcc2d8eef50b112" +
                    "73d43596f758b91a7bdc3d9eff60c12283e445a60768c92a8bec4dae0f70d132" +
                    "93f455b61778d93a9bfc5dbe1f80e142a30465c62788e94aab0c6dce2f90f152" +
                    "b31475d63798f95abb1c7dde3fa00162c32485e647a8096acb2c8dee4fb01172" +
                    "d33495f657b8197a",
                "98f4a4b25f0326bf3c227968098678c5bef552a295541387436b881fb24f443b" +
                    "19fc3bf5a5f6d713c45b016341cc30cada89357a4aba16c8f474a908a5e39952"
            ),
        )
        for ((inputHex, expectedHex) in cases) {
            assertEquals(expectedHex, Sha3.sha3_512(inputHex.fromHex()).toHex())
        }
    }

    @Test
    fun testShake256KnownAnswers() {
        val cases = listOf(
            Triple(
                "",
                32,
                "46b9dd2b0ba88d13233b3feb743eeb243fcd52ea62b81b82b50c27646ed5762f"
            ),
            Triple(
                "616263",
                32,
                "483366601360a8771c6863080cc4114d8db44530f8f1e1ee4f94ea37e78b5739"
            ),
            Triple(
                "616263",
                64,
                "483366601360a8771c6863080cc4114d8db44530f8f1e1ee4f94ea37e78b5739" +
                "d5a15bef186a5386c75744c0527e1faa9f8726e462a12a4feb06bd8801e751e4"
            ),
            Triple(
                "031425364758697a8b9cadbecfe0f102132435465768798a9bacbdcedff00112" +
                "233445566778899aabbccddeef00112233445566778899aabbccddeeff102132" +
                "435465768798a9bacbdcedfe0f2031425364758697a8b9cadbecfd0e1f304152" +
                "63748596a7b8c9daebfc0d1e2f405162738495a6b7c8d9eafb0c1d2e3f506172" +
                "8394a5b6c7d8e9",
                136,
                "872c7d7192a9d1a5d23b15f25b7f5530684bb1bac638e286681b3c3953a3b97f" +
                "af76182bd131dfdae1a78c6d47e4b9c9e4be1b4a48e8188c3dcdacfe14311799" +
                "39b9608e0967ecd0aaabe996b5231299395e58692665bc3f1604fadda2db90dd" +
                "a1b443d9e3025544f8d58c2bf4a918b7933ed2c54c7a0958db0be1648adbab0c" +
                "ac3251c731d2aa54"
            ),
            Triple(
                "0726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8" +
                "e70625446382a1c0dffe1d3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8" +
                "c7e60524436281a0bfdefd1c3b5a7998b7d6f51433527190afceed0c2b4a6988" +
                "a7c6e504234261809fbeddfc1b3a597897b6d5f4133251708faecdec0b2a4968" +
                "87a6c5e403224160",
                136,
                "b4528271407be681bf55998f4f3918c97019bb6280bd42d3b9300822760586f5" +
                "c4a0e6f1349d2e05a8f96bffee2e9cd217015ba8a116c23b0f287ecc4c73a6bd" +
                "c62a9a50e7475fa42777049e35d54e52b877c6e4191574dac40f9a1f3356a0a5" +
                "860c87ed43707281cf022ca384970d4547270e2dcd2e0362bb7a91b270153fe8" +
                "949bade1b19bedba"
            ),
            Triple(
                "0b4075aadf14497eb3e81d5287bcf1265b90c5fa2f6499ce03386da2d70c4176" +
                "abe0154a7fb4e91e5388bdf2275c91c6fb30659acf04396ea3d80d4277ace116" +
                "4b80b5ea1f5489bef3285d92c7fc31669bd0053a6fa4d90e4378ade2174c81b6" +
                "eb20558abff4295e93c8fd32679cd1063b70a5da0f4479aee3184d82b7ec2156" +
                "8bc0f52a5f94c9fe33",
                256,
                "69ae6b0c0ecba50fe452bffbe570e3155d512c814dc92f6729404cf0bfdce743" +
                "b385d8620c8aace4fb59e2c739f36975b735dbeeef2286c7508b00764951551d" +
                "72ae0dd94ee09588a4a2d3ac3e4932a56d7311e60bb921b4b7fecc9372ae8a1b" +
                "10821a75c300c6a8a500720a72ec4ba7e0f6eabb1cd95d8cf88cfadedeed7499" +
                "adf7527df3f3e4cbcbbda52101fb41d0df21f1af6da75e81a700304e5faf5fa7" +
                "2868f665488fb96b1ee289a6e4e545ffe71b74e83af726fd31c9bfadd7d842c4" +
                "03975440a1c8b6e8ab3a7fd08e03d7c8356dc16aedc9c11ceec299465ac711e5" +
                "4a38dbd6d7553d14d257710f459c46cd057eb8023419c8dc134e4171f656776d"
            ),
            Triple(
                "1374d53697f859ba1b7cdd3e9f0061c22384e546a70869ca2b8ced4eaf1071d2" +
                "3394f556b71879da3b9cfd5ebf2081e243a40566c72889ea4bac0d6ecf3091f2" +
                "53b41576d73899fa5bbc1d7edf40a10263c42586e748a90a6bcc2d8eef50b112" +
                "73d43596f758b91a7bdc3d9eff60c12283e445a60768c92a8bec4dae0f70d132" +
                "93f455b61778d93a9bfc5dbe1f80e142a30465c62788e94aab0c6dce2f90f152" +
                "b31475d63798f95abb1c7dde3fa00162c32485e647a8096acb2c8dee4fb01172" +
                "d33495f657b8197a",
                300,
                "294eb2ba6e03644e9f2c548b23338d04f2d56dbe5f0d39914d8743aa9e3ad6e4" +
                "56337fd7195ae56cf15800bf0c878075c6011c3fbf937f22afb6f206bf0db3fe" +
                "fca904c74fe02b73c27c4a8584d56002d7a3fd210f433145027595ebc7eb8f33" +
                "d4303f949a2a305665fa92b1669f728142167788e343a2889fc072d273e3b578" +
                "bca24bccfb482fb99f3c6d4c07da4643fc75f97e36e3970cf72b2a5ac2c5c892" +
                "30d91071218fc8479df4a812f829e5fcf37fee46811b48a04aed919dd04603be" +
                "38af0552cc47798f6f6b88cb6fbb8ddf27de0d43831ac7652d75811b774ba3f5" +
                "edfe5bdc4f4b34524fede27547dd3ee32113f6f625a787ba928bfc82e5847aa9" +
                "5ff22cad3720136eb71313835a7d5f2672a3b2dfaf0526225b0aa7f6d090c218" +
                "4241d37d60be8e1bb61a273d"
            )
        )
        for ((inputHex, outLen, expectedHex) in cases) {
            assertEquals(expectedHex, Sha3.shake256(inputHex.fromHex(), outputLength = outLen).toHex())
        }
    }

    @Test
    fun testMultiChunkAndEdgeLengths() {
        val part1 = ByteArray(100) { (it * 7 + 1).toByte() }
        val part2 = ByteArray(36) { (it * 13 + 5).toByte() }
        val part3 = ByteArray(77) { (it * 29 + 9).toByte() }
        val combined = part1 + part2 + part3
        assertContentEquals(Sha3.sha3_256(combined), Sha3.sha3_256(part1, part2, part3))
        assertContentEquals(Sha3.sha3_384(combined), Sha3.sha3_384(part1, part2, part3))
        assertContentEquals(Sha3.sha3_512(combined), Sha3.sha3_512(part1, part2, part3))
        assertContentEquals(
            Sha3.shake256(combined, outputLength = 200),
            Sha3.shake256(part1, part2, part3, outputLength = 200)
        )
        assertEquals(0, Sha3.shake256(part1, outputLength = 0).size)
        assertFailsWith<IllegalArgumentException> {
            Sha3.shake256(part1, outputLength = -1)
        }
    }
}
