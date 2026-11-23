package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.prompt.Reason
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareKeyUnlockData
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import org.multipaz.webtoken.WebTokenClaim
import org.multipaz.webtoken.WebTokenClaim.Companion.get
import org.multipaz.webtoken.WebTokenClaim.Companion.put
import org.multipaz.webtoken.buildCwt
import org.multipaz.webtoken.buildJwt
import org.multipaz.webtoken.validateCwt
import org.multipaz.webtoken.validateJwt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class HybridSignatureTests {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private data class JoseVector(
        val name: String,
        val algorithm: Algorithm,
        val kid: String,
        val joseAlg: String,
        val pub: String,
        val priv: String,
        val jws: String,
        val rawCompositePubHex: String,
        val rawCompositeSigHex: String,
        val rawToBeSignedHex: String,
        val rawMessageRepresentativeHex: String,
    )

    private data class CoseVector(
        val name: String,
        val algorithm: Algorithm,
        val kidHex: String,
        val pubHex: String,
        val privHex: String,
        val payloadHex: String,
        val mPrimeHex: String,
        val signatureHex: String,
    )

    @Test
    fun testDraft07JoseVectors() = runTest {
        for (vec in joseVectors) {
            if (vec.algorithm !in Crypto.supportedHybridSigningAlgorithms) {
                continue
            }
            val jwk = buildJsonObject {
                put("kty", "AKP")
                put("alg", vec.joseAlg)
                put("pub", vec.pub)
                put("priv", vec.priv)
            }
            val privKey = PrivateKey.fromJwk(jwk)
            assertTrue(privKey is HybridPrivateKey, vec.name)
            try {
                val pubKey = PublicKey.fromJwk(
                    buildJsonObject {
                        put("kty", "AKP")
                        put("alg", vec.joseAlg)
                        put("pub", vec.pub)
                    }
                )
                assertTrue(pubKey is HybridPublicKey, vec.name)
                assertEquals(privKey.publicKey, pubKey, vec.name)
                assertContentEquals(vec.rawCompositePubHex.fromHex(), pubKey.encoded.toByteArray(), vec.name)
                assertEquals(
                    vec.kid,
                    pubKey.toJwkThumbprint(Algorithm.SHA256).toByteArray().toBase64Url(),
                    "kid mismatch for ${vec.name}"
                )

                val spec = HybridSigning.specFor(vec.algorithm)
                val computedMPrime = HybridSigning.buildMPrime(spec, vec.rawToBeSignedHex.fromHex())
                assertContentEquals(vec.rawMessageRepresentativeHex.fromHex(), computedMPrime, vec.name)

                // Verify the raw signature bytes from the vector
                Crypto.checkSignature(
                    pubKey,
                    vec.rawToBeSignedHex.fromHex(),
                    vec.algorithm,
                    HybridSignature(vec.rawCompositeSigHex.fromHex())
                )

                // Verify the JWS string from the vector
                JsonWebSignature.verify(vec.jws, pubKey)

                // Re-sign using our implementation and verify
                val reSigned = Crypto.sign(privKey, vec.algorithm, vec.rawToBeSignedHex.fromHex())
                Crypto.checkSignature(pubKey, vec.rawToBeSignedHex.fromHex(), vec.algorithm, reSigned)
            } finally {
                privKey.close()
            }
        }
    }

    @Test
    fun testDraft07CoseVectors() = runTest {
        for (vec in coseVectors) {
            if (vec.algorithm !in Crypto.supportedHybridSigningAlgorithms) {
                continue
            }
            val coseAlg = vec.algorithm.coseAlgorithmIdentifier!!
            val coseKey = CoseKey(
                mapOf(
                    CoseNumberLabel(Cose.COSE_KEY_KTY) to Cose.COSE_KEY_TYPE_AKP.toDataItem(),
                    CoseNumberLabel(Cose.COSE_KEY_ALG) to coseAlg.toDataItem(),
                    CoseNumberLabel(Cose.COSE_KEY_PARAM_PUB_KEY) to
                        vec.pubHex.fromHex().toDataItem(),
                    CoseNumberLabel(Cose.COSE_KEY_PARAM_PRIV_KEY) to
                        vec.privHex.fromHex().toDataItem(),
                )
            )
            val privKey = PrivateKey.fromCoseKey(coseKey)
            assertTrue(privKey is HybridPrivateKey, vec.name)
            try {
                val pubKey = PublicKey.fromCoseKey(privKey.publicKey.toCoseKey())
                assertTrue(pubKey is HybridPublicKey, vec.name)

                val sign1 = CoseSign1(
                    protectedHeaders = mapOf(
                        CoseNumberLabel(Cose.COSE_LABEL_ALG) to coseAlg.toDataItem(),
                        CoseNumberLabel(Cose.COSE_LABEL_KID) to vec.kidHex.fromHex().toDataItem(),
                    ),
                    unprotectedHeaders = emptyMap(),
                    signature = vec.signatureHex.fromHex(),
                    payload = vec.payloadHex.fromHex(),
                )
                Cose.coseSign1Check(pubKey, null, sign1, vec.algorithm)

                // Re-sign via Cose.coseSign1Sign and verify
                val reSigned = Cose.coseSign1Sign(
                    signingKey = AsymmetricKey.anonymous(privKey, vec.algorithm),
                    message = vec.payloadHex.fromHex(),
                    includeMessageInPayload = true,
                    protectedHeaders = mapOf(
                        CoseNumberLabel(Cose.COSE_LABEL_ALG) to coseAlg.toDataItem()
                    ),
                    unprotectedHeaders = emptyMap(),
                )
                Cose.coseSign1Check(pubKey, null, reSigned, vec.algorithm)
            } finally {
                privKey.close()
            }
        }
    }

    @Test
    fun testCreateKeySignVerifyAndTamper() = runTest {
        val message = "PQ/T hybrid composite signature test message".encodeToByteArray()
        for (alg in HybridSigning.specs.map { it.algorithm }) {
            if (alg !in Crypto.supportedHybridSigningAlgorithms) {
                continue
            }
            Crypto.createHybridPrivateKey(alg).use { privKey ->
                val pubKey = privKey.publicKey
                val sig = Crypto.sign(privKey, alg, message)
                Crypto.checkSignature(pubKey, message, alg, sig)

                // Generic dispatchers
                val genSig = Crypto.sign(privKey as PrivateKey, alg, message)
                assertTrue(genSig is HybridSignature)
                Crypto.checkSignature(pubKey as PublicKey, message, alg, genSig)

                // Wrong message
                assertFailsWith<SignatureVerificationException> {
                    Crypto.checkSignature(pubKey, "Tampered".encodeToByteArray(), alg, sig)
                }

                // Tamper ML-DSA signature half (byte 0)
                val tamperedPq = sig.signature.copyOf()
                tamperedPq[0] = (tamperedPq[0].toInt() xor 0xFF).toByte()
                assertFailsWith<SignatureVerificationException> {
                    Crypto.checkSignature(pubKey, message, alg, HybridSignature(tamperedPq))
                }

                // Tamper traditional signature half (last byte)
                val tamperedTrad = sig.signature.copyOf()
                tamperedTrad[tamperedTrad.size - 1] =
                    (tamperedTrad[tamperedTrad.size - 1].toInt() xor 0xFF).toByte()
                assertFailsWith<SignatureVerificationException> {
                    Crypto.checkSignature(pubKey, message, alg, HybridSignature(tamperedTrad))
                }

                // Wrong algorithm
                val otherAlg = HybridSigning.specs.first { it.algorithm != alg }.algorithm
                assertFailsWith<IllegalArgumentException> {
                    Crypto.sign(privKey, otherAlg, message)
                }
                assertFailsWith<IllegalArgumentException> {
                    Crypto.checkSignature(pubKey, message, otherAlg, sig)
                }
            }
        }
    }

    @Test
    fun testSerializationRoundtrips() = runTest {
        for (alg in HybridSigning.specs.map { it.algorithm }) {
            if (alg !in Crypto.supportedHybridSigningAlgorithms) {
                continue
            }
            Crypto.createHybridPrivateKey(alg).use { privKey ->
                val pubKey = privKey.publicKey

                // SPKI roundtrip
                assertEquals(pubKey, HybridPublicKey.fromSubjectPublicKeyInfo(pubKey.toSubjectPublicKeyInfo()))

                // PKCS#8 roundtrip
                HybridPrivateKey.fromPrivateKeyInfo(privKey.toPrivateKeyInfo(), pubKey).use { fromPkcs8 ->
                    assertEquals(privKey, fromPkcs8)
                }

                // PEM roundtrip
                val pubPem = pubKey.toPem()
                assertEquals(pubKey, HybridPublicKey.fromPem(pubPem))
                assertEquals(pubKey, PublicKey.fromPem(pubPem))

                val privPem = privKey.toPem()
                HybridPrivateKey.fromPem(privPem, pubKey).use { fromPem ->
                    assertEquals(privKey, fromPem)
                }
                PrivateKey.fromPem(privPem, pubKey).use { fromGenPem ->
                    assertEquals(privKey, fromGenPem)
                }

                // JWK roundtrip
                val pubJwk = pubKey.toJwk()
                assertEquals(pubKey, HybridPublicKey.fromJwk(pubJwk))
                assertEquals(pubKey, PublicKey.fromJwk(pubJwk))

                val privJwk = privKey.toJwk()
                HybridPrivateKey.fromJwk(privJwk).use { fromJwk ->
                    assertEquals(privKey, fromJwk)
                }
                PrivateKey.fromJwk(privJwk).use { fromGenJwk ->
                    assertEquals(privKey, fromGenJwk)
                }

                // COSE Key + CBOR DataItem roundtrip
                val pubCose = pubKey.toCoseKey()
                assertEquals(pubKey, HybridPublicKey.fromCoseKey(pubCose))
                assertEquals(pubKey, PublicKey.fromCoseKey(pubCose))
                assertEquals(pubKey, HybridPublicKey.fromDataItem(pubKey.toDataItem()))
                assertEquals(pubKey, PublicKey.fromDataItem(pubKey.toDataItem()))

                val privCose = privKey.toCoseKey()
                HybridPrivateKey.fromCoseKey(privCose).use { fromCose ->
                    assertEquals(privKey, fromCose)
                }
                PrivateKey.fromCoseKey(privCose).use { fromGenCose ->
                    assertEquals(privKey, fromGenCose)
                }
                HybridPrivateKey.fromDataItem(privKey.toDataItem()).use { fromDi ->
                    assertEquals(privKey, fromDi)
                }
                PrivateKey.fromDataItem(privKey.toDataItem()).use { fromGenDi ->
                    assertEquals(privKey, fromGenDi)
                }
            }
        }
    }

    @Test
    fun testStrictWireFormatValidation() = runTest {
        if (Algorithm.ML_DSA_44_ES256 !in Crypto.supportedHybridSigningAlgorithms) {
            return@runTest
        }
        val vec = joseVectors.first { it.algorithm == Algorithm.ML_DSA_44_ES256 }
        val pubBytes = vec.rawCompositePubHex.fromHex()
        val privBytes = vec.priv.fromBase64Url()
        val pubKey = HybridPublicKey(Algorithm.ML_DSA_44_ES256, ByteString(pubBytes))

        // Public key with compressed point prefix 0x02 instead of 0x04 must fail
        val compressedPub = pubBytes.copyOf()
        compressedPub[1312] = 0x02
        assertFailsWith<IllegalArgumentException> {
            HybridPublicKey(Algorithm.ML_DSA_44_ES256, ByteString(compressedPub))
        }

        // Public key with off-curve EC point (flip last byte of y) must fail
        val offCurvePub = pubBytes.copyOf()
        offCurvePub[offCurvePub.size - 1] = (offCurvePub[offCurvePub.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<IllegalArgumentException> {
            HybridPublicKey(Algorithm.ML_DSA_44_ES256, ByteString(offCurvePub))
        }

        // Non-Table-3 ECPrivateKey (e.g., extra trailing byte / wrong length) must fail
        assertFailsWith<IllegalArgumentException> {
            HybridPrivateKey(Algorithm.ML_DSA_44_ES256, ByteString(privBytes + byteArrayOf(0x00)), pubKey)
        }

        // Valid signature mutated to non-minimal DER ECDSA component must fail
        val msg = vec.rawToBeSignedHex.fromHex()
        val sigBytes = vec.rawCompositeSigHex.fromHex()
        val pqHalf = sigBytes.copyOfRange(0, 2420)
        // Non-minimal DER: r padded with extra unnecessary leading 0x00 and adjusted sequence length
        val nonMinimalDer = byteArrayOf(
            0x30, 0x08,
            0x02, 0x02, 0x00, 0x01,
            0x02, 0x02, 0x00, 0x01
        )
        assertFailsWith<IllegalArgumentException> {
            Crypto.checkSignature(pubKey, msg, Algorithm.ML_DSA_44_ES256, HybridSignature(pqHalf + nonMinimalDer))
        }

        // Trailing garbage after DER SEQUENCE must fail
        val trailingDerSig = sigBytes + byteArrayOf(0x00)
        assertFailsWith<IllegalArgumentException> {
            Crypto.checkSignature(pubKey, msg, Algorithm.ML_DSA_44_ES256, HybridSignature(trailingDerSig))
        }
    }

    @Test
    fun testX509CertificateAndChain() = runTest {
        if (Algorithm.ML_DSA_65_ES256 !in Crypto.supportedHybridSigningAlgorithms) {
            return@runTest
        }
        val now = Clock.System.now()
        Crypto.createHybridPrivateKey(Algorithm.ML_DSA_65_ES256).use { rootPriv ->
            Crypto.createHybridPrivateKey(Algorithm.ML_DSA_44_ES256).use { leafPriv ->
                val rootKey = AsymmetricKey.anonymous(rootPriv, Algorithm.ML_DSA_65_ES256)
                val rootCert = X509Cert.Builder(
                    publicKey = rootPriv.publicKey,
                    signingKey = rootKey,
                    serialNumber = ASN1Integer(1),
                    subject = X500Name.fromName("CN=Hybrid Root CA"),
                    issuer = X500Name.fromName("CN=Hybrid Root CA"),
                    validFrom = now - 1.days,
                    validUntil = now + 30.days
                )
                    .includeSubjectKeyIdentifier()
                    .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                    .setBasicConstraints(ca = true, pathLenConstraint = null)
                    .build()
                assertEquals(Algorithm.ML_DSA_65_ES256, rootCert.signatureAlgorithm)
                assertTrue(rootCert.publicKey is HybridPublicKey)
                rootCert.verify(rootPriv.publicKey)

                val rootSigner = AsymmetricKey.X509CertifiedExplicit(
                    X509CertChain(listOf(rootCert)),
                    rootPriv,
                    Algorithm.ML_DSA_65_ES256
                )
                val leafCert = X509Cert.Builder(
                    publicKey = leafPriv.publicKey,
                    signingKey = rootSigner,
                    serialNumber = ASN1Integer(2),
                    subject = X500Name.fromName("CN=Hybrid Leaf"),
                    issuer = X500Name.fromName("CN=Hybrid Root CA"),
                    validFrom = now - 1.days,
                    validUntil = now + 10.days
                )
                    .includeSubjectKeyIdentifier()
                    .setAuthorityKeyIdentifierToCertificate(rootCert)
                    .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
                    .build()
                assertEquals(Algorithm.ML_DSA_65_ES256, leafCert.signatureAlgorithm)
                assertEquals(Algorithm.ML_DSA_44_ES256, (leafCert.publicKey as HybridPublicKey).algorithm)
                leafCert.verify(rootPriv.publicKey)

                val chain = X509CertChain(listOf(leafCert, rootCert))
                chain.validate()
            }
        }
    }

    @Test
    fun testJwtCwtAndSoftwareSecureArea() = runTest {
        for (alg in HybridSigning.specs.map { it.algorithm }) {
            if (alg !in Crypto.supportedHybridSigningAlgorithms) {
                continue
            }
            val key = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            try {
                // JWT roundtrip
                val jwt = buildJwt(
                    type = "JWT",
                    key = key,
                    expiresIn = 5.minutes
                ) {
                    put(WebTokenClaim.Sub, "hybrid-user")
                    put("custom_claim", JsonPrimitive("pq-t"))
                }
                val verifiedJwt = validateJwt(
                    jwt = jwt,
                    jwtName = "HybridTestJwt",
                    publicKey = key.publicKey,
                    checks = emptyMap()
                )
                assertEquals("hybrid-user", verifiedJwt[WebTokenClaim.Sub])

                // CWT roundtrip
                val cwt = buildCwt(
                    type = "application/cwt",
                    key = key,
                    expiresIn = 5.minutes
                ) {
                    put(WebTokenClaim.Sub, "hybrid-cwt-user")
                }
                val verifiedCwt = validateCwt(
                    cwt = cwt,
                    cwtName = "HybridTestCwt",
                    publicKey = key.publicKey,
                    checks = emptyMap()
                )
                assertEquals("hybrid-cwt-user", verifiedCwt[WebTokenClaim.Sub])
            } finally {
                key.close()
            }

            // SoftwareSecureArea plain + passphrase
            val storage = EphemeralStorage()
            val ssa = SoftwareSecureArea.create(storage)
            assertTrue(alg in ssa.supportedAlgorithms)

            val aliasPlain = "hybrid_plain_${alg.name}"
            ssa.createKey(aliasPlain, CreateKeySettings(algorithm = alg))
            val msg = "SoftwareSecureArea hybrid $alg".encodeToByteArray()
            val sigPlain = ssa.sign(aliasPlain, msg)
            assertTrue(sigPlain is HybridSignature)
            Crypto.checkSignature(ssa.getKeyInfo(aliasPlain).publicKey, msg, alg, sigPlain)

            val aliasPass = "hybrid_pass_${alg.name}"
            ssa.createKey(
                aliasPass,
                SoftwareCreateKeySettings.Builder()
                    .setAlgorithm(alg)
                    .setPassphraseRequired(true, "1234", PassphraseConstraints.PIN_FOUR_DIGITS)
                    .build()
            )
            val unlockProvider = object : KeyUnlockDataProvider {
                override suspend fun getKeyUnlockData(
                    secureArea: SecureArea,
                    alias: String,
                    algorithm: Algorithm,
                    unlockReason: Reason
                ): KeyUnlockData = SoftwareKeyUnlockData(secureArea as SoftwareSecureArea, alias, "1234")
            }
            val sigPass = withContext(unlockProvider) {
                ssa.sign(aliasPass, msg)
            }
            assertTrue(sigPass is HybridSignature)
            Crypto.checkSignature(ssa.getKeyInfo(aliasPass).publicKey, msg, alg, sigPass)
        }
    }

    @Test
    fun testCombineSignAndCreatePublicKey() = runTest {
        if (Algorithm.ML_DSA_65_ES256 !in Crypto.supportedHybridSigningAlgorithms) {
            return@runTest
        }
        val pqPriv = Crypto.createMlDsaPrivateKey(Algorithm.ML_DSA_65)
        val ecPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        try {
            val pubKey = HybridSigning.createPublicKey(
                algorithm = Algorithm.ML_DSA_65_ES256,
                pqPublicKey = pqPriv.publicKey,
                traditionalPublicKey = ecPriv.publicKey
            )
            assertEquals(Algorithm.ML_DSA_65_ES256, pubKey.algorithm)
            assertEquals(pqPriv.publicKey, pubKey.pqPublicKey)
            assertEquals(ecPriv.publicKey, pubKey.traditionalPublicKey)

            // Mismatched traditional curve or PQ algorithm must fail
            Crypto.createEcPrivateKey(EcCurve.P384).use { p384Priv ->
                assertFailsWith<IllegalArgumentException> {
                    HybridSigning.createPublicKey(
                        algorithm = Algorithm.ML_DSA_65_ES256,
                        pqPublicKey = pqPriv.publicKey,
                        traditionalPublicKey = p384Priv.publicKey
                    )
                }
            }
            if (Algorithm.ML_DSA_44 in Crypto.supportedMlDsaAlgorithms) {
                Crypto.createMlDsaPrivateKey(Algorithm.ML_DSA_44).use { mlDsa44Priv ->
                    assertFailsWith<IllegalArgumentException> {
                        HybridSigning.createPublicKey(
                            algorithm = Algorithm.ML_DSA_65_ES256,
                            pqPublicKey = mlDsa44Priv.publicKey,
                            traditionalPublicKey = ecPriv.publicKey
                        )
                    }
                }
            }

            val spec = HybridSigning.specFor(Algorithm.ML_DSA_65_ES256)
            val message = "combineSign hardware split test".encodeToByteArray()
            var capturedPqAlg: Algorithm? = null
            var capturedPqMPrime: ByteArray? = null
            var capturedContext: ByteArray? = null
            var capturedTradAlg: Algorithm? = null
            var capturedTradMPrime: ByteArray? = null

            val hybridSig = HybridSigning.combineSign(
                algorithm = Algorithm.ML_DSA_65_ES256,
                message = message,
                signPq = { pqAlgorithm, mPrime, context ->
                    capturedPqAlg = pqAlgorithm
                    capturedPqMPrime = mPrime
                    capturedContext = context
                    Crypto.sign(pqPriv, pqAlgorithm, mPrime, context)
                },
                signTraditional = { traditionalAlgorithm, mPrime ->
                    capturedTradAlg = traditionalAlgorithm
                    capturedTradMPrime = mPrime
                    Crypto.sign(ecPriv, traditionalAlgorithm, mPrime)
                }
            )

            assertEquals(Algorithm.ML_DSA_65, capturedPqAlg)
            assertEquals(Algorithm.ESP256, capturedTradAlg)
            assertContentEquals("COMPSIG-MLDSA65-ECDSA-P256-SHA512".encodeToByteArray(), capturedContext)
            val expectedMPrime = HybridSigning.buildMPrime(spec, message)
            assertContentEquals(expectedMPrime, capturedPqMPrime)
            assertContentEquals(expectedMPrime, capturedTradMPrime)
            Crypto.checkSignature(pubKey, message, Algorithm.ML_DSA_65_ES256, hybridSig)

            // Also verify wrong-sized PQ signature is rejected
            assertFailsWith<IllegalArgumentException> {
                HybridSigning.combineSign(
                    algorithm = Algorithm.ML_DSA_65_ES256,
                    message = message,
                    signPq = { _, _, _ -> MlDsaSignature(byteArrayOf(1, 2, 3)) },
                    signTraditional = { tradAlg, mPrime -> Crypto.sign(ecPriv, tradAlg, mPrime) }
                )
            }
        } finally {
            pqPriv.close()
            ecPriv.close()
        }
    }

    companion object {
        private val joseVectors = listOf(
        JoseVector(
            name = "Figure 1: ML-DSA-44-ES256",
            algorithm = Algorithm.ML_DSA_44_ES256,
            kid = "bJ8aA7ZIsHYNGb4GVaIxupyHLmFNuU1jThxqAXK0Sio",
            joseAlg = "ML-DSA-44-ES256",
            pub =
                "unH59k4RuutY-pxvu24U5h8YZD2rSVtHU5qRZsoBmBMcRPgmu9VuNOVdteXi1zNIXjnqJg_GAAxepLqA00Vc3lO0" +
                "bzRIKu39VFD8Lhuk8l0V-cFEJC-zm7UihxiQMMUEmOFxe3x1ixkKZ0jqmqP3rKryx8tSbtcXyfea64QhT6XNje2S" +
                "oMP6FViBDxLHBQo2dwjRls0k5a-XSQSu2OTOiHLoaWsLe8pQ5FLNfTDqmkrawDEdZyxr3oSWJAsHQxRjcIiVzZuv" +
                "wxYy1zl2STiP2vy_fTBaPemkleynQzqPg7oPCyXEE8bjnJbrfWkbNNN8438e6tHPIX4l7zTuzz98YPhLjt_d6EBd" +
                "T4MldsYe-Y4KLyjaGHcAlTkk9oa5RhRwW89T0z_t1DSO3dvfKLUGXh8gd1BD6Fz5MfgpF5NjoafnQEqDjsAAhrCX" +
                "Y4b-Y3yYJEdX4_dp3dRGdHG_rWcPmgX4JG7lCnser4f8QGnDriqiAzJYEXeS8LzUngg_0bx0lqv_KcyU5IaLISFO" +
                "0xZSU5mmEPvdSoDnyAcV8pV44qhLtAvd29n0ehG259oRihtljTWeiu9V60a1N2tbZVl5mEqSK-6_xZvNYA1TCdzN" +
                "ctvweH24unV7U3wer9XA9Q6kvJWDVJ4oKaQsKMrCSMlteBJMRxWbGK7ddUq6F7GdQw-3j2M-qdJvVKm9UPjY9rc1" +
                "lPgol25-oJxTu7nxGlbJUH-4m5pevAN6NyZ6lfhbjWTKlxkrEKZvQXs_Yf6cpXEwpI_ZJeriq1UC1XHIpRkDwdOY" +
                "9MH3an4RdDl2r9vGl_IwlKPNdh_5aF3jLgn7PCit1FNJAwC8fIncAXgAlgcXIpRXdfJk4bBiO89GGccSyDh2EgXY" +
                "dpG3XvNgGWy7npuSoNTE7WIyblAk13UQuO4sdCbMIuriCdyfE73mvwj15xgb07RZRQtFGlFTmnFcIdZ90zDrWXDb" +
                "ANntv7KCKwNvoTuv64bY3HiGbj-NQ-U9eMylWVpvr4hrXcES8c9K3PqHWADZC0iIOvlzFv4VBoc_wVflcOrL_SIo" +
                "aNFCNBAZZq-2v5lAgpJTqVOtqJ_HVraoSfcKy5g45p-qULunXj6Jwq21fobQiKubBKKOZwcJFyJD7F4ACKXOrz-H" +
                "IvSHMCWW_9dVrRuCpJw0s0aVFbRqopDNhu446nqb4_EDYQM1tTHMozPd_jKxRRD0sH75X8ZoToxFSpLBDbtdWcen" +
                "xj-zBf6IGWfZnmaetjKEBYJWC7QDQx1A91pJVJCEgieCkoIfTqkeQuePpIyu48g2FG3P1zjRF-kumhUTfSjo5qS0" +
                "YiZQy0E1BMs6M11EvuxXRsHClLHoy5nLYI2Sj4zjVjYyxSHyPRPGGo9hwB34yWxzYNtPPGiqXS_dNCpi_zRZwRY4" +
                "lCGrQ-hYTEWIK1Dm5OlttvC4_eiQ1dv63NiGkLRJ5kJA3bICN0fzCDY-MBqnd1cWn8YVBijVkgtaoascjL9EywDg" +
                "JdeHnXK0eeOvUxHHhXJVkNqcibn8O4RQdpVU60TSA-uiu675ytIjcBHC6kTv8A8pmkj_4oypPd-F92YIJC741swk" +
                "YQoeIHj8rE-ThcMUkF7KqC5VORbZTRp8HsZSqgiJcIPaouuxd1-8Rxrid3fXkE6p8bkrysPYoxWEJgh7ZFsRCPDW" +
                "X-yTeJwFN0PKFP1j0F6YtlLfK5wv-c4F8ZQHA_-yc_gODicy7KmWDZgbTP07e7gEWzw4MFRrndjbDQRrF9Hy4SxC" +
                "R_i85uVjpEDydwN9gS3rM6D0oTlF2JjClk_jQuL-Gn-bjufrSnwPnhYrzjNXazFezsu2QGg3v1H1",
            priv =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwMQIBAQQgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAGgCgYIKoZIzj0DAQc",
            jws =
                "eyJhbGciOiJNTC1EU0EtNDQtRVMyNTYiLCJraWQiOiJiSjhhQTdaSXNIWU5HYjRHVmFJeHVweUhMbUZOdVUxalRo" +
                "eHFBWEswU2lvIn0.SXQncyBhIGRhbmdlcm91cyBidXNpbmVzcywgRnJvZG8sIGdvaW5nIG91dCB5b3VyIGRvb3Iu" +
                ".n4lG9qkiRMktRpBwUO2U_G5mP7tk1Vf7nNX_0OX398PDkz0wmqHgqLodXrVEmErocDkAtSusGOEK-MRIdP7mV_6" +
                "vy4sjXf_U3cbyDiTu8f9j12jPxjFD7tsx--Ld5jLiaCcE17uiacS_w0jQaOhA68nz9Et5U16QfDNLlP9k1pKLuwM" +
                "FEMoJ8yeNOIzvmu3A3aZtD63-8gVX3jNGngxoDcSxkrDdeIM1r6Nmvvbe_ZJaIhcYPAmyObtmF9UbSm1SqL7lYBh" +
                "7kOnK268DOj4cG950nKjbS84fRfymZf-yb1Slc24DOJj54PGe6KH5wdckUi-o2M3WjmA9VywIkMOpKIziqPC0hHe" +
                "6Rc2V6uQ9c5XgdBptTxDqk8R_u5lk51hdIHk2C5pd_VnZEIE9kWym4MevcLcQkD6KLA-KkDmqoIOiLQA9wzy-C2E" +
                "VTwXFKkWC7IBNKGBXHXNVA9XmNdcTNroM7RRVve2MjM9M5uECQl8l6G5Rc9zsck9PuicRRc097_2L6fPQ_CoHZ-M" +
                "K9iu0f0-5spAI60ZikKqp68pVixDFhQ8k59Mby7MTNfkEGLWzAE0XQJrpzJ8_f0IRtWCTyktvqwoPtf81OrKz-MQ" +
                "vV2ii-xviKmGqOBnqcAglK_l1OQGbwBa2e1ZlCqJpo2wwtaWzO80KOnKjssuHhidEyjwJArr5a8QppITZvKyVrbI" +
                "WydFhznpD-KeqPKQUM8NhlkqI02PE9TCTmBGa_lYkLbLc5GPUJ1WooDYzG21_Okbg9kJDAnK6BqFeGIGEmsqVDBP" +
                "O4zc9tNVm61HCycG9qTgjqN7z-8rzB6pqCJT0hygSfCPi6LmHswsiOt9gWPxTgtTQGvQWir-htv7LRMC7aM0QDhy" +
                "JVfM4z3mpx1aJn7cfPgpV1Zx3SEycfHaXYHrIsQjgKA6nFTgtBD-iA26967TUHKCL6RyJ_nV6Uw60eMRK1GX8YTt" +
                "qst6WjGN6m9imXQL3fHwER7hg-9m6yyWGojunqmlYe758kC4WrkfGjigR486-GFGwIeZ-NaZ39cqAn0tRHySn_P6" +
                "vtTLRTDwz6y_qARuEERKOIAQx340FDzRXlAqezRjW_7AUOlexOpJCzMDiimM3ChDKKUvf3WHejoDCNKdkxwyawOl" +
                "g__4Lyy_pS4e_mIhMmHL2ALslYYAcIWDk2cb_XLy8KsX9nLU3Wa2swYwUJRsUlHaTBFtOL95xBVUJ-jMnO0oBKGT" +
                "lCX1EZ6Hr90cGOJtGj0ngAbuZWJ89vCfyu14o7XbyJMJnbwsRDyJcVTVRkHamSdARqd5O-S6dNzEICSYxmBCkypB" +
                "xpM4Mrv_sE4ZWwhNb8a8SOL5vr4MssTRJF_g8fwOj2rK_gLjUsh1Ui9qd9si5UIMaXkBvmijGMbu07cVPjsCfyV5" +
                "2uc5wiOCWPvKGiSnf9_jj0d7fY4m2P9S1C5sPc4PC2UueRHgv-H00ZJay3Pt7az1MY6B3uMMFiUc78-k2pl4y1lV" +
                "JNciEhwhQdm_dN2uU-WwNmfR_XhxiAk4zib_PIA6JhrzUb5zoSapcVUymMP4a8QUp3vb455rHvWV_V0wMY2-wni1" +
                "rgscMTQsxrEEI3p_pAx1nojzuwc8ixkf7xBcGo3Rqrwxf6NwLa8ZTEl1KA2NMLUMUIN_yRAqBZ5OUKqu139qHTRO" +
                "Ow48Eu6cBouWLAkkaVVECS7VvnyS259OYMxc4Ut0HHRatE16BTIg6dFbKEiZE4kHtvm-bWNut6M7jRAF9NP-i50J" +
                "BT5e9Rx0wxU5OFfS9lEqMvwdzdrrUdyC9M3ZLgDxK46gFbauphmSnW5pUUzN3gSDIJntpwO52G6QB06JJZQHwzwu" +
                "2AFyLXlc4c3Be10eLVuV_oe02r5I9Zwc3SswMwPce3XTOzBq9P6TQYzSYc8LUQEyNPaeNrvMshZYTrYuABUOWXuW" +
                "GdXQ3AA-hu8VeeRmBg07qA7_PZJ1l3IFS3V1CMfAPtRIdn0YG-JmbtKATdKb_766CdX7ccs8uOyTQSZQGDDWJGMe" +
                "iRpUOOLP2bntTww3b_3xhbn77cj957-Dh95Uv_Y74MOslxI4rrEnt1R4RHOU79GTPkeV7UVRx0_5_uDad8BkFsel" +
                "vogGuPHLa2GOBRhv90OeXM6ePJ9wFBZQiLu9KEhvpR9ojGelrbhTCIAxalJ-eRmIvH6M5th5mSRvG8WkS5nGk0rl" +
                "wYwM6ery-ULL3im_iBYcu9gHf1V4DcoPZ97oDcyWpxd9n9daaL0uYsCZt29AZu1rmATwUKMtQlr2JBETA-XKA4zH" +
                "g03m7LP4bqciXiv4rQW-vvbtVUVE4o9-6qQEHn-tyUhxeruOzsFnRhRu0KzX77d1ywqnZ8FEwUkcr2mb6USXnYKw" +
                "ttkkGTpmJ-07etU35rw30hlUK8pAwh-3irDBvv7gBxt_P8Xw63NJimclQdhHMkaHOOU4ZtSsJ4A3x1cgLjR-0Rhc" +
                "dTpow8-pHSl5n81IgEwZc8kDcVtKKmjONh5Sjc05MErnZkSHUlVc0KewUo53ZbVmX2YW0CfQwRca9dyz0zEuGa4W" +
                "XIPma_QXlpQrumFJsfpYBsyUsXzOs4ZThy-yv1UZQyHNPB_Jtk-2vKMyoyzxEczjXMBcb2mPGXX2UxNEsTuTyO5E" +
                "B9EMaZoReodXPQ33BXXZRwFlCsEmTt7BXglUgnGgx4IdTE-ZMnohGnh7IRrwdj1vsU8z8Gf7pcucxuLgb6gCtNa9" +
                "Ou93VipHkMtP2cbQaUlvDuekzTqa4Ha2ddDz4Ce-QlJw2sKZolnDKWeHnCOiLrXhKX4VXfaJyx5Lnz5_KKpZf4Au" +
                "mf4cC_Dz2FW9OfuWLne62vllyet4obwlwZzQrCIPzoMMxBBkRbk7Q8H2omROIdNGSG8HPLoVaP1mCwQtOrv8yjE4" +
                "RpoYZp0rt3zjahuDDsDbZioak8iv0kX8Z5PlObuNW3eaoTR6BsGdoV5IYW7EbNIlqteS3jNqykFa4qoEXUuIH_Pq" +
                "FvugULnlmZfe9bkP_9wKC1T_8Cxt_2iHG-CSKgKM1pmnX1Ty-fgcvuu1-bHYv34WoBwftT5lPqZoxTY5hdfmuZLv" +
                "1kbjIu1qtvDHhfvwIce3nmmb5i6ShvFWWGEoEFyUmNTc8P1BVdH-Wq7PO8PT3Fxs6Wn6Akaexsr7J3PAPPFZjeJS" +
                "hprLX5foCDyY6O0ZWWGhpb3SMk7LS2d3r7_EAAAAAAAAAAAAAAAAAABMhLUIwRQIgS2ipUvNeHif0Ki4PkDtx0aP" +
                "I7GpZiacijaQ3gukED8oCIQD8PsI480A2A6xnjmINPSjGjbYsQD22lNI9I6Xppr2Rhw",
            rawCompositePubHex =
                "ba71f9f64e11baeb58fa9c6fbb6e14e61f18643dab495b47539a9166ca0198131c44f826bbd56e34e55db5e5" +
                "e2d733485e39ea260fc6000c5ea4ba80d3455cde53b46f34482aedfd5450fc2e1ba4f25d15f9c144242fb39b" +
                "b52287189030c50498e1717b7c758b190a6748ea9aa3f7acaaf2c7cb526ed717c9f79aeb84214fa5cd8ded92" +
                "a0c3fa1558810f12c7050a367708d196cd24e5af974904aed8e4ce8872e8696b0b7bca50e452cd7d30ea9a4a" +
                "dac0311d672c6bde8496240b07431463708895cd9bafc31632d7397649388fdafcbf7d305a3de9a495eca743" +
                "3a8f83ba0f0b25c413c6e39c96eb7d691b34d37ce37f1eead1cf217e25ef34eecf3f7c60f84b8edfdde8405d" +
                "4f832576c61ef98e0a2f28da187700953924f686b94614705bcf53d33fedd4348edddbdf28b5065e1f207750" +
                "43e85cf931f829179363a1a7e7404a838ec00086b0976386fe637c98244757e3f769ddd4467471bfad670f9a" +
                "05f8246ee50a7b1eaf87fc4069c3ae2aa2033258117792f0bcd49e083fd1bc7496abff29cc94e4868b21214e" +
                "d316525399a610fbdd4a80e7c80715f29578e2a84bb40bdddbd9f47a11b6e7da118a1b658d359e8aef55eb46" +
                "b5376b5b655979984a922beebfc59bcd600d5309dccd72dbf0787db8ba757b537c1eafd5c0f50ea4bc958354" +
                "9e2829a42c28cac248c96d78124c47159b18aedd754aba17b19d430fb78f633ea9d26f54a9bd50f8d8f6b735" +
                "94f828976e7ea09c53bbb9f11a56c9507fb89b9a5ebc037a37267a95f85b8d64ca97192b10a66f417b3f61fe" +
                "9ca57130a48fd925eae2ab5502d571c8a51903c1d398f4c1f76a7e11743976afdbc697f23094a3cd761ff968" +
                "5de32e09fb3c28add453490300bc7c89dc01780096071722945775f264e1b0623bcf4619c712c838761205d8" +
                "7691b75ef360196cbb9e9b92a0d4c4ed62326e5024d77510b8ee2c7426cc22eae209dc9f13bde6bf08f5e718" +
                "1bd3b459450b451a51539a715c21d67dd330eb5970db00d9edbfb2822b036fa13bafeb86d8dc78866e3f8d43" +
                "e53d78cca5595a6faf886b5dc112f1cf4adcfa875800d90b48883af97316fe1506873fc157e570eacbfd2228" +
                "68d14234101966afb6bf9940829253a953ada89fc756b6a849f70acb9838e69faa50bba75e3e89c2adb57e86" +
                "d088ab9b04a28e670709172243ec5e0008a5ceaf3f8722f487302596ffd755ad1b82a49c34b3469515b46aa2" +
                "90cd86ee38ea7a9be3f103610335b531cca333ddfe32b14510f4b07ef95fc6684e8c454a92c10dbb5d59c7a7" +
                "c63fb305fe881967d99e669eb632840582560bb403431d40f75a4954908482278292821f4ea91e42e78fa48c" +
                "aee3c836146dcfd738d117e92e9a15137d28e8e6a4b4622650cb413504cb3a335d44beec5746c1c294b1e8cb" +
                "99cb608d928f8ce3563632c521f23d13c61a8f61c01df8c96c7360db4f3c68aa5d2fdd342a62ff3459c11638" +
                "9421ab43e8584c45882b50e6e4e96db6f0b8fde890d5dbfadcd88690b449e64240ddb2023747f308363e301a" +
                "a77757169fc6150628d5920b5aa1ab1c8cbf44cb00e025d7879d72b479e3af5311c785725590da9c89b9fc3b" +
                "8450769554eb44d203eba2bbaef9cad2237011c2ea44eff00f299a48ffe28ca93ddf85f76608242ef8d6cc24" +
                "610a1e2078fcac4f9385c314905ecaa82e553916d94d1a7c1ec652aa08897083daa2ebb1775fbc471ae27777" +
                "d7904ea9f1b92bcac3d8a3158426087b645b1108f0d65fec93789c053743ca14fd63d05e98b652df2b9c2ff9" +
                "ce05f1940703ffb273f80e0e2732eca9960d981b4cfd3b7bb8045b3c3830546b9dd8db0d046b17d1f2e12c42" +
                "47f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33" +
                "576b315ececbb6406837bf51f5",
            rawCompositeSigHex =
                "9f8946f6a92244c92d46907050ed94fc6e663fbb64d557fb9cd5ffd0e5f7f7c3c3933d309aa1e0a8ba1d5eb5" +
                "44984ae8703900b52bac18e10af8c44874fee657feafcb8b235dffd4ddc6f20e24eef1ff63d768cfc63143ee" +
                "db31fbe2dde632e2682704d7bba269c4bfc348d068e840ebc9f3f44b79535e907c334b94ff64d6928bbb0305" +
                "10ca09f3278d388cef9aedc0dda66d0fadfef20557de33469e0c680dc4b192b0dd788335afa366bef6defd92" +
                "5a2217183c09b239bb6617d51b4a6d52a8bee560187b90e9cadbaf033a3e1c1bde749ca8db4bce1f45fca665" +
                "ffb26f54a5736e033898f9e0f19ee8a1f9c1d724522fa8d8cdd68e603d572c0890c3a9288ce2a8f0b48477ba" +
                "45cd95eae43d7395e0741a6d4f10ea93c47fbb9964e7585d2079360b9a5dfd59d910813d916ca6e0c7af70b7" +
                "10903e8a2c0f8a9039aaa083a22d003dc33cbe0b61154f05c52a4582ec804d2860571d735503d5e635d71336" +
                "ba0ced1455bded8c8ccf4ce6e102425f25e86e5173dcec724f4fba271145cd3deffd8be9f3d0fc2a0767e30a" +
                "f62bb47f4fb9b29008eb466290aaa9ebca558b10c5850f24e7d31bcbb31335f90418b5b3004d17409ae9cc9f" +
                "3f7f4211b56093ca4b6fab0a0fb5ff353ab2b3f8c42f5768a2fb1be22a61aa3819ea7008252bf97539019bc0" +
                "16b67b56650aa269a36c30b5a5b33bcd0a3a72a3b2cb87862744ca3c0902baf96bc429a484d9bcac95adb216" +
                "c9d161ce7a43f8a7aa3ca41433c361964a88d363c4f5309398119afe56242db2dce463d42755a8a036331b6d" +
                "7f3a46e0f642430272ba06a15e1881849aca950c13cee3373db4d566eb51c2c9c1bda93823a8def3fbcaf307" +
                "aa6a0894f48728127c23e2e8b987b30b223adf6058fc5382d4d01af4168abfa1b6fecb44c0bb68cd100e1c89" +
                "55f338cf79a9c756899fb71f3e0a55d59c77484c9c7c7697607ac8b108e0280ea715382d043fa2036ebdebb4" +
                "d41ca08be91c89fe757a530eb478c44ad465fc613b6ab2de968c637a9bd8a65d02f77c7c0447b860fbd9bacb" +
                "2586a23ba7aa69587bbe7c902e16ae47c68e2811e3cebe1851b021e67e35a677f5ca809f4b511f24a7fcfeaf" +
                "b532d14c3c33eb2fea011b8411128e200431df8d050f3457940a9ecd18d6ffb0143a57b13a9242ccc0e28a63" +
                "370a10ca294bdfdd61de8e80c234a764c70c9ac0e960fffe0bcb2fe94b87bf98884c9872f600bb2561801c21" +
                "60e4d9c6ff5cbcbc2ac5fd9cb53759adacc18c14251b14947693045b4e2fde71055509fa33273b4a012864e5" +
                "097d4467a1ebf74706389b468f49e001bb99589f3dbc27f2bb5e28ed76f224c2676f0b110f225c5535519076" +
                "a649d011a9de4ef92e9d3731080926319810a4ca9071a4ce0caeffec138656c2135bf1af1238be6faf832cb1" +
                "344917f83c7f03a3dab2bf80b8d4b21d548bda9df6c8b950831a5e406f9a28c631bbb4edc54f8ec09fc95e76" +
                "b9ce7088e0963ef2868929dff7f8e3d1dedf6389b63fd4b50b9b0f7383c2d94b9e44782ff87d346496b2dcfb" +
                "7b6b3d4c63a077b8c30589473bf3e936a65e32d6554935c884870850766fdd376b94f96c0d99f47f5e1c6202" +
                "4e3389bfcf200e8986bcd46f9ce849aa5c554ca630fe1af10529def6f8e79ac7bd657f574c0c636fb09e2d6b" +
                "82c70c4d0b31ac4108de9fe9031d67a23ceec1cf22c647fbc41706a3746aaf0c5fe8dc0b6bc653125d4a0363" +
                "4c2d431420dff2440a816793942aabb5dfda874d138ec38f04bba701a2e58b02491a5551024bb56f9f24b6e7" +
                "d39833173852dd071d16ad135e814c883a7456ca122644e241edbe6f9b58dbade8cee344017d34ffa2e74241" +
                "4f97bd471d30c54e4e15f4bd944a8cbf077376bad47720bd33764b803c4ae3a8056daba98664a75b9a545333" +
                "778120c8267b69c0ee761ba401d3a2496501f0cf0bb6005c8b5e573873705ed7478b56e57fa1ed36af923d67" +
                "07374acc0cc0f71edd74cecc1abd3fa4d063349873c2d4404c8d3da78daef32c859613ad8b800543965ee586" +
                "757437000fa1bbc55e791981834eea03bfcf649d65dc8152dd5d4231f00fb5121d9f4606f8999bb4a01374a6" +
                "ffefae82757edc72cf2e3b24d04994060c358918c7a246950e38b3f66e7b53c30ddbff7c616e7efb723f79ef" +
                "e0e1f7952ffd8ef830eb25c48e2bac49edd51e111ce53bf464cf91e57b515471d3fe7fb8369df01905b1e96f" +
                "a201ae3c72dad86381461bfdd0e79733a78f27dc050594222eef4a121be947da2319e96b6e14c2200c5a949f" +
                "9e46622f1fa339b61e66491bc6f16912e671a4d2b97063033a7abcbe50b2f78a6fe205872ef601dfd55e0372" +
                "83d9f7ba037325a9c5df67f5d69a2f4b98b0266ddbd019bb5ae6013c1428cb5096bd890444c0f97280e331e0" +
                "d379bb2cfe1ba9c8978afe2b416fafbdbb55515138a3dfbaa901079feb72521c5eaee3b3b059d1851bb42b35" +
                "fbeddd72c2a9d9f0513052472bda66fa5125e760ac2db649064e9989fb4edeb54df9af0df486550af2903087" +
                "ede2ac306fbfb801c6dfcff17c3adcd26299c9507611cc91a1ce394e19b52b09e00df1d5c80b8d1fb446171d" +
                "4e9a30f3ea474a5e67f3522013065cf240dc56d28a9a338d8794a3734e4c12b9d99121d495573429ec14a39d" +
                "d96d5997d985b409f43045c6bd772cf4cc4b866b859720f99afd05e5a50aee98526c7e9601b3252c5f33ace1" +
                "94e1cbecafd54650c8734f07f26d93edaf28cca8cb3c447338d730171bda63c65d7d94c4d12c4ee4f23b9101" +
                "f4431a66845ea1d5cf437dc15d7651c05942b04993b7b0578255209c6831e0875313e64c9e88469e1ec846bc" +
                "1d8f5bec53ccfc19fee972e731b8b81bea00ad35af4ebbddd58a91e432d3f671b41a525bc3b9e9334ea6b81d" +
                "ad9d743cf809ef90949c36b0a6689670ca59e1e708e88bad784a5f85577da272c792e7cf9fca2a965fe00ba6" +
                "7f8702fc3cf6156f4e7ee58b9deeb6be59727ade286f097067342b0883f3a0c3310419116e4ed0f07da89913" +
                "8874d1921bc1cf2e855a3f5982c10b4eaeff328c4e11a68619a74aeddf38da86e0c3b036d98a86a4f22bf491" +
                "7f19e4f94e6ee356dde6a84d1e81b067685792185bb11b34896ab5e4b78cdab29056b8aa811752e207fcfa85" +
                "bee8142e796665f7bd6e43fff70282d53ffc0b1b7fda21c6f8248a80a335a669d7d53cbe7e072fbaed7e6c76" +
                "2fdf85a80707ed4f994fa99a314d8e6175f9ae64bbf591b8c8bb5aadbc31e17efc0871ede79a66f98ba4a1bc" +
                "5596184a0417252635373c3f5055747f96abb3cef0f4f7171b3a5a7e8091a7b1b2bec9dcf00f3c56637894a1" +
                "a6b2d7e5fa020f263a3b46565868696f748c93b2d2d9ddebeff1000000000000000000000000000013212d42" +
                "304502204b68a952f35e1e27f42a2e0f903b71d1a3c8ec6a5989a7228da43782e9040fca022100fc3ec238f3" +
                "403603ac678e620d3d28c68db62c403db694d23d23a5e9a6bd9187",
            rawToBeSignedHex =
                "65794a68624763694f694a4e54433145553045744e44517452564d794e5459694c434a72615751694f694a69" +
                "536a68685154646153584e4957553548596a5248566d464a654856776555684d62555a4f64565578616c526f" +
                "654846425745737755326c76496e302e5358516e6379426849475268626d646c636d39316379426964584e70" +
                "626d567a63797767526e4a765a473873494764766157356e4947393164434235623356794947527662334975",
            rawMessageRepresentativeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4134342d45434453412d503235362d53484132353600ba9670882b8315b4c583eafaeb079e0488fc557aba23" +
                "9ba5f9f562fea63fee52",
        ),
        JoseVector(
            name = "Figure 2: ML-DSA-44-Ed25519",
            algorithm = Algorithm.ML_DSA_44_ED25519,
            kid = "5ETztyaAQkvflhmjJ6mtdwlWEb3Nr4ymcDpN8eXQ8h4",
            joseAlg = "ML-DSA-44-Ed25519",
            pub =
                "unH59k4RuutY-pxvu24U5h8YZD2rSVtHU5qRZsoBmBMcRPgmu9VuNOVdteXi1zNIXjnqJg_GAAxepLqA00Vc3lO0" +
                "bzRIKu39VFD8Lhuk8l0V-cFEJC-zm7UihxiQMMUEmOFxe3x1ixkKZ0jqmqP3rKryx8tSbtcXyfea64QhT6XNje2S" +
                "oMP6FViBDxLHBQo2dwjRls0k5a-XSQSu2OTOiHLoaWsLe8pQ5FLNfTDqmkrawDEdZyxr3oSWJAsHQxRjcIiVzZuv" +
                "wxYy1zl2STiP2vy_fTBaPemkleynQzqPg7oPCyXEE8bjnJbrfWkbNNN8438e6tHPIX4l7zTuzz98YPhLjt_d6EBd" +
                "T4MldsYe-Y4KLyjaGHcAlTkk9oa5RhRwW89T0z_t1DSO3dvfKLUGXh8gd1BD6Fz5MfgpF5NjoafnQEqDjsAAhrCX" +
                "Y4b-Y3yYJEdX4_dp3dRGdHG_rWcPmgX4JG7lCnser4f8QGnDriqiAzJYEXeS8LzUngg_0bx0lqv_KcyU5IaLISFO" +
                "0xZSU5mmEPvdSoDnyAcV8pV44qhLtAvd29n0ehG259oRihtljTWeiu9V60a1N2tbZVl5mEqSK-6_xZvNYA1TCdzN" +
                "ctvweH24unV7U3wer9XA9Q6kvJWDVJ4oKaQsKMrCSMlteBJMRxWbGK7ddUq6F7GdQw-3j2M-qdJvVKm9UPjY9rc1" +
                "lPgol25-oJxTu7nxGlbJUH-4m5pevAN6NyZ6lfhbjWTKlxkrEKZvQXs_Yf6cpXEwpI_ZJeriq1UC1XHIpRkDwdOY" +
                "9MH3an4RdDl2r9vGl_IwlKPNdh_5aF3jLgn7PCit1FNJAwC8fIncAXgAlgcXIpRXdfJk4bBiO89GGccSyDh2EgXY" +
                "dpG3XvNgGWy7npuSoNTE7WIyblAk13UQuO4sdCbMIuriCdyfE73mvwj15xgb07RZRQtFGlFTmnFcIdZ90zDrWXDb" +
                "ANntv7KCKwNvoTuv64bY3HiGbj-NQ-U9eMylWVpvr4hrXcES8c9K3PqHWADZC0iIOvlzFv4VBoc_wVflcOrL_SIo" +
                "aNFCNBAZZq-2v5lAgpJTqVOtqJ_HVraoSfcKy5g45p-qULunXj6Jwq21fobQiKubBKKOZwcJFyJD7F4ACKXOrz-H" +
                "IvSHMCWW_9dVrRuCpJw0s0aVFbRqopDNhu446nqb4_EDYQM1tTHMozPd_jKxRRD0sH75X8ZoToxFSpLBDbtdWcen" +
                "xj-zBf6IGWfZnmaetjKEBYJWC7QDQx1A91pJVJCEgieCkoIfTqkeQuePpIyu48g2FG3P1zjRF-kumhUTfSjo5qS0" +
                "YiZQy0E1BMs6M11EvuxXRsHClLHoy5nLYI2Sj4zjVjYyxSHyPRPGGo9hwB34yWxzYNtPPGiqXS_dNCpi_zRZwRY4" +
                "lCGrQ-hYTEWIK1Dm5OlttvC4_eiQ1dv63NiGkLRJ5kJA3bICN0fzCDY-MBqnd1cWn8YVBijVkgtaoascjL9EywDg" +
                "JdeHnXK0eeOvUxHHhXJVkNqcibn8O4RQdpVU60TSA-uiu675ytIjcBHC6kTv8A8pmkj_4oypPd-F92YIJC741swk" +
                "YQoeIHj8rE-ThcMUkF7KqC5VORbZTRp8HsZSqgiJcIPaouuxd1-8Rxrid3fXkE6p8bkrysPYoxWEJgh7ZFsRCPDW" +
                "X-yTeJwFN0PKFP1j0F6YtlLfK5wv-c4F8ZQHA_-yc_gODicy7KmWDZgbTP07e7gEWzw4MFRrndjbDTtqJ7zOtqQt" +
                "YqOo0CpvDXNlMhV3HeJDpjrASKGLWdop",
            priv =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            jws =
                "eyJhbGciOiJNTC1EU0EtNDQtRWQyNTUxOSIsImtpZCI6IjVFVHp0eWFBUWt2ZmxobWpKNm10ZHdsV0ViM05yNHlt" +
                "Y0RwTjhlWFE4aDQifQ.SXQncyBhIGRhbmdlcm91cyBidXNpbmVzcywgRnJvZG8sIGdvaW5nIG91dCB5b3VyIGRvb" +
                "3Iu.L3S6eNjOhnuAx2SP0Zp8r6E8X2VNKS0iD_IZ_-nD7xpyZWiEg8-JLmJZuTwzTyporvUbesjsOSuKfDnquFsv" +
                "0sDYoOmUSdUtz-pS3FrwndyWR_DPZRUODOixq0APbdE4oZPNn3Sw75KOR2wxF9MZHpZ1xkeoJvXJxpdgvWAQf06p" +
                "UuqDbLHRH1OYXZ1qzs2X0QoPl0wlLUhDybARURIycC509igSoMaXdDig8SB0HO6bKWgH0Fjz4MKRm5d18JAwi2BH" +
                "h5QuRV1QN2V_sNl-lJGvS49x8b-5DKEpsUa26gUSd72i7QbIBKclIFHyEtgvZjo-4Ik3Rehdb5rYgNxtR6s4nVmg" +
                "m2c_LaQKHPWQvI29NAijhS41djZ0LCslJ6-iMyu-rv0bIUiLyYEH8y_huCyx7U3AHSkp4bqk-6xsaQhZSjeiUaEo" +
                "-Ub22M4x8vCUE-e-h3THiPGKhzRdBbHqKIOR1bdNhcNLbJL42ueUEJhjonVhC4k9Z0a65t782nrSEePAvVBEK25o" +
                "uKOW9vCH_mZTUGEJ8708RvzlxZ_8Zi5bhTrecEB5cnWxdCNTBYQTkPzOoCJpq4xgZ_Z2Qa5rQIBDFArHIFvtijsu" +
                "-yGCEbD6BPV8ThNDrslzDeVyDsYDANdUfJZ6NPPqn_5jUwKhqz1L0EtdPz9FMDPktVnQ_TxGsBLjawGNLzP64-gq" +
                "C8A9CBsCsx9znJ1x_GNRjnRFwAVK4ZBA3U-FvE_SPiqX0MjeFbCpQwAH4GFef3pk6gO74AfRK_h0ZFXmEWGwv6FI" +
                "IpfGL4yJC28ocQW0dGNrC0EAldUGNkU6HeKvxwiG5nXxRwAghr_QO-YfoLHDQNTR1FOzCk6xTnW26muqFJJef0Y3" +
                "nLd08uHcoWkNaaDkGWM0FHXJGJCVvLIy04WbUR1kV_id5rArx6u1M8L9-yFJEo77SDNo8ba4txgfxd-kvRje5a3H" +
                "UhAJInOvvbUfMR-gW-80wuTaZKjYqRY3l2Cq1mAFbTWdRJTwm04gpfRwkKldnrvOxPwf8w3r2ATolVEZ7vZGcw6B" +
                "JAlgC0s3vTDY9gUXZi4O8fLptafKbqE8a4Oa5a3Td-A0n7h5LKOEI44ZqEXI8zEtADENoFVivJvxGnEHoXKulxEa" +
                "gQH5R2Z4b79oG4B0HCMpT3GLEFCzuVlviHOXncKclnftHBJuXbm9Sn8UVgltcdn8-iYaYFuBBUubtC8D0uIGPu48" +
                "_2BTtHZxU8A-ovZHw6itQZpt-X_AM8swuqOHsHr4c4TiMhfDXjA-sVqJLmlJ86KwB1TgooAwjTyMFXD0kKuaIK57" +
                "pz3GEZ8VS5jBY_4GvXEbdGM9JOMBbaP7yg1sIKqb7XsKvww9_IIGWS-i3rTR4SKpuXMHWeSDYrqvyy5Onr0LZ7e2" +
                "ocl-CWyj7hXl0wWMLU8lo4n5P7wi8mYy5MjKM_W0mqiVvh4rVipeIyfRXaBJxf3hJCUPMBDPBUHDvWHl1FiXjbD-" +
                "N-srP7ZXwArDe8elbnayfcGly_y4IkeWIfD_2a5QWerOG7JNfmT5KKw3XjT_2c3Ye6EUOHvoGMijyGivsDyo7tp_" +
                "Z73G4fQRpZmkkJtDrZpbdnF94oZ_ugfMniDPNmXQdXFw3Um3ZZKbRsAbu-q393lbspBIfRWcsYHAc0HCd87EI8uj" +
                "LH-PmMJHq4N-NOCjrVDthqvFg4Kp3v6p9kay0yDbqOfGqCrHR3Q681d3e5iL9JjXf2IkfauUxEyWRKSRia0E0-Kl" +
                "FZxu8_jZlb8mo5UfjmqHVFEpLa8RQwCa6qTPz5XeXSs08SsyuSRbV8Kn7oiJ7Dbs8sswxjWoLVpSXJH3GH_byRe2" +
                "8-nJpKffDEEnP_qH2hr0wZ08sEFg1Xv6FBQXnEfP0Zv4HlnkMYFbPh1vQuJ7Bd_IMCfn1HDdUNj8CLpBA10R4fna" +
                "QvNB4y0ZAYuIhddbXGP2aEd_38LrsQEI-MfQ7CYa6wZHGW0pBcWYdT7CWRTe-nYl5iIaWUY1oZdNa5W-xvdTlFjo" +
                "nUKLpvY0TzgViSP3asHH034y4jrg0CCxRJ-OMleumOrYpHCV_i0gdvQtjX8PWr2D9XChVmS6_tBjwU5Zfoivw-pz" +
                "htWoWa8VtIrJxd3gTNjPzdbMj9Pp2mJ11xz0HBNTOnooHePSDzccLU3VhV9mvQv-NnM5tuKQRK4DYat3p9YalUvc" +
                "DbkQ_XQygC9pUUPq9Qq24ULiwNgT4U_WewYOUYT-oQzJJ5RRV6DFK8cjIxHZ0yXEKgPHxg14RGdM9miAtRuGRSdg" +
                "ClMTWdPWI2VIJ5U4I0aeNxefyIn2D2nI1__0sZs3UZCc2SIR-JrrslZGTIGrRfuskvTsfSRU-proiOHEOUAf0vne" +
                "ok67e3zXTdfimw7vBeeHPQ4aKmwB8s57_Z6yVnsP1mbEzBvixIEIBT2UZiKr-nLiWYR1zeMqXm8QSuT_PX7y5sce" +
                "7QQD1m9cBCZbDh2GEeje3NiZ2-Zx_gbn_zAnPrYF-GDGhxyHd3nlDk63CPo5hsKC-gBC2t_fQTHkEmHu2EqtUlF_" +
                "d3-N_eqoIhlf03pKTElx0rM8t6vqLoQQIbKu0Xr1ud2CqhCqZHmkddccuxlpVLh0XksjNfjT1shuan5Nm9zEgMga" +
                "EgOGEZTvsiRts8NLfG0k07GO3yVLZgJkVd-LhCkw0b9Q13nAcUvh_nAYaO-1-eb2u-Z3btPzEuMBKmcxgnnXgPY5" +
                "o4YRo-DvpyD1vCBOwuq7aM9pCMrccav-4dXPLbq-nllTg1Seb7J-Tu-UEtXYVjAg8wkCHlO69amvH6EU_vYgUguM" +
                "wgtvSKqLrclROnyX1H9yUXStrcJbgVHOSGXNIqd813Pr6topyt70Q5NQidZeNtlMxmGBOKk-zzxcI8rRy9r97kZf" +
                "ARqvRwa5tWcj-QETqlMs9v7qunVT52beMbhwUc9e_2DYsi_pecqA7edI5px-1oEdCG4acAr8zNrmF1sl5Wlf-3qq" +
                "yrJtZJay3OhB18JfPm8HFduLiMIbeaQuZkekC6jP3fKtAzGvoUrulFiJH3Rso2KadPAa4_Oyq9dgpT68xpdGxt9D" +
                "IOh9NVVecTCeKVgdUoTD4IlhgBi9X3o-jwVm3G0eJV58lpmxyt_sAQojNzo8PVNVV2aKjLjA0dvnGBseISc2Um90" +
                "jJGSpKbG09_m5_8DDxAfMDRMXXSEio-t0dLn6u37AAAAAAAAAAAAAAAAAAocMEPWJ-y89hTR1K6ThF8y_33BiCQV" +
                "CbFU3yyxLkHDq10EPu7QkZOBEDgWuExsrjwdsxRkrUhMymG3qs6uCiUjItUP",
            rawCompositePubHex =
                "ba71f9f64e11baeb58fa9c6fbb6e14e61f18643dab495b47539a9166ca0198131c44f826bbd56e34e55db5e5" +
                "e2d733485e39ea260fc6000c5ea4ba80d3455cde53b46f34482aedfd5450fc2e1ba4f25d15f9c144242fb39b" +
                "b52287189030c50498e1717b7c758b190a6748ea9aa3f7acaaf2c7cb526ed717c9f79aeb84214fa5cd8ded92" +
                "a0c3fa1558810f12c7050a367708d196cd24e5af974904aed8e4ce8872e8696b0b7bca50e452cd7d30ea9a4a" +
                "dac0311d672c6bde8496240b07431463708895cd9bafc31632d7397649388fdafcbf7d305a3de9a495eca743" +
                "3a8f83ba0f0b25c413c6e39c96eb7d691b34d37ce37f1eead1cf217e25ef34eecf3f7c60f84b8edfdde8405d" +
                "4f832576c61ef98e0a2f28da187700953924f686b94614705bcf53d33fedd4348edddbdf28b5065e1f207750" +
                "43e85cf931f829179363a1a7e7404a838ec00086b0976386fe637c98244757e3f769ddd4467471bfad670f9a" +
                "05f8246ee50a7b1eaf87fc4069c3ae2aa2033258117792f0bcd49e083fd1bc7496abff29cc94e4868b21214e" +
                "d316525399a610fbdd4a80e7c80715f29578e2a84bb40bdddbd9f47a11b6e7da118a1b658d359e8aef55eb46" +
                "b5376b5b655979984a922beebfc59bcd600d5309dccd72dbf0787db8ba757b537c1eafd5c0f50ea4bc958354" +
                "9e2829a42c28cac248c96d78124c47159b18aedd754aba17b19d430fb78f633ea9d26f54a9bd50f8d8f6b735" +
                "94f828976e7ea09c53bbb9f11a56c9507fb89b9a5ebc037a37267a95f85b8d64ca97192b10a66f417b3f61fe" +
                "9ca57130a48fd925eae2ab5502d571c8a51903c1d398f4c1f76a7e11743976afdbc697f23094a3cd761ff968" +
                "5de32e09fb3c28add453490300bc7c89dc01780096071722945775f264e1b0623bcf4619c712c838761205d8" +
                "7691b75ef360196cbb9e9b92a0d4c4ed62326e5024d77510b8ee2c7426cc22eae209dc9f13bde6bf08f5e718" +
                "1bd3b459450b451a51539a715c21d67dd330eb5970db00d9edbfb2822b036fa13bafeb86d8dc78866e3f8d43" +
                "e53d78cca5595a6faf886b5dc112f1cf4adcfa875800d90b48883af97316fe1506873fc157e570eacbfd2228" +
                "68d14234101966afb6bf9940829253a953ada89fc756b6a849f70acb9838e69faa50bba75e3e89c2adb57e86" +
                "d088ab9b04a28e670709172243ec5e0008a5ceaf3f8722f487302596ffd755ad1b82a49c34b3469515b46aa2" +
                "90cd86ee38ea7a9be3f103610335b531cca333ddfe32b14510f4b07ef95fc6684e8c454a92c10dbb5d59c7a7" +
                "c63fb305fe881967d99e669eb632840582560bb403431d40f75a4954908482278292821f4ea91e42e78fa48c" +
                "aee3c836146dcfd738d117e92e9a15137d28e8e6a4b4622650cb413504cb3a335d44beec5746c1c294b1e8cb" +
                "99cb608d928f8ce3563632c521f23d13c61a8f61c01df8c96c7360db4f3c68aa5d2fdd342a62ff3459c11638" +
                "9421ab43e8584c45882b50e6e4e96db6f0b8fde890d5dbfadcd88690b449e64240ddb2023747f308363e301a" +
                "a77757169fc6150628d5920b5aa1ab1c8cbf44cb00e025d7879d72b479e3af5311c785725590da9c89b9fc3b" +
                "8450769554eb44d203eba2bbaef9cad2237011c2ea44eff00f299a48ffe28ca93ddf85f76608242ef8d6cc24" +
                "610a1e2078fcac4f9385c314905ecaa82e553916d94d1a7c1ec652aa08897083daa2ebb1775fbc471ae27777" +
                "d7904ea9f1b92bcac3d8a3158426087b645b1108f0d65fec93789c053743ca14fd63d05e98b652df2b9c2ff9" +
                "ce05f1940703ffb273f80e0e2732eca9960d981b4cfd3b7bb8045b3c3830546b9dd8db0d3b6a27bcceb6a42d" +
                "62a3a8d02a6f0d73653215771de243a63ac048a18b59da29",
            rawCompositeSigHex =
                "2f74ba78d8ce867b80c7648fd19a7cafa13c5f654d292d220ff219ffe9c3ef1a7265688483cf892e6259b93c" +
                "334f2a68aef51b7ac8ec392b8a7c39eab85b2fd2c0d8a0e99449d52dcfea52dc5af09ddc9647f0cf65150e0c" +
                "e8b1ab400f6dd138a193cd9f74b0ef928e476c3117d3191e9675c647a826f5c9c69760bd60107f4ea952ea83" +
                "6cb1d11f53985d9d6acecd97d10a0f974c252d4843c9b011511232702e74f62812a0c6977438a0f120741cee" +
                "9b296807d058f3e0c2919b9775f090308b604787942e455d5037657fb0d97e9491af4b8f71f1bfb90ca129b1" +
                "46b6ea051277bda2ed06c804a7252051f212d82f663a3ee0893745e85d6f9ad880dc6d47ab389d59a09b673f" +
                "2da40a1cf590bc8dbd3408a3852e357636742c2b2527afa2332bbeaefd1b21488bc98107f32fe1b82cb1ed4d" +
                "c01d2929e1baa4fbac6c6908594a37a251a128f946f6d8ce31f2f09413e7be8774c788f18a87345d05b1ea28" +
                "8391d5b74d85c34b6c92f8dae794109863a275610b893d6746bae6defcda7ad211e3c0bd50442b6e68b8a396" +
                "f6f087fe6653506109f3bd3c46fce5c59ffc662e5b853ade7040797275b174235305841390fccea02269ab8c" +
                "6067f67641ae6b408043140ac7205bed8a3b2efb218211b0fa04f57c4e1343aec9730de5720ec60300d7547c" +
                "967a34f3ea9ffe635302a1ab3d4bd04b5d3f3f453033e4b559d0fd3c46b012e36b018d2f33fae3e82a0bc03d" +
                "081b02b31f739c9d71fc63518e7445c0054ae19040dd4f85bc4fd23e2a97d0c8de15b0a9430007e0615e7f7a" +
                "64ea03bbe007d12bf8746455e61161b0bfa1482297c62f8c890b6f287105b474636b0b410095d50636453a1d" +
                "e2afc70886e675f147002086bfd03be61fa0b1c340d4d1d453b30a4eb14e75b6ea6baa14925e7f46379cb774" +
                "f2e1dca1690d69a0e41963341475c9189095bcb232d3859b511d6457f89de6b02bc7abb533c2fdfb2149128e" +
                "fb483368f1b6b8b7181fc5dfa4bd18dee5adc75210092273afbdb51f311fa05bef34c2e4da64a8d8a9163797" +
                "60aad660056d359d4494f09b4e20a5f47090a95d9ebbcec4fc1ff30debd804e8955119eef646730e81240960" +
                "0b4b37bd30d8f60517662e0ef1f2e9b5a7ca6ea13c6b839ae5add377e0349fb8792ca384238e19a845c8f331" +
                "2d00310da05562bc9bf11a7107a172ae97111a8101f94766786fbf681b80741c23294f718b1050b3b9596f88" +
                "73979dc29c9677ed1c126e5db9bd4a7f1456096d71d9fcfa261a605b81054b9bb42f03d2e2063eee3cff6053" +
                "b4767153c03ea2f647c3a8ad419a6df97fc033cb30baa387b07af87384e23217c35e303eb15a892e6949f3a2" +
                "b00754e0a280308d3c8c1570f490ab9a20ae7ba73dc6119f154b98c163fe06bd711b74633d24e3016da3fbca" +
                "0d6c20aa9bed7b0abf0c3dfc8206592fa2deb4d1e122a9b9730759e48362baafcb2e4e9ebd0b67b7b6a1c97e" +
                "096ca3ee15e5d3058c2d4f25a389f93fbc22f26632e4c8ca33f5b49aa895be1e2b562a5e2327d15da049c5fd" +
                "e124250f3010cf0541c3bd61e5d458978db0fe37eb2b3fb657c00ac37bc7a56e76b27dc1a5cbfcb822479621" +
                "f0ffd9ae5059eace1bb24d7e64f928ac375e34ffd9cdd87ba114387be818c8a3c868afb03ca8eeda7f67bdc6" +
                "e1f411a599a4909b43ad9a5b76717de2867fba07cc9e20cf3665d0757170dd49b765929b46c01bbbeab7f779" +
                "5bb290487d159cb181c07341c277cec423cba32c7f8f98c247ab837e34e0a3ad50ed86abc58382a9defea9f6" +
                "46b2d320dba8e7c6a82ac747743af357777b988bf498d77f62247dab94c44c9644a49189ad04d3e2a5159c6e" +
                "f3f8d995bf26a3951f8e6a875451292daf1143009aeaa4cfcf95de5d2b34f12b32b9245b57c2a7ee8889ec36" +
                "ecf2cb30c635a82d5a525c91f7187fdbc917b6f3e9c9a4a7df0c41273ffa87da1af4c19d3cb04160d57bfa14" +
                "14179c47cfd19bf81e59e431815b3e1d6f42e27b05dfc83027e7d470dd50d8fc08ba41035d11e1f9da42f341" +
                "e32d19018b8885d75b5c63f668477fdfc2ebb10108f8c7d0ec261aeb0647196d2905c598753ec25914defa76" +
                "25e6221a594635a1974d6b95bec6f7539458e89d428ba6f6344f38158923f76ac1c7d37e32e23ae0d020b144" +
                "9f8e3257ae98ead8a47095fe2d2076f42d8d7f0f5abd83f570a15664bafed063c14e597e88afc3ea7386d5a8" +
                "59af15b48ac9c5dde04cd8cfcdd6cc8fd3e9da6275d71cf41c13533a7a281de3d20f371c2d4dd5855f66bd0b" +
                "fe367339b6e29044ae0361ab77a7d61a954bdc0db910fd7432802f695143eaf50ab6e142e2c0d813e14fd67b" +
                "060e5184fea10cc927945157a0c52bc7232311d9d325c42a03c7c60d7844674cf66880b51b864527600a5313" +
                "59d3d623654827953823469e37179fc889f60f69c8d7fff4b19b3751909cd92211f89aebb256464c81ab45fb" +
                "ac92f4ec7d2454fa9ae888e1c439401fd2f9dea24ebb7b7cd74dd7e29b0eef05e7873d0e1a2a6c01f2ce7bfd" +
                "9eb2567b0fd666c4cc1be2c48108053d946622abfa72e2598475cde32a5e6f104ae4ff3d7ef2e6c71eed0403" +
                "d66f5c04265b0e1d8611e8dedcd899dbe671fe06e7ff30273eb605f860c6871c877779e50e4eb708fa3986c2" +
                "82fa0042dadfdf4131e41261eed84aad52517f777f8dfdeaa822195fd37a4a4c4971d2b33cb7abea2e841021" +
                "b2aed17af5b9dd82aa10aa6479a475d71cbb196954b8745e4b2335f8d3d6c86e6a7e4d9bdcc480c81a120386" +
                "1194efb2246db3c34b7c6d24d3b18edf254b66026455df8b842930d1bf50d779c0714be1fe701868efb5f9e6" +
                "f6bbe6776ed3f312e3012a67318279d780f639a38611a3e0efa720f5bc204ec2eabb68cf6908cadc71abfee1" +
                "d5cf2dbabe9e595383549e6fb27e4eef9412d5d8563020f309021e53baf5a9af1fa114fef620520b8cc20b6f" +
                "48aa8badc9513a7c97d47f725174adadc25b8151ce4865cd22a77cd773ebeada29cadef443935089d65e36d9" +
                "4cc6618138a93ecf3c5c23cad1cbdafdee465f011aaf4706b9b56723f90113aa532cf6feeaba7553e766de31" +
                "b87051cf5eff60d8b22fe979ca80ede748e69c7ed6811d086e1a700afcccdae6175b25e5695ffb7aaacab26d" +
                "6496b2dce841d7c25f3e6f0715db8b88c21b79a42e6647a40ba8cfddf2ad0331afa14aee9458891f746ca362" +
                "9a74f01ae3f3b2abd760a53ebcc69746c6df4320e87d35555e71309e29581d5284c3e089618018bd5f7a3e8f" +
                "0566dc6d1e255e7c9699b1cadfec010a23373a3c3d535557668a8cb8c0d1dbe7181b1e212736526f748c9192" +
                "a4a6c6d3dfe6e7ff030f101f30344c5d74848a8fadd1d2e7eaedfb000000000000000000000000000a1c3043" +
                "d627ecbcf614d1d4ae93845f32ff7dc188241509b154df2cb12e41c3ab5d043eeed0919381103816b84c6cae" +
                "3c1db31464ad484cca61b7aaceae0a252322d50f",
            rawToBeSignedHex =
                "65794a68624763694f694a4e54433145553045744e445174525751794e5455784f534973496d74705a434936" +
                "496a56465648703065574642555774325a6d786f6257704b4e6d31305a486473563056694d3035794e486c74" +
                "59305277546a686c574645346144516966512e5358516e6379426849475268626d646c636d39316379426964" +
                "584e70626d567a63797767526e4a765a473873494764766157356e4947393164434235623356794947527662" +
                "334975",
            rawMessageRepresentativeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4134342d456432353531392d53484135313200d23794746d85892c086d70a49905c7e9e4012755204e01da3a" +
                "f1d4d17743a448ec42685a47c8913bde2cf7eb4deb3e41aee03802d6bb889ebf61177aff41cbab",
        ),
        JoseVector(
            name = "Figure 3: ML-DSA-65-ES256",
            algorithm = Algorithm.ML_DSA_65_ES256,
            kid = "RKckYpZD-Fj494Q4vaeNmq6c3mqg_EhLkiUOo5mhNYE",
            joseAlg = "ML-DSA-65-ES256",
            pub =
                "QksvJn5Y1bO0TXGs_Gpla7JpUNV8YdsciAvPof6rRD8JQquL2619cIq7w1YHj22ZolInH-YsdAkeuUr7m5JkxQqI" +
                "jg3-2AzV-yy9NmfmDVOevkSTAhnNT67RXbs0VaJkgCufSbzkLudVD-_91GQqVa3mk4aKRgy-wD9PyZpOMLzP-opH" +
                "XlOVOWZ067galJN1h4gPbb0nvxxPWp7kPN2LDlOzt_tJxzrfvC1PjFQwNSDCm_l-Ju5X2zQtlXyJOTZSLQlCtB2C" +
                "7jdyoAVwrftUXBFDkisElvgmoKlwBks23fU0tfjhwc0LVWXqhGtFQx8GGBQ-zol3e7P2EXmtIClf4KbgYq5u7Lwu" +
                "848qwaItyTt7EmM2IjxVth64wHlVQruy3GXnIurcaGb_qWg764qZmteoPl5uAWwuTDX292Sa071S7GfsHFxue5ly" +
                "dxIYvpVUu6dyfwuExEubCovYMfz_LJd5zNTKMMatdbBJg-Qd6JPuXznqc1UYC3CccEXCLTOgg_auB6EUdG0b_cy-" +
                "5bkEOHm7Wi4SDipGNig_ShzUkkot5qSqPZnd2I9IqqToi_0ep2nYLBB3ny3teW21Qpccoom3aGPt5Zl7fpzhg7Q8" +
                "zsJ4sQ2SuHRCzgQ1uxYlFx21VUtHAjnFDSoMOkGyo4gH2wcLR7-z59EPPNl51pljyNefgCnMSkjrBPyz1wiET-uq" +
                "i23f8Bq2TVk1jmUFxOwdfLsU7SIS30WOzvwD_gMDexUFpMlEQyL1-Y36kaTLjEWGCi2tx1FTULttQx5JpryPW6lW" +
                "5oKw5RMyGpfRliYCiRyQePYqipZGoxOHpvCWhCZIN4meDY7H0RxWWQEpiyCzRQgWkOtMViwao6Jb7wZWbLNMebwL" +
                "JeQJXWunk-gTEeQaMykVJobwDUiX-E_E7fSybVRTZXherY1jrvZKh8C5Gi5VADg5Vs319uN8-dVILRyOOlvjjxcl" +
                "msRcn6HEvTvxd9MS7lKm2gI8BXIqhzgnTdqNGwTpmDHPV8hygqJWxWXCltBSSgY6OkGkioMAmXjZjYq_Ya9o6AE7" +
                "WU_hUdm-wZmQLExwtJWEIBdDxrUxA9L9JL3weNyQtaGItPjXcheZiNBBbJTUxXwIYLnXtT1M0mHzMqGFFWXVKsN_" +
                "AIdHyv4yDzY9m-tuQRfbQ_2K7r5eDOL1Tj8DZ-s8yXG74MMBqOUvlglJNgNcbuPKLRPbSDoN0E3BYkfeDgiUrXy3" +
                "4a5-vU-PkAWCsgAh539wJUUBxqw90V1Du7eTHFKDJEMSFYwusbPhEX4ZTwoeTHg--8Ysn4HCFWLQ00pfBCteqvMv" +
                "MflcWwVfTnogcPsJb1bEFVSc3nTzhk6Ln8J-MplyS0Y5mGBEtVko_WlyeFsoDCWj4hqrgU7L-ww8vsCRSQfskH8l" +
                "odiLzj0xmugiKjWUXbYq98x1zSnB9dmPy5P3UNwwMQdpebtR38N9I-jup4Bzok0-JsaOe7EORZ8ld7kAgDWa4K7B" +
                "Axjc2eD540Apwxs-VLGFVkXbQgYYeDNG2tW1Xt20-XezJqZVUl6-IZXsqc7DijwNInO3fT5o8ZAcLKUUlzSlEXe8" +
                "sIlHaxjLoJ-oubRtlKKUbzWOHeyxmYZSxYqQhSQj4sheedGXJEYWJ-Y5DRqB-xpy-cftxL10fdXIUhe1hWFBAoQU" +
                "3b5xRY8KCytYnfLhsFF4O49xhnax3vuumLpJbCqTXpLureoKg5PvWfnpFPB0P-ZWQN35mBzqbb3ZV6U0rU55DvyX" +
                "TuiZOK2Z1TxbaAd1OZMmg0cpuzewgueV-Nh_UubIqNto5RXCd7vqgqdXDUKAiWyYegYIkD4wbGMqIjxV8Oo2ggOc" +
                "Sj9UQPS1rD5u0rLckAzsxyty9Q5JsmKa0w8Eh7Jwe4Yob4xPVWWbJfm916avRgzDxXo5gmY7txdGFYHhlolJKdhB" +
                "U9h6f0gtKEtbiUzhp4IWsqAR8riHQs7lLVEz6P537a4kL1r5FjfDf_yjJDBQmy_kdWMDqaNln-MlKK8eENjUO-qZ" +
                "Gy0Ql4bMZtNbHXjfJUuSzapA-RqYfkqSLKgQUOW8NTDKhUk73yqCU3TQqDEKaGAoTsPscyMm7u_8QrvUK8kbc-Xn" +
                "xrWZ0BZJBjdinzh2w-QvjbWQ5mqFp4OMgY94__tIU8vvCUNJiYA1RdyodlfPfH5-avpxOCvBD6C7ZIDyQ-6huGEQ" +
                "EAb6DP8ydWIZQ8xY603DoEKKXkJWcP6CJo3nHFEdj_vcEbDQ-WESDpcQFa1fRIiGuALj-sEWcjGdSHyE8QATOcuW" +
                "l4TLVzRPKAf4tCXx1zyvhJbXQu0jf0yfzVpOhPun4n-xqK4SxPBCeuJOkQ2VG9jDXWH4pnjbAcrqjveJqVti7huM" +
                "XTLGuqU2uoihBw6mGqu_WSlOP2-XTEyRyvxbv2t-z9V6GPt1V9ceBukA0oGwtJqgD-q7NXFK8zhw7desI5PZMXf3" +
                "nuVgbJ3xdvAlzkmm5f9RoqQS6_hqwPQEcclq1MEZ3yML5hc99TDtZWy9gGkhR0Hs3QJxxgP7bEqGFP-HjTPnJsrG" +
                "aT6TjKP7qCxJlcFKLUr5AU_kxMULeUysWWtSGJ9mpxBvsyW1JuoEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5" +
                "RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q",
            priv =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwMQIBAQQgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAGgCgYIKoZIzj0DAQc",
            jws =
                "eyJhbGciOiJNTC1EU0EtNjUtRVMyNTYiLCJraWQiOiJSS2NrWXBaRC1GajQ5NFE0dmFlTm1xNmMzbXFnX0VoTGtp" +
                "VU9vNW1oTllFIn0.SXQncyBhIGRhbmdlcm91cyBidXNpbmVzcywgRnJvZG8sIGdvaW5nIG91dCB5b3VyIGRvb3Iu" +
                ".GzphtwRHY9KgNF0UqxtQAOwpz2g9EAzf469GH_RPhCcHtYVOjmAAiGh_KvWorExxhXNCacrIjS_YSNGpMJuabMM" +
                "DDCLDD2AHcRkcrdr65_KohNi_RZYlVZa5K-620DyWmPdtGGrM9q8QZyv_Jxtujzl5RRjMZgk3JPEVn1gw5Ep21WY" +
                "Yiw84LQ-ZAJWkfNTaz8pRFDHN0vOtg8vuuqpsjXuB5qdXlxZRn_O4QAReU374i2LqjYttYqomSMXhqz-VuHS2ua5" +
                "XMvtdCRB13O3ag_gglbMDqfSINcHrRB65SjzGHv9LdOzvlTlAVQoLLKh3tCavaJxnrLLtbY9SjCAp24IlaciluqM" +
                "3g5k6NAW6s-XAhXwBKa5jAILcNqq3z9pSG3ZaT9-RtixIdJ6E4BVNdaow4MKUvIytQ4xwlWJkZXZOolHcOYbXrRz" +
                "AEou63CyfUyDljKdlhmsI4_i3UhelYr-0AmdNcWXfC1LPMMfS1tH4laI8p9XI1ZAL4ZX05Wdki8TyJzBc9-7Mo7e" +
                "Ct-XCRvrwqIS_FI4Lmp0OL62XwIftpYnrqgCoV3_EwxfbGGgtutO88lczZDHm7vwwnDvMExklPsHSBP_hY9IGELe" +
                "lTEe_yIboAQKNeuPfPJeR4WEqEyhk_qf2AlYYbAVwenVZqKUTmzgcIi4aWQGyISsZuSMGkYryNGf7l6SjbiQzY83" +
                "l4V49zcKZ-ZwPafmsiSG3_Te7Cyyg9nuJmBSohvm7YyibTkHypjHVzyJPXbxDeWnm71xEEk7ulwv_m7r9c7yBJ4M" +
                "2b9sElnppTZ5buMpmW0ViR7Rd7b7F2xComMbzB1Df2V9klY31qd-6OB1lvlRXVLnBfD0N997-Hxbz6Z2iTK9RxJF" +
                "wYnR-oV_UsPUY0AmWduGCSbTe2NUtMNjFyLI2bsNaS9z5nJJrlysuwa2uFnCT65eUx2yi8yIQqUGh_lg4cz5h9s3" +
                "Dc_mdcTdGqCKqy0YHYMNqDP6eVCUjJ88QZnUzH56xdNdRUPgJyX0R1fEEW2CK-S3pL3cJptHF8Q1KJBBhfJZz7np" +
                "Hw1A1p1mGwcxTvJgnK0hPpQHT5RQD8jXmwcbrHVNtObH5WWHAx0llzLy1VKpjPEkDXTQqcAgCyL2HNDQNdl3h9F9" +
                "yc9EGkXzu_wvDJif661o-JD2f3O8W8BYZvLMP3GO9zCusvtgSSF4ycc78gCLnKuTQJ41P0XeF-lGxM_k-lGEDI8J" +
                "mFBPFYOmz2t62Q1FtKUX9QFZTnOf7G-WAuWDrIyFq-ie3FnBDIyH3LuV1_dBJ0OUNyz1eh_OMjTnkl8j6XI0_Kqh" +
                "w2G3ysOIRjAraGaXWLcAJHe4xCeG_96z8faM18k0flmiM39aqoKByPoKGGg5MFjqn--MDFp3j8p3aEHh6EGs8ZV1" +
                "AzuRT_xlE9urzByHPOTU4dzaXZtNmXuYtP-NvKZsRnG36-RP3vzvaK7vE4qm6YwvMxuRlZqX-zFnjfeN7V5HSsXR" +
                "vkAvN9xjhiX1vzZHOLCzfCIR7NCz4SdAI9zmjukYMZ4RlTYRYfUbzo5Hya1v_5-C2z9Vb5ozCoMNcArckv64mEsd" +
                "1E4_8bgniCZf2ryZVKKhkDUqnPPHom1YfBlf20TcLp-qYZRcO1Q3ebjRt_gp44TGfSlJrL_cfutXnSJ6Td5ID8no" +
                "FrCC1BagB1TAOwhZamVrtHXL9otOpv5hc9pCdG4y-zoCpCRKAoir2t3lqI8muetAU1b3uicceAZHfX2ISij2_nfp" +
                "1dmahBkbqO8UuYJW50K2wJS7DPxBg2Ms8iLAsI-JOolzDKafq-Mru79jRJIGHqlyhb9tTskKix-EpMsDuD3UwBFh" +
                "qf-pnlQaHpVaMdawhhsYbwyXcgS2ckxBODmeXsUG1jvNZ_CHVI71fgG_bzUHDVJDmqQeoNU6V0HY4Upx6uNHL7sY" +
                "_fpt9LX4i2hfOrLjUJSXSxRMiDP31rbdHae2Fr0Z80o-tlcqA0JRLT1PO4l94CGDn1E3L2YJzjRBi1A6Ihy7XaAf" +
                "pZPGIE_HJkXfjW_bXe1cJ1Baa1h5lBz9ASyKSxxy9P0gaqa4qAz6-KSPwaKlGoxQpVklPj7C0eli7ohcmqe_vjod" +
                "FSHG8feXbOWYki-n9lJyAoIj_0eCihoz1K9dr34Uj7Edb7gHXhN-tQyLQik328d_jSZJ5XsLFuIe-YGifdFpm39D" +
                "PtWTVPh7LtM_3tGh-SiF1qVFSzXknQa82s5EEn2168-yxWmxgZzvUTN_TQ1CWLgjTVriQ9EiGFAcKd1Adzb7sJBz" +
                "lsXgShYYzn_APQUaHtAJUC2OUe7W91Ub4hfmiNRy5-VOmSPiocsDS30mpeZUPxYjDJg1Jk-1ZpyKLoj0dNNVSVRb" +
                "oKuuMUM-RXBXzJGiQJnPlucNxiZfxl_-HmgEM7fh3wrQztg1dM969BaG2GjDgdyyzSGXO9-0xY4TvoEMLJ1vb283" +
                "LYkDVL_C0-nUpcSxRlkLvmW05urVhxqG_Sr162fty-lxrxaVfGTHrroyHkv9frx2_ultrAne75SwoLJ0tgdf4iVO" +
                "QwbMaukK8LVRdxU6stYHiGcusUPYu-I5m6NS69kYRU4huHMUnFMZX3WoMhN-smzZOgPPDzS9CBK1o5st7_f9hkJc" +
                "WNYHKNsXgqwq4djqqOR9nxMFap0uCntQSOYyO5dCqyP_osNnrkSTEEjuoa8N4xyTvZQKDVZDzLGbcWx099ZHyC--" +
                "SauSsf26Ix6k6OkyowtXn-qoq-xLSjBoaZs_MnZTRvTJiHMmJkvMcNeDDRkhbvwzd-EsoVlMxP5iqYXPzo0X5wFm" +
                "wOGif6gEoZdAAfsw9eP1LccOM69d18c4-Wh39dkwOzxXBiMwCwukKuJRDSyu0WHu_tJqjhjSs5PwF7HAiJu2o3Wh" +
                "tX3L_mCC9WIq_6GE6__O0dXYELoye5-7zmNQFSTrm6GPFb430ertDPVuvg7CZpNdysY9HnX2BrmKbZQNx5plDmit" +
                "BrhmAt7xsAsnujR6TqPaCnxoMv8d8PYUjPRFSxEqZz8iP7vOaN_fDSkLytGowJZfmabm5DaF1wl14ZbPHFZWvzsL" +
                "janGlk6mNJUuLNPDKSuKvPdwua83NSR1_Xy4psMK6-_6AJP_vofuZ0Ctgj3KYCyxzxOKgaDU1nIL_VfzgkrOVU_V" +
                "yoqTmE1HIk7qNQiJO1fJhMql70IiiF9yl4O5ohA0_XMASMMpeU0jNHDNXHs8V-3vlefLzu3KeiMLm7u7krWEoK8G" +
                "U1rVnVhXgSqbQcCjroN3dR80wGsXL-m24GMFJsW4GBXw92RyPUsUTXgPgq6EMsPqJ8axYTWvyajar2S-D5fhFeYs" +
                "Zg-1VdXZl-cIBGZwBc-Az9LyKfdmgGM9knMWzfhoCxdJZNhMp3_1DPENeNXdmOIqvqUs83UwWyq9MQGoE91W8efe" +
                "R9-kyPEJ4q8R8g_HJ_QSomij6W7nsFLWYMA1xyIpy5v8H3-YVSgbKKoY7oGcyQClQ6HTE7RxnywFG1mPJab2IPyQ" +
                "uxHTe3TSe4Xbwx83dwfohnF1DySNJ-m4cjRyRdcbFac0ypQ-DX5vlEn2G6ktOOMuwgm3dMr2lD_uv_1RTCgqMJkT" +
                "-HGwVpU91DcI4y9z2X8LWrpJCbTdbtgxoWEwlRecW13rUXhc6iYEzRu8cbRpuA43LD-BoU-RpGpzoZ-E4OG8wy9l" +
                "9VSBvYFxEk2bTVdCpAJdz4JFWWqAqsBKKF-ZPhsQU2RKjtIwKxfTi_ruP5y9OUUiIb7uE1B_85m6OVieMurDiaFw" +
                "ytN7Yx3bfzXHQ-ZkBM1LWJTzOoWJ7I-KMmCIggnxvOSezvUXYQzJxKrPCqYXGlB21MG9XzJl37BjWGgCEUl8XIZH" +
                "GRYTorJukBoAyoyofUnS5q1zglvnkhuZQKyJxCYa-p0CNNHaLTHisD3Qpq0-yTp0_hBMgFp2sPgBK7CkiECoQCck" +
                "qxt4gTnZOCAhAhGjTSBrubxuqZj79hB9G_8eTjedKiHSPUxIzBkYkqFeUbo5NNG3leRd9wOtaH4BmYXsNLFKdLJe" +
                "Q16839y70dy9Gqwzj9yaXJVCkG2i92ChfM0GYr2RJi9XXlsypMkLKC1sUeab0vXQShOBS4To9OIRxjAfA0V7O3VY" +
                "ziCgMrl3vLbWMT71tx_uk0QJUKqHddCj0CW8pMsd8nI63if6KQ1_Q77YK8exeMJTDMd4kG7S8wSgFIOW0QmICQqn" +
                "fE8nzYtxllu0baGM4Nl4mQ5Rx_1WjE1JHJQt3Tg5kTjcgjXN6sUMuEBbxy5sk5p34AtQyX8BidJ1YG4Kb2cmJakW" +
                "85dkpwF6Gjqmq5O7-kIMbgpGtvs7pCCc7ZZiys9LyE1u5yA0aMThMaI2p0OMECjBKU4nf_P4PFjhAUVxqoqkAAAA" +
                "AAAAABxAUHicwMEYCIQDBZ7BP_h4-rRhdoCuTcL_UsDS7XsVq3OxpiKzztsb_KAIhAJp4r-4_cn_2KGEnlNy0f7L" +
                "zrmCPGPdiXAT9bVHrwyL-",
            rawCompositePubHex =
                "424b2f267e58d5b3b44d71acfc6a656bb26950d57c61db1c880bcfa1feab443f0942ab8bdbad7d708abbc356" +
                "078f6d99a252271fe62c74091eb94afb9b9264c50a888e0dfed80cd5fb2cbd3667e60d539ebe44930219cd4f" +
                "aed15dbb3455a264802b9f49bce42ee7550feffdd4642a55ade693868a460cbec03f4fc99a4e30bccffa8a47" +
                "5e5395396674ebb81a94937587880f6dbd27bf1c4f5a9ee43cdd8b0e53b3b7fb49c73adfbc2d4f8c54303520" +
                "c29bf97e26ee57db342d957c893936522d0942b41d82ee3772a00570adfb545c1143922b0496f826a0a97006" +
                "4b36ddf534b5f8e1c1cd0b5565ea846b45431f0618143ece89777bb3f61179ad20295fe0a6e062ae6eecbc2e" +
                "f38f2ac1a22dc93b7b126336223c55b61eb8c0795542bbb2dc65e722eadc6866ffa9683beb8a999ad7a83e5e" +
                "6e016c2e4c35f6f7649ad3bd52ec67ec1c5c6e7b9972771218be9554bba7727f0b84c44b9b0a8bd831fcff2c" +
                "9779ccd4ca30c6ad75b04983e41de893ee5f39ea7355180b709c7045c22d33a083f6ae07a114746d1bfdccbe" +
                "e5b9043879bb5a2e120e2a4636283f4a1cd4924a2de6a4aa3d99ddd88f48aaa4e88bfd1ea769d82c10779f2d" +
                "ed796db542971ca289b76863ede5997b7e9ce183b43ccec278b10d92b87442ce0435bb1625171db5554b4702" +
                "39c50d2a0c3a41b2a38807db070b47bfb3e7d10f3cd979d69963c8d79f8029cc4a48eb04fcb3d708844febaa" +
                "8b6ddff01ab64d59358e6505c4ec1d7cbb14ed2212df458ecefc03fe03037b1505a4c9444322f5f98dfa91a4" +
                "cb8c45860a2dadc7515350bb6d431e49a6bc8f5ba956e682b0e513321a97d1962602891c9078f62a8a9646a3" +
                "1387a6f09684264837899e0d8ec7d11c565901298b20b345081690eb4c562c1aa3a25bef06566cb34c79bc0b" +
                "25e4095d6ba793e81311e41a3329152686f00d4897f84fc4edf4b26d545365785ead8d63aef64a87c0b91a2e" +
                "5500383956cdf5f6e37cf9d5482d1c8e3a5be38f17259ac45c9fa1c4bd3bf177d312ee52a6da023c05722a87" +
                "38274dda8d1b04e99831cf57c87282a256c565c296d0524a063a3a41a48a83009978d98d8abf61af68e8013b" +
                "594fe151d9bec199902c4c70b49584201743c6b53103d2fd24bdf078dc90b5a188b4f8d772179988d0416c94" +
                "d4c57c0860b9d7b53d4cd261f332a1851565d52ac37f008747cafe320f363d9beb6e4117db43fd8aeebe5e0c" +
                "e2f54e3f0367eb3cc971bbe0c301a8e52f96094936035c6ee3ca2d13db483a0dd04dc16247de0e0894ad7cb7" +
                "e1ae7ebd4f8f900582b20021e77f70254501c6ac3dd15d43bbb7931c5283244312158c2eb1b3e1117e194f0a" +
                "1e4c783efbc62c9f81c21562d0d34a5f042b5eaaf32f31f95c5b055f4e7a2070fb096f56c415549cde74f386" +
                "4e8b9fc27e3299724b4639986044b55928fd6972785b280c25a3e21aab814ecbfb0c3cbec0914907ec907f25" +
                "a1d88bce3d319ae8222a35945db62af7cc75cd29c1f5d98fcb93f750dc3031076979bb51dfc37d23e8eea780" +
                "73a24d3e26c68e7bb10e459f2577b90080359ae0aec10318dcd9e0f9e34029c31b3e54b1855645db42061878" +
                "3346dad5b55eddb4f977b326a655525ebe2195eca9cec38a3c0d2273b77d3e68f1901c2ca5149734a51177bc" +
                "b089476b18cba09fa8b9b46d94a2946f358e1decb1998652c58a90852423e2c85e79d19724461627e6390d1a" +
                "81fb1a72f9c7edc4bd747dd5c85217b5856141028414ddbe71458f0a0b2b589df2e1b051783b8f718676b1de" +
                "fbae98ba496c2a935e92eeadea0a8393ef59f9e914f0743fe65640ddf9981cea6dbdd957a534ad4e790efc97" +
                "4ee89938ad99d53c5b680775399326834729bb37b082e795f8d87f52e6c8a8db68e515c277bbea82a7570d42" +
                "80896c987a0608903e306c632a223c55f0ea3682039c4a3f5440f4b5ac3e6ed2b2dc900cecc72b72f50e49b2" +
                "629ad30f0487b2707b86286f8c4f55659b25f9bdd7a6af460cc3c57a3982663bb717461581e196894929d841" +
                "53d87a7f482d284b5b894ce1a78216b2a011f2b88742cee52d5133e8fe77edae242f5af91637c37ffca32430" +
                "509b2fe4756303a9a3659fe32528af1e10d8d43bea991b2d109786cc66d35b1d78df254b92cdaa40f91a987e" +
                "4a922ca81050e5bc3530ca85493bdf2a825374d0a8310a6860284ec3ec732326eeeffc42bbd42bc91b73e5e7" +
                "c6b599d016490637629f3876c3e42f8db590e66a85a7838c818f78fffb4853cbef09434989803545dca87657" +
                "cf7c7e7e6afa71382bc10fa0bb6480f243eea1b861101006fa0cff3275621943cc58eb4dc3a0428a5e425670" +
                "fe82268de71c511d8ffbdc11b0d0f961120e971015ad5f448886b802e3fac11672319d487c84f1001339cb96" +
                "9784cb57344f2807f8b425f1d73caf8496d742ed237f4c9fcd5a4e84fba7e27fb1a8ae12c4f0427ae24e910d" +
                "951bd8c35d61f8a678db01caea8ef789a95b62ee1b8c5d32c6baa536ba88a1070ea61aabbf59294e3f6f974c" +
                "4c91cafc5bbf6b7ecfd57a18fb7557d71e06e900d281b0b49aa00feabb35714af33870edd7ac2393d93177f7" +
                "9ee5606c9df176f025ce49a6e5ff51a2a412ebf86ac0f40471c96ad4c119df230be6173df530ed656cbd8069" +
                "214741ecdd0271c603fb6c4a8614ff878d33e726cac6693e938ca3fba82c4995c14a2d4af9014fe4c4c50b79" +
                "4cac596b52189f66a7106fb325b526ea046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a139" +
                "45d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            rawCompositeSigHex =
                "1b3a61b7044763d2a0345d14ab1b5000ec29cf683d100cdfe3af461ff44f842707b5854e8e600088687f2af5" +
                "a8ac4c7185734269cac88d2fd848d1a9309b9a6cc3030c22c30f600771191caddafae7f2a884d8bf45962555" +
                "96b92beeb6d03c9698f76d186accf6af10672bff271b6e8f39794518cc66093724f1159f5830e44a76d56618" +
                "8b0f382d0f990095a47cd4dacfca511431cdd2f3ad83cbeebaaa6c8d7b81e6a7579716519ff3b840045e537e" +
                "f88b62ea8d8b6d62aa2648c5e1ab3f95b874b6b9ae5732fb5d091075dcedda83f82095b303a9f48835c1eb44" +
                "1eb94a3cc61eff4b74ecef953940550a0b2ca877b426af689c67acb2ed6d8f528c2029db822569c8a5baa337" +
                "83993a3405bab3e5c0857c0129ae630082dc36aab7cfda521b765a4fdf91b62c48749e84e0154d75aa30e0c2" +
                "94bc8cad438c7095626465764ea251dc3986d7ad1cc0128bbadc2c9f5320e58ca765866b08e3f8b75217a562" +
                "bfb402674d7165df0b52cf30c7d2d6d1f895a23ca7d5c8d5900be195f4e567648bc4f227305cf7eecca3b782" +
                "b7e5c246faf0a884bf148e0b9a9d0e2fad97c087eda589ebaa00a8577fc4c317db18682dbad3bcf257336431" +
                "e6eefc309c3bcc1319253ec1d204ffe163d20610b7a54c47bfc886e801028d7ae3df3c9791e1612a132864fe" +
                "a7f60256186c05707a7559a8a5139b381c222e1a5901b2212b19b92306918af23467fb97a4a36e243363cde5" +
                "e15e3dcdc299f99c0f69f9ac8921b7fd37bb0b2ca0f67b899814a886f9bb63289b4e41f2a631d5cf224f5dbc" +
                "437969e6ef5c44124eee970bff9bbafd73bc812783366fdb04967a694d9e5bb8ca665b456247b45dedbec5db" +
                "10a898c6f30750dfd95f64958df5a9dfba381d65be545754b9c17c3d0df7defe1f16f3e99da24caf51c49170" +
                "62747ea15fd4b0f518d0099676e18249b4ded8d52d30d8c5c8b2366ec35a4bdcf99c926b972b2ec1adae1670" +
                "93eb9794c76ca2f32210a941a1fe5838733e61f6cdc373f99d713746a822aacb460760c36a0cfe9e54252327" +
                "cf106675331f9eb174d75150f809c97d11d5f1045b608af92de92f7709a6d1c5f10d4a2410617c9673ee7a47" +
                "c35035a75986c1cc53bc98272b484fa501d3e51403f235e6c1c6eb1d536d39b1f95961c0c74965ccbcb554aa" +
                "633c49035d342a700802c8bd8734340d765de1f45f7273d106917ceeff0bc32627faeb5a3e243d9fdcef16f0" +
                "1619bcb30fdc63bdcc2bacbed812485e3271cefc8022e72ae4d0278d4fd17785fa51b133f93e94610323c266" +
                "1413c560e9b3dadeb643516d2945fd4056539ce7fb1be580b960eb23216afa27b71670432321f72ee575fdd0" +
                "49d0e50dcb3d5e87f38c8d39e497c8fa5c8d3f2aa870d86df2b0e2118c0ada19a5d62dc0091dee3109e1bff7" +
                "acfc7da335f24d1f96688cdfd6aaa0a0723e82861a0e4c163aa7fbe303169de3f29dda10787a106b3c655d40" +
                "cee453ff1944f6eaf30721cf39353877369766d3665ee62d3fe36f299b119c6dfaf913f7bf3bda2bbbc4e2a9" +
                "ba630bccc6e46566a5fecc59e37de37b5791d2b1746f900bcdf718e1897d6fcd91ce2c2cdf08847b342cf849" +
                "d008f739a3ba460c6784654d84587d46f3a391f26b5bffe7e0b6cfd55be68cc2a0c35c02b724bfae2612c775" +
                "138ffc6e09e20997f6af265528a8640d4aa73cf1e89b561f0657f6d1370ba7ea9865170ed50dde6e346dfe0a" +
                "78e1319f4a526b2ff71fbad5e7489e93779203f27a05ac20b505a801d5300ec2165a995aed1d72fda2d3a9bf" +
                "985cf6909d1b8cbece80a9091280a22af6b7796a23c9ae7ad014d5bdee89c71e0191df5f62128a3dbf9dfa75" +
                "7666a10646ea3bc52e6095b9d0adb0252ec33f1060d8cb3c88b02c23e24ea25cc329a7eaf8caeeefd8d12481" +
                "87aa5ca16fdb53b242a2c7e12932c0ee0f753004586a7fea67950687a5568c75ac2186c61bc325dc812d9c93" +
                "104e0e6797b141b58ef359fc21d523bd5f806fdbcd41c35490e6a907a8354e95d07638529c7ab8d1cbeec63f" +
                "7e9b7d2d7e22da17ceacb8d42525d2c513220cfdf5adb74769ed85af467cd28fad95ca80d0944b4f53cee25f" +
                "780860e7d44dcbd982738d1062d40e88872ed76807e964f18813f1c99177e35bf6d77b5709d4169ad61e6507" +
                "3f404b2292c71cbd3f481aa9ae2a033ebe2923f068a946a3142956494f8fb0b47a58bba21726a9efef8e8745" +
                "4871bc7de5db3966248be9fd949c80a088ffd1e0a2868cf52bd76bdf8523ec475bee01d784dfad4322d08a4d" +
                "f6f1dfe34992795ec2c5b887be60689f745a66dfd0cfb564d53e1ecbb4cff7b4687e4a2175a95152cd792741" +
                "af36b391049f6d7af3ecb15a6c60673bd44cdfd34350962e08d356b890f4488614070a77501dcdbeec241ce5" +
                "b178128586339ff00f414687b402540b63947bb5bdd546f885f9a2351cb9f953a648f8a872c0d2df49a97995" +
                "0fc588c3260d4993ed59a7228ba23d1d34d5525516e82aeb8c50cf915c15f32468902673e5b9c3718997f197" +
                "ff879a010cedf877c2b433b60d5d33debd05a1b61a30e0772cb34865cef7ed316384efa0430b275bdbdbcdcb" +
                "6240d52ff0b4fa7529712c519642ef996d39bab561c6a1bf4abd7ad9fb72fa5c6bc5a55f1931ebae8c8792ff" +
                "5faf1dbfba5b6b0277bbe52c282c9d2d81d7f8895390c1b31aba42bc2d545dc54eacb581e219cbac50f62ef8" +
                "8e66e8d4baf6461153886e1cc52714c657dd6a0c84dfac9b364e80f3c3cd2f4204ad68e6cb7bfdff61909716" +
                "3581ca36c5e0ab0ab8763aaa391f67c4c15aa74b829ed412398c8ee5d0aac8ffe8b0d9eb9124c4123ba86bc3" +
                "78c724ef6502835590f32c66dc5b1d3df591f20bef926ae4ac7f6e88c7a93a3a4ca8c2d5e7faaa2afb12d28c" +
                "1a1a66cfcc9d94d1bd32621cc98992f31c35e0c346485bbf0cddf84b285653313f98aa6173f3a345f9c059b0" +
                "38689fea012865d0007ecc3d78fd4b71c38cebd775f1ce3e5a1dfd764c0ecf15c188cc02c2e90ab894434b2b" +
                "b4587bbfb49aa38634ace4fc05ec702226eda8dd686d5f72ff9820bd588abfe8613afff3b47576042e8c9ee7" +
                "eef398d405493ae6e863c56f8df47abb433d5baf83b099a4d772b18f479d7d81ae629b650371e699439a2b41" +
                "ae1980b7bc6c02c9ee8d1e93a8f6829f1a0cbfc77c3d85233d1152c44a99cfc88feef39a37f7c34a42f2b46a" +
                "302597e669b9b90da175c25d7865b3c71595afcec2e36a71a593a98d254b8b34f0ca4ae2af3ddc2e6bcdcd49" +
                "1d7f5f2e29b0c2bafbfe8024ffefa1fb99d02b608f72980b2c73c4e2a06835359c82ff55fce092b39553f572" +
                "a2a4e61351c893ba8d42224ed5f26132a97bd088a217dca5e0ee68840d3f5cc01230ca5e5348cd1c33571ecf" +
                "15fb7be579f2f3bb729e88c2e6eeeee4ad61282bc194d6b5675615e04aa6d07028eba0dddd47cd301ac5cbfa" +
                "6db818c149b16e06057c3dd91c8f52c5135e03e0aba10cb0fa89f1ac584d6bf26a36abd92f83e5f845798b19" +
                "83ed55757665f9c201199c0173e033f4bc8a7dd9a018cf649cc5b37e1a02c5d259361329dffd433c435e3577" +
                "66388aafa94b3cdd4c16caaf4c406a04f755bc79f791f7e9323c4278abc47c83f1c9fd04a89a28fa5bb9ec14" +
                "b598300d71c88a72e6ff07dfe6154a06ca2a863ba06732402950e874c4ed1c67cb0146d663c969bd883f242e" +
                "c474dedd349ee176f0c7cdddc1fa219c5d43c92349fa6e1c8d1c9175c6c569cd32a50f835f9be5127d86ea4b" +
                "4e38cbb0826ddd32bda50ffbafff54530a0a8c2644fe1c6c15a54f750dc238cbdcf65fc2d6ae92426d375bb6" +
                "0c68584c2545e716d77ad45e173a89813346ef1c6d1a6e038dcb0fe06853e4691a9ce867e138386f30cbd97d" +
                "55206f605c449366d355d0a9009773e091565aa02ab0128a17e64f86c414d912a3b48c0ac5f4e2febb8fe72f" +
                "4e5148886fbb84d41ffce66e8e56278cbab0e2685c32b4ded8c776dfcd71d0f999013352d6253ccea1627b23" +
                "e28c982220827c6f3927b3bd45d84332712ab3c2a985c6941db5306f57cc9977ec18d61a0084525f172191c6" +
                "4584e8ac9ba4068032a32a1f5274b9ab5ce096f9e486e6502b22710986bea7408d34768b4c78ac0f7429ab4f" +
                "b24e9d3f841320169dac3e004aec2922102a1009c92ac6de204e764e0808408468d3481aee6f1baa663efd84" +
                "1f46ffc7938de74a88748f531233064624a857946e8e4d346de579177dc0eb5a1f8066617b0d2c529d2c9790" +
                "d7af37f72ef4772f46ab0ce3f726972550a41b68bdd8285f334198af64498bd5d796cca93242ca0b5b1479a6" +
                "f4bd741284e052e13a3d3884718c07c0d15ecedd563388280cae5def2db58c4fbd6dc7fba4d102542aa1dd74" +
                "28f4096f2932c77c9c8eb789fe8a435fd0efb60af1ec5e3094c331de241bb4bcc1280520e5b442620242a9df" +
                "13c9f362dc6596ed1b686338365e26439471ff55a3135247250b774e0e644e37208d737ab1432e1016f1cb9b" +
                "24e69df802d4325fc062749d581b829bd9c9896a45bce5d929c05e868ea9aae4eefe90831b8291adbecee908" +
                "273b6598b2b3d2f2135bb9c80d1a31384c688da9d0e3040a304a5389dffcfe0f163840515c6aa2a900000000" +
                "0000000710141e27303046022100c167b04ffe1e3ead185da02b9370bfd4b034bb5ec56adcec6988acf3b6c6" +
                "ff280221009a78afee3f727ff628612794dcb47fb2f3ae608f18f7625c04fd6d51ebc322fe",
            rawToBeSignedHex =
                "65794a68624763694f694a4e54433145553045744e6a557452564d794e5459694c434a72615751694f694a53" +
                "53324e725758426152433147616a51354e464530646d466c546d31784e6d4d7a6258466e5830566f54477470" +
                "565539764e57316f546c6c46496e302e5358516e6379426849475268626d646c636d39316379426964584e70" +
                "626d567a63797767526e4a765a473873494764766157356e4947393164434235623356794947527662334975",
            rawMessageRepresentativeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4136352d45434453412d503235362d534841353132003c0860140c9827362bea6ce409bad08c87f67b85daf9" +
                "ed683ef2e6af2bc559a92ccaff26d3da9ee6b7cad46d1332f08ec2ca4b817ca5c27d3f260b73cf4325b4",
        ),
        JoseVector(
            name = "Figure 4: ML-DSA-65-ES384",
            algorithm = Algorithm.ML_DSA_65_ES384,
            kid = "qmo6dT7r6gK-FQQ6vNVm--J2WxyC3mhNu4U8yn0w35w",
            joseAlg = "ML-DSA-65-ES384",
            pub =
                "QksvJn5Y1bO0TXGs_Gpla7JpUNV8YdsciAvPof6rRD8JQquL2619cIq7w1YHj22ZolInH-YsdAkeuUr7m5JkxQqI" +
                "jg3-2AzV-yy9NmfmDVOevkSTAhnNT67RXbs0VaJkgCufSbzkLudVD-_91GQqVa3mk4aKRgy-wD9PyZpOMLzP-opH" +
                "XlOVOWZ067galJN1h4gPbb0nvxxPWp7kPN2LDlOzt_tJxzrfvC1PjFQwNSDCm_l-Ju5X2zQtlXyJOTZSLQlCtB2C" +
                "7jdyoAVwrftUXBFDkisElvgmoKlwBks23fU0tfjhwc0LVWXqhGtFQx8GGBQ-zol3e7P2EXmtIClf4KbgYq5u7Lwu" +
                "848qwaItyTt7EmM2IjxVth64wHlVQruy3GXnIurcaGb_qWg764qZmteoPl5uAWwuTDX292Sa071S7GfsHFxue5ly" +
                "dxIYvpVUu6dyfwuExEubCovYMfz_LJd5zNTKMMatdbBJg-Qd6JPuXznqc1UYC3CccEXCLTOgg_auB6EUdG0b_cy-" +
                "5bkEOHm7Wi4SDipGNig_ShzUkkot5qSqPZnd2I9IqqToi_0ep2nYLBB3ny3teW21Qpccoom3aGPt5Zl7fpzhg7Q8" +
                "zsJ4sQ2SuHRCzgQ1uxYlFx21VUtHAjnFDSoMOkGyo4gH2wcLR7-z59EPPNl51pljyNefgCnMSkjrBPyz1wiET-uq" +
                "i23f8Bq2TVk1jmUFxOwdfLsU7SIS30WOzvwD_gMDexUFpMlEQyL1-Y36kaTLjEWGCi2tx1FTULttQx5JpryPW6lW" +
                "5oKw5RMyGpfRliYCiRyQePYqipZGoxOHpvCWhCZIN4meDY7H0RxWWQEpiyCzRQgWkOtMViwao6Jb7wZWbLNMebwL" +
                "JeQJXWunk-gTEeQaMykVJobwDUiX-E_E7fSybVRTZXherY1jrvZKh8C5Gi5VADg5Vs319uN8-dVILRyOOlvjjxcl" +
                "msRcn6HEvTvxd9MS7lKm2gI8BXIqhzgnTdqNGwTpmDHPV8hygqJWxWXCltBSSgY6OkGkioMAmXjZjYq_Ya9o6AE7" +
                "WU_hUdm-wZmQLExwtJWEIBdDxrUxA9L9JL3weNyQtaGItPjXcheZiNBBbJTUxXwIYLnXtT1M0mHzMqGFFWXVKsN_" +
                "AIdHyv4yDzY9m-tuQRfbQ_2K7r5eDOL1Tj8DZ-s8yXG74MMBqOUvlglJNgNcbuPKLRPbSDoN0E3BYkfeDgiUrXy3" +
                "4a5-vU-PkAWCsgAh539wJUUBxqw90V1Du7eTHFKDJEMSFYwusbPhEX4ZTwoeTHg--8Ysn4HCFWLQ00pfBCteqvMv" +
                "MflcWwVfTnogcPsJb1bEFVSc3nTzhk6Ln8J-MplyS0Y5mGBEtVko_WlyeFsoDCWj4hqrgU7L-ww8vsCRSQfskH8l" +
                "odiLzj0xmugiKjWUXbYq98x1zSnB9dmPy5P3UNwwMQdpebtR38N9I-jup4Bzok0-JsaOe7EORZ8ld7kAgDWa4K7B" +
                "Axjc2eD540Apwxs-VLGFVkXbQgYYeDNG2tW1Xt20-XezJqZVUl6-IZXsqc7DijwNInO3fT5o8ZAcLKUUlzSlEXe8" +
                "sIlHaxjLoJ-oubRtlKKUbzWOHeyxmYZSxYqQhSQj4sheedGXJEYWJ-Y5DRqB-xpy-cftxL10fdXIUhe1hWFBAoQU" +
                "3b5xRY8KCytYnfLhsFF4O49xhnax3vuumLpJbCqTXpLureoKg5PvWfnpFPB0P-ZWQN35mBzqbb3ZV6U0rU55DvyX" +
                "TuiZOK2Z1TxbaAd1OZMmg0cpuzewgueV-Nh_UubIqNto5RXCd7vqgqdXDUKAiWyYegYIkD4wbGMqIjxV8Oo2ggOc" +
                "Sj9UQPS1rD5u0rLckAzsxyty9Q5JsmKa0w8Eh7Jwe4Yob4xPVWWbJfm916avRgzDxXo5gmY7txdGFYHhlolJKdhB" +
                "U9h6f0gtKEtbiUzhp4IWsqAR8riHQs7lLVEz6P537a4kL1r5FjfDf_yjJDBQmy_kdWMDqaNln-MlKK8eENjUO-qZ" +
                "Gy0Ql4bMZtNbHXjfJUuSzapA-RqYfkqSLKgQUOW8NTDKhUk73yqCU3TQqDEKaGAoTsPscyMm7u_8QrvUK8kbc-Xn" +
                "xrWZ0BZJBjdinzh2w-QvjbWQ5mqFp4OMgY94__tIU8vvCUNJiYA1RdyodlfPfH5-avpxOCvBD6C7ZIDyQ-6huGEQ" +
                "EAb6DP8ydWIZQ8xY603DoEKKXkJWcP6CJo3nHFEdj_vcEbDQ-WESDpcQFa1fRIiGuALj-sEWcjGdSHyE8QATOcuW" +
                "l4TLVzRPKAf4tCXx1zyvhJbXQu0jf0yfzVpOhPun4n-xqK4SxPBCeuJOkQ2VG9jDXWH4pnjbAcrqjveJqVti7huM" +
                "XTLGuqU2uoihBw6mGqu_WSlOP2-XTEyRyvxbv2t-z9V6GPt1V9ceBukA0oGwtJqgD-q7NXFK8zhw7desI5PZMXf3" +
                "nuVgbJ3xdvAlzkmm5f9RoqQS6_hqwPQEcclq1MEZ3yML5hc99TDtZWy9gGkhR0Hs3QJxxgP7bEqGFP-HjTPnJsrG" +
                "aT6TjKP7qCxJlcFKLUr5AU_kxMULeUysWWtSGJ9mpxBvsyW1JuoEqofKIr6LBTeOscce8yCtdG4dO2KLp5uYWfdB" +
                "4IJUKjhVAvJdv1UpbDpUXjhydgq3NhfeSpYmLG9dnpi_kpLcKfj0Hb0omhR86doxE7XwuMAKYLHOHX6BnXpDHXyQ" +
                "6g5f",
            priv =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwPgIBAQQwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAABoAcGBSuBBAAi",
            jws =
                "eyJhbGciOiJNTC1EU0EtNjUtRVMzODQiLCJraWQiOiJxbW82ZFQ3cjZnSy1GUVE2dk5WbS0tSjJXeHlDM21oTnU0" +
                "VTh5bjB3MzV3In0.SXQncyBhIGRhbmdlcm91cyBidXNpbmVzcywgRnJvZG8sIGdvaW5nIG91dCB5b3VyIGRvb3Iu" +
                ".u0Byzk_aRTKhAk7az-Z9EvAfbzPb1yE9J5Ar3e5ho0AUo1-DTI4iXFHVVBhcrhpBcejVC4NAM-T7bcu0qSlUANH" +
                "xu6LDP2b3g42wwLRRJg7hgqtIoBeeKdTblppfaiupuKPRWBPvL1T0bUZ8gLLGlV2CqaFxTOdl6KW_fBlHZfdi-f5" +
                "NDZmjbtaYzZqfquVTyIYpIXaZ3qYSWELh3kyciu0dBkZ8JCe7v-0-zFaBMCDP-3Fnlwj3V0K-51T82K-FgRMMNLE" +
                "vmHoJVSVuh5Tkt_rFFs-LbA__CYhdM35ofjnEFWE9kM9_SbRFYTMRrCCO9oIQT7Gq_reO3MksxpbJV5yQC4mqgLk" +
                "DjBy8JTDElRwO8tR5vB8ikilBWIwgkKyRRXwa38wLdF2FjRgouNGY-oKl7HmJYuzfj1bYHc4uhyeDxSnNhVjjWHN" +
                "HRBRcxi9B9-ZRxSqhUZL_wDFoCRFaaOCCgEksJxic-dwE7SeVr4OucR7Z62lBpPbiuV1sGBixi5k_CY0n-EdpBB0" +
                "xD6RCcWmFcB9jVDnMMgIoI1OrGYb3smgjFlY0DpD1A8GYo6B4DzTgIBvWTCADtl0M1KSn2A5Wozg1jHXayAR43aG" +
                "9Qt4t-rBr911cxgNpi5i0LFgg8o9ItCZAhBwbVKFf5McLvp9TwF86KJPyl25-2V8_xA0BcY0OsExGKVaghTmnrjf" +
                "2kNs6uMjY7rKGPz-h_BneTvPD57D48nq-Qe5lqgMh1ctRBHqGy7mvcglFNbE55GSoWCJv4ufW5NsqVaXSXE41FnU" +
                "hospsWhVG2bR0EaZR-Ow1vPvsjAo4h7MYh9WCjim0ps50e9qcVWrJh5lVoG5u-tmFpv5UcnjUKGAB6bIvKWeb0Bo" +
                "OUZ1Gr1CPyJw2u3dvyuaqLZHXbOZ8y-55iXB9UFNS1iAY8hwjtrCogWqDZSOJ_IhAvTns7exGHgEDgYH4echCes0" +
                "IIwfY0NCaN3UrfYypr4YbF63sLPGxFvRpRP-_pEkqp8FwtpSj_k3DVcxZwFJCTIu5Q6sFDQC7d6nwRzH9OtCjw3v" +
                "OS8tk_d8rWqpzjFpksIqi9iLZ6NtVBdktNYDDjYIRRtHJRbHQLtdGE_qEgW3R98FkgZV6KBd3IDyAWV0j8qwvShF" +
                "U0dcFgZZIFmHVUvwhounUNTvLhTNWPLuFNl9PfGJjEdL62skbeawORk_KbwydfwS655nLZEULYns_R8y6cD9-BOq" +
                "B_cFsivQsSxa1_649d_9stA4TGxmMCRvRx8etq7g26xvqmBlbeU8UmL4exXqPlx0lWoTmsLW-flMITlEfiQjuJlO" +
                "udtvbwdR31dSJUD6Oa65NCZRLhED5m03OhIgg8jPEEu8wJwZecDSF8_fEs-zTKY6WjwhT_HOKenejlp-nQSgsr1r" +
                "aJs5isDLUfc53RT70zl5KP-LB8WsmJHN2FiTVOdhNeGPRlfqJOv5qgCrPpwWkaNs_6U7RJ2k20zUfvN1GdvtIg7G" +
                "TH227NffnOginWmHueU_1noZUhIza0TzCkRM_mIMPWynXGn1C33Lv4yW4XFTi0wuQfzUB2mzFOGzxlTyyMdOf7yN" +
                "mEJHJta87msTW6g7v-it8ioIzuhn21OzYrPF8edESIqXVeGK_l-iYJWp2p1lwBqwr6QBVW2g0GQF97SrOf8GitYj" +
                "BC9pZzyEYZm-2R9U0Vdc_oiR28O37xnqItaMjSsu7GRgj42k1BAX-azMvP_irqdYBtabsdAAnNaFFZD809KMN1H4" +
                "8i_rBb63hpkQMFEnehJSLny-4An_eF6mVVua94CTvqk1-UparoFTjCDHCSTFMlQz58SmPjoQ61iepLII48i4WxpB" +
                "5_iz73HHrjh-MqNsbGvUtiW86ZJOw-yranGbwG79yDfH4X9AQW5INqNcTQF2hO_ycbFbeUtUVETfObxiJIQB_p39" +
                "S9x58ySscXCpGcfUinx34SLsmUUv7hE0Wu-fQil6bpqRhGsnijDPg_XMhG9n9ZV5MSwTDaOY5CmvxuTrv-JLjqdS" +
                "WKDj01tD8bxcaGvFPO_cnv9W5c1Rya8JQZntZwDN3J8JlToL1Of1Af2vjyZ4RpOzIhSp_29ag7Rv7r5Vragg51yc" +
                "hjltidVe0dzXer9uyZag3WuKkQbMSNTWIJfihfn3dYkoVQxvyPVs7VnDc5m2LZvUFluzymcDPojoib3LKbWIH1BQ" +
                "HnQRuyacVeZFaPD7JWU5D5n8yBFYjbsKxnzmsDPFWCjMC1v7QZ7A2KJ--MdSc7YthVayuxDhWG9bX72jUMahtbLP" +
                "7AWA-XyqFF394uffkfphIr9nXTwpVt77Css_OqHI10JygNxBrutw1b1ykvUInDXO4F7ZU0K5DNDT8La_-blmh0Oq" +
                "90SQGMrbTPT3zhX4iSDt4_1KG9UwZ-5KxQCQNvGPaf6dqrJRnslD0hrnAvxD9qFeFZLdZeOAlcaq3EsJFWx2Verb" +
                "Hn4rjQ35ZmPlLOnyifkETCC-jToktpJH1uE0d8qM8mNS2kn2wJDlvHN0WoSLkT5Jmps5wqfwMAnF0ksZe9bxpEUi" +
                "8NVHZNt42YdLsjvyHEM4vqRqbks_DPFVe7qHpRplDA8ZR3gIr8DiE-alqSVw90FVMi2w59mOX-wQwe-aD82EdBci" +
                "GtLMbKp1ID1nZdQ4QaGzpSXCMDauYIWD0ZDrhEY7Dzn_kFId9pbBNtQK6WY4gnZB2qMyz94-sPQ3mS7r_8RY2VE3" +
                "QlfstgJYve1JdJOqUPZno_p-O73txgQxFjh_Uw1fhBi1-GhQSywqHnfIiUkq7aZVhlOFEyapWl-JDbTBXQxSwWBV" +
                "AqXCnq2pAdq9-u7tvFollLTQCyk7ORpd_lg_AYlBQJkwBm6L3Tl3vyQWaJkksPHtnK9sVQCsRCpD5hBHjyFj7dUR" +
                "qOQ-hVy4viB1Qs_7pKbSjKjNLXclxR5IUdh2LVa26rfOaivio2bZMavHqgNAG1OhF2oap-g7FKuFGR_BgX4UkGdU" +
                "72U5DzikcjT7dCug0XJvgmMUXBndkGEyXlXDY6MTHC8ooWXAiva22TorgV_TZ7uiXjZwsdrVAGDWv1AwFkl0wPs2" +
                "8LVdNLLkOmr-WuNisHMMhkzr8qDvNpkXaqTNDxOB6uHJQYO8Fv6_LxnxJIc3YzHlMN4b8KPGWaSIzU47HjrXHORz" +
                "di3S6QPagTPVQiWaTznEDv8EI8fC1IfmbedUj9x645aaCQP8ALwdR55RSnFnLhedrFg4jeMnFTyb89Z5E2b7oPeb" +
                "w9zuyU6hPeW7qUQU6-I4XTpOpV0JUgNcdx0DlkBaAukEgY5qKBruMbNw_80Dt5ygMMLqlPBuDp2Nqej1H3fctYjJ" +
                "f509ZcImmgjM7_qPx4ETUOSyYwOcnASpIrqKpT_1axY_g_bFlYcYWMK-o1w6VKKLVsFc-k_YFmdyU_NqhbirjiBl" +
                "znoY3ibmsnng_emysMnlrkIeIC-MIAk2pHmixprNXPGhDYUaMngvfo7HrkN3r4EEVSmSPQYNVWnD57V2GTJvZbUF" +
                "WJUYnXhg3IWZt2swLnxwONg-fTika9jQvuRWEUblgwEE25DqT77N54rlJkj5Gb8kyZ3PqXSzSPbgC02H4fDkGYsW" +
                "eFgNOp7ixGAsXdajHY_dYqBK9eQogLq-yUE2Oo4jE3brjsa4vMJ8G2aQ9FbRSFIhJYtW10CW5dCHXhuDaRlAAwV9" +
                "OOBGXtRvuoIT0dCnONDjYMx3bfxz93HbIfTCy7JM62MSx7NQALUI1lUuZosQiRgNAYWn5W3jcFgwx9kbWv33Nn7X" +
                "8kN0wGSgFCU2NoepU6i0ymiy5wCVNikk8cTQSeiy3K13qhYcrvKKgbA04EwyI-EsOtvWrGg_TRLMbViBejbri1BK" +
                "oYQdlucZbIHA_MI4FfNJpso1mZjsHHpAFWUfsm6mVqNvHhiav0bCPj7Dii4O4mYXFnLLP1iB4-UXUxrLu2CDDknN" +
                "se1e21BnVJvx5XhT-yHNsZ62bOlz4e6fjz_iqoUiIdDv2jOVMrepvtgPs6sHfCgbP1OdiOx9Tbntf1HkM3sM4f2R" +
                "M4TDyDoQ_BLdSq871jfz_yVq7mJ-4TLGU70VDT6-ut98esFh8K7bF2fKq0xhJhg3hEcQOJ9dNe0Sfy29o8-Lhjs_" +
                "fdA3Lw8rr7Y-08qTCq6mBqXcq0yLEpTU49EOAW6yQSmZ7CVaBPvEAX7Rq1Nha3FrvApCZwAInAs_m6diY4ni95ew" +
                "0HeqwN1_3aURsioPaLW72xmfCka_jDMWmD9hQ0Y8Lk9l7zi61g7bjnjXFISENjhmWONVSCLoMLIzkGgUsnz2Ihex" +
                "iL4i_Z-4Nn9_t93qgX6UJc36JqLMMPAQPhZixy9DV2gZAVQs35x8wMUKpue4AAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAABggRFBceMGYCMQC9GzNpKgVRu7M0TKuYKFZnqSrVpOvvROneXULFZ5MAJTGlIox46PRZ7NBKmJt4oWkCMQD" +
                "WC3a3R6Z8dxlyq4sNj58QPqmmynUsPRigH-9oBzX8XdCUC7JbXZgCxeGpvDH_RUk",
            rawCompositePubHex =
                "424b2f267e58d5b3b44d71acfc6a656bb26950d57c61db1c880bcfa1feab443f0942ab8bdbad7d708abbc356" +
                "078f6d99a252271fe62c74091eb94afb9b9264c50a888e0dfed80cd5fb2cbd3667e60d539ebe44930219cd4f" +
                "aed15dbb3455a264802b9f49bce42ee7550feffdd4642a55ade693868a460cbec03f4fc99a4e30bccffa8a47" +
                "5e5395396674ebb81a94937587880f6dbd27bf1c4f5a9ee43cdd8b0e53b3b7fb49c73adfbc2d4f8c54303520" +
                "c29bf97e26ee57db342d957c893936522d0942b41d82ee3772a00570adfb545c1143922b0496f826a0a97006" +
                "4b36ddf534b5f8e1c1cd0b5565ea846b45431f0618143ece89777bb3f61179ad20295fe0a6e062ae6eecbc2e" +
                "f38f2ac1a22dc93b7b126336223c55b61eb8c0795542bbb2dc65e722eadc6866ffa9683beb8a999ad7a83e5e" +
                "6e016c2e4c35f6f7649ad3bd52ec67ec1c5c6e7b9972771218be9554bba7727f0b84c44b9b0a8bd831fcff2c" +
                "9779ccd4ca30c6ad75b04983e41de893ee5f39ea7355180b709c7045c22d33a083f6ae07a114746d1bfdccbe" +
                "e5b9043879bb5a2e120e2a4636283f4a1cd4924a2de6a4aa3d99ddd88f48aaa4e88bfd1ea769d82c10779f2d" +
                "ed796db542971ca289b76863ede5997b7e9ce183b43ccec278b10d92b87442ce0435bb1625171db5554b4702" +
                "39c50d2a0c3a41b2a38807db070b47bfb3e7d10f3cd979d69963c8d79f8029cc4a48eb04fcb3d708844febaa" +
                "8b6ddff01ab64d59358e6505c4ec1d7cbb14ed2212df458ecefc03fe03037b1505a4c9444322f5f98dfa91a4" +
                "cb8c45860a2dadc7515350bb6d431e49a6bc8f5ba956e682b0e513321a97d1962602891c9078f62a8a9646a3" +
                "1387a6f09684264837899e0d8ec7d11c565901298b20b345081690eb4c562c1aa3a25bef06566cb34c79bc0b" +
                "25e4095d6ba793e81311e41a3329152686f00d4897f84fc4edf4b26d545365785ead8d63aef64a87c0b91a2e" +
                "5500383956cdf5f6e37cf9d5482d1c8e3a5be38f17259ac45c9fa1c4bd3bf177d312ee52a6da023c05722a87" +
                "38274dda8d1b04e99831cf57c87282a256c565c296d0524a063a3a41a48a83009978d98d8abf61af68e8013b" +
                "594fe151d9bec199902c4c70b49584201743c6b53103d2fd24bdf078dc90b5a188b4f8d772179988d0416c94" +
                "d4c57c0860b9d7b53d4cd261f332a1851565d52ac37f008747cafe320f363d9beb6e4117db43fd8aeebe5e0c" +
                "e2f54e3f0367eb3cc971bbe0c301a8e52f96094936035c6ee3ca2d13db483a0dd04dc16247de0e0894ad7cb7" +
                "e1ae7ebd4f8f900582b20021e77f70254501c6ac3dd15d43bbb7931c5283244312158c2eb1b3e1117e194f0a" +
                "1e4c783efbc62c9f81c21562d0d34a5f042b5eaaf32f31f95c5b055f4e7a2070fb096f56c415549cde74f386" +
                "4e8b9fc27e3299724b4639986044b55928fd6972785b280c25a3e21aab814ecbfb0c3cbec0914907ec907f25" +
                "a1d88bce3d319ae8222a35945db62af7cc75cd29c1f5d98fcb93f750dc3031076979bb51dfc37d23e8eea780" +
                "73a24d3e26c68e7bb10e459f2577b90080359ae0aec10318dcd9e0f9e34029c31b3e54b1855645db42061878" +
                "3346dad5b55eddb4f977b326a655525ebe2195eca9cec38a3c0d2273b77d3e68f1901c2ca5149734a51177bc" +
                "b089476b18cba09fa8b9b46d94a2946f358e1decb1998652c58a90852423e2c85e79d19724461627e6390d1a" +
                "81fb1a72f9c7edc4bd747dd5c85217b5856141028414ddbe71458f0a0b2b589df2e1b051783b8f718676b1de" +
                "fbae98ba496c2a935e92eeadea0a8393ef59f9e914f0743fe65640ddf9981cea6dbdd957a534ad4e790efc97" +
                "4ee89938ad99d53c5b680775399326834729bb37b082e795f8d87f52e6c8a8db68e515c277bbea82a7570d42" +
                "80896c987a0608903e306c632a223c55f0ea3682039c4a3f5440f4b5ac3e6ed2b2dc900cecc72b72f50e49b2" +
                "629ad30f0487b2707b86286f8c4f55659b25f9bdd7a6af460cc3c57a3982663bb717461581e196894929d841" +
                "53d87a7f482d284b5b894ce1a78216b2a011f2b88742cee52d5133e8fe77edae242f5af91637c37ffca32430" +
                "509b2fe4756303a9a3659fe32528af1e10d8d43bea991b2d109786cc66d35b1d78df254b92cdaa40f91a987e" +
                "4a922ca81050e5bc3530ca85493bdf2a825374d0a8310a6860284ec3ec732326eeeffc42bbd42bc91b73e5e7" +
                "c6b599d016490637629f3876c3e42f8db590e66a85a7838c818f78fffb4853cbef09434989803545dca87657" +
                "cf7c7e7e6afa71382bc10fa0bb6480f243eea1b861101006fa0cff3275621943cc58eb4dc3a0428a5e425670" +
                "fe82268de71c511d8ffbdc11b0d0f961120e971015ad5f448886b802e3fac11672319d487c84f1001339cb96" +
                "9784cb57344f2807f8b425f1d73caf8496d742ed237f4c9fcd5a4e84fba7e27fb1a8ae12c4f0427ae24e910d" +
                "951bd8c35d61f8a678db01caea8ef789a95b62ee1b8c5d32c6baa536ba88a1070ea61aabbf59294e3f6f974c" +
                "4c91cafc5bbf6b7ecfd57a18fb7557d71e06e900d281b0b49aa00feabb35714af33870edd7ac2393d93177f7" +
                "9ee5606c9df176f025ce49a6e5ff51a2a412ebf86ac0f40471c96ad4c119df230be6173df530ed656cbd8069" +
                "214741ecdd0271c603fb6c4a8614ff878d33e726cac6693e938ca3fba82c4995c14a2d4af9014fe4c4c50b79" +
                "4cac596b52189f66a7106fb325b526ea04aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741" +
                "e082542a385502f25dbf55296c3a545e3872760ab73617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a14" +
                "7ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f",
            rawCompositeSigHex =
                "bb4072ce4fda4532a1024edacfe67d12f01f6f33dbd7213d27902bddee61a34014a35f834c8e225c51d55418" +
                "5cae1a4171e8d50b834033e4fb6dcbb4a9295400d1f1bba2c33f66f7838db0c0b451260ee182ab48a0179e29" +
                "d4db969a5f6a2ba9b8a3d15813ef2f54f46d467c80b2c6955d82a9a1714ce765e8a5bf7c194765f762f9fe4d" +
                "0d99a36ed698cd9a9faae553c88629217699dea6125842e1de4c9c8aed1d06467c2427bbbfed3ecc56813020" +
                "cffb71679708f75742bee754fcd8af8581130c34b12f987a0955256e8794e4b7fac516cf8b6c0fff09885d33" +
                "7e687e39c415613d90cf7f49b445613311ac208ef682104fb1aafeb78edcc92cc696c9579c900b89aa80b903" +
                "8c1cbc2530c4951c0ef2d479bc1f22922941588c2090ac91457c1adfcc0b745d858d1828b8d198fa82a5ec79" +
                "8962ecdf8f56d81dce2e872783c529cd8558e358734744145cc62f41f7e651c52aa15192ffc0316809115a68" +
                "e08280492c27189cf9dc04ed2795af83ae711ed9eb6941a4f6e2b95d6c1818b18b993f098d27f84769041d31" +
                "0fa442716985701f635439cc3202282353ab1986f7b268231656340e90f503c198a3a0780f34e0201bd64c20" +
                "03b65d0cd4a4a7d80e56a338358c75dac80478dda1bd42de2dfab06bf75d5cc603698b98b42c5820f28f48b4" +
                "2640841c1b54a15fe4c70bbe9f53c05f3a2893f2976e7ed95f3fc40d01718d0eb04c462956a08539a7ae37f6" +
                "90db3ab8c8d8eeb2863f3fa1fc19de4ef3c3e7b0f8f27abe41ee65aa0321d5cb51047a86cbb9af72094535b1" +
                "39e464a858226fe2e7d6e4db2a55a5d25c4e35167521a2ca6c5a1546d9b47411a651f8ec35bcfbec8c0a3887" +
                "b31887d5828e29b4a6ce747bda9c556ac9879955a06e6efad985a6fe547278d4286001e9b22f29679bd01a0e" +
                "519d46af508fc89c36bb776fcae6aa2d91d76ce67ccbee7989707d505352d62018f21c23b6b0a8816a836523" +
                "89fc8840bd39ecedec461e01038181f879c8427acd082307d8d0d09a37752b7d8ca9af861b17adec2cf1b116" +
                "f46944ffbfa4492aa7c170b694a3fe4dc355cc59c052424c8bb943ab050d00bb77a9f04731fd3ad0a3c37bce" +
                "4bcb64fddf2b5aaa738c5a64b08aa2f622d9e8db5505d92d3580c38d821146d1c945b1d02ed74613fa84816d" +
                "d1f7c16481957a281777203c80595d23f2ac2f4a1154d1d7058196481661d552fc21a2e9d4353bcb8533563c" +
                "bb85365f4f7c626311d2fadac91b79ac0e464fca6f0c9d7f04bae799cb64450b627b3f47ccba703f7e04ea81" +
                "fdc16c8af42c4b16b5ffae3d77ff6cb40e131b198c091bd1c7c7adabb836eb1bea98195b794f1498be1ec57a" +
                "8f971d255a84e6b0b5be7e53084e511f8908ee2653ae76dbdbc1d477d5d489503e8e6bae4d09944b8440f99b" +
                "4dce848820f233c412ef3027065e703485f3f7c4b3ecd3298e968f0853fc738a7a77a3969fa741282caf5ada" +
                "26ce62b032d47dce77453ef4ce5e4a3fe2c1f16b262473761624d539d84d7863d195fa893afe6a802acfa705" +
                "a468db3fe94ed1276936d3351fbcdd4676fb4883b1931f6dbb35f7e73a08a75a61ee794ff59e8654848cdad1" +
                "3cc291133f98830f5b29d71a7d42df72efe325b85c54e2d30b907f3501da6cc5386cf1953cb231d39fef2366" +
                "1091c9b5af3b9ac4d6ea0eeffa2b7c8a8233ba19f6d4ecd8acf17c79d11222a5d57862bf97e898256a76a759" +
                "7006ac2be900555b683419017ded2ace7fc1a2b588c10bda59cf2118666fb647d53455d73fa22476f0edfbc6" +
                "7a88b5a3234acbbb191823e369350405fe6b332f3ff8aba9d601b5a6ec74002735a145643f34f4a30dd47e3c" +
                "8bfac16fade1a6440c1449de84948b9f2fb8027fde17a99556e6bde024efaa4d7e5296aba054e30831c24931" +
                "4c950cf9f1298f8e843ad627a92c8238f22e16c69079fe2cfbdc71eb8e1f8ca8db1b1af52d896f3a6493b0fb" +
                "2ada9c66f01bbf720df1f85fd0105b920da8d713405da13bfc9c6c56de52d5151137ce6f188921007fa77f52" +
                "f71e7cc92b1c5c2a4671f5229f1df848bb26514bfb844d16bbe7d08a5e9ba6a4611ac9e28c33e0fd73211bd9" +
                "fd655e4c4b04c368e6390a6bf1b93aeff892e3a9d4962838f4d6d0fc6f171a1af14f3bf727bfd5b97354726b" +
                "c250667b59c0337727c2654e82f539fd407f6be3c99e11a4ecc8852a7fdbd6a0ed1bfbaf956b6a0839d72721" +
                "8e5b627557b47735deafdbb265a8375ae2a441b31235358825f8a17e7ddd624a15431bf23d5b3b5670dce66d" +
                "8b66f50596ecf299c0cfa23a226f72ca6d6207d414079d046ec9a71579915a3c3ec9594e43e67f320456236e" +
                "c2b19f39ac0cf1560a3302d6fed067b036289fbe31d49ced8b6155acaec438561bd6d7ef68d431a86d6cb3fb" +
                "01603e5f2a85177f78b9f7e47e9848afd9d74f0a55b7bec2b2cfcea87235d09ca037106bbadc356f5ca4bd42" +
                "270d73b817b654d0ae433434fc2daffe6e59a1d0eabdd1240632b6d33d3df3857e22483b78ff5286f54c19fb" +
                "92b140240dbc63da7fa76aac9467b250f486b9c0bf10fda8578564b75978e02571aab712c2455b1d957ab6c7" +
                "9f8ae3437e5998f94b3a7ca27e4113082fa34e892da491f5b84d1df2a33c98d4b6927db024396f1cdd16a122" +
                "e44f9266a6ce70a9fc0c02717492c65ef5bc691148bc3551d936de3661d2ec8efc8710ce2fa91a9b92cfc33c" +
                "555eeea1e946994303c651de022bf03884f9a96a495c3dd0554c8b6c39f66397fb04307be683f3611d05c886" +
                "b4b31b2a9d480f59d9750e10686ce949708c0dab982160f4643ae1118ec3ce7fe414877da5b04db502ba598e" +
                "209d9076a8ccb3f78fac3d0de64bbafff11636544dd095fb2d80962f7b525d24ea943d99e8fe9f8eef7b7181" +
                "0c458e1fd4c357e1062d7e1a1412cb0a879df222524abb69956194e144c9aa5697e2436d30574314b0581540" +
                "a970a7ab6a4076af7ebbbb6f1689652d3402ca4ece46977f960fc0625050264c019ba2f74e5defc9059a2649" +
                "2c3c7b672bdb15402b110a90f98411e3c858fb75446a390fa1572e2f881d50b3fee929b4a32a334b5dc97147" +
                "9214761d8b55adbaadf39a8af8a8d9b64c6af1ea80d006d4e845da86a9fa0ec52ae14647f0605f852419d53b" +
                "d94e43ce291c8d3edd0ae8345c9be098c517067764184c979570d8e8c4c70bca28597022bdadb64e8ae057f4" +
                "d9eee8978d9c2c76b5401835afd40c05925d303ecdbc2d574d2cb90e9abf96b8d8ac1cc321933afca83bcda6" +
                "45daa93343c4e07ab8725060ef05bfafcbc67c4921cdd8cc794c3786fc28f196692233538ec78eb5c7391cdd" +
                "8b74ba40f6a04cf550896693ce7103bfc108f1f0b521f99b79d523f71eb8e5a68240ff002f0751e794529c59" +
                "cb85e76b160e2378c9c54f26fcf59e44d9bee83de6f0f73bb253a84f796eea51053af88e174e93a957425480" +
                "d71dc740e5901680ba4120639a8a06bb8c6cdc3ff340ede7280c30baa53c1b83a7636a7a3d47ddf72d62325f" +
                "e74f597089a682333bfea3f1e044d4392c98c0e727012a48aea2a94ffd5ac58fe0fdb16561c61630afa8d70e" +
                "9528a2d5b0573e93f60599dc94fcdaa16e2ae38819739e863789b9ac9e783f7a6cac32796b9087880be30802" +
                "4da91e68b1a6b3573c684361468c9e0bdfa3b1eb90ddebe041154a648f4183555a70f9ed5d864c9bd96d4156" +
                "2546275e183721666ddacc0b9f1c0e360f9f4e291af6342fb9158451b960c04136e43a93efb379e2b949923e" +
                "466fc9326773ea5d2cd23db802d361f87c390662c59e16034ea7b8b1180b1775a8c763f758a812bd790a202e" +
                "afb2504d8ea388c4ddbae3b1ae2f309f06d9a43d15b45214884962d5b5d025b97421d786e0da465000c15f4e" +
                "381197b51beea084f47429ce3438d8331ddb7f1cfddc76c87d30b2ec933ad8c4b1ecd4002d4235954b99a2c4" +
                "224603406169f95b78dc160c31f646d6bf7dcd9fb5fc90dd30192805094d8da1ea54ea2d329a2cb9c0254d8a" +
                "493c7134127a2cb72b5dea85872bbca2a06c0d38130c88f84b0eb6f5ab1a0fd344b31b56205e8dbae2d412a8" +
                "610765b9c65b20703f308e057cd269b28d66663b071e90055947ec9ba995a8dbc78626afd1b08f8fb0e28b83" +
                "b89985c59cb2cfd62078f945d4c6b2eed820c392736c7b57b6d419d526fc795e14fec8736c67ad9b3a5cf87b" +
                "a7e3cff8aaa14888743bf68ce54cadea6fb603eceac1df0a06cfd4e7623b1f536e7b5fd4790cdec3387f644c" +
                "e130f20e843f04b752abcef58dfcffc95abb989fb84cb194ef45434fafaeb7df1eb0587c2bb6c5d9f2aad318" +
                "49860de111c40e27d74d7b449fcb6f68f3e2e18ecfdf740dcbc3caebed8fb4f2a4c2aba981a9772ad322c4a5" +
                "3538f443805bac904a667b0956813ef1005fb46ad4d85adc5aef029099c0022702cfe6e9d898e278bde5ec34" +
                "1deab0375ff769446c8a83da2d6ef6c667c291afe30cc5a60fd850d18f0b93d97bce2eb583b6e39e35c52121" +
                "0d8e199638d55208ba0c2c8ce41a052c9f3d8885ec622f88bf67ee0d9fdfedf77aa05fa509737e89a8b30c3c" +
                "040f8598b1cbd0d5da0640550b37e71f303142a9b9ee00000000000000000000000000000000000000000000" +
                "00000006081114171e3066023100bd1b33692a0551bbb3344cab98285667a92ad5a4ebef44e9de5d42c56793" +
                "002531a5228c78e8f459ecd04a989b78a169023100d60b76b747a67c771972ab8b0d8f9f103ea9a6ca752c3d" +
                "18a01fef680735fc5dd0940bb25b5d9802c5e1a9bc31ff4549",
            rawToBeSignedHex =
                "65794a68624763694f694a4e54433145553045744e6a557452564d7a4f4451694c434a72615751694f694a78" +
                "625738325a465133636a5a6e5379314755564532646b355762533074536a4a5865486c444d32316f546e5530" +
                "56546835626a42334d7a5633496e302e5358516e6379426849475268626d646c636d39316379426964584e70" +
                "626d567a63797767526e4a765a473873494764766157356e4947393164434235623356794947527662334975",
            rawMessageRepresentativeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4136352d45434453412d503338342d534841353132003152f0f3015a53da1d13a562034a375f3f0137ddec8e" +
                "160f319c3cb22d930665cbdc8329b71e7aedc3c123527f540734a3c1228aec72ed0ed261a60802bf8328",
        ),
        JoseVector(
            name = "Figure 5: ML-DSA-87-ES384",
            algorithm = Algorithm.ML_DSA_87_ES384,
            kid = "yH7Hzh-v4jQOca8n6vLtFOG31o0pT2MDZD5FtvQ7Reg",
            joseAlg = "ML-DSA-87-ES384",
            pub =
                "5F_8jMc9uIXcZi5ioYzY44AylxF_pWWIFKmFtf8dt7Roz8gruSnx2Gt37RT1rhamU2h3LOUZEkEBBeBFaXWukf22" +
                "Q7US8STV5gvWi4x-Mf4Bx7DcZa5HBQHMVlpuHfz8_RJWVDPEr-3VEYIeLpYQxFJ14oNt7jXO1p1--mcv0eQxi-9e" +
                "tuiX6LRRqiAt7QQrKq73envj9pkUbaIpqL2z_6SWRFln51IXv7yQSPmVZEPYcx-DPrMN4Q2slv_-fPZeoERcPjHo" +
                "YB4TO-ahAHZP4xluJncmRB8xdR-_mm9YgGRPTnJ15X3isPEF5NsFXVDdHJyTT931NbjeKLDHTARJ8iLNLtC7j7x3" +
                "XM7oyUBmW0D3EvT34AdQ6eHkzZz_JdGUXD6bylPM1PEu7nWBhW69aPJoRZVuPnvrdh8P51vdMb_i-gGBEzl7OHvV" +
                "nWKmi4r3-iRauTLmn3eOLO79ITBPu4CZ6hPY6lfBgTGXovda4lEHW1Ha04-FNmnp1fmKNlUJiUGZOhWUhg-6cf5T" +
                "DuXCn1jyl4r2iMy3Wlg4o1nBEumOJahYOsjawfhh_Vjir7pd5aUuAgkE9bQrwIdONb788-YRloR2jzbgCPBHEhd8" +
                "6-YnYHOB5W6q7hYcFym43lHb3kdNSMxoJJ6icWK4eZPmDITtbMZCPLNnbZ61CyyrWjoEnvExOB1iP6b7y8nbHnzA" +
                "JeoEGLna0sxszU6V-izsJP7spwMYp1Fxa3IT9j7b9lpjM4NX-Dj5TsBxgiwkhRJIiFEHs9HE6SRnjHYU6hrwOBBG" +
                "GfKuNylAvs-mninLtf9sPiCke-Sk90usNMEzwApqcGrMxv_T2OT71pqZcE4Sg8hQ2MWNHldTzZWHuDxMNGy5pYE3" +
                "IT7BCDTGat_iu1xQGo7y7K3Rtnej3xpt64br8HIsT1Aw4g-QGN1bb8U-6iT9kre1tAJf6umW0-SP1MZQ2C261-r5" +
                "NmOWmFEvJiU9LvaEfIUY6FZcyaVJXG__V83nMjiCxUp9tHCrLa-P_Sv3lPp8aS2ef71TLuzB14gOLKCzIWEovii0" +
                "qfHRUfrJeAiwvZi3tDphKprIZYEr_qxvR0YCd4QLUqOwh_kWynztwPdo6ivRnqIRVfhLSgTEAArSrgWHFU1WC8Ck" +
                "d6T5MpqJhN0x6x8qBePZGHAdYwz8qa9h7wiNLFWBrLRj5DmQLl1CVxnpVrjW33MFso4P8n060N4ghdKSSZsZozkN" +
                "Q5b7O6yajYy-rSp6QpD8msb8oEX5imFKRaOcviQ2D4TRT45HJxKs63Tb9FtT1JoORzfkdv_E1bL3zSR6oYbTt2St" +
                "npz-7kVqc8KR2N45EkFKxDkRw3IXOte0cq81xoU87S_ntf4KiVZaszuqb2XN2SgxnXBl4EDnpehPmqkD92SAlLrQ" +
                "cTaxaSe47G28K-8MwoVt4eeVkj4UEsSfJN7rbCH2yKl2XJx5huDaS0xn2ODQyNRmgk-5I9hXMUiZDNLvEzx4zuyr" +
                "cu2d0oXFo3ZoUtVFNCB__TQCf2x27ej9GjLXLDAEi7qnl9Xfb94n0IfeVyGte3-j6NP3DWv8OrLiUjNTaLv6Fay1" +
                "yzfUaU6LI86-Jd6ckloiGhg7kE0_hd-ZKakZxU1vh0Vzc6DW7MFAPky75iCZlDXoBpZjTNGo5HR-mCW_ozblu60U" +
                "9zZA8bn-voANuu_hYwxh-uY1sHTFZOqp2xicnnMChz_GTm1Je8XCkICYegeiHUryEHA6T6B_L9gW8S_R4ptMD0Sv" +
                "6b1KHqqKeubwKltCWPUsr2En9iYypnz06DEL5Wp8KMhrLid2AMPpLI0j1CWGJExXHpBWjfIC8vbYH4YKVl-euRo8" +
                "eDcuKosb5hxUGM9Jvy1siVXUpIKpkZt2YLP5pEBP_EVOoHPh5LJomrLMpORr1wBKbEkfom7npX1g817bK4IeYmZE" +
                "LI8zXUUtUkx3LgNTckwjx90Vt6oVXpFEICIUDF_LAVMUftzz6JUvbwOZo8iAZqcnVslAmRXeY_ZPp5eEHFfHlsb8" +
                "VQ73Rd_p8XlFf5R1WuWiUGp2TzJ-VQvj3BTdQfOwSxR9RUk4xjqNabLqTFcQ7As246bHJXH6XVnd4DbEIDPfNa8F" +
                "aWb_DNEgQAiXGqa6n7l7aFq5_6Kp0XeBBM0sOzJt4fy8JC6U0DEcMnWxKFDtMM7q06LubQYFCEEdQ5b1Qh2LbQZ8" +
                "98tegmeF--EZ4F4hvYebZPV8sM0ZcsKBXyCr585qs00PRxr0S6rReekGRBIvXzMojmid3dxc6DPpdV3x5zxlxaIB" +
                "xO3i_6axknSSdxnS04_bemWqQ3CLf6mpSqfTIQJT1407GB4QINAAC9Ch3AXUR_n1jr64TGWzbIr8uDcnoVCJlOgm" +
                "lXpmOwubigAzJattbWRi7k4QYBnA3_4QMjt73n2Co4-F_Qh4boYLpmwWG2SwcIw2PeXGr2LY2zwkPR4bcSyx1Z6U" +
                "K5trQpWlpQCxgsvV_RvGzpN22RtHoihPH74K0cBIzCz7tK-jqeuWl1A7af7KmQ66fpRBr5ykTLOsa17WblkcIB_j" +
                "DvqKfEcdxhPWJUwmOo4TIQS-xH8arLOy_NQFG2m14_yxwUemXC-QxLUYi6_FIcqwPBKjCdpQtadRdyftQSKO0SP-" +
                "GxUvamMZzWI780rXuOBkq5kyYLy9QF9bf_-bL6QLpe1WMCQlOeXZaCPoncgYoT0WZ17jB52Xb2lPWsyXYK54npsz" +
                "kbKJ4OIqfvF8xqRXcVe22VwJuqT9Uy4-4KKQgQ7TXla7Gdm2H7mKl8YXQlsGCT2Ypc8O4t0Sfw7qYAuaDGf752Hb" +
                "m3fl1bupcB2huIPlIaDP6IRR9XvTYIW2flbwYfhKLmoVKnG85uUi2qtqCjPOIuU3-peT0othfmwKQXaoOqO-V4r6" +
                "wPL1VHxVFtIYmEdVt0RccUOvpOVR_OAHG9uHOzTmueK5557Qxp0ojtZCHyN-hgoMZJLrvdKkTCxPNo2-mZQbHoVh" +
                "2FnThZ9JbO49dB8lKXP4_MU5xAnjXMgKXtbfI8w6ZWATE_XWgf2VQMUpGp4wpy44yWQTxHxh_4T9540BGwG0FU0b" +
                "kgrwA_erseGZnepqdmz5_ScCs84O5Xr5MbYhJLCGGxY6O5GqS-ooB2w0Mt87KbbE4bpYje9CAHH8FX3pDrJyLsya" +
                "sA3zxmk4OmGpG7Z70ofONJtHRe56R5287vFmuazEEutXn81kNzB-3aJT1ga3vnWZw4CSvFKoWYSA7auLgrHSHFZd" +
                "ITfOrgtmQmGbFhM9kSBdY1UCnpzf65oos3PZWRa2twfUxxLAnPNtrxpRGyvtsapw7ljUagZmuyh3hLCjhAxYmnoE" +
                "1dbyIWvpCqSlEtVjL1yb_nuLEzgvmZuV02fHxGuWgHTOMVGXpf81Rce3eoBK3lapW1wkzezlk3tcA2bZOtA9qbxd" +
                "sbVR37kemzQ9K1e3Y0OWhtSjBKqHyiK-iwU3jrHHHvMgrXRuHTtii6ebmFn3QeCCVCo4VQLyXb9VKWw6VF44cnYK" +
                "tzYX3kqWJixvXZ6Yv5KS3Cn49B29KJoUfOnaMRO18LjACmCxzh1-gZ16Qx18kOoOXw",
            priv =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwPgIBAQQwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAABoAcGBSuBBAAi",
            jws =
                "eyJhbGciOiJNTC1EU0EtODctRVMzODQiLCJraWQiOiJ5SDdIemgtdjRqUU9jYThuNnZMdEZPRzMxbzBwVDJNRFpE" +
                "NUZ0dlE3UmVnIn0.SXQncyBhIGRhbmdlcm91cyBidXNpbmVzcywgRnJvZG8sIGdvaW5nIG91dCB5b3VyIGRvb3Iu" +
                ".Gr-m2mj44blFE8GI2xxWdoJ3_AwG4ysPNVVh0ganXkQDpvSiPubgG07DOVB0Nk4g-vasqvTbuAvknKvQaeSMwR0" +
                "D6o3noXBtQT2oR-XjWKe30pgMzPQLf-NkQqKGZv3AoJ4GqUVx22ZWn3E0Mzb6XvNrRTzf7GC0tJTb786wx-dYRjJ" +
                "d0axkjTxD_xV8JZ-lMmUia2ATU3fIv2LBX0YWDOmSF31h86dh3g8Q3dbY7XDnwf_cWlGtfDfqDB0NDlsj_rqJ-rh" +
                "d7iH-0gtrrnEkHt5X_qK_0XgS-ro6eXvXpW7WMYcUbAMC0dfz6z98LaPwJ1_lxoYFHEAgAZvm4_M-fqgpgMbM_qg" +
                "GzqnZFoOZC_E2hKpL7wpn-C5NFamV3M-WCSvSuX8txLlK7gf2RS7zjdnpX1QYQhXHJuydua-51ekczClU2Y3wab_" +
                "GnXGP5NWnzDrcpIuZnCBqB_o2EY_SCQ_Pdup9QTTdNVvTM-kqIN-0DzCNYU8KHmZ3N_y1M9rZBZNjpPhYUSYqE2V" +
                "WTWFhxL7Gr12XshR1pZs_rxpQtCkwSoA7CY8eQ6NtDUAWIO87QeAtHNfpf0L2H_i85mZItDirJUHnBKkbvGl10qi" +
                "CbqitjIfQLdBH2XawWDzz2J94uNztAbsp9GfYeALOD7uMiAd47GTiXV26F33-vdD-FmZoY6VJMCb7huk3AKhwbtO" +
                "XkCP4BVmlGDYGaIXBUeQ4EenylcvBAXixejfken1r-0XQ6TNWi_F14v7v_HMKPz0n_S38oNMIc7SfIEoVLQpwhg2" +
                "7EVwvHv94vPBmKNC8WGhtQTeiimIouGp9mFF4ASlh2Vq6c0RX3rKnMjFtwQg-TPfK_rHSvIogm6ZZP8tKf_qfpvm" +
                "C6tPSA8M-sNKSz-16clacLOrLLPafq7Daxh_vNGpVm3xnFl5wKosOzJNARGbE0hNdjpxxKyTuOFm8iN1yUDOYwyQ" +
                "dVziY_5ym4UGoUe4UbeMrQQB_foumtu-D4fzmDMR4hrbAFDrxfUckHcBHJwAHSA13YtxY6UmOtN8mkOaC4XYUdOK" +
                "_DnkcOSW-T_SMiPbFf245GtaztWMscWr4W_WOz4qY99HE3sVEcEw-Vu70VDy3dw3u1cKHC68koKQ-8l-6YHxpUkf" +
                "JCa1f0vkD2gLMERWwMCZw7878TMxo0Q2SAclyfvNavTUbMgoeNxEnEBbV_xd75FV9flUX6NxZtA8XCL97iKmx9yO" +
                "ISh9wbsF7CGgjDP7r_4z0vTTk_2zONWeQ7dY9ra39ldrmNAmgN0YOLil8b2Cd1STS_4VQbFGTsTIEstGTF2maKjH" +
                "e_hRK58_eECvEPub2VyMSCvffBxDUWQIJfxosGofP_MejtSfx4Nk0MoLjiI23wz4V0K_j12Olx0VSnoqFY8nntoc" +
                "JLbT-kwWjkksKXd8Soq3uYk-r-gjHilxuGauA6CoIG6vHLv3ymgVdIuJvKEcLjXvYaYDVgjWStwbaqub6jCv1KHc" +
                "ygnmwwU3_fcs63u_OhtH3miLGh_qBSRcsHmL5he8142tGECHJa0rKMH1ICcUSz02I6m_VdfHqOV6IJBpzelgA-16" +
                "arD16a5KDjhv3ndODnU21JLIm8QMTnqmSiWwfif6dz9UVrXoZ7Us0DAmlBoX70vPQmzQ7nzTlRhhSlO5BO8u_8wB" +
                "7OOlcankVoA3mZyf-vo2ZEmkSDrlpD_EiGKrQs98hUM2yjYV1VKPkkW-Sb9e_6NgFHM6JutpbGA53r79WL0Qfg9M" +
                "0xXLSaxgc7Xmdw9beWX4RUyFBGGXeCQEmpehk5OEj9usFZUhIsbkTjMzIjkT_BQsQYwpX55AhleVmORkNcpVX8Yo" +
                "bu6-JTslTxafiooHiO02WVTMoGSMBF8wCftUy_t2AzDbKkQoYclrbkI-uZaz_3XvbIVKg0ErPzvV1aLMFgTTDvS0" +
                "7lc9MVV11aixngaqaZxVsIxPzgAOGbhP2rf4KWUwvgphfaYondjSGwKgbwlRZIXQHI2o-6bXr-g3YLgfFarJkZFG" +
                "gb8VWi3nBM_3Ng3QI7NUOcnx6gaAR7aZHjI5hNv-nUknUU2yzpGAgIpJHZubv__5YL2CB877x6AAWiJF47u3zC9J" +
                "YXV9POGKuQbhnT_Nfi__Ri20aZFMalkqCeqaCkvYpm-Bz-A55NWhfZsjXnkcxSTRsTglDwa0lr24TO2gwrShqn9b" +
                "WiH0NfuCXxEJZFwdW0Cj3IgyfGZIjKdf54X0Hdp1Q3Viy8-EsGxIWHM1-Vc27lp9W4MfSlESsa_bYcAmWOdVwkLb" +
                "tXeaySC7a7uVS-92f6Z6_Vj0lVq3FQAhmHJAlMYJtvPGWKvzlCT1RmCgQl5pBOLQDB_ZxGQQYPWjSBALvBsVeG75" +
                "UgrVnToWktKRtm0YXgVsr0TfUW4qe5GlQV28uKpL-imeNOGGT7yk3TN7rkU3iJ5Cj6PSb4grsxLEAno6jLYCoDv_" +
                "IRYB1xfpMMrlScXwdcvlvp0-6FFnpJ9q3PZel0xUz3yMv524Gxka0CmtWmT9-Y1xyoPosBIgao_Fv7eRmOvUYSCH" +
                "FwFeRdTzezb-SxtmhCPXmw3Diig08gWv7eOymA7DEYCOdNAkk8KpdyifIVnLNg8GkKOQpEQ5vUf_SjN3FPwZQxht" +
                "oFU0PnrCq1SLg6y8oa1eTZgMB33abAqtzRLSJGyPSZdoqsHWOh9OLEFuNG-a889JWIuWN2hAFOYgWRwI1GWrrfh0" +
                "abH_lgw4NoXyE19wjft18Ll4mz2RihSBdIntA29nR_IAkYjk8ZCUUaw3l8cL-y4-qdKgWUc4dXE3GmM1bXQO3kgq" +
                "87SwedK_tydHo2AN86ZMXByz1Q2jlWRtLIOnBlbENlx_X0POlOjDT8DSWZ9t29hFOCvpAWFSUVYkRxKLD4tm7MWx" +
                "NZqBcpx5byJrwITpW_QkjiLHB0UMhK3zIfQnYKQpvA2HQd4macntYFlns5Owy6ZFRaVolROCLv-dDudBEMO1hBax" +
                "-60EHkGZObx44KZP7yu0d3uNjI190Pt-UIC4l07fTh8QSOzD89FaGV0MhBjKFCJlJw-v99lI5AZ5dfDqe5-h8Bud" +
                "kn4lr_7OVgtFsmwjZiyFvazIDktDzt5Fe1-u8uBaWyVtNZMh_wcpQQMdu-N45LEba5lt-d3jqQgcB1YPFkwdok3i" +
                "f1qaIuSDhJbsnQ2-iZuAMyeOT9TvJTXirVoiF9buVN-51YgryXjOWgRqb1fs0MvWB3eHQAXv3hcXQ6PbuCRm7_j6" +
                "Me8QsN54OnAGlBB30HfLaHurELKJ5EMjEEUyadwfQoNUPH2kp_YlVX_G0ck7Qx17fqSode93Wbgfz9lQqsy0FdAB" +
                "wVLEYqzvufAYEMEBf8n7FpW6V-Qnpe-Sa094aV47CNcznuSYviTKdxvEyGLI8_mEcVoUtl2vvQR9xViaC0q0YuH-" +
                "LjuEE9VgS3MXKGAxVqtVYiVE6UyhU1kWMDVNA3_PFjCt4-hN-G5qnJuM2SqA8oKGsoBW9NPMy_fe6tA1p_l3Q7mh" +
                "koeC9MMBRPpNwO3toWB013X03LF6HP-xd5JU-TFIlV0_-6vAcNov8mNt_TMwj5IiXuN-flZrgCMiI2wjCtKI_UDL" +
                "WSZK9p4DwQPwPWu7BjlidTJd6yk8D-V4iP3acZ8YMFMzywWwx1MbarkQQ7N5uKT1G5Di5n8uMCPTFxfcdZq1QuE1" +
                "jRG_MDGSabPpyhW5tKD2TJrZLQM2DbJ9Cuvb7uUF1bqT72bYM4IAnnoWopI4BssMp0MCBPM_go7WS1S6d9qJyWK6" +
                "9-zmT0xP-fhOz3x9NV12R7KVG2bmszWLNih2t58OdSKdwnQFw8kv-81zfNlj2XGntyUBA6ewP79ltIfEC5XwC_sc" +
                "1EuNtkhUn_AeJQRhV67LMu1JCf7IkBZht-fbc6zoXBaULXwhP_5GHdHg9wteIWRD9WppWpnRdaQSVrpO0so1UiXx" +
                "28PqtQ1DEi-47xl1W1fZ8O-VywfHMZPSnhvVjR-mKWb0k35PUHAVp4rOt8P4hlicEGcAXeE3sFWd3cteCrv9Y23Y" +
                "zVnSKrafiXhMUVAex23qKtDZSMmAaUtVbFFOIdNfrTnEe5yvHz-7ThoN-ppL509m3Dpl1Soq2UacNMvguK1u-niQ" +
                "46YgEKARHKyYIf3SnlZM8ao1YLCGDSeIlOekrn2tY5abeuEMPSQZpErhNX-3XvHnGLAXNMi3d7se0H9SatG2Cf17" +
                "eGrzlInYpxYZHCgzMmxx-wHXJIGnSGGpwPKq1urbRoLgFvKOVEMGf9JGClaacag7QuyHD0umOuXjSNxkbkIOxrMu" +
                "6xr1iBfTyU-vrEfSv-W_i615Re0gZhxXOhlM1_I5YXm6xKzG1POZO5eCfA_Phyv1-UMHSLO3LhOea8oaNhkNroi9" +
                "SndW5ZJ8esaucaRCVnXelFI8PcGANS41W2AUjENoXPlMDl3SSPcMdeLZ9wzO6FtuH81xQwk8cplcooLLleGYZDuJ" +
                "RkIneNeT_udx38D_iDg8SwwQe98u3HwPwUYKtlpLgHcoZ3p_ppvZh5D0lGzriJ5DhidSJ8x--oj4HJrTjpzN3EwN" +
                "blVdJecTLZ_EefzqewU9hGZI0YQpU06xSt81NPqVjOPsDlf38VkOxIqe5HK5-Mjlse5lHrCP2oHVxb4YWDrrydL2" +
                "8ooO8RYMpbQFeivzW0eOPlKVXd5I100UU82sQ8siF0vYci90HqDWds8_Ihj1chmQ_s8ZL7KaR3ja8b6bmUhK0a5G" +
                "0BMS6Fn0lHshuW93hUZMpH-m9MaJj98K9XqsUtBYa5jzn4NNw5oKOfRWV2Krt4v-HYjt8ryKymqS_63uIrfKaztM" +
                "MO09albvSzojHHU5DHnVhh3mgo6-kxCqa3wmuRBuAhFx6nRod5M0vITWTPEjSgrhnK85fpaeF8GAzgDJSwDcRjDK" +
                "wgK3fUk8MJfkfUXAf7VxveUhJyW6ehyNttaV2NWTnHnCy5UZdd8xwqSyx119ldyPTRTret2ysDgDV7VbUPdT9kKz" +
                "qNjoBH9mfZjQtCNfgmLjYR5_2AEypcEuAGIFFDGMTtEJBtvl-M9uOxNv4RXXQocknqChXJV2UjMNlzE3IvLzXnIN" +
                "G2TN2zYRImY_9FMvlKL7j19qOL7IQEd2-gBhw8B0a3TfYVsXlOR2zv2BPftntzlepEbnogDqhHzwhnYnf-ug5Aq_" +
                "Dvz4GSyV9JM4WhURCFbA8lFD_XTXgVt2iwcZ_iZYdOgfBJpC0uTX-d5Jjd1vWiY93HUKo6GfH5YggrZ7bVRmvjNg" +
                "s2CuzYgEmSLwa4az5mogKCI-lR7-BeVsNLCkFrZsRny77hC6UdJwMGxoLg5hnHFtd-q-BhOzrOsPDO1UGCdueZJV" +
                "pJBmzLEXpYRm4QOfDGzoXa6p_wsvtwcsLZ_4sNUNnSji-CyjWVXEreoWC8Uh5aM4Uu1-XVpyOoWt54D9292PZyp1" +
                "c7pw-rBethi6Cx4RwwOg3lpqeNU-XYs3gaBXUrGOXF6vbmQrx7VXfcyDIs-U6d-v-cj6z7_jb-q82PbCPOMhnfzM" +
                "yZrSUNJ6WQjP8dHMWIbqLv7w4WLjblEzf5eVwka7uTCJw2yIaUY2E4hmzi_egTlGrRtmmq56pZJ6JIadCS1_WLbP" +
                "G4SkunsZESVofRjrIM2E9iEgAJ2zSgLP3fCDX9nKGxgR0XIBpDkwn7q58mCN6Res_n5dkunuKAoxRYG8W9s9WARj" +
                "Ep1BARhjS3_fGMScSUrs6gz4nV7eaHpgLYNgjVRNiTHLRL9oYRyZcLO39fmAtR4j9AaA-F4zCbhPngfloWUK-Ln8" +
                "mJhGj6L-38dGsYmY3J09jvLJD8eR0suGP_1cnog6hr2sUKN10vOTTZBF5Vg6mvP4feX2Nz5j_qxlAxgFGWvPd7Pn" +
                "79KZYfqt6i7g1OZm3v6h09QVnGjnTfNKXA9nUSI8UdIzrd_XPtiq7SZdvUf-mAWZ-EPJFXvsEejuYSGmAMq9tF5p" +
                "E7h8kmVypHXedh6JIwkWAzxb_z-Eg_V7mcRYI2g6pvnZDz_VAvY2jG1KEi64Qv3dC-1sqvqig_Y0CKEFyMD0-owM" +
                "kqa7xDSc3OViEmgoiQmB0eoCDoKrEy9rl9QwTFW6vt7_K1vSpyszZBiU_T1zhAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "ECA0UIy0xNzBlAjBZ55zaI4gCrqGTR5eWeLN9-V-GX6c1R2lGZeZffCiC28MEsLzlamLO62KksfqURPACMQCqmVw" +
                "jqh2O47QhEEqqeJz34rM8liCCOGSGeQAwnffbg0yCH9A8mFsnp2w3F7vkMiI",
            rawCompositePubHex =
                "e45ffc8cc73db885dc662e62a18cd8e3803297117fa5658814a985b5ff1db7b468cfc82bb929f1d86b77ed14" +
                "f5ae16a65368772ce51912410105e0456975ae91fdb643b512f124d5e60bd68b8c7e31fe01c7b0dc65ae4705" +
                "01cc565a6e1dfcfcfd12565433c4afedd511821e2e9610c45275e2836dee35ced69d7efa672fd1e4318bef5e" +
                "b6e897e8b451aa202ded042b2aaef77a7be3f699146da229a8bdb3ffa496445967e75217bfbc9048f9956443" +
                "d8731f833eb30de10dac96fffe7cf65ea0445c3e31e8601e133be6a100764fe3196e267726441f31751fbf9a" +
                "6f5880644f4e7275e57de2b0f105e4db055d50dd1c9c934fddf535b8de28b0c74c0449f222cd2ed0bb8fbc77" +
                "5ccee8c940665b40f712f4f7e00750e9e1e4cd9cff25d1945c3e9bca53ccd4f12eee7581856ebd68f2684595" +
                "6e3e7beb761f0fe75bdd31bfe2fa018113397b387bd59d62a68b8af7fa245ab932e69f778e2ceefd21304fbb" +
                "8099ea13d8ea57c1813197a2f75ae251075b51dad38f853669e9d5f98a3655098941993a1594860fba71fe53" +
                "0ee5c29f58f2978af688ccb75a5838a359c112e98e25a8583ac8dac1f861fd58e2afba5de5a52e020904f5b4" +
                "2bc0874e35befcf3e6119684768f36e008f04712177cebe627607381e56eaaee161c1729b8de51dbde474d48" +
                "cc68249ea27162b87993e60c84ed6cc6423cb3676d9eb50b2cab5a3a049ef131381d623fa6fbcbc9db1e7cc0" +
                "25ea0418b9dad2cc6ccd4e95fa2cec24feeca70318a751716b7213f63edbf65a63338357f838f94ec071822c" +
                "24851248885107b3d1c4e924678c7614ea1af038104619f2ae372940becfa69e29cbb5ff6c3e20a47be4a4f7" +
                "4bac34c133c00a6a706accc6ffd3d8e4fbd69a99704e1283c850d8c58d1e5753cd9587b83c4c346cb9a58137" +
                "213ec10834c66adfe2bb5c501a8ef2ecadd1b677a3df1a6deb86ebf0722c4f5030e20f9018dd5b6fc53eea24" +
                "fd92b7b5b4025feae996d3e48fd4c650d82dbad7eaf936639698512f26253d2ef6847c8518e8565cc9a5495c" +
                "6fff57cde7323882c54a7db470ab2daf8ffd2bf794fa7c692d9e7fbd532eecc1d7880e2ca0b3216128be28b4" +
                "a9f1d151fac97808b0bd98b7b43a612a9ac865812bfeac6f47460277840b52a3b087f916ca7cedc0f768ea2b" +
                "d19ea21155f84b4a04c4000ad2ae0587154d560bc0a477a4f9329a8984dd31eb1f2a05e3d918701d630cfca9" +
                "af61ef088d2c5581acb463e439902e5d425719e956b8d6df7305b28e0ff27d3ad0de2085d292499b19a3390d" +
                "4396fb3bac9a8d8cbead2a7a4290fc9ac6fca045f98a614a45a39cbe24360f84d14f8e472712aceb74dbf45b" +
                "53d49a0e4737e476ffc4d5b2f7cd247aa186d3b764ad9e9cfeee456a73c291d8de3912414ac43911c372173a" +
                "d7b472af35c6853ced2fe7b5fe0a89565ab33baa6f65cdd928319d7065e040e7a5e84f9aa903f7648094bad0" +
                "7136b16927b8ec6dbc2bef0cc2856de1e795923e1412c49f24deeb6c21f6c8a9765c9c7986e0da4b4c67d8e0" +
                "d0c8d466824fb923d8573148990cd2ef133c78ceecab72ed9dd285c5a3766852d54534207ffd34027f6c76ed" +
                "e8fd1a32d72c30048bbaa797d5df6fde27d087de5721ad7b7fa3e8d3f70d6bfc3ab2e252335368bbfa15acb5" +
                "cb37d4694e8b23cebe25de9c925a221a183b904d3f85df9929a919c54d6f87457373a0d6ecc1403e4cbbe620" +
                "999435e80696634cd1a8e4747e9825bfa336e5bbad14f73640f1b9febe800dbaefe1630c61fae635b074c564" +
                "eaa9db189c9e7302873fc64e6d497bc5c29080987a07a21d4af210703a4fa07f2fd816f12fd1e29b4c0f44af" +
                "e9bd4a1eaa8a7ae6f02a5b4258f52caf6127f62632a67cf4e8310be56a7c28c86b2e277600c3e92c8d23d425" +
                "86244c571e90568df202f2f6d81f860a565f9eb91a3c78372e2a8b1be61c5418cf49bf2d6c8955d4a482a991" +
                "9b7660b3f9a4404ffc454ea073e1e4b2689ab2cca4e46bd7004a6c491fa26ee7a57d60f35edb2b821e626644" +
                "2c8f335d452d524c772e0353724c23c7dd15b7aa155e91442022140c5fcb0153147edcf3e8952f6f0399a3c8" +
                "8066a72756c9409915de63f64fa797841c57c796c6fc550ef745dfe9f179457f94755ae5a2506a764f327e55" +
                "0be3dc14dd41f3b04b147d454938c63a8d69b2ea4c5710ec0b36e3a6c72571fa5d59dde036c42033df35af05" +
                "6966ff0cd1204008971aa6ba9fb97b685ab9ffa2a9d1778104cd2c3b326de1fcbc242e94d0311c3275b12850" +
                "ed30ceead3a2ee6d060508411d4396f5421d8b6d067cf7cb5e826785fbe119e05e21bd879b64f57cb0cd1972" +
                "c2815f20abe7ce6ab34d0f471af44baad179e90644122f5f33288e689ddddc5ce833e9755df1e73c65c5a201" +
                "c4ede2ffa6b19274927719d2d38fdb7a65aa43708b7fa9a94aa7d3210253d78d3b181e1020d0000bd0a1dc05" +
                "d447f9f58ebeb84c65b36c8afcb83727a1508994e826957a663b0b9b8a003325ab6d6d6462ee4e106019c0df" +
                "fe10323b7bde7d82a38f85fd08786e860ba66c161b64b0708c363de5c6af62d8db3c243d1e1b712cb1d59e94" +
                "2b9b6b4295a5a500b182cbd5fd1bc6ce9376d91b47a2284f1fbe0ad1c048cc2cfbb4afa3a9eb9697503b69fe" +
                "ca990eba7e9441af9ca44cb3ac6b5ed66e591c201fe30efa8a7c471dc613d6254c263a8e132104bec47f1aac" +
                "b3b2fcd4051b69b5e3fcb1c147a65c2f90c4b5188bafc521cab03c12a309da50b5a7517727ed41228ed123fe" +
                "1b152f6a6319cd623bf34ad7b8e064ab993260bcbd405f5b7fff9b2fa40ba5ed5630242539e5d96823e89dc8" +
                "18a13d16675ee3079d976f694f5acc9760ae789e9b3391b289e0e22a7ef17cc6a4577157b6d95c09baa4fd53" +
                "2e3ee0a290810ed35e56bb19d9b61fb98a97c617425b06093d98a5cf0ee2dd127f0eea600b9a0c67fbe761db" +
                "9b77e5d5bba9701da1b883e521a0cfe88451f57bd36085b67e56f061f84a2e6a152a71bce6e522daab6a0a33" +
                "ce22e537fa9793d28b617e6c0a4176a83aa3be578afac0f2f5547c5516d218984755b7445c7143afa4e551fc" +
                "e0071bdb873b34e6b9e2b9e79ed0c69d288ed6421f237e860a0c6492ebbdd2a44c2c4f368dbe99941b1e8561" +
                "d859d3859f496cee3d741f252973f8fcc539c409e35cc80a5ed6df23cc3a65601313f5d681fd9540c5291a9e" +
                "30a72e38c96413c47c61ff84fde78d011b01b4154d1b920af003f7abb1e1999dea6a766cf9fd2702b3ce0ee5" +
                "7af931b62124b0861b163a3b91aa4bea28076c3432df3b29b6c4e1ba588def420071fc157de90eb2722ecc9a" +
                "b00df3c669383a61a91bb67bd287ce349b4745ee7a479dbceef166b9acc412eb579fcd6437307edda253d606" +
                "b7be7599c38092bc52a8598480edab8b82b1d21c565d2137ceae0b6642619b16133d91205d6355029e9cdfeb" +
                "9a28b373d95916b6b707d4c712c09cf36daf1a511b2bedb1aa70ee58d46a0666bb287784b0a3840c589a7a04" +
                "d5d6f2216be90aa4a512d5632f5c9bfe7b8b13382f999b95d367c7c46b968074ce315197a5ff3545c7b77a80" +
                "4ade56a95b5c24cdece5937b5c0366d93ad03da9bc5db1b551dfb91e9b343d2b57b763439686d4a304aa87ca" +
                "22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760a" +
                "b73617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d" +
                "7c90ea0e5f",
            rawCompositeSigHex =
                "1abfa6da68f8e1b94513c188db1c56768277fc0c06e32b0f355561d206a75e4403a6f4a23ee6e01b4ec33950" +
                "74364e20faf6acaaf4dbb80be49cabd069e48cc11d03ea8de7a1706d413da847e5e358a7b7d2980cccf40b7f" +
                "e36442a28666fdc0a09e06a94571db66569f71343336fa5ef36b453cdfec60b4b494dbefceb0c7e75846325d" +
                "d1ac648d3c43ff157c259fa53265226b60135377c8bf62c15f46160ce992177d61f3a761de0f10ddd6d8ed70" +
                "e7c1ffdc5a51ad7c37ea0c1d0d0e5b23feba89fab85dee21fed20b6bae71241ede57fea2bfd17812faba3a79" +
                "7bd7a56ed63187146c0302d1d7f3eb3f7c2da3f0275fe5c686051c4020019be6e3f33e7ea82980c6ccfea806" +
                "cea9d91683990bf13684aa4bef0a67f82e4d15a995dccf96092bd2b97f2dc4b94aee07f6452ef38dd9e95f54" +
                "184215c726ec9db9afb9d5e91ccc2954d98df069bfc69d718fe4d5a7cc3adca48b999c206a07fa36118fd209" +
                "0fcf76ea7d4134dd355bd333e92a20dfb40f308d614f0a1e667737fcb533dad9059363a4f85851262a136556" +
                "4d6161c4bec6af5d97b21475a59b3faf1a50b429304a803b098f1e43a36d0d401620ef3b41e02d1cd7e97f42" +
                "f61ff8bce66648b438ab2541e704a91bbc6975d2a8826ea8ad8c87d02dd047d976b0583cf3d89f78b8dced01" +
                "bb29f467d87802ce0fbb8c880778ec64e25d5dba177dfebdd0fe16666863a5493026fb86e93700a8706ed397" +
                "9023f80559a51836066885c151e43811e9f295cbc10178b17a37e47a7d6bfb45d0e933568bf175e2feeffc73" +
                "0a3f3d27fd2dfca0d30873b49f204a152d0a70860dbb115c2f1eff78bcf06628d0bc58686d4137a28a6228b8" +
                "6a7d985178012961d95aba734457deb2a732316dc1083e4cf7cafeb1d2bc8a209ba6593fcb4a7ffa9fa6f982" +
                "ead3d203c33eb0d292cfed7a72569c2ceacb2cf69fabb0dac61fef346a559b7c67165e702a8b0ecc93404466" +
                "c4d2135d8e9c712b24ee3859bc88dd72503398c3241d573898ff9ca6e141a851ee146de32b41007f7e8ba6b6" +
                "ef83e1fce60cc47886b6c0143af17d47241dc047270007480d7762dc58e9498eb4df2690e682e1761474e2bf" +
                "0e791c3925be4ff48c88f6c57f6e391ad6b3b5632c716af85bf58ecf8a98f7d1c4dec544704c3e56eef4543c" +
                "b7770deed5c2870baf24a0a43ef25fba607c695247c909ad5fd2f903da02cc1115b0302670efcefc4ccc68d1" +
                "0d9201c9727ef35abd351b320a1e3711271016d5ff177be4557d7e5517e8dc59b40f1708bf7b88a9b1f72388" +
                "4a1f706ec17b0868230cfeebff8cf4bd34e4ff6cce356790edd63dadadfd95dae63409a037460e2e297c6f60" +
                "9dd524d2ff85506c5193b13204b2d19317699a2a31defe144ae7cfde102bc43ee6f65723120af7df0710d459" +
                "02097f1a2c1a87cffcc7a3b527f1e0d9343282e3888db7c33e15d0afe3d763a5c745529e8a8563c9e7b68709" +
                "2db4fe9305a3924b0a5ddf12a2adee624fabfa08c78a5c6e19ab80e82a081babc72efdf29a055d22e26f2847" +
                "0b8d7bd86980d5823592b706daaae6fa8c2bf52877328279b0c14dff7dcb3adeefce86d1f79a22c687fa8149" +
                "172c1e62f985ef35e36b461021c96b4aca307d4809c512cf4d88ea6fd575f1ea395e88241a737a5800fb5e9a" +
                "ac3d7a6b92838e1bf79dd3839d4db524b226f103139ea992896c1f89fe9dcfd515ad7a19ed4b340c09a50685" +
                "fbd2f3d09b343b9f34e546185294ee413bcbbff3007b38e95c6a7915a00de66727febe8d991269120eb9690f" +
                "f12218aad0b3df2150cdb28d857554a3e4916f926fd7bfe8d8051cce89bada5b180e77afbf562f441f83d334" +
                "c572d26b181ced799dc3d6de597e115321411865de090126a5e864e4e123f6eb05654848b1b9138cccc88e44" +
                "ff050b10630a57e7902195e56639190d729557f18a1bbbaf894ec953c5a7e2a281e23b4d9655332819230117" +
                "cc027ed532fedd80cc36ca910a18725adb908fae65acffdd7bdb2152a0d04acfcef57568b3058134c3bd2d3b" +
                "95cf4c555d756a2c6781aa9a67156c2313f38003866e13f6adfe0a594c2f82985f698a27763486c0a81bc254" +
                "59217407236a3ee9b5ebfa0dd82e07c56ab2646451a06fc5568b79c133fdcd837408ecd50e727c7a81a011ed" +
                "a6478c8e6136ffa75249d4536cb3a4602022924766e6effffe582f6081f3bef1e80016889178eeedf30bd258" +
                "5d5f4f3862ae41b8674ff35f8bffd18b6d1a64531a964a827aa68292f6299be073f80e7935685f66c8d79e47" +
                "3149346c4e0943c1ad25af6e133b6830ad286a9fd6d6887d0d7ee097c44259170756d028f7220c9f19922329" +
                "d7f9e17d07769d50dd58b2f3e12c1b12161ccd7e55cdbb969f56e0c7d29444ac6bf6d870099639d57090b6ed" +
                "5de6b2482edaeee552fbdd9fe99ebf563d2556adc54008661c902531826dbcf1962afce5093d51982810979a" +
                "4138b40307f6711904183d68d20402ef06c55e1bbe5482b5674e85a4b4a46d9b4617815b2bd137d45b8a9ee4" +
                "6950576f2e2a92fe8a678d386193ef29374cdeeb914de22790a3e8f49be20aecc4b1009e8ea32d80a80effc8" +
                "458075c5fa4c32b952717c1d72f96fa74fba1459e927dab73d97a5d31533df232fe76e06c646b40a6b56993f" +
                "7e635c72a0fa2c04881aa3f16fede4663af5184821c5c05791753cdecdbf92c6d9a108f5e6c370e28a0d3c81" +
                "6bfb78eca603b0c460239d340924f0aa5dca27c85672cd83c1a428e429110e6f51ffd28cddc53f0650c61b68" +
                "154d0f9eb0aad522e0eb2f286b5793660301df769b02ab7344b4891b23d265da2ab0758e87d38b105b8d1be6" +
                "bcf3d25622e58dda1005398816470235196aeb7e1d1a6c7fe5830e0da17c84d7dc237edd7c2e5e26cf646285" +
                "205d227b40dbd9d1fc802462393c6425146b0de5f1c2fecb8faa74a81651ce1d5c4dc698cd5b5d03b7920abc" +
                "ed2c1e74afedc9d1e8d8037ce99317072cf54368e5591b4b20e9c195b10d971fd7d0f3a53a30d3f0349667db" +
                "76f6114e0afa40585494558911c4a2c3e2d9bb316c4d66a05ca71e5bc89af0213a56fd092388b1c1d143212b" +
                "7cc87d09d8290a6f0361d077899a727b581659ece4ec32e99151695a2544e08bbfe743b9d04430ed6105ac7e" +
                "eb410790664e6f1e382993fbcaed1ddee363235f743edf94202e25d3b7d387c4123b30fcf456865743210632" +
                "85089949c3ebfdf65239019e5d7c3a9ee7e87c06e7649f896bffb39582d16c9b08d98b216f6b320392d0f3b7" +
                "915ed7ebbcb81696c95b4d64c87fc1ca5040c76ef8de392c46dae65b7e7778ea420701d583c593076893789f" +
                "d6a688b920e125bb27436fa266e00cc9e393f53bc94d78ab568885f5bb9537ee75620af25e3396811a9bd5fb" +
                "3432f581dde1d0017bf785c5d0e8f6ee0919bbfe3e8c7bc42c379e0e9c01a5041df41df2da1eeac42ca27910" +
                "c8c4114c9a7707d0a0d50f1f6929fd89555ff1b4724ed0c75edfa92a1d7bddd66e07f3f6542ab32d05740070" +
                "54b118ab3bee7c060430405ff27ec5a56e95f909e97be49ad3de1a578ec235cce7b9262f89329dc6f13218b2" +
                "3cfe611c56852d976bef411f71562682d2ad18b87f8b8ee104f55812dcc5ca180c55aad55889513a532854d6" +
                "458c0d5340dff3c58c2b78fa137e1b9aa726e3364aa03ca0a1aca015bd34f332fdf7bab40d69fe5dd0ee6864" +
                "a1e0bd30c0513e93703b7b68581d35dd7d372c5e873fec5de4953e4c5225574ffeeaf01c368bfc98db7f4ccc" +
                "23e48897b8df9f959ae008c888db08c2b4a23f5032d64992bda780f040fc0f5aeec18e589d4c977aca4f03f9" +
                "5e223f769c67c60c14ccf2c16c31d4c6daae4410ecde6e293d46e438b99fcb8c08f4c5c5f71d66ad50b84d63" +
                "446fcc0c649a6cfa72856e6d283d9326b64b40cd836c9f42baf6fbb941756ea4fbd9b60ce080279e85a8a48e" +
                "01b2c329d0c0813ccfe0a3b592d52e9df6a27258aebdfb3993d313fe7e13b3df1f4d575d91eca546d9b9accd" +
                "62cd8a1dade7c39d48a7709d0170f24bfef35cdf3658f65c69edc94040e9ec0fefd96d21f102e57c02fec735" +
                "12e36d921527fc0789411855ebb2ccbb52427fb22405986df9f6dceb3a1705a50b5f084fff918774783dc2d7" +
                "885910fd5a9a56a6745d690495ae93b4b28d54897c76f0faad4350c48bee3bc65d56d5f67c3be572c1f1cc64" +
                "f4a786f56347e98a59bd24df93d41c0569e2b3adf0fe2196270419c017784dec15677772d782aeff58db7633" +
                "56748aada7e25e13145407b1db7a8ab4365232601a52d55b14538874d7eb4e711ee72bc7cfeed386837ea692" +
                "f9d3d9b70e99754a8ab651a70d32f82e2b5bbe9e2438e988042804472b26087f74a795933c6a8d582c218349" +
                "e22539e92b9f6b58e5a6deb8430f49066912b84d5fedd7bc79c62c05cd322dddeec7b41fd49ab46d827f5ede" +
                "1abce5227629c586470a0ccc9b1c7ec075c92069d2186a703caab5bab6d1a0b805bca39510c19ff4918295a6" +
                "9c6a0ed0bb21c3d2e98eb978d237191b9083b1accbbac6bd6205f4f253ebeb11f4aff96fe2eb5e517b481987" +
                "15ce865335fc8e585e6eb12b31b53ce64ee5e09f03f3e1cafd7e50c1d22cedcb84e79af2868d86436ba22f52" +
                "9dd5b9649f1eb1ab9c6910959d77a5148f0f70600d4b8d56d8052310da173e53039774923dc31d78b67dc333" +
                "ba16db87f35c50c24f1ca65728a0b2e57866190ee2519089de35e4ffb9dc77f03fe20e0f12c3041ef7cbb71f" +
                "03f05182ad9692e01dca19de9fe9a6f661e43d251b3ae22790e189d489f31fbea23e0726b4e3a7337713035b" +
                "95574979c4cb67f11e7f3a9ec14f61199234610a54d3ac52b7cd4d3ea56338fb0395fdfc5643b122a7b91cae" +
                "7e32396c7b9947ac23f6a075716f86160ebaf274bdbca283bc4583296d015e8afcd6d1e38f94a557779235d3" +
                "4514f36b10f2c885d2f61c8bdd07a8359db3cfc8863d5c86643fb3c64beca691de36bc6fa6e65212b46b91b4" +
                "04c4ba167d251ec86e5bdde15193291fe9bd31a263f7c2bd5eab14b4161ae63ce7e0d370e6828e7d1595d8aa" +
                "ede2ff87623b7caf22b29aa4bfeb7b88adf29aced30c3b4f5a95bbd2ce88c71d4e431e75618779a0a3afa4c4" +
                "2a9adf09ae441b80845c7a9d1a1de4cd2f2135933c48d282b8672bce5fa5a785f06033803252c037118c32b0" +
                "80addf524f0c25f91f51701fed5c6f794849c96e9e87236db5a5763564e71e70b2e5465d77cc70a92cb1d75f" +
                "657723d3453adeb76cac0e00d5ed56d43dd4fd90acea363a011fd99f66342d08d7e098b8d8479ff6004ca970" +
                "4b801881450c6313b44241b6f97e33db8ec4dbf84575d0a1c927a82857255d948cc365cc4dc8bcbcd79c8346" +
                "d93376cd8448998ffd14cbe528bee3d7da8e2fb21011ddbe801870f01d1add37d856c5e5391db3bf604f7ed9" +
                "edce57a911b9e8803aa11f3c219d89dffae83902afc3bf3e064b257d24ce1685444215b03c9450ff5d35e056" +
                "dda2c1c67f89961d3a07c12690b4b935fe779263775bd6898f771d42a8e867c7e58820ad9edb5519af8cd82c" +
                "d82bb362012648bc1ae1acf99a880a088fa547bf81795b0d2c2905ad9b119f2efb842e94749c0c1b1a0b8398" +
                "671c5b5dfaaf8184eceb3ac3c33b550609db9e6495692419b32c45e96119b840e7c31b3a176baa7fc2cbedc1" +
                "cb0b67fe2c3543674a38be0b28d655712b7a8582f1487968ce14bb5f97569c8ea16b79e03f76f763d9ca9d5c" +
                "ee9c3eac17ad862e82c78470c0e837969a9e354f9762cde06815d4ac639717abdb990af1ed55df7320c8b3e5" +
                "3a77ebfe723eb3eff8dbfaaf363db08f38c8677f333266b494349e964233fc74731621ba8bbfbc3858b8db94" +
                "4cdfe5e57091aeee4c2270db221a518d84e219b38bf7a04e51ab46d9a6ab9ea9649e8921a7424b5fd62db3c6" +
                "e1292e9ec644495a1f463ac833613d884800276cd280b3f77c20d7f67286c604745c80690e4c27eeae7c9823" +
                "7a45eb3f9f9764ba7b8a028c51606f16f6cf560118c4a750404618d2dff7c631271252bb3a833e2757b79a1e" +
                "980b60d8235513624c72d12fda1847265c2cedfd7e602d4788fd01a03e178cc26e13e781f9685942be2e7f26" +
                "2611a3e8bfb7f1d1ac626637274f63bcb243f1e474b2e18fff5727a20ea1af6b1428dd74bce4d3641179560e" +
                "a6bcfe1f797d8dcf98ffab1940c601465af3ddecf9fbf4a6587eab7a8bb8353999b7bfa874f505671a39d37c" +
                "d29703d9d4488f14748ceb77f5cfb62abb49976f51ffa601667e10f2455efb047a3b9848698032af6d179a44" +
                "ee1f24995ca91d779d87a248c24580cf16ffcfe120fd5ee6711608da0ea9be7643cff540bd8da31b52848bae" +
                "10bf7742fb5b2abea8a0fd8d02284172303d3ea30324a9aef10d27373958849a0a224260747a8083a0aac4cb" +
                "dae5f50c13156eafb7bfcad6f4a9caccd906253f4f5ce1000000000000000000000000000000000000000004" +
                "080d14232d31373065023059e79cda238802aea19347979678b37df95f865fa73547694665e65f7c2882dbc3" +
                "04b0bce56a62ceeb62a4b1fa9444f0023100aa995c23aa1d8ee3b421104aaa789cf7e2b33c96208238648679" +
                "00309df7db834c821fd03c985b27a76c3717bbe43222",
            rawToBeSignedHex =
                "65794a68624763694f694a4e54433145553045744f44637452564d7a4f4451694c434a72615751694f694a35" +
                "53446449656d6774646a52715555396a595468754e6e5a4d64455a50527a4d78627a427756444a4e52467045" +
                "4e555a30646c4533556d566e496e302e5358516e6379426849475268626d646c636d39316379426964584e70" +
                "626d567a63797767526e4a765a473873494764766157356e4947393164434235623356794947527662334975",
            rawMessageRepresentativeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4138372d45434453412d503338342d53484135313200c6e74eec87cc66427e79b59b94d43773c7e5f186967e" +
                "8be973ed9d3f56fdab8cc6037c003b066915d54565074e83ab2e4f2ef6a1921ca96231fe6a6b6b63b109",
        )
        )

        private val coseVectors = listOf(
        CoseVector(
            name = "Figure 6: ML-DSA-44-ES256",
            algorithm = Algorithm.ML_DSA_44_ES256,
            kidHex = "08e7acdb81ec1e3e",
            pubHex =
                "ba71f9f64e11baeb58fa9c6fbb6e14e61f18643dab495b47539a9166ca0198131c44f826bbd56e34e55db5e5" +
                "e2d733485e39ea260fc6000c5ea4ba80d3455cde53b46f34482aedfd5450fc2e1ba4f25d15f9c144242fb39b" +
                "b52287189030c50498e1717b7c758b190a6748ea9aa3f7acaaf2c7cb526ed717c9f79aeb84214fa5cd8ded92" +
                "a0c3fa1558810f12c7050a367708d196cd24e5af974904aed8e4ce8872e8696b0b7bca50e452cd7d30ea9a4a" +
                "dac0311d672c6bde8496240b07431463708895cd9bafc31632d7397649388fdafcbf7d305a3de9a495eca743" +
                "3a8f83ba0f0b25c413c6e39c96eb7d691b34d37ce37f1eead1cf217e25ef34eecf3f7c60f84b8edfdde8405d" +
                "4f832576c61ef98e0a2f28da187700953924f686b94614705bcf53d33fedd4348edddbdf28b5065e1f207750" +
                "43e85cf931f829179363a1a7e7404a838ec00086b0976386fe637c98244757e3f769ddd4467471bfad670f9a" +
                "05f8246ee50a7b1eaf87fc4069c3ae2aa2033258117792f0bcd49e083fd1bc7496abff29cc94e4868b21214e" +
                "d316525399a610fbdd4a80e7c80715f29578e2a84bb40bdddbd9f47a11b6e7da118a1b658d359e8aef55eb46" +
                "b5376b5b655979984a922beebfc59bcd600d5309dccd72dbf0787db8ba757b537c1eafd5c0f50ea4bc958354" +
                "9e2829a42c28cac248c96d78124c47159b18aedd754aba17b19d430fb78f633ea9d26f54a9bd50f8d8f6b735" +
                "94f828976e7ea09c53bbb9f11a56c9507fb89b9a5ebc037a37267a95f85b8d64ca97192b10a66f417b3f61fe" +
                "9ca57130a48fd925eae2ab5502d571c8a51903c1d398f4c1f76a7e11743976afdbc697f23094a3cd761ff968" +
                "5de32e09fb3c28add453490300bc7c89dc01780096071722945775f264e1b0623bcf4619c712c838761205d8" +
                "7691b75ef360196cbb9e9b92a0d4c4ed62326e5024d77510b8ee2c7426cc22eae209dc9f13bde6bf08f5e718" +
                "1bd3b459450b451a51539a715c21d67dd330eb5970db00d9edbfb2822b036fa13bafeb86d8dc78866e3f8d43" +
                "e53d78cca5595a6faf886b5dc112f1cf4adcfa875800d90b48883af97316fe1506873fc157e570eacbfd2228" +
                "68d14234101966afb6bf9940829253a953ada89fc756b6a849f70acb9838e69faa50bba75e3e89c2adb57e86" +
                "d088ab9b04a28e670709172243ec5e0008a5ceaf3f8722f487302596ffd755ad1b82a49c34b3469515b46aa2" +
                "90cd86ee38ea7a9be3f103610335b531cca333ddfe32b14510f4b07ef95fc6684e8c454a92c10dbb5d59c7a7" +
                "c63fb305fe881967d99e669eb632840582560bb403431d40f75a4954908482278292821f4ea91e42e78fa48c" +
                "aee3c836146dcfd738d117e92e9a15137d28e8e6a4b4622650cb413504cb3a335d44beec5746c1c294b1e8cb" +
                "99cb608d928f8ce3563632c521f23d13c61a8f61c01df8c96c7360db4f3c68aa5d2fdd342a62ff3459c11638" +
                "9421ab43e8584c45882b50e6e4e96db6f0b8fde890d5dbfadcd88690b449e64240ddb2023747f308363e301a" +
                "a77757169fc6150628d5920b5aa1ab1c8cbf44cb00e025d7879d72b479e3af5311c785725590da9c89b9fc3b" +
                "8450769554eb44d203eba2bbaef9cad2237011c2ea44eff00f299a48ffe28ca93ddf85f76608242ef8d6cc24" +
                "610a1e2078fcac4f9385c314905ecaa82e553916d94d1a7c1ec652aa08897083daa2ebb1775fbc471ae27777" +
                "d7904ea9f1b92bcac3d8a3158426087b645b1108f0d65fec93789c053743ca14fd63d05e98b652df2b9c2ff9" +
                "ce05f1940703ffb273f80e0e2732eca9960d981b4cfd3b7bb8045b3c3830546b9dd8db0d046b17d1f2e12c42" +
                "47f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33" +
                "576b315ececbb6406837bf51f5",
            privHex =
                "0000000000000000000000000000000000000000000000000000000000000000303102010104200000000000" +
                "000000000000000000000000000000000000000000000000000001a00a06082a8648ce3d030107",
            payloadHex = "68656c6c6f20706f7374207175616e74756d207369676e617475726573",
            mPrimeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4134342d45434453412d503235362d53484132353600ad9c56542caaef74ac6ad7cbf62386c2f4aac8c329e4" +
                "a6a614fd272572ccbead",
            signatureHex =
                "2a2fa6272a8cabd04670e6adce8f462c9c905742f528d59012cb6ad1aabe87341df45c39c840a5c2007febfb" +
                "eb0f755becb8d1bfa4bde4b16656cea016f42f8a5cbdc37ff388b419a91ffc31680c14744c3e4d6263f7db6a" +
                "6d1ab6a983bb6ff35ad62e388b59508913a786ce0fb39d8badbca771021afd7df255002a5ba3dd9c0da9b285" +
                "0a816913c3ef3b353fb24e6294287b7d0799d01056e4ef23aaffb68e10a3df96b90ab38d2d4b5e6373d8f321" +
                "f8c2ffd0a9fc672f8d0bdb677e39ce5dc1a3ae817cc1b6bc033bc0fdfea2dba7cd24558bddd594f96c8b21f2" +
                "06b7f2bf64b094f829f54f57be1357c3c610e406250f4a689cf00b23f4107b1c07062e55b18a73c9ec282abc" +
                "cee312f05a4c057e517647f13593ce6455f1ee721ac7099f6bb42f61b8cef0d8faebfa2924a48f2210b25ea1" +
                "b9daeb698d7d96a3348e921b84b98d9fb78111c174aa8f234eb06f02e78a662c2f76302022be516f9012edbf" +
                "26b07b9ee6086d8e5b549d2a953cd35f9a27ad89bbc2747471d38383e4e53c2bcddb293fa2807b9baa18d37f" +
                "8729dad3ee86ff0efa8fe7750eda6d48e838b70a94e6b062f402afdb7089bbe24ebd81cfdf0bd73b38c188a9" +
                "e66bafc3dbe622883d4427c276fd3b101add92294fbee3cf4891022e0899446d76e1c6b20f848965f7fa8bff" +
                "f642c75a3975b8f4317f528ca46d3c08bef2d586be15bf684619017c411dcbbefa7333c834f25a137dba319e" +
                "24b89479a84357765be4be74063d9172a1cf2891594b5de34aed7a387ca6928d538e0daf4cfda698545f7ec2" +
                "a71635a7f086e7e342950000a6ce46cf8c52f8139bddb9288cd0e4130b3e90422bc9f464fe0453795156f0cd" +
                "62721e9cc831cc2a9ec0558feb4fa79c1b2b8442b55d9d827e085119afef0c8a6706fba58117c4e5c77f1fe1" +
                "f766c54f0cd789e383479418c5968230d5f86d319d97a009c35c6288c6ac6b7846b3dcc3b9daae92c7759017" +
                "2b77e0ff9258faec938c9996a10d82cbaec999c08a05ee0244002c2383bd110b8ad7218cada06646455de2af" +
                "c247b723961961523b5633fb00bc46aac1bb2a2670ac0cb5e79e4335728535c78374dfbafbced9c8a65d01f2" +
                "0435d0500d3622dc74ff528cbae4926080c83e4879a0887e168db95c59e85489b0a0146399231f40fe9d9659" +
                "08d9f685827d0393a74edf8bf6bc015ab4071dbdfa9e1157db6b63d935144c481966037a464875fff90555c3" +
                "d055ac8b39b996d3794fc264dd2a334e7a0ec253986e283e36c4346e93f97b4f7e62015d72be5352dcc03771" +
                "c4b4b48581367f26296645dd1c886b166b7d91bb8ece9e50a42d86bf22855cdd8c190042b502c5d22c7f1f47" +
                "3b25d2c1b5412efaaf5c195c37846bd455c783f9083e46a28a2fcbaeb39a792675ff31431d51a13871091a0c" +
                "35844904aff57288f076636ca826142943837c0ebe5ad0c3210a89d88655c1792c0704dca97bce5b144d3e94" +
                "6100c626d352a1e17e24717fe4478bd9901b0df9fd5eda7eefb1a582732f3a33cfa7df15234b59cacf868e11" +
                "41cb22442e8ccbe970d842b7d1dfe2d6ca1e4ed8ccd0f4fbd8d1799177b6c2ef210602b1dd9033ae899c5213" +
                "7937d1538875b152dd05357842dd0087325ec0b5343335174968b5b2f15b76db936b6db29bd79e35ed9eb057" +
                "5375a884a3efd57bac1449699b93fe186e0cc6e43b3d0d3e2973033bfeaf2e1803798b087b049ce8a2c7a215" +
                "6d1b3a16dde288a158bc740afa154835ef7d1b5e6dddbcaafbaf2c490a0bf08a14b69c871576aa370d43cdcb" +
                "9a0e346a4b79f740a8f0168d95c9e2d684ec6759d0302d5346803252bda5bee8d64dee39e1686c90a293fb8c" +
                "2bfedfdc00778141b362e522827a26f3c095ccce0680d7b86b7650cd40f94b272dec01e58da108b0312de29b" +
                "738b04f26034515a35e4235f92b877c5fb64494b54c9e1f8005140ae73b0d60d8ca0f234ae6de7deec60867e" +
                "d3eb04b08602b313c99f69a9497e9a65fb574e1a2e749724fbdea47f58192e9617e90afb5a88681042591aa0" +
                "92acbaaf68424287651bca7b1a35fab63936c85c440be4aa5a5b86425d7117c6f3a51d499dbf42148cec7dd8" +
                "77733adf30e1c23be878715e58b3538295b02138144b70398bff8124fe2d4931339769e24e563c89d75870ff" +
                "ccedb1fd1d5b2eaa2adac94fdf1660dd272513b28b7a2aa8266117e058800dafc46fb20de541d275804933e9" +
                "e30f5beb5bffa36f4665ba334bf88214672a95a104b1877f74ca9b0a18ec2c0769c67871eae04b5b9820fa4e" +
                "6742c37e1ad126a323e28c6c83c2182390297aa304fe8c0b21c85713fbf94f19a20948166c1b5fe68371b509" +
                "e693fbcfc750aa84eff2608a4d1083647eab2af2a074f475732e5fc667c97f1d7374fab7b54897204d61e4f6" +
                "de7b949a4a8580407f877056cb6eee57c95e566fb8f8eb25387f52beacd85813ab6dc779f9b92b278ee414ff" +
                "643ae3bf4c4c449ff35392d5b9e9cd914d1eabfa043ac33d9312ce5b9a82dd04eb0491c4584a711c386b78d8" +
                "e86705c1c07db3cd5a53a3e4ce340f9a178a6107eadf883d2ee481ee3c84b73d37d85332b344d235a0ab1cde" +
                "b513875949a5948f918b1243f43c90b135bc8c4867f53348f563f660efb44401f0aa72e90ddefe657521a382" +
                "adb5b6100feea242349b70ceff91391e2ad922fb50842a8fdb8a276e9f3e5601b8fe27979afd40b1039eaa6b" +
                "580d672d0a7424162f4a50bd517fe4d33a9cf6d6889895c92aea189b18f6505dd72b9434d2a819e5dd62eae2" +
                "a3fa26e3211e26c7d8751d4fec2304e13839ead9e006d9ba3bbf4a2789339eb2fd974742acfecdd9420d3eb6" +
                "8ee031e9d7ad0ca8ab508d9776f2a60e804d0aacad590f60bc864b042b0b1e1d90e653bb5171d9637b551b0a" +
                "04b257866ce2a9830f6822c6f56ba8ee1705e527cd5536a09e71acde5faf65e8ce9d3660cd5d4a6e6554c742" +
                "12b01b4baaae32392dc91be9fd764844b7509f7b81c5a54c8ffaba91bc8a9f9adb85f46f15a85662adbabf33" +
                "20164640e5df7d3e1388fd9503ad5f5b43f2945c5cdca8cf7f25299ca5b661042fef8a9d3abe25c52003676d" +
                "f0ecda149810dc0feb0e815027994bd1d7b61d80af71b48f22fa709ae8fc80fbc0ead415303d830bea2b10d2" +
                "f2df077635f7bb9d31ab14e1286e1b4fd20016b869c7fef906d6c257148f6bf73d32952b66267b90723e4252" +
                "d49aadeed1ef89149b684f9747474b8d8224afaca962a94c76ead84b91c21fb10c4c3b125c1915978390b525" +
                "c70105472333393d4e5a6d76777da1a8c7d3d7dee3e9ebf609115584868a979fd4effb011d232931437f929a" +
                "a9b6bde8020a12172f344b516e7e9fa5b1b600000000000000000000000000000000000000000000141f2c3a" +
                "3044022064010778b5547a4484130febea0dfaabf646d09792c8d5c0cb56010da02c11380220518ecbda4c8a" +
                "f10b5add5af991ed33c9e21ef22c2d0e6aa93a90bbefd410cb69",
        ),
        CoseVector(
            name = "Figure 7: ML-DSA-44-Ed25519",
            algorithm = Algorithm.ML_DSA_44_ED25519,
            kidHex = "b930f85dd9be4a04",
            pubHex =
                "ba71f9f64e11baeb58fa9c6fbb6e14e61f18643dab495b47539a9166ca0198131c44f826bbd56e34e55db5e5" +
                "e2d733485e39ea260fc6000c5ea4ba80d3455cde53b46f34482aedfd5450fc2e1ba4f25d15f9c144242fb39b" +
                "b52287189030c50498e1717b7c758b190a6748ea9aa3f7acaaf2c7cb526ed717c9f79aeb84214fa5cd8ded92" +
                "a0c3fa1558810f12c7050a367708d196cd24e5af974904aed8e4ce8872e8696b0b7bca50e452cd7d30ea9a4a" +
                "dac0311d672c6bde8496240b07431463708895cd9bafc31632d7397649388fdafcbf7d305a3de9a495eca743" +
                "3a8f83ba0f0b25c413c6e39c96eb7d691b34d37ce37f1eead1cf217e25ef34eecf3f7c60f84b8edfdde8405d" +
                "4f832576c61ef98e0a2f28da187700953924f686b94614705bcf53d33fedd4348edddbdf28b5065e1f207750" +
                "43e85cf931f829179363a1a7e7404a838ec00086b0976386fe637c98244757e3f769ddd4467471bfad670f9a" +
                "05f8246ee50a7b1eaf87fc4069c3ae2aa2033258117792f0bcd49e083fd1bc7496abff29cc94e4868b21214e" +
                "d316525399a610fbdd4a80e7c80715f29578e2a84bb40bdddbd9f47a11b6e7da118a1b658d359e8aef55eb46" +
                "b5376b5b655979984a922beebfc59bcd600d5309dccd72dbf0787db8ba757b537c1eafd5c0f50ea4bc958354" +
                "9e2829a42c28cac248c96d78124c47159b18aedd754aba17b19d430fb78f633ea9d26f54a9bd50f8d8f6b735" +
                "94f828976e7ea09c53bbb9f11a56c9507fb89b9a5ebc037a37267a95f85b8d64ca97192b10a66f417b3f61fe" +
                "9ca57130a48fd925eae2ab5502d571c8a51903c1d398f4c1f76a7e11743976afdbc697f23094a3cd761ff968" +
                "5de32e09fb3c28add453490300bc7c89dc01780096071722945775f264e1b0623bcf4619c712c838761205d8" +
                "7691b75ef360196cbb9e9b92a0d4c4ed62326e5024d77510b8ee2c7426cc22eae209dc9f13bde6bf08f5e718" +
                "1bd3b459450b451a51539a715c21d67dd330eb5970db00d9edbfb2822b036fa13bafeb86d8dc78866e3f8d43" +
                "e53d78cca5595a6faf886b5dc112f1cf4adcfa875800d90b48883af97316fe1506873fc157e570eacbfd2228" +
                "68d14234101966afb6bf9940829253a953ada89fc756b6a849f70acb9838e69faa50bba75e3e89c2adb57e86" +
                "d088ab9b04a28e670709172243ec5e0008a5ceaf3f8722f487302596ffd755ad1b82a49c34b3469515b46aa2" +
                "90cd86ee38ea7a9be3f103610335b531cca333ddfe32b14510f4b07ef95fc6684e8c454a92c10dbb5d59c7a7" +
                "c63fb305fe881967d99e669eb632840582560bb403431d40f75a4954908482278292821f4ea91e42e78fa48c" +
                "aee3c836146dcfd738d117e92e9a15137d28e8e6a4b4622650cb413504cb3a335d44beec5746c1c294b1e8cb" +
                "99cb608d928f8ce3563632c521f23d13c61a8f61c01df8c96c7360db4f3c68aa5d2fdd342a62ff3459c11638" +
                "9421ab43e8584c45882b50e6e4e96db6f0b8fde890d5dbfadcd88690b449e64240ddb2023747f308363e301a" +
                "a77757169fc6150628d5920b5aa1ab1c8cbf44cb00e025d7879d72b479e3af5311c785725590da9c89b9fc3b" +
                "8450769554eb44d203eba2bbaef9cad2237011c2ea44eff00f299a48ffe28ca93ddf85f76608242ef8d6cc24" +
                "610a1e2078fcac4f9385c314905ecaa82e553916d94d1a7c1ec652aa08897083daa2ebb1775fbc471ae27777" +
                "d7904ea9f1b92bcac3d8a3158426087b645b1108f0d65fec93789c053743ca14fd63d05e98b652df2b9c2ff9" +
                "ce05f1940703ffb273f80e0e2732eca9960d981b4cfd3b7bb8045b3c3830546b9dd8db0d3b6a27bcceb6a42d" +
                "62a3a8d02a6f0d73653215771de243a63ac048a18b59da29",
            privHex =
                "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000",
            payloadHex = "68656c6c6f20706f7374207175616e74756d207369676e617475726573",
            mPrimeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4134342d456432353531392d5348413531320045e21ac849ce0a75816c44fe833a3813eaab74e722d23d859b" +
                "70a2f92bb12e48b1530f6ffe4b9dfb8a48bb1783752ce44afc5af9607deb5af43fb2baf4fc76ea",
            signatureHex =
                "7bf842d2aeec91023285382a0ae33a7fbef2fab0c92372dfb9b220f95ee192b6a483f66de8027a387cb3f7bd" +
                "8cdfb49b1d50d6dd8c04bf899fa38923dc4e764ce850cfc0e6642040612d548a129594a428c7aeb03b5779ef" +
                "37ab15022d50494d740a4342f7fe9155e7131e2aa9af637faba2d3a52f94af4764421a4cbb9e484996a32b5a" +
                "98deca539b6c30a496ed3d98c7812de1b035fc8ec9cb13a24705fa00cbeb66c372a6701574f72484277a2094" +
                "d7232164ff466dd3421a4e54ad55937c73214643cd205790f50da8bb30256c6bd85d422869b02c7d765501fb" +
                "306a74b16b70c0dc339a15026181abcb4cae0e00b88d94a732988e3c50eeff8f00207eeb8bc1af4a15bf0328" +
                "160fcea5449b65515969f27b0d45515403166cce0f34857f2db9667f0dcdcd7218aca465d4c0d30f903c09c5" +
                "68f6a33ec4da71a9dac435f2c05acbda1717335d5fe183a468321d288401ad1b4f0d5d5f8b2450787de3be2c" +
                "5d0a910d343d4e123b364f09be66334d6f2de011de4bdcefcd41f0ad23e40d5b54fc62a62519f302c6d6bc00" +
                "96eaae6e495d49b5550b91d2035ddd7ad5ac528476267420bdc315ecf1f7d256c0b7018cce2d4df032ef278c" +
                "99af0298cb5782760f076a7533acde36c349c0dd9073eb2d313b14aed75f47df6bf4630cb3a94d6f83f26f09" +
                "139199fcfea3286eec1da738d8451a4fca5fd02deb719bec2864bcdbdbfbc727ab94f7b6fca8abddfc073695" +
                "ba1676fbae7950ba4f853a454a30441ff5405b306326f461caf9fc5d5a7dd31fc8282590f4196290eb17a987" +
                "87a4df7cd3537870056271c7c300c2436980932acab502607aecfb3bd371dcfb5d2707a046138239e487247e" +
                "12f7576fc7092c3a77835a0e32b06bcb26e21272a03898156dc55c4c751cee57b3662478884f5d711abe9793" +
                "70a52f5b7c292a90ceca4fa2c2fda5aa0bc87719080e16d3617a7f29776a7f57e6b4ccecdceb1db8be05d948" +
                "2ba720cb0cb586b3140209ab51f0f74ae38a0bd141bcf1bbecabd2196af08dd18aec1377d8bd62885611c9f4" +
                "e3da2c657ec47649cc9cc310aef02329589cd1a6e0d852e1829e37cf4e453451818155b981e46f8f8efa3297" +
                "2d5b4ae01561b1ca50dc550a069108bec43c2fc63205b960f191b6a87dfed40ad5d0478109314c609db6634a" +
                "414a6da9474defae417e008c140977bab0165584b9cd794763946163726ac885fd45884be8f4a0a34213e447" +
                "c91722a0268887f2e6dae5b74b82761ac21ac7fc3760818bb1dbf42937459967a454f24296b9bc58438384cb" +
                "fa4cfd4747ec124acebe964f99a72860b27e812ef8020ff28485adf1b81687e4d157a3f8604cfb7b16af79fa" +
                "3f7e94cbb07724809d4eca6ff5f9bf3103c14cb6c7801c609c4f63ff53cf0fbee3dc551e55362580b350c8b7" +
                "2fdc5830319ee806a6df4f8315b3ea7e49b59a7019b0533859eb11a343e03eefc646e532b35ff794c068c594" +
                "6fd4d9a70da948eb4f355536940292e9acbfe250952ad0fa082a5c7889fc0ca53af6d538cf8585346f1890c1" +
                "b6fea61095cdf7bb2bc18f7ea84b41f7c1795755a63115d4d4549f63ef31bc909972b98a36bdbfe502a5375a" +
                "bf04f32b1ff957b65d70069d0b2a72218fda913c2b999728e2ad3fcfcd9e71aef1395d37fbf56ec83432ef0f" +
                "177dec6e328bca2455275c9db5b81c29726263d96a2823c72d0026100bd6f5d0a596ff03ef0a1193953421c7" +
                "a6a1f3c9472dcc1e7091c6fc07e77b5b70e64e3fbe98769731de15993de329d69174698dc3c5513cf8ae10a5" +
                "b96f4f539a58fc791969614d792c3fb5b710187b38cd08a2c5b3dd2d7f6424cb0d72a1af458d0ac388680e8a" +
                "3a4c005a00fb5ae34e23cb8284c14abdf8c3e91be32b805f861a70c1ba1d1efad90c8c841572e26cecc4e481" +
                "680ef33dd41186830e2847b9fb44694c33f431c7402e054bb566133b704a851c5796561212acad6a082357a8" +
                "905e214c940a8cc90fca25b800cd96c96e1e53895bd08af925db209e3a3bb2a8cce3d753494d18c9d856efea" +
                "5084d7bf9a0c6c1096a89a83239d5bd182864834788c6f72356d9c54f439f399268408d9f364922b662054cb" +
                "ca457b3d5ee9fef4caac65896b8a39b114b90ff5291aaea1b75187e538b8fdf78f11ef80ba95a1c384687485" +
                "6e60559a16eb5497d810ba46053ceee6b2b215bb69879a604ba6d99cb7aba7c485724436cd13ef97175a7de0" +
                "8e8ae47b0f3a50293dcf6b3c69325a11df9c03b51e405f271446444b05b5bda324fee4940c15341b6a3cd76d" +
                "992767a0eaa5d8d1dbeb0bda853d4e8023c56409e054cc5a020a102607d827dd08ce54a45b4b97d85b491b2a" +
                "31f9d563af92fab9de2ae20ca18d81bedd0235430b3c2bc8cf6bb81373d2e4fdd21a4c5a9350f3b83eb88afd" +
                "f8423b9005795ca0c7512ff1a849cbf5b1118409f4330bc7b09879233f79b489dfb2ac50b660ffe8246677fb" +
                "da1dfe2b828a74a225357a5c2683302827317a31ade37320476f4d4afa7abc65894539e2cda388ee1a917d80" +
                "2afb3eccedb715d596243017baf1775b49b131b213cbcc36bf42dd63946f6d6c477da79982dee0bee695430a" +
                "5d9847326a8bdbe1f1293768a412340f280e6b360f86dd2bcd425b7f9238068826ea21d14d558eb70d884e5a" +
                "471812a4bc1cfa900e32e80ce7b2a88cca93cd5db80e899fd197b5184fac8beacb4dfbc6ce3cdc1d3e8381ab" +
                "6474b09973dbe69d8c9a2083671f1ebaad40b4efde4a22586e8747bd231ea427da9c3e9a4cf6eac893ea82bf" +
                "db84dbab6d7b60bd2819627c00e82e8292f4cafba88935b2527829e479e7be7bb4736215b123fa750f98b8cf" +
                "f289537d100e436bc9b4643caaff0983d133ce334a416459af68110b0d5352b9aa8720dfaf44692b57382a39" +
                "4e4bec301fee4ed8b7db430a86d224b7dfa1e1dfa234538852e10f7c3f82d044fe73c8008aebf761e88a82f0" +
                "1bb8c57c6cf5b3112728a3f7dca160d7b6bfa91b337d914ee3166f554689a95a2331b3c9360801de5546e4ec" +
                "496c1940cd23dcd93598dbb73d04d8341f806637972c24b89e9f7f85caefc77ff086023a51fd670ac7d3807c" +
                "1525cfa0dbfb30900206338301f60a93ce6340a98bad1f0d4cc986079cc9579a2073f878c42f9d259d0ca956" +
                "2f9010f6be24d0040ad44ac9eac55bb90f8016486e7cf204a071b4460adb74c7adf02a27aba12e87848e4336" +
                "ff57cb5e9643e48d842deba1a5d3bfe983a00c19163a36a2bd48628078300cf990f2d0c8905634b07640d2ca" +
                "2a3e3dd7022b7f8a8b9fadceff0c104d555c7e96a4bcc7ecf9fd0914222b2e4269788a99a0a7b3e8ec0b1423" +
                "33415760969e9fa9b4bcbec0cdddf70000000000000000000000000000000000000000000000000009162537" +
                "cf4678188930aa96722ddba92e7e4ccee2b4283e90c186ee88b19822430c715b5bfa062e3c144c76a71a8d7f" +
                "744fd4c14b7104f81d731dceac646ca0f80ce90c",
        ),
        CoseVector(
            name = "Figure 8: ML-DSA-65-ES256",
            algorithm = Algorithm.ML_DSA_65_ES256,
            kidHex = "65c28ec7cdef33cd",
            pubHex =
                "424b2f267e58d5b3b44d71acfc6a656bb26950d57c61db1c880bcfa1feab443f0942ab8bdbad7d708abbc356" +
                "078f6d99a252271fe62c74091eb94afb9b9264c50a888e0dfed80cd5fb2cbd3667e60d539ebe44930219cd4f" +
                "aed15dbb3455a264802b9f49bce42ee7550feffdd4642a55ade693868a460cbec03f4fc99a4e30bccffa8a47" +
                "5e5395396674ebb81a94937587880f6dbd27bf1c4f5a9ee43cdd8b0e53b3b7fb49c73adfbc2d4f8c54303520" +
                "c29bf97e26ee57db342d957c893936522d0942b41d82ee3772a00570adfb545c1143922b0496f826a0a97006" +
                "4b36ddf534b5f8e1c1cd0b5565ea846b45431f0618143ece89777bb3f61179ad20295fe0a6e062ae6eecbc2e" +
                "f38f2ac1a22dc93b7b126336223c55b61eb8c0795542bbb2dc65e722eadc6866ffa9683beb8a999ad7a83e5e" +
                "6e016c2e4c35f6f7649ad3bd52ec67ec1c5c6e7b9972771218be9554bba7727f0b84c44b9b0a8bd831fcff2c" +
                "9779ccd4ca30c6ad75b04983e41de893ee5f39ea7355180b709c7045c22d33a083f6ae07a114746d1bfdccbe" +
                "e5b9043879bb5a2e120e2a4636283f4a1cd4924a2de6a4aa3d99ddd88f48aaa4e88bfd1ea769d82c10779f2d" +
                "ed796db542971ca289b76863ede5997b7e9ce183b43ccec278b10d92b87442ce0435bb1625171db5554b4702" +
                "39c50d2a0c3a41b2a38807db070b47bfb3e7d10f3cd979d69963c8d79f8029cc4a48eb04fcb3d708844febaa" +
                "8b6ddff01ab64d59358e6505c4ec1d7cbb14ed2212df458ecefc03fe03037b1505a4c9444322f5f98dfa91a4" +
                "cb8c45860a2dadc7515350bb6d431e49a6bc8f5ba956e682b0e513321a97d1962602891c9078f62a8a9646a3" +
                "1387a6f09684264837899e0d8ec7d11c565901298b20b345081690eb4c562c1aa3a25bef06566cb34c79bc0b" +
                "25e4095d6ba793e81311e41a3329152686f00d4897f84fc4edf4b26d545365785ead8d63aef64a87c0b91a2e" +
                "5500383956cdf5f6e37cf9d5482d1c8e3a5be38f17259ac45c9fa1c4bd3bf177d312ee52a6da023c05722a87" +
                "38274dda8d1b04e99831cf57c87282a256c565c296d0524a063a3a41a48a83009978d98d8abf61af68e8013b" +
                "594fe151d9bec199902c4c70b49584201743c6b53103d2fd24bdf078dc90b5a188b4f8d772179988d0416c94" +
                "d4c57c0860b9d7b53d4cd261f332a1851565d52ac37f008747cafe320f363d9beb6e4117db43fd8aeebe5e0c" +
                "e2f54e3f0367eb3cc971bbe0c301a8e52f96094936035c6ee3ca2d13db483a0dd04dc16247de0e0894ad7cb7" +
                "e1ae7ebd4f8f900582b20021e77f70254501c6ac3dd15d43bbb7931c5283244312158c2eb1b3e1117e194f0a" +
                "1e4c783efbc62c9f81c21562d0d34a5f042b5eaaf32f31f95c5b055f4e7a2070fb096f56c415549cde74f386" +
                "4e8b9fc27e3299724b4639986044b55928fd6972785b280c25a3e21aab814ecbfb0c3cbec0914907ec907f25" +
                "a1d88bce3d319ae8222a35945db62af7cc75cd29c1f5d98fcb93f750dc3031076979bb51dfc37d23e8eea780" +
                "73a24d3e26c68e7bb10e459f2577b90080359ae0aec10318dcd9e0f9e34029c31b3e54b1855645db42061878" +
                "3346dad5b55eddb4f977b326a655525ebe2195eca9cec38a3c0d2273b77d3e68f1901c2ca5149734a51177bc" +
                "b089476b18cba09fa8b9b46d94a2946f358e1decb1998652c58a90852423e2c85e79d19724461627e6390d1a" +
                "81fb1a72f9c7edc4bd747dd5c85217b5856141028414ddbe71458f0a0b2b589df2e1b051783b8f718676b1de" +
                "fbae98ba496c2a935e92eeadea0a8393ef59f9e914f0743fe65640ddf9981cea6dbdd957a534ad4e790efc97" +
                "4ee89938ad99d53c5b680775399326834729bb37b082e795f8d87f52e6c8a8db68e515c277bbea82a7570d42" +
                "80896c987a0608903e306c632a223c55f0ea3682039c4a3f5440f4b5ac3e6ed2b2dc900cecc72b72f50e49b2" +
                "629ad30f0487b2707b86286f8c4f55659b25f9bdd7a6af460cc3c57a3982663bb717461581e196894929d841" +
                "53d87a7f482d284b5b894ce1a78216b2a011f2b88742cee52d5133e8fe77edae242f5af91637c37ffca32430" +
                "509b2fe4756303a9a3659fe32528af1e10d8d43bea991b2d109786cc66d35b1d78df254b92cdaa40f91a987e" +
                "4a922ca81050e5bc3530ca85493bdf2a825374d0a8310a6860284ec3ec732326eeeffc42bbd42bc91b73e5e7" +
                "c6b599d016490637629f3876c3e42f8db590e66a85a7838c818f78fffb4853cbef09434989803545dca87657" +
                "cf7c7e7e6afa71382bc10fa0bb6480f243eea1b861101006fa0cff3275621943cc58eb4dc3a0428a5e425670" +
                "fe82268de71c511d8ffbdc11b0d0f961120e971015ad5f448886b802e3fac11672319d487c84f1001339cb96" +
                "9784cb57344f2807f8b425f1d73caf8496d742ed237f4c9fcd5a4e84fba7e27fb1a8ae12c4f0427ae24e910d" +
                "951bd8c35d61f8a678db01caea8ef789a95b62ee1b8c5d32c6baa536ba88a1070ea61aabbf59294e3f6f974c" +
                "4c91cafc5bbf6b7ecfd57a18fb7557d71e06e900d281b0b49aa00feabb35714af33870edd7ac2393d93177f7" +
                "9ee5606c9df176f025ce49a6e5ff51a2a412ebf86ac0f40471c96ad4c119df230be6173df530ed656cbd8069" +
                "214741ecdd0271c603fb6c4a8614ff878d33e726cac6693e938ca3fba82c4995c14a2d4af9014fe4c4c50b79" +
                "4cac596b52189f66a7106fb325b526ea046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a139" +
                "45d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            privHex =
                "0000000000000000000000000000000000000000000000000000000000000000303102010104200000000000" +
                "000000000000000000000000000000000000000000000000000001a00a06082a8648ce3d030107",
            payloadHex = "68656c6c6f20706f7374207175616e74756d207369676e617475726573",
            mPrimeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4136352d45434453412d503235362d53484135313200ca2b702903acf278f47a0ca5abb69b13a60605a055f7" +
                "334ed9c6de25e1ca63ae2bc4995cc7f9279eaf70c25a1f46a77b90756657e2b1fc623ea4bd79c3846935",
            signatureHex =
                "eb31a839b3b9a2de6740077573d34b0f8536f7bc480f42a07e44eaf3b5efa7836fb96ee559f841693beb99ac" +
                "8c458343f233640f6f54243cb93840a13959e0e6b0dd1b34891b56cc126ee9ac4ebac54ba28a8c0b676266a4" +
                "543abfa9f00d403fae8323e28b11d3e0017274bad65ff2a322bd36b86e8108cd9704e47d70d7cc928065cfe0" +
                "c5c18ddcae6debdbd90e1aa08b2101adbd648399987aa2aeee60287397beb5c3e83b1ad102a31ada5c7a32b2" +
                "65ae5a7dc6643f6591d4b3b6109c7b8f311d8de6c53efe6100110227106de0475d202de65a6d5cad520909f5" +
                "0cf0d117fed2b65242014940de066e9d02ae4d56d3b462c7500035911c96164db7b101d6b68e73babfaa68cc" +
                "435af83f98d2284c99831e1d1811059153cf25c6300bfd73a6549bd512f095f3a220530c41090ec52f6b76e2" +
                "9b3e6efa5f016ebb5e351ce24f78144d306fd947b921fa14afc10b107e5a801412a5d2332a1eaf2cf7e993c8" +
                "baa74a75a26f118ca44da3ffef9c1ecd9d5cedc29cab177a1f0ec58acf6e54d34e57177c63fb74e34a15acba" +
                "f1cdec9902054f749492c798543384de2195bc565002551b52c6d5c5069a7d3c8e4fd9cd4b7d32ee57917195" +
                "2712109efdc2ef4f1929a69bc40c976d6af90fb956af77b891718dc491845193c14549782f207d70baa59d9a" +
                "d3fe201d753e28eb8756a0572500d74cecd1fe5503428e5a925fe09ab42dfa11421be6fd88e96de15bac81d6" +
                "fa8f7e1390e9671d24540c26b94f2a78f37bb1a8b9ac908defc8f2771297e1702f10542e6d922ad8332ab164" +
                "55fa4caff5c6b246ff8fbb378dd966dc436e03ee22e83e073e60feb916c7ef1be6f424e5d401423a72b7953f" +
                "7705bfcf569355b20a9fa8ae340738e98bd8a62369774bbac2a47b8f87cb4a0a4f5e82cc6214c17b517f4ccd" +
                "556f29caeb8c0e11acf8d0183e44e313335821c3fcf992597181127d8254a2d74a20369fcd51fe2a5f309815" +
                "dee9f9a307808d624145b09b45fb8ca4e14f716823ba465f160da727783c454d0aca565c56226af06bd0634e" +
                "969bb950d9fb96555bf8814ab28593f2d4b4a9454851f7013a4ea7d46d781e219484a43ae4d308c2f546405f" +
                "d750fa2fb9b187ed18f9b7d1e52eaa58d78a49a0c0ac390723f975d96277df4a0c0746bc76bf4722c84ebd11" +
                "aface18fa9269a7a95a9c6a0667018f78583875d30691d2043437289c1480a77f6dc55138a9eceadabf71737" +
                "69c0d1a4e65091244120e51a974238e0ed3f79eb879f67f6066b3fa40fd9e0f34f55e4aa80edfef9bd8ad073" +
                "a8e681ddc3cb3b35947549aee8fe3a241ffbfc0cf10cff212c69e7f8d4074ac2fd89bd4b6111be94c1cf3abd" +
                "41dbcc414d0956e01a65f7f684abe574dbd4db5fa4afad83364659577debbcec6fcca437bffe9280a26cae01" +
                "9c01b25d393ddb46e2c09e3680a3a7bbf4a5908867e761e225a6fb9dc3a9c2226bc68b3849e218b936600a63" +
                "34c1586e152e84ad68ac0055ff6142670f2c51a47ffa4dfd8e84d75c289339f0491f9eb8db2292b4da6ec54c" +
                "3034e60ad4adceaa7dfcc349ab0964865469a0813de15284d23d101d02a03b16a1807214b708d79625f86e94" +
                "f26b234b5640d5053df76b00b9cb3cc1b46ad08692192f232e1c03dd55d22aa6b3ea3aded2c70a11c4c8dfc1" +
                "8f4b85ee273d07a9297b5f1c104958927b23733086df4510a1346e34a01046b12906857f8f789d681c6ccba9" +
                "7526fba5a9e742615484b6a0a32dcc307a5215e9d36be0098e4300f29b8601f15e0d0a6facd899304cdc3de8" +
                "14e3ec9cd2f294143c8e5d56780575ba06ec1d34a18df398124397a8d879b59683068a4707c357f1bede1788" +
                "569651f2f7ca06beea3096bc5463b4088702c74cf978bee896e0d99c2eee8ae2320d4003ccb4903dcf6452ba" +
                "f318df5d9a94f2d9f30ff37bf77a9106d680d73e835b6f23ed8105621e6c8f0e6b02ac5866e439ff3d2ca4fb" +
                "32a9398d5a04f507f5f0d5b82a5284492768cdf9a89fbd854c28a459604ddbf6f6586e23df3cea929a96396c" +
                "ebb03a4321773d23157805edde975290673d8ea510f071c04b645009e8b20bafc37324525e550519d17e1322" +
                "53edd3e920b85b71c877291e36710b5b57933ddf718315c1a230f284b3a1715f858922b37f6795089ec69874" +
                "fbf3dd2c544f77fde18775686d85c92a8fa52582fa5f444c5bea0c131c593d8ac8b0bd96acde9b9abaa16a05" +
                "be0bf604c4de1af77ef6b3c9474a59532ba10e8d4a05f8d9ec7a6fb545c7b6a30c5a83916603a016977c65e2" +
                "b8b4583c24489da237d153c0bbd8ef1009604ecb988cf319de06adf1d24237642373154f2d6723161b35ecf5" +
                "d7d1b4ba6bdec9931681cf6dba67c7fe7def2370b7b4f73b8eb14d2042323843e69761f015940120dc7edea9" +
                "ea1d6ed4a4a43f4b31e7e69291cbbbedc615e3e2ef453f65387e180686b08de8cda0a9f761e02c4b76db484f" +
                "010908f6f97d73c2154f6083abf12c907415d199ac9df6a157d511f8e2f5d1e20c36ce7da69a8457263eada6" +
                "ab152bc6928b3357eda17314dec890bda5fe33f23d0dcc823480bacb02e7b56a68381216f0575cfa2491e68a" +
                "fb88653e349b75745b0fa711360762ad8bab39f7e498c65c2ddc796b156dbef72ccfa3bca4789060ac33f017" +
                "9c0a925c4697b490523066675bcc40519ac49756832fe73b948af61af48ea20b775afa4e4880c0cd0001d9ee" +
                "831e7e0baf5fddf12aba12b5c001108f16aa342bd1df76ea2f8358e2e5677997c24c1e467906da82c0307dfd" +
                "a6b8c40c14218c9a98057765bd154b1e6083ed7e9507e67b97d8356cd58c2bf8f68c95e5b15be7d435777119" +
                "32f33ae99a99f2329fef451c132651a7aa44a52d0ca8221aa6f8d53779c160b61dcb747863a2a43e96cfea88" +
                "c1203fbfb1e36a19b2bea394e9160bd9731ba5ce87509460aefa03396e314de7fc90d5135caba197cd3d991a" +
                "5a899d649f9f0335f72cba9885bef04d314ca20740681c0e710cb590494289a9b331536139b1cb2566bf3f2d" +
                "3fafbe8016350cb9938ee87e926bfb9b07401d5c244cd774103fd4a713d7080acd1639158201f17f80244812" +
                "433e4fcbb69975e83c9bc26bd7cc9bdea6fbb29aa52e92177f2cccec409ad1c5d6a3608994c6a8d265704ddb" +
                "492bbf5b580e2c04a8b0ed36b75c8f10569b06b7d0f007ee0f7162b1f7c3f8d1e7f496568b53d596df98bbd2" +
                "983337c5b3b37208e1ed4e2f5e9c7e73dd498e3a3400b34d32240e491bdff35ef7c2ae9e99f13ca5f7d235ee" +
                "bfc6bcd45695b47bdf482a792eeaeae048175d3f7a5d51f0dba4f4dd4fcce2300f7df8c76e26ab36686e73d2" +
                "fd29e2be23d577f7efb01549cd5462e69325db648fc09bbdec3146f3f80a7d0b958c02ad7276e8326d728065" +
                "70a85131bd0ac4d4bb7b3c166247e2669ea62d3bc24da1175f80dc726f29232e5f2136401bc53556add83245" +
                "4b3d1b54e22a944028f1c1d05f1741b519b63ab9d2881508a0ae1be515890fdcf8a3b82cd99565335d312050" +
                "1ae01115f1c0f266a90fb3498528d67b039b77e19f050a1cc1837f00f539f65af7e85c57fccbfa054355b501" +
                "a9f0529b401c86b5350561c16628c771a34b86e99d3941bc37beb2119f53020eaf71885220216b478a867e6d" +
                "2c39e5ad1d92d8c629f1e851e9b298f1926ecaebff6b57a7f4789cf1c8c3b9f87c289ef139997382759d991d" +
                "e9733161169185d546ff288e35584f61d44940c799e1f9bb8591b49a7f5cef234c454e6d97c8879869ce57a2" +
                "e30bad36f93a34183e25f0e60a8b9f6ece0426143c0ce94b9545854a8e7ba93ef5af604f10204de39608915f" +
                "1826a42acaa4c2ed5840cac6cbb3d65706ecec946d04f6c1923e1492a40d7d39a68f38f4c5baa982055b5f6e" +
                "419167a55ac8c9806fdd90d95e0f783ec4d90ecf89bc8a90689fd3116c4102a002714822e2754168c70a3cec" +
                "69ee0cda813ae34a0c01d46bf6102dbbbfbccf6b9c50fce710621fe2a330a0140577eb81dee01567a05356cf" +
                "7433d68368687b909e54f495382a478abbd95efd17537dbfcc514c43ec522a7c0d0b4829809215ba6a4b1854" +
                "ca9aacf312df139b931a1d1f97ebce2cfc6b0c713ab0c7a3c9b9cb543c9f02f316e0f9e813ebe130bfd6d4c3" +
                "c7d6c4c0f03940f07548c63226d4327119670c61fa954e31d0c4b47f9df65076a6a6a672a0fea7cec3de8fd4" +
                "0f1e3a3ffb83c3882c334408d55e788e0490b1b49c5ca75e2d154f8c507c2183cc7ceb98864c369f29b116c8" +
                "0e80a14bb0a1374c4258721093f386d6161ed32cbc943776ec1136b2bae812ab3f949aa85bac47f1a2001f39" +
                "acedc313cb8f2f1156a8181aa30d86a80a036b1643e5f18b51ea5a07ce06d972081cd015a348a6e69f963107" +
                "f5d166e673d96d7f9d574035667feef40096e9677f12a0ea9cdbbe99a23beafc33311fe85b58d309de9c77d8" +
                "ce1a4b104434db0c7e32719b7fc950b2a4cfad52ce167b6acc8c4a84a532a21513df7b1b28f6526351de2695" +
                "5a0e01924f4e4fb531f2d28a1c1e6312cedaf8d4a284a26de3f4ce7dc313f34121f8204a5f627c99c5edf011" +
                "7998b42f315b95d7016c95d5d9f20415292a4e4f5f74c0ee042b3b7791be0000000000000000000000000000" +
                "000000070b101620263046022100ae40ae810e648370eb2082acfcf03855c76825510f4c5a28367b1e14ca9f" +
                "e524022100a47db6fcbab3df76abb69b8e5c67c86b282abba06e860adfc513637ebdcca703",
        ),
        CoseVector(
            name = "Figure 9: ML-DSA-65-ES384",
            algorithm = Algorithm.ML_DSA_65_ES384,
            kidHex = "61ef765a64534160",
            pubHex =
                "424b2f267e58d5b3b44d71acfc6a656bb26950d57c61db1c880bcfa1feab443f0942ab8bdbad7d708abbc356" +
                "078f6d99a252271fe62c74091eb94afb9b9264c50a888e0dfed80cd5fb2cbd3667e60d539ebe44930219cd4f" +
                "aed15dbb3455a264802b9f49bce42ee7550feffdd4642a55ade693868a460cbec03f4fc99a4e30bccffa8a47" +
                "5e5395396674ebb81a94937587880f6dbd27bf1c4f5a9ee43cdd8b0e53b3b7fb49c73adfbc2d4f8c54303520" +
                "c29bf97e26ee57db342d957c893936522d0942b41d82ee3772a00570adfb545c1143922b0496f826a0a97006" +
                "4b36ddf534b5f8e1c1cd0b5565ea846b45431f0618143ece89777bb3f61179ad20295fe0a6e062ae6eecbc2e" +
                "f38f2ac1a22dc93b7b126336223c55b61eb8c0795542bbb2dc65e722eadc6866ffa9683beb8a999ad7a83e5e" +
                "6e016c2e4c35f6f7649ad3bd52ec67ec1c5c6e7b9972771218be9554bba7727f0b84c44b9b0a8bd831fcff2c" +
                "9779ccd4ca30c6ad75b04983e41de893ee5f39ea7355180b709c7045c22d33a083f6ae07a114746d1bfdccbe" +
                "e5b9043879bb5a2e120e2a4636283f4a1cd4924a2de6a4aa3d99ddd88f48aaa4e88bfd1ea769d82c10779f2d" +
                "ed796db542971ca289b76863ede5997b7e9ce183b43ccec278b10d92b87442ce0435bb1625171db5554b4702" +
                "39c50d2a0c3a41b2a38807db070b47bfb3e7d10f3cd979d69963c8d79f8029cc4a48eb04fcb3d708844febaa" +
                "8b6ddff01ab64d59358e6505c4ec1d7cbb14ed2212df458ecefc03fe03037b1505a4c9444322f5f98dfa91a4" +
                "cb8c45860a2dadc7515350bb6d431e49a6bc8f5ba956e682b0e513321a97d1962602891c9078f62a8a9646a3" +
                "1387a6f09684264837899e0d8ec7d11c565901298b20b345081690eb4c562c1aa3a25bef06566cb34c79bc0b" +
                "25e4095d6ba793e81311e41a3329152686f00d4897f84fc4edf4b26d545365785ead8d63aef64a87c0b91a2e" +
                "5500383956cdf5f6e37cf9d5482d1c8e3a5be38f17259ac45c9fa1c4bd3bf177d312ee52a6da023c05722a87" +
                "38274dda8d1b04e99831cf57c87282a256c565c296d0524a063a3a41a48a83009978d98d8abf61af68e8013b" +
                "594fe151d9bec199902c4c70b49584201743c6b53103d2fd24bdf078dc90b5a188b4f8d772179988d0416c94" +
                "d4c57c0860b9d7b53d4cd261f332a1851565d52ac37f008747cafe320f363d9beb6e4117db43fd8aeebe5e0c" +
                "e2f54e3f0367eb3cc971bbe0c301a8e52f96094936035c6ee3ca2d13db483a0dd04dc16247de0e0894ad7cb7" +
                "e1ae7ebd4f8f900582b20021e77f70254501c6ac3dd15d43bbb7931c5283244312158c2eb1b3e1117e194f0a" +
                "1e4c783efbc62c9f81c21562d0d34a5f042b5eaaf32f31f95c5b055f4e7a2070fb096f56c415549cde74f386" +
                "4e8b9fc27e3299724b4639986044b55928fd6972785b280c25a3e21aab814ecbfb0c3cbec0914907ec907f25" +
                "a1d88bce3d319ae8222a35945db62af7cc75cd29c1f5d98fcb93f750dc3031076979bb51dfc37d23e8eea780" +
                "73a24d3e26c68e7bb10e459f2577b90080359ae0aec10318dcd9e0f9e34029c31b3e54b1855645db42061878" +
                "3346dad5b55eddb4f977b326a655525ebe2195eca9cec38a3c0d2273b77d3e68f1901c2ca5149734a51177bc" +
                "b089476b18cba09fa8b9b46d94a2946f358e1decb1998652c58a90852423e2c85e79d19724461627e6390d1a" +
                "81fb1a72f9c7edc4bd747dd5c85217b5856141028414ddbe71458f0a0b2b589df2e1b051783b8f718676b1de" +
                "fbae98ba496c2a935e92eeadea0a8393ef59f9e914f0743fe65640ddf9981cea6dbdd957a534ad4e790efc97" +
                "4ee89938ad99d53c5b680775399326834729bb37b082e795f8d87f52e6c8a8db68e515c277bbea82a7570d42" +
                "80896c987a0608903e306c632a223c55f0ea3682039c4a3f5440f4b5ac3e6ed2b2dc900cecc72b72f50e49b2" +
                "629ad30f0487b2707b86286f8c4f55659b25f9bdd7a6af460cc3c57a3982663bb717461581e196894929d841" +
                "53d87a7f482d284b5b894ce1a78216b2a011f2b88742cee52d5133e8fe77edae242f5af91637c37ffca32430" +
                "509b2fe4756303a9a3659fe32528af1e10d8d43bea991b2d109786cc66d35b1d78df254b92cdaa40f91a987e" +
                "4a922ca81050e5bc3530ca85493bdf2a825374d0a8310a6860284ec3ec732326eeeffc42bbd42bc91b73e5e7" +
                "c6b599d016490637629f3876c3e42f8db590e66a85a7838c818f78fffb4853cbef09434989803545dca87657" +
                "cf7c7e7e6afa71382bc10fa0bb6480f243eea1b861101006fa0cff3275621943cc58eb4dc3a0428a5e425670" +
                "fe82268de71c511d8ffbdc11b0d0f961120e971015ad5f448886b802e3fac11672319d487c84f1001339cb96" +
                "9784cb57344f2807f8b425f1d73caf8496d742ed237f4c9fcd5a4e84fba7e27fb1a8ae12c4f0427ae24e910d" +
                "951bd8c35d61f8a678db01caea8ef789a95b62ee1b8c5d32c6baa536ba88a1070ea61aabbf59294e3f6f974c" +
                "4c91cafc5bbf6b7ecfd57a18fb7557d71e06e900d281b0b49aa00feabb35714af33870edd7ac2393d93177f7" +
                "9ee5606c9df176f025ce49a6e5ff51a2a412ebf86ac0f40471c96ad4c119df230be6173df530ed656cbd8069" +
                "214741ecdd0271c603fb6c4a8614ff878d33e726cac6693e938ca3fba82c4995c14a2d4af9014fe4c4c50b79" +
                "4cac596b52189f66a7106fb325b526ea04aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741" +
                "e082542a385502f25dbf55296c3a545e3872760ab73617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a14" +
                "7ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f",
            privHex =
                "0000000000000000000000000000000000000000000000000000000000000000303e02010104300000000000" +
                "00000000000000000000000000000000000000000000000000000000000000000000000000000000000001a0" +
                "0706052b81040022",
            payloadHex = "68656c6c6f20706f7374207175616e74756d207369676e617475726573",
            mPrimeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4136352d45434453412d503338342d53484135313200c79ec61899152e19a013da80986c8027c0089c4f4103" +
                "62d4a331558d420ed28b8c2c60b1befe5fc27b2ee0193537feee42bdd049245fbff880567dfad8f46601",
            signatureHex =
                "e19443c8b83477e64086aa057b1110fdb9e90bbe28e7c372dc8ae29e584327dda6aaa480c02e0f9ebb633412" +
                "f3add05c2b6a9840452f99bb1b10d926cba53ab37c3854b2bf5637023c427552ceffafea21fbeb755d3a9a92" +
                "2975587deec03a4e6098cc21bdb3ad0b7ed57a420115f0fc2355f0408c89a0753d64dd2fea01563af19a4690" +
                "401f89bf38a18ee47285a32dbfc0d4883305f5fc11e8db301420d904621cebdd8a73fc16035a407b73d36ff1" +
                "50264e14099be9bb0ffcc59f95435d431c9be88869cc2decd63aeb61e8e325d245e8ec47f21afda4c5cf3c8c" +
                "bb1384317ed616f905b96ee8da01afdeb2f1f22ded82193673f0c61f94cbdb1dff918340fa49ff53dcc9c1d6" +
                "e9577012a644a96095fc6068735397b27ee626afe7a89f4a9711eff0c79a5a8a131bea4e634ccd2b97b98b2d" +
                "90b1abbaf3e2bed17bccc6e65bd39686fb127a16fd5bfcc7a6233c14b6ce7fd46c2dc993e03f61aa307b0499" +
                "e96bf4672d294bf3ed70efa71bd57fd331701cd01d99eb243d13d6079505a2080b89bfe6293a4a5b0c8bd8c6" +
                "e6c31e2f702c4a10c5f47ee050223bc3810751e584e6074f04b6cc020c028e1f55ee1c420b93e180a0cdcd29" +
                "b36117ab817856ee0f5f80c37b6ca47393d94f660579aebaf6ffd08fb8721c7339857706d9bd71fdc497e473" +
                "772c83b0b82d52889493e7a3cd538c4d35516da79a6136706146f93b8daf622d9e12a22a4a06403c22535928" +
                "b9ee88cdfd07bbd19a7144ee7d6ef10e3388eed8381e3836dcd8d423815f9cb50af9ab50d4ecf12d8b33a4db" +
                "a37661cbfccadc6acd25f61bae83bb8f297e6858b48f684d545a11b45450795977500b6cc2edbc0dbdea7c67" +
                "5954d712b7226119d60fc30288170d76227f4b076d9d7fcd8332ebbc8b6b90c0a58003b614583b5aad3c01a0" +
                "603d3e62a55acd7e8e2c14c9d068e0e496daec7ce004ce4497b3ce511cba697de4ca0aea8dd58a9ca67ff7fe" +
                "5107a3fc08f8462bc44c71963c15ede70ccfe22caed409849270c78ba630963716e5c394f02757eda3c26f6e" +
                "34aecde0fc51cdc06c8002ce9522b2e863f81d2daf464496938a76e6d8ab214021ec191599c8452c5b8fcd70" +
                "55c9e0fdf3b3959303f12b8284e5b6b438fe78874c480b1df8a482e5d25a97aeeefd9a5c7b96179301a96414" +
                "3be3e8628d5a93d3be7d2bd657ffaa0e4672f773c1a99aa0451286c6f408983add76a6e0773a54a22f248264" +
                "a1e1f555bfa0ae977896728cac175c275fbcf4a8d34a38307a2876ccd55363f8733a239e3d6db915ba6f65f1" +
                "d461aea8b957b0ab26821a9bd43f94c10440746758a44172e61c54d2d37d315af5476d391e2363308ded7729" +
                "453b98327a4ca5ee31886c0f46dfca303233b09bfa4339741b8523b703de76e724f9057dd39f131f63da4a5d" +
                "b930367fa47b0b836c6e39ea5c4e14f7e8654d21a92344c6b8ae460f2f7eb4a0e3885ddff433424a02e1f3eb" +
                "ad76573981db0d13f75e853f0ce9236cf2dcebdf225db589fe766620276b048ea4c43460f78ac911a4808c4f" +
                "4f9cfefd79a17566a2596c5ea62179245d8260b7b3abdf151beebaf653a6742b2a58e9f7739c72ab88391129" +
                "07347bacda6ef22433d916c5c33b727d6c5a51c7c944abd880c424736369b9c0596322a20c82d8a82afe568e" +
                "814ea4089738d63c3ee3c85a056a3b4264c3d878a3a1a4d10bc40763f982c7ac46ae238e6b9d43a8eff6761f" +
                "754d830217a94111f221f0854ab8d4c383192ca109dbaf0c25fcd88bdb90a0e4e779b212bb500148b566fe09" +
                "f2a2132c887ffa1b768b33f49b9dda443b45f254ef2a9eb141bcec85feca9e4bb609668d71e06318669292ba" +
                "5e073486cab6cf73ea2035129a3332c61047c6c13d02a079b85d1437973e465352f8d4889744dda9886bb4ed" +
                "99b28786e0a3a8253da9b8867e1e32ddcc5d7d8300e3d74a29bf35676eff7eec712008878ea84241e64de5a2" +
                "ff99fcdba1c4c7c7f787acf5ded6f4ba25c60501eb46a5b0ed2f4dfb99d4c031d50846f3e216baaab4408109" +
                "bdf7296825057777d81e24218fec57eabf6f3641ba68707e59de015aa3fe5a68df2b2938441155ddc8008e63" +
                "8605cb8acf53a0f83a3494a9128374880e50141a32cea23710bf3a32b25921c21afbfd3423faf2dd258ca55b" +
                "0617975e89abaafe1c6268cd55bbf84ecf091272a0d2d319d86a3b5da5924dea26bd1e369e8e972d7316cc56" +
                "03f7d59e121aa84936b71ee9f3a16496b5466ee57539b3772ea0cf5eea5b4b40a2940ffb08f3110692e72906" +
                "606a4c1adf7023945027d8648560d3eafeebf9215ce0ddacea503ed31f2061f4ccb6507d7e5ca14f9e7d703e" +
                "8f0a13ecbd25c7f69a6d39794d96f10dc6fb0a708e85ceee08c2d3fe89236b3bab1cccbf087b5e59a2629767" +
                "1d9dfdafb8ee933ea9ebbc198bf19a97202dd0c479fc5d9a7bc55adbf87cd08a1a8f60aedb885643e112e2c9" +
                "6ead81904077e666faac33c59c6d11ada4eafde16e0939fae1f6be3a7396fe6f9c84e82351fc1b01285c5a53" +
                "15571ac74b25ed8ae0959608cef6a06e4d8e1e952a26a368503b0a35cb0b6aa8750f50de4a76dd33061f25d9" +
                "db64f809de57f086fdcee1e8c7c77a9ee4c8748628b1ce306e31b30f2929d0bd0746afd7f46a6e1f99ec1118" +
                "552903c27cc14b1c669575e5bb99ca5dd621ec163bf461a627bfd762af3c7c5ce0cd0662ebd6300933a6734b" +
                "51ba94baea378f53a661d7a30753fdaae326a3f8e1ca099ca17bd45277bc8e93eb4d8ec0f94e321d23b13eb3" +
                "ef46eb7858ff8b7b441c989b14c92d930c44d93f7bc01cc3b986cfaaa5239cc3e2750c35da95d65f39654218" +
                "0fb2c633c696451c7378711472152ec3233d25764e077bbdad28db9fbbfa00eee54944b1a74eee78fa2b655c" +
                "3603825017c552ae03cfc832aee94eea6741b7537ff49d227b58e382e7e7aa3fdcaf94590af09c1571bb5218" +
                "29241244e2dfc46164ceaa67620ce69198faa09128c82c45efa43879a3c442f59fd5688d28f67208adbe8f1d" +
                "c70f6d2dda33d97094f364908a2dfe4eb13af36a765ef609443544539b1b22c7509200d7e3bb588fb140f6d0" +
                "095775ac7e63a2f37754d76d66d15174bd33ad4067916bafc536bdb368ef37798084302ba1b63f0e34eb163b" +
                "261527076a064bd5482bcd9671cc57428fd31d08a58d34d422538ffda86b863bfe3be4360a0c49e06bffbd37" +
                "e8e1775736ad44feda6cde0cd7e029109388fdf696a6aedb9e47152ed763ca4678f2c53b4ee9e3ec6970392d" +
                "b05aba6720249c429d7a96beca39a004b25f43546ef86f011e5b2516924eb5bcb18d4ddfe5172a75e1e54ebe" +
                "7b31098fd5fe0e05904df13d917b3ee76c7c737f67c6959f7ce25a1f402dacca1d7d665c017ba94870500b30" +
                "53342b6ed73f8c9f22e3170560888bd2bdbb34f46f1a147749f66645012dc9aec549f1535f4bc525e38447af" +
                "aea61a01b11e6b7955d4ee8524c96c5f920613ea3887b4d1156e3c4507e358f18226b1b627b45bb41fe0846c" +
                "2f9e0b2972a84daca2c5c4dce3900c6720a71cc726fba440e1dd3add8b524bbc8c4064c5bb39c40e567f6d05" +
                "fd834a8c701df592d01877dd86a08251e915eea99b54a6b07322fa05352bfc06369b0fa81200d62de4a5d68e" +
                "cff920a74834a65119f9b4c9acdcf4085ea3eceb7b5eb5910eaf8c50e86d047b0f7d2d95d84f7f4b34884192" +
                "755e204e16c02cb7c079e77f410d7783aa8db52d1ef56ec270ec90d653239a7debe1d77e7c5ff1d8fcab84a6" +
                "3f13a15e8bde8842f10d80f8754be8f66a7a1339111e7cf13ca50fa6438458af8b59ebc84e407898105a6d60" +
                "9893a2b350143ad93d0bc5590a37e656f365edc6fe97aa374b221e1efc2e9e31e689dc71ef5c40824cbf6948" +
                "b8408e3032560baf401fb22f4e07c3ed4b3d3f00d4c8b13c529f5618cb532d8e69cec545c2e27bee69bc9d2f" +
                "36969d619398f3a09ef125b5deb1b2e942cf33776f21a715bfaa86df8f60262109b3305dd89a985a21248e8e" +
                "3c16042ab6bc49ee0375255a2846430aeb869f6acfe176f929e9ca5f3f24b308ad3f7b81acf9ac1aca2f4a92" +
                "0615169c3581c538326416d4ce07f9d9ddc0f03b8c1982a953dabe2e26deda3d482893a59c35950d7713fedd" +
                "625376a38cfd48fc388e372a86f3e16804c52503ed4a71262fd5beb5cfef3881cd9975cf379546f7d4e0c1ac" +
                "0b51e7918250675d3142c9b7a3274566970b6d65529b18575ae44ea5df1ea1af31951b932d9e575ff4908f26" +
                "d114820e2846fd377c32423e573b74e6d7ed3de5bb3c696d81a952a2667e82e90643c49a4f7b65293277aa67" +
                "a7dab804f0fc47f824fdda01a4ed24abcefa3bdd742770166a9c889b34309b725e0f864e97319f28caa4098c" +
                "755df3e78cc565691fa7161a6ef264bcf913626c58b2afcf639a715ca459b4ec400eae315959f35b30cbb9e3" +
                "49c023431383186a0dcbcf0a04201da39e9dabbf37f65e07d63ee756f58cbac666e47220383e1060bc94f6ad" +
                "889f9b00bf6fea116232a95543ee905bc97aca523079ab8e247d8c89ad09d4fcdcffa10491b0b1d37487a3b3" +
                "bed0d70f5dd1efff1114375b5e81adb0e8f51c1f3334769aa50041d1ef000000000000000000000000000000" +
                "000000040b101a212530650230101234f6675bd6f8740e479d269dc8656157db1afb7be02c1bad2d02fc13ea" +
                "383e16df3321c6725430caffec9a7ad90b023100902abda179f4d68e6346ddd9df1726112d99276a0260ff29" +
                "9eeda4c20fb7eff54e5a16dc3b8654e79bfbc73d6b3c49ba",
        ),
        CoseVector(
            name = "Figure 10: ML-DSA-87-ES384",
            algorithm = Algorithm.ML_DSA_87_ES384,
            kidHex = "10da59a01e274d3d",
            pubHex =
                "e45ffc8cc73db885dc662e62a18cd8e3803297117fa5658814a985b5ff1db7b468cfc82bb929f1d86b77ed14" +
                "f5ae16a65368772ce51912410105e0456975ae91fdb643b512f124d5e60bd68b8c7e31fe01c7b0dc65ae4705" +
                "01cc565a6e1dfcfcfd12565433c4afedd511821e2e9610c45275e2836dee35ced69d7efa672fd1e4318bef5e" +
                "b6e897e8b451aa202ded042b2aaef77a7be3f699146da229a8bdb3ffa496445967e75217bfbc9048f9956443" +
                "d8731f833eb30de10dac96fffe7cf65ea0445c3e31e8601e133be6a100764fe3196e267726441f31751fbf9a" +
                "6f5880644f4e7275e57de2b0f105e4db055d50dd1c9c934fddf535b8de28b0c74c0449f222cd2ed0bb8fbc77" +
                "5ccee8c940665b40f712f4f7e00750e9e1e4cd9cff25d1945c3e9bca53ccd4f12eee7581856ebd68f2684595" +
                "6e3e7beb761f0fe75bdd31bfe2fa018113397b387bd59d62a68b8af7fa245ab932e69f778e2ceefd21304fbb" +
                "8099ea13d8ea57c1813197a2f75ae251075b51dad38f853669e9d5f98a3655098941993a1594860fba71fe53" +
                "0ee5c29f58f2978af688ccb75a5838a359c112e98e25a8583ac8dac1f861fd58e2afba5de5a52e020904f5b4" +
                "2bc0874e35befcf3e6119684768f36e008f04712177cebe627607381e56eaaee161c1729b8de51dbde474d48" +
                "cc68249ea27162b87993e60c84ed6cc6423cb3676d9eb50b2cab5a3a049ef131381d623fa6fbcbc9db1e7cc0" +
                "25ea0418b9dad2cc6ccd4e95fa2cec24feeca70318a751716b7213f63edbf65a63338357f838f94ec071822c" +
                "24851248885107b3d1c4e924678c7614ea1af038104619f2ae372940becfa69e29cbb5ff6c3e20a47be4a4f7" +
                "4bac34c133c00a6a706accc6ffd3d8e4fbd69a99704e1283c850d8c58d1e5753cd9587b83c4c346cb9a58137" +
                "213ec10834c66adfe2bb5c501a8ef2ecadd1b677a3df1a6deb86ebf0722c4f5030e20f9018dd5b6fc53eea24" +
                "fd92b7b5b4025feae996d3e48fd4c650d82dbad7eaf936639698512f26253d2ef6847c8518e8565cc9a5495c" +
                "6fff57cde7323882c54a7db470ab2daf8ffd2bf794fa7c692d9e7fbd532eecc1d7880e2ca0b3216128be28b4" +
                "a9f1d151fac97808b0bd98b7b43a612a9ac865812bfeac6f47460277840b52a3b087f916ca7cedc0f768ea2b" +
                "d19ea21155f84b4a04c4000ad2ae0587154d560bc0a477a4f9329a8984dd31eb1f2a05e3d918701d630cfca9" +
                "af61ef088d2c5581acb463e439902e5d425719e956b8d6df7305b28e0ff27d3ad0de2085d292499b19a3390d" +
                "4396fb3bac9a8d8cbead2a7a4290fc9ac6fca045f98a614a45a39cbe24360f84d14f8e472712aceb74dbf45b" +
                "53d49a0e4737e476ffc4d5b2f7cd247aa186d3b764ad9e9cfeee456a73c291d8de3912414ac43911c372173a" +
                "d7b472af35c6853ced2fe7b5fe0a89565ab33baa6f65cdd928319d7065e040e7a5e84f9aa903f7648094bad0" +
                "7136b16927b8ec6dbc2bef0cc2856de1e795923e1412c49f24deeb6c21f6c8a9765c9c7986e0da4b4c67d8e0" +
                "d0c8d466824fb923d8573148990cd2ef133c78ceecab72ed9dd285c5a3766852d54534207ffd34027f6c76ed" +
                "e8fd1a32d72c30048bbaa797d5df6fde27d087de5721ad7b7fa3e8d3f70d6bfc3ab2e252335368bbfa15acb5" +
                "cb37d4694e8b23cebe25de9c925a221a183b904d3f85df9929a919c54d6f87457373a0d6ecc1403e4cbbe620" +
                "999435e80696634cd1a8e4747e9825bfa336e5bbad14f73640f1b9febe800dbaefe1630c61fae635b074c564" +
                "eaa9db189c9e7302873fc64e6d497bc5c29080987a07a21d4af210703a4fa07f2fd816f12fd1e29b4c0f44af" +
                "e9bd4a1eaa8a7ae6f02a5b4258f52caf6127f62632a67cf4e8310be56a7c28c86b2e277600c3e92c8d23d425" +
                "86244c571e90568df202f2f6d81f860a565f9eb91a3c78372e2a8b1be61c5418cf49bf2d6c8955d4a482a991" +
                "9b7660b3f9a4404ffc454ea073e1e4b2689ab2cca4e46bd7004a6c491fa26ee7a57d60f35edb2b821e626644" +
                "2c8f335d452d524c772e0353724c23c7dd15b7aa155e91442022140c5fcb0153147edcf3e8952f6f0399a3c8" +
                "8066a72756c9409915de63f64fa797841c57c796c6fc550ef745dfe9f179457f94755ae5a2506a764f327e55" +
                "0be3dc14dd41f3b04b147d454938c63a8d69b2ea4c5710ec0b36e3a6c72571fa5d59dde036c42033df35af05" +
                "6966ff0cd1204008971aa6ba9fb97b685ab9ffa2a9d1778104cd2c3b326de1fcbc242e94d0311c3275b12850" +
                "ed30ceead3a2ee6d060508411d4396f5421d8b6d067cf7cb5e826785fbe119e05e21bd879b64f57cb0cd1972" +
                "c2815f20abe7ce6ab34d0f471af44baad179e90644122f5f33288e689ddddc5ce833e9755df1e73c65c5a201" +
                "c4ede2ffa6b19274927719d2d38fdb7a65aa43708b7fa9a94aa7d3210253d78d3b181e1020d0000bd0a1dc05" +
                "d447f9f58ebeb84c65b36c8afcb83727a1508994e826957a663b0b9b8a003325ab6d6d6462ee4e106019c0df" +
                "fe10323b7bde7d82a38f85fd08786e860ba66c161b64b0708c363de5c6af62d8db3c243d1e1b712cb1d59e94" +
                "2b9b6b4295a5a500b182cbd5fd1bc6ce9376d91b47a2284f1fbe0ad1c048cc2cfbb4afa3a9eb9697503b69fe" +
                "ca990eba7e9441af9ca44cb3ac6b5ed66e591c201fe30efa8a7c471dc613d6254c263a8e132104bec47f1aac" +
                "b3b2fcd4051b69b5e3fcb1c147a65c2f90c4b5188bafc521cab03c12a309da50b5a7517727ed41228ed123fe" +
                "1b152f6a6319cd623bf34ad7b8e064ab993260bcbd405f5b7fff9b2fa40ba5ed5630242539e5d96823e89dc8" +
                "18a13d16675ee3079d976f694f5acc9760ae789e9b3391b289e0e22a7ef17cc6a4577157b6d95c09baa4fd53" +
                "2e3ee0a290810ed35e56bb19d9b61fb98a97c617425b06093d98a5cf0ee2dd127f0eea600b9a0c67fbe761db" +
                "9b77e5d5bba9701da1b883e521a0cfe88451f57bd36085b67e56f061f84a2e6a152a71bce6e522daab6a0a33" +
                "ce22e537fa9793d28b617e6c0a4176a83aa3be578afac0f2f5547c5516d218984755b7445c7143afa4e551fc" +
                "e0071bdb873b34e6b9e2b9e79ed0c69d288ed6421f237e860a0c6492ebbdd2a44c2c4f368dbe99941b1e8561" +
                "d859d3859f496cee3d741f252973f8fcc539c409e35cc80a5ed6df23cc3a65601313f5d681fd9540c5291a9e" +
                "30a72e38c96413c47c61ff84fde78d011b01b4154d1b920af003f7abb1e1999dea6a766cf9fd2702b3ce0ee5" +
                "7af931b62124b0861b163a3b91aa4bea28076c3432df3b29b6c4e1ba588def420071fc157de90eb2722ecc9a" +
                "b00df3c669383a61a91bb67bd287ce349b4745ee7a479dbceef166b9acc412eb579fcd6437307edda253d606" +
                "b7be7599c38092bc52a8598480edab8b82b1d21c565d2137ceae0b6642619b16133d91205d6355029e9cdfeb" +
                "9a28b373d95916b6b707d4c712c09cf36daf1a511b2bedb1aa70ee58d46a0666bb287784b0a3840c589a7a04" +
                "d5d6f2216be90aa4a512d5632f5c9bfe7b8b13382f999b95d367c7c46b968074ce315197a5ff3545c7b77a80" +
                "4ade56a95b5c24cdece5937b5c0366d93ad03da9bc5db1b551dfb91e9b343d2b57b763439686d4a304aa87ca" +
                "22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760a" +
                "b73617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d" +
                "7c90ea0e5f",
            privHex =
                "0000000000000000000000000000000000000000000000000000000000000000303e02010104300000000000" +
                "00000000000000000000000000000000000000000000000000000000000000000000000000000000000001a0" +
                "0706052b81040022",
            payloadHex = "68656c6c6f20706f7374207175616e74756d207369676e617475726573",
            mPrimeHex =
                "436f6d706f73697465416c676f726974686d5369676e61747572657332303235434f4d505349472d4d4c4453" +
                "4138372d45434453412d503338342d53484135313200c840f916bd4558291c80b45b2bfb745759b19cf711b0" +
                "2abf84ba80d74b4021973d3da3a183e28ada2dfc047cfe759c133ddce94d7746345b844798b02db0915f",
            signatureHex =
                "0743f9e50443d761268d1a3062fdd61c399a63b387c837ba6553c54702163fff3e68315ab6e851062f186fab" +
                "61fe642db1ce79ab77016e030fb54f94e3899f7664f7447b7d24cfd3e606340d067022f1d46ce942d2dec890" +
                "354c77b713a2e8f2e4bfb90e9e02b42f14fa37577b561324553f14a44820327c0101d01ca8ed5cbd6981a84a" +
                "840a599da08e48e04c9187057fc0c8a75b3050b98dfc6411dae38bbed7c169f56cd06bbee756f0ba7dd57f81" +
                "ff4185e18a361912af9116899f4251ae809e06bf39082b852e364420c1f194248e04a03d06558d069c8d2659" +
                "10c7b7c38a9ecf376d7f3d3766e956d26c176f39dc7b94e8a79c09f8c59670784354a3f584250cf581af69c0" +
                "d117dd8d2a43336537bce03ec9c587b8f8576173f8dec89863e7bba323390716c77bced750f07c1857a59ca3" +
                "0d90c2b90d3b2f94d56a4f3681e712c95ea294a304d5e7b0b6f6f3ceab7699c135ed3d7b594dde5c8493a0b0" +
                "928745684e3bf6102f7a660dcef2afc0d17ef68bfcf2225356cc4ed1fb48a04c3487f55e1af403e4e9b6abf1" +
                "f2c7b19d3fe7506ab0ff2d2fb3884880eae0f54990f57dc520575533d5057017ae44c7142d722262710ccd1d" +
                "fc8002c564db8bb00aead7a79f01d7be3c81777b443495e7c3de7d3b04e868481e2ef43ae57b477fec6d988f" +
                "7fae78748cd8579e1ec4186eded0faa8e09787ecd53a25aa879e1686a559c97c5cdc029a698e694061ff9458" +
                "adfc5afdc4e8bced846dee11d39b9c875195c0681656f051a2d061762c71e6ed63c98a06cbba2a41294cf083" +
                "62f2a8ec2c7d62d35f1ebf0949198fa492d157538f3f7994b04b1595537e3bf2d3e08742c9e23bb42c6986bb" +
                "b3fb6ad9d0230a6c3210e71aebe5c8aad7bb757965ff7b3d247fd318d93659d72e4666ac281c7391c35d9e5d" +
                "1f22d16d6a7640d826e83ce77a8730970bf795de9c558cba3af01d85bc312bd9b5b2e674caf7cf0b911f9db2" +
                "c4330a8b1912df98f9e395566ce9dc657ed20538d696201894ee030d505616cd9e2646dc996004ff888c58ed" +
                "dc5427584b21946048a420123e83858047972976cc81fd4b13f044e22b74347abf1fa524aec1da8eb5fa61be" +
                "8df72141aaaa877d7b77c076a1935e51647f5132e06b7a9831a3127e1e8659f39761878ba175b685cd59fa7d" +
                "590dd14743be2cf1a16c34d3b0afbee0b2698f1ae0a192d84b7176b84b84c8cafe83ef5003bf515a1cc419ef" +
                "02e4ebd57306d5d3933e254f8e12b5c5a3f44cb1067c6c52bfa630164de69f54dd64c88c9bab4a48e20087f6" +
                "eda0d2678ec9f9e00bf95f4edf8775281d7a42f8bbd417c44149d02426b3636472bd0a3d16aae0849c1ee947" +
                "c9dea772a785710c3cfc487c00b8940919c4acb1869fc59ea30d56f6b404a4474bd8a2323aec1144faaa1bea" +
                "0cd4e1029c4563bcbd94c64da433cf2ab37829edb1484124feb32ccb7f3cc62222aab28d82797078315db245" +
                "2ffde2511a753f754fc306f3b13792848c547e645dc80318fce41b2a80d0b3d70f1576d780e21b201b0d4063" +
                "cc606764f7b52e7f689593560cc77b9852a6a3cb6cb749154d50c8b40baa8e8c998890bf82e3d788fdebb03f" +
                "126036312208cd9d81fac7c31d94820b0c443ec4d586f0fbeebd76302436072000df7f31789595c8ef33b0f0" +
                "f239260a64d8060ec6c5b00a15e4e45fcd6564786fd2868a9a2416ce35548de08f072dc6f4cee9a401f1e151" +
                "bb3838390f6bb11d3712c30487c317d17b52941bf3afa27eae6d267399986330ffa6260eb919e6b5532066bb" +
                "3230a30d3da8f3dad910fc7c5486455e83fda3e804caa108158b5b35f24f2a18ed9062f7b3c9ebc779a16f30" +
                "ead562fdd02fe7f27cb59d4484f6fb896c0982c1eab7f486e12b850b33a5869621efed828ce1c2215a08eed2" +
                "8987c0d275efbb3e5ffa40072efec95757412e54569440f9c7c36ac082ba4b931c4544abfa35b50486978f2f" +
                "9379eff7d583d8d57706ff7671da4c3670294c2053811c3331227c83d92a191dbdbf1f85abc89f9f7458ac0d" +
                "7f211c392eb6e6a31df310f166b80486e192060d7933faf5c5411c894416414fda137baf4fda728101623499" +
                "fe327935deed08191167dcfec5693a008ee440e8066383da3cf8c917e92c5147c190e4fdfaf1d37b8e9d0e60" +
                "83b75192b2ccb7425cb5f246c44c71ab1a5e7bb369637e86c847e1b71dffa9556384fd2bea02f62ca1eb439f" +
                "6f5137d87ff2d378ee230963796bf2095c6ec15c6f08298a966b610313917bb65fbebf02ade73856f1f0d931" +
                "7e346b373c85c9696f5a136cfa66bf74761e40f701ce49857b957f23045aae8a07d655f00b1ff3bda5752ebd" +
                "94f44d78d5358a290ae66b29ec2ede48c03338e3f33b335b26d5eea0e24719c1a76455c30872a655b0f10b36" +
                "006c0ab9fa099369f0b01a9ce01a7bec315b3f18c8f380fd032d95e18604646d4cc4316f441f32c5497ab9d1" +
                "2aecda1cce03db239f8cf1898b402365658bc84a388cbe6dff4d3efbc6c7230c1daee3ac6bd8263e5379236d" +
                "9da60fb4d3142c8a302bab12f7b4e1eea461f3a0a478221f3fad28675885ad93c3d19a3ec26bec877b24b99b" +
                "a0d61b0532d8fd9b96611e6eee589a9e9de126a43517ce4c9973dead361fbb171c02478bd2554cdc0bb1d7cd" +
                "24b59cd6830e4cda7064eb5fd902ec4562c82a6566a24421df32db662639f33e5635398e436aae8cfa562301" +
                "280be889cf35c2d02feb52f2ab04543f7e5b86bac9982cbb66efb699531bf681269d697ebaa09fe89fc93b1c" +
                "78fa51dcb869a417da941d96c61f99121f8d2b5e998ca971a3589896fa2ac1108c350309ea9efb712492cbce" +
                "e965cab35e0d0fbf6c626dd4fce1444e9eaaff4fdd0db474e5c9f5054fd84cd0ebd73819ed8ee4f43c9e1a56" +
                "53399da0b86e4dba3b13a9b60efc215d6444ca939089fffa7bbb4c1b96342a93e5a874b0442b1d964fc5ec28" +
                "ee2b5d288881f766b9693d1e59acba52181c760f2c9e886b0858b61f08d54681b573ab9e9a950a7ef4d9ee74" +
                "bd8072544bf4095d003bb3a11f89256a235b8711a6a1d5542bb9bf47b2097afd2c74fec35d4c4bbef9a42935" +
                "f2b6a71d7da5e751565fd9e3a7b4a1cc08d967c97ee9dd5e2c1ce8fcf8c86b48f9f6928adfbfd01d317ab72d" +
                "1f9c11e2f2af58413e47af03bdd215987887c3444cb252d1e1c9697a5510abe6572a0a1650141f24ae51d944" +
                "41db092f3939f15199f117eb2e0f23af833c8b65e21467c35f1ff5751050f0ae82ec981ab7a739f24ad3be77" +
                "513b8a9c40f298a033ebb622d1e2d65541e72b20c0ae7301a695d44028d59c4a586e8eeec87d9497d5f7f543" +
                "932032c51705257758ee0abe77e6e15d4444ad98980bfeb59b626c8ee2ad73c6042e472224fb96f83a9642b4" +
                "7ebb59aac9452ec1d98c7673ecc1cef89dfddfc5580c045da175c40f5295a58de753c428931e035840ebe957" +
                "15f46b11be8be0ff079eb77c5824ebeb6635f6a2e038d07f32d6e1084d493067c8c41bf05635063ac64c1a57" +
                "b19c1d74a6a57f526bbafbe7f1b072b2fbc370b1e856b9cdac1b3af9fcd6a20a73e43355b26abcfb911a9f37" +
                "246f06874c7c7eaaec027a673be8593378c6fafdabe787b3c0a2fbf252e61b915ddb643a031f0fd34f54c368" +
                "84178a43339141dbfee316180d1f090daed25c1b023d39d20f469f123c66127fd2f94aca7014a0310937d27e" +
                "c1ab436a676e2b7f6d1d52b3e0df038abcde87fcf536044a21555bceb6fddf0c8d2128df57889c3fec5afdd5" +
                "15392bb898e42e4d11fddf7d2809557017873d558e53254056dc93deec7acda9bfa0261c5b01625eaaa1948f" +
                "259e32b365086037c483d71b133dcafbd808a88ba54c487586aeab83fd5fd08f6f76f88a3459ea9b09ab728f" +
                "26255e4f7247545165c6aa38b40848d8646ab6bf167fac14424ae5741edc4bc8a3fda649ece243190d92fd5e" +
                "5978d5a2268a5971c2e4e0328791d97c51fccc630420aac760ddfca80323f8c9b9c74aee386f7d647d0f7f3d" +
                "f78e7599b7b6ca2a05268924a367f8ebcb82d53c1f52633fdb681abce8fecf2a9d1265c845d86d509d5829c6" +
                "68ab1f8ead9ac086c87a0a249e25a440d33c2c5beb0642b61a723d50c74429562a4428f48252eedd049bcbca" +
                "bee2b2bf4807f4d39ba168e81660f10322ed5db36dcbd1b67e15d94bff1b7f99a4714c19dfae5ee97ff3daf9" +
                "df28b01f96ea7585b79b32465ba4188bf24df9c8847ff48760fa5146b86e728813878cb65d669092bba0d4ab" +
                "e5c0ef7f59e91fcca97fb511f191e8f93481cbbabf9a610e79ad957c7900810cdd64dcf48a8fdb6f20b7dd9d" +
                "52a91d75a0e4a377743f24974f791700ff7959f0abfa5ca5467adb091985f171ce19c3cc192529f52cabdc50" +
                "9f4e947d2d904c48d93d99aa98bd00d9679a72ba59d4d58e45cc42911d51b827d2b5d9119f6a41cb243c7702" +
                "aecdb1b91a541d00ef00556bfc8d35acd059dc8e876cc44e01236d7edd729dc3b68827498f3a9bb1d4105d68" +
                "1fe54c87cc75dcaedf7e7bf91201f68fa103223f7b17224093e19e332ca0e9f6f41fd97b0f4471f2b0a8c074" +
                "5051c774674e76e64c2fb847e2c27d1376fb055d7b3af5c231206e5a5904a1cbc7fd387e642d49268b4d9ae7" +
                "61d1fd05dcf67ee395b2b4d25843e9cef8effa7fc2329b2b78ea6bc2d72e0e5fe1860dec2fe155c9118289e0" +
                "185e6b267845dd712bd7fce69441218990d9345cad890cf0e41680df7058cfee786716e5c02166ac4a34a882" +
                "4a081638982614e80a6e17d93253679abe7fb0b33575d4fe5f618d5b871c2571601f0fd7240ca619ad89f9d8" +
                "876b6d3bf8446a77804a71d60419d496fef69fa6fe8dd092ead1b3169feceb74fe12c806d6873ca87c59db98" +
                "0887454822a8a0f199fbd06e73efb011fff97546cdf2148c769bf4ef0596c6243f899b88855951dca784ff8b" +
                "d1eab1790447f26cad07cd9e020d1a0c756de39cdcf5d1b81567cf355c9e53d67297bf666a730fb41c8eedf3" +
                "dee4e364fc468a6a3255bb210c6207612eaced13ed63b5934951105f60148dfcf60d6d07bfeb76d2b6a47eeb" +
                "83166b2226616bbc126555212cfea492744aab04bb1ba634dc82e1f594f63dbf15ce95a6d41202200d663d50" +
                "20f449a65452a7535f6d0787ac1432db0d5cf63a756312bdeca8b34ec0f4e7ce4ced51508b68852754c1970a" +
                "54dfd448fe018195a6cfa93777e2a14f449b0c53fee3cb878d2706b1a1b4c1470c22ab0e4ae90a6e90b515b8" +
                "57198ea70c6d505fed6d8847c2732799f6d244c3259839db828f19b2135949a0dc72811fad73cd59ee3ccdfc" +
                "4c4720ed865850b2e0f71636edce1009e730393c8a6b1b0e8b5696bac3a8743707eba3baac9c22da21048471" +
                "c290094f56d24afd9a023ad03e735b096ae1a61957f8632cfbd79211c63c82dfeea18fee75554320910ae5a0" +
                "917f9515d58518c86eb331dbd0cc56e7160cc01496105b2db88adca555e7487d156499cfe6c71e4ab6bdeaa8" +
                "c2aa8e18e2773a754ac59fba84c0b2fa708e49cbce85882a8350a98f9bbff86355971a3972b1a95619afceed" +
                "f7d883341ade8ab7ae797120e661f223eaf535338e6e986c4223038af5ed0c5ffd45ff98e0322a1670f7beb1" +
                "b2af1cadc142eb597e21ae5f4dcdd44411d9286c9a48496bcc0e454b530c647e8ad28cb3868b0f9f6c728054" +
                "f94b47981a192d4f7b83370cd0b2ea0c0b5d8294eb1a42247f0d9ed66e5733cab78827dab38218da5298a667" +
                "933d897ec8f8108ed9841e4d682f054a6d4c1be5b1379011c47ad15771bec68702001407e1296d8574f37239" +
                "301edd858806e6b24c88f7115f9e9c1ddf16fcd14e5b64c82ed739a87413a40827a659ec4bab66bde536ca92" +
                "8b7a30c544c519fe3df85247aa106efdcdaadcdb83ebad7fee1bf89e41aae48b153055bcd067229cc60236ce" +
                "fdf9417d84790c6ba40a44efb0a691d7ce39febb0ec23fa2d10bb0a6f65ad625eb9d7a4092af3418787fae44" +
                "a31cf80208af0d202a2c2719d8975efb1a5ab21aa9ed21f41157962f77d9c8ff46d2500ae7f65dfb6f529b38" +
                "30c63cb585b858df63692ddc63aa87efcce28c8478041396926b834f8ccf85588ff6f91085bae518f6990757" +
                "7a37db336de8dfd67fbac01c375bdd7041a3bc985ded68def4628c388dc327fbb8d02660c1902537b04e7975" +
                "8c9200a4dbbd6bfdfa2ba45b42b61d571289af92af6acdfe123a8c300db67fd09cd198a5f35be502f7a77699" +
                "f4cd4a73281bd2ddacd2f41e7b98060826a19b40185b1360f4e97d1666127a9c745342d539abbe316108875f" +
                "bd48da6e027acedc122cc40908a055333bc29fea11e466ef739b0ac42336b9b29f06687d15982acfe143d8fe" +
                "b793b4b848b05d668b42e2361e86909cbcbfd211266066676a793841485da50723479da6bdeb0a102b3a464c" +
                "555a6e7376dce516b0e02ca5b0ddf70d1a3645608bc4dbf20000000000000000000000000000000000000007" +
                "0e131a272a2f3830650230062b4dacf0090bc5beb1dd35d092540cb51b2c400ac22ac36e4c2b33a586eda8ea" +
                "7da1253d309fef00d5ff255c712e020231009502889319d03f78cbcf2b2eb32bc74a705d0bf8c8a2cda4348c" +
                "9d3cb6da09f3bf7fcf679467ae008dec74bb43286e0d",
        )
        )
    }
}
