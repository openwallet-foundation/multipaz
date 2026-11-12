package org.multipaz.digitalcredentials

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredmanCredentialOfferTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val offer = json("""
        {
          "credential_issuer": "https://issuer.example.com",
          "credential_configuration_ids": ["mDL"],
          "grants": {
            "urn:ietf:params:oauth:grant-type:pre-authorized_code": {
              "pre-authorized_code": "a b&c=d"
            }
          }
        }
    """.trimIndent())

    @Test
    fun findsOffer() {
        val request = json("""
            {
              "requests": [
                {"protocol": "openid4vp-v1-unsigned", "data": {}},
                {"protocol": "openid4vci-v1", "data": $offer}
              ]
            }
        """.trimIndent())
        assertEquals(
            CredmanCredentialOffer(protocol = "openid4vci-v1", offer = offer),
            CredmanCredentialOffer.fromRequest(request)
        )
    }

    @Test
    fun skipsMalformedRequests() {
        val request = json("""
            {
              "requests": [
                "not an object",
                {"protocol": 42, "data": $offer},
                {"protocol": "openid4vci-v1"},
                {"protocol": "openid4vci-v1", "data": "not an object"},
                {"protocol": "openid4vci-v1", "data": {"credential_configuration_ids": ["mDL"]}},
                {"protocol": "openid4vci-v1", "data": {"credential_issuer": "https://a.example.com"}},
                {"protocol": "openid4vci-v1", "data": {
                  "credential_issuer": "https://a.example.com", "credential_configuration_ids": []
                }}
              ]
            }
        """.trimIndent())
        assertNull(CredmanCredentialOffer.fromRequest(request))
        assertNull(CredmanCredentialOffer.fromRequest(json("""{"requests": "not an array"}""")))
        assertNull(CredmanCredentialOffer.fromRequest(json("""{}""")))
    }

    @Test
    fun responseEchoesProtocol() {
        assertEquals(
            json("""{"protocol": "openid4vci-v1", "data": {}}"""),
            CredmanCredentialOffer(protocol = "openid4vci-v1", offer = offer).buildResponse()
        )
    }

    @Test
    fun creationOptions() {
        val options = Cbor.decode(
            buildCredmanCreationOptions(
                title = "Wallet",
                subtitle = "Save to Wallet",
                icon = byteArrayOf(1, 2, 3),
                protocols = setOf("openid4vci-v1"),
            )
        )
        assertEquals(Tstr("Wallet"), options["title"])
        assertEquals(Tstr("Save to Wallet"), options["subtitle"])
        assertEquals(Bstr(byteArrayOf(1, 2, 3)), options["icon"])
        assertEquals(buildCborArray { add("openid4vci-v1") }, options["protocols"])

        val minimal = Cbor.decode(buildCredmanCreationOptions("Wallet", null, null, emptySet()))
        assertEquals(Tstr("Wallet"), minimal["title"])
        assertNull(minimal.getOrNull("subtitle"))
        assertNull(minimal.getOrNull("icon"))
        assertEquals(buildCborArray {}, minimal["protocols"])
    }
}
