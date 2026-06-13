package org.multipaz.records.payment

import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Instant

@CborSerializable
data class PaymentRecord(
    val payerAccount: String,
    val payerName: String,
    val payeeAccount: String,
    val payeeName: String,
    val amount: Double,
    val time: Instant,
    val description: String?
) {
    companion object
}