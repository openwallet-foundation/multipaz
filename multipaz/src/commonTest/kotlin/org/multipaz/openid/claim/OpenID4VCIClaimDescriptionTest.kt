package org.multipaz.openid.claim

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

class OpenID4VCIClaimDescriptionTest {

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
                OpenID4VCIClaimDescription(
                    path = buildJsonArray {
                        add("org.example.residence.1")
                        add("family_name")
                    },
                    display = listOf(
                        OpenID4VCIClaimDisplay("Family Name", "en-US"),
                        OpenID4VCIClaimDisplay(FAMILY_NAME_JA, "ja-JP")
                    )
                ),
                OpenID4VCIClaimDescription(
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
                {"path": ["address", null, 0], "display": [{"locale": "en"}, {"name": null, "locale": "de"}]}
              ]
            }
        """.trimIndent()))
        // Only the last one is well-formed; `name` and `locale` are both optional.
        assertEquals(
            listOf(
                OpenID4VCIClaimDescription(
                    path = buildJsonArray {
                        add("address")
                        add(JsonNull)
                        add(0)
                    },
                    display = listOf(OpenID4VCIClaimDisplay(null, "en"), OpenID4VCIClaimDisplay(null, "de"))
                )
            ),
            claims
        )
    }

    @Test
    fun extractClaimsSkipsMalformedDisplays() {
        val claims = parser.extractClaims(credentialMetadata("""
            {
              "claims": [
                {
                  "path": ["given_name"],
                  "display": [
                    "not an object",
                    {"name": 42, "locale": "fr"},
                    {"name": "Numeric locale", "locale": 42},
                    {"name": "Given name", "locale": "en"},
                    {"name": "名", "locale": "ja"}
                  ]
                }
              ]
            }
        """.trimIndent()))
        // Malformed display objects are dropped while the well-formed ones are kept.
        assertEquals(
            listOf(
                OpenID4VCIClaimDescription(
                    path = buildJsonArray { add("given_name") },
                    display = listOf(OpenID4VCIClaimDisplay("Given name", "en"), OpenID4VCIClaimDisplay("名", "ja"))
                )
            ),
            claims
        )
    }

    @Test
    fun getDisplayName() {
        val description = OpenID4VCIClaimDescription(
            path = buildJsonArray { add("family_name") },
            display = listOf(
                OpenID4VCIClaimDisplay(null, "fr"),
                OpenID4VCIClaimDisplay("Family Name", "en-US"),
                OpenID4VCIClaimDisplay(FAMILY_NAME_JA, "ja-JP")
            )
        )
        assertEquals(FAMILY_NAME_JA, description.getDisplayName(listOf("ja")))
        assertEquals("Family Name", description.getDisplayName(listOf("en-GB")))
        // The French object has no name, so it is not a candidate and English is used instead.
        assertEquals("Family Name", description.getDisplayName(listOf("fr")))
        val unnamed = OpenID4VCIClaimDescription(
            buildJsonArray { add("x") },
            listOf(OpenID4VCIClaimDisplay(null, "en"))
        )
        assertNull(unnamed.getDisplayName(listOf("en")))
    }

    @Test
    fun cborRoundTrip() {
        val descriptions = listOf(
            OpenID4VCIClaimDescription(
                path = buildJsonArray {
                    add("org.example.residence.1")
                    add("family_name")
                },
                display = listOf(
                    OpenID4VCIClaimDisplay(FAMILY_NAME_JA, "ja-JP"),
                    OpenID4VCIClaimDisplay("Family Name", null),
                    OpenID4VCIClaimDisplay(null, "de")
                )
            ),
            OpenID4VCIClaimDescription(
                path = buildJsonArray {
                    add("address")
                    add(JsonNull)
                    add(3)
                },
                display = emptyList()
            )
        )
        assertEquals(descriptions, decodeOpenID4VCIClaimDescriptions(descriptions.encodeToCbor()))
    }

    @Test
    fun findDisplayName() {
        val descriptions = listOf(
            OpenID4VCIClaimDescription(
                buildJsonArray { add("ns"); add("a") },
                listOf(OpenID4VCIClaimDisplay("A", "en"))
            ),
            OpenID4VCIClaimDescription(
                buildJsonArray { add("ns"); add("b") },
                listOf(OpenID4VCIClaimDisplay("B", "en"))
            )
        )
        assertEquals("B", descriptions.findDisplayName(buildJsonArray { add("ns"); add("b") }, listOf("en")))
        assertNull(descriptions.findDisplayName(buildJsonArray { add("ns"); add("c") }, listOf("en")))
        assertNull(descriptions.findDisplayName(buildJsonArray { add("b") }, listOf("en")))
    }
}
