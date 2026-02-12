package org.multipaz.mdoc.transport.request

import org.multipaz.cbor.Simple
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.PresentmentSource

// Kotlin version of ISO18013MobileDocumentRequest
data class Iso18013Request(
    val presentmentRequests: List<Iso18013PresentmentRequest>
) {

    suspend fun getCredentialPresentmentData(
        source: PresentmentSource,
        keyAgreementPossible: List<EcCurve> = emptyList()
    ): CredentialPresentmentData {
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
            deviceRequestInfo =  DeviceRequestInfo(
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
            keyAgreementPossible = keyAgreementPossible
        )
    }

    companion object {
        private const val TAG = "Iso18013Request"
    }
}