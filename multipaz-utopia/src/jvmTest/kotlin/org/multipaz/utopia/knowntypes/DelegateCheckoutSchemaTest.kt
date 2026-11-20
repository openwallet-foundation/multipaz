package org.multipaz.utopia.knowntypes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that [UCP_CHECKOUT_PAYLOAD], the checkout the text tests decode, follows AP2 v0.2.0's own
 * UCP schemas (`resources/ap2-schemas/ucp/types`): the members they require, integer minor units
 * for every amount, and exactly one `subtotal` and one `total`.
 */
class DelegateCheckoutSchemaTest {

    private fun schema(path: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/ap2-schemas/$path")!!.readText()).jsonObject

    private val checkout = Json.parseToJsonElement(UCP_CHECKOUT_PAYLOAD).jsonObject

    private fun requireMembers(schemaFile: String, value: JsonObject) {
        val schema = schema(schemaFile)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(value.keys.containsAll(required), "$schemaFile requires $required, got ${value.keys}")
        assertTrue(value.keys.all { it in schema["properties"]!!.jsonObject.keys }, "unknown member in ${value.keys}")
    }

    private fun assertIntegerAmounts(totals: JsonArray) {
        val schema = schema("ucp/types/total.json")
        assertEquals("integer", schema["properties"]!!.jsonObject["amount"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        totals.forEach { total ->
            requireMembers("ucp/types/total.json", total.jsonObject)
            assertTrue(total.jsonObject["amount"]!!.jsonPrimitive.let { !it.isString && it.content.toLongOrNull() != null })
        }
    }

    @Test
    fun theFixtureIsAUcpCheckout() {
        requireMembers("ucp/types/checkout.json", checkout)
        val status = schema("ucp/types/checkout.json")["properties"]!!.jsonObject["status"]!!.jsonObject["enum"]!!.jsonArray
        assertTrue(checkout["status"] in status)
        assertEquals(3, checkout["currency"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun theTotalsHaveExactlyOneSubtotalAndOneTotalInMinorUnits() {
        val totals = checkout["totals"]!!.jsonArray
        assertIntegerAmounts(totals)
        assertEquals(1, totals.count { it.jsonObject["type"] == JsonPrimitive("subtotal") })
        assertEquals(1, totals.count { it.jsonObject["type"] == JsonPrimitive("total") })
    }

    @Test
    fun theLineItemsFollowTheLineItemAndItemSchemas() {
        checkout["line_items"]!!.jsonArray.forEach { lineItem ->
            requireMembers("ucp/types/line_item.json", lineItem.jsonObject)
            requireMembers("types/item.json", lineItem.jsonObject["item"]!!.jsonObject)
            assertIntegerAmounts(lineItem.jsonObject["totals"]!!.jsonArray)
            assertTrue(lineItem.jsonObject["item"]!!.jsonObject["price"]!!.jsonPrimitive.content.toLongOrNull() != null)
        }
        checkout["links"]!!.jsonArray.forEach { requireMembers("ucp/types/link.json", it.jsonObject) }
    }
}
