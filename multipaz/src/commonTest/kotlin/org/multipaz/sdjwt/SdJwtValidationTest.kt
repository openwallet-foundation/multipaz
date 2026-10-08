package org.multipaz.sdjwt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class SdJwtValidationTest {

    private suspend fun createIssuerKey(
        validFrom: Instant,
        validUntil: Instant,
        keyUsages: Set<X509KeyUsage> = setOf(X509KeyUsage.DIGITAL_SIGNATURE)
    ): AsymmetricKey.X509Certified {
        val ecKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val builder = X509Cert.Builder(
            publicKey = ecKey.publicKey,
            signingKey = AsymmetricKey.anonymous(ecKey, Algorithm.ES256),
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Test Issuer"),
            issuer = X500Name.fromName("CN=Test Issuer"),
            validFrom = validFrom,
            validUntil = validUntil
        )
        if (keyUsages.isNotEmpty()) {
            builder.setKeyUsage(keyUsages)
        }
        val cert = builder.build()
        return AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(cert)),
            privateKey = ecKey
        )
    }

    @Test
    fun testRfcVectorValidation() = runTest {
        val compact = SdJwtTest.sdJwtRfcSection51SdJwtCompactSerialization
        // Validate with now around iat (1683000000)
        val now = Instant.fromEpochSeconds(1683001000)
        val result = SdJwt.validate(compact, now)
        assertFalse(result.hasErrors, "RFC vector should have no errors: ${result.errors}")
        assertTrue(result.hasWarnings)
        assertTrue(result.warnings.any { it.message.contains("x5c") })
    }

    @Test
    fun testValidSdJwtWithX5c() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
                put("family_name", "Doe")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
                put("nbf", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        val result = sdJwt.validate(now = now + 1.days)
        assertFalse(result.hasErrors, "Validation failed: ${result.errors.map { it.message }}")
        assertFalse(result.hasWarnings, "Warnings: ${result.warnings.map { it.message }}")
    }

    @Test
    fun testMissingIssClaim() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "") // empty!
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
            }
        )
        val result = sdJwt.validate(now = now)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("missing 'iss' claim") })
    }

    @Test
    fun testTimestampOrdering() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 10.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
                put("nbf", (now + 5.days).epochSeconds)
                put("exp", (now + 2.days).epochSeconds) // exp earlier than nbf!
            }
        )
        val result = sdJwt.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("'exp'") && it.message.contains("must be later than 'nbf'") })
    }

    @Test
    fun testExpiredSdJwt() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 10.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
                put("exp", (now + 1.days).epochSeconds)
            }
        )
        val result = sdJwt.validate(now = now + 2.days) // validated after expiration!
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("expired") })
    }

    @Test
    fun testOrphanedDisclosure() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
            }
        )
        val orphanDisclosure = Disclosure(
            salt = Crypto.secureRandom.nextBytes(16).toBase64Url(),
            claimName = "unreferenced_claim",
            claimValue = JsonPrimitive("value"),
            disclosureString = ""
        )
        val orphanStr = "[\"${orphanDisclosure.salt}\",\"unreferenced_claim\",\"value\"]".encodeToByteArray().toBase64Url()
        val tamperedCompact = sdJwt.compactSerialization + orphanStr + "~"
        val result = SdJwt.validate(tamperedCompact, now = now)
        assertFalse(result.hasErrors)
        assertTrue(result.hasWarnings)
        assertTrue(result.warnings.any { it.message.contains("Orphaned disclosure") })
    }

    @Test
    fun testMissingDisclosure() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
                put("family_name", "Doe")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
            }
        )
        val splits = sdJwt.compactSerialization.split("~").toMutableList()
        // Remove one disclosure (keep the JWS at index 0 and one disclosure)
        val tamperedCompact = "${splits[0]}~${splits[1]}~"
        val result = SdJwt.validate(tamperedCompact, now = now)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("missing from SD-JWT disclosures") })
    }

    @Test
    fun testInvalidSignature() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
            }
        )
        val splits = sdJwt.compactSerialization.split("~")
        val jwtSplits = splits[0].split(".").toMutableList()
        jwtSplits[2] = "corruptedSignature"
        val corruptedJws = jwtSplits.joinToString(".")
        val tamperedCompact = "$corruptedJws~${splits.subList(1, splits.size).joinToString("~")}"
        val result = SdJwt.validate(tamperedCompact, now = now)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("signature", ignoreCase = true) }, "Errors: ${result.errors.map { it.message }}")
    }

    @Test
    fun testIssuerCertMissingKeyUsage() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(
            validFrom = now - 1.days,
            validUntil = now + 30.days,
            keyUsages = setOf(X509KeyUsage.KEY_CERT_SIGN) // missing DIGITAL_SIGNATURE!
        )
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "John")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/vct")
                put("iat", now.epochSeconds)
            }
        )
        val result = sdJwt.validate(now = now)
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("does not have DIGITAL_SIGNATURE key usage") })
    }

    @Test
    fun testPureRfc9901SdJwt() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("sub", "user_123")
                put("email", "user@example.com")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("iat", now.epochSeconds)
                put("exp", (now + 1.days).epochSeconds)
            },
            type = "sd-jwt"
        )
        // RFC 9901 validation should succeed completely with no warnings (no vct expected, "sd-jwt" typ is valid)
        val result = sdJwt.validate(now = now + 1.hours)
        assertFalse(result.hasErrors, "Errors: ${result.errors.map { it.message }}")
        assertFalse(result.hasWarnings, "Warnings: ${result.warnings.map { it.message }}")

        // But validateVc should fail because typ is "sd-jwt" (not a VC type) and "vct" is missing
        val vcResult = sdJwt.validateVc(now = now + 1.hours)
        assertTrue(vcResult.hasErrors)
        assertTrue(vcResult.errors.any { it.message.contains("missing 'vct' claim") })
        assertTrue(vcResult.errors.any { it.message.contains("typ") })
    }

    @Test
    fun testSdJwtValidateVc() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)
        val sdJwt = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "Alice")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/pid")
                put("iat", now.epochSeconds)
                put("exp", (now + 1.days).epochSeconds)
            },
            type = "vc+sd-jwt"
        )
        val matchingResult = sdJwt.validateVc(expectedVct = "https://example.com/pid", now = now + 1.hours)
        assertFalse(matchingResult.hasErrors, "Errors: ${matchingResult.errors.map { it.message }}")

        val mismatchResult = sdJwt.validateVc(expectedVct = "https://example.com/other", now = now + 1.hours)
        assertTrue(mismatchResult.hasErrors)
        assertTrue(mismatchResult.errors.any { it.message.contains("does not match expected") })
    }

    @Test
    fun testSdJwtValidateVcDisallowedDisclosuresAndStatus() = runTest {
        val now = Instant.fromEpochSeconds(1700000000)
        val issuerKey = createIssuerKey(validFrom = now - 1.days, validUntil = now + 30.days)

        // Selectively disclosing 'vct' is allowed in generic RFC 9901, but forbidden in SD-JWT VC
        val sdJwtWithVctDisclosure = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("vct", "https://example.com/pid")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("iat", now.epochSeconds)
                put("exp", (now + 1.days).epochSeconds)
            },
            type = "vc+sd-jwt"
        )
        // RFC 9901 validate() passes (vct is not forbidden in RFC 9901)
        val rfcResult = sdJwtWithVctDisclosure.validate(now = now + 1.hours)
        assertFalse(rfcResult.hasErrors, "RFC errors: ${rfcResult.errors.map { it.message }}")

        // validateVc() fails on selective disclosure of vct
        val vcResult = sdJwtWithVctDisclosure.validateVc(now = now + 1.hours)
        assertTrue(vcResult.hasErrors)
        assertTrue(vcResult.errors.any { it.message.contains("Claim 'vct' cannot be selectively disclosed") })

        // Malformed status claim in nonSdClaims
        val sdJwtWithInvalidStatus = SdJwt.create(
            issuerKey = issuerKey,
            kbKey = null,
            claims = buildJsonObject {
                put("given_name", "Alice")
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", "https://example.com/pid")
                put("status", "not_a_json_object")
                put("iat", now.epochSeconds)
                put("exp", (now + 1.days).epochSeconds)
            },
            type = "vc+sd-jwt"
        )
        val invalidStatusResult = sdJwtWithInvalidStatus.validateVc(now = now + 1.hours)
        assertTrue(invalidStatusResult.hasErrors)
        assertTrue(invalidStatusResult.errors.any { it.message.contains("'status' claim is not a JSON object") })
    }
}
