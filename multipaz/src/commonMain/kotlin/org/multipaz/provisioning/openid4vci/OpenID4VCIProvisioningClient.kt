package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.authority
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationException
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningClient
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class OpenID4VCIProvisioningClient(
    val clientPreferences: OpenID4VCIClientPreferences,
    val credentialOffer: CredentialOffer,
    val issuerConfiguration: IssuerConfiguration,
    val authorizationConfiguration: AuthorizationConfiguration
): ProvisioningClient {
    var pkceCodeVerifier: String? = null
    var token: String? = null
    var tokenExpiration: Instant? = null
    var refreshToken: String? = null
    var authorizationDPoPNonce: String? = null
    var issuerDPoPNonce: String? = null
    var clientAttestationChallenge: String? = null
    var keyChallenge: String? = null
    var redirectState: String? = null
    var txRetry = false

    var walletAttestation: String? = null

    var walletAttestationKeyAlias: String? = null

    override suspend fun getMetadata(): ProvisioningMetadata {
        val fullMetadata = issuerConfiguration.provisioningMetadata
        val credentialId = credentialOffer.configurationId
        return ProvisioningMetadata(
            display = fullMetadata.display,
            credentials = mapOf(credentialId to fullMetadata.credentials[credentialId]!!)
        )
    }

    override suspend fun getAuthorizationChallenges(): List<AuthorizationChallenge> {
        if (token != null) {
            return listOf()
        }
        if (credentialOffer is CredentialOffer.PreauthorizedCode) {
            if (credentialOffer.txCode != null) {
                return listOf(AuthorizationChallenge.SecretText(
                    id = "tx",
                    retry = txRetry,
                    request = credentialOffer.txCode
                ))
            }
            obtainToken(preauthorizedCode = credentialOffer.preauthorizedCode)
            return listOf()
        }
        val requestUri = performPushedAuthorizationRequest()
        return listOf(AuthorizationChallenge.OAuth(
            id = "oauth",
            url = buildString {
                append(authorizationConfiguration.authorizationEndpoint)
                append("?client_id=")
                append(clientPreferences.clientId.encodeURLParameter())
                append("&request_uri=")
                append(requestUri.encodeURLParameter())
            },
            state = redirectState!!
        ))
    }

    override suspend fun authorize(response: AuthorizationResponse) {
        when (response) {
            is AuthorizationResponse.OAuth -> processOauthResponse(response.parameterizedRedirectUrl)
            is AuthorizationResponse.SecretText -> processSecretTextResponse(response.secret)
        }
    }

    override suspend fun getKeyBindingChallenge(): String {
        val credentialConfiguration =
            issuerConfiguration.provisioningMetadata.credentials[credentialOffer.configurationId]!!
        if (credentialConfiguration.keyBindingType == KeyBindingType.Keyless) {
            throw IllegalStateException("getKeyBindingChallenge must not be called for keyless credentials")
        }
        // obtain c_nonce (serves as challenge for the device-bound key)
        val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
        val nonceResponse = httpClient.post(issuerConfiguration.nonceEndpoint!!) {}
        if (nonceResponse.status != HttpStatusCode.OK) {
            throw IllegalStateException("Error getting a nonce")
        }
        Logger.i(TAG, "Got successful response for nonce request")
        // A fresh DPoP nonce might or might not be given
        nonceResponse.headers["DPoP-Nonce"]?.let { issuerDPoPNonce = it }
        val responseText = nonceResponse.readBytes().decodeToString()
        val cNonce = Json.parseToJsonElement(responseText).jsonObject.string("c_nonce")
        keyChallenge = cNonce
        return cNonce
    }

    override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): List<ByteString> {
        refreshAccessIfNeeded()
        val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!

        // without a nonce we may need to retry
        var retry = true
        var credentialResponse: HttpResponse
        val credentialMetadata =
            issuerConfiguration.provisioningMetadata.credentials[credentialOffer.configurationId]!!
        val keyProofs = buildKeyProofs(keyInfo)
        while (true) {
            val dpop = OpenID4VCIUtil.generateDPoP(
                clientId = clientPreferences.clientId,
                requestUrl = issuerConfiguration.credentialEndpoint,
                dpopNonce = issuerDPoPNonce,
                accessToken = token
            )
            credentialResponse = httpClient.post(issuerConfiguration.credentialEndpoint) {
                headers {
                    append("Authorization", "DPoP $token")
                    append("DPoP", dpop)
                    contentType(ContentType.Application.Json)
                }
                setBody(buildJsonObject {
                    put("credential_configuration_id", credentialOffer.configurationId)
                    if (keyProofs != null) {
                        put("proofs", keyProofs)
                    }
                    when (credentialMetadata.format) {
                        is CredentialFormat.Mdoc -> {
                            put("format", "mso_mdoc")
                            put("doctype", credentialMetadata.format.docType)
                        }
                        is CredentialFormat.SdJwt -> {
                            put("format", "dc+sd-jwt")
                            put("vct", credentialMetadata.format.vct)
                        }
                    }
                }.toString())
            }
            if (credentialResponse.headers.contains("DPoP-Nonce")) {
                issuerDPoPNonce = credentialResponse.headers["DPoP-Nonce"]!!
                if (retry) {
                    retry = false // don't retry more than once
                    if (credentialResponse.status != HttpStatusCode.OK) {
                        Logger.e(TAG, "Retry with a fresh DPoP nonce")
                        continue  // retry with the nonce
                    }
                }
            }
            break
        }

        val responseText = credentialResponse.readBytes().decodeToString()
        if (credentialResponse.status != HttpStatusCode.OK) {
            Logger.e(TAG,"Credential request error: ${credentialResponse.status} $responseText")
            throw IllegalStateException(
                "Error getting a credential issued: ${credentialResponse.status} $responseText")
        }
        Logger.i(TAG, "Got successful response for credential request")

        val response = Json.parseToJsonElement(responseText) as JsonObject
        return response["credentials"]!!.jsonArray.map {
            if (it !is JsonObject) {
                throw IllegalStateException("Credential must be represented as json string")
            }
            val text = it.string("credential")
            when (credentialMetadata.format) {
                is CredentialFormat.Mdoc -> ByteString(text.fromBase64Url())
                is CredentialFormat.SdJwt -> text.encodeToByteString()
            }
        }
    }

    private suspend fun buildKeyProofs(keyInfo: KeyBindingInfo): JsonElement? =
        when (keyInfo) {
            KeyBindingInfo.Keyless -> null
            is KeyBindingInfo.OpenidProofOfPossession -> buildJsonObject {
                putJsonArray("jwt") {
                    for (jwt in keyInfo.jwtList) {
                        add(jwt)
                    }
                }
            }
            is KeyBindingInfo.Attestation -> buildJsonObject {
                val backend = BackendEnvironment.getInterface(OpenID4VCIBackend::class)!!
                val jwtKeyAttestation = backend.createJwtKeyAttestation(
                    keyAttestations = keyInfo.attestations,
                    challenge = keyChallenge!!
                )
                putJsonArray("attestation") {
                    add(jwtKeyAttestation)
                }
            }
        }

    private suspend fun performPushedAuthorizationRequest(): String {
        maybeObtainClientAttestationChallenge()

        pkceCodeVerifier = Random.Default.nextBytes(32).toBase64Url()
        val codeChallenge = Crypto.digest(
            Algorithm.SHA256,
            pkceCodeVerifier!!.encodeToByteArray()
        ).toBase64Url()

        // Attempt to use scope. We prefer scopes to authorization_details, but it is only safe
        // if scope and credential format identify this credential id uniquely.
        val credentialMap = issuerConfiguration.provisioningMetadata.credentials
        val configurationId = credentialOffer.configurationId
        val credentialMetadata = credentialMap[configurationId]!!
        val scope = issuerConfiguration.credentialConfigurations[configurationId]!!.scope
            ?.let { provisionalScope ->
                for ((id, config) in issuerConfiguration.credentialConfigurations) {
                    if (provisionalScope == config.scope &&
                        credentialMetadata.format.formatId == credentialMap[id]!!.format.formatId) {
                        Logger.w(TAG, "Scope does not uniquely identify credential for configuration id '$configurationId'")
                        return@let null
                    }
                }
                provisionalScope
            }

        val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
        var response: HttpResponse
        var retryCount = 0

        while (true) {
            // retry loop for DPoP nonce
            val dpop = OpenID4VCIUtil.generateDPoP(
                clientId = clientPreferences.clientId,
                requestUrl = authorizationConfiguration.pushedAuthorizationRequestEndpoint,
                dpopNonce = authorizationDPoPNonce,
                accessToken = null
            )
            val walletAttestationPoP = if (authorizationConfiguration.useClientAssertion) {
                null
            } else {
                // If client assertion is not used, we always send wallet attestation.
                val key = obtainWalletAttestation()
                OpenID4VCIUtil.createWalletAttestationPoP(
                    clientId = clientPreferences.clientId,
                    key = key,
                    authenticationServerIdentifier = authorizationConfiguration.identifier,
                    challenge = clientAttestationChallenge
                )
            }
            val clientAssertion = if (authorizationConfiguration.useClientAssertion) {
                OpenID4VCIUtil.createClientAssertion(authorizationConfiguration.identifier)
            } else {
                null
            }
            val redirectState = createUniqueStateValue()
            this.redirectState = redirectState

            response = httpClient.submitForm(
                url = authorizationConfiguration.pushedAuthorizationRequestEndpoint,
                formParameters = parameters {
                    if (scope != null) {
                        append("scope", scope)
                    } else {
                        append("authorization_details", buildJsonArray {
                            addJsonObject {
                                put("type", "openid_credential")
                                put("credential_configuration_id", configurationId)
                            }
                        }.toString())
                    }
                    if (credentialOffer is CredentialOffer.AuthorizationCode) {
                        val issuerState = credentialOffer.issuerState
                        if (issuerState != null) {
                            append("issuer_state", issuerState)
                        }
                    }
                    if (clientAssertion != null) {
                        append("client_assertion", clientAssertion)
                        append(
                            "client_assertion_type",
                            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                        )
                    }
                    append("response_type", "code")
                    append("code_challenge_method", "S256")
                    append("redirect_uri", clientPreferences.redirectUrl)
                    append("code_challenge", codeChallenge)
                    append("client_id", clientPreferences.clientId)
                    append("state", redirectState)
                }
            ) {
                headers {
                    append("DPoP", dpop)
                    if (walletAttestation != null) {
                        append("OAuth-Client-Attestation", walletAttestation!!)
                        append("OAuth-Client-Attestation-PoP", walletAttestationPoP!!)
                    }
                }
            }
            response.headers["DPoP-Nonce"]?.let { authorizationDPoPNonce = it }
            response.headers["OAuth-Client-Attestation-Challenge"]?.let { clientAttestationChallenge = it }
            if (response.status == HttpStatusCode.Created) {
                break
            }
            val responseText = response.readBytes().decodeToString()
            Logger.e(TAG, "PAR request error: ${response.status}: $responseText")
            if (retryCount == 0) {
                retryCount++
                if (authorizationDPoPNonce != null) {
                    continue  // retry the request with the nonce
                }
            }
            throw IllegalStateException("Error establishing authenticated channel with issuer")
        }
        val responseText = response.readBytes().decodeToString()
        val parsedResponse = Json.parseToJsonElement(responseText).jsonObject
        return parsedResponse.string("request_uri")
    }

    private suspend fun obtainWalletAttestation(): AsymmetricKey {
        val secureArea = BackendEnvironment.getInterface(SecureAreaProvider::class)!!.get()
        val endpoint = Url(authorizationConfiguration.pushedAuthorizationRequestEndpoint)
        // https://datatracker.ietf.org/doc/html/draft-ietf-oauth-attestation-based-client-auth-01
        // Section 6.1. "Client Instance Tracking Across Authorization Servers" recommends
        // using different keys for different servers. We go even further and obtain a fresh
        // wallet attestation for every session. Perhaps this should be made configurable; in
        // such case walletAttestation and walletAttestationKeyAlias should be cached based on
        // endpoint.authority value.
        val keyInfo = secureArea.createKey(
            alias = null,
            createKeySettings = CreateKeySettings(
                nonce = endpoint.authority.encodeToByteString()
            )
        )
        walletAttestationKeyAlias = keyInfo.alias
        val backend = BackendEnvironment.getInterface(OpenID4VCIBackend::class)!!
        walletAttestation = backend.createJwtWalletAttestation(keyInfo.attestation)
        return AsymmetricKey.anonymous(secureArea, keyInfo.alias)
    }

    private suspend fun processOauthResponse(parameterizedRedirectUrl: String) {
        val navigatedUrl = Url(parameterizedRedirectUrl)
        if (navigatedUrl.parameters["state"] != redirectState) {
            throw IllegalStateException("Openid4Vci: state parameter value mismatch")
        }
        releaseStateValue(redirectState!!)
        redirectState = null
        val error = navigatedUrl.parameters["error"]
        if (error != null) {
            val description = navigatedUrl.parameters["error_description"]
            throw AuthorizationException(error, description)
        }
        val code = navigatedUrl.parameters["code"]
            ?: throw IllegalStateException("Openid4Vci: no code in authorization response")
        obtainToken(authorizationCode = code, codeVerifier = pkceCodeVerifier!!)
        pkceCodeVerifier = null
    }

    private suspend fun processSecretTextResponse(secret: String) {
        val credentialOffer = this.credentialOffer as CredentialOffer.PreauthorizedCode
        obtainToken(preauthorizedCode = credentialOffer.preauthorizedCode, txCode = secret)
    }

    private suspend fun obtainToken(
        refreshToken: String? = null,
        authorizationCode: String? = null,
        preauthorizedCode: String? = null,
        txCode: String? = null,  // pin or other transaction code
        codeVerifier: String? = null
    ) {
        if (refreshToken == null && authorizationCode == null && preauthorizedCode == null) {
            throw IllegalArgumentException("No authorizations provided")
        }
        if (preauthorizedCode != null) {
            maybeObtainClientAttestationChallenge()
        }
        val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
        var retried = false
        // When dpop nonce is null, this loop will run twice, first request will return with error,
        // but will provide fresh, dpop nonce and the second request will get fresh access data.
        while (true) {
            val dpop = OpenID4VCIUtil.generateDPoP(
                clientId = clientPreferences.clientId,
                requestUrl = authorizationConfiguration.tokenEndpoint,
                dpopNonce = authorizationDPoPNonce,
                accessToken = null
            )
            val walletAttestationPoP = if (authorizationConfiguration.useClientAssertion) {
                null
            } else {
                val key = if (walletAttestation == null) {
                    // For pre-authorized code case, this is where the session is initialized.
                    obtainWalletAttestation()
                } else {
                    AsymmetricKey.anonymous(
                        secureArea = BackendEnvironment.getInterface(SecureAreaProvider::class)!!.get(),
                        alias = walletAttestationKeyAlias!!
                    )
                }
                OpenID4VCIUtil.createWalletAttestationPoP(
                    clientId = clientPreferences.clientId,
                    key = key,
                    authenticationServerIdentifier = authorizationConfiguration.identifier,
                    challenge = clientAttestationChallenge
                )
            }
            val clientAssertion = if (authorizationConfiguration.useClientAssertion) {
                OpenID4VCIUtil.createClientAssertion(authorizationConfiguration.identifier)
            } else {
                null
            }

            val response = httpClient.submitForm(
                url = authorizationConfiguration.tokenEndpoint,
                formParameters = parameters {
                    if (refreshToken != null) {
                        append("grant_type", "refresh_token")
                        append("refresh_token", refreshToken)
                    } else if (authorizationCode != null) {
                        append("grant_type", "authorization_code")
                        append("code", authorizationCode)
                    } else if (preauthorizedCode != null) {
                        append("grant_type", "urn:ietf:params:oauth:grant-type:pre-authorized_code")
                        append("pre-authorized_code", preauthorizedCode)
                        if (txCode != null) {
                            append("tx_code", txCode)
                        }
                        append("authorization_details", buildJsonArray {
                            addJsonObject {
                                put("type", "openid_credential")
                                put("credential_configuration_id", credentialOffer.configurationId)
                            }
                        }.toString())
                    }
                    if (codeVerifier != null) {
                        append("code_verifier", codeVerifier)
                    }
                    if (clientAssertion != null) {
                        append("client_assertion", clientAssertion)
                        append(
                            "client_assertion_type",
                            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                        )
                    }
                    append("client_id", clientPreferences.clientId)
                    append("redirect_uri", clientPreferences.redirectUrl)
                }
            ) {
                headers {
                    append("DPoP", dpop)
                    append("Content-Type", "application/x-www-form-urlencoded")
                    if (walletAttestation != null) {
                        append("OAuth-Client-Attestation", walletAttestation!!)
                        append("OAuth-Client-Attestation-PoP", walletAttestationPoP!!)
                    }
                }
            }
            response.headers["DPoP-Nonce"]?.let { authorizationDPoPNonce = it }
            response.headers["OAuth-Client-Attestation-Challenge"]?.let { clientAttestationChallenge = it }
            if (response.status != HttpStatusCode.OK) {
                val errResponseText = response.readBytes().decodeToString()
                if (preauthorizedCode != null && txCode != null) {
                    // Transaction Code may be wrong
                    val errResponse = Json.parseToJsonElement(errResponseText) as JsonObject
                    if (errResponse.string("error") == "invalid_grant") {
                        // NB: we do not know if the pre-authorized code is wrong (or expired)
                        // or tx_code was mis-entered. Assume it is the latter
                        txRetry = true
                        return
                    }
                }
                if (!retried && authorizationDPoPNonce != null) {
                    retried = true
                    Logger.e(TAG, "DPoP nonce refreshed: $errResponseText")
                    continue
                }
                Logger.e(TAG, "Token request error: ${response.status} $errResponseText")
                throw IllegalStateException(
                    if (authorizationCode != null) {
                        "Authorization code rejected by the issuer"
                    } else if (preauthorizedCode != null) {
                        "Pre-authorized code rejected by the issuer"
                    } else {
                        "Refresh token (seed credential) rejected by the issuer"
                    }
                )
            }
            val tokenResponseString = response.readBytes().decodeToString()
            val tokenResponse = Json.parseToJsonElement(tokenResponseString) as JsonObject
            token = tokenResponse.string("access_token")
            val duration = tokenResponse.integer("expires_in")
            tokenExpiration = Clock.System.now() + duration.seconds
            val refreshToken = tokenResponse.stringOrNull("refresh_token")
            if (refreshToken != null) {
                this.refreshToken = refreshToken
            }
            return
        }
    }

    private suspend fun maybeObtainClientAttestationChallenge() {
        if (!authorizationConfiguration.useClientAssertion) {
            // Using client attestation. Check if we need to get a fresh challenge
            if (authorizationConfiguration.challengeEndpoint != null) {
                val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
                val response = httpClient.post(authorizationConfiguration.challengeEndpoint) {}
                if (response.status != HttpStatusCode.OK) {
                    throw IllegalStateException("Error getting a challenge")
                }
                Logger.i(TAG, "Got successful response for challenge request")
                // DPoP nonce might or might not be given
                authorizationDPoPNonce = response.headers["DPoP-Nonce"]
                val responseText = response.readBytes().decodeToString()
                clientAttestationChallenge = Json.parseToJsonElement(responseText)
                    .jsonObject.string("attestation_challenge")
            }
        }
    }

    private suspend fun refreshAccessIfNeeded() {
        if (token == null && refreshToken == null) {
            throw IllegalStateException("Not authorized")
        }
        val expiration = tokenExpiration
        if (expiration != null && Clock.System.now() + 30.seconds < expiration) {
            // No need to refresh.
            return
        }
        obtainToken(
            refreshToken = refreshToken
                ?: throw IllegalStateException("refresh token was not issued")
        )
        Logger.i(TAG, "Refreshed access tokens")
    }

    companion object Companion : JsonParsing("Openid4Vci") {
        private const val TAG = "OpenID4VCIProvisioningClient"

        private val stateLock = Mutex()
        private val states = mutableSetOf<String>()

        private suspend fun createUniqueStateValue(): String {
            while (true) {
                val state = Random.Default.nextBytes(15).toBase64Url()
                stateLock.withLock {
                    if (states.add(state)) {
                        return state
                    }
                }
            }
        }

        private suspend fun releaseStateValue(state: String) {
            stateLock.withLock {
                states.remove(state)
            }
        }

        suspend fun createFromOffer(
            offerUri: String,
            clientPreferences: OpenID4VCIClientPreferences,
        ): OpenID4VCIProvisioningClient {
            val credentialOffer = CredentialOffer.parseCredentialOffer(offerUri)
            val issuerConfig = IssuerConfiguration.get(
                url = credentialOffer.issuerUri,
                clientPreferences = clientPreferences
            )
            val authorizationServerUrl = credentialOffer.authorizationServer
                ?: issuerConfig.authorizationServerUrls.first()
            val authorizationConfiguration = AuthorizationConfiguration.get(
                url = authorizationServerUrl,
                clientPreferences = clientPreferences
            )
            return OpenID4VCIProvisioningClient(
                clientPreferences = clientPreferences,
                credentialOffer = credentialOffer,
                issuerConfiguration = issuerConfig,
                authorizationConfiguration = authorizationConfiguration,
            )
        }
    }
}