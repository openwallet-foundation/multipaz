package org.multipaz.mdoc.transport.request

// Kotlin version of ISO18013MobileDocumentRequest.DocumentRequest
data class Iso18013DocumentRequest(
    val docType: String,
    val nameSpaces: Map<String, Map<String, Iso18013ElementInfo>>
)
