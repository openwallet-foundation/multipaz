package org.multipaz.util

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.jwt.JwtCheck
import org.multipaz.jwt.validateJwt
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class JwtTest {
    private val clock = FakeClock()
    private val privateTrustedKey = Crypto.createEcPrivateKey(EcCurve.P256)
    private val trustedKey = privateTrustedKey.publicKey

    @Test
    fun testSimple() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey)
        validateJwt(
            jwt, "test", privateTrustedKey.publicKey, clock = clock, checks = mapOf(
                JwtCheck.JTI to TEST_JTI,
                JwtCheck.SUB to TEST_SUB,
                JwtCheck.ISS to TEST_ISS,
                JwtCheck.AUD to TEST_AUD,
                JwtCheck.NONCE to TEST_NONCE,
                JwtCheck.JTI to "space1"
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
        clock.advance(2.minutes)
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
    fun testReplay() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey)
        validateJwt(
            jwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(JwtCheck.JTI to "jti-space1")
        )
        try {
            validateJwt(
                jwt, "test", privateTrustedKey.publicKey, clock = clock,
                checks = mapOf(JwtCheck.JTI to "jti-space1")
            )
            fail()
        } catch (err: InvalidRequestException) {
            assertTrue(err.message!!.lowercase().contains("jti"))
        }
        validateJwt(
            jwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(JwtCheck.JTI to "jti-space2")
        )
        clock.advance(2.minutes)
        val newJwt = makeJwt(privateTrustedKey)
        validateJwt(
            newJwt, "test", privateTrustedKey.publicKey, clock = clock,
            checks = mapOf(JwtCheck.JTI to "jti-space1")
        )
    }

    @Test
    fun testTrustIss() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey)
        validateJwt(
            jwt, "test", publicKey = null, clock = clock,
            checks = mapOf(JwtCheck.TRUST to "iss")
        )
    }

    @Test
    fun testTrustKid() = runBackendTest {
        val jwt = makeJwt(privateTrustedKey, iss = null, kid = "test-kid")
        validateJwt(
            jwt, "test", publicKey = null, clock = clock,
            checks = mapOf(JwtCheck.TRUST to "kid")
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
                privateKey = Crypto.createEcPrivateKey(EcCurve.P384),
                algorithm = EcCurve.P384.defaultSigningAlgorithm
            ),
            serialNumber = ASN1Integer(57),
            subject = X500Name.fromName("CN=test-x5c"),
            issuer = X500Name.fromName("CN=test-ca"),
            validFrom = clock.now() - 10.days,
            validUntil = clock.now() + 100.days
        ).build()
        val chain = X509CertChain(listOf(cert, root))
        val jwt = makeJwt(x5cKey, iss = "test-x5c-leaf", x5c = chain)
        validateJwt(
            jwt, "test", publicKey = null, clock = clock,
            checks = mapOf(JwtCheck.TRUST to "x5c")
        )
    }

    private fun makeJwt(
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
                put("x5c", x5c.toX5c())
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
        runTest {
            withContext(TestBackendEnvironment()) {
                body()
            }
        }

    inner class TestConfiguration(): Configuration {
        private val trustedCert = runBlocking {
            X509Cert.Builder(
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

        override fun getValue(key: String): String? {
            if (key == "iss" || key == "kid") {
                return buildJsonObject {
                    put("test-$key", trustedKey.toJwk())
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
        private var instant = Instant.parse("2025-06-10T22:30:00Z")

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