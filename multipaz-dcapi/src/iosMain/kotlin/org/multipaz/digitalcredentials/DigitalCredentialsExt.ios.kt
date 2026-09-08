package org.multipaz.digitalcredentials

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toByteString
import kotlinx.io.bytestring.toNSData
import kotlinx.serialization.json.JsonObject
import org.multipaz.DocRegInfo
import org.multipaz.SwiftBridge
import org.multipaz.document.DocumentStore
import org.multipaz.document.getIosMdocDoctypes
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.util.Logger
import org.multipaz.util.toByteArray
import org.multipaz.util.toKotlinError
import org.multipaz.util.toNSData
import platform.Foundation.NSData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Clock

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
    selectedProtocols: Set<String>,
    forceRegistration: Boolean
) {
    require(supportedProtocols.containsAll(selectedProtocols)) {
        "The selected protocols is not a subset of supported protocols"
    }
    registerLock.withLock {
        updateOsCredentialManagerUnlocked(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            selectedProtocols = selectedProtocols,
            forceRegistration = forceRegistration
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
    selectedProtocols: Set<String>,
    forceRegistration: Boolean
) {
    Logger.i(TAG, "Updating OS Credential Manager (forceRegistration=$forceRegistration)")

    val iosMdocDoctypes = documentStore.getIosMdocDoctypes()?.toSet()
    if (iosMdocDoctypes == null) {
        Logger.w(
            TAG,
            "documentStore.getIosMdocDoctypes() is not set. All ISO mdoc credentials will be registered. " +
            "Please use documentStore.setIosMdocDoctypes() to configure the doctypes declared in your iOS manifest."
        )
    }

    // First figure out which documents we want to be registered...
    val docIdsWant = mutableSetOf<String>()
    if (selectedProtocols.contains("org-iso-mdoc")) {
        val documents = documentStore.listDocuments()
        for (document in documents) {
            val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential } as MdocCredential?
            if (mdocCredential != null) {
                if (iosMdocDoctypes == null || iosMdocDoctypes.contains(mdocCredential.docType)) {
                    docIdsWant.add(document.identifier)
                }
            }
        }
    }

    val authorizationState = getAuthorizationState()
    mutableAuthorizationState.value = authorizationState

    if (authorizationState == DigitalCredentialsAuthorizationState.NOT_AUTHORIZED) {
        Logger.w(TAG, "Status is notAuthorized, not updating OS Credential Manager")
        return
    }

    val timeStart = Clock.System.now()
    val registrationsHave = suspendCoroutine<List<DocRegInfo>> { continuation ->
        SwiftBridge.docRegGetAll { registrations, error ->
            if (error != null) {
                continuation.resumeWithException(
                    IllegalStateException("Error getting registered documents", error.toKotlinError())
                )
            } else {
                continuation.resume((registrations as List<DocRegInfo>?) ?: emptyList())
            }
        }
    }
    val timeEnd = Clock.System.now()
    val durationMs = (timeEnd - timeStart).inWholeMilliseconds
    Logger.i(TAG, "Fetched ${registrationsHave.size} OS registrations in $durationMs ms")

    val regHaveByDocId = registrationsHave.associateBy { it.documentIdentifier() }

    // ... and then calculate what we need to register and unregister
    val docIdsToRegister = mutableSetOf<String>()
    for (docId in docIdsWant) {
        if (forceRegistration) {
            docIdsToRegister.add(docId)
            continue
        }
        val reg = regHaveByDocId[docId]
        if (reg == null) {
            docIdsToRegister.add(docId)
            continue
        }
        val document = documentStore.lookupDocument(docId)
        if (document == null) {
            docIdsToRegister.add(docId)
            continue
        }
        val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential } as MdocCredential?
        if (mdocCredential == null) {
            docIdsToRegister.add(docId)
            continue
        }
        if (reg.documentType() != mdocCredential.docType) {
            docIdsToRegister.add(docId)
            continue
        }
        val registeredReaderAkis = (reg.supportedAuthorityKeyIdentifiers() as List<NSData>)
            .map { ByteString(it.toByteArray()) }
            .toSet()
        val wantedReaderAkis = document.readerIdentifiers.toSet()
        if (registeredReaderAkis != wantedReaderAkis) {
            docIdsToRegister.add(docId)
            continue
        }
        val registeredIssuerAkis = (reg.supportedIssuerAuthorityKeyIdentifiers() as List<NSData>)
            .map { ByteString(it.toByteArray()) }
            .toSet()
        val wantedIssuerAkis = mdocCredential.issuerCertChain.certificates
            .mapNotNull { it.authorityKeyIdentifier?.let { aki -> ByteString(aki) } }
            .toSet()
        if (registeredIssuerAkis.isNotEmpty() && registeredIssuerAkis != wantedIssuerAkis) {
            docIdsToRegister.add(docId)
            continue
        }
    }

    val docIdsHaveSet = regHaveByDocId.keys
    val docIdsToUnregister = docIdsHaveSet.minus(docIdsWant)

    if (docIdsToRegister.isEmpty() && docIdsToUnregister.isEmpty()) {
        Logger.i(TAG, "No changes to iOS Digital Credentials registrations")
        return
    }

    for (docId in docIdsToRegister) {
        val document = documentStore.lookupDocument(docId)
        if (document == null) {
            Logger.w(TAG, "Error finding document for documentId $docId")
            continue
        }
        val displayName = document.displayName ?: document.typeDisplayName ?: "Unnamed Document"
        val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential } as MdocCredential?
        if (mdocCredential != null) {
            val issuerIdentifiers = mdocCredential.issuerCertChain.certificates
                .mapNotNull { it.authorityKeyIdentifier }
            suspendCoroutine<Unit> { continuation ->
                SwiftBridge.docRegAdd(
                    document.identifier,
                    mdocCredential.docType,
                    document.readerIdentifiers.map { it.toNSData() },
                    issuerIdentifiers.map { it.toNSData() },
                    null
                ) { success, error ->
                    // Matching on the error like this is a little bit of a hack but it does work...
                    if (error != null) {
                        if (error.domain.toString() == "IdentityDocumentServices.IdentityDocumentProviderRegistrationStore.RegistrationError" &&
                            error.code.toInt() == 2) {
                            Logger.w(TAG, "Ignoring registration error .noAuth for credential " +
                                    "with docType ${mdocCredential.docType} - did you add it to the entitlement file?")
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("Credential registration failed", error.toKotlinError())
                            )
                        }
                    } else {
                        Logger.i(
                            TAG, "Registered document with docId ${document.identifier} ('$displayName')" +
                                    " and docType ${mdocCredential.docType}"
                        )
                        continuation.resume(Unit)
                    }
                }
            }
        }
    }

    for (docId in docIdsToUnregister) {
        val document = documentStore.lookupDocument(docId)
        val displayName = document?.displayName ?: document?.typeDisplayName ?: "Unnamed Document"
        suspendCoroutine<Unit> { continuation ->
            SwiftBridge.docRegRemove(
                docId
            ) { success, error ->
                // Matching on the error like this is a little bit of a hack but it does work...
                if (error != null) {
                    continuation.resumeWithException(
                        IllegalStateException("Credential registration failed", error.toKotlinError())
                    )
                } else {
                    Logger.i(TAG, "Unregistered document with docId $docId ('$displayName')")
                    continuation.resume(Unit)
                }
            }
        }
    }
}

internal actual suspend fun defaultRequest(request: JsonObject): JsonObject {
    throw NotImplementedError("DigitalCredentials.defaultRequest is not available on iOS")
}

