package org.multipaz.server.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Data returned when a payment transaction is created via [PaymentProcessor.createTransaction].
 *
 * @property transactionId unique identifier for the pending transaction.
 * @property payeeName display name of the payee, suitable for showing to the payer.
 * @property nonce a nonce to use in the presentment request.
 */
@CborSerializable
data class PaymentTransactionData(
    val transactionId: String,
    val payeeName: String,
    val nonce: ByteString
) {
    companion object
}