package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.documenttype.TransactionType
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TransactionDataValidateTogetherTest {

    private object Plain : TransactionType<String>(
        displayName = "Plain",
        identifier = "com.example.plain",
    )

    /** Refuses two items that say the same thing, as a type that cannot answer them in one response. */
    private object NoRepeats : TransactionType<String>(
        displayName = "No repeats",
        identifier = "com.example.noRepeats",
    ) {
        override fun validateTogether(transactionData: List<TransactionData<String>>) {
            require(transactionData.map { it.payload }.distinct().size == transactionData.size) { "repeated" }
        }
    }

    private fun <T : Any> item(type: TransactionType<T>, payload: T) =
        TransactionData(type, payload, TransactionProtocol.OPENID4VP, ByteString())

    // Each type checks its own items together, and only its own.
    @Test
    fun eachTypeChecksItsOwnItemsTogether() {
        TransactionData.validateTogether(listOf(item(NoRepeats, "a"), item(NoRepeats, "b"), item(Plain, "a")))
        assertFailsWith<IllegalArgumentException> {
            TransactionData.validateTogether(listOf(item(NoRepeats, "a"), item(Plain, "x"), item(NoRepeats, "a")))
        }
    }
}
