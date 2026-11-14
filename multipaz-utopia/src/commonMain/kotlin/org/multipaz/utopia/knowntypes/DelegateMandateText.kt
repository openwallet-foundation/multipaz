package org.multipaz.utopia.knowntypes

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
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
     * @property kind what a summary row is about, so a screen can pick its icon without
     *   reading [label], which is worded for a person and may change.
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
    data class GroupSummary(
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
     * A group of mandates as a person reads them.
     *
     * @property limits the spending limits, in the order they arrived.
     * @property rows short label/value rows: At, For, Until and Agent first, then every
     *   constraint this wallet does not recognise, under the generic label [summarize] gives it.
     * @property details every line [summarizeGroup] produces, opaque ones included, for the
     *   disclosure. Its size is the true field count.
     */
    data class PermissionSummary(
        val limits: List<Limit>,
        val rows: List<SummaryLine>,
        val details: List<SummaryLine>,
    )

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
     * The expiry is shown in the device's time zone.
     */
    fun describePermission(mandates: List<JsonObject>): PermissionSummary =
        describePermission(mandates, TimeZone.currentSystemDefault())

    /** [describePermission] in a given [timeZone]; separate so a test can pin the zone. */
    internal fun describePermission(mandates: List<JsonObject>, timeZone: TimeZone): PermissionSummary {
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
                        until += formatDate(value.long, timeZone)
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
            addAll(other.filter { it.shape != Shape.OPAQUE })
        }
        val group = summarizeGroup(mandates)
        return PermissionSummary(
            limits = limits.toList(),
            rows = rows,
            details = group.money + group.rest + group.opaque,
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
        fun minor(member: String) = (constraint[member] as? JsonPrimitive)?.longOrNull
        return when (type) {
            AMOUNT_RANGE -> {
                val currency = (constraint["currency"] as? JsonPrimitive)?.contentOrNull ?: return false
                val min = minor("min")
                val max = minor("max")
                limits += when {
                    min != null && max != null -> Limit(
                        "Per purchase, between",
                        "${formatMinorAmount(min, currency)} – ${formatMinorAmount(max, currency)}",
                        currency
                    )
                    max != null -> Limit("Per purchase, up to", formatMinorAmount(max, currency), currency)
                    min != null -> Limit("Per purchase, at least", formatMinorAmount(min, currency), currency)
                    else -> return false
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
     */
    private data class Merchant(val key: String, val name: String, val domain: String?)

    /** One row naming [parties]: the name with its domain underneath, or one per line. */
    private fun partiesRow(label: String, kind: Kind, parties: Set<Merchant>): SummaryLine? = when (parties.size) {
        0 -> null
        1 -> parties.single().let { SummaryLine(label, it.name, detail = it.domain, kind = kind) }
        else -> SummaryLine(
            label = label,
            value = parties.joinToString("\n") { m -> m.domain?.let { "${m.name} ($it)" } ?: m.name },
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
        val domain = (member("website") ?: member("origin"))?.let(::hostOf) ?: id
        return Merchant(key = id ?: name, name = name, domain = domain?.takeIf { it != name })
    }

    /** `https://shop.example.com/path` → `shop.example.com`. */
    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')

    /** The members of a `checkout.line_items` requirement, and of an item it accepts. */
    internal val REQUIREMENT_MEMBERS = setOf("id", "acceptable_items", "quantity")
    internal val ITEM_MEMBERS = setOf("id", "title")

    /**
     * One `checkout.line_items` requirement: `quantity` of any one of `acceptable_items`.
     * Reads "2 × Latte (small) or Latte (large)"; the quantity is left out when it is one.
     *
     * @return null when the requirement carries anything this wallet does not understand.
     */
    private fun describeRequirement(requirement: JsonObject): String? {
        if (!requirement.keys.all { it in REQUIREMENT_MEMBERS }) return null
        val titles = (requirement["acceptable_items"] as? JsonArray)?.map { element ->
            val item = element as? JsonObject ?: return null
            if (!item.keys.all { it in ITEM_MEMBERS }) return null
            ((item["title"] ?: item["id"]) as? JsonPrimitive)?.contentOrNull ?: return null
        }?.distinct()?.takeIf { it.isNotEmpty() } ?: return null
        val quantity = requirement["quantity"]?.let { (it as? JsonPrimitive)?.longOrNull ?: return null }
        val what = titles.joinToString(" or ")
        return if (quantity != null && quantity != 1L) "$quantity × $what" else what
    }

    /** [summarize] over several mandates at once, deduplicated and with the amounts separated. */
    fun summarizeGroup(mandates: List<JsonObject>): GroupSummary {
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
     * An empty result means nothing can be shown, and such a mandate must not be signed —
     * [DelegateTransaction.parseOpenId4VpRequest] refuses it.
     */
    fun summarize(mandate: JsonObject): List<SummaryLine> =
        mandate.flatMap { (key, value) -> linesFor(key, value) }

    /** The generic lines for one mandate member. */
    private fun linesFor(key: String, value: JsonElement): List<SummaryLine> {
        val amount = amountOf(value)
        return when {
            key == "vct" -> emptyList() // the heading, rendered separately
            key == "cnf" -> listOfNotNull(keyExcerpt(value)?.let { SummaryLine("Authorized agent key", it, Shape.PARTY) })
            key in INSTANT_CLAIMS && value is JsonPrimitive && value.longOrNull != null ->
                listOf(SummaryLine(INSTANT_CLAIMS.getValue(key), formatInstant(value.long), Shape.INSTANT))
            value is JsonArray -> value.map { element -> lineForElement(key, element) }
            // `types/amount.json` is minor units: 15000 USD is "150.00 USD", not "amount 15000".
            amount != null ->
                listOf(SummaryLine(prettyLabel(key), formatMinorUnits(amount.first, amount.second), Shape.MONEY))
            else -> renderValue(value).let { listOf(SummaryLine(prettyLabel(key), it, shapeOf(it))) }
        }
    }

    /**
     * One mandate as a details page lists it.
     *
     * @property heading what the mandate governs, in words ([headingFor]).
     * @property mandateType the raw mandate type, or blank when the mandate does not say.
     * @property lines every line of the mandate ([summarize]), in the order it arrived.
     */
    data class MandateSection(
        val heading: String,
        val mandateType: String,
        val lines: List<SummaryLine>,
    )

    /**
     * Each mandate on its own, in full, for a details page.
     *
     * [describePermission] deduplicates what the mandates share. A details page must not: AP2
     * requires the signed Mandate Content to be shown, so every mandate is listed as it will be
     * signed, one section each, even when several arrive in one transaction data item.
     */
    fun describeMandates(mandates: List<JsonObject>): List<MandateSection> =
        mandates.map { mandate ->
            val mandateType = mandateType(mandate)
            MandateSection(
                heading = headingFor(mandateType),
                mandateType = mandateType,
                lines = summarize(mandate),
            )
        }

    /** The mandate type, for the heading above its lines. Blank when the mandate does not say. */
    fun mandateType(mandate: JsonObject): String =
        (mandate["vct"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * A heading for a mandate type, in words.
     *
     * A type identifier is a machine name and means nothing to a person. An unrecognised one is
     * said to be unrecognised rather than guessed at — the raw identifier is still printed, so a
     * wallet never claims to understand a condition it has not seen.
     */
    fun headingFor(mandateType: String): String =
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
            // A `currency` member means the members beside it are amounts, and this is the screen
            // where the person has to read the limit correctly (see [amountInMinorUnits]).
            val currency = (rest["currency"] as? JsonPrimitive)?.contentOrNull
            val amounts = rest.mapValues { (k, v) ->
                if (k == "currency") return@mapValues null
                currency?.let { c -> amountInMinorUnits(tag, v, c)?.let { formatMinorUnits(it, c) } }
            }
            // The currency is folded into the amounts; it is only dropped when one of them shows it.
            val currencyShown = amounts.values.any { it != null }
            val shown = rest.entries
                .filter { (k, v) -> !isEmptyCollection(v) && !(k == "currency" && currencyShown) }
            val text = shown
                .joinToString(", ") { (k, v) -> "$k ${amounts[k] ?: renderValue(v)}" }
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

    /** What an empty list reads as. NOT "any" — an empty allow-list permits nothing. */
    private const val EMPTY_VALUE = "(none)"

    private fun isEmptyCollection(value: JsonElement): Boolean =
        (value is JsonArray && value.isEmpty()) || (value is JsonObject && value.isEmpty())

    /**
     * An amount in an AP2 constraint, in ISO 4217 minor units, or `null` when [value] is not one.
     *
     * AP2 is not uniform here: `payment.budget` gives `max` in major units (its schema types it
     * as a number, and AP2's own SDK multiplies it by 100), while every other amount, such as
     * `payment.amount_range`, is integer minor units ("13000" is $130.00).
     */
    private fun amountInMinorUnits(constraintType: String, value: JsonElement?, currency: String): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (constraintType !in MAJOR_UNIT_CONSTRAINTS) return primitive.longOrNull
        if (primitive.isString) return null
        val match = MAJOR_AMOUNT.matchEntire(primitive.content) ?: return null
        val exp = MINOR_UNIT_EXPONENTS[currency.uppercase()] ?: 2
        // "1000.00" in JPY is still exact; "10.005" in USD is not, and is not rounded for display.
        val fraction = match.groupValues[2].trimEnd('0')
        if (fraction.length > exp) return null
        var minor = match.groupValues[1].toLong()
        repeat(exp) { minor *= 10 }
        return minor + fraction.padEnd(exp, '0').ifEmpty { "0" }.toLong()
    }

    /** The constraint kinds whose amounts AP2 gives in major units (a JSON number, not an integer). */
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
     * A short, stable excerpt of the `cnf` key (RFC 7800). Not an RFC 7638 thumbprint: that needs
     * a hash, and `Crypto.digest` suspends while this renders in a composable. An excerpt still
     * lets a person tell one agent from another.
     */
    private fun keyExcerpt(cnf: JsonElement): String? {
        val jwk = (cnf as? JsonObject)?.get("jwk") as? JsonObject ?: return null
        val x = (jwk["x"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (x.length <= 16) return x
        return "${x.take(8)}…${x.takeLast(8)}"
    }

    /** `2026-09-09 22:56:00 UTC`. Whole seconds, so ISO 8601 has no fraction, only the `Z`. */
    private fun formatInstant(epochSeconds: Long): String =
        Instant.fromEpochSeconds(epochSeconds).toString().removeSuffix("Z").replace('T', ' ') + " UTC"

    /** `21 September 2027`: a date a person reads at a glance, in their own time zone. */
    private fun formatDate(epochSeconds: Long, timeZone: TimeZone): String {
        val date = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(timeZone).date
        val month = date.month.name.lowercase().replaceFirstChar { it.titlecase() }
        return "${date.day} $month ${date.year}"
    }
}
