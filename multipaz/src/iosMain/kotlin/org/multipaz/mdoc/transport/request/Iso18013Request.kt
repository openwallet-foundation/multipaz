package org.multipaz.mdoc.transport.request

import org.multipaz.cbor.Simple
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.PresentmentSource
import org.multipaz.request.RequesterIdentity

// Kotlin version of ISO18013MobileDocumentRequest
data class Iso18013Request(
    val presentmentRequests: List<Iso18013PresentmentRequest>
) {

    /**
     * Executes the request against [source] and returns a [CredentialQueryResult].
     *
     * @param source the [PresentmentSource] to use as a source of truth for presentment.
     * @param keyAgreementPossible if non-empty, a credential using Key Agreement may be returned provided
     *   its private key is using one of the given curves.
     * @param requesterIdentities additional identities of the requester used for matching reader identifiers, if any.
     * @return the resulting [CredentialQueryResult] if the query was successful.
     */
    suspend fun getCredentialQueryResult(
        source: PresentmentSource,
        keyAgreementPossible: List<EcCurve> = emptyList(),
        requesterIdentities: List<RequesterIdentity> = emptyList()
    ): CredentialQueryResult {
        val documentRequest = mutableListOf<Iso18013DocumentRequest>()

        presentmentRequests.forEach { pr ->
            pr.documentRequestSets.forEach { drs ->
                drs.requests.forEach { dr ->
                    if (documentRequest.find { it == dr } == null) {
                        documentRequest.add(dr)
                    }
                }
            }
        }

        // Rebuild the parsed request as a proper DeviceRequest...
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = Simple.NULL,
            deviceRequestInfo =  DeviceRequestInfo.fromValues(
                useCases = presentmentRequests.map { pr ->
                    UseCase(
                        mandatory = pr.isMandatory,
                        documentSets = pr.documentRequestSets.map { drs ->
                            DocumentSet(
                                docRequestIds = drs.requests.map { dr -> documentRequest.indexOf(dr) }
                            )
                        },
                        purposeHints = emptyMap()
                    )
                }
            )
        ) {
            for (dr in documentRequest) {
                addDocRequest(
                    docType = dr.docType,
                    nameSpaces = dr.nameSpaces.mapValues { (namespace, dataElements) ->
                        dataElements.mapValues { (dataElement, value) ->
                            value.isRetaining
                        }
                    },
                )
            }
        }

        // ... and then just execute the request against our DocumentStore...
        return deviceRequest.execute(
            presentmentSource = source,
            keyAgreementPossible = keyAgreementPossible,
            requesterIdentities = requesterIdentities
        )
    }

    companion object {
        private const val TAG = "Iso18013Request"
    }
}