package org.multipaz.digitalcredentials

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import kotlin.test.Test
import kotlin.test.assertEquals

// Tests for the issuance matcher in multipaz-dcapi-credman/src/androidMain/matcher/issuance_matcher.cpp
class IssuanceMatcherTest {

    init {
        System.loadLibrary("MatcherTest")
    }

    external fun runIssuanceMatcher(
        request: ByteArray,
        creationOptions: ByteArray,
    ): String

    private val offer = """
        {
          "credential_issuer": "https://issuer.example.com",
          "credential_configuration_ids": ["mDL"],
          "grants": {
            "urn:ietf:params:oauth:grant-type:pre-authorized_code": {"pre-authorized_code": "1234"}
          }
        }
    """.trimIndent()

    private val creationOptions = buildCredmanCreationOptions(
        title = "Multipaz",
        subtitle = "Save to Multipaz",
        icon = byteArrayOf(1, 2, 3, 4),
        protocols = setOf("openid4vci-v1"),
    )

    private fun run(request: String, options: ByteArray = creationOptions): String =
        runIssuanceMatcher(request.encodeToByteArray(), options)

    private val expectedEntry = """
        Entry
          cred_id openid4vci
          title Multipaz
          subtitle Save to Multipaz
          icon 4 bytes

    """.trimIndent()

    @Test
    fun matchesCredentialOffer() {
        assertEquals(
            expectedEntry,
            run("""{"requests": [{"protocol": "openid4vci-v1", "data": $offer}]}"""),
        )
    }

    @Test
    fun matchesOnlyOnce() {
        val multiProtocolOptions = buildCredmanCreationOptions(
            title = "Multipaz",
            subtitle = "Save to Multipaz",
            icon = byteArrayOf(1, 2, 3, 4),
            protocols = setOf("openid4vci-v1", "openid4vci"),
        )
        assertEquals(
            expectedEntry,
            run(
                """
                    {
                      "requests": [
                        {"protocol": "openid4vp-v1-unsigned", "data": {}},
                        {"protocol": "openid4vci-v1", "data": $offer},
                        {"protocol": "openid4vci", "data": $offer}
                      ]
                    }
                """.trimIndent(),
                multiProtocolOptions,
            )
        )
    }

    @Test
    fun noMatchWhenUnregisteredWithEmptyProtocols() {
        val emptyProtocolOptions = buildCredmanCreationOptions(
            title = "Multipaz",
            subtitle = "Save to Multipaz",
            icon = byteArrayOf(1, 2, 3, 4),
            protocols = emptySet(),
        )
        assertEquals(
            "",
            run(
                """{"requests": [{"protocol": "openid4vci-v1", "data": $offer}]}""",
                emptyProtocolOptions,
            )
        )
    }

    @Test
    fun noMatchWithoutCredentialOffer() {
        for (request in listOf(
            """{"requests": [{"protocol": "openid4vp-v1-unsigned", "data": $offer}]}""",
            """{"requests": [{"protocol": "openid4vci", "data": $offer}]}""",
            """{"requests": [{"protocol": "openid4vci-v1"}]}""",
            """{"requests": [{"protocol": "openid4vci-v1", "data": "not an object"}]}""",
            """{"requests": [{"protocol": "openid4vci-v1", "data": {"credential_issuer": "https://a.example.com"}}]}""",
            """{"requests": [{"protocol": "openid4vci-v1", "data": {
                "credential_issuer": "https://a.example.com", "credential_configuration_ids": []}}]}""",
            """{"requests": "not an array"}""",
            """not json""",
        )) {
            assertEquals("", run(request), request)
        }
    }

    @Test
    fun optionalSubtitleAndIcon() {
        assertEquals(
            """
                Entry
                  cred_id openid4vci
                  title Multipaz
                  subtitle (null)
                  icon 0 bytes

            """.trimIndent(),
            run(
                """{"requests": [{"protocol": "openid4vci-v1", "data": $offer}]}""",
                buildCredmanCreationOptions(
                    title = "Multipaz",
                    subtitle = null,
                    icon = null,
                    protocols = setOf("openid4vci-v1"),
                )
            )
        )
    }

    @Test
    fun noMatchWithMalformedCreationOptions() {
        val request = """{"requests": [{"protocol": "openid4vci-v1", "data": $offer}]}"""
        assertEquals("", run(request, byteArrayOf()))
        assertEquals("", run(request, byteArrayOf(0x01)))
        // Not a map
        assertEquals("", run(request, Cbor.encode(Tstr("Multipaz"))))
        // No title
        assertEquals("", run(request, Cbor.encode(buildCborMap { put("subtitle", "Save to Multipaz") })))
        // No protocols
        assertEquals("", run(request, Cbor.encode(buildCborMap { put("title", "Multipaz") })))
    }
}
