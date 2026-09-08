package org.multipaz.documenttype.knowntypes

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborDouble
import org.multipaz.cbor.Tstr
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PaymentTransactionTest {

    @Test
    fun testVerifyMdocResponseAmountMismatch() = runTest {
        val payload = PaymentTransaction.sampleData.payload.copy(amount = 123.25, currency = "USD")
        val dataItem = PaymentTransaction.serializeIso18013Request(payload)
        val transactionData = TransactionData(
            type = PaymentTransaction,
            payload = payload,
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = ByteString(Cbor.encode(dataItem)),
        )

        assertFailsWith(IllegalStateException::class) {
            PaymentTransaction.verifyMdocResponse(
                transactionData = transactionData,
                responseElements = mapOf(
                    "amount" to CborDouble(123.26),
                    "currency" to Tstr("USD"),
                )
            )
        }
    }

    @Test
    fun testVerifyMdocResponseCurrencyMismatch() = runTest {
        val payload = PaymentTransaction.sampleData.payload.copy(amount = 123.25, currency = "USD")
        val dataItem = PaymentTransaction.serializeIso18013Request(payload)
        val transactionData = TransactionData(
            type = PaymentTransaction,
            payload = payload,
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = ByteString(Cbor.encode(dataItem)),
        )

        assertFailsWith(IllegalStateException::class) {
            PaymentTransaction.verifyMdocResponse(
                transactionData = transactionData,
                responseElements = mapOf(
                    "amount" to CborDouble(123.25),
                    "currency" to Tstr("EUR"),
                )
            )
        }
    }

    @Test
    fun testVerifyMdocResponseMissingAmount() = runTest {
        val payload = PaymentTransaction.sampleData.payload.copy(amount = 123.25, currency = "USD")
        val dataItem = PaymentTransaction.serializeIso18013Request(payload)
        val transactionData = TransactionData(
            type = PaymentTransaction,
            payload = payload,
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = ByteString(Cbor.encode(dataItem)),
        )

        assertFailsWith(IllegalStateException::class) {
            PaymentTransaction.verifyMdocResponse(
                transactionData = transactionData,
                responseElements = mapOf(
                    "currency" to Tstr("USD"),
                )
            )
        }
    }
}
