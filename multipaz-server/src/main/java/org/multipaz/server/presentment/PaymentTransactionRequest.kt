package org.multipaz.server.presentment

import org.multipaz.cbor.annotation.CborSerializable

/**
 * Request to create a payment transaction via [PaymentProcessor.createTransaction].
 *
 * @property payeeAccount identifier of the payee's account.
 * @property amount payment amount.
 * @property currency ISO 4217 currency code (e.g. "USD", "EUR").
 * @property description optional human-readable description of the payment.
 */
@CborSerializable
data class PaymentTransactionRequest(
    val payeeAccount: String,
    val amount: Double,
    val currency: String,
    val description: String?
) {
    companion object
}