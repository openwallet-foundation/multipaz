package org.multipaz.provisioning

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.Document
import org.multipaz.webtoken.buildJwt
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.util.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * This model supports UX/UI flow for provisioning of credentials.
 *
 * Only a single provisioning session per model object can be active at any time.
 *
 * @param documentProvisioningHandler object that manages document and credential creation,
 *  e.g. [DocumentProvisioningHandler].
 * @param httpClient HTTP client used to communicate to the provisioning server, it MUST NOT
 *  handle redirects automatically
 * @param promptModel [PromptModel] that is used to show prompts to generate proof-of-possession for
 *  credential keys
 * @param authorizationSecureArea secure area that is used to store session authorization keys
 *  during provisioning; when credentials are refreshed, it is important that the [SecureArea]
 *  used during refresh is the same that was used during the initial provisioning
 */
class ProvisioningModel(
    private val documentProvisioningHandler: AbstractDocumentProvisioningHandler,
    private val httpClient: HttpClient,
    private val promptModel: PromptModel,
    private val authorizationSecureArea: SecureArea
) {
    private var mutableState = MutableStateFlow<State>(Idle)

    /** State of the model */
    val state: StateFlow<State> get() = mutableState.asStateFlow()

    private val authorizationResponseChannel = Channel<AuthorizationResponse>()

    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive ?: false

    private var targetDocument: Document? = null

    /**
     * Launch provisioning session to provision credentials to a new [Document] using
     * OpenID4VCI protocol.
     *
     * @param offerUri credential offer (formatted as URI with custom protocol name)
     * @param clientPreferences configuration parameters for OpenID4VCI client
     * @param backend interface to the wallet back-end service
     * @return deferred [Document] value
     */
    fun launchOpenID4VCIProvisioning(
        offerUri: String,
        clientPreferences: OpenID4VCIClientPreferences,
        backend: OpenID4VCIBackend,
    ): Deferred<Document> =
        launch(createCoroutineContext(clientPreferences, backend)) {
            targetDocument = null
            OpenID4VCI.createClientFromOffer(offerUri, clientPreferences)
        }

    /**
     * Launch provisioning session to provision additional credentials to an existing [Document].
     *
     * @param document [Document] where credentials should be provisioned
     * @param authorizationData authorization data from a previous provisioning session (see
     *  [DocumentProvisioningHandler.AbstractDocumentMetadataHandler.updateDocumentMetadata]
     *  `authorizationData` parameter)
     * @param clientPreferences configuration parameters for OpenID4VCI client
     * @param backend interface to the wallet back-end service
     * @return deferred [Document] value, resolved when credentials are provisioned
     */
    fun launchOpenID4VCIRefreshCredentials(
        document: Document,
        authorizationData: ByteString,
        clientPreferences: OpenID4VCIClientPreferences,
        backend: OpenID4VCIBackend,
    ): Deferred<Document> =
        launch(createCoroutineContext(clientPreferences, backend)) {
            targetDocument = document
            Provisioning.createClientFromAuthorizationData(authorizationData)
        }

    /**
     * Launch provisioning session to provision credentials to a new [Document] using
     * given [ProvisioningClient] factory.
     *
     * @param coroutineContext coroutine context to run [ProvisioningClient] in
     * @param document if null, this is an initial provisioning, if not null, provision more
     *  credentials into the given document
     * @param provisioningClientFactory function that creates [ProvisioningClient]
     * @return deferred [Document] value
     */
    fun launch(
        coroutineContext: CoroutineContext,
        document: Document? = null,
        provisioningClientFactory: suspend () -> ProvisioningClient
    ): Deferred<Document> {
        if (isActive) {
            throw IllegalStateException("Existing job is active")
        }
        val deferred = CoroutineScope(coroutineContext).async {
            try {
                mutableState.emit(Initial)
                targetDocument = document
                val provisioningClient = provisioningClientFactory.invoke()
                runProvisioning(provisioningClient)
            } catch(err: CancellationException) {
                mutableState.emit(Idle)
                throw err
            } catch(err: Throwable) {
                Logger.e(TAG, "Error provisioning", err)
                mutableState.emit(Error(err))
                throw err
            }
        }
        this.job = deferred
        return deferred
    }

    /**
     * Cancel currently-running provisioning session (if any) and sets the state to [Idle].
     *
     * Note: cancellation is asynchronous and is typically not going to happen yet when this
     * function returns.
     */
    fun cancel() {
        job?.let {
            if (it.isActive) {
                it.cancel()
            } else {
                CoroutineScope(Dispatchers.Default).launch {
                    mutableState.emit(Idle)
                }
            }
        }
    }

    /**
     * Provide [AuthorizationResponse] for one of the challenges in
     * [Authorizing.authorizationChallenges].
     *
     * Provisioning will wait for this method to be called when [state] is [Authorizing].
     */
    suspend fun provideAuthorizationResponse(response: AuthorizationResponse) {
        authorizationResponseChannel.send(response)
    }

    private fun createCoroutineContext(
        clientPreferences: OpenID4VCIClientPreferences,
        backend: OpenID4VCIBackend
    ) = Dispatchers.Default + promptModel + RpcAuthClientSession() +
            ProvisioningEnvironment(clientPreferences, backend)

    private suspend fun runProvisioning(provisioningClient: ProvisioningClient): Document {
        mutableState.emit(Connected)
        val issuerMetadata = provisioningClient.getMetadata()
        val credentialConfig = issuerMetadata.credentials.values.first()

        var evidenceRequests = provisioningClient.getAuthorizationChallenges()

        while (evidenceRequests.isNotEmpty()) {
            mutableState.emit(Authorizing(evidenceRequests))
            val authorizationResponse = authorizationResponseChannel.receive()
            mutableState.emit(ProcessingAuthorization)
            provisioningClient.authorize(authorizationResponse)
            evidenceRequests = provisioningClient.getAuthorizationChallenges()
        }

        mutableState.emit(Authorized)

        val documentAuthorizationData = provisioningClient.getAuthorizationData()
        val document = targetDocument ?: run {
            documentProvisioningHandler.createDocument(
                credentialConfig,
                issuerMetadata,
                documentAuthorizationData
            )
        }
        var pendingCredentials: List<Credential> = listOf()
        try {
            // get the initial set of credentials
            val keyInfo = if (credentialConfig.keyBindingType == KeyBindingType.Keyless) {
                // keyless, no need for keys
                KeyBindingInfo.Keyless
            } else {
                // create keys in the selected secure area and send them to the issuer
                val keyChallenge = provisioningClient.getKeyBindingChallenge()
                val createKeySettings = CreateKeySettings(
                    algorithm = when (val type = credentialConfig.keyBindingType) {
                        is KeyBindingType.OpenidProofOfPossession -> type.algorithm
                        is KeyBindingType.Attestation -> type.algorithm
                        else -> throw IllegalStateException()
                    },
                    nonce = keyChallenge.encodeToByteString(),
                    userAuthenticationRequired = true
                )
                pendingCredentials = documentProvisioningHandler.createKeyBoundCredentials(
                    document,
                    credentialConfig,
                    createKeySettings
                )

                when (val keyProofType = credentialConfig.keyBindingType) {
                    is KeyBindingType.Attestation -> {
                        KeyBindingInfo.Attestation(
                            attestations = pendingCredentials.map {
                                CredentialKeyAttestation(it.identifier, it.getAttestation())
                            }
                        )
                    }
                    is KeyBindingType.OpenidProofOfPossession -> {
                        val jwtList = pendingCredentials.map {
                            openidProofOfPossession(
                                challenge = keyChallenge,
                                keyProofType = keyProofType,
                                credential = it
                            )
                        }
                        KeyBindingInfo.OpenidProofOfPossession(jwtList)
                    }
                    else -> throw IllegalStateException()
                }
            }

            mutableState.emit(RequestingCredentials)
            val credentials = provisioningClient.obtainCredentials(keyInfo)
            // If we successfully sent keys to the server, we should not unconditionally clean
            // them up on error if we are past this point.
            pendingCredentials = listOf()

            val credentialData = credentials.certifications

            if (credentialConfig.keyBindingType == KeyBindingType.Keyless) {
                if (credentialData.size != 1) {
                    throw IllegalStateException("Only a single keyless credential must be issued")
                }
                val pendingCredential = documentProvisioningHandler.createKeylessCredential(
                    document = document,
                    credentialMetadata = credentialConfig
                )
                pendingCredential.certify(credentialData.first().issuerData)
            } else {
                // Credential minting can happen offline, we can get any number of new credentials
                // here, some might have been created as pending in the previous calls to this
                // method.
                for ((credentialId, credentialData) in credentialData) {
                    val pendingCredential = document.lookupCredential(credentialId)
                    if (pendingCredential == null) {
                        Logger.e(TAG, "Credential '$credentialId' is not found")
                    } else if (pendingCredential.isCertified) {
                        Logger.e(TAG, "Credential '$credentialId' is already certified")
                    } else {
                        pendingCredential.certify(credentialData)
                    }
                }
                documentProvisioningHandler.updateDocument(
                    document = document,
                    display = credentials.display,
                    documentAuthorizationData = provisioningClient.getAuthorizationData()
                )
            }
        } catch (err: Throwable) {
            // Clean-up after failed provisioning
            if (targetDocument == null) {
                // Initial provisioning: failed
                documentProvisioningHandler.cleanupDocumentOnError(document, err)
            } else {
                // Refresh: only delete the pending credentials
                documentProvisioningHandler.cleanupCredentialsOnError(pendingCredentials, err)
            }
            throw err
        }
        mutableState.emit(CredentialsIssued)
        return document
    }

    /** Represents model's state */
    sealed class State

    /** Provisioning is not active */
    data object Idle: State()

    /** Provisioning is about to start */
    data object Initial: State()

    /** Connected to the provisioning server */
    data object Connected: State()

    /**
     * Authorizing the user.
     *
     * When in this state, [provideAuthorizationResponse] must be called to authorize user using
     * one of the methods in [authorizationChallenges], provisioning will not progress until
     * that call is made.
     *
     * @param authorizationChallenges holds non-empty list of authorization methods and data
     * necessary to use them.
     */
    data class Authorizing(
        val authorizationChallenges: List<AuthorizationChallenge>
    ): State()

    /** Authorization response is being processed */
    data object ProcessingAuthorization: State()

    /** User was successfully authorized */
    data object Authorized: State()

    /** Credentials are being requested from the provisioning server */
    data object RequestingCredentials: State()

    /** Credentials are issued, provisioning has stopped */
    data object CredentialsIssued: State()

    /** Error occurred when provisioning, provisioning has stopped */
    data class Error(
        val err: Throwable
    ): State()

    internal inner class ProvisioningEnvironment(
        val openID4VCIClientPreferences: OpenID4VCIClientPreferences,
        val openid4VciBackend: OpenID4VCIBackend
    ): BackendEnvironment {
        val secureAreaProvider = SecureAreaProvider {
            authorizationSecureArea
        }
        override fun <T : Any> getInterface(clazz: KClass<T>): T? = clazz.safeCast(
            when (clazz) {
                HttpClient::class -> httpClient
                SecureAreaProvider::class -> secureAreaProvider
                OpenID4VCIClientPreferences::class -> openID4VCIClientPreferences
                OpenID4VCIBackend::class -> openid4VciBackend
                else -> null
            }
        )
    }

    companion object {
        private const val TAG = "ProvisioningModel"

        private suspend fun openidProofOfPossession(
            challenge: String,
            keyProofType: KeyBindingType.OpenidProofOfPossession,
            credential: SecureAreaBoundCredential
        ): String {
            val signingKey = AsymmetricKey.anonymous(
                secureArea = credential.secureArea,
                alias = credential.alias,
                unlockReason = ProofOfPossessionUnlockReason
            )
            return buildJwt(
                type = "openid4vci-proof+jwt",
                key = signingKey,
                header = {
                    put("jwk", signingKey.publicKey.toJwk(buildJsonObject {
                        put("kid", credential.identifier)
                    }))
                }
            ) {
                put("iss", keyProofType.clientId)
                put("aud", keyProofType.aud)
                put("nonce", challenge)
            }
        }
    }
}