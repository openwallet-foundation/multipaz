package org.multipaz.webtoken

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64
import org.multipaz.webtoken.WebTokenClaim.Companion.put
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CwtTest {
    private lateinit var clock: FakeClock

    private lateinit var privateTrustedKey: EcPrivateKey
    private lateinit var trustedKeyJwk: JsonObject

    private lateinit var trustedCert: X509Cert

    // On Kotlin/JS, @BeforeTest using runTest is broken. Work around.
    private fun runTestWithSetup(block: suspend TestScope.() -> Unit) = runTest { setup(); block() }

    private suspend fun setup() {
        privateTrustedKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val trustedKey = privateTrustedKey.publicKey
        trustedKeyJwk = trustedKey.toJwk()
        clock = FakeClock(Instant.fromEpochSeconds(1443944945))
        trustedCert = X509Cert.Builder(
            publicKey = trustedKey,
            signingKey = AsymmetricKey.Companion.anonymous(
                privateKey = Crypto.createEcPrivateKey(EcCurve.P384),
                algorithm = EcCurve.P384.defaultSigningAlgorithm
            ),
            serialNumber = ASN1Integer(57),
            subject = X500Name.Companion.fromName("CN=test-root"),
            issuer = X500Name.Companion.fromName("CN=test-ca"),
            validFrom = clock.now() - 10.days,
            validUntil = clock.now() + 100.days
        ).build()
    }

    @Test
    fun testSpecData() = runBackendTest {
        val cwtData = """
           d28443a10126a104524173796d6d657472696345434453413235365850a701756
           36f61703a2f2f61732e6578616d706c652e636f6d02656572696b77037818636f
           61703a2f2f6c696768742e6578616d706c652e636f6d041a5612aeb0051a5610d
           9f0061a5610d9f007420b7158405427c1ff28d23fbad1f29c4c7c6a555e601d6f
           a29f9179bc3d7438bacaca5acd08c8d4d4f96131680c429a01f85951ecee743a5
           2b9b63632c57209120e1c9e30
        """.replace(Regex("\\s+"), "").fromHex()
        val keyData = """
            a72358206c1382765aec5358f117733d281c1c7bdc39884d04a45a1e6c67c858
            bc206c1922582060f7f1a780d8a783bfb7a2dd6b2796e8128dbbcef9d3d168db
            9529971a36e7b9215820143329cce7868e416927599cf65a34f3ce2ffda55a7e
            ca69ed8919a394d42f0f2001010202524173796d6d6574726963454344534132
            35360326
        """.replace(Regex("\\s+"), "").fromHex()
        val publicKey = EcPublicKey.fromCoseKey(Cbor.decode(keyData).asCoseKey)
        validateCwt(cwtData, "Test", publicKey,
            checks = mapOf(
                WebTokenCheck.ISS to "coap://as.example.com",
                WebTokenCheck.SUB to "erikw",
                WebTokenCheck.AUD to "coap://light.example.com",
                WebTokenCheck.IDENT to "foo"
            ),
            maxValidity = 48.hours,
            clock = clock
        )
    }

    @Test
    fun testSimple() = runBackendTest {
        val cwt = makeCwt(privateTrustedKey)
        validateCwt(
            cwt, "test", privateTrustedKey.publicKey, clock = clock, checks = mapOf(
                WebTokenCheck.SUB to TEST_SUB,
                WebTokenCheck.ISS to TEST_ISS,
                WebTokenCheck.AUD to TEST_AUD,
                WebTokenCheck.NONCE to TEST_NONCE,
                WebTokenCheck.IDENT to "space1"
            )
        )
    }

    @Test
    fun testExpirationExp() = runBackendTest {
        val cwt = makeCwt(privateTrustedKey)
        clock.advance(2.minutes)
        try {
            validateCwt(cwt, "test", privateTrustedKey.publicKey, clock = clock)
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("expired"))
        }
    }

    @Test
    fun testExpirationIat() = runBackendTest {
        val cwt = makeCwt(privateTrustedKey, exp = null, iat = clock.now())
        clock.advance(2.minutes)
        try {
            validateCwt(
                cwt, "test", privateTrustedKey.publicKey,
                clock = clock, maxValidity = 1.minutes
            )
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("expired"))
        }
    }

    @Test
    fun testClockSkewLarge() = runBackendTest {
        val cwt = makeCwt(privateTrustedKey, exp = null, iat = clock.now() + 30.seconds)
        try {
            validateCwt(
                cwt, "test", privateTrustedKey.publicKey,
                clock = clock, maxValidity = 1.minutes
            )
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("future"))
        }
    }

    @Test
    fun testClockSkewSmall() = runBackendTest {
        val cwt = makeCwt(privateTrustedKey, exp = null, iat = clock.now() + 1.seconds)
        validateCwt(cwt, "test", privateTrustedKey.publicKey, clock = clock)
    }

    @Test
    fun testReplay() = runBackendTest {
        val cwt = makeCwt(privateTrustedKey)
        validateCwt(
            cwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(WebTokenCheck.IDENT to "cti-space1")
        )
        try {
            validateCwt(
                cwt, "test", privateTrustedKey.publicKey, clock = clock,
                checks = mapOf(WebTokenCheck.IDENT to "cti-space1")
            )
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("cti"))
        }
        validateCwt(
            cwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(WebTokenCheck.IDENT to "cti-space2")
        )
        clock.advance(2.minutes)
        val newCwt = makeCwt(privateTrustedKey)
        validateCwt(
            newCwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(WebTokenCheck.IDENT to "cti-space1")
        )
    }

    @Test
    fun testTrustIssKid() = runBackendTest {
        val cwt = makeCwt(privateTrustedKey, iss = "test-iss", kid = "test-kid")
        validateCwt(
            cwt, "test", publicKey = null, clock = clock,
            checks = mapOf(WebTokenCheck.TRUST to "iss_kid")
        )
    }

    @Test
    fun testTrustX5Chain() = runBackendTest {
        val x5cKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val cert = buildX509Cert(
            publicKey = x5cKey.publicKey,
            signingKey = AsymmetricKey.anonymous(
                privateKey = privateTrustedKey,
                algorithm = privateTrustedKey.curve.defaultSigningAlgorithm
            ),
            serialNumber = ASN1Integer(2),
            subject = X500Name.fromName("CN=test-x5c-leaf"),
            issuer = X500Name.fromName("CN=test-x5c"),
            validFrom = clock.now() - 1.days,
            validUntil = clock.now() + 1.days
        ) {
            setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
        }
        val chain = X509CertChain(listOf(cert))
        val cwt = makeCwt(x5cKey, iss = "test-x5c-leaf", x5c = chain)
        validateCwt(
            cwt, "test", publicKey = null, clock = clock,
            checks = mapOf(WebTokenCheck.TRUST to "x5c")
        )
    }
    

    private suspend fun makeCwt(
        key: EcPrivateKey,
        typ: String = TEST_TYP,
        iss: String? = TEST_ISS,
        aud: String? = TEST_AUD,
        sub: String? = TEST_SUB,
        exp: Instant? = clock.now() + 1.minutes,
        cti: ByteString? = TEST_JTI,
        nonce: String? = TEST_NONCE,
        iat: Instant? = null,
        x5c: X509CertChain? = null,
        kid: String? = null
    ): ByteArray {
        val signingKey = if (x5c != null) {
            AsymmetricKey.X509CertifiedExplicit(x5c, key)
        } else if (kid != null) {
            AsymmetricKey.NamedExplicit(kid, key)
        } else {
            AsymmetricKey.AnonymousExplicit(key)
        }
        return buildCwt(typ, signingKey) {
            if (iss != null) {
                put(WebTokenClaim.Iss, iss)
            }
            if (aud != null) {
                put(WebTokenClaim.Aud, aud)
            }
            if (sub != null) {
                put(WebTokenClaim.Sub, sub)
            }
            if (exp != null) {
                put(WebTokenClaim.Exp, exp)
            }
            if (iat != null) {
                put(WebTokenClaim.Iat, iat)
            }
            if (cti != null) {
                put(WebTokenClaim.Cti, cti)
            }
            if (nonce != null) {
                put(WebTokenClaim.Nonce, nonce)
            }
        }
    }

    private fun runBackendTest(body: suspend TestScope.() -> Unit) =
        runTestWithSetup {
            withContext(TestBackendEnvironment()) {
                body()
            }
        }

    inner class TestConfiguration(): Configuration {
        override fun getValue(key: String): String? {
            if (key == "iss_kid") {
                return buildJsonObject {
                    put("test-iss#test-kid", trustedKeyJwk)
                }.toString()
            }
            if (key == "x5c") {
                return buildJsonObject {
                    put("test-$key", trustedCert.encoded.toByteArray().toBase64())
                }.toString()
            }
            return null
        }
    }

    inner class TestBackendEnvironment(): BackendEnvironment {
        private val configuration = TestConfiguration()
        private val storage: Storage = EphemeralStorage(clock)
        override fun <T : Any> getInterface(clazz: KClass<T>): T? {
            return clazz.cast(when (clazz) {
                Configuration::class -> configuration
                Storage::class -> storage
                else -> return null
            })
        }
    }

    class FakeClock(private var instant: Instant): Clock {

        fun advance(duration: Duration) {
            instant += duration
        }

        override fun now(): Instant = instant
    }

    companion object {
        const val TEST_TYP = "test-typ"
        const val TEST_ISS = "test-iss"
        const val TEST_AUD = "test-aud"
        const val TEST_SUB = "test-sub"
        const val TEST_NONCE = "test-nonce"
        val TEST_JTI = ByteString( byteArrayOf(1, 2, 42, 57, 0))
    }
}