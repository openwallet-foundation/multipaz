package org.multipaz.presentment

import org.multipaz.consent.PresentmentPurpose
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity

/**
 * What an application's [org.multipaz.prompt.TransactionDataRenderer] needs to draw one type's items
 * in the consent prompt.
 *
 * The consent prompt creates this for each group of transaction data items of the same
 * [TransactionType] for a credential, and passes it to the renderer found for that type in the
 * [org.multipaz.prompt.TransactionDataRendererRepository].
 *
 * The transaction data comes from the relying party, so a renderer must treat it as untrusted:
 *
 * - Show strings from a payload as plain text: don't interpret markup, limit their length, and
 *   don't make links tappable without validating them.
 * - The prompt always shows the [requester]. A renderer may leave out a domain equal to the
 *   requester's origin, but a merchant or payee that differs from the requester must be shown.
 *
 * @property transactionData the items, all of the same [TransactionType], in request order.
 * @property requester the relying party asking, so a renderer can leave out what the prompt's header
 *   already shows, such as a domain equal to the requester's origin.
 * @property trustedRequesterIdentity the requester's trust status, or `null` if not trusted.
 * @property hasClaims whether the credential also discloses claims, for wording such as
 *   "will also be approved".
 * @property purposes the inferred [PresentmentPurpose]s for the current selection.
 * @property userInput the input the user has given for this type so far, or `null`.
 * @property onUserInputChanged called when the user changes their input.
 * @property isScrolledToBottom whether the details page has been scrolled to its end. This is
 *   always `true` in the summary. A details view can use it to require the user to read the whole
 *   transaction before confirming, for example by enabling an acceptance checkbox only at the end.
 * @property onContinueAllowedChanged called by the renderer when the user's ability to continue
 *   changes. A renderer that requires an explicit action, such as accepting contract terms, reports
 *   `false` until that action is complete and `true` afterwards. The consent prompt keeps its
 *   continue action disabled while any visible transaction reports `false`.
 */
class TransactionDataContext(
    val transactionData: List<TransactionData<*>>,
    val requester: Requester,
    val trustedRequesterIdentity: TrustedRequesterIdentity?,
    val hasClaims: Boolean,
    val purposes: Set<PresentmentPurpose> = emptySet(),
    val userInput: TransactionUserInput?,
    val onUserInputChanged: (userInput: TransactionUserInput) -> Unit,
    val isScrolledToBottom: Boolean = true,
    val onContinueAllowedChanged: (allowed: Boolean) -> Unit = {},
)
