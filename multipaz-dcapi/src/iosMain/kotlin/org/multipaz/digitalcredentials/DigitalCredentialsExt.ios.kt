package org.multipaz.digitalcredentials

import kotlinx.serialization.json.JsonObject
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository

internal actual val defaultAvailable = false

internal actual val defaultSupportedProtocols: Set<String>
    get() = supportedProtocols

internal actual val defaultSelectedProtocols: Set<String>
    get() = supportedProtocols

private val supportedProtocols = setOf<String>()

internal actual suspend fun defaultSetSelectedProtocols(
    protocols: Set<String>
) {
    throw IllegalStateException("Not supported on iOS")
}

internal actual suspend fun defaultStartExportingCredentials(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository
) {
    throw NotImplementedError("DigitalCredentials is not available on iOS")
}

internal actual suspend fun defaultStopExportingCredentials(
    documentStore: DocumentStore,
) {
    throw NotImplementedError("DigitalCredentials is not available on iOS")
}

internal actual suspend fun defaultRequest(request: JsonObject): JsonObject {
    throw NotImplementedError("DigitalCredentials is not available on iOS")
}