package org.multipaz.utopia.knowntypes

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.documenttype.knowntypes.addKnownTypes
import org.multipaz.presentment.TransactionData
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
            JsonArray.serializer(),
            JsonArray(listOf(JsonPrimitive(salt), mandate))
        ).encodeToByteArray().toBase64Url()

    private fun payload(index: Int = 0) = single(mandates[index])

    /** A Delegate SD-JWT §7.1 request payload carrying one mandate. */
    private fun single(content: JsonObject) = DelegateTransaction.Payload(
        format = "dSD-JWT",
        mandates = listOf(DelegateTransaction.Mandate(disclosureOf(content), content)),
    )

    private val DelegateTransaction.Payload.content: JsonObject get() = mandates.single().content
    private val DelegateTransaction.Payload.disclosure: String get() = mandates.single().disclosure

    private fun transactionData(
        payload: DelegateTransaction.Payload = payload(),
        hashAlgorithms: List<Algorithm>? = null,
    ): TransactionData<DelegateTransaction.Payload> {
        val jsonString = DelegateTransaction.serializeOpenId4VpRequest(payload, listOf("dpc_credential"), hashAlgorithms)
        return DelegateTransaction.parseJson(jsonString.encodeToByteArray().toBase64Url().encodeToByteString())
    }

    // Delegate SD-JWT §7.1 — the request

    @Test
    fun requestRoundTrips() {
        val jsonString = DelegateTransaction.serializeOpenId4VpRequest(payload(), listOf("dpc_credential"))
        val obj = Json.parseToJsonElement(jsonString).jsonObject

        // The members §7.1 marks REQUIRED on a delegate transaction data object.
        assertEquals("delegate", obj["type"]!!.jsonPrimitive.content)
        assertEquals("dSD-JWT", obj["format"]!!.jsonPrimitive.content)
        assertTrue(obj["delegate_payload_disclosure"]!!.jsonPrimitive.isString)
        assertContentEquals(listOf("dpc_credential"), obj["credential_ids"]!!.jsonArray.map { it.jsonPrimitive.content })

        // AP2 puts the mandate objects here in the clear; following the draft means it is absent.
        assertEquals(null, obj["delegate_payload"])

        val parsed = DelegateTransaction.parseOpenId4VpRequest(jsonString)
        assertEquals("dSD-JWT", parsed.format)
        assertEquals(mandates[0], parsed.content)
    }

    @Test
    fun theMandateContentSurvivesTheRoundTripVerbatim() {
        // The KB-JWT digest covers the disclosure's own bytes, so any re-encoding here would
        // change what the signature means.
        val parsed = transactionData().payload
        assertEquals(disclosureOf(mandates[0]), parsed.disclosure)
        assertEquals(mandates[0], parsed.content)
        assertEquals("mandate.checkout.open.1", parsed.content["vct"]!!.jsonPrimitive.content)
        assertEquals(
            "oak-whiskey",
            parsed.content["constraints"]!!.jsonArray[0].jsonObject["allowed"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }

    // Registered without a renderer, the consent prompt would show only a heading and still sign.
    @Test
    fun theUtopiaTypesLeaveItToTheApplication() {
        val repository = DocumentTypeRepository().apply {
            addKnownTypes("en-US")
            addUtopiaTypes("en-US")
        }
        assertNull(repository.getTransactionTypeByIdentifier(DelegateTransaction.identifier))
        assertNotNull(repository.getTransactionTypeByIdentifier(PaymentTransaction.identifier))
    }

    @Test
    fun theApplicationAddsItByItself() {
        val repository = DocumentTypeRepository().apply { addDelegateTransactionType() }
        assertSame(DelegateTransaction, repository.getTransactionTypeByIdentifier(DelegateTransaction.identifier))
    }

    @Test
    fun theClaimIsNotNested() {
        // Nesting would wrap the array in an object under the type identifier — the request would
        // still "work" while every verifier silently stopped recognising it.
        assertFalse(DelegateTransaction.nestSdJwtResponseClaims)
    }

    // `_delegate_payload` came from datatracker's rendering, not the draft's prose.
    @Test
    fun theClaimNameHasNoLeadingUnderscore() {
        assertEquals("delegate_payload", DelegateTransaction.DELEGATE_PAYLOAD_CLAIM)
        assertFalse(DelegateTransaction.DELEGATE_PAYLOAD_CLAIM.startsWith("_"))
    }

    // A wallet still emitting `kb+jwt` has not applied the extension (§5.1.4).
    @Test
    fun theKeyBindingHasItsOwnMediaType() {
        assertEquals("kb+sd-jwt", DelegateTransaction.sdJwtKbType)
        assertEquals("kb+sd-jwt", DelegateTransaction.DELEGATE_KB_TYPE)
        assertEquals("kb+sd-jwt+kb", DelegateTransaction.DELEGATE_KB_KB_TYPE)
    }

    private fun kbType(vararg payloads: DelegateTransaction.Payload) =
        TransactionData.sdJwtKbType(payloads.map { transactionData(it) })

    private val terminalPayload = JsonObject(mandates[0] - "cnf").let {
        single(it)
    }

    // An AP2 mandate names the agent's key in `cnf`, so the wallet's hop is a KB-SD-JWT+KB (§5.1.4).
    // Typed `kb+sd-jwt` it reads as the end of the chain, and a chain verifier refuses the agent's
    // hop after it.
    @Test
    fun aMandateNamingTheAgentsKeyIsTypedForFurtherDelegation() {
        assertEquals("kb+sd-jwt+kb", kbType(payload(0)))
        assertEquals("kb+sd-jwt+kb", kbType(payload(1)))
    }

    // The other half of the same rule: a mandate that names no further key ends the chain.
    @Test
    fun aMandateNamingNoFurtherKeyIsTypedTerminal() {
        assertEquals("kb+sd-jwt", kbType(terminalPayload))
    }

    // One key binding signs both mandates. With only the first item deciding, AP2's example of a
    // checkout mandate naming the agent's key and a payment mandate that does not was typed
    // terminal or not depending on the order the verifier listed them in.
    @Test
    fun theTypeIsDecidedOverAllMandatesInAnyOrder() {
        assertEquals("kb+sd-jwt+kb", kbType(terminalPayload, payload(0)))
        assertEquals("kb+sd-jwt+kb", kbType(payload(0), terminalPayload))
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
        // Signing this would authorize nothing while the user believed they had approved something.
        val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],"delegate_payload_disclosure":""}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    // AP2 sends its mandates in the clear, several in one item. The wallet makes their disclosures.
    @Test
    fun ap2sPlainDelegatePayloadIsAccepted() = runTest {
        val jsonString = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],""" +
            """"delegate_payload":[${mandates[0]},${mandates[1]}]}"""
        val parsed = DelegateTransaction.parseOpenId4VpRequest(jsonString)
        assertEquals(DelegateTransaction.RequestForm.PLAIN, parsed.requestForm)
        assertEquals(mandates, parsed.mandates.map { it.content })
        parsed.mandates.forEach { mandate ->
            // RFC 9901 §4.2.4.2: [salt, mandate], so the KB-JWT digest commits to the mandate shown.
            val decoded = Json.parseToJsonElement(mandate.disclosure.fromBase64Url().decodeToString()).jsonArray
            assertEquals(2, decoded.size)
            assertEquals(mandate.content, decoded[1])
            assertTrue(decoded[0].jsonPrimitive.content.fromBase64Url().size >= 16, "salt of at least 128 bits")
        }

        // One digest per mandate, each over its disclosure, and every disclosure follows the KB-JWT.
        val data = DelegateTransaction.parseJson(jsonString.encodeToByteArray().toBase64Url().encodeToByteString())
        val digests = DelegateTransaction.delegateClaims(data).getValue("delegate_payload").jsonArray
            .map { it.jsonObject["..."]!!.jsonPrimitive.content }
        assertEquals(
            data.payload.mandates.map { Crypto.digest(Algorithm.SHA256, it.disclosure.encodeToByteArray()).toBase64Url() },
            digests
        )
        assertEquals(data.payload.mandates.map { it.disclosure }, DelegateTransaction.responseDisclosures(data.payload))
    }

    // Fresh salts: a mandate the agent later withholds must not be guessable from its digest.
    @Test
    fun ap2MandatesGetAFreshSaltEachTime() {
        val jsonString = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],"delegate_payload":[${mandates[0]}]}"""
        val first = DelegateTransaction.parseOpenId4VpRequest(jsonString).disclosure
        val second = DelegateTransaction.parseOpenId4VpRequest(jsonString).disclosure
        assertTrue(first != second)
    }

    @Test
    fun ap2sPlainFormRoundTrips() {
        val jsonString = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],"delegate_payload":[${mandates[0]},${mandates[1]}]}"""
        val parsed = DelegateTransaction.parseOpenId4VpRequest(jsonString)
        val again = DelegateTransaction.parseOpenId4VpRequest(
            DelegateTransaction.serializeOpenId4VpRequest(parsed, listOf("c"))
        )
        assertEquals(DelegateTransaction.RequestForm.PLAIN, again.requestForm)
        assertEquals(mandates, again.mandates.map { it.content })
    }

    @Test
    fun malformedAp2RequestsAreRefused() {
        val disclosure = disclosureOf(mandates[0])
        for (members in listOf(
            // Both forms at once: which one would the signature cover?
            """"format":"dc+sd-jwt","delegate_payload":[${mandates[0]}],"delegate_payload_disclosure":"$disclosure"""",
            """"format":"dc+sd-jwt","delegate_payload":[]""",
            """"format":"dc+sd-jwt","delegate_payload":{}""",
            """"format":"dc+sd-jwt","delegate_payload":["not a mandate"]""",
            // A mandate with nothing to show would produce a blank approval.
            """"format":"dc+sd-jwt","delegate_payload":[{"vct":"mandate.checkout.open.1"}]""",
            // The plain form says which VDC format to return, not a §7.1 value.
            """"format":"dSD-JWT","delegate_payload":[${mandates[0]}]""",
            // And the §7.1 form takes only §7.1 values.
            """"format":"dc+sd-jwt","delegate_payload_disclosure":"$disclosure"""",
        )) {
            val jsonString = """{"type":"delegate","credential_ids":["c"],$members}"""
            assertFailsWith(IllegalArgumentException::class, members) {
                DelegateTransaction.parseOpenId4VpRequest(jsonString)
            }
        }
    }

    @Test
    fun aDisclosureThatIsNotAnArrayDisclosureIsRefused() {
        // [salt, name, value] is the object-property form; a delegate payload is an array element.
        val threeElements = Json.encodeToString(
            JsonArray.serializer(),
            JsonArray(listOf(JsonPrimitive("salt"), JsonPrimitive("key"), mandates[0]))
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

    // §7.1 allows two values; anything else is a request this type was not written for.
    @Test
    fun anUnknownFormatIsRefused() {
        val jsonString = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(mandates[0])}"}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    @Test
    fun bothFormatsOfTheDraftAreAccepted() {
        for (format in listOf("dSD-JWT", "dSD-JWT+KB")) {
            val jsonString = """{"type":"delegate","format":"$format","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(mandates[0])}"}"""
            assertEquals(format, DelegateTransaction.parseOpenId4VpRequest(jsonString).format)
        }
    }

    @Test
    fun optionalDisclosuresSurvive() {
        val withDisclosures = payload().copy(
            delegateDisclosures = listOf("WyJzYWx0IiwgImtleSIsICJ2YWwiXQ"),
        )
        val jsonString = DelegateTransaction.serializeOpenId4VpRequest(withDisclosures, listOf("dpc_credential"))
        val parsed = DelegateTransaction.parseOpenId4VpRequest(jsonString)
        assertEquals(listOf("WyJzYWx0IiwgImtleSIsICJ2YWwiXQ"), parsed.delegateDisclosures)
    }

    // They are appended to the presentation as they are, so anything but a disclosure string is refused.
    @Test
    fun delegateDisclosuresThatAreNotStringsAreRefused() {
        for (disclosures in listOf("[1]", "[\"\"]", "[\"a~b\"]", "\"WyJ4Il0\"")) {
            val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],""" +
                """"delegate_payload_disclosure":"${disclosureOf(mandates[0])}","delegate_disclosures":$disclosures}"""
            assertFailsWith(IllegalArgumentException::class, disclosures) {
                DelegateTransaction.parseOpenId4VpRequest(jsonString)
            }
        }
    }

    // Delegate SD-JWT §5.1.1: the mandate disclosure follows the KB-JWT, so a verifier resolves the
    // digest from the presentation instead of from the request it sent. Without it AP2's verifier
    // accepted the presentation and returned no mandate.
    @Test
    fun theMandateDisclosureFollowsTheKeyBinding() {
        assertEquals(listOf(payload().disclosure), DelegateTransaction.responseDisclosures(payload()))
        val withDisclosures = payload().copy(delegateDisclosures = listOf("WyJzYWx0IiwgImtleSIsICJ2YWwiXQ"))
        assertEquals(
            listOf(withDisclosures.disclosure, "WyJzYWx0IiwgImtleSIsICJ2YWwiXQ"),
            DelegateTransaction.responseDisclosures(withDisclosures)
        )
    }

    // Delegate SD-JWT §7.1 — the hash algorithm the verifier asked for

    // The requested algorithm was dropped on parse, so SHA-384 requests got SHA-256 back.
    @Test
    fun theRequestedHashAlgorithmSurvivesTheRoundTrip() {
        val data = transactionData(hashAlgorithms = listOf(Algorithm.SHA384))
        assertEquals(listOf(Algorithm.SHA384), data.hashAlgorithms)
    }

    @Test
    fun aRequestThatNamesNoAlgorithmReportsNone() {
        // Absent means "the default applies", not "refused" — the caller decides.
        assertEquals(null, transactionData().hashAlgorithms)
    }

    // Delegate SD-JWT §7.1 — what the KB-JWT carries

    @Test
    fun theKeyBindingCarriesTheDigestNotThePlainMandate() = runTest {
        val data = transactionData()
        val digest = Crypto.digest(Algorithm.SHA256, data.payload.disclosure.encodeToByteArray()).toBase64Url()

        val claims = DelegateTransaction.delegateClaims(data)
        val array = claims.getValue("delegate_payload").jsonArray
        assertEquals(1, array.size)
        // RFC 9901 §4.2.4.2: a replaced array element is `{"...": "<digest>"}`.
        assertEquals(digest, array[0].jsonObject["..."]!!.jsonPrimitive.content)
        // The mandate itself must NOT be in there in the clear — §7.1 says digest.
        assertEquals(null, array[0].jsonObject["vct"])
        assertEquals("sha-256", claims.getValue("_sd_alg").jsonPrimitive.content)
    }

    // §7.1 lets the digest use an algorithm the request names for the transaction data, but a
    // verifier only knows which one from `_sd_alg`: without it, it reads SHA-256 and finds nothing.
    @Test
    fun theDigestAlgorithmIsAlwaysNamed() = runTest {
        val data = transactionData(hashAlgorithms = listOf(Algorithm.SHA384))
        val expected = Crypto.digest(Algorithm.SHA384, data.payload.disclosure.encodeToByteArray()).toBase64Url()

        val claims = DelegateTransaction.delegateClaims(data)
        assertEquals(expected, claims.getValue("delegate_payload").jsonArray[0].jsonObject["..."]!!.jsonPrimitive.content)
        assertEquals("sha-384", claims.getValue("_sd_alg").jsonPrimitive.content)
    }

    // A mandate with nothing to show would produce a blank approval.
    @Test
    fun aMandateThatCannotBeShownIsRefused() {
        val blank = Json.parseToJsonElement("""{"vct":"mandate.payment.open.1"}""").jsonObject
        val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(blank)}"}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }


    private val merchant =
        """{"id":"shop.example.com","name":"utopia","origin":"https://shop.example.com"}"""

    // RFC 9901 §4.2: an array disclosure, and an object property disclosure.
    private fun encode(vararg members: JsonElement) = JsonArray(members.toList()).toString().encodeToByteArray().toBase64Url()

    private suspend fun digestOf(disclosure: String) =
        Crypto.digest(Algorithm.SHA256, disclosure.encodeToByteArray()).toBase64Url()

    private val merchantDisclosure get() = encode(JsonPrimitive("s1"), Json.parseToJsonElement(merchant))
    private val expDisclosure = encode(JsonPrimitive("s2"), JsonPrimitive("exp"), JsonPrimitive(1821496085))

    private suspend fun selectivelyDisclosedMandate(): JsonObject = Json.parseToJsonElement(
        """{"vct":"mandate.checkout.open.1","_sd":["${digestOf(expDisclosure)}"],"constraints":[
             {"type":"checkout.allowed_merchants","allowed":[{"...":"${digestOf(merchantDisclosure)}"}]}]}"""
    ).jsonObject

    private fun withDisclosures(raw: JsonObject, disclosures: List<String>) = DelegateTransaction.Payload(
        format = "dc+sd-jwt",
        mandates = listOf(DelegateTransaction.Mandate(disclosureOf(raw), raw)),
        delegateDisclosures = disclosures,
        requestForm = DelegateTransaction.RequestForm.PLAIN,
    )

    // What is signed is what is shown: terms behind digests are resolved from
    // `delegate_disclosures`, not displayed as the digests themselves.
    @Test
    fun selectivelyDisclosedTermsAreShown() = runTest {
        val raw = selectivelyDisclosedMandate()
        val shown = DelegateTransaction.resolveMandates(
            withDisclosures(raw, listOf(merchantDisclosure, expDisclosure)), Algorithm.SHA256
        ).single()

        assertEquals(
            Json.parseToJsonElement(
                """{"vct":"mandate.checkout.open.1","constraints":[
                     {"type":"checkout.allowed_merchants","allowed":[$merchant]}],"exp":1821496085}"""
            ),
            shown
        )
        val rows = DelegateMandateText.describePermission(listOf(shown), TimeZone.UTC).rows.associate { it.label to it.value }
        assertEquals("utopia", rows["At"])
        assertEquals("21 September 2027", rows["Until"])
    }

    @Test
    fun shownMandatesResolvesEveryDelegateItem() = runTest {
        val raw = selectivelyDisclosedMandate()
        val jsonString = DelegateTransaction.serializeOpenId4VpRequest(
            withDisclosures(raw, listOf(merchantDisclosure, expDisclosure)), listOf("c")
        )
        val data = DelegateTransaction.parseJson(jsonString.encodeToByteArray().toBase64Url().encodeToByteString())
        val shown = DelegateTransaction.shownMandates(listOf(data, transactionData(payload(1))))
        assertEquals(2, shown.size)
        assertEquals(1821496085, shown[0]["exp"]!!.jsonPrimitive.long)
        assertEquals(mandates[1], shown[1])
    }

    // Delegate SD-JWT §7.1: the payload "MUST NOT contain any disclosures not provided in
    // delegate_disclosures". Anything that cannot be resolved one to one is refused.
    @Test
    fun disclosuresThatDoNotMatchAreRefused() = runTest {
        val raw = selectivelyDisclosedMandate()
        val twice = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.allowed_merchants","allowed":[
                 {"...":"${digestOf(merchantDisclosure)}"},{"...":"${digestOf(merchantDisclosure)}"}]}]}"""
        ).jsonObject
        val wrongShape = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","_sd":["${digestOf(merchantDisclosure)}"]}"""
        ).jsonObject
        for ((case, payload) in listOf(
            "a digest without its disclosure" to withDisclosures(raw, listOf(merchantDisclosure)),
            "a disclosure nothing refers to" to
                withDisclosures(raw, listOf(merchantDisclosure, expDisclosure, encode(JsonPrimitive("s3"), JsonPrimitive("x")))),
            "the same disclosure twice" to withDisclosures(raw, listOf(merchantDisclosure, expDisclosure, expDisclosure)),
            "a digest referenced twice" to withDisclosures(twice, listOf(merchantDisclosure)),
            "an array disclosure under _sd" to withDisclosures(wrongShape, listOf(merchantDisclosure)),
        )) {
            assertFailsWith(IllegalArgumentException::class, case) {
                DelegateTransaction.resolveMandates(payload, Algorithm.SHA256)
            }
        }
    }
}
