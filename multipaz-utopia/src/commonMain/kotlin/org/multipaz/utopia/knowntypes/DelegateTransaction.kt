package org.multipaz.utopia.knowntypes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.SdJwtTransactionResponse
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.presentment.TransactionData
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential

/**
 * Mandate delegation, as defined by the
 * [Agent Payments Protocol](https://github.com/google-agentic-commerce/AP2)
 * (`docs/ap2/agent_authorization.md`, "Delegation using OpenID4VP").
 *
 * A user authorizes an agent to act later, when they are no longer present — "this assistant may
 * buy groceries, up to $50 a shop, until December". The verifier puts that Mandate Content in a
 * `transaction_data` entry of type `delegate`, and the wallet returns it bound into the Key
 * Binding JWT; the user's key binding is the authorization.
 *
 * Requests come in two forms, because the documents disagree: AP2 sends the mandates in the
 * clear as `delegate_payload`, while
 * [Delegate SD-JWT](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-delegate-sd-jwt) §7.1
 * sends one array disclosure in `delegate_payload_disclosure`. Both are accepted (see
 * [RequestForm]) and answered alike: the digest of each mandate's disclosure goes in the KB-JWT and
 * the disclosures follow it, a Delegated SD-JWT (§5.1.1),
 * `<SD-JWT>~~<KB-JWT>~<mandate disclosure>~`.
 *
 * AP2 requires the Mandate Content to be shown to the user on a Trusted Surface before it is
 * signed, and the wallet is that surface. This type only reads and answers the protocol, and
 * refuses mandates that cannot be shown; [DelegateMandateText] puts them into words.
 *
 * The consent prompt's built-in rendering shows only a heading for this type, not the mandate, so
 * [addUtopiaTypes] does not register it. An application that registers it must also register a
 * [org.multipaz.prompt.TransactionDataRenderer] for [identifier] that shows the mandates from
 * [shownMandates], for example with [DelegateMandateText.describePermission], and every line of
 * [DelegateMandateText.summarize] reachable on its details page.
 */
object DelegateTransaction : TransactionType<DelegateTransaction.Payload>(
    displayName = "Delegate authorization",
    identifier = "delegate",
    // Delegate SD-JWT §5.1.4 fixes both: `delegate_payload` is an array at the KB-JWT top level,
    // and the key binding has its own media type. Nesting would make it unreadable to a verifier.
    nestSdJwtResponseClaims = false,
    sdJwtKbType = "kb+sd-jwt",
) {
    /**
     * The KB-JWT claim carrying the digest of the Mandate Content (Delegate SD-JWT §7.1).
     *
     * No leading underscore: the draft's source writes it in Markdown italics around an escaped
     * underscore, which datatracker renders as `_delegate_payload_`. §7.1's prose spells it plainly.
     */
    const val DELEGATE_PAYLOAD_CLAIM = "delegate_payload"

    /** The values §7.1 allows for the request's `format`. */
    val FORMATS = listOf("dSD-JWT", "dSD-JWT+KB")

    /** The `format` AP2 sends with mandates in the clear: the VDC format of the returned mandate. */
    const val AP2_FORMAT = "dc+sd-jwt"

    /** The `typ` a Delegate Key Binding JWT carries when it ends the chain (Delegate SD-JWT §5.1.4). */
    const val DELEGATE_KB_TYPE = "kb+sd-jwt"

    /** The `typ` of one whose Delegate Payload names a further key, a KB-SD-JWT+KB (§5.1.4). */
    const val DELEGATE_KB_KB_TYPE = "kb+sd-jwt+kb"

    /**
     * Delegate SD-JWT §5.1.4: "The typ parameter value MUST be replaced with `kb+sd-jwt` for a
     * KB-SD-JWT, and `kb+sd-jwt+kb` for a KB-SD-JWT+KB" — the variant whose Delegate Payload names
     * the next holder's key in `cnf`.
     *
     * AP2's wallet hop is that variant: the mandate names the agent's key, and the agent signs the
     * purchase with it later. Typed `kb+sd-jwt`, the hop reads as the END of the chain, so a chain
     * verifier refuses the agent's hop after it.
     *
     * One key binding signs every mandate in the request, so the chain continues when any of them
     * names a further key. AP2's own example pairs a checkout mandate that does with a payment
     * mandate that does not, in either order.
     */
    override fun sdJwtKbType(transactionData: List<TransactionData<Payload>>): String =
        if (transactionData.any { data -> data.payload.mandates.any { "cnf" in it.content } }) {
            DELEGATE_KB_KB_TYPE
        } else {
            DELEGATE_KB_TYPE
        }

    /** How a request carries its mandates. The two documents disagree, and verifiers send both. */
    enum class RequestForm {
        /** Delegate SD-JWT §7.1: one `delegate_payload_disclosure`, made by the verifier. */
        DISCLOSURE,

        /** AP2: `delegate_payload`, an array of mandates in the clear; the wallet makes their disclosures. */
        PLAIN,
    }

    /**
     * One mandate being delegated.
     *
     * @property disclosure the Array Disclosure carrying it, `base64url(JSON([salt, content]))`;
     *   the KB-JWT digest is computed over these bytes and they follow the KB-JWT unchanged.
     * @property content the Mandate Content, for display.
     */
    data class Mandate(
        val disclosure: String,
        val content: JsonObject,
    )

    /**
     * @property format the delegation format: `dSD-JWT` or `dSD-JWT+KB` for
     *   [RequestForm.DISCLOSURE] (Delegate SD-JWT §7.1), `dc+sd-jwt` for [RequestForm.PLAIN] (AP2).
     * @property mandates the mandates, in request order: exactly one for [RequestForm.DISCLOSURE],
     *   one or more for [RequestForm.PLAIN].
     * @property delegateDisclosures optional disclosures of the selectively disclosable claims
     *   within [mandates], as they arrived.
     * @property requestForm how the request carried the mandates.
     */
    data class Payload(
        val format: String,
        val mandates: List<Mandate>,
        val delegateDisclosures: List<String>? = null,
        val requestForm: RequestForm = RequestForm.DISCLOSURE,
    )

    /**
     * The Mandate Content carried by an Array Disclosure — `base64url(JSON([salt, value]))`,
     * RFC 9901 §4.2.4.2.
     *
     * @throws IllegalArgumentException if the disclosure is not a two-element array of a JSON object.
     */
    private fun mandateFromDisclosure(disclosure: String): JsonObject {
        val decoded = try {
            json.parseToJsonElement(disclosure.fromBase64Url().decodeToString())
        } catch (err: Exception) {
            throw IllegalArgumentException("'delegate_payload_disclosure' is not a base64url-encoded JSON array", err)
        }
        val array = decoded as? JsonArray
            ?: throw IllegalArgumentException("'delegate_payload_disclosure' does not decode to a JSON array")
        // [salt, value] is the array-element form; [salt, name, value] is the object-property one.
        if (array.size != 2) {
            throw IllegalArgumentException(
                "'delegate_payload_disclosure' has ${array.size} elements — an array disclosure is [salt, value]"
            )
        }
        return array[1] as? JsonObject
            ?: throw IllegalArgumentException("'delegate_payload_disclosure' does not disclose a JSON object")
    }

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
            when (payload.requestForm) {
                RequestForm.DISCLOSURE ->
                    put("delegate_payload_disclosure", JsonPrimitive(payload.mandates.single().disclosure))
                RequestForm.PLAIN ->
                    put("delegate_payload", JsonArray(payload.mandates.map { it.content }))
            }
            payload.delegateDisclosures?.let { list ->
                put("delegate_disclosures", JsonArray(list.map { JsonPrimitive(it) }))
            }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(obj))
    }

    /**
     * Reads either request form: Delegate SD-JWT §7.1's `delegate_payload_disclosure`, or AP2's
     * `delegate_payload` of mandates in the clear, for which the wallet makes the disclosures here,
     * with fresh salts, so the same ones are shown, digested and returned.
     *
     * @throws IllegalArgumentException if a required member is missing or malformed, the request
     *   carries both forms, or a mandate has nothing that can be shown to the user.
     */
    override fun parseOpenId4VpRequest(jsonString: String): Payload {
        val obj = json.parseToJsonElement(jsonString).jsonObject
        val format = (obj["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing 'format' in delegate transaction data")
        val disclosureMember = obj["delegate_payload_disclosure"]
        val plainMember = obj["delegate_payload"]
        val payload = when {
            disclosureMember != null && plainMember != null -> throw IllegalArgumentException(
                "Delegate transaction data carries both 'delegate_payload_disclosure' and 'delegate_payload'"
            )
            disclosureMember != null -> {
                if (format !in FORMATS) {
                    throw IllegalArgumentException(
                        "'format' is '$format', but §7.1 allows only ${FORMATS.joinToString(" or ")}"
                    )
                }
                val disclosure = (disclosureMember as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("'delegate_payload_disclosure' must be a string")
                if (disclosure.isEmpty()) {
                    throw IllegalArgumentException("'delegate_payload_disclosure' is empty — nothing to authorize")
                }
                Payload(
                    format = format,
                    mandates = listOf(Mandate(disclosure, mandateFromDisclosure(disclosure))),
                    delegateDisclosures = parseDelegateDisclosures(obj),
                    requestForm = RequestForm.DISCLOSURE,
                )
            }
            plainMember != null -> {
                if (format != AP2_FORMAT) {
                    throw IllegalArgumentException("'format' is '$format', but AP2 sends '$AP2_FORMAT'")
                }
                val contents = (plainMember as? JsonArray)?.map {
                    it as? JsonObject
                        ?: throw IllegalArgumentException("'delegate_payload' must hold mandate objects")
                } ?: throw IllegalArgumentException("'delegate_payload' must be an array")
                if (contents.isEmpty()) {
                    throw IllegalArgumentException("'delegate_payload' is empty — nothing to authorize")
                }
                Payload(
                    format = format,
                    mandates = contents.map { Mandate(disclosureOf(it), it) },
                    delegateDisclosures = parseDelegateDisclosures(obj),
                    requestForm = RequestForm.PLAIN,
                )
            }
            else -> throw IllegalArgumentException(
                "Missing 'delegate_payload_disclosure' or 'delegate_payload' in delegate transaction data"
            )
        }
        // A mandate the consent screen cannot show must not be signable: the signature is the
        // authorization, so it cannot cover terms the person was never shown.
        if (payload.mandates.any { DelegateMandateText.summarize(it.content).isEmpty() }) {
            throw IllegalArgumentException(
                "a mandate has nothing that can be shown to the user — refusing to request a signature over terms they cannot read"
            )
        }
        return payload
    }

    private fun parseDelegateDisclosures(obj: JsonObject): List<String>? =
        obj["delegate_disclosures"]?.let { disclosures ->
            (disclosures as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.takeIf { list -> list.all { it != null && it.isNotEmpty() && '~' !in it } }
                ?.filterNotNull()
                ?: throw IllegalArgumentException("'delegate_disclosures' must be an array of disclosure strings")
        }

    /**
     * An RFC 9901 §4.2.4.2 Array Disclosure of [content], `base64url(JSON([salt, content]))`, with
     * a fresh 128-bit salt: a mandate the agent later withholds must not be guessable from its
     * digest.
     */
    private fun disclosureOf(content: JsonObject): String =
        JsonArray(listOf(JsonPrimitive(Crypto.secureRandom.nextBytes(16).toBase64Url()), content))
            .toString().encodeToByteArray().toBase64Url()

    /**
     * Delegation is the holder's signature over the mandate, so only a key-bound SD-JWT VC can
     * carry it; Delegate SD-JWT is SD-JWT syntax and has no mdoc equivalent.
     *
     * This runs before the consent prompt, and it is where the mandates are checked against
     * their `delegate_disclosures`, since matching a disclosure to its digest takes a hash. A
     * request whose mandates cannot all be shown, term by term, is not applicable to anything.
     */
    override suspend fun isApplicable(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Boolean {
        if (credential !is KeyBoundSdJwtVcCredential) return false
        val shown = try {
            resolveMandates(transactionData.payload, digestAlgorithm(transactionData.hashAlgorithms))
        } catch (err: IllegalArgumentException) {
            Logger.w(TAG, "Refusing delegate transaction data: ${err.message}")
            return false
        }
        if (shown.any { DelegateMandateText.summarize(it).isEmpty() }) {
            Logger.w(TAG, "Refusing delegate transaction data: a mandate has nothing to show")
            return false
        }
        return super.isApplicable(transactionData, credential)
    }

    /**
     * The mandates of the `delegate` items in [transactionData] as a person reads them, in
     * order: each selectively disclosable claim replaced by its value from the item's
     * `delegate_disclosures` (RFC 9901 §7.1). A consent screen shows these, not
     * [Mandate.content], whose digests mean nothing to a person.
     *
     * Suspends because matching a disclosure to its digest takes a hash.
     *
     * @throws IllegalArgumentException if a digest has no disclosure, or a disclosure is not
     *   referenced exactly once; [isApplicable] refuses such a request before the consent prompt.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun shownMandates(transactionData: List<TransactionData<*>>): List<JsonObject> =
        transactionData.filter { it.type == this }.flatMap { data ->
            resolveMandates(data.payload as Payload, digestAlgorithm(data.hashAlgorithms))
        }

    /**
     * [Payload.mandates] with every selectively disclosable claim resolved from
     * [Payload.delegateDisclosures], digests computed with [algorithm].
     *
     * Delegate SD-JWT §7.1: the delegate payload "MUST NOT contain any disclosures not provided
     * in delegate_disclosures", so a digest without one is refused rather than taken for a decoy.
     */
    internal suspend fun resolveMandates(payload: Payload, algorithm: Algorithm): List<JsonObject> {
        val byDigest = LinkedHashMap<String, JsonArray>()
        for (disclosure in payload.delegateDisclosures.orEmpty()) {
            val digest = Crypto.digest(algorithm, disclosure.encodeToByteArray()).toBase64Url()
            if (byDigest.put(digest, decodeDisclosure(disclosure)) != null) {
                throw IllegalArgumentException("'delegate_disclosures' holds the same disclosure twice")
            }
        }
        val used = mutableSetOf<String>()
        val shown = payload.mandates.map { resolveObject(it.content, byDigest, used) }
        if (used.size != byDigest.size) {
            throw IllegalArgumentException("'delegate_disclosures' holds a disclosure no mandate refers to")
        }
        return shown
    }

    private fun resolveElement(
        element: JsonElement,
        byDigest: Map<String, JsonArray>,
        used: MutableSet<String>,
    ): JsonElement = when (element) {
        is JsonObject -> resolveObject(element, byDigest, used)
        is JsonArray -> JsonArray(element.map { item ->
            val digest = (item as? JsonObject)?.takeIf { it.size == 1 }?.get(ARRAY_ELEMENT_DIGEST_KEY)
            if (digest == null) {
                resolveElement(item, byDigest, used)
            } else {
                val disclosure = takeDisclosure(digest, byDigest, used)
                if (disclosure.size != 2) {
                    throw IllegalArgumentException("An array element's disclosure must be [salt, value]")
                }
                resolveElement(disclosure[1], byDigest, used)
            }
        })
        else -> element
    }

    private fun resolveObject(
        obj: JsonObject,
        byDigest: Map<String, JsonArray>,
        used: MutableSet<String>,
    ): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, value) in obj) {
            if (key != SD_CLAIM && key != SD_ALG_CLAIM) result[key] = resolveElement(value, byDigest, used)
        }
        val digests = obj[SD_CLAIM]?.let { sd ->
            (sd as? JsonArray) ?: throw IllegalArgumentException("'_sd' must be an array of digests")
        }.orEmpty()
        for (digest in digests) {
            val disclosure = takeDisclosure(digest, byDigest, used)
            val name = (disclosure.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (disclosure.size != 3 || name == null) {
                throw IllegalArgumentException("An object property's disclosure must be [salt, name, value]")
            }
            if (name == SD_CLAIM || name == ARRAY_ELEMENT_DIGEST_KEY || name in result) {
                throw IllegalArgumentException("The disclosure of '$name' clashes with a claim already there")
            }
            result[name] = resolveElement(disclosure[2], byDigest, used)
        }
        return JsonObject(result)
    }

    private fun takeDisclosure(digest: JsonElement, byDigest: Map<String, JsonArray>, used: MutableSet<String>): JsonArray {
        val key = (digest as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("A digest must be a string")
        val disclosure = byDigest[key] ?: throw IllegalArgumentException(
            "A selectively disclosable claim has no disclosure in 'delegate_disclosures' — refusing to sign terms that cannot be shown"
        )
        if (!used.add(key)) throw IllegalArgumentException("A disclosure is referenced more than once")
        return disclosure
    }

    /** An RFC 9901 disclosure, `[salt, value]` or `[salt, name, value]`, decoded. */
    private fun decodeDisclosure(disclosure: String): JsonArray {
        val decoded = try {
            json.parseToJsonElement(disclosure.fromBase64Url().decodeToString())
        } catch (err: Exception) {
            throw IllegalArgumentException("A disclosure in 'delegate_disclosures' is not base64url-encoded JSON", err)
        }
        val array = decoded as? JsonArray
        if (array == null || array.size !in 2..3 || (array[0] as? JsonPrimitive)?.isString != true) {
            throw IllegalArgumentException("A disclosure in 'delegate_disclosures' is not [salt, value] or [salt, name, value]")
        }
        return array
    }

    /** The digest algorithm for an item: the first the request names for the transaction data. */
    private fun digestAlgorithm(hashAlgorithms: List<Algorithm>?): Algorithm =
        hashAlgorithms?.firstOrNull() ?: Algorithm.SHA256

    /** Puts the digest of the Mandate Content into the KB-JWT (Delegate SD-JWT §7.1). */
    override suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, JsonElement> =
        super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId) +
            delegateClaims(transactionData)

    /**
     * Adds the disclosures the `delegate_payload` digests commit to after the Key Binding JWT,
     * which makes the presentation a Delegated SD-JWT (§5.1.1): a verifier resolves the mandate
     * from the presentation itself, not from the request it sent.
     */
    override suspend fun generateSdJwtResponse(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): SdJwtTransactionResponse = SdJwtTransactionResponse(
        claims = generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId),
        disclosures = responseDisclosures(transactionData.payload),
    )

    /**
     * The disclosures that follow the Key Binding JWT for one item: each mandate's, whose digests
     * are in `delegate_payload`, then those of the claims selectively disclosable within them.
     */
    internal fun responseDisclosures(payload: Payload): List<String> =
        payload.mandates.map { it.disclosure } + payload.delegateDisclosures.orEmpty()

    /**
     * The claims this type adds to the KB-JWT: in `delegate_payload`, the digest of each mandate's
     * disclosure in RFC 9901's replaced-array-element form, and in `_sd_alg`, the algorithm of
     * that digest.
     *
     * §7.1 lets the digest use any algorithm the request specifies for the transaction data
     * hashes, or SHA-256 when it names none; AP2's requests name SHA-256. `_sd_alg` is always
     * present: without it a verifier reads the digest as SHA-256 (RFC 9901 §4.1.1) and finds no
     * mandate.
     * The digest covers the disclosure's own bytes, never a re-encoding of the JSON inside it.
     *
     * Separate from [generateSdJwtResponseClaims] so a test needs no credential.
     */
    internal suspend fun delegateClaims(transactionData: TransactionData<Payload>): Map<String, JsonElement> {
        val algorithm = digestAlgorithm(transactionData.hashAlgorithms)
        val digests = transactionData.payload.mandates.map { mandate ->
            val digest = Crypto.digest(algorithm, mandate.disclosure.encodeToByteArray()).toBase64Url()
            JsonObject(mapOf(ARRAY_ELEMENT_DIGEST_KEY to JsonPrimitive(digest)))
        }
        return mapOf(
            DELEGATE_PAYLOAD_CLAIM to JsonArray(digests),
            SD_ALG_CLAIM to JsonPrimitive(algorithm.hashAlgorithmName),
        )
    }

    /** The RFC 9901 claim naming the algorithm of the digests in a (KB-)SD-JWT. */
    private const val SD_ALG_CLAIM = "_sd_alg"

    /** The RFC 9901 claim listing the digests of an object's selectively disclosable claims. */
    private const val SD_CLAIM = "_sd"

    private const val TAG = "DelegateTransaction"

    /** RFC 9901 §4.2.4.2: an array element replaced by a disclosure is `{"...": "<digest>"}`. */
    private const val ARRAY_ELEMENT_DIGEST_KEY = "..."
}
