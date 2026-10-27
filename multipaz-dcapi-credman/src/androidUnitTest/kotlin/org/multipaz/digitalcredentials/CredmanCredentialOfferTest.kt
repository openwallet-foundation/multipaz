package org.multipaz.digitalcredentials

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
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
    fun findsOfferForSupportedProtocol() {
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
    fun acceptsAllProtocolVariants() {
        for (protocol in CredmanCredentialOffer.SUPPORTED_PROTOCOLS) {
            val request = json("""{"requests": [{"protocol": "$protocol", "data": $offer}]}""")
            assertEquals(protocol, CredmanCredentialOffer.fromRequest(request)?.protocol)
        }
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
    }

    @Test
    fun noOfferWithoutSupportedProtocol() {
        assertNull(CredmanCredentialOffer.fromRequest(json("""{"requests": [{"protocol": "foo", "data": $offer}]}""")))
        assertNull(CredmanCredentialOffer.fromRequest(json("""{"requests": "not an array"}""")))
        assertNull(CredmanCredentialOffer.fromRequest(json("""{}""")))
    }

    @Test
    fun offerUriContainsOffer() {
        val uri = CredmanCredentialOffer(protocol = "openid4vci-v1", offer = offer).offerUri
        val url = Url(uri)
        assertEquals("openid-credential-offer", url.protocol.name)
        assertEquals(offer, json(url.parameters["credential_offer"]!!))
    }

    @Test
    fun responseEchoesProtocol() {
        assertEquals(
            json("""{"protocol": "openid4vci", "data": {}}"""),
            CredmanCredentialOffer(protocol = "openid4vci", offer = offer).buildResponse()
        )
    }

    @Test
    fun creationOptions() {
        val options = Cbor.decode(buildCredmanCreationOptions("Wallet", "Save to Wallet", byteArrayOf(1, 2, 3)))
        assertEquals(Tstr("Wallet"), options["title"])
        assertEquals(Tstr("Save to Wallet"), options["subtitle"])
        assertEquals(Bstr(byteArrayOf(1, 2, 3)), options["icon"])

        val minimal = Cbor.decode(buildCredmanCreationOptions("Wallet", null, null))
        assertEquals(Tstr("Wallet"), minimal["title"])
        assertNull(minimal.getOrNull("subtitle"))
        assertNull(minimal.getOrNull("icon"))
    }
}
