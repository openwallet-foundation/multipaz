package org.multipaz.digitalcredentials

import kotlinx.serialization.json.JsonObject
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.verification.VerificationUtil
import org.multipaz.verification.VerifiedPresentation

/**
 * An interface for interacting with the W3C Digital Credentials API provider
 * on the platform, if available.
 */
interface DigitalCredentials {

    /**
     * Whether this API is available on the platform.
     */
    val available: Boolean

    /**
     * The set of W3C Digital Credentials protocols supported.
     */
    val supportedProtocols: Set<String>

    /**
     * The set of W3C Digital Credentials protocols currently selected.
     *
     * The default value for this is [supportedProtocols] but this may be changed using
     * [setSelectedProtocols] if supported by the platform.
     */
    val selectedProtocols: Set<String>

    /**
     * Sets the supported W3C Digital Credentials protocols, in order of preference.
     *
     * @param protocols the set of selected W3C protocols, must be a subset of [supportedProtocols].
     * @throws IllegalStateException if the platform doesn't allow configuring which protocols
     *   to export credentials on.
     */
    suspend fun setSelectedProtocols(protocols: Set<String>)

    /**
     * Registers all documents in the given [DocumentStore] with the platform.
     *
     * This also watches the store and updates the registration as documents and credentials
     * are added and removed.
     *
     * @param documentStore the [DocumentStore] to export credentials from.
     * @param documentTypeRepository a [DocumentTypeRepository].
     */
    suspend fun startExportingCredentials(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository
    )

    /**
     * Stops exporting documents.
     *
     * All documents from the given store are unregistered with the platform.
     *
     * @param documentStore the [DocumentStore] passed to [startExportingCredentials]
     */
    suspend fun stopExportingCredentials(documentStore: DocumentStore)

    /**
     * Request credentials from wallet applications.
     *
     * This is a wrapper for a native implementation of the
     * [W3C Digital Credentials API](https://www.w3.org/TR/digital-credentials/)
     * available in web browsers via `navigator.credentials.get()`. This may not be available
     * on all platforms.
     *
     * This will trigger external components for the user to interact with so make sure to launch
     * this from a coroutine which is properly bound to the UI, see [org.multipaz.context.UiContext]
     * for details.
     *
     * Use [VerificationUtil.generateDcRequestMdoc] or [VerificationUtil.generateDcRequestSdJwt]
     * to generate requests and use [VerificationUtil.decryptDcResponse] to decrypt the response.
     * Once decrypted [VerificationUtil.verifyMdocDeviceResponse],
     * [VerificationUtil.verifyOpenID4VPResponse] can be used to generate [VerifiedPresentation]
     * instances for further checks and analysis.
     *
     * @param request a W3C Digital Credentials request.
     * @return the W3C Digital Credentials response.
     */
    suspend fun request(request: JsonObject): JsonObject

    companion object
}