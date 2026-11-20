package org.multipaz.utopia.knowntypes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks what [DelegateMandateText] recognises against AP2's own JSON Schemas (v0.2.0, in
 * `resources/ap2-schemas`), so the constraint kinds, members, units and mandate types it matches
 * by name cannot drift from the protocol unnoticed.
 */
class DelegateMandateSchemaTest {

    private fun schema(path: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/ap2-schemas/$path")!!.readText()).jsonObject

    private val JsonObject.properties: JsonObject get() = this["properties"]!!.jsonObject

    private fun JsonObject.typeOf(member: String): String? =
        properties[member]?.jsonObject?.get("type")?.jsonPrimitive?.content

    private fun JsonObject.def(name: String): JsonObject = this["\$defs"]!!.jsonObject[name]!!.jsonObject

    private val openMandates = listOf("open_checkout_mandate.json", "open_payment_mandate.json")
    private val mandates = openMandates + listOf("checkout_mandate.json", "payment_mandate.json")

    /** Every constraint kind the open mandates allow, by its `type`, with its definition. */
    private val constraintKinds: Map<String, JsonObject> by lazy {
        openMandates.flatMap { file ->
            val mandate = schema(file)
            // The payment mandate lists its kinds under `oneOf`, the checkout mandate under `anyOf`.
            val items = mandate.properties["constraints"]!!.jsonObject["items"]!!.jsonObject
            (items["oneOf"] ?: items["anyOf"])!!.jsonArray.map { ref ->
                val def = mandate.def(ref.jsonObject["\$ref"]!!.jsonPrimitive.content.substringAfterLast('/'))
                def.properties["type"]!!.jsonObject["const"]!!.jsonPrimitive.content to def
            }
        }.toMap()
    }

    // Shown with the generic rendering on purpose. A kind AP2 adds is in neither list and fails
    // the test, so someone decides how to word it.
    private val shownGenerically = setOf(
        "payment.reference",
        "payment.agent_recurrence",
        "payment.allowed_payment_instruments",
        "payment.allowed_pisps",
        "payment.execution_date",
    )

    @Test
    fun everyConstraintKindIsAccountedFor() {
        assertEquals(constraintKinds.keys, DelegateMandateText.UNDERSTOOD_CONSTRAINTS.keys + shownGenerically)
        assertTrue(DelegateMandateText.UNDERSTOOD_CONSTRAINTS.keys.none { it in shownGenerically })
    }

    // A member the wallet does not understand sends the whole constraint to the generic
    // rendering: safe, but the person loses the plain-language row.
    @Test
    fun understoodConstraintsCoverEveryMemberTheSchemaDefines() {
        for ((kind, understood) in DelegateMandateText.UNDERSTOOD_CONSTRAINTS) {
            assertEquals(constraintKinds.getValue(kind).properties.keys - "type", understood, kind)
        }
    }

    // The budget was read in the wrong units once: AP2 types an amount in major units as a
    // number, and in minor units as an integer.
    @Test
    fun amountsAreReadInTheUnitsTheSchemaGivesThem() {
        for ((kind, def) in constraintKinds) {
            if ("currency" !in def.properties) continue
            for (member in def.properties.keys - setOf("type", "currency")) {
                when (def.typeOf(member)) {
                    "number" -> assertTrue(kind in DelegateMandateText.MAJOR_UNIT_CONSTRAINTS, "$kind.$member")
                    "integer" -> assertTrue(kind !in DelegateMandateText.MAJOR_UNIT_CONSTRAINTS, "$kind.$member")
                }
            }
        }
    }

    @Test
    fun anAmountIsIntegerMinorUnitsAndACurrency() {
        val amount = schema("types/amount.json")
        assertEquals(amount.properties.keys, DelegateMandateText.AMOUNT_MEMBERS)
        assertEquals("integer", amount.typeOf("amount"))
    }

    @Test
    fun everyMerchantMemberIsUnderstood() {
        val merchant = schema("types/merchant.json").properties.keys
        assertTrue(DelegateMandateText.MERCHANT_MEMBERS.containsAll(merchant), merchant.toString())
    }

    @Test
    fun lineItemMembersMatchTheSchema() {
        val checkout = schema("open_checkout_mandate.json")
        assertEquals(checkout.def("line_item_requirements").properties.keys, DelegateMandateText.REQUIREMENT_MEMBERS)
        assertEquals(checkout.def("item").properties.keys, DelegateMandateText.ITEM_MEMBERS)
    }

    @Test
    fun everyMandateTypeHasAHeading() {
        for (file in mandates) {
            val vct = schema(file).properties["vct"]!!.jsonObject["const"]!!.jsonPrimitive.content
            assertTrue(vct in DelegateMandateText.HEADINGS, "$file: $vct")
        }
    }

    @Test
    fun timestampsAreShownAsDates() {
        for (file in mandates) {
            for (claim in listOf("iat", "exp")) {
                assertEquals("integer", schema(file).typeOf(claim), "$file: $claim")
                assertTrue(claim in DelegateMandateText.INSTANT_CLAIMS, claim)
            }
        }
    }
}
