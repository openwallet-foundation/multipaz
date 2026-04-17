package org.multipaz.server.presentment

import org.multipaz.rpc.annotation.RpcInterface
import org.multipaz.rpc.annotation.RpcMethod

/**
 * RPC interface for payment transaction processing.
 *
 * A payment flow consists of two steps:
 * 1. [createTransaction] — the verifier creates a pending transaction and receives a nonce
 *    to be included in the credential presentation.
 * 2. [commitTransaction] — after the holder presents a credential, the verifier submits
 *    the [PresentmentRecord] to finalize the transaction.
 */
@RpcInterface
interface PaymentProcessor {
    /**
     * Creates a new pending payment transaction.
     *
     * @param request details of the payment (payee, amount, currency).
     * @return transaction data including the transaction ID and a nonce that the payment
     *  server uses to request credential presentation.
     */
    @RpcMethod(endpoint = "create")
    suspend fun createTransaction(request: PaymentTransactionRequest): PaymentTransactionData

    /**
     * Commits a previously created transaction by providing a verified credential presentation.
     *
     * @param presentmentRecord self-contained result of the credential presentation.
     * @return transaction confirmation id
     */
    @RpcMethod(endpoint = "commit")
    suspend fun commitTransaction(presentmentRecord: PresentmentRecord): String
}