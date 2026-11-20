package org.multipaz.utopia.knowntypes

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How [DelegateMandateText] puts AP2 mandates into words for the consent screen. */
class DelegateMandateTextTest {

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

    // The permission as a person reads it: limits in words, known constraints as short rows.

    private fun mandateOf(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private val agentCnf =
        """{"jwk":{"kty":"EC","crv":"P-256","x":"wu7CCOUoAAAAAAAAAAAAAAAAac733T-o","y":"y"}}"""

    private val merchant =
        """{"id":"shop.example.com","name":"utopia","origin":"https://shop.example.com"}"""

    private val checkoutMandate = mandateOf(
        """{"vct":"mandate.checkout.open.1","exp":1821496085,"cnf":$agentCnf,"constraints":[
             {"type":"checkout.allowed_merchants","allowed":[$merchant]},
             {"type":"checkout.line_items","items":[
               {"id":"aurora-headphones","acceptable_items":[{"id":"aurora-headphones","title":"aurora-headphones"}],"quantity":1}]}]}"""
    )

    private val paymentMandate = mandateOf(
        """{"vct":"mandate.payment.open.1","exp":1821496085,"cnf":$agentCnf,"constraints":[
             {"type":"payment.amount_range","currency":"USD","max":25000},
             {"type":"payment.budget","currency":"USD","max":400.00},
             {"type":"payment.reference","conditional_transaction_id":"2OM1tHKyC3wyg3ukVtXfEbBsI711JT50IreFRIvzOyY"},
             {"type":"payment.allowed_payees","allowed":[$merchant]}]}"""
    )

    private fun describe(vararg mandates: JsonObject) =
        DelegateMandateText.describePermission(mandates.toList())

    // AP2: "The User is shown the Mandate Content on a Trusted Surface."

    @Test
    fun theMandateIsReadableOnScreen() {
        val checkout = DelegateMandateText.summarize(mandates[0]).joinToString(" | ") { "${it.label}: ${it.value}" }
        val payment = DelegateMandateText.summarize(mandates[1]).joinToString(" | ") { "${it.label}: ${it.value}" }

        // Limits, scope and the agent key — all rendered without knowing AP2's mandate types.
        assertTrue(checkout.contains("oak-whiskey"), checkout)
        // Readable money, not minor units: 5000 is $50.00.
        assertTrue(payment.contains("50.00 USD"), payment)
        assertTrue(!payment.contains("5000"), payment)
        assertTrue(checkout.contains("Authorized agent key"), checkout)
        assertTrue(payment.contains("Authorized agent key"), payment)
        // An excerpt at least distinguishes one agent from another.
        assertTrue(checkout.contains("agent-x"), checkout)
    }

    // Several mandates signed together are one permission. Before they were grouped, the same
    // merchant printed once per mandate and the heading repeated with it.
    @Test
    fun aGroupOfMandatesDeduplicatesWhatTheyShare() {
        val group = DelegateMandateText.summarizeGroup(listOf(mandates[0], mandates[1]))
        val rendered = (group.money + group.rest).map { "${it.label}: ${it.value}" }

        assertEquals(rendered.size, rendered.distinct().size, rendered.toString())
        // The agent key is named by both mandates and appears once.
        assertEquals(1, rendered.count { it.startsWith("Authorized agent key") }, rendered.toString())
        // …and nothing was lost: both mandates' own constraints are still there.
        assertTrue(rendered.any { it.contains("oak-whiskey") }, rendered.toString())
        assertTrue(rendered.any { it.contains("50.00 USD") }, rendered.toString())
    }

    // The amounts are the limit a person has to read correctly, so the screen can give them room.
    @Test
    fun amountsAreSeparatedFromTheRest() {
        val group = DelegateMandateText.summarizeGroup(listOf(mandates[1]))
        assertEquals(1, group.money.size, group.money.toString())
        assertTrue(group.money.single().value.contains("50.00 USD"), group.money.toString())
        assertTrue(group.rest.none { it.shape == DelegateMandateText.Shape.MONEY }, group.rest.toString())
        assertEquals(group.size, group.money.size + group.rest.size + group.opaque.size)
    }

    // A 44-character digest changes nobody's mind, and at full prominence it pushes the
    // amount off the screen. `payment.reference` must classify as opaque and stay expanded-only.
    @Test
    fun aDigestIsNotPromoted() {
        val withRef = Json.parseToJsonElement(
            """{"vct":"mandate.payment.open.1","constraints":[
                 {"type":"payment.reference","conditional_transaction_id":"2OM1tHKyC3wyg3ukVtXfEbBsI711JT50IreFRIvzOyY"},
                 {"type":"payment.budget","currency":"USD","max":50.00}]}"""
        ).jsonObject
        val group = DelegateMandateText.summarizeGroup(
            listOf(withRef)
        )
        assertTrue(group.opaque.any { it.label.contains("reference", ignoreCase = true) }, group.toString())
        assertTrue(group.rest.none { it.label.contains("reference", ignoreCase = true) }, group.rest.toString())
        // …while the amount it was competing with is promoted.
        assertTrue(group.money.single().value.contains("50.00 USD"), group.money.toString())
    }

    // The agent key is opaque too, but every mandate names the same one — it is the party the
    // permission binds to, so it keeps a summary row where a one-off digest does not.
    @Test
    fun theAgentKeyIsKeptInTheSummary() {
        val group = DelegateMandateText.summarizeGroup(listOf(mandates[0], mandates[1]))
        assertTrue(group.rest.any { it.label == "Authorized agent key" }, group.rest.toString())
        assertTrue(group.opaque.none { it.label == "Authorized agent key" }, group.opaque.toString())
    }

    @Test
    fun theMandateTypeIsTheHeading() {
        assertEquals("mandate.checkout.open.1", DelegateMandateText.mandateType(mandates[0]))
        assertEquals("", DelegateMandateText.mandateType(Json.parseToJsonElement("{}").jsonObject))
    }

    // A type identifier is a machine name. An unrecognised one must say so, not be guessed at.
    @Test
    fun headingsAreWordsAndUnknownTypesAdmitIt() {
        assertEquals("What can be bought", DelegateMandateText.headingFor("mandate.checkout.open.1"))
        assertEquals("How much can be spent", DelegateMandateText.headingFor("mandate.payment.open.1"))
        assertTrue(DelegateMandateText.headingFor("mandate.something.new.7").contains("doesn't recognise"))
    }

    @Test
    fun expiryIsShownAsADateNotEpochSeconds() {
        val withExp = Json.parseToJsonElement("""{"vct":"mandate.payment.open.1","exp":1788994560}""").jsonObject
        val lines = DelegateMandateText.summarize(withExp)
        val exp = lines.single { it.label == "Valid until" }
        assertEquals("9 September 2026, 22:56 UTC", exp.value)
    }

    @Test
    fun anEmptyAllowListSaysSoInsteadOfNothing() {
        // An empty allow-list permits nothing; rendering it as "any" would invert the limit.
        val empty = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.allowed_merchants","allowed":[]}]}"""
        ).jsonObject
        val line = DelegateMandateText.summarize(empty)
            .single { it.label.contains("allowed merchants", ignoreCase = true) }
        assertEquals("allowed (none)", line.value)
    }

    // Beside other members an empty one is still said: an empty allow-list permits nothing. And only
    // `min`, `max` and `amount` are money next to a `currency`; a count is not.
    @Test
    fun anEmptyMemberIsSaidBesideOthersAndOnlyAmountsAreMoney() {
        val mandate = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[
                 {"type":"payment.custom","allowed":[],"currency":"USD","max":100,"max_uses":3}]}"""
        )
        val line = DelegateMandateText.summarize(mandate).single { it.label == "Payment custom" }
        assertTrue("allowed (none)" in line.value, line.value)
        assertTrue("max 1.00 USD" in line.value, line.value)
        assertTrue("max_uses 3" in line.value, line.value)
        assertTrue("0.03" !in line.value, line.value)
    }

    // ISO 4217 has currencies with four decimals; showing them with two would read 100 times too large.
    @Test
    fun aFourDecimalCurrencyIsShownWithFourDecimals() {
        val mandate = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.amount_range","currency":"CLF","max":123456}]}"""
        )
        assertEquals(listOf(DelegateMandateText.Limit("Per purchase, up to", "12.3456", "CLF")), describe(mandate).limits)
    }

    @Test
    fun zeroDecimalCurrenciesAreNotGivenCents() {
        val yen = Json.parseToJsonElement(
            """{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.amount_range","currency":"JPY","max":5000}]}"""
        ).jsonObject
        val lines = DelegateMandateText.summarize(yen)
        assertTrue(lines.any { it.value.contains("5000 JPY") }, lines.toString())
    }

    // AP2's SDK enforces a budget as int(max * 100) minor units in every currency (constraints.py), so
    // a JPY budget of 5000 allows 500,000 yen. Reading it with yen's own exponent would show 5000.
    @Test
    fun aBudgetIsScaledByAHundredInEveryCurrencyLikeAp2Enforces() {
        val yen = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.budget","currency":"JPY","max":5000}]}"""
        )
        assertEquals(listOf(DelegateMandateText.Limit("In total, up to", "500000", "JPY")), describe(yen).limits)
    }

    @Test
    fun theTwoLimitsSayWhichIsPerPurchaseAndWhichIsTheTotal() {
        val summary = describe(checkoutMandate, paymentMandate)
        assertEquals(
            listOf(
                DelegateMandateText.Limit("Per purchase, up to", "250.00", "USD"),
                DelegateMandateText.Limit("In total, up to", "400.00", "USD"),
            ),
            summary.limits
        )
    }

    // AP2 gives the budget in major units ("1000.00" is USD 1,000), unlike the per-purchase range.
    // Read as minor units it would show a limit 100 times smaller than the one being signed.
    @Test
    fun theBudgetIsInMajorUnits() {
        val budget = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[
                 {"type":"payment.budget","currency":"USD","max":1000.00}]}"""
        )
        assertEquals(listOf(DelegateMandateText.Limit("In total, up to", "1000.00", "USD")), describe(budget).limits)
        val details = DelegateMandateText.summarize(budget).single { it.label == "Payment budget" }
        assertEquals("max 1000.00 USD", details.value)
        assertEquals(DelegateMandateText.Shape.MONEY, details.shape)
    }

    @Test
    fun aWholeNumberBudgetIsStillMajorUnits() {
        val budget = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[
                 {"type":"payment.budget","currency":"USD","max":200}]}"""
        )
        assertEquals(listOf(DelegateMandateText.Limit("In total, up to", "200.00", "USD")), describe(budget).limits)
    }

    // A budget the currency cannot express exactly is shown as it was sent, not rounded.
    @Test
    fun aBudgetWithTooManyDecimalsIsNotRounded() {
        val budget = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[
                 {"type":"payment.budget","currency":"USD","max":10.005}]}"""
        )
        assertTrue(describe(budget).limits.isEmpty())
        val line = DelegateMandateText.summarize(budget).single { it.label == "Payment budget" }
        assertEquals("currency USD, max 10.005", line.value)
    }

    @Test
    fun aRangeWithAFloorSaysBetween() {
        val summary = describe(
            mandateOf(
                """{"vct":"mandate.payment.open.1","constraints":[
                     {"type":"payment.amount_range","currency":"USD","min":1000,"max":25000}]}"""
            )
        )
        assertEquals(listOf(DelegateMandateText.Limit("Per purchase, between", "10.00 – 250.00", "USD")), summary.limits)
    }

    // AP2's schema requires `max`. A range without it is not one this wallet understands, so it
    // is shown as it was sent rather than as a floor that reads like the whole permission.
    @Test
    fun aRangeWithoutACapIsNotUnderstood() {
        val summary = describe(
            mandateOf(
                """{"vct":"mandate.payment.open.1","constraints":[
                     {"type":"payment.amount_range","currency":"USD","min":1000}]}"""
            )
        )
        assertTrue(summary.limits.isEmpty(), summary.limits.toString())
        assertTrue(summary.rows.isNotEmpty(), "the constraint is still shown, generically")
    }

    // A bound that is there but not a number must not be dropped from the sentence: {"min":100,
    // "max":"abc"} would otherwise read "at least 1.00", with the cap gone.
    @Test
    fun aBoundThatIsNotANumberIsNotUnderstood() {
        for (bounds in listOf(
            """"min":100,"max":"abc"""",
            """"min":"abc","max":25000""",
            """"max":"25000"""",       // a number in quotes is a string
            """"min":"1000","max":25000""",
            """"max":null""",
        )) {
            val summary = describe(
                mandateOf(
                    """{"vct":"mandate.payment.open.1","constraints":[
                         {"type":"payment.amount_range","currency":"USD",$bounds}]}"""
                )
            )
            assertTrue(summary.limits.isEmpty(), "$bounds: ${summary.limits}")
        }
    }

    @Test
    fun withoutABudgetThereIsOneLimit() {
        val summary = describe(mandates[1])
        assertEquals(listOf(DelegateMandateText.Limit("Per purchase, up to", "50.00", "USD")), summary.limits)
    }

    @Test
    fun knownConstraintsBecomeShortRows() {
        val rows = describe(checkoutMandate, paymentMandate).rows.associate { it.label to it.value }
        assertEquals("Up to 1 × aurora-headphones", rows["For"], rows.toString())
        assertEquals("21 September 2027, 03:08:05 UTC", rows["Until"], rows.toString())
        assertEquals("Key only · wu7CCOUo…ac733T-o", rows["Agent"], rows.toString())
        // The generic labels are replaced, not repeated.
        assertTrue(rows.keys.none { it.startsWith("Checkout") || it.startsWith("Payment") }, rows.toString())
    }

    // Named by the checkout and the payment mandate alike, the merchant is one fact.
    @Test
    fun theMerchantIsItsNameWithItsDomainUnderneath() {
        val at = describe(checkoutMandate, paymentMandate).rows.filter { it.label == "At" }
        assertEquals(listOf(DelegateMandateText.SummaryLine("At", "utopia", detail = "shop.example.com", kind = DelegateMandateText.Kind.MERCHANT)), at)
    }

    // The requester is not assumed to be the merchant.
    @Test
    fun noMerchantConstraintMeansNoAtRow() {
        assertTrue(describe(mandates[1]).rows.none { it.label == "At" })
    }

    @Test
    fun severalMerchantsAreEachNamedWithTheirDomain() {
        val rows = describe(
            mandateOf(
                """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.allowed_merchants","allowed":[
                     $merchant,{"id":"other.example","name":"Other","website":"https://other.example/shop"}]}]}"""
            )
        ).rows
        assertEquals(
            DelegateMandateText.SummaryLine("At", "utopia (shop.example.com)\nOther (other.example)", kind = DelegateMandateText.Kind.MERCHANT),
            rows.single()
        )
    }

    // A requirement names the items that satisfy it and how many; the id and a title equal to it
    // are one thing, not three.
    @Test
    fun lineItemsReadAsWhatCanBeBought() {
        val rows = describe(
            mandateOf(
                """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.line_items","items":[
                     {"id":"r1","acceptable_items":[{"id":"latte-s","title":"Latte (small)"},{"id":"latte-l","title":"Latte (large)"}],"quantity":2},
                     {"id":"r2","acceptable_items":[{"id":"aurora-headphones","title":"aurora-headphones"}],"quantity":1}]}]}"""
            )
        ).rows
        assertEquals(
            DelegateMandateText.SummaryLine(
                "For",
                "Up to 2 × Latte (small) · ID latte-s or Latte (large) · ID latte-l\nUp to 1 × aurora-headphones",
                kind = DelegateMandateText.Kind.ITEMS,
            ),
            rows.single()
        )
    }

    private fun itemsRow(requirements: String) = describe(
        mandateOf(
            """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.line_items","items":[$requirements]}]}"""
        )
    ).rows.single { it.label == "For" }

    // AP2's verifier treats an empty `acceptable_items` as a wildcard (test_line_items_wildcard_requirement):
    // the agent may buy up to ten of anything, so the screen must not say nothing may be bought.
    @Test
    fun anEmptyAcceptableItemsIsAWildcard() {
        val row = itemsRow("""{"id":"wild","acceptable_items":[],"quantity":10}""")
        assertEquals("Up to 10 × any item", row.value)
        assertEquals(DelegateMandateText.Kind.ITEMS, row.kind)
    }

    // AP2 matches items on `id` alone, so a title cannot stand for it: the id is shown beside the title.
    @Test
    fun anItemsIdIsShownWhenItDiffersFromItsTitle() {
        val row = itemsRow(
            """{"id":"r1","acceptable_items":[
                 {"id":"supershoe_limited_edition_gold_sneaker_womens_9_0","title":"Latte"}],"quantity":1}"""
        )
        assertEquals("Up to 1 × Latte · ID supershoe_limited_edition_gold_sneaker_womens_9_0", row.value)
    }

    // Items are the same only when their ids are: two ids that share a title are two items.
    @Test
    fun itemsAreMergedOnIdNotOnTitle() {
        val row = itemsRow(
            """{"id":"r1","acceptable_items":[
                 {"id":"sku-1","title":"Latte"},{"id":"sku-2","title":"Latte"},{"id":"sku-1","title":"Latte"}],"quantity":3}"""
        )
        assertEquals("Up to 3 × Latte · ID sku-1 or Latte · ID sku-2", row.value)
    }

    // A shape this wallet does not fully understand is not summarised away.
    @Test
    fun aLineItemWithAnUnknownMemberFallsBackToTheGenericRow() {
        val rows = describe(
            mandateOf(
                """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.line_items","items":[
                     {"id":"r1","acceptable_items":[{"id":"a","title":"A"}],"quantity":1,"max_price":100}]}]}"""
            )
        ).rows
        assertTrue(rows.none { it.label == "For" }, rows.toString())
        assertEquals("Checkout line items", rows.single().label, rows.toString())
    }

    @Test
    fun rowsLeadWithAtThenForThenUntilThenAgent() {
        val rows = describe(
            mandateOf(
                """{"vct":"mandate.checkout.open.1","exp":1821496085,"cnf":$agentCnf,"constraints":[
                     {"type":"checkout.line_items","items":[
                       {"id":"a","acceptable_items":[{"id":"a","title":"A"}],"quantity":1}]},
                     {"type":"checkout.allowed_merchants","allowed":[$merchant]}]}"""
            )
        ).rows
        assertEquals(listOf("At", "For", "Until", "Agent"), rows.map { it.label })
        // A screen picks icons by kind, so the wording above can change without breaking it.
        assertEquals(
            listOf(DelegateMandateText.Kind.MERCHANT, DelegateMandateText.Kind.ITEMS, DelegateMandateText.Kind.EXPIRY, DelegateMandateText.Kind.AGENT),
            rows.map { it.kind },
        )
    }

    // Every row without a fixed meaning is OTHER, so a screen never mistakes it for one that has.
    @Test
    fun aRowOfNoFixedMeaningIsOther() {
        val rows = describe(
            mandateOf("""{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.shipping_region","allowed":["US"]}]}""")
        ).rows
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.kind == DelegateMandateText.Kind.OTHER }, rows.toString())
    }

    // A constraint nobody has seen is still shown, with the generic label it had before.
    @Test
    fun anUnknownConstraintStillGetsARow() {
        val rows = describe(
            mandateOf(
                """{"vct":"mandate.checkout.open.1","constraints":[
                     {"type":"checkout.shipping_region","allowed":["US"]}]}"""
            )
        ).rows
        assertEquals(DelegateMandateText.SummaryLine("Checkout shipping region", "allowed US"), rows.single())
    }

    // "Show all N fields" must keep counting everything the signature covers.
    @Test
    fun detailsHoldEveryLineAndOpaqueOnesOnlyThere() {
        val summary = describe(checkoutMandate, paymentMandate)
        val group = DelegateMandateText.summarizeGroup(listOf(checkoutMandate, paymentMandate))
        assertEquals(group.size, summary.details.size)
        assertTrue(summary.details.any { it.label.contains("reference", ignoreCase = true) }, summary.details.toString())
        assertTrue(summary.rows.none { it.label.contains("reference", ignoreCase = true) }, summary.rows.toString())
    }

    // Who is paid is a separate fact from where the agent may shop. Deduplicating them by id
    // alone let a payee with a different name hide behind the merchant.
    @Test
    fun aPayeeOtherThanTheMerchantIsShown() {
        val rows = describe(
            mandateOf("""{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.allowed_merchants","allowed":[$merchant]}]}"""),
            mandateOf("""{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.allowed_payees","allowed":[
                 {"id":"shop.example.com","name":"Someone else","website":"https://else.example"}]}]}"""),
        ).rows
        assertEquals(
            DelegateMandateText.SummaryLine("At", "utopia", detail = "shop.example.com", kind = DelegateMandateText.Kind.MERCHANT),
            rows.single { it.label == "At" }
        )
        assertEquals(
            // Same id as the merchant, which is what AP2 compares: shown, so it cannot pass as another.
            DelegateMandateText.SummaryLine(
                "Paid to", "Someone else", detail = "else.example · ID shop.example.com", kind = DelegateMandateText.Kind.PAYEE
            ),
            rows.single { it.label == "Paid to" }
        )
    }

    @Test
    fun aPayeeThatIsTheMerchantIsNotRepeated() {
        assertTrue(describe(checkoutMandate, paymentMandate).rows.none { it.label == "Paid to" })
    }

    // A closed payment mandate states the exact amount, in minor units (`types/amount.json`).
    @Test
    fun aClosedMandateShowsItsAmountAndPayee() {
        val closed = mandateOf(
            """{"vct":"mandate.payment.1","transaction_id":"tx-1","payment_amount":{"amount":15000,"currency":"USD"},"payee":$merchant}"""
        )
        val summary = describe(closed)
        assertEquals(listOf(DelegateMandateText.Limit("Amount", "150.00", "USD")), summary.limits)
        assertEquals("utopia", summary.rows.single { it.label == "Paid to" }.value)
        assertTrue(
            DelegateMandateText.summarize(closed)
                .contains(DelegateMandateText.SummaryLine("Payment amount", "150.00 USD", DelegateMandateText.Shape.MONEY))
        )
    }

    /** A merchant-signed JWT; only its payload is read, so the header and signature are filler. */
    private fun checkoutJwt(payload: String) =
        "eyJhbGciOiJFUzI1NiJ9." + payload.encodeToByteArray().toBase64Url() + ".c2lnbmF0dXJl"

    private fun closedCheckout(checkoutJwt: String) = mandateOf(
        """{"vct":"mandate.checkout.1","checkout_jwt":"$checkoutJwt","checkout_hash":"3WiKMabE8NRYJgveUbyAZ3pBqRfPrWwGDbOyvbO1eYA"}"""
    )

    // AP2's closed checkout is a `checkout_jwt` and its hash: both are long identifiers. Showing
    // the JWT's payload is what lets a person read the cart they are signing, with its amounts as
    // the UCP checkout gives them: integer minor units, and the currency at the top.
    @Test
    fun aClosedCheckoutShowsTheCheckoutWithItsAmountsAsMoney() {
        val closed = closedCheckout(checkoutJwt(UCP_CHECKOUT_PAYLOAD))
        assertTrue(DelegateMandateText.hasReadableLine(closed))
        val lines = DelegateMandateText.summarize(closed)
        // The total leads, and 4200 in USD is 42.00, not 4,200.
        assertEquals(
            DelegateMandateText.SummaryLine("Total", "42.00 USD", DelegateMandateText.Shape.MONEY),
            lines.first { it.label == "Total" },
        )
        assertEquals("Total", lines.first { it.shape == DelegateMandateText.Shape.MONEY }.label)
        assertTrue(lines.any { it.label == "Subtotal" && it.value == "42.00 USD" }, lines.toString())
        assertTrue(
            lines.any { it.label == "Item" && it.value == "1 × Aurora headphones · ID aurora · 42.00 USD each" },
            lines.toString()
        )
        assertTrue(lines.any { it.label == "Item total" && it.value == "42.00 USD" }, lines.toString())
        // No raw `amount 4200` is left beside a currency it is not formatted with.
        assertTrue(lines.none { "4200" in it.value }, lines.toString())
    }

    @Test
    fun aCheckoutsAmountsFollowItsCurrencysDecimals() {
        val yen = checkoutJwt(UCP_CHECKOUT_PAYLOAD.replace("USD", "JPY"))
        val lines = DelegateMandateText.summarize(closedCheckout(yen))
        assertEquals("4200 JPY", lines.first { it.label == "Total" }.value)
    }

    // Without a currency the amounts cannot be formatted, so they are shown as they came rather than guessed.
    @Test
    fun aCheckoutWithoutACurrencyKeepsItsAmountsRaw() {
        val noCurrency = checkoutJwt(UCP_CHECKOUT_PAYLOAD.replace(""""currency": "USD",""", ""))
        val lines = DelegateMandateText.summarize(closedCheckout(noCurrency))
        assertTrue(lines.none { it.value.endsWith("USD") && it.label == "Total" }, lines.toString())
        assertTrue(lines.any { "4200" in it.value }, lines.toString())
    }

    // A `checkout_jwt` that is not a JWT stays opaque, and a mandate of only opaque lines is not shown.
    @Test
    fun aClosedCheckoutThatCannotBeDecodedHasNothingReadable() {
        assertFalse(DelegateMandateText.hasReadableLine(closedCheckout("a".repeat(40))))
        assertFalse(
            DelegateMandateText.hasReadableLine(closedCheckout("a".repeat(30) + ".!!!." + "b".repeat(30)))
        )
    }

    // AP2's example mandate lasts an hour: that must not read as a bare date, and the summary and the
    // details must say the same thing about the same instant.
    @Test
    fun theSummaryAndTheDetailsShowTheSameMomentWithItsTime() {
        val hourLong = mandateOf("""{"vct":"mandate.payment.open.1","exp":1788998160,"payment_amount":{"amount":100,"currency":"USD"}}""")
        val summary = describe(hourLong)
        val summaryUntil = summary.rows.single { it.kind == DelegateMandateText.Kind.EXPIRY }.value
        val detailsUntil = summary.details.single { it.label == "Valid until" }.value
        assertEquals("9 September 2026, 23:56 UTC", summaryUntil)
        assertEquals(summaryUntil, detailsUntil)
    }

    // Each value is judged alone. One identifier in a row must not take the readable description
    // beside it down with it, which is how AP2's allowed payment instruments went missing.
    @Test
    fun anIdentifierDoesNotHideTheReadableValuesBesideIt() {
        val instruments = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.allowed_payment_instruments","allowed":[
                 {"id":"instrument-abcdefghij1234567890","type":"card","description":"Visa ending in 1234"}]}]}"""
        )
        val summary = describe(instruments)
        val row = summary.rows.single()
        assertTrue("Visa ending in 1234" in row.value, row.toString())
        assertTrue("instrument-abcdefghij1234567890" !in row.value, row.toString())
        assertEquals(1, summary.hiddenCount)
        // The details still list the value that was left out of the row.
        assertTrue(summary.details.any { "instrument-abcdefghij1234567890" in it.value }, summary.details.toString())
    }

    // A row of nothing readable is left out, and said to be: the count is how a screen can tell.
    @Test
    fun aRowOfOnlyIdentifiersIsCountedNotSilentlyDropped() {
        val reference = mandateOf(
            """{"vct":"mandate.payment.open.1","constraints":[
                 {"type":"payment.reference","conditional_transaction_id":"2OM1tHKyC3wyg3ukVtXfEbBsI711JT50IreFRIvzOyY"}]}"""
        )
        val summary = describe(reference)
        assertTrue(summary.rows.isEmpty(), summary.rows.toString())
        assertEquals(1, summary.hiddenCount)
    }

    @Test
    fun nothingIsHiddenWhenEverythingCanBeRead() {
        assertEquals(0, describe(mandates[0]).hiddenCount)
    }

    private fun partyRow(party: String, constraint: String = "checkout.allowed_merchants", label: String = "At") =
        describe(
            mandateOf("""{"vct":"mandate.checkout.open.1","constraints":[{"type":"$constraint","allowed":[$party]}]}""")
        ).rows.single { it.label == label }

    // AP2 matches merchants on `id` alone, and the schema requires it: of the three fields it is the
    // one that is enforced, so it must be on the screen. This is AP2's own example.
    @Test
    fun theMerchantsIdIsShownWhenItAddsSomething() {
        val row = partyRow("""{"id":"merchant_1","name":"Demo Merchant","website":"https://demo-merchant.example"}""")
        assertEquals("Demo Merchant", row.value)
        assertEquals("demo-merchant.example · ID merchant_1", row.detail)
    }

    // `https://shop.example@evil.example/` goes to evil.example; the part before `@` is userinfo.
    @Test
    fun theHostIsWhatFollowsTheUserinfo() {
        val row = partyRow("""{"id":"m_1","name":"Shop","website":"https://shop.example@evil.example/path"}""")
        assertEquals("evil.example · ID m_1", row.detail)
    }

    @Test
    fun detailsGatherTheHeadingTheTypeAndTheLines() {
        val details = DelegateMandateText.detailsOf(mandates[0])
        assertEquals("mandate.checkout.open.1", details.type)
        assertEquals("What can be bought", details.heading)
        assertEquals(DelegateMandateText.summarize(mandates[0]), details.lines)

        val unknown = DelegateMandateText.detailsOf(mandateOf("""{"vct":"mandate.something.new.7","note":"x"}"""))
        assertEquals("mandate.something.new.7", unknown.type)
        assertTrue(unknown.heading.contains("doesn't recognise"))
    }

    // A details page asks for the sections, not the mandates: the terms behind digests are resolved
    // for it, and each mandate is listed in full.
    @Test
    fun theDetailsComeStraightFromTheTransactionData() = runTest {
        val json = """{"type":"delegate","format":"dc+sd-jwt","credential_ids":["c"],""" +
            """"delegate_payload":[$checkoutMandate,$paymentMandate]}"""
        val data = listOf(DelegateTransaction.parseJson(json.encodeToByteArray().toBase64Url().encodeToByteString()))

        val sections = DelegateMandateText.mandateDetails(data)

        assertEquals(DelegateTransaction.shownMandates(data).map(DelegateMandateText::detailsOf), sections)
        assertEquals(listOf("What can be bought", "How much can be spent"), sections.map { it.heading })
        assertEquals(describe(checkoutMandate, paymentMandate), DelegateMandateText.permissionSummary(data))
    }

    @Test
    fun theIdIsNotRepeatedWhenTheNameOrTheHostAlreadySaysIt() {
        // Same as the host, which is what AP2's example data does.
        assertEquals("shop.example.com", partyRow(merchant).detail)
        // Same as the name.
        assertEquals(null, partyRow("""{"id":"utopia","name":"utopia"}""").detail)
        assertEquals(null, partyRow("""{"id":"utopia"}""").detail)
    }

    @Test
    fun anIdWithNoWebsiteIsStillShown() {
        assertEquals("ID merchant_1", partyRow("""{"id":"merchant_1","name":"Demo Merchant"}""").detail)
    }

    @Test
    fun thePayeesIdIsShownToo() {
        val row = partyRow(
            """{"id":"payee_9","name":"Payee Inc","website":"https://payee.example"}""",
            constraint = "payment.allowed_payees",
            label = "Paid to",
        )
        assertEquals("payee.example · ID payee_9", row.detail)
    }

    // Several parties are one per line, each with what identifies it.
    @Test
    fun severalPartiesAreEachNamedWithTheirId() {
        val row = partyRow(
            """{"id":"a_1","name":"A","website":"https://a.example"},{"id":"b_2","name":"B"}"""
        )
        assertEquals("A (a.example · ID a_1)\nB (ID b_2)", row.value)
    }
}
