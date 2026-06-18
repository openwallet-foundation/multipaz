package org.multipaz.presentment

import org.multipaz.credential.Credential
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.eventlogger.EventLogger
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.trustmanagement.TrustMetadata

/**
 * The source of truth used for credential presentment.
 *
 * This is used whenever an application wishes to present credentials including the [DocumentStore]
 * which holds credentials that can be presented and a [DocumentTypeRepository] which contains descriptions
 * of well-known document types which may be shown in a consent prompt.
 *
 * It's also used for more dynamic data such as determining whether the requester is trusted (via [resolveTrust])
 * and if so what branding to show, whether user consent should be obtained or if preconsent exists (via
 * [showConsentPrompt]), and even what kind of user authentication to perform to present the credential, if any (by
 * picking a [Credential] from an appropriate domain in [selectCredential]).
 *
 * @property documentStore the [DocumentStore] which holds credentials that can be presented.
 * @property documentTypeRepository a [DocumentTypeRepository] which holds metadata for document types.
 * @property zkSystemRepository a [ZkSystemRepository] with ZKP systems or `null`.
 * @property eventLogger an [EventLogger] for logging events or `null`.
 * @see SimplePresentmentSource for one concrete implementation tailored for ISO mdoc and IETF SD-JWT VC credentials.
 */
abstract class PresentmentSource(
    open val documentStore: DocumentStore,
    open val documentTypeRepository: DocumentTypeRepository,
    open val zkSystemRepository: ZkSystemRepository? = null,
    open val eventLogger: EventLogger? = null
) {
    /**
     * Determines if a request comes from a trusted requestor and returns the information about the
     * requestor.
     *
     * Two pieces of information are returned as a part of [TrustedRequesterIdentity] result.
     *
     * The first is [RequesterIdentity] represents the primary request signature on which the
     * trust determination is made. This [RequesterIdentity] must come from the
     * [Requester.requesterIdentities] list of the [requester] parameter.
     *
     * The other one is [TrustMetadata] which provides branding and other information about the
     * requester that can be presented to the user.
     *
     * Note: this function generally can base its trust determination and [TrustMetadata] values
     * on all of the [RequesterIdentity] objects in [Requester.requesterIdentities], not only
     * the primary identity it returns.
     *
     * @param requester the requester to check.
     * @return a [TrustedRequesterIdentity] object as described above or `null` if the requestor
     *  trust could not be established.
     */
    abstract suspend fun resolveTrust(requester: Requester): TrustedRequesterIdentity?

    /**
     * A function to show a consent prompt.
     *
     * An application will typically call [org.multipaz.prompt.promptModelRequestConsent] which will
     * show a consent prompt to the user. The application may also be configured to exercise consent
     * previously given by the user in which case it can call [org.multipaz.prompt.promptModelSilentConsent].
     *
     * In either case implementations *MUST* always call [onSelectionChanged], even if no user interaction
     * is happening.
     *
     * @param requester the relying party which is requesting the data.
     * @param trustedRequesterIdentity conveys the level of trust in the requester, if any.
     * @param consentData the combinations of credentials and claims that the user can select.
     * @param preselectedDocuments a list of documents the user may have preselected earlier (for
     *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
     *   if the user didn't preselect.
     * @param onDocumentsInFocus called with the documents currently selected for the user, including when
     *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
     * @return `null` if the user dismissed the prompt, otherwise a [CredentialSelection] object
     *   conveying which credentials the user selected, if multiple options are available.
     * @see [org.multipaz.prompt.ShowConsentPromptFn] which this method wraps.
     */
    abstract suspend fun showConsentPrompt(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
        consentData: ConsentData,
        preselectedDocuments: List<Document>,
        onDocumentsInFocus: (documents: List<Document>) -> Unit
    ): CredentialSelection?

    /**
     * Chooses a credential from a document.
     *
     * @param document the [Document] to pick a credential from.
     * @param requestedClaims the requested claims.
     * @param keyAgreementPossible if non-empty, a credential using Key Agreement may be returned provided
     *   its private key is one of the given curves.
     * @return a [Credential] belonging to [document] that may be presented or `null`.
     */
    abstract suspend fun selectCredential(
        document: Document,
        requestedClaims: List<RequestedClaim>,
        keyAgreementPossible: List<EcCurve>,
    ): Credential?

    /**
     * Gets a list of badges for a document.
     *
     * This may be used when the UI shows a list of documents to choose from.
     *
     * @param document the document to get a list of badges from.
     * @return the badges.
     */
    abstract suspend fun getBadges(document: Document): List<DocumentBadge>
}
