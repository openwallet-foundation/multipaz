package org.multipaz.mdoc.transport.request

import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.request.MdocRequest
import org.multipaz.request.Requester

// Kotlin version of ISO18013MobileDocumentRequest.DocumentRequest
data class Iso18013DocumentRequest(
    val docType: String,
    val nameSpaces: Map<String, Map<String, Iso18013ElementInfo>>
) {

    internal fun toMdocRequest(
        documentTypeRepository: DocumentTypeRepository,
        mdocCredential: MdocCredential?,
        requesterAppId: String? = null,
        requesterOrigin: String? = null,
    ): MdocRequest {

        val requestedData = mutableMapOf<String, MutableList<Pair<String, Boolean>>>()
        for ((namespace, dataElementMap) in nameSpaces) {
            for ((dataElement, elementInfo) in dataElementMap) {
                requestedData.getOrPut(namespace) { mutableListOf() }
                    .add(Pair(dataElement, elementInfo.isRetaining))
            }
        }
        return MdocRequest(
            requester = Requester(
                certChain = null,   // TODO
                appId = requesterAppId,
                origin = requesterOrigin
            ),
            requestedClaims = MdocUtil.generateRequestedClaims(
                docType,
                requestedData,
                documentTypeRepository,
                mdocCredential
            ),
            docType = docType,
            zkSystemSpecs = emptyList()
        )
    }
}
