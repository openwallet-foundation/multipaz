package org.multipaz.crypto

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import com.nimbusds.jose.proc.JWSKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.time.Clock
import kotlin.time.Instant
import org.multipaz.webtoken.WebTokenCheck
import org.multipaz.webtoken.buildJwt
import org.multipaz.webtoken.validateJwt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.toBase64
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.days

// Note: This checks the JsonWebSignature implementation against the https://connect2id.com/products/nimbus-jose-jwt
// implementation
class JsonWebSignatureTestsNimbus {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    // TODO: Check for other curves than just P-256.

    @Test
    fun testSigning() = runTest {
        val rootKey = AsymmetricKey.anonymous(Crypto.createEcPrivateKey(EcCurve.P256))
        val signingKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        val rootCert = X509Cert.Builder(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Root"),
            issuer = X500Name.fromName("CN=Test Root"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            .setBasicConstraints(true, null)
            .build()
        val signingKeyCert = X509Cert.Builder(
            publicKey = signingKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Key"),
            issuer = X500Name.fromName("CN=Test Root"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()
        val certChain = X509CertChain(listOf(signingKeyCert, rootCert))
        certChain.validate(now)

        val claimsSet = buildJsonObject {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        val signedJwt = JsonWebSignature.sign(
            key = signingKey,
            signatureAlgorithm = signingKey.curve.defaultSigningAlgorithmFullySpecified,
            claimsSet = claimsSet,
            type = "oauth-authz-req+jwt",
            x5c = certChain
        )

        val sjwt = SignedJWT.parse(signedJwt)
        val jwtProcessor = DefaultJWTProcessor<SecurityContext>()
        val x5c = sjwt.header?.x509CertChain ?: throw IllegalArgumentException("Error retrieving x5c")
        val pubCertChain = x5c.mapNotNull { runCatching { X509Cert(ByteString(it.decode())) }.getOrNull() }
        assertEquals(1, pubCertChain.size)
        assertEquals(signingKeyCert, pubCertChain[0])

        jwtProcessor.jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(
            JOSEObjectType("oauth-authz-req+jwt"),
            JOSEObjectType.JWT,
            JOSEObjectType(""),
            null,
        )
        jwtProcessor.jwsKeySelector = JWSKeySelector { _, _ ->
            listOf(pubCertChain[0].javaX509Certificate.publicKey)
        }
        val resultingClaimsSet = jwtProcessor.process(sjwt, null)
        val extractedClaimsSet = Json.parseToJsonElement(resultingClaimsSet.toString()).jsonObject

        assertEquals(claimsSet, extractedClaimsSet)
    }

    @Test
    fun testVerification() = runTest {
        val signingKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        val signingKeyCert = X509Cert.Builder(
            publicKey = signingKey.publicKey,
            signingKey = AsymmetricKey.anonymous(signingKey, signingKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Key"),
            issuer = X500Name.fromName("CN=Test Key"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        val claimsSet = buildJsonObject {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        val ecKey = ECKey(
            Curve.P_256,
            signingKey.publicKey.javaPublicKey as ECPublicKey,
            signingKey.javaPrivateKey as ECPrivateKey,
            null, null, null, null, null, null, null, null, null, null, null, null
        )
        val builder = JWSHeader.Builder(JWSAlgorithm.ES256)
        builder.x509CertChain(
            listOf(com.nimbusds.jose.util.Base64.from(signingKeyCert.encoded.toByteArray().toBase64()))
        )
        builder.type(JOSEObjectType("oauth-authz-req+jwt"))
        builder.keyID(ecKey.getKeyID())
        val signedJWT = SignedJWT(
            builder.build(),
            JWTClaimsSet.parse(claimsSet.toString())
        )
        val signer: JWSSigner = ECDSASigner(ecKey)
        signedJWT.sign(signer)
        val signedJwt = Json.parseToJsonElement(signedJWT.serialize())

        // Verify JwsInfo fields
        val info = JsonWebSignature.getInfo(signedJwt.jsonPrimitive.content)
        assertEquals(claimsSet, info.claimsSet)
        assertEquals("oauth-authz-req+jwt", info.type)
        assertEquals(X509CertChain(listOf(signingKeyCert)), info.x5c)

        // Verify signature checks out
        JsonWebSignature.verify(
            signedJwt.jsonPrimitive.content,
            info.x5c!!.certificates.first().publicKey
        )

        // Verify signature check with another key fails
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256)
        assertFails {
            JsonWebSignature.verify(
                signedJwt.jsonPrimitive.content,
                otherKey.publicKey
            )
        }
    }

    @Test
    fun testSigningRsa_RS256() = testSigningRsa(Algorithm.RS256, JWSAlgorithm.RS256)

    @Test
    fun testSigningRsa_RS384() = testSigningRsa(Algorithm.RS384, JWSAlgorithm.RS384)

    @Test
    fun testSigningRsa_RS512() = testSigningRsa(Algorithm.RS512, JWSAlgorithm.RS512)

    @Test
    fun testSigningRsa_PS256() = testSigningRsa(Algorithm.PS256, JWSAlgorithm.PS256)

    @Test
    fun testSigningRsa_PS384() = testSigningRsa(Algorithm.PS384, JWSAlgorithm.PS384)

    @Test
    fun testSigningRsa_PS512() = testSigningRsa(Algorithm.PS512, JWSAlgorithm.PS512)

    fun testSigningRsa(algorithm: Algorithm, expectedJwsAlgorithm: JWSAlgorithm) = runTest {
        val rootKeyPriv = Crypto.createRsaPrivateKey(2048)
        val rootKey = AsymmetricKey.anonymous(rootKeyPriv, Algorithm.RS256)
        val signingKeyPriv = Crypto.createRsaPrivateKey(2048)
        val now = Clock.System.now()
        val rootCert = X509Cert.Builder(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Root"),
            issuer = X500Name.fromName("CN=Test Root"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            .setBasicConstraints(true, null)
            .build()
        val signingKeyCert = X509Cert.Builder(
            publicKey = signingKeyPriv.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Key"),
            issuer = X500Name.fromName("CN=Test Root"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()
        val certChain = X509CertChain(listOf(signingKeyCert, rootCert))
        certChain.validate(now)

        val signingKey = AsymmetricKey.X509CertifiedExplicit(
            privateKey = signingKeyPriv,
            certChain = certChain,
            algorithm = algorithm
        )

        val claimsSet = buildJsonObject {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        val signedJwt = buildJwt(
            type = "oauth-authz-req+jwt",
            key = signingKey,
            creationTime = Instant.DISTANT_PAST
        ) {
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        val sjwt = SignedJWT.parse(signedJwt)
        assertEquals(expectedJwsAlgorithm, sjwt.header.algorithm)
        val jwtProcessor = DefaultJWTProcessor<SecurityContext>()
        val x5c = sjwt.header?.x509CertChain ?: throw IllegalArgumentException("Error retrieving x5c")
        val pubCertChain = x5c.mapNotNull { runCatching { X509Cert(ByteString(it.decode())) }.getOrNull() }
        assertEquals(1, pubCertChain.size)
        assertEquals(signingKeyCert, pubCertChain[0])

        jwtProcessor.jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(
            JOSEObjectType("oauth-authz-req+jwt"),
            JOSEObjectType.JWT,
            JOSEObjectType(""),
            null,
        )
        jwtProcessor.jwsKeySelector = JWSKeySelector { _, _ ->
            listOf(pubCertChain[0].javaX509Certificate.publicKey)
        }
        val resultingClaimsSet = jwtProcessor.process(sjwt, null)
        val extractedClaimsSet = Json.parseToJsonElement(resultingClaimsSet.toString()).jsonObject

        assertEquals(claimsSet, extractedClaimsSet)
    }

    @Test
    fun testVerificationRsa_RS256() = testVerificationRsa(Algorithm.RS256, JWSAlgorithm.RS256)

    @Test
    fun testVerificationRsa_RS384() = testVerificationRsa(Algorithm.RS384, JWSAlgorithm.RS384)

    @Test
    fun testVerificationRsa_RS512() = testVerificationRsa(Algorithm.RS512, JWSAlgorithm.RS512)

    @Test
    fun testVerificationRsa_PS256() = testVerificationRsa(Algorithm.PS256, JWSAlgorithm.PS256)

    @Test
    fun testVerificationRsa_PS384() = testVerificationRsa(Algorithm.PS384, JWSAlgorithm.PS384)

    @Test
    fun testVerificationRsa_PS512() = testVerificationRsa(Algorithm.PS512, JWSAlgorithm.PS512)

    fun testVerificationRsa(algorithm: Algorithm, jwsAlgorithm: JWSAlgorithm) = runTest {
        val signingKey = Crypto.createRsaPrivateKey(2048)
        val now = Clock.System.now()
        val signingKeyCert = X509Cert.Builder(
            publicKey = signingKey.publicKey,
            signingKey = AsymmetricKey.anonymous(signingKey, Algorithm.RS256),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Test Key"),
            issuer = X500Name.fromName("CN=Test Key"),
            validFrom = now,
            validUntil = now + 1.days
        ).includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        val claimsSet = buildJsonObject {
            put("iat", JsonPrimitive(now.epochSeconds))
            put("vp_token", buildJsonObject {
                put("credential", buildJsonObject {
                    put("foo", JsonPrimitive("blah"))
                })
            })
        }

        val builder = JWSHeader.Builder(jwsAlgorithm)
        builder.x509CertChain(
            listOf(com.nimbusds.jose.util.Base64.from(signingKeyCert.encoded.toByteArray().toBase64()))
        )
        builder.type(JOSEObjectType("oauth-authz-req+jwt"))
        builder.keyID("test-key-id")
        val signedJWT = SignedJWT(
            builder.build(),
            JWTClaimsSet.parse(claimsSet.toString())
        )
        val signer: JWSSigner = RSASSASigner(signingKey.javaPrivateKey)
        signedJWT.sign(signer)
        val signedJwt = Json.parseToJsonElement(signedJWT.serialize())

        // Verify JwsInfo fields
        val info = JsonWebSignature.getInfo(signedJwt.jsonPrimitive.content)
        assertEquals(claimsSet, info.claimsSet)
        assertEquals("oauth-authz-req+jwt", info.type)
        assertEquals(X509CertChain(listOf(signingKeyCert)), info.x5c)

        // Verify signature checks out
        JsonWebSignature.verify(
            signedJwt.jsonPrimitive.content,
            info.x5c!!.certificates.first().publicKey
        )

        // Also test validateJwt
        val body = validateJwt(
            jwt = signedJwt.jsonPrimitive.content,
            jwtName = "test jwt",
            publicKey = signingKey.publicKey,
            checks = mapOf(
                WebTokenCheck.TYP to "oauth-authz-req+jwt"
            )
        )
        assertEquals(
            "blah",
            body["vp_token"]!!.jsonObject["credential"]!!.jsonObject["foo"]!!.jsonPrimitive.content
        )

        // Verify signature check with another key fails
        val otherKey = Crypto.createRsaPrivateKey(2048)
        assertFails {
            JsonWebSignature.verify(
                signedJwt.jsonPrimitive.content,
                otherKey.publicKey
            )
        }
    }
}
