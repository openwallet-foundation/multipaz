package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.webtoken.WebTokenCheck
import org.multipaz.webtoken.buildJwt
import org.multipaz.webtoken.validateJwt
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.truncateToWholeSeconds
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days

class JsonWebSignatureTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test fun roundTrip_P256() = roundtrip(EcCurve.P256)
    @Test fun roundTrip_P384() = roundtrip(EcCurve.P384)
    @Test fun roundTrip_P521() = roundtrip(EcCurve.P521)
    @Test fun roundTrip_B256() = roundtrip(EcCurve.BRAINPOOLP256R1)
    @Test fun roundTrip_B320() = roundtrip(EcCurve.BRAINPOOLP320R1)
    @Test fun roundTrip_B384() = roundtrip(EcCurve.BRAINPOOLP384R1)
    @Test fun roundTrip_B512() = roundtrip(EcCurve.BRAINPOOLP512R1)
    @Test fun roundTrip_ED25519() = roundtrip(EcCurve.ED25519)
    @Test fun roundTrip_ED448() = roundtrip(EcCurve.ED448)

    @Test fun roundTrip_RS256() = roundtripRsa(Algorithm.RS256)
    @Test fun roundTrip_RS384() = roundtripRsa(Algorithm.RS384)
    @Test fun roundTrip_RS512() = roundtripRsa(Algorithm.RS512)
    @Test fun roundTrip_PS256() = roundtripRsa(Algorithm.PS256)
    @Test fun roundTrip_PS384() = roundtripRsa(Algorithm.PS384)
    @Test fun roundTrip_PS512() = roundtripRsa(Algorithm.PS512)

    fun roundtripRsa(algorithm: Algorithm) = runTest {
        val privateKey = Crypto.createRsaPrivateKey(2048)
        val now = Clock.System.now().truncateToWholeSeconds()
        val signingKeyCert = X509Cert.Builder(
            publicKey = privateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(privateKey, Algorithm.RS256),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test RSA Key"),
            issuer = X500Name.fromName("CN=Test RSA Key"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()
        val signingKey = AsymmetricKey.X509CertifiedExplicit(
            privateKey = privateKey,
            certChain = X509CertChain(listOf(signingKeyCert)),
            algorithm = algorithm
        )

        val jwt = buildJwt(
            key = signingKey,
            type = "oauth-authz-req+jwt",
        ) {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        JsonWebSignature.verify(jwt, signingKey.publicKey)

        val body = validateJwt(
            jwt = jwt,
            jwtName = "test jwt",
            publicKey = signingKey.publicKey,
            checks = mapOf(
                WebTokenCheck.TYP to "oauth-authz-req+jwt"
            )
        )
        assertEquals(
            expected = "blah",
            actual = body["vp_token"]!!.jsonObject["credential"]!!.jsonObject["foo"]!!.jsonPrimitive.content
        )
    }

    fun roundtrip(curve: EcCurve) = runTest {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return@runTest
        }

        val privateKey = Crypto.createEcPrivateKey(curve)
        val now = Clock.System.now().truncateToWholeSeconds()
        val signingKeyCert = X509Cert.Builder(
            publicKey = privateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(privateKey, privateKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Key"),
            issuer = X500Name.fromName("CN=Test Key"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()
        val signingKey = AsymmetricKey.X509CertifiedExplicit(
            privateKey = privateKey,
            certChain = X509CertChain(listOf(signingKeyCert)),
            algorithm = privateKey.curve.defaultSigningAlgorithmFullySpecified
        )

        val jwt = buildJwt(
            key = signingKey,
            type = "oauth-authz-req+jwt",
        ) {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        JsonWebSignature.verify(jwt, signingKey.publicKey)

        val body = validateJwt(
            jwt = jwt,
            jwtName = "test jwt",
            publicKey = signingKey.publicKey,
            checks = mapOf(
                WebTokenCheck.TYP to "oauth-authz-req+jwt"
            )
        )
        assertEquals(
            expected = "blah",
            actual = body["vp_token"]!!.jsonObject["credential"]!!.jsonObject["foo"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun testCriticalHeaderRejection() = runTest {
        val privateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val signingKey = AsymmetricKey.anonymous(privateKey, Algorithm.ES256)

        // Control: unknown non-critical header parameter must be ignored per RFC 7515 Section 4
        val nonCriticalJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                put("urn:example:non-critical", true)
            },
        ) {
            put("sub", "alice")
        }
        JsonWebSignature.verify(nonCriticalJwt, privateKey.publicKey)

        // Unsupported critical extension present in header
        val unsupportedCritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") { add("urn:example:never-understood") }
                put("urn:example:never-understood", true)
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(unsupportedCritJwt, privateKey.publicKey)
        }

        // Unsupported RFC 7797 b64 critical extension
        val b64CritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") { add("b64") }
                put("b64", false)
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(b64CritJwt, privateKey.publicKey)
        }

        // Empty crit array (RFC 7515 Section 4.1.11 forbids [])
        val emptyCritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") {}
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(emptyCritJwt, privateKey.publicKey)
        }

        // Non-array crit values (string, null)
        val stringCritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                put("crit", "urn:example:never-understood")
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(stringCritJwt, privateKey.publicKey)
        }

        val nullCritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                put("crit", JsonNull)
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(nullCritJwt, privateKey.publicKey)
        }

        // Non-string and empty-string entries in crit
        val nonStringEntryJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") { add(123) }
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(nonStringEntryJwt, privateKey.publicKey)
        }

        val emptyStringEntryJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") { add("") }
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(emptyStringEntryJwt, privateKey.publicKey)
        }

        // Duplicate entries in crit
        val duplicateCritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") {
                    add("urn:example:dup")
                    add("urn:example:dup")
                }
                put("urn:example:dup", true)
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(duplicateCritJwt, privateKey.publicKey)
        }

        // Standard RFC 7515 header parameter listed in crit
        val standardParamCritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") { add("alg") }
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(standardParamCritJwt, privateKey.publicKey)
        }

        // Critical header parameter not present in header
        val missingParamCritJwt = buildJwt(
            type = "JWT",
            key = signingKey,
            header = {
                putJsonArray("crit") { add("urn:example:missing") }
            },
        ) {
            put("sub", "alice")
        }
        assertFailsWith<IllegalArgumentException> {
            JsonWebSignature.verify(missingParamCritJwt, privateKey.publicKey)
        }
    }
}