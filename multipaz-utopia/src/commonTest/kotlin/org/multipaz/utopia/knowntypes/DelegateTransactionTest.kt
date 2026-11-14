package org.multipaz.utopia.knowntypes

import kotlinx.coroutines.test.runTest
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    private fun single(content: JsonObject) = DelegateTransaction.Payload.DelegateSdJwt(
        mandate = DelegateTransaction.Mandate(disclosureOf(content), content),
        format = DelegateTransaction.FORMAT_DSD_JWT,
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

    // The built-in rendering shows only a heading for this type, so a prompt without a renderer for it
    // refuses instead of letting the person approve a spending permission they were not shown.
    @Test
    fun theTypeRequiresARenderer() {
        assertTrue(DelegateTransaction.requiresRenderer)
    }

    // A wallet still emitting `kb+jwt` has not applied the extension (§5.1.4).
    @Test
    fun theKeyBindingHasItsOwnMediaType() {
        assertEquals("kb+sd-jwt", DelegateTransaction.DELEGATE_KB_TYPE)
        assertEquals("kb-sd-jwt+kb", DelegateTransaction.DELEGATE_KB_KB_TYPE)
    }

    private fun kbType(vararg payloads: DelegateTransaction.Payload) =
        DelegateTransaction.sdJwtKbType(payloads.map { transactionData(it) })

    private val terminalPayload = JsonObject(mandates[0] - "cnf").let {
        single(it)
    }

    // An AP2 mandate names the agent's key in `cnf`, so the wallet's hop is a KB-SD-JWT+KB (§5.1.4).
    // Typed `kb+sd-jwt` it reads as the end of the chain, and a chain verifier refuses the agent's
    // hop after it.
    @Test
    fun aMandateNamingTheAgentsKeyIsTypedForFurtherDelegation() {
        assertEquals("kb-sd-jwt+kb", kbType(payload(0)))
        assertEquals("kb-sd-jwt+kb", kbType(payload(1)))
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
        assertEquals("kb-sd-jwt+kb", kbType(terminalPayload, payload(0)))
        assertEquals("kb-sd-jwt+kb", kbType(payload(0), terminalPayload))
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
        assertIs<DelegateTransaction.Payload.Ap2>(parsed)
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
        assertIs<DelegateTransaction.Payload.Ap2>(again)
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

    // `dSD-JWT+KB` promises a further key; a mandate that names none contradicts it.
    @Test
    fun aKeyBoundFormatWithoutCnfIsRefused() {
        val withoutCnf = JsonObject(mandates[0] - "cnf")
        val jsonString = """{"type":"delegate","format":"${DelegateTransaction.FORMAT_DSD_JWT_KB}","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(withoutCnf)}"}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    private fun withCnf(cnf: String) = JsonObject(
        mandates[0] + ("cnf" to Json.parseToJsonElement(cnf))
    )

    // The consent screen shows only `cnf.jwk.x`; any other key would be signed and never seen.
    @Test
    fun aCnfThatCannotBeShownIsRefused() {
        val unseen = listOf(
            """{"jwk": {"kty": "RSA", "n": "abc", "e": "AQAB"}}""",
            """{"jkt": "0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I"}""",
            """{"kid": "agent-7"}""",
            """{"jwk": {"kty": "EC", "crv": "P-256", "x": "", "y": "agent-y"}}""",
        )
        for (cnf in unseen) {
            val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(withCnf(cnf))}"}"""
            assertFailsWith(IllegalArgumentException::class, cnf) {
                DelegateTransaction.parseOpenId4VpRequest(jsonString)
            }
        }
    }

    // The same rule for AP2's mandates in the clear.
    @Test
    fun aCnfThatCannotBeShownIsRefusedInPlainForm() {
        val jsonString = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],"delegate_payload":[${withCnf("""{"kid": "agent-7"}""")}]}"""
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.parseOpenId4VpRequest(jsonString)
        }
    }

    @Test
    fun aCnfWithAShownKeyIsAccepted() {
        val jsonString = """{"type":"delegate","format":"${DelegateTransaction.FORMAT_DSD_JWT_KB}","credential_ids":["c"],"delegate_payload_disclosure":"${disclosureOf(mandates[0])}"}"""
        assertEquals(1, DelegateTransaction.parseOpenId4VpRequest(jsonString).mandates.size)
    }

    // An Ap2 payload built outside the parser needs a disclosure per mandate: `fromContent` makes it.
    @Test
    fun anAp2PayloadCanBeBuiltFromMandatesInTheClear() {
        val built = DelegateTransaction.Payload.Ap2(mandates.map { DelegateTransaction.Mandate.fromContent(it) })
        val parsed = DelegateTransaction.parseOpenId4VpRequest(
            DelegateTransaction.serializeOpenId4VpRequest(built, listOf("c"))
        )
        assertEquals(mandates, parsed.mandates.map { it.content })
        // The disclosure is [salt, content], and each call uses a fresh salt.
        val mandate = DelegateTransaction.Mandate.fromContent(mandates[0])
        val decoded = Json.parseToJsonElement(mandate.disclosure.fromBase64Url().decodeToString()) as JsonArray
        assertEquals(mandates[0], decoded[1])
        assertTrue(mandate.disclosure != DelegateTransaction.Mandate.fromContent(mandates[0]).disclosure)
    }

    // The salts come from the `random` given, so a seeded one makes the wallet's disclosures
    // repeatable; the default makes each parse different, which is why they must not be compared.
    @Test
    fun theSaltsOfAp2sDisclosuresComeFromTheRandomGiven() {
        val jsonString = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],"delegate_payload":[${mandates[0]}]}"""
        fun disclosure(random: Random) =
            DelegateTransaction.parseOpenId4VpRequest(jsonString, random).mandates.single().disclosure
        assertEquals(disclosure(Random(1)), disclosure(Random(1)))
        assertTrue(disclosure(Random(1)) != disclosure(Random(2)))
        assertTrue(
            DelegateTransaction.parseOpenId4VpRequest(jsonString).mandates.single().disclosure !=
                DelegateTransaction.parseOpenId4VpRequest(jsonString).mandates.single().disclosure
        )
    }

    // The sealed types make the states that used to fail later impossible to build.
    @Test
    fun aPayloadThatCouldNotBeSerializedCannotBeBuilt() {
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.Payload.Ap2(emptyList())
        }
        assertFailsWith(IllegalArgumentException::class) {
            DelegateTransaction.Payload.DelegateSdJwt(
                DelegateTransaction.Mandate.fromContent(mandates[0]),
                format = "dc+sd-jwt",
            )
        }
    }

    // Whether a person can read a mandate is decided once its selectively disclosed terms are
    // resolved: before that they are digests, so the same mandate has nothing readable and then does.
    @Test
    fun readabilityIsJudgedOnTheResolvedMandate() = runTest {
        val raw = selectivelyDisclosedMandate()
        assertFalse(DelegateMandateText.hasReadableLine(raw))
        val resolved = resolve(
            withDisclosures(raw, listOf(merchantDisclosure, expDisclosure))
        ).single()
        assertTrue(DelegateMandateText.hasReadableLine(resolved))
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

    // AP2's SDK rejects a response digested with anything but SHA-256, so SHA-256 wins when offered.
    @Test
    fun sha256IsPreferredWhenTheRequestOffersIt() = runTest {
        for (offered in listOf(listOf(Algorithm.SHA384, Algorithm.SHA256), listOf(Algorithm.SHA256, Algorithm.SHA384))) {
            val data = transactionData(hashAlgorithms = offered)
            val expected = Crypto.digest(Algorithm.SHA256, data.payload.disclosure.encodeToByteArray()).toBase64Url()
            val claims = DelegateTransaction.delegateClaims(data)
            assertEquals(expected, claims.getValue("delegate_payload").jsonArray[0].jsonObject["..."]!!.jsonPrimitive.content)
            assertEquals("sha-256", claims.getValue("_sd_alg").jsonPrimitive.content)
        }
    }

    // Every nested digest is resolved with the algorithm the Key Binding JWT names, because that is
    // what AP2's verifier does (sd-jwt verifier.py): a term digested differently is silently dropped
    // there, so showing it would show something that is never enforced.
    @Test
    fun aMandatesDisclosuresAreResolvedWithTheKeyBindingJwtsAlgorithm() = runTest {
        val sha256Digests = withDisclosures(selectivelyDisclosedMandate(), listOf(merchantDisclosure, expDisclosure))
        // SHA-256 is preferred when offered, and these digests are SHA-256.
        assertEquals(1, resolve(sha256Digests, listOf(Algorithm.SHA384, Algorithm.SHA256)).size)
        // Under SHA-384 alone the Key Binding JWT digests with SHA-384, which these are not.
        assertFailsWith<IllegalArgumentException> { resolve(sha256Digests, listOf(Algorithm.SHA384)) }
        assertFailsWith<IllegalArgumentException> {
            DelegateMandateText.permissionSummary(listOf(transactionData(sha256Digests, hashAlgorithms = listOf(Algorithm.SHA384))))
        }
    }

    // AP2's own `["sha-256"]` request with a mandate that says `_sd_alg: sha-384`: AP2 hashes with the
    // Key Binding JWT's sha-256, finds nothing, and drops those terms, so the mandate is refused
    // before the person is shown terms that would never be enforced.
    @Test
    fun aMandateThatDigestsWithAnotherAlgorithmThanTheKeyBindingJwtIsRefused() = runTest {
        val digest384 = Crypto.digest(Algorithm.SHA384, merchantDisclosure.encodeToByteArray()).toBase64Url()
        val raw = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","_sd_alg":"sha-384","constraints":[
                 {"type":"checkout.allowed_merchants","allowed":[{"...":"$digest384"}]}]}"""
        ).jsonObject
        val payload = withDisclosures(raw, listOf(merchantDisclosure))
        assertFailsWith<IllegalArgumentException> { resolve(payload, listOf(Algorithm.SHA256)) }
        assertFailsWith<IllegalArgumentException> { resolve(payload) }
        // When the Key Binding JWT digests with SHA-384 as well, nothing disagrees.
        assertTrue("utopia" in resolve(payload, listOf(Algorithm.SHA384)).single().toString())
    }

    // One Key Binding JWT carries one `_sd_alg`: items whose requests settle on different digest
    // algorithms fail after approval today, so they are refused before it.
    @Test
    fun itemsThatSettleOnDifferentDigestAlgorithmsAreRefusedTogether() = runTest {
        // Each item is its own mandate, so only the algorithm can set them apart.
        var count = 0
        fun item(vararg algorithms: Algorithm): TransactionData<DelegateTransaction.Payload> {
            val raw = Json.parseToJsonElement("""{"vct":"mandate.payment.open.1","exp":${1821496085 + count++}}""").jsonObject
            return transactionData(withDisclosures(raw, emptyList()), algorithms.toList())
        }
        DelegateTransaction.validateTogether(listOf(item(Algorithm.SHA256), item(Algorithm.SHA256)))
        assertFailsWith<IllegalArgumentException> {
            DelegateTransaction.validateTogether(listOf(item(Algorithm.SHA256), item(Algorithm.SHA384)))
        }
        // The same list settles on the same algorithms; a different order names a different
        // `transaction_data_hashes_alg` (the first), which `processTransactions` would refuse.
        DelegateTransaction.validateTogether(
            listOf(item(Algorithm.SHA256, Algorithm.SHA384), item(Algorithm.SHA256, Algorithm.SHA384))
        )
        assertFailsWith<IllegalArgumentException> {
            DelegateTransaction.validateTogether(
                listOf(item(Algorithm.SHA256, Algorithm.SHA384), item(Algorithm.SHA384, Algorithm.SHA256))
            )
        }
    }

    // A disclosure that follows the KB-JWT appears once: two items sharing one would put its digest in
    // the response twice, and AP2's verifier rejects that ("Duplicate hash found") after approval.
    @Test
    fun itemsThatShareADisclosureAreRefusedTogether() = runTest {
        val a = transactionData(withDisclosures(selectivelyDisclosedMandate(), listOf(merchantDisclosure, expDisclosure)))
        val sharing = transactionData(
            DelegateTransaction.Payload.Ap2(
                mandates = listOf(DelegateTransaction.Mandate(disclosureOf(selectivelyDisclosedMandate()), selectivelyDisclosedMandate())),
                delegateDisclosures = listOf(merchantDisclosure, expDisclosure),
            )
        )
        DelegateTransaction.validateTogether(listOf(a))
        assertFailsWith<IllegalArgumentException> { DelegateTransaction.validateTogether(listOf(a, sharing)) }
        // Different disclosures, even for the same mandate content, are fine.
        val other = transactionData(withDisclosures(selectivelyDisclosedMandate(), listOf(encode(JsonPrimitive("s9"), JsonPrimitive("x")))))
        DelegateTransaction.validateTogether(listOf(a, other))
    }

    private val agentKey = """{"jwk":{"kty":"EC","crv":"P-256","x":"wu7CCOUoXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXac733T-o","y":"YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY"}}"""

    // A `cnf` behind a digest is never shown by the parse-time check, which sees the unresolved
    // mandate. Signed anyway, it would leave `sdJwtKbType` seeing no `cnf` and typing the key binding as
    // the end of the chain, which AP2's verifier rejects after the person approved.
    @Test
    fun aKeyBehindADigestIsRefusedBeforeConsent() = runTest {
        val cnfDisclosure = encode(JsonPrimitive("s3"), JsonPrimitive("cnf"), Json.parseToJsonElement(agentKey))
        val raw = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","exp":1821496085,"_sd":["${digestOf(cnfDisclosure)}"]}"""
        ).jsonObject
        assertFailsWith<IllegalArgumentException> {
            DelegateTransaction.requireShowable(withDisclosures(raw, listOf(cnfDisclosure)), null)
        }
        // The same key in the clear is shown and typed correctly, so it is fine.
        val inTheClear = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","exp":1821496085,"cnf":$agentKey}"""
        ).jsonObject
        assertEquals(1, DelegateTransaction.requireShowable(withDisclosures(inTheClear, emptyList()), null).size)
    }

    // A key this wallet cannot show (a `kid`, an RSA key) is judged on the resolved mandate too.
    @Test
    fun aKeyThatCannotBeShownIsRefusedEvenWhenDisclosed() = runTest {
        val kidDisclosure = encode(JsonPrimitive("s4"), JsonPrimitive("cnf"), Json.parseToJsonElement("""{"kid":"agent-1"}"""))
        val raw = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","exp":1821496085,"cnf":{"jwk":{"kty":"EC"}},"_sd":["${digestOf(kidDisclosure)}"]}"""
        ).jsonObject
        assertFailsWith<IllegalArgumentException> {
            DelegateTransaction.requireShowable(withDisclosures(raw, listOf(kidDisclosure)), null)
        }
    }

    private fun checkoutJwtOf(payload: String) =
        "eyJhbGciOiJFUzI1NiJ9." + payload.encodeToByteArray().toBase64Url() + ".c2lnbmF0dXJl"

    // The screen shows the decoded `checkout_jwt` but `checkout_hash` is what is signed, and AP2 defines
    // the hash as H(checkout_jwt): a mandate where they differ shows one cart and signs another.
    @Test
    fun aClosedCheckoutWhoseHashIsNotTheHashOfItsJwtIsRefused() = runTest {
        val jwt = checkoutJwtOf(UCP_CHECKOUT_PAYLOAD)
        fun closed(hash: String) = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.1","checkout_jwt":"$jwt","checkout_hash":"$hash"}"""
        ).jsonObject
        assertEquals(1, DelegateTransaction.requireShowable(withDisclosures(closed(digestOf(jwt)), emptyList()), null).size)
        assertFailsWith<IllegalArgumentException> {
            DelegateTransaction.requireShowable(withDisclosures(closed("3WiKMabE8NRYJgveUbyAZ3pBqRfPrWwGDbOyvbO1eYA"), emptyList()), null)
        }
    }

    @Test
    fun anSdAlgThatIsNotAHashAlgorithmIsRefused() = runTest {
        for (bad in listOf("\"md5\"", "5")) {
            val raw = Json.parseToJsonElement("""{"vct":"mandate.checkout.open.1","_sd_alg":$bad,"exp":1821496085}""").jsonObject
            assertFailsWith(IllegalArgumentException::class, bad) {
                resolve(withDisclosures(raw, emptyList()))
            }
        }
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

    /** [DelegateTransaction.resolveMandates] under the algorithms a request names, SHA-256 when it names none. */
    private suspend fun resolve(payload: DelegateTransaction.Payload, hashAlgorithms: List<Algorithm>? = null) =
        DelegateTransaction.resolveMandates(payload, hashAlgorithms)

    private fun withDisclosures(raw: JsonObject, disclosures: List<String>) = DelegateTransaction.Payload.Ap2(
        mandates = listOf(DelegateTransaction.Mandate(disclosureOf(raw), raw)),
        delegateDisclosures = disclosures,
    )

    // Both decoders catch only IllegalArgumentException, so each malformed input must surface as one:
    // text that is not base64url, and base64url of text that is not JSON.
    @Test
    fun aMalformedMandateDisclosureIsRefused() {
        for (malformed in listOf("!!!", "bm90IGpzb24")) {
            val jsonString = """{"type":"delegate","format":"dSD-JWT","credential_ids":["c"],"delegate_payload_disclosure":"$malformed"}"""
            assertFailsWith(IllegalArgumentException::class, malformed) {
                DelegateTransaction.parseOpenId4VpRequest(jsonString)
            }
        }
    }

    @Test
    fun aMalformedDelegateDisclosureIsRefused() = runTest {
        for (malformed in listOf("!!!", "bm90IGpzb24")) {
            assertFailsWith(IllegalArgumentException::class, malformed) {
                resolve(
                    withDisclosures(selectivelyDisclosedMandate(), listOf(malformed))
                )
            }
        }
    }

    // The way a consent screen gets its summary resolves the terms behind digests; describing a
    // mandate's own content, the call it replaces, leaves them out without a word.
    @Test
    fun theSummaryFromTransactionDataIncludesTheDisclosedTerms() = runTest {
        val raw = selectivelyDisclosedMandate()
        val data = transactionData(withDisclosures(raw, listOf(merchantDisclosure, expDisclosure)))
        val rows = DelegateMandateText.permissionSummary(listOf(data)).rows.associate { it.label to it.value }
        assertEquals("utopia", rows["At"])
        assertFalse(DelegateMandateText.describePermission(data.payload.mandates.map { it.content }).rows.any { it.label == "At" })
    }

    // What is signed is what is shown: terms behind digests are resolved from
    // `delegate_disclosures`, not displayed as the digests themselves.
    @Test
    fun selectivelyDisclosedTermsAreShown() = runTest {
        val raw = selectivelyDisclosedMandate()
        val shown = resolve(
            withDisclosures(raw, listOf(merchantDisclosure, expDisclosure))
        ).single()

        assertEquals(
            Json.parseToJsonElement(
                """{"vct":"mandate.checkout.open.1","constraints":[
                     {"type":"checkout.allowed_merchants","allowed":[$merchant]}],"exp":1821496085}"""
            ),
            shown
        )
        val rows = DelegateMandateText.describePermission(listOf(shown)).rows.associate { it.label to it.value }
        assertEquals("utopia", rows["At"])
        assertEquals("21 September 2027, 03:08:05 UTC", rows["Until"])
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
                resolve(payload)
            }
        }
    }
}
