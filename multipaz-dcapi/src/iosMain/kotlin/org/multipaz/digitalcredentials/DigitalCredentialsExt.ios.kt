package org.multipaz.digitalcredentials

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.multipaz.SwiftBridge
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.toKotlinError
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "DigitalCredentials"

internal actual suspend fun defaultInitialize() {
    mutableAuthorizationState.value = getAuthorizationState()
}

private val mutableAuthorizationState = MutableStateFlow(DigitalCredentialsAuthorizationState.NOT_DETERMINED)

internal actual val defaultAuthorizationState: StateFlow<DigitalCredentialsAuthorizationState> = mutableAuthorizationState

internal actual val defaultRegisterAvailable = true

internal actual val defaultRequestAvailable = false

internal actual val defaultSupportedProtocols: Set<String>
    get() = supportedProtocols

private val supportedProtocols = setOf(
    "org-iso-mdoc",
)


private val registerLock = Mutex()

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun defaultRegister(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>
) {
    require(supportedProtocols.containsAll(selectedProtocols)) {
        "The selected protocols is not a subset of supported protocols"
    }
    registerLock.withLock {
        updateOsCredentialManagerUnlocked(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            selectedProtocols = selectedProtocols
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun getAuthorizationState(): DigitalCredentialsAuthorizationState {
    val status = suspendCoroutine<String> { continuation ->
        SwiftBridge.docRegGetStatus { status ->
            continuation.resume(status!!)
        }
    }
    return when (status) {
        "authorized" -> DigitalCredentialsAuthorizationState.AUTHORIZED
        "notAuthorized" -> DigitalCredentialsAuthorizationState.NOT_AUTHORIZED
        "notDetermined" -> DigitalCredentialsAuthorizationState.NOT_DETERMINED
        else -> DigitalCredentialsAuthorizationState.UNKNOWN
    }
}

// Called with lock held
@OptIn(ExperimentalForeignApi::class)
private suspend fun updateOsCredentialManagerUnlocked(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>
) {
    Logger.i(TAG, "Updating OS Credential Manager")

    val authorizationState = getAuthorizationState()
    mutableAuthorizationState.value = getAuthorizationState()

    if (authorizationState == DigitalCredentialsAuthorizationState.NOT_AUTHORIZED) {
        Logger.w(TAG, "Status is notAuthorized, not updating OS Credential Manager")
        return
    }

    // First figure out which documents we want to be registered...
    val docIdsWant = mutableSetOf<String>()
    val documents = documentStore.listDocuments()
    for (document in documents) {
        val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential } as MdocCredential?
        if (mdocCredential != null) {
            docIdsWant.add(document.identifier)
        }
    }
    if (!selectedProtocols.contains("org-iso-mdoc")) {
        docIdsWant.clear()
    }

    // ... and which ones we already registered...
    val docIdsHave = if (authorizationState == DigitalCredentialsAuthorizationState.AUTHORIZED) {
        suspendCoroutine<Set<String>> { continuation ->
            SwiftBridge.docRegGetAll { docIds, error ->
                if (error != null) {
                    continuation.resumeWithException(
                        Error("Error getting registered document ids", error.toKotlinError())
                    )
                } else {
                    continuation.resume((docIds as List<String>).toSet())
                }
            }
        }
    } else emptySet()

    // ... and then calculate what we need to register and unregister
    val docIdsToRegister = docIdsWant.minus(docIdsHave)
    val docIdsToUnregister = docIdsHave.minus(docIdsWant)

    for (docId in docIdsToRegister) {
        val document = documentStore.lookupDocument(docId)
        if (document == null) {
            Logger.w(TAG, "Error finding document for documentId $docId")
            continue
        }
        val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential } as MdocCredential?
        if (mdocCredential != null) {
            val success = suspendCoroutine<Boolean> { continuation ->
                SwiftBridge.docRegAdd(
                    document.identifier,
                    mdocCredential.docType
                ) { success, error ->
                    // Matching on the error like this is a little bit of a hack but it does work...
                    if (error != null) {
                        if (error.domain.toString() == "IdentityDocumentServices.IdentityDocumentProviderRegistrationStore.RegistrationError" &&
                            error.code.toInt() == 2) {
                            Logger.w(TAG, "Ignoring registration error .noAuth for credential " +
                                    "with docType ${mdocCredential.docType} - did you add it to the entitlement file?")
                            continuation.resume(true)
                        } else {
                            continuation.resumeWithException(
                                Error("Credential registration failed", error.toKotlinError())
                            )
                        }
                    } else {
                        Logger.i(
                            TAG, "Registered document with docId ${document.identifier}" +
                                    " and docType ${mdocCredential.docType}"
                        )
                        continuation.resume(true)
                    }
                }
            }
        }
    }

    for (docId in docIdsToUnregister) {
        val success = suspendCoroutine<Boolean> { continuation ->
            SwiftBridge.docRegRemove(
                docId
            ) { success, error ->
                // Matching on the error like this is a little bit of a hack but it does work...
                if (error != null) {
                    continuation.resumeWithException(
                        Error("Credential registration failed", error.toKotlinError())
                    )
                } else {
                    Logger.i(TAG, "Unregistered document with docId $docId")
                    continuation.resume(true)
                }
            }
        }
    }
}

internal actual suspend fun defaultRequest(request: JsonObject): JsonObject {
    throw NotImplementedError("DigitalCredentials.defaultRequest is not available on iOS")
}

