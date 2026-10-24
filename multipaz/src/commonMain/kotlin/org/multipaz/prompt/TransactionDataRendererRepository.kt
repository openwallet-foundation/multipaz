package org.multipaz.prompt

/**
 * A repository of [TransactionDataRenderer], indexed by transaction type.
 *
 * Renderers are keyed by [org.multipaz.documenttype.TransactionType.identifier], for example
 * `"org.multipaz.transaction.ping"` or `"urn:eudi:sca:payment:1"`. Transaction types without a
 * renderer keep the consent prompt's built-in rendering.
 *
 * The repository is passed to the consent prompt with each request, see [promptModelRequestConsent].
 * An application wanting different renderers for a given issuer or relying party can pass a
 * different repository for that request, or override [getRenderer].
 *
 * With [org.multipaz.presentment.SimplePresentmentSource], pass it from the consent prompt function:
 *
 * ```
 * val source = SimplePresentmentSource(
 *     // ...
 *     showConsentPromptFn = { requester, trustedRequesterIdentity, presentmentData, preselectedDocuments, onDocumentsInFocus ->
 *         promptModelRequestConsent(
 *             requester = requester,
 *             trustedRequesterIdentity = trustedRequesterIdentity,
 *             presentmentData = presentmentData,
 *             preselectedDocuments = preselectedDocuments,
 *             onDocumentsInFocus = onDocumentsInFocus,
 *             transactionDataRendererRepository = renderers,
 *         )
 *     },
 * )
 * ```
 */
open class TransactionDataRendererRepository {
    private val renderers = mutableMapOf<String, TransactionDataRenderer>()

    /**
     * Associates a renderer with a transaction type, replacing any renderer added for it before.
     *
     * @param identifier the [org.multipaz.documenttype.TransactionType.identifier].
     * @param renderer the [TransactionDataRenderer] to use for that transaction type.
     */
    fun addRenderer(identifier: String, renderer: TransactionDataRenderer) {
        renderers[identifier] = renderer
    }

    /**
     * Gets the renderer for a transaction type.
     *
     * @param identifier the [org.multipaz.documenttype.TransactionType.identifier].
     * @return the [TransactionDataRenderer] for that transaction type or `null` to use the built-in rendering.
     */
    open fun getRenderer(identifier: String): TransactionDataRenderer? = renderers[identifier]
}
