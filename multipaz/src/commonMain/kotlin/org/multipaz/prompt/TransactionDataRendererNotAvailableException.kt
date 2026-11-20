package org.multipaz.prompt

/**
 * Thrown when a consent prompt is asked to show transaction data of a type that
 * [org.multipaz.documenttype.TransactionType.requiresRenderer], and there is no
 * [TransactionDataRenderer] for it that the prompt can use: none is registered under the type's
 * identifier, the [TransactionDataRendererRepository] was never passed to the prompt, or the one
 * registered is made for another UI toolkit.
 *
 * The prompt is refused rather than shown with the built-in rendering, so a person never approves
 * transaction data they were not shown.
 *
 * @property typeIdentifier the identifier of the transaction type that has no renderer.
 * @param message the exception message. Without one, it says how to give the prompt a renderer.
 */
class TransactionDataRendererNotAvailableException(
    val typeIdentifier: String,
    message: String,
) : Exception(message) {
    constructor(typeIdentifier: String) : this(
        typeIdentifier,
        "Transaction type '$typeIdentifier' can only be approved when its data is shown, but the consent " +
            "prompt has no renderer for it: register a TransactionDataRenderer made for this UI toolkit " +
            "under '$typeIdentifier' and pass the repository to the prompt, for example as " +
            "SimplePresentmentSource's transactionDataRendererRepository"
    )
}
