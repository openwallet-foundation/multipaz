package org.multipaz.claim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import org.multipaz.provisioning.openid4vci.JsonParsing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// "Family Name" in Japanese. A non-ASCII label is used on purpose so that multi-byte text goes through JSON
// parsing, the CBOR round trip and language matching.
private const val FAMILY_NAME_JA = "氏"

class ClaimDescriptionTest {

    private val parser = JsonParsing("Issuer metadata")

    private fun credentialMetadata(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun extractClaimsFromIssuerMetadata() {
        val claims = parser.extractClaims(credentialMetadata("""
            {
              "display": [{"name": "Residence Record", "locale": "en-US"}],
              "claims": [
                {
                  "path": ["org.example.residence.1", "family_name"],
                  "mandatory": true,
                  "display": [
                    {"name": "Family Name", "locale": "en-US"},
                    {"name": "$FAMILY_NAME_JA", "locale": "ja-JP"}
                  ]
                },
                {"path": ["org.example.residence.1", "address"]}
              ]
            }
        """.trimIndent()))
        assertEquals(
            listOf(
                ClaimDescription(
                    path = buildJsonArray {
                        add("org.example.residence.1")
                        add("family_name")
                    },
                    display = listOf(ClaimDisplay("Family Name", "en-US"), ClaimDisplay(FAMILY_NAME_JA, "ja-JP"))
                ),
                ClaimDescription(
                    path = buildJsonArray {
                        add("org.example.residence.1")
                        add("address")
                    },
                    display = emptyList()
                )
            ),
            claims
        )
    }

    @Test
    fun extractClaimsWithoutClaimsArray() {
        assertNull(parser.extractClaims(null))
        assertNull(parser.extractClaims(credentialMetadata("""{"display": []}""")))
        // Pre-1.0 drafts used an object keyed by namespace instead of an array.
        assertNull(parser.extractClaims(credentialMetadata("""{"claims": {"org.example.1": {}}}""")))
    }

    @Test
    fun extractClaimsSkipsMalformedDescriptions() {
        val claims = parser.extractClaims(credentialMetadata("""
            {
              "claims": [
                "not an object",
                {"display": [{"name": "No path"}]},
                {"path": [], "display": [{"name": "Empty path"}]},
                {"path": ["a", -1], "display": [{"name": "Negative index"}]},
                {"path": ["a", 1.5], "display": [{"name": "Fractional index"}]},
                {"path": ["a", true], "display": [{"name": "Boolean"}]},
                {"path": ["a"], "display": {"name": "Not an array"}},
                {"path": ["a"], "display": ["not an object"]},
                {"path": ["a"], "display": [{"name": 42}]},
                {"path": ["a"], "display": [{"name": "Numeric locale", "locale": 42}]},
                {"path": ["address", null, 0], "display": [{"locale": "en"}, {"name": null, "locale": "de"}]}
              ]
            }
        """.trimIndent()))
        // Only the last one is well-formed; `name` and `locale` are both optional.
        assertEquals(
            listOf(
                ClaimDescription(
                    path = buildJsonArray {
                        add("address")
                        add(JsonNull)
                        add(0)
                    },
                    display = listOf(ClaimDisplay(null, "en"), ClaimDisplay(null, "de"))
                )
            ),
            claims
        )
    }

    @Test
    fun getDisplayName() {
        val description = ClaimDescription(
            path = buildJsonArray { add("family_name") },
            display = listOf(
                ClaimDisplay(null, "fr"),
                ClaimDisplay("Family Name", "en-US"),
                ClaimDisplay(FAMILY_NAME_JA, "ja-JP")
            )
        )
        assertEquals(FAMILY_NAME_JA, description.getDisplayName(listOf("ja")))
        assertEquals("Family Name", description.getDisplayName(listOf("en-GB")))
        // The French object has no name, so it is not a candidate and English is used instead.
        assertEquals("Family Name", description.getDisplayName(listOf("fr")))
        val unnamed = ClaimDescription(buildJsonArray { add("x") }, listOf(ClaimDisplay(null, "en")))
        assertNull(unnamed.getDisplayName(listOf("en")))
    }

    @Test
    fun cborRoundTrip() {
        val descriptions = listOf(
            ClaimDescription(
                path = buildJsonArray {
                    add("org.example.residence.1")
                    add("family_name")
                },
                display = listOf(
                    ClaimDisplay(FAMILY_NAME_JA, "ja-JP"),
                    ClaimDisplay("Family Name", null),
                    ClaimDisplay(null, "de")
                )
            ),
            ClaimDescription(
                path = buildJsonArray {
                    add("address")
                    add(JsonNull)
                    add(3)
                },
                display = emptyList()
            )
        )
        assertEquals(descriptions, decodeClaimDescriptions(descriptions.encodeToCbor()))
    }

    @Test
    fun findDisplayName() {
        val descriptions = listOf(
            ClaimDescription(buildJsonArray { add("ns"); add("a") }, listOf(ClaimDisplay("A", "en"))),
            ClaimDescription(buildJsonArray { add("ns"); add("b") }, listOf(ClaimDisplay("B", "en")))
        )
        assertEquals("B", descriptions.findDisplayName(buildJsonArray { add("ns"); add("b") }, listOf("en")))
        assertNull(descriptions.findDisplayName(buildJsonArray { add("ns"); add("c") }, listOf("en")))
        assertNull(descriptions.findDisplayName(buildJsonArray { add("b") }, listOf("en")))
    }
}
