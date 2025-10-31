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
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.put
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.AbstractDocumentMetadata
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.jwt.buildJwt
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.util.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.reflect.safeCast
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * This model supports UX/UI flow for provisioning of credentials.
 *
 * Only a single provisioning session per model object can be active at any time.
 *
 * @param documentStore new [Document] will be created in this [DocumentStore]
 * @param secureArea [Credential] objects will be bound to this [SecureArea]
 * @param httpClient HTTP client used to communicate to the provisioning server, it MUST NOT
 *     handle redirects automatically
 * @param promptModel [PromptModel] that is used to show prompts to generate proof-of-possession for
 *     credential keys
 * @param documentMetadataInitializer function that initializes document metadata at [Document]
 *     creation time based on issuance server metadata
 */
class ProvisioningModel(
    private val documentStore: DocumentStore,
    private val secureArea: SecureArea,
    private val httpClient: HttpClient,
    private val promptModel: PromptModel,
    private val documentMetadataInitializer: suspend (
            metadata: AbstractDocumentMetadata,
            credentialDisplay: Display,
            issuerDisplay: Display
        ) -> Unit
) {
    private var mutableState = MutableStateFlow<State>(Idle)

    /** State of the model */
    val state: StateFlow<State> get() = mutableState.asStateFlow()

    private val authorizationResponseChannel = Channel<AuthorizationResponse>()

    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive ?: false

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
        launch(Dispatchers.Default + promptModel + RpcAuthClientSession() + ProvisioningEnvironment(backend)) {
            OpenID4VCI.createClientFromOffer(offerUri, clientPreferences)
        }

    /**
     * Launch provisioning session to provision credentials to a new [Document] using
     * given [ProvisioningClient] factory.
     *
     * @param coroutineContext coroutine context to run [ProvisioningClient] in
     * @param provisioningClientFactory function that creates [ProvisioningClient]
     * @return deferred [Document] value
     */
    fun launch(
        coroutineContext: CoroutineContext,
        provisioningClientFactory: suspend () -> ProvisioningClient
    ): Deferred<Document> {
        if (isActive) {
            throw IllegalStateException("Existing job is active")
        }
        val deferred = CoroutineScope(coroutineContext).async {
            try {
                mutableState.emit(Initial)
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

    private suspend fun runProvisioning(provisioningClient: ProvisioningClient): Document {
        mutableState.emit(Connected)
        val issuerMetadata = provisioningClient.getMetadata()
        val credentialMetadata = issuerMetadata.credentials.values.first()

        var evidenceRequests = provisioningClient.getAuthorizationChallenges()

        while (evidenceRequests.isNotEmpty()) {
            mutableState.emit(Authorizing(evidenceRequests))
            val authorizationResponse = authorizationResponseChannel.receive()
            mutableState.emit(ProcessingAuthorization)
            provisioningClient.authorize(authorizationResponse)
            evidenceRequests = provisioningClient.getAuthorizationChallenges()
        }

        mutableState.emit(Authorized)

        val format = credentialMetadata.format
        val document = documentStore.createDocument { metadata ->
            documentMetadataInitializer(
                metadata,
                credentialMetadata.display,
                issuerMetadata.display
            )
        }
        try {
            val credentialCount = min(credentialMetadata.maxBatchSize, 3)

            var pendingCredentials: List<Credential>

            // get the initial set of credentials
            val keyInfo = if (credentialMetadata.keyBindingType == KeyBindingType.Keyless) {
                // keyless, no need for keys
                pendingCredentials = listOf()
                KeyBindingInfo.Keyless
            } else {
                // create keys in the selected secure area and send them to the issuer
                val keyChallenge = provisioningClient.getKeyBindingChallenge()
                val createKeySettings = CreateKeySettings(
                    algorithm = when (val type = credentialMetadata.keyBindingType) {
                        is KeyBindingType.OpenidProofOfPossession -> type.algorithm
                        is KeyBindingType.Attestation -> type.algorithm
                        else -> throw IllegalStateException()
                    },
                    nonce = keyChallenge.encodeToByteString(),
                    userAuthenticationRequired = true
                )
                when (format) {
                    is CredentialFormat.Mdoc -> {
                        pendingCredentials = (0..<credentialCount).map {
                            MdocCredential.create(
                                document = document,
                                asReplacementForIdentifier = null,
                                domain = CREDENTIAL_DOMAIN_MDOC,
                                secureArea = secureArea,
                                docType = format.docType,
                                createKeySettings = createKeySettings
                            )
                        }
                    }

                    is CredentialFormat.SdJwt -> {
                        pendingCredentials = (0..<credentialCount).map {
                            KeyBoundSdJwtVcCredential.create(
                                document = document,
                                asReplacementForIdentifier = null,
                                domain = CREDENTIAL_DOMAIN_SD_JWT_VC,
                                secureArea = secureArea,
                                vct = format.vct,
                                createKeySettings = createKeySettings
                            )
                        }
                    }
                }

                when (val keyProofType = credentialMetadata.keyBindingType) {
                    is KeyBindingType.Attestation -> {
                        KeyBindingInfo.Attestation(pendingCredentials.map { it.getAttestation() })
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
                    // TODO: proof of possession
                    else -> throw IllegalStateException()
                }
            }

            mutableState.emit(RequestingCredentials)
            val credentials = provisioningClient.obtainCredentials(keyInfo)

            if (credentialMetadata.keyBindingType == KeyBindingType.Keyless) {
                if (credentials.size != 1) {
                    throw IllegalStateException("Only a single keyless credential must be issued")
                }
                pendingCredentials = listOf(
                    KeylessSdJwtVcCredential.create(
                        document,
                        null,
                        CREDENTIAL_DOMAIN_SD_JWT_VC_KEYLESS,
                        (format as CredentialFormat.SdJwt).vct
                    )
                )
            }
            for ((credentialData, pendingCredential) in credentials.zip(pendingCredentials)) {
                // TODO: remove validity parameters, extract them from the credentialData
                pendingCredential.certify(
                    credentialData.toByteArray(),
                    Clock.System.now(),
                    Clock.System.now() + 30.days
                )
            }
        } catch (err: Throwable) {
            documentStore.deleteDocument(document.identifier)
            throw err
        }
        document.metadata.markAsProvisioned()
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
        val openid4VciBackend: OpenID4VCIBackend
    ): BackendEnvironment {
        val secureAreaProvider = SecureAreaProvider { secureArea }
        override fun <T : Any> getInterface(clazz: KClass<T>): T? = clazz.safeCast(
            when (clazz) {
                HttpClient::class -> httpClient
                SecureAreaProvider::class -> secureAreaProvider
                OpenID4VCIBackend::class -> openid4VciBackend
                else -> null
            }
        )
    }

    companion object {
        private const val CREDENTIAL_DOMAIN_MDOC = "mdoc_user_auth"
        private const val CREDENTIAL_DOMAIN_SD_JWT_VC = "sdjwt_user_auth"
        private const val CREDENTIAL_DOMAIN_SD_JWT_VC_KEYLESS = "sdjwt_keyless"

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
                    put("jwk", signingKey.publicKey.toJwk())
                }
            ) {
                put("iss", keyProofType.clientId)
                put("aud", keyProofType.aud)
                put("nonce", challenge)
            }
        }
    }
}