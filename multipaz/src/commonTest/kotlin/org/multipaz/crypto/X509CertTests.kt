package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.time.Clock
import kotlin.time.Instant
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class X509CertTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    // This is a key attestation recorded from an Android device and traces up to a Google CA.
    // It contains both EC and RSA keys of various sizes making it an useful test vector for
    // X509Cert implementations.
    //
    private val androidKeyCertChain = X509CertChain(
        listOf(
            "308202ce30820273a003020102020101300a06082a8648ce3d040302303931293027060355040313203037653832313634373436306364383232663631363063383565386138616261310c300a060355040a1303544545301e170d3234303631383032313533335a170d3234303731383032313533335a301f311d301b06035504031314416e64726f6964204b657973746f7265204b65793059301306072a8648ce3d020106082a8648ce3d03010703420004ac858c2a816562929c5c69bfd64aacaf7c2e84c0db4f0b71a6a1dd153a72265c1b91fb8608e39bedf168a56634966b6fd4c140796397ce3171e50c8a5a30ebc6a382018430820180300e0603551d0f0101ff0404030207803082016c060a2b06010401d6790201110482015c308201580202012c0a01010202012c0a010104094368616c6c656e6765040030818ebf831008020601902920cc83bf83110802060190c39f9483bf83120802060190c39f9483bf853d08020601902920cca4bf85455a045830563130302e0429636f6d2e616e64726f69642e6964656e746974792e7365637572655f617265615f746573745f61707002010131220420e180e58c9e4bfb40d5e68e528be1a668e70361537528b8eda19bf900863f11223081a9a1053103020102a203020103a30402020100a5053103020104aa03020101bf837803020103bf83790302010abf853e03020100bf85404c304a042000000000000000000000000000000000000000000000000000000000000000000101000a01020420c95b9d89c2adc6bed35d630cfb43e8f514a99e521a0944348009e6651d7c5414bf85410502030222e0bf8542050203031647bf854e0602040134b3c1bf854f0602040134b3c1300a06082a8648ce3d0403020349003046022100bb9878eabc1ea4bbf095d77aba6ebb93ea24988f3bdbc87b2894be22aa905767022100a1f10252af737d4d836b6a222422f0e037b23efbea3bed882ef7db8ebd16ac68".fromHex(),
            "308201d63082017ca003020102021007e821647460cd822f6160c85e8a8aba300a06082a8648ce3d040302302931133011060355040a130a476f6f676c65204c4c43311230100603550403130944726f696420434133301e170d3234303532333233323432305a170d3234303632303233323432305a303931293027060355040313203037653832313634373436306364383232663631363063383565386138616261310c300a060355040a13035445453059301306072a8648ce3d020106082a8648ce3d0301070342000402a3dd51ea8533eda9abad1b4dd4df5674abb01c839a21c7918f85d89eb77aaa3d570e522ba79ba40d26e21db20bf5a955c789711a9e1788b4592ea14c5487f8a3763074301d0603551d0e0416041421136b05cef61befcd0cd43dc8b15df83b6d3e5c301f0603551d230418301680141cc2bd40dba29c4bdc06a6a10d4694ffb2f5c02a300f0603551d130101ff040530030101ff300e0603551d0f0101ff0404030202043011060a2b06010401d67902011e0403a10110300a06082a8648ce3d0403020348003045022100e5c42005468e145cf200c4ba618351ee297fe9af5e228b258a57b7b45e29d9a702204ee2b68c0754019e743d60c0b013d62fc5914c4ac6088c04b484c27e74e0751b".fromHex(),
            "308201d63082015ca0030201020213408ad01b2be2d94e2ffdd454b3cb87bba93cd9300a06082a8648ce3d040303302931133011060355040a130a476f6f676c65204c4c43311230100603550403130944726f696420434132301e170d3234303532353230343535395a170d3234303731343230343535385a302931133011060355040a130a476f6f676c65204c4c43311230100603550403130944726f6964204341333059301306072a8648ce3d020106082a8648ce3d03010703420004b893145670be40c9d352400297edef3bc9a1fea89d0b4fb0226eb4363aa2cdf8b47cc5b8ce77036389ba4a1bf257fefc0fa40f667598393dab429cc032f2d9cea3633061300e0603551d0f0101ff040403020204300f0603551d130101ff040530030101ff301d0603551d0e041604141cc2bd40dba29c4bdc06a6a10d4694ffb2f5c02a301f0603551d23041830168014bbf836ad89ae6ce2e59e94f0d5b2d7d27ae47c41300a06082a8648ce3d040303036800306502305c2c09ba5a4320deb6c27f1d7adbfef8592fc5cbb9da4a3c551268d2798ba027f27e30f41fab084c29271ad27cf16ad60231009e6031b2fcfcc424234a36b3ec60153374c46d65a5e20191f7394f08f466d3fe36f896a9d24a00350059082b9eb2c525".fromHex(),
            "3082038030820168a003020102020a0388266760658996860d300d06092a864886f70d01010b0500301b311930170603550405131066393230303965383533623662303435301e170d3232303132363232343735325a170d3337303132323232343735325a302931133011060355040a130a476f6f676c65204c4c43311230100603550403130944726f6964204341323076301006072a8648ce3d020106052b8104002203620004ba9a716d9bc98303575deeaa40a89d5ea52dd68a13f0ce90fb2b16230a5db8cd846a5493f2ffc2cb624dd746aa4df5124e12616bfa02b46f85f53514be239c87a45a86ba6357205d868a4cf4c1439427e488290f08f1aed8e0cc8fb3bf6ca9e4a3663064301d0603551d0e04160414bbf836ad89ae6ce2e59e94f0d5b2d7d27ae47c41301f0603551d230418301680143661e1007c880509518b446c47ff1a4cc9ea4f1230120603551d130101ff040830060101ff020102300e0603551d0f0101ff040403020106300d06092a864886f70d01010b05000382020100817152214761f39baab24cd1e79723e919153e7bc01a6586af9312377eefc9c859b2af28650596fb7b22e5c7cd9ed825db520ecee516d0acc5776b58498b724c510c245cb61dc46f595e15eb294b7e4d00e7741de920f2fcb61b7d4e5807b9ebb97a35d2e3ab2b6e91c178280cc7211c68162bcdfad9641099a0ec4430f43db03eae0aa41b6e838a09139dd1f5b2c24f9c12563eb7865cc4637842d0da1202da7491db61a30f3a5055732eac448d1d81d4c3c2325c887e81b206093201685dda058dea955e43c459a0ee607cf990b82b27f26f64bff75c9453162da39ac204b8932027f62462db26231002345558bbbb75c3801567a30b58027e2099ae6ae0175781b59b51368c555559b1ea57294a43a4acc47ecb3a230d33c761aa97be73443b20cae0f23540f3badbc3330f268099859168e592a9153367620d9c9dce6822f1baf6b094cb746e25530bde74868b8239c80c41b527cff9c5c3d540916045fde6e571190788631b759eff8f69f89455412a44d7e4331b4ada62974db310543bee35762428eea0bb4bcc3627e4cc7867692868c22332a3503032dbe80e99975969c6a714dff4dd2c9d6ca5475746ff35845631ae156996e898a38ddfc207d9ea7ca5d8fefa7992217796a5013c2e73047675058ef0f3a747f410b497556dbee716e5f9f72a5a098eba9994e3e6f13492ed9272e8e3d060e70f63367ed3eeb905".fromHex(),
            "3082051c30820304a003020102020900d50ff25ba3f2d6b3300d06092a864886f70d01010b0500301b311930170603550405131066393230303965383533623662303435301e170d3139313132323230333735385a170d3334313131383230333735385a301b31193017060355040513106639323030396538353362366230343530820222300d06092a864886f70d01010105000382020f003082020a0282020100afb6c7822bb1a701ec2bb42e8bcc541663abef982f32c77f7531030c97524b1b5fe809fbc72aa9451f743cbd9a6f1335744aa55e77f6b6ac3535ee17c25e639517dd9c92e6374a53cbfe258f8ffbb6fd129378a22a4ca99c452d47a59f3201f44197ca1ccd7e762fb2f53151b6feb2fffd2b6fe4fe5bc6bd9ec34bfe08239daafceb8eb5a8ed2b3acd9c5e3a7790e1b51442793159859811ad9eb2a96bbdd7a57c93a91c41fccd27d67fd6f671aa0b815261ad384fa37944864604ddb3d8c4f920a19b1656c2f14ad6d03c56ec060899041c1ed1a5fe6d3440b556bad1d0a152589c53e55d370762f0122eef91861b1b0e6c4c80927499c0e9bec0b83e3bc1f93c72c049604bbd2f1345e62c3f8e26dbec06c94766f3c128239d4f4312fad8123887e06becf567583bf8355a81feeabaf99a83c8df3e2a322afc672bf120b135158b6821ceaf309b6eee77f98833b018daa10e451f06a374d50781f359082966bb778b9308942698e74e0bcd24628a01c2cc03e51f0b3e5b4ac1e4df9eaf9ff6a492a77c1483882885015b422ce67b80b88c9b48e13b607ab545c723ff8c44f8f2d368b9f6520d31145ebf9e862ad71df6a3bfd2450959d653740d97a12f368b13ef66d5d0a54a6e2f5d9a6fef446832bc67844725861f093dd0e6f3405da89643ef0f4d69b6420051fdb93049673e36950580d3cdf4fbd08bc58483952600630203010001a3633061301d0603551d0e041604143661e1007c880509518b446c47ff1a4cc9ea4f12301f0603551d230418301680143661e1007c880509518b446c47ff1a4cc9ea4f12300f0603551d130101ff040530030101ff300e0603551d0f0101ff040403020204300d06092a864886f70d01010b050003820201004e31a05cf28ba65dbdafa1ced70969ee5ca84104added8a306cf7f6dee50375d745ed992cb0242cce72dc9eed51191fe5ad52bad7dd3b25c099e13a491a3cdd487a5acce8766324c4ae46338246ae7b78a418acbb98a05c4c9d696eeaab609d0ba0ce1a31be98490df3f4c0ea9ddc9e82ffb0fcb3e9ebdd8cb952789f2b1411fac56c886426eb7296042735da50e11ac715f1818cf9fdc4e254a3763351b6a2440150861263a6e310be1a50de5c7e8ee880fdd4be5884a37128d18830bb3476bf4291e82d5c66a6494939e08480bfbc00f7d8a74d43e73737ebe5d8e4ec515302d4689692780dc7538ed7e9175be6139e74d43ad388b3050ffd5a9de5262000898c01f63c53dfe22209108fa4f65ba16c49ccbde0837d7c5844d54b7398ba0122e505b155c9313cfe26e72d87e22aa1616e6bdbf547ddff93df29e35a63b455fe1fc0ec95581f3f4f7bbe3bb828396a37ae3157582bc3764b9780a239efc0f75a1e2e6d941ceabac27ddeb01e2bd8421029bea34d51aee6c60271d5a95ebd00515a9c0013dd80bf87eea260b81c34f688e6eb1348af0d8ea1cac32acb9d93fa24aff030a84c8f2b0f569cc95080b20ac35ace0c6d8dbd4f6847719519d32450166eb4bf15b859044501adeaf436382c34b15e3b54c92e61b69c2bfc7264589172b3c93dbe35ce06d08fd5c01322ca0877b1d12743af1fad5940ea1bc02dd891c".fromHex(),
        ).map { X509Cert(ByteString(it)) }
    )

    // The public EC keys (X || Y) for the first four certificates from the chain above. The fifth
    // certificate is for an RSA key.
    //
    private val androidKeyCertChainKeysRaw = listOf(
        "ac858c2a816562929c5c69bfd64aacaf7c2e84c0db4f0b71a6a1dd153a72265c1b91fb8608e39bedf168a56634966b6fd4c140796397ce3171e50c8a5a30ebc6",
        "02a3dd51ea8533eda9abad1b4dd4df5674abb01c839a21c7918f85d89eb77aaa3d570e522ba79ba40d26e21db20bf5a955c789711a9e1788b4592ea14c5487f8",
        "b893145670be40c9d352400297edef3bc9a1fea89d0b4fb0226eb4363aa2cdf8b47cc5b8ce77036389ba4a1bf257fefc0fa40f667598393dab429cc032f2d9ce",
        "ba9a716d9bc98303575deeaa40a89d5ea52dd68a13f0ce90fb2b16230a5db8cd846a5493f2ffc2cb624dd746aa4df5124e12616bfa02b46f85f53514be239c87a45a86ba6357205d868a4cf4c1439427e488290f08f1aed8e0cc8fb3bf6ca9e4"
    )

    // The first certificate from the chain above, encoded as PEM
    //
    private val androidKeyCertChainFirstCertAsPem = """
        -----BEGIN CERTIFICATE-----
        MIICzjCCAnOgAwIBAgIBATAKBggqhkjOPQQDAjA5MSkwJwYDVQQDEyAwN2U4MjE2NDc0NjBjZDgy
        MmY2MTYwYzg1ZThhOGFiYTEMMAoGA1UEChMDVEVFMB4XDTI0MDYxODAyMTUzM1oXDTI0MDcxODAy
        MTUzM1owHzEdMBsGA1UEAxMUQW5kcm9pZCBLZXlzdG9yZSBLZXkwWTATBgcqhkjOPQIBBggqhkjO
        PQMBBwNCAASshYwqgWVikpxcab/WSqyvfC6EwNtPC3Gmod0VOnImXBuR+4YI45vt8WilZjSWa2/U
        wUB5Y5fOMXHlDIpaMOvGo4IBhDCCAYAwDgYDVR0PAQH/BAQDAgeAMIIBbAYKKwYBBAHWeQIBEQSC
        AVwwggFYAgIBLAoBAQICASwKAQEECUNoYWxsZW5nZQQAMIGOv4MQCAIGAZApIMyDv4MRCAIGAZDD
        n5SDv4MSCAIGAZDDn5SDv4U9CAIGAZApIMykv4VFWgRYMFYxMDAuBCljb20uYW5kcm9pZC5pZGVu
        dGl0eS5zZWN1cmVfYXJlYV90ZXN0X2FwcAIBATEiBCDhgOWMnkv7QNXmjlKL4aZo5wNhU3UouO2h
        m/kAhj8RIjCBqaEFMQMCAQKiAwIBA6MEAgIBAKUFMQMCAQSqAwIBAb+DeAMCAQO/g3kDAgEKv4U+
        AwIBAL+FQEwwSgQgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAQAKAQIEIMlbnYnC
        rca+011jDPtD6PUUqZ5SGglENIAJ5mUdfFQUv4VBBQIDAiLgv4VCBQIDAxZHv4VOBgIEATSzwb+F
        TwYCBAE0s8EwCgYIKoZIzj0EAwIDSQAwRgIhALuYeOq8HqS78JXXerpuu5PqJJiPO9vIeyiUviKq
        kFdnAiEAofECUq9zfU2Da2oiJCLw4DeyPvvqO+2ILvfbjr0WrGg=
        -----END CERTIFICATE-----
    """.trimIndent() + "\n"

    private val v1CertAsPem = """
        -----BEGIN CERTIFICATE-----
        MIIBITCByAIJAPeqGjkRIU3AMAoGCCqGSM49BAMCMBkxFzAVBgNVBAMMDnNhbXBs
        ZV92MV9jZXJ0MB4XDTI1MTAxNTE3MjQwOFoXDTM1MTAxMzE3MjQwOFowGTEXMBUG
        A1UEAwwOc2FtcGxlX3YxX2NlcnQwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARi
        hzx6zPwtrJFUlB7PW9sj+3n4A4j45VZiLDKgXRHhZuzUMdDkkZy0GLU9C62C3B++
        9urzuwq9B4nn7ZjRkC2hMAoGCCqGSM49BAMCA0gAMEUCIQCKlDxbTr7LLmTF8ZrU
        8l6thMpHotRMYQWcJcJOWaduVAIgDCLyLqpNcNUQjZDg4OGNWG9H7QxyqVqRCO8e
        Jh/gpxs=
        -----END CERTIFICATE-----
    """.trimIndent()

    @Test
    fun testPemEncodingRoundTrip() {
        // Check that each certificate is signed by the next one...
        for (cert in androidKeyCertChain.certificates) {
            val pemEncoded = cert.toPem()
            val certFromPem = X509Cert.fromPem(pemEncoded)
            assertEquals(certFromPem, cert)
        }
    }

    @Test
    fun testCborRoundTrip() {
        // Check that each certificate is signed by the next one...
        for (cert in androidKeyCertChain.certificates) {
            val cborEncoded = cert.toDataItem()
            val certFromCbor = X509Cert.fromDataItem(cborEncoded)
            assertEquals(certFromCbor, cert)
        }
    }

    @Test
    fun testToPem() {
        val toPem = androidKeyCertChain.certificates[0].toPem()
            .replace("\r\n", "\n")
        assertEquals(androidKeyCertChainFirstCertAsPem, toPem)
    }

    // TODO: add tests for certificates with keys of all supported curves

    @Test
    fun testEcPublicKey() {
        // Check we can extract EC public keys for certs with EC keys and that we throw
        // if the certificate does not have an EC key. In this case the first three
        // certificates are for P-256 keys, the fourth for a P-384 key, and the fifth
        // for a 4096-bit RSA key.
        //
        for (n in IntRange(0, androidKeyCertChain.certificates.size - 1)) {
            when (n) {
                0, 1, 2, 3 -> {
                    val cert = androidKeyCertChain.certificates[n]
                    val publicKey = cert.ecPublicKey as EcPublicKeyDoubleCoordinate
                    assertEquals(
                        androidKeyCertChainKeysRaw[n],
                        (publicKey.x + publicKey.y).toHex()
                    )
                }
                else -> {
                    assertFailsWith(IllegalStateException::class) {
                        val publicKey = androidKeyCertChain.certificates[n].ecPublicKey
                    }
                }
            }
        }
    }

    // Checks that X509Cert.verify() works with certificates created by X509Cert.Builder
    private fun testCertSignedWithCurve(curve: EcCurve) = runTest {
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Skipping testCertSignedWithCurve($curve) since platform does not support the curve.")
            return@runTest
        }

        val key = Crypto.createEcPrivateKey(curve)
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val serialNumber = ASN1Integer(1)
        val subject = X500Name.fromName("CN=Foobar1")
        val issuer = X500Name.fromName("CN=Foobar2")
        val cert = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, key.curve.defaultSigningAlgorithm),
            serialNumber = serialNumber,
            subject = subject,
            issuer = issuer,
            validFrom = now - 1.hours,
            validUntil = now + 1.hours
        )
            .includeSubjectKeyIdentifier()
            .includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
            .build()

        cert.verify(key.publicKey)

        // Also check that the fields are as expected.
        assertEquals(curve.defaultSigningAlgorithm, cert.signatureAlgorithm)
        assertEquals(2, cert.version)
        assertEquals(cert.serialNumber, serialNumber)
        assertEquals(cert.issuer, issuer)
        assertEquals(cert.validityNotBefore, now - 1.hours)
        assertEquals(cert.validityNotAfter, now + 1.hours)
        assertEquals(cert.subject, subject)
        assertEquals(cert.ecPublicKey, key.publicKey)
        assertEquals(
            setOf(
                OID.X509_EXTENSION_SUBJECT_KEY_IDENTIFIER.oid,
                OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid,
            ),
            cert.nonCriticalExtensionOIDs
        )
        assertTrue(cert.criticalExtensionOIDs.isEmpty())

        val subjectPublicKey = when (key.publicKey) {
            is EcPublicKeyDoubleCoordinate -> {
                (key.publicKey as EcPublicKeyDoubleCoordinate).asUncompressedPointEncoding
            }
            is EcPublicKeyOkp -> {
                (key.publicKey as EcPublicKeyOkp).x
            }
        }

        // Check the subjectKeyIdentifier and authorityKeyIdentifier extensions are correct and
        // also correctly encoded.
        val expectedSubjectKeyIdentifier = Crypto.digest(Algorithm.INSECURE_SHA1, subjectPublicKey)
        assertEquals(expectedSubjectKeyIdentifier.toHex(), cert.subjectKeyIdentifier!!.toHex())
        assertEquals(expectedSubjectKeyIdentifier.toHex(), cert.authorityKeyIdentifier!!.toHex())
        assertEquals(
            "OCTET STRING (20 byte) ${expectedSubjectKeyIdentifier.toHex(byteDivider = " ", decodeAsString = true)}",
            ASN1.print(ASN1.decode(cert.getExtensionValue(OID.X509_EXTENSION_SUBJECT_KEY_IDENTIFIER.oid)!!)!!).trim()
        )
        assertEquals(
            """
                SEQUENCE (1 elem)
                  [0] (1 elem)
                    ${expectedSubjectKeyIdentifier.toHex(byteDivider = " ", decodeAsString = true)}
            """.trimIndent().trim(),
            ASN1.print(ASN1.decode(cert.getExtensionValue(OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid)!!)!!).trim()
        )
    }

    @Test fun testCertSignedWithCurve_P256() = testCertSignedWithCurve(EcCurve.P256)
    @Test fun testCertSignedWithCurve_P384() = testCertSignedWithCurve(EcCurve.P384)
    @Test fun testCertSignedWithCurve_P521() = testCertSignedWithCurve(EcCurve.P521)
    @Test fun testCertSignedWithCurve_BRAINPOOLP256R1() = testCertSignedWithCurve(EcCurve.BRAINPOOLP256R1)
    @Test fun testCertSignedWithCurve_BRAINPOOLP320R1() = testCertSignedWithCurve(EcCurve.BRAINPOOLP320R1)
    @Test fun testCertSignedWithCurve_BRAINPOOLP384R1() = testCertSignedWithCurve(EcCurve.BRAINPOOLP384R1)
    @Test fun testCertSignedWithCurve_BRAINPOOLP512R1() = testCertSignedWithCurve(EcCurve.BRAINPOOLP512R1)
    @Test fun testCertSignedWithCurve_ED25519() = testCertSignedWithCurve(EcCurve.ED25519)
    @Test fun testCertSignedWithCurve_ED448() = testCertSignedWithCurve(EcCurve.ED448)

    @Test
    fun testKeyUsageEncoding() {
        assertEquals(
            "BIT STRING (9 bit) 000000001",
            ASN1.print(X509KeyUsage.encodeSet(setOf(
                X509KeyUsage.DECIPHER_ONLY
            ))).trim()
        )
        assertEquals(
            X509KeyUsage.encodeSet(setOf(
                X509KeyUsage.DECIPHER_ONLY
            )),
            ASN1.decode("0303070080".fromHex())
        )

        // Check that we handle trailing zero bits correctly
        assertEquals(
            "BIT STRING (7 bit) 0000011",
            ASN1.print(X509KeyUsage.encodeSet(setOf(
                X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.CRL_SIGN
            ))).trim()
        )
        assertEquals(
            X509KeyUsage.encodeSet(setOf(
                X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.CRL_SIGN
            )),
            ASN1.decode("03020106".fromHex())
        )
    }

    @Test
    fun testExtensions() = runTest {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()

        val extData = byteArrayOf(1, 2, 3)

        val cert = buildX509Cert(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, key.curve.defaultSigningAlgorithmFullySpecified),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Foo"),
            issuer = X500Name.fromName("CN=Foo"),
            validFrom = now - 1.days,
            validUntil = now + 1.days,
        ) {
            addExtension("1.2.3", false, extData)
            addExtension(X509Extension("1.2.4", true, ByteString(extData)))
        }
        assertEquals(2, cert.extensions.size)

        assertEquals("1.2.3", cert.extensions[0].oid)
        assertEquals(false, cert.extensions[0].isCritical)
        assertEquals(ByteString(extData), cert.extensions[0].data)
        assertContentEquals(extData, cert.getExtensionValue("1.2.3"))

        assertEquals("1.2.4", cert.extensions[1].oid)
        assertEquals(true, cert.extensions[1].isCritical)
        assertEquals(ByteString(extData), cert.extensions[1].data)
        assertContentEquals(extData, cert.getExtensionValue("1.2.4"))
    }

    @Test
    fun testV1() = runTest {
        val v1Cert = X509Cert.fromPem(v1CertAsPem)
        v1Cert.verify(v1Cert.ecPublicKey)
        assertEquals("CN=sample_v1_cert", v1Cert.subject.name)
        assertTrue(v1Cert.extensions.isEmpty())
    }
}