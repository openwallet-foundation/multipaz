package org.multipaz.mdoc.transport.request

// Kotlin version of ISO18013MobileDocumentRequest.DocumentRequestSet
data class Iso18013DocumentRequestSet(
    val requests: List<Iso18013DocumentRequest>
)