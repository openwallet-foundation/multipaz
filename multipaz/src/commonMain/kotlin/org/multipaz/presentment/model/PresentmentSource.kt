package org.multipaz.presentment.model

import kotlin.time.Clock
import org.multipaz.credential.Credential
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.request.JsonRequest
import org.multipaz.request.MdocRequest
import org.multipaz.request.Request
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Logger

/**
 * The source of truth used for credential presentment.
 *
 * @property documentStore the [DocumentStore] which holds credentials that can be presented.
 * @property documentTypeRepository a [DocumentTypeRepository] which holds metadata for document types.
 * @property readerTrustManager the [TrustManager] used to determine if a reader is trusted.
 * @property skipConsentPrompt set to `true` to not show a consent dialog.
 * @property dynamicMetadataResolver a function which can be used to calculate [TrustMetadata] on a
 *   per-request basis, which may used in credential prompts.
 */
abstract class PresentmentSource(
    open val documentStore: DocumentStore,
    open val documentTypeRepository: DocumentTypeRepository,
    open val readerTrustManager: TrustManager,
    open val zkSystemRepository: ZkSystemRepository? = null,
    open val skipConsentPrompt: Boolean = false,
    open val dynamicMetadataResolver: (requester: Requester) -> TrustMetadata? = { requester -> null },
    ) {

    /**
     * Chooses a credential from a document.
     *
     * @param document the [Document] to pick a credential from or `null`.
     * @param request the request in question.
     * @param keyAgreementPossible if non-empty, a credential using Key Agreement may be returned provided
     *   its private key is one of the given curves.
     * @return a [Credential] belonging to [document] that may be presented or `null`.
     */
    abstract suspend fun selectCredential(
        document: Document?,
        request: Request,
        keyAgreementPossible: List<EcCurve>,
    ): Credential?

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
}

private const val TAG = "PresentmentSource"

internal suspend fun PresentmentSource.findTrustPoint(requester: Requester): TrustPoint? {
    return requester.certChain?.let {
        val trustResult = readerTrustManager.verify(it.certificates)
        if (trustResult.isTrusted) {
            trustResult.trustPoints[0]
        } else {
            trustResult.error?.let {
                Logger.w(TAG, "Trust-result error", it)
            }
            null
        }
    }
}

internal suspend fun PresentmentSource.getDocumentsMatchingRequest(
    request: Request,
): List<Document> {
    return when (request) {
        is MdocRequest -> mdocFindDocumentsForRequest(request)
        is JsonRequest -> sdjwtFindDocumentsForRequest(request)
    }
}

private suspend fun PresentmentSource.mdocFindDocumentsForRequest(
    request: MdocRequest,
): List<Document> {
    val now = Clock.System.now()
    val result = mutableListOf<Document>()

    for (documentName in documentStore.listDocumentIds()) {
        val document = documentStore.lookupDocument(documentName) ?: continue
        if (mdocDocumentMatchesRequest(request, document)) {
            result.add(document)
        }
    }
    return result
}

private suspend fun PresentmentSource.mdocDocumentMatchesRequest(
    request: MdocRequest,
    document: Document,
): Boolean {
    for (credential in document.getCertifiedCredentials()) {
        if (credential is MdocCredential && credential.docType == request.docType) {
            return true
        }
    }
    return false
}

private suspend fun PresentmentSource.sdjwtFindDocumentsForRequest(
    request: JsonRequest,
): List<Document> {
    val now = Clock.System.now()
    val result = mutableListOf<Document>()

    for (documentName in documentStore.listDocumentIds()) {
        val document = documentStore.lookupDocument(documentName) ?: continue
        if (sdjwtDocumentMatchesRequest(request, document)) {
            result.add(document)
        }
    }
    return result
}

internal suspend fun PresentmentSource.sdjwtDocumentMatchesRequest(
    request: JsonRequest,
    document: Document,
): Boolean {
    for (credential in document.getCertifiedCredentials()) {
        if (credential is SdJwtVcCredential && credential.vct == request.vct) {
            return true
        }
    }
    return false
}

/*
/**
 * An interface used for the application to provide data and policy for credential presentment.
 */
interface PresentmentSource {

    /**
     * The [DocumentTypeRepository] to look up metadata for incoming requests.
     */
    val documentTypeRepository: DocumentTypeRepository

    /**
     * Finds a [TrustPoint] for a requester.
     *
     * @param request The request.
     * @return a [TrustPoint] or `null` if none could be found.
     */
    suspend fun findTrustPoint(
        request: Request
    ): TrustPoint?

    /**
     * Gets the documents that match a given request.
     *
     * The documents returned must be distinct.
     *
     * @param request the request.
     * @return zero, one, or more [Document] instances eligible for presentment.
     */
    suspend fun getDocumentsMatchingRequest(
        request: Request,
    ): List<Document>

    /**
     * Returns a credential that can be presented.
     *
     * @param request the request.
     * @param document the document to get a credential from or `null`.
     * @return a [CredentialForPresentment] object with a credential that can be used for presentment.
     */
    suspend fun getCredentialForPresentment(
        request: Request,
        document: Document?
    ): CredentialForPresentment

    /**
     * Function to determine if the consent prompt should be shown.
     *
     * @param credential the credential being presented.
     * @param request the request.
     * @return `true` if the consent prompt should be shown, `false` otherwise.
     */
    fun shouldShowConsentPrompt(
        credential: Credential,
        request: Request,
    ): Boolean

    /**
     * Function to determine if a Signature should be used for the response if both
     * Key Agreement and Signatures are possible options.
     *
     * Key Agreement provides better privacy to the credential holder because it does not
     * require producing a potentially non-repudiable signature over reader-provided data.
     * The holder can always deny the MAC value to a third party because the reader
     * could have produced it by itself.
     *
     * In some cases the reader might prefer a Signature to get proof that the credential
     * holder really participated in the transaction. This function provides a mechanism
     * for the holder to honor such a request. The request from the reader to express
     * this preference would need to be provided out-of-band by the reader.
     *
     * @param document the document being presented.
     * @param request the request.
     * @return `true` to always use signatures even if key agreement is possible.
     */
    fun shouldPreferSignatureToKeyAgreement(
        document: Document,
        request: Request,
    ): Boolean
}
 */