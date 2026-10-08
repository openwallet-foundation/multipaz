package org.multipaz.sdjwt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisclosureValidationTest {

    private fun createValidSalt(): String = Crypto.secureRandom.nextBytes(16).toBase64Url()

    @Test
    fun testValidClaimDisclosure() {
        val disclosure = Disclosure(
            salt = createValidSalt(),
            claimName = "given_name",
            claimValue = JsonPrimitive("John"),
            disclosureString = "placeholder"
        )
        val result = disclosure.validate()
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
        assertFalse(disclosure.isArrayElement)
    }

    @Test
    fun testValidArrayDisclosure() {
        val disclosure = Disclosure(
            salt = createValidSalt(),
            claimName = null,
            claimValue = JsonPrimitive("US"),
            disclosureString = "placeholder"
        )
        val result = disclosure.validate()
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
        assertTrue(disclosure.isArrayElement)
    }

    @Test
    fun testSaltEntropyTooSmall() {
        val disclosure = Disclosure(
            salt = "short".encodeToByteArray().toBase64Url(),
            claimName = "given_name",
            claimValue = JsonPrimitive("John"),
            disclosureString = "placeholder"
        )
        val result = disclosure.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("less than 128 bits of entropy") })
    }

    @Test
    fun testEmptySalt() {
        val disclosure = Disclosure(
            salt = "",
            claimName = "given_name",
            claimValue = JsonPrimitive("John"),
            disclosureString = "placeholder"
        )
        val result = disclosure.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("salt cannot be empty") })
    }

    @Test
    fun testEmptyClaimName() {
        val disclosure = Disclosure(
            salt = createValidSalt(),
            claimName = "",
            claimValue = JsonPrimitive("John"),
            disclosureString = "placeholder"
        )
        val result = disclosure.validate()
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message.contains("claim name cannot be empty") })
    }

    @Test
    fun testReservedClaimNames() {
        for (name in listOf("_sd", "...")) {
            val disclosure = Disclosure(
                salt = createValidSalt(),
                claimName = name,
                claimValue = JsonPrimitive("value"),
                disclosureString = "placeholder"
            )
            val result = disclosure.validate()
            assertTrue(result.hasErrors)
            assertTrue(result.errors.any { it.message.contains("cannot be '$name'") })
        }
    }

    @Test
    fun testDisallowedClaimsFromSelectiveDisclosure() {
        for (name in listOf("iss", "exp", "nbf", "cnf", "aud", "_sd_alg")) {
            val disclosure = Disclosure(
                salt = createValidSalt(),
                claimName = name,
                claimValue = JsonPrimitive("value"),
                disclosureString = "placeholder"
            )
            val result = disclosure.validate()
            assertTrue(result.hasErrors)
            assertTrue(result.errors.any { it.message.contains("cannot be selectively disclosed") })
        }
    }

    @Test
    fun testValidateDisclosureString() {
        val validJson = buildJsonArray {
            add(JsonPrimitive(createValidSalt()))
            add(JsonPrimitive("family_name"))
            add(JsonPrimitive("Doe"))
        }
        val validStr = validJson.toString().encodeToByteArray().toBase64Url()
        val validResult = Disclosure.validate(validStr)
        assertFalse(validResult.hasErrors)

        // Invalid: only 1 element
        val invalidJson = buildJsonArray {
            add(JsonPrimitive(createValidSalt()))
        }
        val invalidStr = invalidJson.toString().encodeToByteArray().toBase64Url()
        val invalidResult = Disclosure.validate(invalidStr)
        assertTrue(invalidResult.hasErrors)
        assertTrue(invalidResult.errors.any { it.message.contains("Disclosure array must have 2 or 3 elements") })
    }

    @Test
    fun testDigestCalculation() = runTest {
        val salt = "2GLC42sKQveCfGfryNRN9w"
        val json = buildJsonArray {
            add(JsonPrimitive(salt))
            add(JsonPrimitive("given_name"))
            add(JsonPrimitive("John"))
        }
        val discStr = json.toString().encodeToByteArray().toBase64Url()
        val disclosure = Disclosure.fromDisclosureString(discStr)
        val digest = disclosure.calculateDigest(Algorithm.SHA256)
        val expectedDigest = Crypto.digest(Algorithm.SHA256, discStr.encodeToByteArray()).toBase64Url()
        assertEquals(expectedDigest, digest)
    }
}
