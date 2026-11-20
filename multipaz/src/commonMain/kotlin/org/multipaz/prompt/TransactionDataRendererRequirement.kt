package org.multipaz.prompt

import org.multipaz.presentment.TransactionData

/**
 * The identifier of the first transaction type in [transactionData] that
 * [org.multipaz.documenttype.TransactionType.requiresRenderer] and has no renderer in [repository]
 * that [isUsable], or `null` when every such type has one.
 *
 * A consent prompt asks this with what its own toolkit can use, so a renderer made for another
 * toolkit counts as none.
 *
 * @param transactionData the transaction data to be shown, in request order.
 * @param repository the repository the prompt looks renderers up in, or `null` if it was given none.
 * @param isUsable whether a renderer found for a type is one the prompt can draw.
 */
fun missingTransactionDataRenderer(
    transactionData: List<TransactionData<*>>,
    repository: TransactionDataRendererRepository?,
    isUsable: (TransactionDataRenderer) -> Boolean = { true },
): String? = transactionData
    .map { it.type }
    .filter { it.requiresRenderer }
    .map { it.identifier }
    .distinct()
    .firstOrNull { identifier -> repository?.getRenderer(identifier)?.let(isUsable) != true }
