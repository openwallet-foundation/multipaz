package org.multipaz.digitalcredentials

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository

internal actual suspend fun defaultInitialize() {
}

internal actual val defaultRegisterAvailable = false

internal actual val defaultRequestAvailable = false

private val mutableAuthorizationState = MutableStateFlow(DigitalCredentialsAuthorizationState.UNKNOWN)

internal actual val defaultAuthorizationState: StateFlow<DigitalCredentialsAuthorizationState> = mutableAuthorizationState

internal actual val defaultSupportedProtocols: Set<String>
    get() = supportedProtocols

private val supportedProtocols = setOf<String>()

internal actual suspend fun defaultRegister(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>
) {
    throw NotImplementedError("DigitalCredentials is not available on JS or WasmJs")
}

internal actual suspend fun defaultRequest(request: JsonObject): JsonObject {
    throw NotImplementedError("DigitalCredentials is not available on JS or WasmJs")
}