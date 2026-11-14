package org.multipaz.testapp

import org.multipaz.documenttype.CannedTransactionData
import org.multipaz.documenttype.SingleDocumentCannedRequest
import org.multipaz.utopia.knowntypes.DelegateTransaction
import org.multipaz.utopia.knowntypes.DigitalPaymentCredentialSdJwt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A request for the SD-JWT payment card that also asks the holder to let an agent spend for them,
 * as an AP2 merchant would: two open mandates in `delegate_payload`, for one item from one
 * merchant, with a per-purchase and a total limit, valid for 30 days.
 *
 * It exercises [DelegateTransaction] and its consent screen end to end, with the test documents.
 *
 * @param now the time the mandates' expiry is counted from.
 */
fun ap2DelegateCannedRequest(now: Instant = Clock.System.now()): SingleDocumentCannedRequest {
    val exp = (now + 30.days).epochSeconds
    // The agent's key. Only its public part is in the mandate; nothing here signs with it.
    val agent = """{"jwk":{"kty":"EC","crv":"P-256",""" +
        """"x":"Fo9yTRgc4kXAdKpCV2mQe8Lw3sZ1HbuN6r0iJt7aPyE","y":"r4XbR2Gh6m0Qe1tYpLz8kVwN5sJdC3uA9fOiHnTyM7c"}}"""
    val merchant = """{"id":"shop.example.com","name":"Utopia Shop","website":"https://shop.example.com"}"""
    val request = """
        {
          "type": "${DelegateTransaction.identifier}",
          "credential_ids": ["cred1"],
          "format": "dc+sd-jwt",
          "delegate_payload": [
            {
              "vct": "mandate.checkout.open.1", "exp": $exp, "cnf": $agent,
              "constraints": [
                {"type": "checkout.allowed_merchants", "allowed": [$merchant]},
                {"type": "checkout.line_items", "items": [
                  {"id": "r1", "quantity": 1, "acceptable_items": [{"id": "aurora", "title": "Aurora headphones"}]}
                ]}
              ]
            },
            {
              "vct": "mandate.payment.open.1", "exp": $exp, "cnf": $agent,
              "constraints": [
                {"type": "payment.amount_range", "currency": "USD", "max": 25000},
                {"type": "payment.budget", "currency": "USD", "max": 400.00},
                {"type": "payment.allowed_payees", "allowed": [$merchant]}
              ]
            }
          ]
        }
    """.trimIndent()
    val base = DigitalPaymentCredentialSdJwt.getDocumentType().cannedRequests.first { it.id == "payment_sca_minimal" }
    return base.copy(
        id = "ap2_delegate",
        displayName = "AP2: let an agent spend up to 400 USD",
        transactionData = listOf(
            CannedTransactionData(DelegateTransaction, DelegateTransaction.parseOpenId4VpRequest(request))
        ),
    )
}
