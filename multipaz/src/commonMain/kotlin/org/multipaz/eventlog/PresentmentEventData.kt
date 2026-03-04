package org.multipaz.eventlog

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509CertChain
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata

/**
 * High-level data involved in a presentment event.
 *
 * This data structure is designed to work for any credential type so the UI
 * can show a simple overview. More detailed data is available in each of the [Event]-derived
 * classes which include this data structure.
 *
 * @property requesterName the name of the requester, if available.
 * @property requesterCertChain the certificate chain of the requester if available.
 * @property trustMetadata a [TrustMetadata] or `null`.
 * @property requestedDocuments list of requested documents and claims.
 */
@CborSerializable
data class PresentmentEventData(
    val requesterName: String?,
    val requesterCertChain: X509CertChain?,
    val trustMetadata: TrustMetadata?,
    val requestedDocuments: List<PresentmentEventDocument>
    // TODO: include high-level view of transaction data in this data structure.
) {

    companion object {
        /**
         * Creates a [PresentmentEventData] from a [CredentialPresentmentSelection].
         *
         * @param selection the [CredentialPresentmentSelection] to create the [PresentmentEventData] from.
         * @param requester the requester of the data.
         * @param trustMetadata a [TrustMetadata] or `null`.
         * @return a [PresentmentEventData].
         */
        fun fromPresentmentSelection(
            selection: CredentialPresentmentSelection,
            requester: Requester,
            trustMetadata: TrustMetadata?,
        ): PresentmentEventData {
            var requesterName: String? = null
            if (trustMetadata != null) {
                requesterName = trustMetadata.displayName
            } else if (requester.isWebOrigin) {
                requesterName = requester.origin
            }

            val requestedDocuments = mutableListOf<PresentmentEventDocument>()
            selection.matches.forEach { match ->
                requestedDocuments.add(PresentmentEventDocument(
                    documentId = match.credential.document.identifier,
                    documentName = match.credential.document.displayName,
                    claims = match.claims
                ))
            }

            return PresentmentEventData(
                requesterName = requesterName,
                requesterCertChain = requester.certChain,
                trustMetadata = trustMetadata,
                requestedDocuments = requestedDocuments
            )
        }
    }
}
