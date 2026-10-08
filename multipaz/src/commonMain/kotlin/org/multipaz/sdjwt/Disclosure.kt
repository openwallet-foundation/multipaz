package org.multipaz.sdjwt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.validation.ValidationResult
import org.multipaz.validation.buildValidationResult

/**
 * Represents a parsed SD-JWT disclosure according to RFC 9901 Section 5.1.
 *
 * A disclosure is either:
 * - An object property disclosure: `[salt, claimName, claimValue]`
 * - An array element disclosure: `[salt, claimValue]`
 *
 * @property salt the random salt string.
 * @property claimName the name of the claim, or `null` if this is an array element disclosure.
 * @property claimValue the selectively disclosable claim value.
 * @property disclosureString the base64url-encoded JSON array string.
 */
data class Disclosure(
    val salt: String,
    val claimName: String?,
    val claimValue: JsonElement,
    val disclosureString: String
) {
    /** `true` if this disclosure is for an array element, `false` if for an object property. */
    val isArrayElement: Boolean
        get() = claimName == null

    /**
     * Calculates the base64url-encoded digest of this disclosure using the given [digestAlgorithm].
     */
    suspend fun calculateDigest(digestAlgorithm: Algorithm): String {
        return Crypto.digest(digestAlgorithm, disclosureString.encodeToByteArray()).toBase64Url()
    }

    /**
     * Validates the internal structure and contents of this [Disclosure] according to RFC 9901.
     *
     * @return a [ValidationResult] containing any errors or warnings.
     */
    fun validate(): ValidationResult = buildValidationResult {
        // Salt checks
        if (salt.isEmpty()) {
            addError("Disclosure salt cannot be empty")
        } else {
            // Check salt entropy (RFC 9901 Section 5.1: MUST have at least 128 bits of entropy)
            val saltBytes = try {
                salt.fromBase64Url()
            } catch (_: Throwable) {
                salt.encodeToByteArray()
            }
            if (saltBytes.size < 16) {
                addError("Disclosure salt has less than 128 bits of entropy (${saltBytes.size} bytes, minimum 16 bytes required)")
            }
        }

        if (!isArrayElement) {
            val name = claimName!!
            if (name.isEmpty()) {
                addError("Disclosure claim name cannot be empty")
            }
            if (name == "_sd" || name == "...") {
                addError("Disclosure claim name cannot be '$name'")
            }
            if (name in DISALLOWED_DISCLOSURE_CLAIM_NAMES) {
                addError("Claim '$name' cannot be selectively disclosed per RFC 9901")
            }
        }
    }

    companion object {
        /**
         * Set of claim names that are disallowed from selective disclosure in RFC 9901 §5.1 and §6.3.
         */
        val DISALLOWED_DISCLOSURE_CLAIM_NAMES = setOf(
            "_sd",
            "...",
            "iss",
            "exp",
            "nbf",
            "cnf",
            "aud",
            "_sd_alg"
        )

        /**
         * Parses a [Disclosure] from its base64url-encoded string.
         *
         * @param disclosureString the base64url-encoded JSON array.
         * @return the parsed [Disclosure].
         * @throws IllegalArgumentException if the disclosure cannot be decoded or parsed.
         */
        fun fromDisclosureString(disclosureString: String): Disclosure {
            val jsonText = disclosureString.fromBase64Url().decodeToString()
            val jsonArray = Json.decodeFromString(JsonArray.serializer(), jsonText).jsonArray
            return when (jsonArray.size) {
                2 -> {
                    val salt = jsonArray[0].jsonPrimitive.content
                    val value = jsonArray[1]
                    Disclosure(
                        salt = salt,
                        claimName = null,
                        claimValue = value,
                        disclosureString = disclosureString
                    )
                }
                3 -> {
                    val salt = jsonArray[0].jsonPrimitive.content
                    val claimName = jsonArray[1].jsonPrimitive.content
                    val value = jsonArray[2]
                    Disclosure(
                        salt = salt,
                        claimName = claimName,
                        claimValue = value,
                        disclosureString = disclosureString
                    )
                }
                else -> throw IllegalArgumentException("Disclosure array must have 2 or 3 elements, had ${jsonArray.size}")
            }
        }

        /**
         * Validates a base64url-encoded disclosure string.
         *
         * @param disclosureString the base64url-encoded disclosure.
         * @return a [ValidationResult] with findings.
         */
        fun validate(disclosureString: String): ValidationResult = buildValidationResult {
            if (disclosureString.isEmpty()) {
                addError("Disclosure string cannot be empty")
                return@buildValidationResult
            }
            val disclosure = try {
                fromDisclosureString(disclosureString)
            } catch (e: Throwable) {
                addError("Failed to parse disclosure: ${e.message}")
                null
            }
            if (disclosure != null) {
                addAll(disclosure.validate())
            }
        }
    }
}
