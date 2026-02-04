package org.multipaz.digitalcredentials

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository

private var default: DigitalCredentialsImpl? = null
private val defaultLock = Mutex()

/**
 * Gets the default implementation of the [DigitalCredentials] API on the platform.
 *
 * @return A [DigitalCredentials] implementation.
 */
suspend fun DigitalCredentials.Companion.getDefault(): DigitalCredentials {
    defaultLock.withLock {
        if (default != null) {
            return default!!
        }
        val defaultImpl = DigitalCredentialsImpl()
        defaultImpl.initialize()
        default = defaultImpl
        return default!!
    }
}

internal class DigitalCredentialsImpl : DigitalCredentials {
    suspend fun initialize() {
        defaultInitialize()
    }

    override val registerAvailable: Boolean
        get() = defaultRegisterAvailable

    override val requestAvailable: Boolean
        get() = defaultRequestAvailable

    override val authorizationState: StateFlow<DigitalCredentialsAuthorizationState>
        get() = defaultAuthorizationState

    override val supportedProtocols: Set<String>
        get() = defaultSupportedProtocols

    override suspend fun register(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        selectedProtocols: Set<String>
    ) = defaultRegister(documentStore, documentTypeRepository, selectedProtocols)

    override suspend fun request(request: JsonObject): JsonObject = defaultRequest(request)
}

internal expect suspend fun defaultInitialize()

internal expect val defaultRegisterAvailable: Boolean

internal expect val defaultRequestAvailable: Boolean

internal expect val defaultAuthorizationState: StateFlow<DigitalCredentialsAuthorizationState>

internal expect val defaultSupportedProtocols: Set<String>

internal expect suspend fun defaultRegister(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>
)

internal expect suspend fun defaultRequest(
    request: JsonObject
): JsonObject
