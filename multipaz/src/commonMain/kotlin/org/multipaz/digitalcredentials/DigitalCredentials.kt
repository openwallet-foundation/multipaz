package org.multipaz.digitalcredentials

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.verification.VerificationUtil
import org.multipaz.verification.VerifiedPresentation
import kotlin.coroutines.cancellation.CancellationException

/**
 * An interface for interacting with the W3C Digital Credentials API provider on the platform.
 *
 * Use [DigitalCredentials.Companion.getDefault] from the `multipaz-dcapi` library to obtain an instance.
 */
interface DigitalCredentials {
    /**
     * Whether the [register] method is available on the platform.
     */
    val registerAvailable: Boolean

    /**
     * Whether the [request] method is available on the platform.
     */
    val requestAvailable: Boolean

    /**
     * Whether the application is authorized to use the [register] method.
     */
    val authorizationState: StateFlow<DigitalCredentialsAuthorizationState>

    /**
     * The set of W3C Digital Credentials protocols supported by the [register] method.
     */
    val supportedProtocols: Set<String>

    /**
     * Registers all documents in [documentStore] and removes registrations for previously
     * registered documents no longer in [documentStore].
     *
     * @param documentStore the [DocumentStore] to export credentials from.
     * @param documentTypeRepository a [DocumentTypeRepository].
     * @param selectedProtocols the set of selected W3C protocols, must be a subset of [supportedProtocols].
     * @throws IllegalArgumentException if [selectedProtocols] isn't a subset of [supportedProtocols]
     * @throws IllegalStateException if an error occurs during registration.
     * @throws NotImplementedError if not implemented by the platform (e.g. if [registerAvailable] is `false`).
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class, NotImplementedError::class, CancellationException::class)
    suspend fun register(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        selectedProtocols: Set<String> = supportedProtocols
    )

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
     * @throws IllegalStateException if an error occurs during the request.
     * @throws NotImplementedError if not implemented by the platform (e.g. if [requestAvailable] is `false`).
     */
    @Throws(IllegalStateException::class, NotImplementedError::class, CancellationException::class)
    suspend fun request(request: JsonObject): JsonObject

    companion object
}
