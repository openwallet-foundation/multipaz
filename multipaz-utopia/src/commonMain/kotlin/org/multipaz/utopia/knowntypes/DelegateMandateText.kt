package org.multipaz.utopia.knowntypes

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.multipaz.presentment.TransactionData
import org.multipaz.util.fromBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Instant

/**
 * The consent screen's wording for [DelegateTransaction] mandates: AP2's Mandate Content put into
 * words a person reads before signing it.
 *
 * Kept apart from [DelegateTransaction], which only reads and answers the protocol, so each can
 * change on its own; an official AP2 library could replace the protocol side while this stays.
 *
 * It works on mandates as [DelegateTransaction.shownMandates] gives them, with their selectively
 * disclosed terms filled in. The text is English.
 */
object DelegateMandateText {
    /** The title of a spending permission on the consent screen. */
    const val TITLE = "Spending permission"

    /** Under [TITLE] in the consent prompt: what approving the permission lets happen. */
    const val SUMMARY_EXPLAINER =
        "An agent will be able to spend for you without asking again, until these limits are reached."

    /** Under [TITLE] on the details page. */
    const val DETAILS_EXPLAINER = "Everything below is part of what you sign."

    /** The label for who asked for the permission. */
    const val REQUESTED_BY = "Requested by"

    /**
     * Shown in place of the permission when its mandates cannot be read. [DelegateTransaction]
     * refuses such a request before the consent prompt, so a screen should never need it.
     */
    const val CANNOT_SHOW = "This spending permission can't be shown. Don't approve it."

    /** The raw mandate type, printed under a section's heading on the details page. */
    fun mandateTypeLine(mandateType: String): String = "Type: $mandateType"

    /**
     * One readable line on the consent screen.
     *
     * @property label what the line is — a mandate member or constraint, in words.
     * @property value what it says, formatted for a person (money, dates, lists).
     * @property shape what the value looks like, which is how a screen decides its prominence.
     * @property detail a secondary line under [value] — the domain under a merchant's name.
     * @property kind what the line is about, which is how a screen picks an icon or a position.
     *   [label] is English wording for a person to read and may change; [kind] is the contract.
     */
    data class SummaryLine(
        val label: String,
        val value: String,
        val shape: Shape = Shape.TEXT,
        val detail: String? = null,
        val kind: Kind = Kind.OTHER,
    )

    /** What a row of [PermissionSummary.rows] is about. */
    enum class Kind {
        /** Where the agent may buy (`checkout.allowed_merchants`). */
        MERCHANT,

        /** Who may be paid, when they are not the merchants (`payment.allowed_payees`, `payee`). */
        PAYEE,

        /** What may be bought (`checkout.line_items`). */
        ITEMS,

        /** When the permission ends (`exp`). */
        EXPIRY,

        /** Who the permission is for: the agent's key (`cnf`). */
        AGENT,

        /** Anything else. */
        OTHER,
    }

    /**
     * What a value looks like. The renderer walks arbitrary JSON and cannot know which field
     * names matter, so prominence is decided by shape: a constraint type nobody has seen still
     * lands in the right place.
     */
    enum class Shape {
        /** An amount and a currency. The limit being granted. */
        MONEY,

        /** A point in time. */
        INSTANT,

        /** A long unreadable identifier — a digest, a nonce. */
        OPAQUE,

        /** Who the permission empowers. Unreadable like an identifier, but it is the "who". */
        PARTY,

        /** Anything else a person can read. */
        TEXT,
    }

    /**
     * The lines for a whole group of mandates that are signed together.
     *
     * Deduplicated on (label, value): a merchant named by both the checkout and the payment
     * mandate is one fact about the permission, not two, and printing it twice crowds out
     * something the person has not seen.
     *
     * @property money the amounts — the limits being granted.
     * @property rest what a person can read, in the order it arrived.
     * @property opaque digests and nonces: kept, but never given a summary row. They change
     *   nobody's mind, and at full prominence they push the amount off the screen.
     */
    internal data class GroupSummary(
        val money: List<SummaryLine>,
        val rest: List<SummaryLine>,
        val opaque: List<SummaryLine>,
    ) {
        /** How many lines the group holds in total. */
        val size: Int get() = money.size + rest.size + opaque.size
    }

    /**
     * A spending limit, worded for a person: [label] says which kind of limit it is ("Per
     * purchase, up to", "In total, up to"), because two bare "max" amounts are indistinguishable.
     *
     * @property amount the figure alone, so a screen can set it larger than [currency].
     */
    data class Limit(val label: String, val amount: String, val currency: String)

    /**
     * One mandate as its details page shows it.
     *
     * @property heading the type in words ("What can be bought"); an unrecognised type says so
     *   rather than being guessed at.
     * @property type the mandate's `vct`, blank when it does not say. A machine name, printed
     *   beside [heading] so a wallet never claims to understand a condition it has not seen.
     * @property lines every line of the mandate, opaque ones included.
     */
    data class MandateDetails(
        val heading: String,
        val type: String,
        val lines: List<SummaryLine>,
    )

    /**
     * A group of mandates as a person reads them.
     *
     * @property limits the spending limits, in the order they arrived.
     * @property rows short label/value rows: At, For, Until and Agent first, then every
     *   constraint this wallet does not recognise, under the generic label [summarize] gives it.
     * @property details every line [summarizeGroup] produces, opaque ones included, for the
     *   disclosure. Its size is the true field count.
     * @property hiddenCount how many values the [rows] leave out because nobody can read them — a
     *   digest, an identifier — so a screen can say "2 more conditions" and point to [details],
     *   where they are all listed. Each value is judged on its own: a readable value beside an
     *   identifier stays in its row.
     */
    data class PermissionSummary(
        val limits: List<Limit>,
        val rows: List<SummaryLine>,
        val details: List<SummaryLine>,
        val hiddenCount: Int,
    )

    /**
     * [describePermission] for the `delegate` items in [transactionData], their mandates filled in
     * by [DelegateTransaction.shownMandates]. This is what a consent screen calls: the one way to
     * get a summary, so the terms behind digests cannot be left out by passing unresolved mandates.
     *
     * @throws IllegalArgumentException if a mandate can't be filled in; [DelegateTransaction]
     *   refuses such a request before the consent prompt.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun permissionSummary(transactionData: List<TransactionData<*>>): PermissionSummary =
        describePermission(DelegateTransaction.shownMandates(transactionData))

    /**
     * The mandates signed together, described in words.
     *
     * Known AP2 constraints map to plain-language rows; a constraint is only mapped when every
     * member it carries is understood, so nothing is summarised away. Anything else keeps the
     * generic rendering, and [PermissionSummary.details] holds all of it regardless.
     *
     * The merchant ("At") comes only from the mandate, never from the requester: the page asking
     * for the signature is not necessarily the merchant the agent may buy from.
     *
     * The expiry is shown as a date and a time in UTC, the same way the details show every
     * moment: it is an instant, so it reads the same wherever the person is, and a permission
     * that lasts an hour does not read as a bare date.
     *
     * Internal because [mandates] must be the resolved ones: a mandate's own content still holds
     * digests where terms are selectively disclosed, and describing it would drop those terms
     * without a word. Outside this module, [permissionSummary] is the way in.
     */
    internal fun describePermission(mandates: List<JsonObject>): PermissionSummary {
        val limits = LinkedHashSet<Limit>()
        val merchants = LinkedHashSet<Merchant>()
        val payees = LinkedHashSet<Merchant>()
        val items = LinkedHashSet<String>()
        val until = LinkedHashSet<String>()
        val agents = LinkedHashSet<String>()
        val other = LinkedHashSet<SummaryLine>()
        for (mandate in mandates) {
            for ((key, value) in mandate) {
                val amount = amountOf(value)
                val party = (value as? JsonObject)?.let(::merchantOf)
                when {
                    key == "vct" -> {}
                    key == "cnf" -> keyExcerpt(value)?.let { agents += "Key only · $it" }
                    key == "exp" && value is JsonPrimitive && value.longOrNull != null ->
                        until += formatMoment(value.long)
                    // A closed payment mandate states the exact amount: the figure to read first.
                    key == "payment_amount" && amount != null ->
                        limits += Limit("Amount", formatMinorAmount(amount.first, amount.second), amount.second)
                    key == "payee" && party != null -> payees += party
                    value is JsonArray -> value.forEach { element ->
                        val known = (element as? JsonObject)?.let {
                            describeConstraint(it, limits, merchants, payees, items)
                        } ?: false
                        if (!known) other += lineForElement(key, element)
                    }
                    else -> other += linesFor(key, value)
                }
            }
        }
        var hiddenCount = 0
        val rows = buildList {
            partiesRow("At", Kind.MERCHANT, merchants)?.let(::add)
            // Who is paid is said apart from where the agent shops, unless they are the same.
            if (payees != merchants) partiesRow("Paid to", Kind.PAYEE, payees)?.let(::add)
            if (items.isNotEmpty()) add(SummaryLine("For", items.joinToString("\n"), kind = Kind.ITEMS))
            if (until.isNotEmpty()) {
                add(SummaryLine("Until", until.joinToString(", "), Shape.INSTANT, kind = Kind.EXPIRY))
            }
            if (agents.isNotEmpty()) {
                add(SummaryLine("Agent", agents.joinToString(", "), Shape.PARTY, kind = Kind.AGENT))
            }
            for (line in other) {
                if (line.shape != Shape.OPAQUE) {
                    add(line)
                    continue
                }
                // A row is opaque when any value in it is, so judge the values one by one: dropping
                // the row would take a readable description down with the identifier beside it.
                val values = line.value.split(VALUE_SEPARATOR)
                val readable = values.filter { shapeOf(it) != Shape.OPAQUE }
                hiddenCount += values.size - readable.size
                if (readable.isNotEmpty()) {
                    add(line.copy(value = readable.joinToString(VALUE_SEPARATOR), shape = Shape.TEXT))
                }
            }
        }
        val group = summarizeGroup(mandates)
        return PermissionSummary(
            limits = limits.toList(),
            rows = rows,
            details = group.money + group.rest + group.opaque,
            hiddenCount = hiddenCount,
        )
    }

    private const val AMOUNT_RANGE = "payment.amount_range"
    private const val BUDGET = "payment.budget"
    private const val LINE_ITEMS = "checkout.line_items"
    private const val ALLOWED_MERCHANTS = "checkout.allowed_merchants"
    private const val ALLOWED_PAYEES = "payment.allowed_payees"

    /**
     * The AP2 constraint kinds put into words, each with every member it understands. A
     * constraint of another kind, or carrying another member, keeps the generic rendering, so
     * nothing it says is summarised away. Kept in step with AP2's schemas by a test.
     */
    internal val UNDERSTOOD_CONSTRAINTS: Map<String, Set<String>> = mapOf(
        AMOUNT_RANGE to setOf("currency", "min", "max"),
        BUDGET to setOf("currency", "max"),
        LINE_ITEMS to setOf("items"),
        ALLOWED_MERCHANTS to setOf("allowed"),
        ALLOWED_PAYEES to setOf("allowed"),
    )

    /**
     * Folds one constraint into the readable summary when this wallet understands all of it.
     *
     * @return false when the constraint is not recognised, or carries a member that is not, and
     *   must be rendered generically instead.
     */
    private fun describeConstraint(
        constraint: JsonObject,
        limits: MutableSet<Limit>,
        merchants: MutableSet<Merchant>,
        payees: MutableSet<Merchant>,
        items: MutableSet<String>,
    ): Boolean {
        val type = (constraint["type"] as? JsonPrimitive)?.contentOrNull ?: return false
        val understood = UNDERSTOOD_CONSTRAINTS[type] ?: return false
        if (!constraint.keys.all { it == "type" || it in understood }) return false
        // Minor units are a JSON number: "5000" in quotes is a string, as in [amountOf].
        fun minor(member: String) = (constraint[member] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return when (type) {
            AMOUNT_RANGE -> {
                val currency = (constraint["currency"] as? JsonPrimitive)?.contentOrNull ?: return false
                // `max` is required, and a bound that is there but not a number is not skipped: showing
                // "at least 1.00" for {"min":100,"max":"abc"} would hide the cap that was sent.
                val max = minor("max") ?: return false
                val min = constraint["min"]?.let { minor("min") ?: return false }
                limits += if (min != null) {
                    Limit(
                        "Per purchase, between",
                        "${formatMinorAmount(min, currency)} – ${formatMinorAmount(max, currency)}",
                        currency
                    )
                } else {
                    Limit("Per purchase, up to", formatMinorAmount(max, currency), currency)
                }
                true
            }
            BUDGET -> {
                val currency = (constraint["currency"] as? JsonPrimitive)?.contentOrNull ?: return false
                val max = amountInMinorUnits(type, constraint["max"], currency) ?: return false
                limits += Limit("In total, up to", formatMinorAmount(max, currency), currency)
                true
            }
            LINE_ITEMS -> {
                val requirements = (constraint["items"] as? JsonArray)
                    ?.map { (it as? JsonObject)?.let(::describeRequirement) ?: return false }
                    ?: return false
                if (requirements.isEmpty()) return false
                items += requirements
                true
            }
            ALLOWED_MERCHANTS, ALLOWED_PAYEES -> {
                val allowed = (constraint["allowed"] as? JsonArray)
                    ?.map { (it as? JsonObject)?.let(::merchantOf) ?: return false }
                    ?: return false
                if (allowed.isEmpty()) return false
                // Deduplicated only when identical: the same id under another name is shown too.
                if (type == ALLOWED_PAYEES) payees += allowed else merchants += allowed
                true
            }
            else -> false
        }
    }

    /**
     * An AP2 merchant (`types/merchant.json`), as a person reads it.
     *
     * @property key the merchant's id, or its name when it has none.
     * @property domain where it trades, shown under [name]; null when it would only repeat it.
     * @property id the merchant's id, when it says something [name] and [domain] do not. AP2 matches
     *   merchants on the id alone when both sides have one, so it is the field that is enforced; the
     *   name and the website are not.
     */
    private data class Merchant(val key: String, val name: String, val domain: String?, val id: String?) {
        /** What goes under the name: where it trades, and the id AP2 matches on. Null when neither. */
        val detail: String? get() = listOfNotNull(domain, id?.let { "ID $it" }).joinToString(" · ").ifEmpty { null }
    }

    /** One row naming [parties]: the name with its domain and id underneath, or one per line. */
    private fun partiesRow(label: String, kind: Kind, parties: Set<Merchant>): SummaryLine? = when (parties.size) {
        0 -> null
        1 -> parties.single().let { SummaryLine(label, it.name, detail = it.detail, kind = kind) }
        else -> SummaryLine(
            label,
            parties.joinToString("\n") { m -> m.detail?.let { "${m.name} ($it)" } ?: m.name },
            kind = kind,
        )
    }

    /**
     * An AP2 amount (`types/amount.json`): integer minor units and a currency, as `amount` and
     * `currency` and nothing else. Returns the minor units and the currency.
     */
    private fun amountOf(value: JsonElement): Pair<Long, String>? {
        val obj = value as? JsonObject ?: return null
        if (obj.keys != AMOUNT_MEMBERS) return null
        val amount = (obj["amount"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val currency = (obj["currency"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return amount to currency
    }

    /** The members of `types/amount.json`. */
    internal val AMOUNT_MEMBERS = setOf("amount", "currency")

    /** The members of `types/merchant.json`, and `origin`, which some verifiers send instead of `website`. */
    internal val MERCHANT_MEMBERS = setOf("id", "name", "website", "origin")

    private fun merchantOf(obj: JsonObject): Merchant? {
        if (!obj.keys.all { it in MERCHANT_MEMBERS }) return null
        fun member(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val id = member("id")
        val name = member("name") ?: id ?: return null
        val host = (member("website") ?: member("origin"))?.let(::hostOf)
        return Merchant(
            key = id ?: name,
            name = name,
            domain = host?.takeIf { it != name },
            // Shown whenever it adds something: the name and the website are what a person reads, but
            // the id is what AP2 compares, so a merchant named like another must not pass as it.
            id = id?.takeIf { it != name && it != host },
        )
    }

    /**
     * `https://shop.example.com/path` → `shop.example.com`. Userinfo is dropped: in
     * `https://shop.example@evil.example/` the host is `evil.example`, and showing the part before
     * the `@` would let a merchant's name pose as its domain.
     */
    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')

    /** The members of a `checkout.line_items` requirement, and of an item it accepts. */
    internal val REQUIREMENT_MEMBERS = setOf("id", "acceptable_items", "quantity")
    internal val ITEM_MEMBERS = setOf("id", "title")

    /**
     * One `checkout.line_items` requirement: up to `quantity` of any one of `acceptable_items`.
     * Reads "Up to 2 × Latte (small) · ID latte-s or Latte (large) · ID latte-l". AP2's verifier
     * accepts up to the quantity, and a cart that meets only one of several requirements, so
     * "2 × Latte" would read as exactly two; the quantity is kept even when it is one.
     *
     * AP2 matches items on `id` only, so the id is shown whenever it differs from the title (a
     * requester can title any SKU "Latte"), and items are merged on id, not on title.
     *
     * An empty `acceptable_items` is a wildcard: any item, up to the quantity. That is the broader
     * reading of AP2's SDK v0.2.0 (`max_flow_helper.py`), and what its verifier enforces; the
     * schema's text ("one and only one must be present") says otherwise.
     *
     * @return null when the requirement carries anything this wallet does not understand.
     */
    private fun describeRequirement(requirement: JsonObject): String? {
        if (!requirement.keys.all { it in REQUIREMENT_MEMBERS }) return null
        val accepted = (requirement["acceptable_items"] as? JsonArray)?.map { element ->
            val item = element as? JsonObject ?: return null
            if (!item.keys.all { it in ITEM_MEMBERS }) return null
            fun member(name: String) = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = member("id")
            val name = member("title") ?: id ?: return null
            (id ?: name) to if (id != null && id != name) "$name · ID $id" else name
        } ?: return null
        val quantity = requirement["quantity"]?.let { (it as? JsonPrimitive)?.longOrNull ?: return null } ?: 1L
        val what = accepted.distinctBy { it.first }.joinToString(" or ") { it.second }.ifEmpty { "any item" }
        return "Up to $quantity × $what"
    }

    /** [summarize] over several mandates at once, deduplicated and with the amounts separated. */
    internal fun summarizeGroup(mandates: List<JsonObject>): GroupSummary {
        val seen = LinkedHashMap<Pair<String, String>, SummaryLine>()
        for (mandate in mandates) {
            for (line in summarize(mandate)) {
                val key = line.label to line.value
                if (!seen.containsKey(key)) seen[key] = line
            }
        }
        val lines = seen.values.toList()
        return GroupSummary(
            money = lines.filter { it.shape == Shape.MONEY },
            rest = lines.filter { it.shape != Shape.MONEY && it.shape != Shape.OPAQUE },
            opaque = lines.filter { it.shape == Shape.OPAQUE },
        )
    }

    /**
     * The Mandate Content of one mandate, rendered for a consent screen.
     *
     * A mandate with no [hasReadableLine] cannot be shown, and must not be signed —
     * [DelegateTransaction.parseOpenId4VpRequest] refuses it.
     */
    internal fun summarize(mandate: JsonObject): List<SummaryLine> =
        mandate.flatMap { (key, value) -> linesFor(key, value) }

    /**
     * Whether [summarize] gives a person something to read: at least one line that is not
     * [Shape.OPAQUE]. A mandate of nothing but digests would pass a mere "not empty" test, yet
     * the person would be signing a cart they cannot read.
     */
    internal fun hasReadableLine(mandate: JsonObject): Boolean =
        summarize(mandate).any { it.shape != Shape.OPAQUE }

    /**
     * The members of a `checkout_jwt`: AP2's closed checkout mandate carries the checkout as a JWT,
     * so the person reads the checkout as the requester sent it rather than the JWT itself. The
     * signature is not verified, so this is not shown as what the merchant signed.
     *
     * AP2's SDK parses the payload as a UCP Checkout (`checkout_mandate_chain.py`): its `totals`
     * and each item's `price` and `totals` are integer minor units, and the `currency` is at the
     * top, so they are shown as money in that currency, the `total` first. Anything else is
     * rendered generically, like any other mandate member. A value that is not a JWT with a JSON
     * payload keeps the generic rendering, where it is opaque.
     */
    private fun checkoutLines(value: JsonElement): List<SummaryLine>? {
        val jwt = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val segments = jwt.split('.')
        if (segments.size != 3) return null
        val payload = try {
            Json.parseToJsonElement(segments[1].fromBase64Url().decodeToString()) as? JsonObject
        } catch (err: IllegalArgumentException) {
            null
        } ?: return null
        val currency = (payload["currency"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val lines = ArrayList<SummaryLine>()
        // The total leads: it is the figure the person is agreeing to.
        payload["totals"]?.let { totals ->
            (currency?.let { totalLines(totals, it) } ?: linesFor("totals", totals)).let { lines += it }
        }
        for ((member, memberValue) in payload) {
            lines += when (member) {
                "totals" -> continue
                "line_items" -> currency?.let { lineItemLines(memberValue, it) } ?: linesFor(member, memberValue)
                else -> linesFor(member, memberValue)
            }
        }
        return lines
    }

    private val TOTAL_MEMBERS = setOf("type", "display_text", "amount")

    /** UCP `totals`, `total` first, or null when an entry is not `{type, amount}` in integer minor units. */
    private fun totalLines(totals: JsonElement, currency: String, prefix: String = ""): List<SummaryLine>? {
        val entries = (totals as? JsonArray)?.map { entry ->
            val obj = entry as? JsonObject ?: return null
            if (!obj.keys.all { it in TOTAL_MEMBERS }) return null
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val amount = (obj["amount"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            type to amount
        } ?: return null
        return entries.sortedByDescending { it.first == "total" }.map { (type, amount) ->
            SummaryLine(prettyLabel(prefix + type), formatMinorUnits(amount, currency), Shape.MONEY)
        }
    }

    private val UCP_LINE_ITEM_MEMBERS = setOf("id", "item", "quantity", "totals", "parent_id")
    private val UCP_ITEM_MEMBERS = setOf("id", "title", "price", "image_url")

    /**
     * UCP `line_items`: each as "quantity × title · ID id · price each", then its totals; null if
     * not understood.
     */
    private fun lineItemLines(lineItems: JsonElement, currency: String): List<SummaryLine>? {
        val items = lineItems as? JsonArray ?: return null
        return items.flatMap { element ->
            val lineItem = element as? JsonObject ?: return null
            if (!lineItem.keys.all { it in UCP_LINE_ITEM_MEMBERS }) return null
            val item = lineItem["item"] as? JsonObject ?: return null
            if (!item.keys.all { it in UCP_ITEM_MEMBERS }) return null
            fun text(name: String) = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = text("id")
            val name = text("title") ?: id ?: return null
            fun integer(element: JsonElement?): Long? =
                (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            val price = item["price"]?.let { integer(it) ?: return null }
            val quantity = lineItem["quantity"]?.let { integer(it) ?: return null }
            // AP2 matches items on `id`, so it is shown whenever it differs from the title.
            val what = if (id != null && id != name) "$name · ID $id" else name
            val text = buildString {
                append(quantity?.let { "$it × " }.orEmpty()).append(what)
                price?.let { append(" · ").append(formatMinorUnits(it, currency)).append(" each") }
            }
            val totals = lineItem["totals"]?.let { totalLines(it, currency, prefix = "item ") ?: return null }.orEmpty()
            listOf(SummaryLine("Item", text, Shape.TEXT)) + totals
        }
    }

    /** The generic lines for one mandate member. */
    private fun linesFor(key: String, value: JsonElement): List<SummaryLine> {
        if (key == CHECKOUT_JWT) checkoutLines(value)?.let { return it }
        val amount = amountOf(value)
        return when {
            key == "vct" -> emptyList() // the heading, rendered separately
            key == "cnf" ->
                listOfNotNull(keyExcerpt(value)?.let { SummaryLine("Authorized agent key", it, Shape.PARTY) })
            key in INSTANT_CLAIMS && value is JsonPrimitive && value.longOrNull != null ->
                listOf(SummaryLine(INSTANT_CLAIMS.getValue(key), formatMoment(value.long), Shape.INSTANT))
            value is JsonArray -> value.map { element -> lineForElement(key, element) }
            // `types/amount.json` is minor units: 15000 USD is "150.00 USD", not "amount 15000".
            amount != null ->
                listOf(SummaryLine(prettyLabel(key), formatMinorUnits(amount.first, amount.second), Shape.MONEY))
            else -> renderValue(value).let { listOf(SummaryLine(prettyLabel(key), it, shapeOf(it))) }
        }
    }

    /**
     * [mandate] as its details page shows it: heading, type and every line, in one value, so a
     * renderer does not assemble them itself.
     */
    internal fun detailsOf(mandate: JsonObject): MandateDetails {
        val type = mandateType(mandate)
        return MandateDetails(heading = headingFor(type), type = type, lines = summarize(mandate))
    }

    /**
     * [detailsOf] for each `delegate` mandate in [transactionData], their selectively disclosed
     * terms filled in by [DelegateTransaction.shownMandates]: one section per mandate, in full.
     *
     * Unlike [permissionSummary] it does not deduplicate what the mandates share. AP2 requires
     * the signed Mandate Content to be shown, so a details page lists every mandate as it will be
     * signed, even when several arrive in one transaction data item. The one way to get these
     * lines, so a details page cannot show the digests where terms are selectively disclosed.
     *
     * @throws IllegalArgumentException if a mandate can't be filled in; [DelegateTransaction]
     *   refuses such a request before the consent prompt.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun mandateDetails(transactionData: List<TransactionData<*>>): List<MandateDetails> =
        DelegateTransaction.shownMandates(transactionData).map(::detailsOf)

    /** The mandate type, for the heading above its lines. Blank when the mandate does not say. */
    internal fun mandateType(mandate: JsonObject): String =
        (mandate["vct"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * A heading for a mandate type, in words.
     *
     * A type identifier is a machine name and means nothing to a person. An unrecognised one is
     * said to be unrecognised rather than guessed at — the raw identifier is still printed, so a
     * wallet never claims to understand a condition it has not seen.
     */
    internal fun headingFor(mandateType: String): String =
        HEADINGS[mandateType] ?: "A condition your wallet doesn't recognise"

    /** A heading for each AP2 mandate type (its `vct`). */
    internal val HEADINGS = mapOf(
        "mandate.checkout.open.1" to "What can be bought",
        "mandate.checkout.1" to "What can be bought",
        "mandate.payment.open.1" to "How much can be spent",
        "mandate.payment.1" to "How much can be spent",
    )

    internal val INSTANT_CLAIMS = mapOf(
        "exp" to "Valid until",
        "nbf" to "Valid from",
        "iat" to "Issued",
    )

    /** An array element. An object carrying a `type` reads better with that as its label. */
    private fun lineForElement(key: String, element: JsonElement): SummaryLine {
        val tag = (element as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (tag != null) {
            val rest = element.jsonObject.filterKeys { it != "type" }
            // A `currency` member means `min`, `max` and `amount` beside it are amounts, and this is the
            // screen where the person has to read the limit correctly (see [amountInMinorUnits]). Any
            // other number beside it, such as a count, is not money.
            val currency = (rest["currency"] as? JsonPrimitive)?.contentOrNull
            val amounts = rest.mapValues { (k, v) ->
                if (k !in MONEY_MEMBERS) return@mapValues null
                currency?.let { c -> amountInMinorUnits(tag, v, c)?.let { formatMinorUnits(it, c) } }
            }
            // The currency is folded into the amounts; it is only dropped when one of them shows it.
            val currencyShown = amounts.values.any { it != null }
            // An empty member is shown, as `allowed (none)`: an empty allow-list permits nothing.
            val shown = rest.entries.filter { (k, _) -> !(k == "currency" && currencyShown) }
            val text = shown
                .joinToString(VALUE_SEPARATOR) { (k, v) -> "$k ${amounts[k] ?: renderValue(v)}" }
                .ifEmpty { EMPTY_VALUE }
            return SummaryLine(
                label = prettyLabel(tag),
                value = text,
                shape = if (amounts.values.any { it != null }) Shape.MONEY else shapeOf(text),
            )
        }
        return renderValue(element).let { SummaryLine(prettyLabel(key), it, shapeOf(it)) }
    }

    /**
     * A value is opaque when it carries a long run of identifier characters and no spaces to
     * break it up — a digest, a key, a nonce. Nobody reads one, and at full prominence it pushes
     * the amount off the screen.
     */
    private fun shapeOf(value: String): Shape =
        if (OPAQUE_RUN.containsMatchIn(value)) Shape.OPAQUE else Shape.TEXT

    private val OPAQUE_RUN = Regex("[A-Za-z0-9_-]{20,}")

    /** What separates the values of a line, so a row can be taken apart value by value. */
    private const val VALUE_SEPARATOR = ", "

    /** The member of AP2's closed checkout mandate that carries the checkout, as a JWT. */
    private const val CHECKOUT_JWT = "checkout_jwt"

    /** What an empty list reads as. NOT "any" — an empty allow-list permits nothing. */
    private const val EMPTY_VALUE = "(none)"

    /** The members of an AP2 constraint that hold an amount, beside its `currency`. */
    private val MONEY_MEMBERS = setOf("min", "max", "amount")

    /**
     * An amount in an AP2 constraint, in ISO 4217 minor units, or `null` when [value] is not one.
     *
     * AP2 is not uniform here: `payment.budget` gives `max` as a number that AP2's SDK v0.2.0
     * enforces as `int(max * 100)` minor units in every currency (its schema does not define the
     * unit), while every other amount, such as `payment.amount_range`, is integer minor units
     * ("13000" is $130.00). This follows the SDK, because it is what is enforced: `5000` JPY is
     * 500,000 minor units, so the person is shown ¥500,000, not ¥5,000.
     */
    private fun amountInMinorUnits(constraintType: String, value: JsonElement?, currency: String): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        if (constraintType !in MAJOR_UNIT_CONSTRAINTS) return primitive.longOrNull
        val match = MAJOR_AMOUNT.matchEntire(primitive.content) ?: return null
        // Always two places, whatever the currency's own exponent: that is the SDK's scale. A
        // value with more places, such as "10.005", is not exact and is not rounded for display.
        val fraction = match.groupValues[2].trimEnd('0')
        if (fraction.length > BUDGET_SCALE_DIGITS) return null
        return match.groupValues[1].toLong() * 100 + fraction.padEnd(BUDGET_SCALE_DIGITS, '0').toLong()
    }

    /** The decimal places AP2's SDK scales a `payment.budget` by, in every currency. */
    private const val BUDGET_SCALE_DIGITS = 2

    /** The constraint kinds whose amounts AP2 gives as a decimal number, scaled by 100, not as integer minor units. */
    internal val MAJOR_UNIT_CONSTRAINTS = setOf(BUDGET)

    /** A plain non-negative decimal, short enough that its minor units fit in a [Long]. */
    private val MAJOR_AMOUNT = Regex("""(\d{1,15})(?:\.(\d+))?""")

    /** Minor units to a readable amount. ISO 4217 exponents that are not 2 are listed. */
    private fun formatMinorUnits(minor: Long, currency: String): String =
        "${formatMinorAmount(minor, currency)} $currency"

    /** [formatMinorUnits] without the currency: `25000` USD is `250.00`. */
    private fun formatMinorAmount(minor: Long, currency: String): String {
        val exp = MINOR_UNIT_EXPONENTS[currency.uppercase()] ?: 2
        if (exp == 0) return minor.toString()
        val sign = if (minor < 0) "-" else ""
        val digits = abs(minor).toString().padStart(exp + 1, '0')
        val whole = digits.dropLast(exp)
        return "$sign$whole.${digits.takeLast(exp)}"
    }

    private val MINOR_UNIT_EXPONENTS = mapOf(
        "BIF" to 0, "CLP" to 0, "DJF" to 0, "GNF" to 0, "ISK" to 0, "JPY" to 0, "KMF" to 0,
        "KRW" to 0, "PYG" to 0, "RWF" to 0, "UGX" to 0, "UYI" to 0, "VND" to 0, "VUV" to 0,
        "XAF" to 0, "XOF" to 0, "XPF" to 0,
        "BHD" to 3, "IQD" to 3, "JOD" to 3, "KWD" to 3, "LYD" to 3, "OMR" to 3, "TND" to 3,
        "CLF" to 4, "UYW" to 4,
    )

    /** `checkout.allowed_merchants` → `Checkout allowed merchants`. Punctuation only. */
    private fun prettyLabel(raw: String): String =
        raw.replace('_', ' ').replace('.', ' ').trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun renderValue(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        is JsonArray -> value.joinToString(VALUE_SEPARATOR) { renderValue(it) }.ifEmpty { EMPTY_VALUE }
        is JsonObject ->
            value.entries.joinToString(VALUE_SEPARATOR) { (k, v) -> "$k ${renderValue(v)}" }.ifEmpty { EMPTY_VALUE }
    }

    /**
     * A short, stable excerpt of the `cnf` key (RFC 7800). Not an RFC 7638 thumbprint: that needs
     * a hash, and `Crypto.digest` suspends while this renders in a composable. An excerpt still
     * lets a person tell one agent from another.
     */
    private fun keyExcerpt(cnf: JsonElement): String? {
        val jwk = (cnf as? JsonObject)?.get("jwk") as? JsonObject ?: return null
        val x = (jwk["x"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        if (x.length <= 16) return x
        return "${x.take(8)}…${x.takeLast(8)}"
    }

    /** Whether the mandate's `cnf` is absent or one this screen can show; false if it would be signed unseen. */
    internal fun namesOnlyShownKeys(mandate: JsonObject): Boolean {
        val cnf = mandate["cnf"] ?: return true
        return keyExcerpt(cnf) != null
    }

    /**
     * `21 September 2027, 22:56 UTC`: a date and a time, the one format for every moment shown,
     * in the summary and the details alike. Seconds follow the minutes only when they are not
     * zero, so an expiry is never shown earlier than it is.
     */
    private fun formatMoment(epochSeconds: Long): String {
        val moment = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC)
        val month = moment.month.name.lowercase().replaceFirstChar { it.titlecase() }
        val seconds = if (moment.second == 0) "" else ":${moment.second.toString().padStart(2, '0')}"
        val time = "${moment.hour.toString().padStart(2, '0')}:${moment.minute.toString().padStart(2, '0')}$seconds"
        return "${moment.day} $month ${moment.year}, $time UTC"
    }
}
