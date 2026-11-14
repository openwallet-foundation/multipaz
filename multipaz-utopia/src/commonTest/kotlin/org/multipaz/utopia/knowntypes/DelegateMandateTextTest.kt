package org.multipaz.utopia.knowntypes

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
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
        DelegateMandateText.describePermission(mandates.toList(), TimeZone.UTC)

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
        assertEquals("2026-09-09 22:56:00 UTC", exp.value)
    }

    @Test
    fun anEmptyAllowListSaysSoInsteadOfNothing() {
        // An empty allow-list permits nothing; rendering it as "any" would invert the limit.
        val empty = Json.parseToJsonElement(
            """{"vct":"mandate.checkout.open.1","constraints":[{"type":"checkout.line_items","allowed":[]}]}"""
        ).jsonObject
        val line = DelegateMandateText.summarize(empty)
            .single { it.label.contains("line items", ignoreCase = true) }
        assertEquals("(none)", line.value)
    }

    @Test
    fun zeroDecimalCurrenciesAreNotGivenCents() {
        val yen = Json.parseToJsonElement(
            """{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.budget","currency":"JPY","max":5000}]}"""
        ).jsonObject
        val lines = DelegateMandateText.summarize(yen)
        assertTrue(lines.any { it.value.contains("5000 JPY") }, lines.toString())
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

    @Test
    fun aRangeWithOnlyAFloorSaysAtLeast() {
        val summary = describe(
            mandateOf(
                """{"vct":"mandate.payment.open.1","constraints":[
                     {"type":"payment.amount_range","currency":"USD","min":1000}]}"""
            )
        )
        assertEquals(listOf(DelegateMandateText.Limit("Per purchase, at least", "10.00", "USD")), summary.limits)
    }

    @Test
    fun withoutABudgetThereIsOneLimit() {
        val summary = describe(mandates[1])
        assertEquals(listOf(DelegateMandateText.Limit("Per purchase, up to", "50.00", "USD")), summary.limits)
    }

    @Test
    fun knownConstraintsBecomeShortRows() {
        val rows = describe(checkoutMandate, paymentMandate).rows.associate { it.label to it.value }
        assertEquals("aurora-headphones", rows["For"], rows.toString())
        assertEquals("21 September 2027", rows["Until"], rows.toString())
        assertEquals("Key only · wu7CCOUo…ac733T-o", rows["Agent"], rows.toString())
        // The generic labels are replaced, not repeated.
        assertTrue(rows.keys.none { it.startsWith("Checkout") || it.startsWith("Payment") }, rows.toString())
    }

    // Named by the checkout and the payment mandate alike, the merchant is one fact.
    @Test
    fun theMerchantIsItsNameWithItsDomainUnderneath() {
        val at = describe(checkoutMandate, paymentMandate).rows.filter { it.label == "At" }
        assertEquals(
            listOf(DelegateMandateText.SummaryLine("At", "utopia", detail = "shop.example.com", kind = DelegateMandateText.Kind.MERCHANT)),
            at
        )
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
            DelegateMandateText.SummaryLine("For", "2 × Latte (small) or Latte (large)\naurora-headphones", kind = DelegateMandateText.Kind.ITEMS),
            rows.single()
        )
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
            DelegateMandateText.SummaryLine("Paid to", "Someone else", detail = "else.example", kind = DelegateMandateText.Kind.PAYEE),
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

    // A screen picks a row's icon from its kind, so nothing depends on how the label is worded.
    @Test
    fun eachRowSaysWhatItIsAbout() {
        val rows = describe(
            mandateOf(
                """{"vct":"mandate.checkout.open.1","exp":1821496085,"cnf":$agentCnf,"constraints":[
                     {"type":"checkout.allowed_merchants","allowed":[$merchant]},
                     {"type":"checkout.line_items","items":[
                       {"id":"a","acceptable_items":[{"id":"a","title":"A"}],"quantity":1}]},
                     {"type":"checkout.shipping_region","allowed":["US"]}]}"""
            ),
            mandateOf(
                """{"vct":"mandate.payment.open.1","constraints":[{"type":"payment.allowed_payees","allowed":[
                     {"id":"else.example","name":"Someone else"}]}]}"""
            ),
        ).rows
        assertEquals(
            mapOf(
                "At" to DelegateMandateText.Kind.MERCHANT,
                "Paid to" to DelegateMandateText.Kind.PAYEE,
                "For" to DelegateMandateText.Kind.ITEMS,
                "Until" to DelegateMandateText.Kind.EXPIRY,
                "Agent" to DelegateMandateText.Kind.AGENT,
                "Checkout shipping region" to DelegateMandateText.Kind.OTHER,
            ),
            rows.associate { it.label to it.kind }
        )
    }

    // AP2 requires the signed Mandate Content to be shown, so the details page lists each
    // mandate in full, under a heading in words and its raw type.
    @Test
    fun detailsListEachMandateInFull() {
        val sections = DelegateMandateText.describeMandates(listOf(checkoutMandate, paymentMandate))

        assertEquals(
            listOf(
                DelegateMandateText.MandateSection(
                    heading = "What can be bought",
                    mandateType = "mandate.checkout.open.1",
                    lines = DelegateMandateText.summarize(checkoutMandate),
                ),
                DelegateMandateText.MandateSection(
                    heading = "How much can be spent",
                    mandateType = "mandate.payment.open.1",
                    lines = DelegateMandateText.summarize(paymentMandate),
                ),
            ),
            sections
        )
    }

    // The summary prints the shared merchant, expiry and agent once; the details page must not
    // hide the second mandate's copy of them.
    @Test
    fun detailsKeepWhatTheSummaryDeduplicates() {
        val mandates = listOf(checkoutMandate, paymentMandate)
        val detailLines = DelegateMandateText.describeMandates(mandates).sumOf { it.lines.size }

        assertTrue(detailLines > describe(*mandates.toTypedArray()).details.size)
    }

    @Test
    fun aMandateWithoutATypeIsSaidToBeUnrecognised() {
        val section = DelegateMandateText.describeMandates(listOf(mandateOf("""{"exp":1821496085,"cnf":$agentCnf}"""))).single()

        assertEquals("", section.mandateType)
        assertEquals("A condition your wallet doesn't recognise", section.heading)
    }
}
