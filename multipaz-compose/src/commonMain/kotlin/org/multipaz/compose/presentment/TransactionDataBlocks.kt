package org.multipaz.compose.presentment

import org.multipaz.presentment.TransactionData
import org.multipaz.prompt.TransactionDataRendererRepository

/** One block of transaction data in the consent prompt, drawn either by the application or by the library. */
internal sealed interface TransactionDataBlock {
    /** All items of a transaction type the application has a [TransactionDataRendererCompose] for, drawn together. */
    data class Wallet(
        val identifier: String,
        val items: List<TransactionData<*>>,
        val renderer: TransactionDataRendererCompose,
    ) : TransactionDataBlock

    /** A single item drawn by the built-in rendering. */
    data class BuiltIn(val item: TransactionData<*>) : TransactionDataBlock
}

/**
 * Splits the transaction data of a credential into the blocks the consent prompt draws.
 *
 * Items of a transaction type with a [TransactionDataRendererCompose] in the repository are drawn
 * together, in the place of the first of them. Every other item keeps the built-in rendering, one
 * item at a time, in request order. Renderers made for another UI toolkit are ignored.
 *
 * @param transactionData the transaction data items of one credential, in request order.
 * @param transactionDataRendererRepository the repository to look up renderers in, or `null` for none.
 * @return the blocks, in the order to draw them.
 */
internal fun transactionDataBlocks(
    transactionData: List<TransactionData<*>>,
    transactionDataRendererRepository: TransactionDataRendererRepository?,
): List<TransactionDataBlock> {
    val renderers = mutableMapOf<String, TransactionDataRendererCompose?>()
    val blocks = mutableListOf<TransactionDataBlock>()
    for (item in transactionData) {
        val identifier = item.type.identifier
        val isFirstOfType = identifier !in renderers
        val renderer = renderers.getOrPut(identifier) {
            transactionDataRendererRepository?.getRenderer(identifier) as? TransactionDataRendererCompose
        }
        if (renderer == null) {
            blocks.add(TransactionDataBlock.BuiltIn(item))
        } else if (isFirstOfType) {
            blocks.add(
                TransactionDataBlock.Wallet(
                    identifier = identifier,
                    items = transactionData.filter { it.type.identifier == identifier },
                    renderer = renderer,
                )
            )
        }
    }
    return blocks
}
