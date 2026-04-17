package org.multipaz.records.payment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.server.presentment.PresentmentRecord
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Instant

@CborSerializable
data class PaymentData(
    val payeeAccount: String,
    val payeeName: String,
    val nonce: ByteString,
    val amount: Double,
    val currency: String,
    val description: String?,
    val time: Instant,
    val payerAccount: String?,
    val payerName: String?,
    val presentmentRecord: PresentmentRecord?
) {
    companion object {
        val paymentsTableSpec = StorageTableSpec(
            name = "Payments",
            supportPartitions = false,
            supportExpiration = true
        )
    }
}
