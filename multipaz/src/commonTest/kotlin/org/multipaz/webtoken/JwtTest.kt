package org.multipaz.webtoken

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.CwtTest.FakeClock
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class JwtTest {
    private val clock = FakeClock()
    private lateinit var privateTrustedKey: EcPrivateKey
    private lateinit var trustedKey: EcPublicKey
    private lateinit var trustedKeyJwk: JsonObject

    private lateinit var trustedCert: X509Cert

    // On Kotlin/JS, @BeforeTest using runTest is broken. Work around.
    private fun runTestWithSetup(block: suspend TestScope.() -> Unit) = runTest { setup(); block() }

    private suspend fun setup() {
        privateTrustedKey = Crypto.createEcPrivateKey(EcCurve.P256)
        trustedKey = privateTrustedKey.publicKey
        trustedKeyJwk = trustedKey.toJwk()
        trustedCert = X509Cert.Builder(
            publicKey = trustedKey,
            signingKey = AsymmetricKey.anonymous(
                privateKey = Crypto.createEcPrivateKey(EcCurve.P384),
                algorithm = EcCurve.P384.defaultSigningAlgorithm
            ),
            serialNumber = ASN1Integer(57),
            subject = X500Name.fromName("CN=test-root"),
            issuer = X500Name.fromName("CN=test-ca"),
            validFrom = clock.now() - 10.days,
            validUntil = clock.now() + 100.days
        ).build()
    }

    @Test
    fun testSimple() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey)
        validateJwt(
            jwt, "test", privateTrustedKey.publicKey, clock = clock, checks = mapOf(
                WebTokenCheck.IDENT to TEST_JTI,
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
        val jwt = makeJwt(privateTrustedKey)
        clock.advance(2.minutes)
        try {
            validateJwt(jwt, "test", privateTrustedKey.publicKey, clock = clock)
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("expired"))
        }
    }

    @Test
    fun testExpirationIat() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey, exp = null, iat = clock.now())
        validateJwt(jwt, "test", privateTrustedKey.publicKey, clock = clock)
        clock.advance(2.minutes)
        validateJwt(jwt, "test", privateTrustedKey.publicKey, clock = clock)
        try {
            validateJwt(
                jwt, "test", privateTrustedKey.publicKey,
                clock = clock, maxValidity = 1.minutes
            )
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("expired"))
        }
    }

    @Test
    fun testClockSkewLarge() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey, exp = null, iat = clock.now() + 30.seconds)
        try {
            validateJwt(
                jwt, "test", privateTrustedKey.publicKey,
                clock = clock, maxValidity = 1.minutes
            )
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("future"))
        }
    }

    @Test
    fun testClockSkewSmall() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey, exp = null, iat = clock.now() + 1.seconds)
        validateJwt(jwt, "test", privateTrustedKey.publicKey, clock = clock)
    }

    @Test
    fun testReplay() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey)
        validateJwt(
            jwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(WebTokenCheck.IDENT to "jti-space1")
        )
        try {
            validateJwt(
                jwt, "test", privateTrustedKey.publicKey, clock = clock,
                checks = mapOf(WebTokenCheck.IDENT to "jti-space1")
            )
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("jti"))
        }
        validateJwt(
            jwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(WebTokenCheck.IDENT to "jti-space2")
        )
        clock.advance(2.minutes)
        val newJwt = makeJwt(privateTrustedKey)
        validateJwt(
            newJwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(WebTokenCheck.IDENT to "jti-space1")
        )
    }

    @Test
    fun testTrustIssKid() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey, iss = "test-iss", kid = "test-kid")
        validateJwt(
            jwt, "test", publicKey = null, clock = clock,
            checks = mapOf(WebTokenCheck.TRUST to "iss_kid")
        )
    }

    @Test
    fun testTrustX5C() = runBackendTest {
        val x5cKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val cert = X509Cert.Builder(
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
        ).build()
        val root = X509Cert.Builder(
            publicKey = trustedKey,
            signingKey = AsymmetricKey.anonymous(
                privateKey = privateTrustedKey,
                algorithm = privateTrustedKey.curve.defaultSigningAlgorithm
            ),
            serialNumber = ASN1Integer(57),
            subject = X500Name.fromName("CN=test-x5c"),
            issuer = X500Name.fromName("CN=test-x5c"),
            validFrom = clock.now() - 10.days,
            validUntil = clock.now() + 100.days
        ).build()
        val chain = X509CertChain(listOf(cert, root))
        val jwt = makeJwt(x5cKey, iss = "test-x5c-leaf", x5c = chain)
        validateJwt(
            jwt, "test", publicKey = null, clock = clock,
            checks = mapOf(WebTokenCheck.TRUST to "x5c")
        )
    }

    private suspend fun makeJwt(
        privateKey: EcPrivateKey,
        typ: String = TEST_TYP,
        iss: String? = TEST_ISS,
        aud: String? = TEST_AUD,
        sub: String? = TEST_SUB,
        exp: Instant? = clock.now() + 1.minutes,
        jti: String? = TEST_JTI,
        nonce: String? = TEST_NONCE,
        iat: Instant? = null,
        x5c: X509CertChain? = null,
        kid: String? = null
    ): String {
        val alg = privateKey.curve.defaultSigningAlgorithmFullySpecified
        val header = buildJsonObject {
            put("typ", typ)
            put("alg", alg.joseAlgorithmIdentifier)
            if (kid != null) {
                put("kid", kid)
            }
            if (x5c != null) {
                put("x5c", x5c.toX5c(excludeRoot = true))
            }
        }.toString().encodeToByteArray().toBase64Url()
        val body = buildJsonObject {
            if (iss != null) {
                put("iss", iss)
            }
            if (aud != null) {
                put("aud", aud)
            }
            if (sub != null) {
                put("sub", sub)
            }
            if (exp != null) {
                put("exp", exp.epochSeconds)
            }
            if (iat != null) {
                put("iat", iat.epochSeconds)
            }
            if (jti != null) {
                put("jti", jti)
            }
            if (nonce != null) {
                put("nonce", nonce)
            }
        }.toString().encodeToByteArray().toBase64Url()
        val message = "$header.$body"
        val sig = Crypto.sign(privateKey, alg, message.encodeToByteArray())
        return "$message.${sig.toCoseEncoded().toBase64Url()}"
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

    class FakeClock: Clock {
        private var instant = Instant.Companion.parse("2025-06-10T22:30:00Z")

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
        const val TEST_JTI = "test-jti"
    }
}