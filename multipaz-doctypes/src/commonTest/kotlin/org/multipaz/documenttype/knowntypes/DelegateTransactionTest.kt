package org.multipaz.documenttype.knowntypes

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.presentment.TransactionData
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelegateTransactionTest {

    /** Two AP2 "open" mandates — what a user authorizes for an agent to spend later. */
    private val mandates = listOf(
        Json.parseToJsonElement(
            """
            {
              "vct": "mandate.checkout.open.1",
              "constraints": [{"type": "checkout.line_items", "allowed": ["oak-whiskey"]}],
              "cnf": {"jwk": {"kty": "EC", "crv": "P-256", "x": "agent-x", "y": "agent-y"}}
            }
            """.trimIndent()
        ).jsonObject,
        Json.parseToJsonElement(
            """
            {
              "vct": "mandate.payment.open.1",
              "constraints": [{"type": "payment.amount_range", "currency": "USD", "max": 5000}],
              "cnf": {"jwk": {"kty": "EC", "crv": "P-256", "x": "agent-x", "y": "agent-y"}}
            }
            """.trimIndent()
        ).jsonObject,
    )

    /** An RFC 9901 §4.2.4.2 array disclosure: base64url(JSON([salt, value])). */
    private fun disclosureOf(mandate: JsonObject, salt: String = "0123456789abcdef01234567"): String =
        Json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(salt), mandate))
        ).encodeToByteArray().toBase64Url()

    private fun payload(index: Int = 0) = DelegateTransaction.Payload(
        format = "dSD-JWT",
        delegatePayloadDisclosure = disclosureOf(mandates[index]),
        delegatePayload = mandates[index],
    )

    private fun transactionData(
        payload: DelegateTransaction.Payload = payload(),
        hashAlgorithms: List<Algorithm>? = null,
    ): TransactionData<DelegateTransaction.Payload> {
        val jsonString = DelegateTransaction.serializeOpenId4VpRequest(payload, listOf("dpc_credential"), hashAlgorithms)
        return DelegateTransaction.parseJson(jsonString.encodeToByteArray().toBase64Url().encodeToByteString())
    }

    // ---------------------------------------------------------------------------------------
    // Delegate SD-JWT §7.1 — the request
    // ---------------------------------------------------------------------------------------

    @Test
    fun requestRoundTrips() {
        val jsonString = DelegateTransaction.serializeOpenId4VpRequest(payload(), listOf("dpc_credential"))
        val obj = Json.parseToJsonElement(jsonString).jsonObject

        // The members §7.1 marks REQUIRED on a delegate transaction data object.
        assertEquals("delegate", obj["type"]!!.jsonPrimitive.content)
        assertEquals("dSD-JWT", obj["format"]!!.jsonPrimitive.content)
        assertTrue(obj["delegate_payload_disclosure"]!!.jsonPrimitive.isString)
        assertContentEquals(listOf("dpc_credential"), obj["credential_ids"]!!.jsonArray.map { it.jsonPrimitive.content })

        // AP2's `agent_authorization.md` puts the mandate objects here in the clear. Following the
        // draft means this member is absent — if it comes back, we silently switched specs.
        assertEquals(null, obj["delegate_payload"])

        val parsed = DelegateTransaction.parseOpenId4VpRequest(jsonString)
        assertEquals("dSD-JWT", parsed.format)
        assertEquals(mandates[0], parsed.delegatePayload)
    }

    @Test
    fun theMandateContentSurvivesTheRoundTripVerbatim() {
        // The digest the KB-JWT carries is taken over the disclosure's own bytes, so a verbatim
        // round trip here is what guarantees the signature covers what was requested. Re-encoding,
        // reordering, or dropping an unknown member would change what the signature means.
        val parsed = transactionData().payload
        assertEquals(disclosureOf(mandates[0]), parsed.delegatePayloadDisclosure)
        assertEquals(mandates[0], parsed.delegatePayload)
        assertEquals("mandate.checkout.open.1", parsed.delegatePayload["vct"]!!.jsonPrimitive.content)
        assertEquals(
            "oak-whiskey",
            parsed.delegatePayload["constraints"]!!.jsonArray[0].jsonObject["allowed"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun theClaimIsNotNested() {
        // Delegate SD-JWT fixes `delegate_payload` at the KB-JWT top level, as an array. The
        // default nesting would wrap it in an object under the type identifier and produce a
        // payload no verifier written against that draft can read. If this flips to true, the
        // request still "works" and every downstream verifier silently stops recognising it.
        assertFalse(DelegateTransaction.nestSdJwtResponseClaims)
    }

    // BYPASS: the claim name is what a verifier looks for. `_delegate_payload` came from
    // datatracker's rendering of the draft's Markdown italics, not from the draft's prose.
    @Test
    fun theClaimNameHasNoLeadingUnderscore() {
        assertEquals("delegate_payload", DelegateTransaction.DELEGATE_PAYLOAD_CLAIM)
        assertFalse(DelegateTransaction.DELEGATE_PAYLOAD_CLAIM.startsWith("_"))
    }

    // BYPASS: §5.1.4 gives the Delegate key binding its own media type. A wallet still emitting
    // the ordinary `kb+jwt` has not applied the extension, and a verifier reading `typ` to tell
    // the two apart would reject it — or, worse, accept a plain key binding as a delegation.
    @Test
    fun theKeyBindingHasItsOwnMediaType() {
        assertEquals("kb+sd-jwt", DelegateTransaction.sdJwtKbType)
        assertEquals("kb+sd-jwt", DelegateTransaction.DELEGATE_KB_TYPE)
    }

    @Test
    fun aMissingDisclosureIsRefused() {
        val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"]}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    @Test
    fun anEmptyDisclosureIsRefused() {
        // A wallet that signed this would hand back a key binding authorizing nothing, while
        // the user believed they had approved something.
        val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],"delegate_payload_disclosure":""}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    // BYPASS: AP2's plain `delegate_payload` array must NOT be quietly accepted. Signing it would
    // produce a key binding this wallet cannot honestly describe as conforming to either document.
    @Test
    fun ap2sPlainDelegatePayloadIsRefused() {
        val jsonString = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],"delegate_payload":[{"vct":"mandate.checkout.open.1"}]}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    @Test
    fun aDisclosureThatIsNotAnArrayDisclosureIsRefused() {
        // [salt, name, value] is the object-property form; a delegate payload is an array element.
        val threeElements = Json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("salt"), JsonPrimitive("key"), mandates[0]))
        ).encodeToByteArray().toBase64Url()
        val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],"delegate_payload_disclosure":"$threeElements"}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    @Test
    fun aMissingFormatIsRefused() {
        val jsonString = """{"type":"delegate","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(mandates[0])}"}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    @Test
    fun optionalDisclosuresSurvive() {
        val withDisclosures = payload().copy(
            delegateDisclosures = listOf(JsonPrimitive("WyJzYWx0IiwgImtleSIsICJ2YWwiXQ")),
        )
        val jsonString = DelegateTransaction.serializeOpenId4VpRequest(withDisclosures, listOf("dpc_credential"))
        val parsed = DelegateTransaction.parseOpenId4VpRequest(jsonString)
        assertEquals(1, parsed.delegateDisclosures?.size)
    }

    // ---------------------------------------------------------------------------------------
    // Delegate SD-JWT §7.1 — the hash algorithm the verifier asked for
    // ---------------------------------------------------------------------------------------

    // BYPASS: the requested algorithm was dropped on parse, so every request was answered in
    // SHA-256 and a SHA-384 verifier saw a digest mismatch it could not explain.
    @Test
    fun theRequestedHashAlgorithmSurvivesTheRoundTrip() {
        val data = transactionData(hashAlgorithms = listOf(Algorithm.SHA384))
        assertEquals(listOf(Algorithm.SHA384), data.hashAlgorithms)
    }

    @Test
    fun aRequestThatNamesNoAlgorithmReportsNone() {
        // Absent means "the default applies", not "SHA-384 was refused" — the caller decides.
        assertEquals(null, transactionData().hashAlgorithms)
    }

    // ---------------------------------------------------------------------------------------
    // Delegate SD-JWT §7.1 — what the KB-JWT carries
    // ---------------------------------------------------------------------------------------

    @Test
    fun theKeyBindingCarriesTheDigestNotThePlainMandate() = runTest {
        val data = transactionData()
        val digest = Crypto.digest(Algorithm.SHA256, data.payload.delegatePayloadDisclosure.encodeToByteArray()).toBase64Url()

        val array = DelegateTransaction.delegatePayloadClaim(data)
        assertEquals(1, array.size)
        // RFC 9901 §4.2.4.2: a replaced array element is `{"...": "<digest>"}`.
        assertEquals(digest, array[0].jsonObject["..."]!!.jsonPrimitive.content)
        // The mandate itself must NOT be in there in the clear — §7.1 says digest.
        assertEquals(null, array[0].jsonObject["vct"])
    }

    // BYPASS: answering in a different algorithm than was requested produces a digest the verifier
    // cannot match, and no error that says why.
    @Test
    fun theDigestUsesTheAlgorithmTheVerifierAskedFor() = runTest {
        val data = transactionData(hashAlgorithms = listOf(Algorithm.SHA384))
        val expected = Crypto.digest(Algorithm.SHA384, data.payload.delegatePayloadDisclosure.encodeToByteArray()).toBase64Url()

        val digest = DelegateTransaction.delegatePayloadClaim(data)[0].jsonObject["..."]!!.jsonPrimitive.content
        assertEquals(expected, digest)
        assertEquals(64, digest.length) // base64url SHA-384, not the 43 of SHA-256
    }

    // ---------------------------------------------------------------------------------------
    // AP2: "The User is shown the Mandate Content on a Trusted Surface."
    // ---------------------------------------------------------------------------------------

    @Test
    fun theMandateIsReadableOnScreen() {
        val checkout = DelegateTransaction.summarize(payload(0)).joinToString(" | ") { "${it.label}: ${it.value}" }
        val payment = DelegateTransaction.summarize(payload(1)).joinToString(" | ") { "${it.label}: ${it.value}" }

        // The limits, where it can be spent, and which agent it empowers — all present without
        // the wallet knowing anything about AP2's mandate types.
        assertTrue(checkout.contains("oak-whiskey"), checkout)
        // Readable money, not minor units: 5000 is $50.00, and this is the one screen that
        // exists so the person reads the limit correctly.
        assertTrue(payment.contains("50.00 USD"), payment)
        assertTrue(!payment.contains("5000"), payment)
        assertTrue(checkout.contains("Authorized agent key"), checkout)
        assertTrue(payment.contains("Authorized agent key"), payment)
        // Raw key coordinates tell a person nothing; an excerpt at least distinguishes agents.
        assertTrue(checkout.contains("agent-x"), checkout)
    }

    @Test
    fun theMandateTypeIsTheHeading() {
        assertEquals("mandate.checkout.open.1", DelegateTransaction.mandateType(mandates[0]))
        assertEquals("", DelegateTransaction.mandateType(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun expiryIsShownAsADateNotEpochSeconds() {
        val withExp = Json.parseToJsonElement("""{"vct":"mandate.payment.open.1","exp":1788994560}""").jsonObject
        val lines = DelegateTransaction.summarize(
            DelegateTransaction.Payload("dSD-JWT", disclosureOf(withExp), withExp)
        )
        val exp = lines.single { it.label == "Valid until" }
        assertTrue(exp.value.startsWith("2026-"), exp.value)
        assertTrue(!exp.value.contains("1788994560"), exp.value)
    }

    // BYPASS: the whole point of the consent screen is that the signature covers terms the
    // person read. A mandate with nothing to show would produce a blank approval.
    @Test
    fun aMandateThatCannotBeShownIsRefused() {
        val blank = Json.parseToJsonElement("""{"vct":"mandate.payment.open.1"}""").jsonObject
        val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(blank)}"}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    @Test
    fun anEmptyAllowListSaysSoInsteadOfNothing() {
        // An empty allow-list usually means NOTHING is permitted. Rendering it as blank — or,
        // worse, as "any" — would misreport the very limit being approved.
        val empty = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.line_items","allowed":[]}]}"""
        ).jsonObject
        val line = DelegateTransaction.summarize(DelegateTransaction.Payload("dSD-JWT", disclosureOf(empty), empty))
            .single { it.label.contains("line items", ignoreCase = true) }
        assertEquals("(none)", line.value)
    }

    @Test
    fun zeroDecimalCurrenciesAreNotGivenCents() {
        val yen = Json.parseToJsonElement(
            """{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.budget","currency":"JPY","max":5000}]}"""
        ).jsonObject
        val lines = DelegateTransaction.summarize(DelegateTransaction.Payload("dSD-JWT", disclosureOf(yen), yen))
        assertTrue(lines.any { it.value.contains("5000 JPY") }, lines.toString())
    }
}
