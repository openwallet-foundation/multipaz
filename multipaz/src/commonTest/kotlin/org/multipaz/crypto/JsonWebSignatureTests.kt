package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.jwt.JwtCheck
import org.multipaz.jwt.buildJwt
import org.multipaz.jwt.validateJwt
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    fun roundtrip(curve: EcCurve) = runTest {
        // TODO: use assumeTrue() when available in kotlin-test
        if (!Crypto.supportedCurves.contains(curve)) {
            println("Curve $curve not supported on platform")
            return@runTest
        }

        val privateKey = Crypto.createEcPrivateKey(curve)
        val now = Clock.System.now()
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
                JwtCheck.TYP to "oauth-authz-req+jwt"
            )
        )
        assertEquals(
            expected = "blah",
            actual = body["vp_token"]!!.jsonObject["credential"]!!.jsonObject["foo"]!!.jsonPrimitive.content
        )
    }
}