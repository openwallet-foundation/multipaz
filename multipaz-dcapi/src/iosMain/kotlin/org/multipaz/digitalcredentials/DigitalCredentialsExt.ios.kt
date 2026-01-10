package org.multipaz.digitalcredentials

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.multipaz.SwiftBridge
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.util.Logger
import org.multipaz.util.toKotlinError
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

private const val TAG = "DigitalCredentials"

internal actual val defaultAvailable = true

internal actual val defaultSupportedProtocols: Set<String>
    get() = supportedProtocols

private val supportedProtocols = setOf(
    "org-iso-mdoc",
)

internal actual val defaultSelectedProtocols: Set<String>
    get() = selectedProtocols

private var selectedProtocols = supportedProtocols

internal actual suspend fun defaultSetSelectedProtocols(
    protocols: Set<String>
) {
    selectedProtocols = protocols.mapNotNull {
        if (supportedProtocols.contains(it)) {
            it
        } else {
            Logger.w(TAG, "Protocol $it is not supported")
            null
        }
    }.toSet()
}

private class RegistrationData (
    val documentStore: DocumentStore,
    val documentTypeRepository: DocumentTypeRepository,
    val listeningJob: Job,
)

private val exportedStores = mutableMapOf<DocumentStore, RegistrationData>()


@OptIn(ExperimentalForeignApi::class, FlowPreview::class)
internal actual suspend fun defaultStartExportingCredentials(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository
) {
    val listeningJob = CoroutineScope(Dispatchers.Default).launch {
        documentStore.eventFlow
            .onEach { event ->
                Logger.i(TAG, "DocumentStore event ${event::class.simpleName} ${event.documentId}")
                try {
                    updateOsCredentialManager()
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    Logger.w(TAG, "Exception while updating OS Credential Manager", e)
                    e.printStackTrace()
                }
            }
    }
    exportedStores.put(documentStore, RegistrationData(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository,
        listeningJob = listeningJob,
    ))
    updateOsCredentialManager()

    // To avoid continually updating OsCredentialProvider when documents are added one after the other, sample
    // only every 10 seconds.
    documentStore.eventFlow
        .sample(10.seconds)
        .onEach { event ->
            Logger.i(TAG, "DocumentStore event ${event::class.simpleName} ${event.documentId}")
            updateOsCredentialManager()
        }
        .launchIn(CoroutineScope(Dispatchers.Default))

}

internal actual suspend fun defaultStopExportingCredentials(
    documentStore: DocumentStore,
) {
    val registrationData = exportedStores.remove(documentStore)
    if (registrationData == null) {
        return
    }
    registrationData.listeningJob.cancel()
    updateOsCredentialManager()
}


@OptIn(ExperimentalForeignApi::class)
private suspend fun updateOsCredentialManager() {
    Logger.i(TAG, "Updating OS Credential Manager")
    val numRemoved = suspendCoroutine<Int> { continuation ->
        SwiftBridge.docRegRemoveAll() { numRemoved, error ->
            if (error != null) {
                continuation.resumeWithException(
                    Error("Removal of all credentials failed", error.toKotlinError())
                )
            } else {
                continuation.resume(numRemoved.toInt())
            }
        }
    }
    Logger.i(TAG, "Unregistered $numRemoved existing credentials")

    for (registrationData in exportedStores.values) {
        val documents = registrationData.documentStore.listDocuments()
        Logger.i(TAG, "numDoc: ${documents.size}")
        for (document in documents) {
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
    }
}

internal actual suspend fun defaultRequest(request: JsonObject): JsonObject {
    throw NotImplementedError("DigitalCredentials.defaultRequest is not available on iOS")
}

