package org.multipaz.documenttype.knowntypes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.presentment.TransactionData
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential

/**
 * Mandate delegation, as defined by the
 * [Agent Payments Protocol](https://github.com/google-agentic-commerce/AP2)
 * (`docs/ap2/agent_authorization.md`, "Delegation using OpenID4VP").
 *
 * ### What this is for
 *
 * A user authorizes an *agent* to act later, when the user is no longer present — "this
 * assistant may buy groceries, up to $50 a shop, $200 a month, until December". AP2 calls the
 * record of that authorization a Mandate, and delegates it during an ordinary OpenID4VP
 * presentation of the user's own credential: the verifier puts the Mandate Content in a
 * `transaction_data` entry of type `delegate`, and the wallet returns it bound into the Key
 * Binding JWT. The user's key binding *is* the authorization.
 *
 ### Which specification this follows, because two describe it
 *
 * AP2's `agent_authorization.md` puts the Mandate Content in the request in the clear, as
 * `delegate_payload` (an array of JSON objects), with `format` naming the credential's VDC
 * format. [Delegate SD-JWT](https://datatracker.ietf.org/doc/html/draft-gco-oauth-delegate-sd-jwt)
 * §7.1 — the specification AP2 points at — puts a single **array disclosure** there instead, and
 * the KB-JWT carries only its digest. This implementation follows THE DRAFT, because that is
 * what a verifier written to the standard will read.
 *
 * The request object carries, per §7.1:
 * - `type`: **REQUIRED**, the string `"delegate"`.
 * - `format`: **REQUIRED**, `dSD-JWT` or `dSD-JWT+KB` — the delegation format, not the credential's.
 * - `delegate_payload_disclosure`: **REQUIRED**, the Array Disclosure of the delegate payload.
 * - `delegate_disclosures`: **OPTIONAL**, selective disclosures within that payload.
 *
 * and §7.1 states: *"The KB-JWT includes the digest of the delegate_payload in the
 * delegate_payload claim of the KB-JWT."*
 *
 * ### Why [nestSdJwtResponseClaims] is false, and why the KB-JWT is typed `kb+sd-jwt`
 *
 * The claim shape is fixed by the draft: the KB-JWT carries `delegate_payload` as an **array**,
 * at the top level, and §5.1.4 requires the key binding to be typed `kb+sd-jwt` rather than the
 * ordinary `kb+jwt`. The default nesting would wrap the array in an object under this type's
 * identifier and produce a payload no verifier written against that draft can read.
 *
 * ### Showing the user what they are signing
 *
 * AP2 is explicit that "The User is shown the Mandate Content on a Trusted Surface", and in this
 * model the wallet IS that surface — not the web page that asked for the signature, which is
 * served by the party requesting it and so cannot vouch for itself. A wallet that signed a
 * `delegate` request without displaying it would produce a signature proving the person tapped
 * Share, not that they agreed to the terms.
 *
 * [summarize] renders the Mandate Content for that screen. It stays GENERIC: it walks whatever
 * JSON the mandate carries rather than knowing AP2's mandate types, so new mandate and
 * constraint types display without a wallet change. The two values it does interpret are format
 * concerns, not AP2 ones — a `cnf` key (RFC 7800) shown as a thumbprint rather than raw
 * coordinates, and `exp`/`iat`/`nbf` shown as dates rather than epoch seconds.
 */
object DelegateTransaction : TransactionType<DelegateTransaction.Payload>(
    displayName = "Delegate authorization",
    identifier = "delegate",
    // Delegate SD-JWT fixes the claim name and shape; see the KDoc above.
    nestSdJwtResponseClaims = false,
    // …and gives the key binding its own media type (§5.1.4).
    sdJwtKbType = "kb+sd-jwt",
) {
    /**
     * The KB-JWT claim carrying the digest of the delegated Mandate Content (Delegate SD-JWT §7.1).
     *
     * `delegate_payload`, with no leading underscore. The draft's source writes the claim as
     * `*delegate\_payload*` — Markdown italics around an escaped underscore — and datatracker's
     * rendering turns that into `_delegate_payload_`. Reading the rendering rather than the
     * source is how an underscored name shipped; §7.1's prose spells it plainly.
     */
    const val DELEGATE_PAYLOAD_CLAIM = "delegate_payload"

    /** The `typ` a Delegate Key Binding JWT carries (Delegate SD-JWT §5.1.4). */
    const val DELEGATE_KB_TYPE = "kb+sd-jwt"

    /**
     * @property format the delegation format, `dSD-JWT` or `dSD-JWT+KB` (Delegate SD-JWT §7.1).
     * @property delegatePayloadDisclosure the Array Disclosure exactly as it arrived. Kept
     *   VERBATIM because the digest in the KB-JWT is computed over these bytes: re-encoding the
     *   JSON inside it would change what the user's signature covers.
     * @property delegatePayload the Mandate Content that disclosure carries, decoded for display.
     * @property delegateDisclosures optional selective disclosures within [delegatePayload].
     */
    data class Payload(
        val format: String,
        val delegatePayloadDisclosure: String,
        val delegatePayload: JsonObject,
        val delegateDisclosures: List<JsonElement>? = null,
    )

    /**
     * One readable line on the consent screen.
     *
     * @property label what the line is — a mandate member or constraint, in words.
     * @property value what it says, already formatted for a person (money, dates, lists).
     */
    data class SummaryLine(val label: String, val value: String)

    /**
     * The Mandate Content, rendered for a consent screen.
     *
     * One entry carries one Delegate Payload (§5.1.4), so this is one mandate's worth of lines; a
     * request delegating several mandates sends several entries and the screen shows each.
     *
     * Generic by design: it walks the JSON rather than knowing AP2's mandate types, so a mandate
     * or constraint type nobody has seen still displays. A mandate that produces NO lines is
     * reported as an empty list, and a wallet MUST refuse to sign it rather than show a blank —
     * a signature over terms the user could not read is the thing this whole screen exists to
     * prevent.
     */
    fun summarize(payload: Payload): List<SummaryLine> =
        buildList {
            for ((key, value) in payload.delegatePayload) {
                when {
                    key == "vct" -> {} // the heading, rendered separately
                    key == "cnf" -> keyExcerpt(value)?.let { add(SummaryLine("Authorized agent key", it)) }
                    key in INSTANT_CLAIMS && value is JsonPrimitive && value.longOrNull != null ->
                        add(SummaryLine(INSTANT_CLAIMS.getValue(key), formatInstant(value.long)))
                    value is JsonArray -> value.forEach { element -> add(lineForElement(key, element)) }
                    else -> add(SummaryLine(prettyLabel(key), renderValue(value)))
                }
            }
        }

    /** The mandate type, for the heading above its lines. Blank when the mandate does not say. */
    fun mandateType(mandate: JsonObject): String =
        (mandate["vct"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * The Mandate Content carried by an Array Disclosure — `base64url(JSON([salt, value]))`,
     * RFC 9901 §4.2.4.2.
     *
     * A disclosure that is not that shape cannot be shown to anyone, so it is refused here rather
     * than carried to a screen that would render a blank.
     */
    private fun mandateFromDisclosure(disclosure: String): JsonObject {
        val decoded = try {
            json.parseToJsonElement(disclosure.fromBase64Url().decodeToString())
        } catch (err: Exception) {
            throw IllegalArgumentException("'delegate_payload_disclosure' is not a base64url-encoded JSON array", err)
        }
        val array = decoded as? JsonArray
            ?: throw IllegalArgumentException("'delegate_payload_disclosure' does not decode to a JSON array")
        // [salt, value] for an array element; [salt, name, value] is the object-property form,
        // which is not what a delegate payload is.
        if (array.size != 2) {
            throw IllegalArgumentException(
                "'delegate_payload_disclosure' has ${array.size} elements — an array disclosure is [salt, value]"
            )
        }
        return array[1] as? JsonObject
            ?: throw IllegalArgumentException("'delegate_payload_disclosure' does not disclose a JSON object")
    }

    private val INSTANT_CLAIMS = mapOf(
        "exp" to "Valid until",
        "nbf" to "Valid from",
        "iat" to "Issued",
    )

    /**
     * An element of an array. Objects carrying a `type` member — a common JSON convention, and
     * the one AP2 constraints happen to use — read far better with that as the label than as
     * "constraints[0]", so it is used when present. No knowledge of what the types MEAN.
     */
    private fun lineForElement(key: String, element: JsonElement): SummaryLine {
        val tag = (element as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (tag != null) {
            val rest = element.jsonObject.filterKeys { it != "type" }
            // An object carrying a `currency` states amounts in that currency's minor units
            // (ISO 4217). Rendering the raw integer would show "max 13000" for $130.00 — on the
            // one screen that exists so a person reads the limit correctly. Formatting it is a
            // currency concern, like rendering a JWT `exp` as a date, not knowledge of AP2.
            val currency = (rest["currency"] as? JsonPrimitive)?.contentOrNull
            return SummaryLine(
                prettyLabel(tag),
                rest.entries
                    .filter { (k, v) -> !isEmptyCollection(v) && !(k == "currency" && currency != null) }
                    .joinToString(", ") { (k, v) ->
                        val amount = currency?.let { c -> (v as? JsonPrimitive)?.longOrNull?.let { formatMinorUnits(it, c) } }
                        if (amount != null) "$k $amount" else "$k ${renderValue(v)}"
                    }
                    .ifEmpty { EMPTY_VALUE },
            )
        }
        return SummaryLine(prettyLabel(key), renderValue(element))
    }

    /**
     * What an empty list reads as.
     *
     * NOT "any". An empty allow-list usually means the opposite — nothing is permitted — and a
     * screen that turned "nothing" into "anything" would misreport the very limit the person is
     * being asked to approve. Saying it is empty lets them see something is wrong.
     */
    private const val EMPTY_VALUE = "(none)"

    private fun isEmptyCollection(value: JsonElement): Boolean =
        (value is JsonArray && value.isEmpty()) || (value is JsonObject && value.isEmpty())

    /** Minor units to a readable amount. ISO 4217 exponents that are not 2 are listed. */
    private fun formatMinorUnits(minor: Long, currency: String): String {
        val exp = MINOR_UNIT_EXPONENTS[currency.uppercase()] ?: 2
        if (exp == 0) return "$minor $currency"
        val sign = if (minor < 0) "-" else ""
        val digits = kotlin.math.abs(minor).toString().padStart(exp + 1, '0')
        val whole = digits.dropLast(exp)
        return "$sign$whole.${digits.takeLast(exp)} $currency"
    }

    private val MINOR_UNIT_EXPONENTS = mapOf(
        "BIF" to 0, "CLP" to 0, "DJF" to 0, "GNF" to 0, "ISK" to 0, "JPY" to 0, "KMF" to 0,
        "KRW" to 0, "PYG" to 0, "RWF" to 0, "UGX" to 0, "UYI" to 0, "VND" to 0, "VUV" to 0,
        "XAF" to 0, "XOF" to 0, "XPF" to 0,
        "BHD" to 3, "IQD" to 3, "JOD" to 3, "KWD" to 3, "LYD" to 3, "OMR" to 3, "TND" to 3,
    )

    /** `checkout.allowed_merchants` → `Checkout allowed merchants`. Punctuation only. */
    private fun prettyLabel(raw: String): String =
        raw.replace('_', ' ').replace('.', ' ').trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun renderValue(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        is JsonArray -> value.joinToString(", ") { renderValue(it) }.ifEmpty { EMPTY_VALUE }
        is JsonObject -> value.entries.joinToString(", ") { (k, v) -> "$k ${renderValue(v)}" }.ifEmpty { EMPTY_VALUE }
    }

    /**
     * A short, stable excerpt of the `cnf` key (RFC 7800), for the consent screen.
     *
     * Deliberately NOT an RFC 7638 thumbprint: computing one needs a hash, and `Crypto.digest`
     * is suspending while this renders inside a composable. An excerpt of the key's own `x`
     * coordinate is stable for a given key and serves the purpose the screen has — letting a
     * person see that two requests name the SAME agent, or a different one. It is named for
     * what it is rather than dressed up as a thumbprint.
     */
    private fun keyExcerpt(cnf: JsonElement): String? {
        val jwk = (cnf as? JsonObject)?.get("jwk") as? JsonObject ?: return null
        val x = (jwk["x"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (x.length <= 16) return x
        return "${x.take(8)}…${x.takeLast(8)}"
    }

    private fun formatInstant(epochSeconds: Long): String =
        Instant.fromEpochSeconds(epochSeconds).toString().substringBefore('.').replace('T', ' ') + " UTC"

    private val json = Json { ignoreUnknownKeys = true }

    override fun serializeOpenId4VpRequest(
        payload: Payload,
        credentialIds: List<String>,
        hashAlgorithms: List<Algorithm>?
    ): String {
        val obj = buildMap<String, JsonElement> {
            put("type", JsonPrimitive(identifier))
            put("format", JsonPrimitive(payload.format))
            put("credential_ids", JsonArray(credentialIds.map { JsonPrimitive(it) }))
            joseHashAlgorithms(hashAlgorithms)?.let {
                put("transaction_data_hashes_alg", JsonArray(it.map { alg -> JsonPrimitive(alg) }))
            }
            put("delegate_payload_disclosure", JsonPrimitive(payload.delegatePayloadDisclosure))
            payload.delegateDisclosures?.let { put("delegate_disclosures", JsonArray(it)) }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(obj))
    }

    override fun parseOpenId4VpRequest(jsonString: String): Payload {
        val obj = json.parseToJsonElement(jsonString).jsonObject
        // REQUIRED by §7.1. Refuse rather than delegate an empty authorization: a wallet that
        // signed an absent payload would return a key binding that authorizes nothing, and the
        // user would have approved something with no content.
        //
        // A request carrying AP2's plain `delegate_payload` instead lands here too, and is
        // refused: signing it would produce a KB-JWT this wallet cannot honestly describe as
        // conforming to either document.
        val disclosure = (obj["delegate_payload_disclosure"]
            ?: throw IllegalArgumentException("Missing 'delegate_payload_disclosure' in delegate transaction data"))
            .jsonPrimitive.content
        if (disclosure.isEmpty()) {
            throw IllegalArgumentException("'delegate_payload_disclosure' is empty — nothing to authorize")
        }
        val format = (obj["format"]
            ?: throw IllegalArgumentException("Missing 'format' in delegate transaction data"))
            .jsonPrimitive.content
        val payload = Payload(
            format = format,
            delegatePayloadDisclosure = disclosure,
            delegatePayload = mandateFromDisclosure(disclosure),
            delegateDisclosures = obj["delegate_disclosures"]?.jsonArray?.toList(),
        )
        // A mandate the consent screen cannot show MUST NOT be signable. Refusing here, at the
        // protocol boundary, means a blank approval screen is unreachable rather than merely
        // unlikely — the person's signature is the authorization, so it cannot cover terms they
        // were never shown.
        if (summarize(payload).isEmpty()) {
            throw IllegalArgumentException(
                "the mandate in 'delegate_payload_disclosure' has nothing that can be shown to the user — refusing to request a signature over terms they cannot read"
            )
        }
        return payload
    }

    /**
     * Delegation requires key binding — the whole mechanism is the holder's signature over the
     * Mandate Content — so only a key-bound SD-JWT VC can carry it. An mdoc credential cannot:
     * Delegate SD-JWT's chain is SD-JWT syntax and has no mdoc equivalent.
     */
    override suspend fun isApplicable(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Boolean {
        if (credential !is KeyBoundSdJwtVcCredential) return false
        return super.isApplicable(transactionData, credential)
    }

    /**
     * Put the DIGEST of the Mandate Content into the KB-JWT (Delegate SD-JWT §7.1).
     *
     * The digest is taken over the disclosure's own bytes, exactly as they arrived (RFC 9901
     * §4.2.4.2), never over a re-encoding of the JSON inside it. Any normalisation — reordering,
     * respacing, dropping an unknown member — would produce a different digest and so a signature
     * over something other than what the verifier asked for.
     *
     * The element is written in the replaced-array-element form `{"...": "<digest>"}`, which is
     * what §5.1.4 means by "they MUST all be replaced with disclosures".
     *
     * The algorithm is the one the verifier asked for in `transaction_data_hashes_alg`, falling
     * back to SD-JWT's default (RFC 9901 §4.1.1). Answering in a different algorithm than was
     * requested produces a digest mismatch the verifier cannot explain.
     */
    override suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, JsonElement> = buildMap {
        putAll(super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId))
        put(DELEGATE_PAYLOAD_CLAIM, delegatePayloadClaim(transactionData))
    }

    /**
     * The value of the `delegate_payload` claim for one transaction data item: a one-element
     * array holding the digest of its disclosure, in RFC 9901's replaced-array-element form.
     *
     * Separate from [generateSdJwtResponseClaims] so the shape can be exercised without a
     * credential and a document store — this is the part a verifier reads, and it is worth being
     * able to assert on directly.
     */
    suspend fun delegatePayloadClaim(transactionData: TransactionData<Payload>): JsonArray {
        val algorithm = transactionData.hashAlgorithms?.firstOrNull() ?: Algorithm.SHA256
        val digest = Crypto.digest(
            algorithm,
            transactionData.payload.delegatePayloadDisclosure.encodeToByteArray()
        ).toBase64Url()
        return JsonArray(listOf(JsonObject(mapOf(ARRAY_ELEMENT_DIGEST_KEY to JsonPrimitive(digest)))))
    }

    /** RFC 9901 §4.2.4.2: an array element replaced by a disclosure is `{"...": "<digest>"}`. */
    private const val ARRAY_ELEMENT_DIGEST_KEY = "..."
}
