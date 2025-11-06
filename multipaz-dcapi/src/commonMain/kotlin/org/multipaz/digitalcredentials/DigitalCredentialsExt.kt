package org.multipaz.digitalcredentials

import kotlinx.serialization.json.JsonObject
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository

/**
 * The default implementation of the [DigitalCredentials] API on the platform.
 */
val DigitalCredentials.Companion.Default: DigitalCredentials
    get() = DigitalCredentialsImpl

private object DigitalCredentialsImpl : DigitalCredentials {
    override val available: Boolean
        get() = defaultAvailable

    override val supportedProtocols: Set<String>
        get() = defaultSupportedProtocols

    override val selectedProtocols: Set<String>
        get() = defaultSelectedProtocols

    override suspend fun setSelectedProtocols(
        protocols: Set<String>
    ) = defaultSetSelectedProtocols(protocols)

    override suspend fun startExportingCredentials(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository
    ) = defaultStartExportingCredentials(documentStore, documentTypeRepository)

    override suspend fun stopExportingCredentials(
        documentStore: DocumentStore
    ) = defaultStopExportingCredentials(documentStore)

    override suspend fun request(request: JsonObject): JsonObject = defaultRequest(request)
}

internal expect val defaultAvailable: Boolean

internal expect val defaultSupportedProtocols: Set<String>

internal expect val defaultSelectedProtocols: Set<String>

internal expect suspend fun defaultSetSelectedProtocols(
    protocols: Set<String>
)

internal expect suspend fun defaultStartExportingCredentials(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository
)

internal expect suspend fun defaultStopExportingCredentials(
    documentStore: DocumentStore,
)

internal expect suspend fun defaultRequest(
    request: JsonObject
): JsonObject