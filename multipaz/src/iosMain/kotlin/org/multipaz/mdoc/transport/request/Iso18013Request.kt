package org.multipaz.mdoc.transport.request

import org.multipaz.claim.Claim
import org.multipaz.claim.findMatchingClaim
import org.multipaz.credential.Credential
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.SimpleCredentialPresentmentData
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.presentment.model.getDocumentsMatchingRequest
import org.multipaz.request.RequestedClaim
import org.multipaz.util.Logger

// Kotlin version of ISO18013MobileDocumentRequest
data class Iso18013Request(
    val presentmentRequests: List<Iso18013PresentmentRequest>
) {

    suspend fun getCredentialPresentmentData(
        source: PresentmentSource,
        keyAgreementPossible: List<EcCurve> = emptyList()
    ): CredentialPresentmentData {
        // For now we only support a single docRequest.
        val docRequest = presentmentRequests.first().documentRequestSets.first().requests.first()

        val requestWithoutFiltering = docRequest.toMdocRequest(
            documentTypeRepository = source.documentTypeRepository,
            mdocCredential = null
        )
        val documents = source.getDocumentsMatchingRequest(
            request = requestWithoutFiltering,
        )
        val matches = mutableListOf<Pair<Credential, Map<RequestedClaim, Claim>>>()
        for (document in documents) {
            val mdocCredential = source.selectCredential(
                document = document,
                request = requestWithoutFiltering,
                keyAgreementPossible = keyAgreementPossible
            ) as MdocCredential?
            if (mdocCredential == null) {
                Logger.w(TAG, "No credential found")
                continue
            }

            val claims = mdocCredential.getClaims(source.documentTypeRepository)
            val claimsToShow = buildMap {
                for (requestedClaim in requestWithoutFiltering.requestedClaims) {
                    claims.findMatchingClaim(requestedClaim)?.let {
                        put(requestedClaim as RequestedClaim, it)
                    }
                }
            }
            matches.add(Pair(mdocCredential,claimsToShow))
        }
        return SimpleCredentialPresentmentData(matches)
    }

    companion object {
        private const val TAG = "Iso18013Request"
    }
}