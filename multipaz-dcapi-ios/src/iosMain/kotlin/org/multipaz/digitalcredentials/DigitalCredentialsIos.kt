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
import org.multipaz.document.getIosSdJwtVcTypes
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.util.Logger
import org.multipaz.util.toByteArray
import org.multipaz.util.toKotlinError
import org.multipaz.util.toNSData
import platform.Foundation.NSData
import platform.Foundation.NSError
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Clock

private const val TAG = "DigitalCredentials"

/**
 * An implementation of [DigitalCredentials] using iOS `IdentityDocumentServices`.
 */
class DigitalCredentialsIos private constructor() : DigitalCredentials {
    companion object {
        private var instance: DigitalCredentialsIos? = null
        private val instanceLock = Mutex()

        /**
         * Creates or returns the [DigitalCredentialsIos] instance.
         *
         * @return a [DigitalCredentialsIos] instance.
         */
        suspend fun create(): DigitalCredentialsIos {
            instanceLock.withLock {
                if (instance == null) {
                    val newInstance = DigitalCredentialsIos()
                    newInstance.initialize()
                    instance = newInstance
                }
                return instance!!
            }
        }
    }

    private suspend fun initialize() {
        mutableAuthorizationState.value = getAuthorizationState()
    }

    private val mutableAuthorizationState = MutableStateFlow(DigitalCredentialsAuthorizationState.NOT_DETERMINED)

    override val authorizationState: StateFlow<DigitalCredentialsAuthorizationState> = mutableAuthorizationState

    override val registerAvailable: Boolean = true

    override val requestAvailable: Boolean = false

    @OptIn(ExperimentalForeignApi::class)
    override val supportedProtocols: Set<String> = buildSet {
        add("org-iso-mdoc")
        if (SwiftBridge.docRegIsOpenID4VPSupported()) {
            add("openid4vp-v1-unsigned")
            add("openid4vp-v1-signed")
            add("openid4vp-v1-multisigned")
        }
    }

    private val registerLock = Mutex()

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun register(
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

    override suspend fun request(request: JsonObject): JsonObject {
        throw NotImplementedError("DigitalCredentialsIos.request is not available on iOS")
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

private data class WantedRegistration(
    val registrationId: String,
    val documentId: String,
    val displayName: String,
    val credentialFormat: String,
    val documentTypes: List<String>,
    val readerAkis: List<ByteString>,
    val issuerAkis: List<ByteString>,
)

// Called with lock held
@OptIn(ExperimentalForeignApi::class)
private suspend fun updateOsCredentialManagerUnlocked(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>,
    forceRegistration: Boolean
) {
    Logger.i(TAG, "Updating OS Credential Manager (forceRegistration=$forceRegistration)")

    val hasMdocProtocol = selectedProtocols.contains("org-iso-mdoc")
    val hasOpenID4VPProtocol = selectedProtocols.any { it.startsWith("openid4vp") } &&
        SwiftBridge.docRegIsOpenID4VPSupported()

    val iosMdocDoctypes = if (hasMdocProtocol || hasOpenID4VPProtocol) {
        val types = documentStore.getIosMdocDoctypes()?.toSet()
        if (types == null) {
            Logger.w(
                TAG,
                "documentStore.getIosMdocDoctypes() is not set. All ISO mdoc credentials will be registered. " +
                "Please use documentStore.setIosMdocDoctypes() to configure the doctypes declared in your iOS manifest."
            )
        }
        types
    } else {
        emptySet()
    }

    val iosSdJwtVcTypes = if (hasOpenID4VPProtocol) {
        val types = documentStore.getIosSdJwtVcTypes()?.toSet()
        if (types == null) {
            Logger.w(
                TAG,
                "documentStore.getIosSdJwtVcTypes() is not set. All IETF SD-JWT VC credentials will be registered. " +
                "Please use documentStore.setIosSdJwtVcTypes() to configure the vct values declared in your iOS manifest."
            )
        }
        types
    } else {
        emptySet()
    }

    // First figure out which credentials we want to be registered...
    val wantedByRegId = mutableMapOf<String, WantedRegistration>()
    if (hasMdocProtocol || hasOpenID4VPProtocol) {
        val documents = documentStore.listDocuments()
        for (document in documents) {
            val displayName = document.displayName ?: document.typeDisplayName ?: "Unnamed Document"
            val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential } as MdocCredential?
            if (mdocCredential != null) {
                if (iosMdocDoctypes == null || iosMdocDoctypes.contains(mdocCredential.docType)) {
                    val regId = "mdoc:${document.identifier}"
                    val issuerAkis = mdocCredential.issuerCertChain.certificates
                        .mapNotNull { it.authorityKeyIdentifier?.let { aki -> ByteString(aki) } }
                    wantedByRegId[regId] = WantedRegistration(
                        registrationId = regId,
                        documentId = document.identifier,
                        displayName = displayName,
                        credentialFormat = "mso_mdoc",
                        documentTypes = listOf(mdocCredential.docType),
                        readerAkis = document.readerIdentifiers,
                        issuerAkis = issuerAkis,
                    )
                }
            }
            if (hasOpenID4VPProtocol) {
                val sdJwtCredential = document.getCertifiedCredentials()
                    .find { it is SdJwtVcCredential } as SdJwtVcCredential?
                if (sdJwtCredential != null) {
                    val allVcts = listOf(sdJwtCredential.vct) + sdJwtCredential.otherVcts
                    val vctsToRegister = if (iosSdJwtVcTypes == null) {
                        allVcts
                    } else {
                        allVcts.filter { iosSdJwtVcTypes.contains(it) }
                    }
                    if (vctsToRegister.isNotEmpty()) {
                        val regId = "sdjwt:${document.identifier}"
                        val issuerAkis = sdJwtCredential.getIssuerCertChain()?.certificates
                            ?.mapNotNull { it.authorityKeyIdentifier?.let { aki -> ByteString(aki) } }
                            ?: emptyList()
                        wantedByRegId[regId] = WantedRegistration(
                            registrationId = regId,
                            documentId = document.identifier,
                            displayName = displayName,
                            credentialFormat = "dc+sd-jwt",
                            documentTypes = vctsToRegister,
                            readerAkis = document.readerIdentifiers,
                            issuerAkis = issuerAkis,
                        )
                    }
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

    val regHaveByRegId = registrationsHave.associateBy { it.documentIdentifier() }

    // ... and then calculate what we need to register and unregister
    val registrationsToAdd = mutableListOf<WantedRegistration>()
    for ((regId, wanted) in wantedByRegId) {
        if (forceRegistration) {
            registrationsToAdd.add(wanted)
            continue
        }
        val reg = regHaveByRegId[regId]
        if (reg == null) {
            registrationsToAdd.add(wanted)
            continue
        }
        if (reg.credentialFormat() != wanted.credentialFormat || reg.documentTypes() != wanted.documentTypes) {
            registrationsToAdd.add(wanted)
            continue
        }
        val registeredReaderAkis = (reg.supportedAuthorityKeyIdentifiers() as List<NSData>)
            .map { ByteString(it.toByteArray()) }
            .toSet()
        if (registeredReaderAkis != wanted.readerAkis.toSet()) {
            registrationsToAdd.add(wanted)
            continue
        }
        val registeredIssuerAkis = (reg.supportedIssuerAuthorityKeyIdentifiers() as List<NSData>)
            .map { ByteString(it.toByteArray()) }
            .toSet()
        if (registeredIssuerAkis != wanted.issuerAkis.toSet()) {
            registrationsToAdd.add(wanted)
            continue
        }
    }

    val regIdsHaveSet = regHaveByRegId.keys
    val regIdsToUnregister = regIdsHaveSet.minus(wantedByRegId.keys)

    if (registrationsToAdd.isEmpty() && regIdsToUnregister.isEmpty()) {
        Logger.i(TAG, "No changes to iOS Digital Credentials registrations")
        return
    }

    for (wanted in registrationsToAdd) {
        val readerNsData = wanted.readerAkis.map { it.toNSData() }
        val issuerNsData = wanted.issuerAkis.map { it.toNSData() }
        suspendCoroutine<Unit> { continuation ->
            val completion: (Boolean, NSError?) -> Unit = { _, error ->
                // Matching on the error like this is a little bit of a hack but it does work...
                if (error != null) {
                    if (error.domain.toString() == "IdentityDocumentServices.IdentityDocumentProviderRegistrationStore.RegistrationError" &&
                        error.code.toInt() == 2) {
                        Logger.w(
                            TAG,
                            "Ignoring registration error .noAuth for ${wanted.credentialFormat} credential " +
                                "with types ${wanted.documentTypes} - did you add it to the entitlement file?"
                        )
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("Credential registration failed", error.toKotlinError())
                        )
                    }
                } else {
                    Logger.i(
                        TAG,
                        "Registered ${wanted.credentialFormat} credential for docId ${wanted.documentId} " +
                            "('${wanted.displayName}') with types ${wanted.documentTypes}"
                    )
                    continuation.resume(Unit)
                }
            }
            if (wanted.credentialFormat == "mso_mdoc") {
                SwiftBridge.docRegAdd(
                    wanted.registrationId,
                    wanted.documentTypes.first(),
                    readerNsData,
                    issuerNsData,
                    null,
                    completion
                )
            } else {
                SwiftBridge.docRegAddVerifiableCredential(
                    wanted.registrationId,
                    wanted.documentTypes,
                    readerNsData,
                    issuerNsData,
                    null,
                    completion
                )
            }
        }
    }

    for (regId in regIdsToUnregister) {
        val docId = regId.substringAfter(":", regId)
        val document = documentStore.lookupDocument(docId)
        val displayName = document?.displayName ?: document?.typeDisplayName ?: "Unnamed Document"
        suspendCoroutine<Unit> { continuation ->
            SwiftBridge.docRegRemove(
                regId
            ) { _, error ->
                if (error != null) {
                    continuation.resumeWithException(
                        IllegalStateException("Credential unregistration failed", error.toKotlinError())
                    )
                } else {
                    Logger.i(TAG, "Unregistered credential $regId ('$displayName')")
                    continuation.resume(Unit)
                }
            }
        }
    }
}
}


