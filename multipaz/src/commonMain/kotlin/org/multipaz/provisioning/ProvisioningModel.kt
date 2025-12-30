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
import org.multipaz.document.AbstractDocumentMetadata
import org.multipaz.document.Document
import org.multipaz.document.DocumentMetadata
import org.multipaz.document.DocumentStore
import org.multipaz.webtoken.buildJwt
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.openid4vci.KeyIdAndAttestation
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
import kotlin.io.encoding.Base64
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
 * @param metadataHandler interface that initializes and updates document metadata; it must be
 *  provided if [DocumentStore] uses custom implementation for [AbstractDocumentMetadata] (i.e.
 *  not [DocumentMetadata]).
 */
class ProvisioningModel(
    private val documentStore: DocumentStore,
    private val secureArea: SecureArea,
    private val httpClient: HttpClient,
    private val promptModel: PromptModel,
    private val metadataHandler: AbstractDocumentMetadataHandler = DocumentMetadataHandler()
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
            metadataHandler.initializeDocumentMetadata(
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
                        KeyBindingInfo.Attestation(
                            attestations = pendingCredentials.map {
                                KeyIdAndAttestation(it.identifier, it.getAttestation())
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
            val credentialData = credentials.serializedCredentials

            if (credentialMetadata.keyBindingType == KeyBindingType.Keyless) {
                if (credentialData.size != 1) {
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
            // TODO: for server-to-server provisioning protocols we should handle the case when
            //  (some) credentials come later in subsequent calls to obtainCredentials.
            for ((credentialData, pendingCredential) in credentialData.zip(pendingCredentials)) {
                // TODO: remove validity parameters, extract them from the credentialData
                pendingCredential.certify(
                    credentialData.toByteArray(),
                    Clock.System.now(),
                    Clock.System.now() + 30.days
                )
            }
            if (credentials.display != null) {
                metadataHandler.updateDocumentMetadata(document, credentials.display)
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

    /**
     * Manager document metadata when the document is created and when the metadata is updated
     * from the server.
     *
     * When [DocumentMetadata] is used as [AbstractDocumentMetadata] implementation,
     * [DocumentMetadataHandler] can be used as implementation of this interface.
     */
    interface AbstractDocumentMetadataHandler {
        /**
         * Initializes metadata object when the document is first created.
         *
         * @param metadata metadata object from a freshly created [Document]
         * @param credentialDisplay display data from the issuer's credential configuration
         * @param issuerDisplay display data for the issuer itself
         */
        suspend fun initializeDocumentMetadata(
            metadata: AbstractDocumentMetadata,
            credentialDisplay: Display,
            issuerDisplay: Display
        )

        /**
         * Updates metadata for the existing document.
         *
         * @param document document being updated
         * @param credentialDisplay customized display data for the provisioned credentials
         */
        suspend fun updateDocumentMetadata(
            document: Document,
            credentialDisplay: Display
        )
    }

    /**
     * [AbstractDocumentMetadataHandler] implementation that handles the case when default
     * implementation ([DocumentMetadata]) is used to represent document metadata.
     *
     * @param defaultCardArtLoader function that is called to create card art for the document
     *    when no card art is provided by the server.
     */
    class DocumentMetadataHandler(
        private val defaultCardArtLoader: suspend () -> ByteString = { defaultCardArt }
    ): AbstractDocumentMetadataHandler {
        override suspend fun initializeDocumentMetadata(
            metadata: AbstractDocumentMetadata,
            credentialDisplay: Display,
            issuerDisplay: Display
        ) {
            (metadata as DocumentMetadata).setMetadata(
                displayName = credentialDisplay.text,
                typeDisplayName = credentialDisplay.text,
                cardArt = credentialDisplay.logo ?: defaultCardArtLoader.invoke(),
                issuerLogo = issuerDisplay.logo,
                other = null
            )
        }

        override suspend fun updateDocumentMetadata(
            document: Document,
            credentialDisplay: Display
        ) {
            val metadata = document.metadata as DocumentMetadata
            metadata.setMetadata(
                displayName = credentialDisplay.text,
                typeDisplayName = metadata.typeDisplayName,
                cardArt = credentialDisplay.logo ?: metadata.cardArt,
                issuerLogo = metadata.issuerLogo,
                other = metadata.other
            )
        }
    }

    companion object {
        private const val CREDENTIAL_DOMAIN_MDOC = "mdoc_user_auth"
        private const val CREDENTIAL_DOMAIN_SD_JWT_VC = "sdjwt_user_auth"
        private const val CREDENTIAL_DOMAIN_SD_JWT_VC_KEYLESS = "sdjwt_keyless"

        private const val TAG = "ProvisioningModel"

        private val defaultCardArt: ByteString by lazy {
            ByteString(Base64.Mime.decode(DEFAULT_CARD_ART))
        }

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

        // PNG image that says "NO CARD ART".
        private val DEFAULT_CARD_ART = """
            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
            alIAAAAASUVORK5CYII=
        """.trimIndent()
    }
}