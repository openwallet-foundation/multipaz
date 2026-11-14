package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.documenttype.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransactionDataSdJwtKbTypeTest {

    private object Plain : TransactionType<String>(
        displayName = "Plain",
        identifier = "com.example.plain",
    )

    private object Fixed : TransactionType<String>(
        displayName = "Fixed",
        identifier = "com.example.fixed",
        sdJwtKbType = "kb+fixed",
    )

    private object OtherFixed : TransactionType<String>(
        displayName = "Other fixed",
        identifier = "com.example.otherFixed",
        sdJwtKbType = "kb+other",
    )

    /** Asks for `kb+chain` when any of its items says "chain", whatever their order. */
    private object PayloadDependent : TransactionType<String>(
        displayName = "Payload dependent",
        identifier = "com.example.payloadDependent",
        sdJwtKbType = "kb+end",
    ) {
        override fun sdJwtKbType(transactionData: List<TransactionData<String>>): String =
            if (transactionData.any { it.payload == "chain" }) "kb+chain" else "kb+end"
    }

    private fun <T : Any> item(type: TransactionType<T>, payload: T) =
        TransactionData(type, payload, TransactionProtocol.OPENID4VP, ByteString())

    @Test
    fun noTransactionDataIsTheOrdinaryType() {
        assertEquals("kb+jwt", TransactionData.sdJwtKbType(emptyList()))
    }

    @Test
    fun typesThatAskForNothingKeepTheOrdinaryType() {
        assertEquals("kb+jwt", TransactionData.sdJwtKbType(listOf(item(Plain, "a"), item(Plain, "b"))))
    }

    @Test
    fun aTypeThatAsksIsHeardAlongsideOnesThatDoNot() {
        assertEquals("kb+fixed", TransactionData.sdJwtKbType(listOf(item(Plain, "a"), item(Fixed, "b"))))
        assertEquals("kb+fixed", TransactionData.sdJwtKbType(listOf(item(Fixed, "b"), item(Plain, "a"))))
    }

    @Test
    fun theTypeDecidesOverAllOfItsItemsInAnyOrder() {
        val end = item(PayloadDependent, "end")
        val chain = item(PayloadDependent, "chain")
        assertEquals("kb+chain", TransactionData.sdJwtKbType(listOf(end, chain)))
        assertEquals("kb+chain", TransactionData.sdJwtKbType(listOf(chain, end)))
        assertEquals("kb+end", TransactionData.sdJwtKbType(listOf(end, end)))
    }

    // One Key Binding JWT has one `typ`; picking either would silently break the other type.
    @Test
    fun twoTypesAskingForDifferentValuesAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            TransactionData.sdJwtKbType(listOf(item(Fixed, "a"), item(OtherFixed, "b")))
        }
    }
}
