package org.multipaz.sdjwt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.toBase64Url

/** Helper extensions related to disclosures. */
object DisclosureUtil {

    /**
     * Creates a digest from a disclosure, using the provided digest algorithm.
     *
     * @receiver the [JsonArray] representing the disclosure.
     * @param digestAlgorithm the digest algorithm to use.
     * @return the [String] digest of the [JsonArray].
     */
    suspend fun JsonArray.toDigest(digestAlgorithm: Algorithm) = Crypto.digest(
        digestAlgorithm,
        toString()
            .encodeToByteArray()
            .toBase64Url()
            .encodeToByteArray()
    ).toBase64Url()

    /**
     * Creates the appropriate JSON Object for a Digest being inserted into a [JsonArray].
     *
     * @receiver the [JsonArray] representing the array disclosure.
     * @param digestAlgorithm the digest algorithm to use.
     * @return the [JsonObject] to insert into a [JsonArray] as a digest in place of the element.
     */
    suspend fun JsonArray.toArrayDigestElement(digestAlgorithm: Algorithm) = buildJsonObject {
        put("...", JsonPrimitive(toDigest(digestAlgorithm)))
    }

    /**
     * Creates the appropriate JSON Primitive for a Digest being inserted into a JSON Object.
     *
     * @receiver the [JsonArray] representing the array disclosure.
     * @param digestAlgorithm the digest algorithm to use.
     * @return the [JsonPrimitive] to add as the digest for a property.
     */
    suspend fun JsonArray.toClaimDigestElement(digestAlgorithm: Algorithm) = JsonPrimitive(
        toDigest(digestAlgorithm)
    )

    /**
     * Creates and inserts the digests for selectively disclosable object properties into a
     * JSON Object as defined SD-JWT 4.2.4.1.
     *
     * @receiver the [JsonObjectBuilder] to insert the digest clai into.
     * @param disclosures the list of [JsonArray]s representing the  disclosures.
     * @param digestAlgorithm the digest algorithm to use.
     * @return the [JsonObjectBuilder].
     */
    suspend fun JsonObjectBuilder.putClaimDisclosureDigests(
        disclosures: List<JsonArray>,
        digestAlgorithm: Algorithm,
    ): JsonObjectBuilder {
        if (disclosures.isNotEmpty()) {
            put(
                "_sd",
                JsonArray(disclosures.map {
                    it.toClaimDigestElement(digestAlgorithm)
                })
            )
        }
        return this
    }

    /**
     * Creates a disclosure for a [JsonElement] in a [JsonArray] as defined by SD-JWT 4.2.2.
     *
     * @receiver the [JsonElement] to turn into an Array Disclosures.
     * @param salt the salt to use in the disclosure.
     * @return the [JsonArray] representing the disclosure.
     */
    fun JsonElement.toArrayDisclosure(salt: String) = buildJsonArray {
        add(JsonPrimitive(salt))
        add(this@toArrayDisclosure)
    }

    /**
     *  Creates a disclosure for a [JsonElement] Object Property as defined by SD-JWT 4.2.1.
     *
     *  @receiver the [JsonElement] to turn into a Claim Disclosure.
     *  @param claimName the name of the claim.
     *  @param salt the salt to use in the disclosure.
     *  @return the [JsonArray] representing the disclosure.
     */
    fun JsonElement.toClaimDisclosure(
        claimName: String,
        salt: String
    ) = buildJsonArray {
        add(JsonPrimitive(salt))
        add(JsonPrimitive(claimName))
        add(this@toClaimDisclosure)
    }
}
