package org.multipaz.prompt

/**
 * Draws the transaction data of one [org.multipaz.documenttype.TransactionType] in the consent prompt.
 *
 * This class carries no drawing code itself. The UI libraries extend it with the slots that draw the
 * transaction data, for example `TransactionDataRendererCompose` in `multipaz-compose` and
 * `TransactionDataRendererSwiftUi` in `multipaz-swiftui`. A consent prompt uses only renderers made
 * for its UI toolkit and keeps the built-in rendering for any other.
 *
 * The slots take a [org.multipaz.presentment.TransactionDataContext] with what to draw: the items of
 * the transaction type, the requester, and callbacks for the user's input and the details page.
 *
 * Renderers are found through a [TransactionDataRendererRepository].
 */
abstract class TransactionDataRenderer
