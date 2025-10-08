package org.multipaz.mdoc.transport.request

// Kotlin version of ISO18013MobileDocumentRequest.PresentmentRequest
data class Iso18013PresentmentRequest(
    val documentRequestSets: List<Iso18013DocumentRequestSet>,
    val isMandatory: Boolean
)
