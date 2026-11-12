package org.multipaz.compose.presentment

import androidx.compose.runtime.Composable
import org.multipaz.presentment.TransactionDataContext
import org.multipaz.prompt.TransactionDataRenderer

/**
 * A [TransactionDataRenderer] drawn with Compose.
 *
 * Add it to a [org.multipaz.prompt.TransactionDataRendererRepository] under the transaction type's
 * identifier and pass the repository to [org.multipaz.prompt.promptModelRequestConsent].
 *
 * The summary may leave fields out, but for transaction types whose specification requires the
 * signed content to be shown, such as AP2 mandates, everything signed must be reachable on the
 * details page. The details page is part of the consent prompt, so it is still a trusted surface.
 * The SDK cannot check that a details view is complete; its author owns that. See
 * [TransactionDataContext] for how to treat the payload.
 *
 * Both slots draw all items of one transaction type for a credential, given in the
 * [TransactionDataContext].
 *
 * @property summary drawn in the consent prompt; should show only what the user needs to decide.
 * @property details drawn on a separate page inside the consent prompt when the user taps the
 *   "View details" chip rendered by the prompt, or `null` for no details page.
 */
open class TransactionDataRendererCompose(
    val summary: @Composable (context: TransactionDataContext) -> Unit,
    val details: (@Composable (context: TransactionDataContext) -> Unit)? = null,
) : TransactionDataRenderer()
