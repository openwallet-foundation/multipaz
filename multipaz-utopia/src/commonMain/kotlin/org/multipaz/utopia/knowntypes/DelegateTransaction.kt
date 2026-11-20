package org.multipaz.utopia.knowntypes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.SdJwtTransactionResponse
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
 * A user authorizes an agent to act later, when they are no longer present — "this assistant may
 * buy groceries, up to $50 a shop, until December". The verifier puts that Mandate Content in a
 * `transaction_data` entry of type `delegate`, and the wallet returns it bound into the Key
 * Binding JWT; the user's key binding is the authorization.
 *
 * Requests come in two forms, because the documents disagree: AP2 sends the mandates in the
 * clear as `delegate_payload`, while
 * [Delegate SD-JWT](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-delegate-sd-jwt) §7.1
 * sends one array disclosure in `delegate_payload_disclosure`. Both are accepted (see
 * [Payload]) and answered alike: the digest of each mandate's disclosure goes in the KB-JWT and
 * the disclosures follow it, a Delegated SD-JWT (§5.1.1),
 * `<SD-JWT>~~<KB-JWT>~<mandate disclosure>~`.
 *
 * AP2 requires the Mandate Content to be shown to the user on a Trusted Surface before it is
 * signed, and the wallet is that surface. This type only reads and answers the protocol, and
 * refuses mandates that cannot be shown; [DelegateMandateText] puts them into words.
 *
 * The consent prompt's built-in rendering shows only a heading for this type, not the mandate, so
 * [addUtopiaTypes] does not register it. An application that registers it must also register a
 * [org.multipaz.prompt.TransactionDataRenderer] for [identifier] that shows what
 * [DelegateMandateText.permissionSummary] words, with every line of
 * [DelegateMandateText.mandateDetails] reachable on its details page.
 */
object DelegateTransaction : TransactionType<DelegateTransaction.Payload>(
    displayName = "Delegate authorization",
    identifier = "delegate",
    // Delegate SD-JWT §5.1.4 fixes both: `delegate_payload` is an array at the KB-JWT top level,
    // and the key binding has its own media type. Nesting would make it unreadable to a verifier.
    nestSdJwtResponseClaims = false,
    // The built-in rendering shows only a heading, not the mandate: a person must not approve a
    // spending permission they were not shown, so a prompt without a renderer for it refuses.
    requiresRenderer = true,
) {
    /**
     * The KB-JWT claim carrying the digest of the Mandate Content (Delegate SD-JWT §7.1).
     *
     * No leading underscore: the draft's source writes it in Markdown italics around an escaped
     * underscore, which datatracker renders as `_delegate_payload_`. §7.1's prose spells it plainly.
     */
    const val DELEGATE_PAYLOAD_CLAIM = "delegate_payload"

    /** The `format` of a request whose mandate ends the chain (§7.1). */
    const val FORMAT_DSD_JWT = "dSD-JWT"

    /** The `format` of a request whose mandate names a further key in `cnf` (§7.1). */
    const val FORMAT_DSD_JWT_KB = "dSD-JWT+KB"

    /** The values §7.1 allows for the request's `format`. */
    val FORMATS = listOf(FORMAT_DSD_JWT, FORMAT_DSD_JWT_KB)

    /** The `format` AP2 sends with mandates in the clear: the VDC format of the returned mandate. */
    const val AP2_FORMAT = "dc+sd-jwt"

    /** The `typ` a Delegate Key Binding JWT carries when it ends the chain (Delegate SD-JWT §5.1.4). */
    const val DELEGATE_KB_TYPE = "kb+sd-jwt"

    /** The `typ` of one whose Delegate Payload names a further key, a KB-SD-JWT+KB (§5.1.4). */
    const val DELEGATE_KB_KB_TYPE = "kb-sd-jwt+kb"

    /**
     * Delegate SD-JWT §5.1.4: "The typ parameter value MUST be replaced with `kb+sd-jwt` for a
     * KB-SD-JWT, and `kb-sd-jwt+kb` for a KB-SD-JWT+KB" — the variant whose Delegate Payload names
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
    ) {
        companion object {
            /**
             * A mandate for [content] given in the clear, as AP2 sends it: its Array Disclosure is
             * `base64url(JSON([salt, content]))` (RFC 9901 §4.2.4.2) with a fresh 128-bit salt, so a
             * mandate the agent later withholds cannot be guessed from its digest.
             *
             * @param random the source of the salt; a test can seed it.
             */
            fun fromContent(content: JsonObject, random: Random = Crypto.secureRandom): Mandate {
                val disclosure = JsonArray(
                    listOf(JsonPrimitive(random.nextBytes(16).toBase64Url()), content)
                ).toString().encodeToByteArray().toBase64Url()
                return Mandate(disclosure, content)
            }
        }
    }

    /**
     * What a delegate transaction data item asks the wallet to sign. The two documents that define
     * it disagree on how a request carries its mandates, and verifiers send both, so there is one
     * subtype per form and each holds only what its form can carry.
     *
     * @property format the delegation format: one of [FORMATS] for [DelegateSdJwt]
     *   (Delegate SD-JWT §7.1), [AP2_FORMAT] for [Ap2].
     * @property mandates the mandates, in request order.
     * @property delegateDisclosures optional disclosures of the selectively disclosable claims
     *   within [mandates], as they arrived.
     */
    sealed class Payload {
        abstract val format: String
        abstract val mandates: List<Mandate>
        abstract val delegateDisclosures: List<String>?

        /**
         * Delegate SD-JWT §7.1: one `delegate_payload_disclosure`, made by the verifier.
         *
         * @property mandate the one mandate the disclosure carries.
         * @property format `dSD-JWT` or `dSD-JWT+KB`.
         * @throws IllegalArgumentException if [format] is not one of [FORMATS].
         */
        data class DelegateSdJwt(
            val mandate: Mandate,
            override val format: String,
            override val delegateDisclosures: List<String>? = null,
        ) : Payload() {
            init {
                require(format in FORMATS) {
                    "'format' is '$format', but §7.1 allows only ${FORMATS.joinToString(" or ")}"
                }
            }

            override val mandates get() = listOf(mandate)
        }

        /**
         * AP2: `delegate_payload`, an array of mandates in the clear. The wallet makes their
         * disclosures (see [Mandate.fromContent]), so the same ones are shown, digested and
         * returned. The format is always [AP2_FORMAT].
         *
         * @property mandates one or more mandates.
         * @throws IllegalArgumentException if [mandates] is empty.
         */
        data class Ap2(
            override val mandates: List<Mandate>,
            override val delegateDisclosures: List<String>? = null,
        ) : Payload() {
            init {
                require(mandates.isNotEmpty()) { "'delegate_payload' is empty — nothing to authorize" }
            }

            override val format get() = AP2_FORMAT
        }
    }

    /**
     * The Mandate Content carried by an Array Disclosure — `base64url(JSON([salt, value]))`,
     * RFC 9901 §4.2.4.2.
     *
     * @throws IllegalArgumentException if the disclosure is not a two-element array of a JSON object.
     */
    private fun mandateFromDisclosure(disclosure: String): JsonObject {
        val decoded = try {
            json.parseToJsonElement(disclosure.fromBase64Url().decodeToString())
        } catch (err: IllegalArgumentException) {
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
        val obj = buildMap {
            put("type", JsonPrimitive(identifier))
            put("format", JsonPrimitive(payload.format))
            put("credential_ids", JsonArray(credentialIds.map { JsonPrimitive(it) }))
            joseHashAlgorithms(hashAlgorithms)?.let {
                put("transaction_data_hashes_alg", JsonArray(it.map { alg -> JsonPrimitive(alg) }))
            }
            when (payload) {
                is Payload.DelegateSdJwt ->
                    put("delegate_payload_disclosure", JsonPrimitive(payload.mandate.disclosure))
                is Payload.Ap2 ->
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
     * Those disclosures exist only in the parsed [Payload]: parsing the same request again makes
     * different ones. A verifier that re-parses a request must therefore not compare its
     * [Mandate.disclosure] values against another parse; the mandate content is what is stable.
     *
     * @throws IllegalArgumentException if a required member is missing or malformed, the request
     *   carries both forms, or a mandate has nothing that can be shown to the user.
     */
    @Throws(IllegalArgumentException::class)
    override fun parseOpenId4VpRequest(jsonString: String): Payload =
        parseOpenId4VpRequest(jsonString, Crypto.secureRandom)

    /**
     * [parseOpenId4VpRequest] with the source of the salts given, so a test can seed it.
     *
     * @param random the source of the salts of the disclosures made for AP2's mandates.
     */
    internal fun parseOpenId4VpRequest(jsonString: String, random: Random): Payload {
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
                val content = mandateFromDisclosure(disclosure)
                if (format == FORMAT_DSD_JWT_KB && "cnf" !in content) {
                    throw IllegalArgumentException(
                        "'format' is '$FORMAT_DSD_JWT_KB', but the mandate names no further key in 'cnf'"
                    )
                }
                Payload.DelegateSdJwt(
                    mandate = Mandate(disclosure, content),
                    format = format,
                    delegateDisclosures = parseDelegateDisclosures(obj),
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
                Payload.Ap2(
                    mandates = contents.map { Mandate.fromContent(it, random) },
                    delegateDisclosures = parseDelegateDisclosures(obj),
                )
            }
            else -> throw IllegalArgumentException(
                "Missing 'delegate_payload_disclosure' or 'delegate_payload' in delegate transaction data"
            )
        }
        if (!payload.mandates.all { DelegateMandateText.namesOnlyShownKeys(it.content) }) {
            throw IllegalArgumentException(
                "a mandate names a 'cnf' key that cannot be shown to the user — " +
                    "refusing to request a signature for an agent they cannot see"
            )
        }
        // A mandate the consent screen cannot show must not be signable: the signature is the
        // authorization, so it cannot cover terms the person was never shown.
        //
        // This only checks that something can be shown. Terms behind digests are still digests at
        // this point, and reading them takes a hash, which this function cannot compute. Whether a
        // person can actually read the resolved mandate is checked in isApplicable().
        if (payload.mandates.any { DelegateMandateText.summarize(it.content).isEmpty() }) {
            throw IllegalArgumentException(
                "a mandate has nothing that can be shown to the user — " +
                    "refusing to request a signature over terms they cannot read"
            )
        }
        return payload
    }

    private fun parseDelegateDisclosures(obj: JsonObject): List<String>? =
        obj["delegate_disclosures"]?.let { disclosures ->
            (disclosures as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.takeIf { list -> list.all { !it.isNullOrEmpty() && '~' !in it } }
                ?.filterNotNull()
                ?: throw IllegalArgumentException("'delegate_disclosures' must be an array of disclosure strings")
        }

    /**
     * Delegation is the holder's signature over the mandate, so only a key-bound SD-JWT VC can
     * carry it; Delegate SD-JWT is SD-JWT syntax and has no mdoc equivalent.
     *
     * This runs before the consent prompt, and it is where the mandates are checked against
     * their `delegate_disclosures`, since matching a disclosure to its digest takes a hash.
     *
     * A request whose mandates cannot all be shown, term by term, is malformed, not a property of
     * the credential, so it throws with the reason, as [parseOpenId4VpRequest] does, instead of
     * returning `false` for the application to see only "unable to satisfy the request".
     *
     * @throws IllegalArgumentException if a mandate's digest has no disclosure, a disclosure is
     *   not referenced exactly once, or a mandate has nothing a person can read.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    override suspend fun isApplicable(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Boolean {
        if (credential !is KeyBoundSdJwtVcCredential) return false
        requireShowable(transactionData.payload, transactionData.hashAlgorithms)
        return super.isApplicable(transactionData, credential)
    }

    /**
     * Resolves the item's mandates (see [resolveMandates]) and refuses what the person could not be
     * shown or what AP2 would not accept after they approved:
     *
     * - a mandate with nothing readable once its terms are resolved;
     * - a key (`cnf`) this wallet cannot show, judged on the resolved mandate: one behind a digest
     *   would otherwise be signed with no row, and with an EC key [sdJwtKbType] still sees no `cnf`
     *   and types the key binding as the end of the chain, which AP2's verifier rejects ("Terminal
     *   KB-SD-JWT MUST NOT carry a 'cnf' claim");
     * - a closed checkout whose `checkout_hash` is not the hash of its `checkout_jwt`, which is
     *   what AP2 defines it as: the screen shows the decoded JWT but the hash is what is signed.
     *
     * @return the resolved mandates.
     * @throws IllegalArgumentException if any of the above, or if [resolveMandates] refuses.
     */
    internal suspend fun requireShowable(payload: Payload, hashAlgorithms: List<Algorithm>?): List<JsonObject> {
        val shown = resolveMandates(payload, hashAlgorithms)
        payload.mandates.zip(shown).forEach { (mandate, resolved) ->
            require(DelegateMandateText.hasReadableLine(resolved)) {
                "a mandate has nothing that can be shown to the user — " +
                    "refusing to request a signature over terms they cannot read"
            }
            require("cnf" in mandate.content || "cnf" !in resolved) {
                "a mandate's 'cnf' is behind a digest: it would be signed unseen, and the key binding would be typed " +
                    "as the end of the chain"
            }
            require(DelegateMandateText.namesOnlyShownKeys(resolved)) {
                "a mandate names a key this wallet cannot show — refusing to request a signature over it"
            }
            requireCheckoutHashMatches(resolved, digestAlgorithm(hashAlgorithms))
        }
        return shown
    }

    /**
     * AP2 defines `checkout_hash` as the base64url hash of the `checkout_jwt` value, with the
     * mandate's `_sd_alg` when it has one and SHA-256 otherwise (`checkout_mandate.json`); AP2's
     * payment side matches `transaction_id` against it. Nothing else ties what the screen shows,
     * the decoded JWT, to what is signed, so a mandate where they differ is refused.
     */
    private suspend fun requireCheckoutHashMatches(mandate: JsonObject, algorithm: Algorithm) {
        val jwt = (mandate[CHECKOUT_JWT_MEMBER] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val hash = (mandate[CHECKOUT_HASH_MEMBER] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val used = if (SD_ALG_CLAIM in mandate) algorithm else Algorithm.SHA256
        val expected = Crypto.digest(used, jwt.encodeToByteArray()).toBase64Url()
        require(hash == expected) {
            "A closed checkout's 'checkout_hash' is not the hash of its 'checkout_jwt': " +
                "what is shown is not what is signed"
        }
    }

    private const val CHECKOUT_JWT_MEMBER = "checkout_jwt"
    private const val CHECKOUT_HASH_MEMBER = "checkout_hash"

    /**
     * What no single `delegate` item shows, checked before the consent prompt because both fail
     * only after the person has approved:
     *
     * - One Key Binding JWT carries one `_sd_alg` and one `transaction_data_hashes_alg`, so the
     *   items must all settle on the same algorithms; items whose requests do not cannot be answered
     *   together. A `delegate` item is only ever answered by an SD-JWT, where `processTransactions`
     *   would otherwise refuse the second algorithm after the person approved.
     * - A disclosure that follows the Key Binding JWT appears once there, so two items cannot share
     *   one, or its digest would appear twice and AP2's verifier rejects it ("Duplicate hash found").
     *
     * @throws IllegalArgumentException if the items cannot be answered in one response.
     */
    @Throws(IllegalArgumentException::class)
    override fun validateTogether(transactionData: List<TransactionData<Payload>>) {
        val digests = transactionData.map { digestAlgorithm(it.hashAlgorithms) }.distinct()
        require(digests.size <= 1) {
            "Delegate items would be digested with different algorithms " +
                "${digests.map { it.hashAlgorithmName }}, but one Key Binding JWT names a single '_sd_alg'"
        }
        val hashes = transactionData.map { it.hashAlgorithms?.first() ?: Algorithm.SHA256 }.distinct()
        require(hashes.size <= 1) {
            "Delegate items ask for different transaction data hash algorithms " +
                "${hashes.map { it.hashAlgorithmName }}, but one Key Binding JWT names a single " +
                "'transaction_data_hashes_alg'"
        }
        val disclosures = HashSet<String>()
        for (data in transactionData) {
            for (disclosure in responseDisclosures(data.payload)) {
                require(disclosures.add(disclosure)) {
                    "A disclosure is shared by more than one delegate item, so its digest would appear twice"
                }
            }
        }
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
    internal suspend fun shownMandates(transactionData: List<TransactionData<*>>): List<JsonObject> =
        transactionData.filter { it.type == this }.flatMap { data ->
            resolveMandates(data.payload as Payload, data.hashAlgorithms)
        }

    /**
     * [Payload.mandates] with every selectively disclosable claim resolved from
     * [Payload.delegateDisclosures], every digest computed with the one algorithm the Key Binding
     * JWT names, [digestAlgorithm] of [hashAlgorithms].
     *
     * One algorithm for display and signature: AP2's verifier hashes every nested digest with the
     * Key Binding JWT's `_sd_alg` and silently drops what does not match, so a term resolved under
     * the mandate's own `_sd_alg` would be shown to the person and never enforced. Under any other
     * algorithm a digest finds no disclosure and the request is refused. A mandate that names a
     * different `_sd_alg` is refused too: Delegate SD-JWT §5.1.4 keeps it out of the Delegate Payload.
     *
     * Delegate SD-JWT §7.1: the delegate payload "MUST NOT contain any disclosures not provided
     * in delegate_disclosures", so a digest without one is refused rather than taken for a decoy.
     *
     * @throws IllegalArgumentException if a digest has no disclosure, a disclosure is not referenced
     *   exactly once, or a mandate's `_sd_alg` is not the algorithm the digests are made with.
     */
    internal suspend fun resolveMandates(payload: Payload, hashAlgorithms: List<Algorithm>?): List<JsonObject> {
        val algorithm = digestAlgorithm(hashAlgorithms)
        val disclosures = payload.delegateDisclosures.orEmpty()
        val indexByDigest = LinkedHashMap<String, Int>()
        disclosures.forEachIndexed { index, disclosure ->
            val digest = Crypto.digest(algorithm, disclosure.encodeToByteArray()).toBase64Url()
            if (indexByDigest.put(digest, index) != null) {
                throw IllegalArgumentException("'delegate_disclosures' holds the same disclosure twice")
            }
        }
        val lookup = DisclosureLookup(indexByDigest, disclosures.map { decodeDisclosure(it) }, mutableSetOf())
        val shown = payload.mandates.map { mandate ->
            mandate.content[SD_ALG_CLAIM]?.let { requireSdAlgIs(algorithm, it) }
            resolveObject(mandate.content, lookup)
        }
        if (lookup.used.size != disclosures.size) {
            throw IllegalArgumentException("'delegate_disclosures' holds a disclosure no mandate refers to")
        }
        return shown
    }

    /** A mandate's `_sd_alg`, when it has one, must name the algorithm the response's digests use. */
    private fun requireSdAlgIs(algorithm: Algorithm, member: JsonElement) {
        val name = (member as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("'_sd_alg' must be a string")
        val declared = Algorithm.fromHashAlgorithmIdentifier(name)
        require(declared == algorithm) {
            "A mandate's '_sd_alg' is '$name', but the Key Binding JWT digests with " +
                "'${algorithm.hashAlgorithmName}'; AP2's verifier would not enforce what it hides"
        }
    }

    /**
     * The `delegate_disclosures` of an item, found by the digest a mandate refers to one by.
     *
     * @property indexByDigest the position of each disclosure, by its digest.
     * @property decoded every disclosure, decoded, by position.
     * @property used the positions a mandate has referred to so far, across all the mandates: each
     *   disclosure must be referred to exactly once.
     */
    private class DisclosureLookup(
        val indexByDigest: Map<String, Int>,
        val decoded: List<JsonArray>,
        val used: MutableSet<Int>,
    )

    private fun resolveElement(
        element: JsonElement,
        lookup: DisclosureLookup,
    ): JsonElement = when (element) {
        is JsonObject -> resolveObject(element, lookup)
        is JsonArray -> JsonArray(element.map { item ->
            val digest = (item as? JsonObject)?.takeIf { it.size == 1 }?.get(ARRAY_ELEMENT_DIGEST_KEY)
            if (digest == null) {
                resolveElement(item, lookup)
            } else {
                val disclosure = takeDisclosure(digest, lookup)
                if (disclosure.size != 2) {
                    throw IllegalArgumentException("An array element's disclosure must be [salt, value]")
                }
                resolveElement(disclosure[1], lookup)
            }
        })
        else -> element
    }

    private fun resolveObject(
        obj: JsonObject,
        lookup: DisclosureLookup,
    ): JsonObject {
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, value) in obj) {
            if (key != SD_CLAIM && key != SD_ALG_CLAIM) result[key] = resolveElement(value, lookup)
        }
        val digests = obj[SD_CLAIM]?.let { sd ->
            (sd as? JsonArray) ?: throw IllegalArgumentException("'_sd' must be an array of digests")
        }.orEmpty()
        for (digest in digests) {
            val disclosure = takeDisclosure(digest, lookup)
            val name = (disclosure.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (disclosure.size != 3 || name == null) {
                throw IllegalArgumentException("An object property's disclosure must be [salt, name, value]")
            }
            if (name == SD_CLAIM || name == ARRAY_ELEMENT_DIGEST_KEY || name in result) {
                throw IllegalArgumentException("The disclosure of '$name' clashes with a claim already there")
            }
            result[name] = resolveElement(disclosure[2], lookup)
        }
        return JsonObject(result)
    }

    private fun takeDisclosure(digest: JsonElement, lookup: DisclosureLookup): JsonArray {
        val key = (digest as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("A digest must be a string")
        val index = lookup.indexByDigest[key] ?: throw IllegalArgumentException(
            "A selectively disclosable claim has no disclosure in 'delegate_disclosures' — " +
                "refusing to sign terms that cannot be shown"
        )
        if (!lookup.used.add(index)) throw IllegalArgumentException("A disclosure is referenced more than once")
        return lookup.decoded[index]
    }

    /** An RFC 9901 disclosure, `[salt, value]` or `[salt, name, value]`, decoded. */
    private fun decodeDisclosure(disclosure: String): JsonArray {
        val decoded = try {
            json.parseToJsonElement(disclosure.fromBase64Url().decodeToString())
        } catch (err: IllegalArgumentException) {
            throw IllegalArgumentException("A disclosure in 'delegate_disclosures' is not base64url-encoded JSON", err)
        }
        val array = decoded as? JsonArray
        if (array == null || array.size !in 2..3 || (array[0] as? JsonPrimitive)?.isString != true) {
            throw IllegalArgumentException(
                "A disclosure in 'delegate_disclosures' is not [salt, value] or [salt, name, value]"
            )
        }
        return array
    }

    /**
     * The algorithm of the digest of each mandate's disclosure that goes in the KB-JWT: one the
     * request names for the transaction data. SHA-256 when it is offered, because AP2's SDK
     * rejects a response in any other; otherwise the first named. SHA-256 when it names none.
     */
    private fun digestAlgorithm(hashAlgorithms: List<Algorithm>?): Algorithm =
        if (hashAlgorithms.isNullOrEmpty() || Algorithm.SHA256 in hashAlgorithms) {
            Algorithm.SHA256
        } else {
            hashAlgorithms.first()
        }

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
     * hashes, or SHA-256 when it names none; see [digestAlgorithm] for which one of several. `_sd_alg` is always
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

    /** RFC 9901 §4.2.4.2: an array element replaced by a disclosure is `{"...": "<digest>"}`. */
    private const val ARRAY_ELEMENT_DIGEST_KEY = "..."
}
