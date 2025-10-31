package org.multipaz.openid4vci.request

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.utils.io.CancellationException
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.NameSpacedData
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.jwt.buildJwt
import org.multipaz.verifier.Openid4VpVerifierModel
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.util.AUTHZ_REQ
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.MULTIPAZ_PRE_AUTHORIZE_URI
import org.multipaz.openid4vci.util.OAUTH_REQUEST_URI_PREFIX
import org.multipaz.openid4vci.util.OPENID4VP_REQUEST_URI_PREFIX
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.SystemOfRecordAccess
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.server.getBaseUrl
import org.multipaz.openid4vci.util.getReaderIdentity
import org.multipaz.openid4vci.util.getSystemOfRecordUrl
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.openid4vci.util.parseTxKind
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import org.multipaz.rpc.cache
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import java.lang.IllegalArgumentException
import java.net.URI
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

private const val TAG = "authorize"

suspend fun authorizeGet(call: ApplicationCall) {
    val queryParameters = call.request.queryParameters
    val requestUri = queryParameters["request_uri"] ?: ""
    val id = if (requestUri.startsWith(OAUTH_REQUEST_URI_PREFIX)) {
        val code = requestUri.substring(OAUTH_REQUEST_URI_PREFIX.length)
        codeToId(OpaqueIdType.PAR_CODE, code)
    } else if (requestUri.startsWith(OPENID4VP_REQUEST_URI_PREFIX)) {
        // Request a presentation using openid4vp
        getOpenid4Vp(requestUri.substring(OPENID4VP_REQUEST_URI_PREFIX.length), call)
        return
    } else if (requestUri == MULTIPAZ_PRE_AUTHORIZE_URI) {
        val configurationId = queryParameters["configuration_id"]
            ?: throw InvalidRequestException("'configuration_id' parameter is required")
        val factory = CredentialFactory.getRegisteredFactories().byOfferId[configurationId]
            ?: throw InvalidRequestException("invalid 'configuration_id' parameter")
        val txCodeSpec = parseTxKind(
            txKind = queryParameters["tx_kind"],
            txPrompt = queryParameters["tx_prompt"]
        )
        val urlSchema = queryParameters["url_schema"]
        // Create a new session
        IssuanceState.createIssuanceState(IssuanceState(
            clientId = null,
            scope = factory.scope,
            clientAttestationKey = null,
            dpopKey = null,
            redirectUri = null,
            codeChallenge = null,
            configurationId = configurationId,
            txCodeSpec = txCodeSpec,
            urlSchema = urlSchema
        ))
    } else {
        throw InvalidRequestException("Invalid or missing 'request_uri' parameter")
    }
    val systemOfRecordUrl = BackendEnvironment.getSystemOfRecordUrl()
    if (systemOfRecordUrl != null) {
        authorizeUsingSystemOfRecord(id, systemOfRecordUrl, call)
    } else {
        // Create a simple web page for the user to authorize the credential issuance.
        getHtml(id, call)
    }
}

internal data class RecordsClient(
    val signingKey: AsymmetricKey
)

private suspend fun authorizeUsingSystemOfRecord(
    id: String,
    systemOfRecordUrl: String,
    call: ApplicationCall
) {
    val secureAreaRepository = BackendEnvironment.getInterface(SecureAreaRepository::class)
    val recordsClient = BackendEnvironment.cache(RecordsClient::class) { config, _ ->
        val jwkText = config.getValue("system_of_record_jwk")
            ?: throw IllegalArgumentException(
                "config error: 'system_of_record_jwk' parameter must be specified"
            )
        RecordsClient(
            signingKey = AsymmetricKey.parse(jwkText, secureAreaRepository)
        )
    }
    val state = IssuanceState.getIssuanceState(id)
    val codeVerifier = Random.Default.nextBytes(32)
    state.systemOfRecordCodeVerifier = ByteString(codeVerifier)
    val codeChallenge = Crypto.digest(
        Algorithm.SHA256,
        codeVerifier.toBase64Url().encodeToByteArray()
    ).toBase64Url()
    val redirectUrl = BackendEnvironment.getBaseUrl() + "/finish_authorization"
    val clientAssertion = createJwtClientAssertion(
        recordsClient.signingKey,
        systemOfRecordUrl
    )
    val req = buildMap {
        put("scope", state.scope)
        put("client_assertion", clientAssertion)
        put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
        put("response_type", "code")
        put("code_challenge_method", "S256")
        put("redirect_uri", redirectUrl)
        put("code_challenge", codeChallenge)
        put("client_id", recordsClient.signingKey.subject)
        put("state", idToCode(OpaqueIdType.RECORDS_STATE, id, 10.minutes))
    }
    val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
    val response = httpClient.post("$systemOfRecordUrl/par") {
        headers {
            append("Content-Type", "application/x-www-form-urlencoded")
        }
        setBody(req.map { (name, value) ->
            name.encodeURLParameter() + "=" + value.encodeURLParameter()
        }.joinToString("&"))
    }
    val responseText = response.readBytes().decodeToString()
    if (response.status != HttpStatusCode.Created) {
        Logger.e(TAG, "PAR request error: ${response.status}: $responseText")
        throw IllegalStateException("System-of-Record: PAR request error")
    }
    val parsedResponse = Json.parseToJsonElement(responseText).jsonObject
    val requestUri = parsedResponse["request_uri"]!!.jsonPrimitive.content
    IssuanceState.updateIssuanceState(id, state)
    call.respondRedirect(buildString {
        append(systemOfRecordUrl)
        append("/authorize?clientId=")
        append(recordsClient.signingKey.subject)
        append("&request_uri=")
        append(requestUri.encodeURLParameter())
    })
}

private suspend fun createJwtClientAssertion(
    key: AsymmetricKey,
    aud: String
): String = buildJwt(
        type = "JWT",
        key = key,
        expiresIn = 5.minutes
    ) {
        put("jti", Random.Default.nextBytes(18).toBase64Url())
        put("iss", key.subject)
        put("sub", key.subject) // RFC 7523 Section 3, item 2.B
        put("aud", aud)
    }

private suspend fun getHtml(id: String, call: ApplicationCall) {
    val resources = BackendEnvironment.getInterface(Resources::class)!!
    val authorizationCode = idToCode(OpaqueIdType.AUTHORIZATION_STATE, id, 20.minutes)
    val pidReadingCode = idToCode(OpaqueIdType.PID_READING, id, 20.minutes)
    val authorizeHtml = resources.getStringResource("authorize.html")!!
    call.respondText(
        text = authorizeHtml
            .replace("\$authorizationCode", authorizationCode)
            .replace("\$pidReadingCode", pidReadingCode),
        contentType = ContentType.Text.Html
    )
}

private suspend fun getOpenid4Vp(code: String, call: ApplicationCall) {
    val id = codeToId(OpaqueIdType.OPENID4VP_CODE, code)
    val stateRef = idToCode(OpaqueIdType.OPENID4VP_STATE, id, 5.minutes)
    val baseUrl = BackendEnvironment.getBaseUrl()
    val responseUri = "$baseUrl/openid4vp_response"
    val state = IssuanceState.getIssuanceState(id)
    val model = Openid4VpVerifierModel("redirect_uri:$responseUri")
    state.openid4VpVerifierModel = model
    val jwt = state.openid4VpVerifierModel!!.makeRequest(
        state = stateRef,
        responseUri = responseUri,
        responseMode = "direct_post.jwt",
        readerIdentity = getReaderIdentity(),
        requests = mapOf(
            "pid" to EUPersonalID.getDocumentType().cannedRequests.first { it.id == "full" }
        )
    )
    IssuanceState.updateIssuanceState(id, state)
    call.respondText(
        text = jwt,
        contentType = AUTHZ_REQ
    )
}

/**
 * Handle user's authorization and redirect to `finish_authorization` endpoint.
 */
suspend fun authorizePost(call: ApplicationCall)  {
    val parameters = call.receiveParameters()
    val code = parameters["authorizationCode"]
        ?: throw InvalidRequestException("'authorizationCode' missing")
    val id = codeToId(OpaqueIdType.AUTHORIZATION_STATE, code)
    val stateCode = idToCode(OpaqueIdType.ISSUER_STATE, id, 5.minutes)

    try {
        val state = IssuanceState.getIssuanceState(id)
        val data = NameSpacedData.Builder()
        val baseUrl = BackendEnvironment.getBaseUrl()
        val pidData = parameters["pidData"]
        if (pidData != null) {
            val baseUri = URI(baseUrl)
            val origin = baseUri.scheme + "://" + baseUri.authority
            val credMap = state.openid4VpVerifierModel!!.processResponse(
                origin,
                pidData
            )
            state.openid4VpVerifierModel = null

            when (val presentation = credMap["pid"]!!) {
                is Openid4VpVerifierModel.MdocPresentation -> {
                    for (document in presentation.deviceResponse.documents) {
                        for (namespaceName in document.issuerNamespaces) {
                            for (dataElementName in document.getIssuerEntryNames(namespaceName)) {
                                val value =
                                    document.getIssuerEntryData(namespaceName, dataElementName)
                                data.putEntry(namespaceName, dataElementName, value)
                            }
                        }
                    }
                }

                is Openid4VpVerifierModel.SdJwtPresentation -> {
                    TODO()
                }
            }
        } else {
            // No system of record, just make things up
            state.systemOfRecordAccess = createFakeSystemOfRecordAccess(parameters)
        }

        state.authorized = true  // OK to issue authorization code
        IssuanceState.updateIssuanceState(id, state)

        call.respondRedirect("finish_authorization?state=$stateCode")
    } catch (err: CancellationException) {
        throw err
    } catch (err: Exception) {
        val (error, description) = when (err) {
            is InvalidRequestException -> Pair("invalid_request", err.message)
            is AuthorizationFailed -> Pair("authorization_failed", "Invalid credentials")
            else -> Pair("internal",
                (err::class.simpleName ?: "Unknown") + ": " + err.message)
        }
        Logger.e(TAG, "$error: $description", err)
        call.respondRedirect(buildString {
            append("finish_authorization?state=")
            append(stateCode)
            append("&error=")
            append(error)
            if (description != null) {
                append("&error_description=")
                append(description.encodeURLParameter())
            }
        })
    }
}


/**
 * Creates [SystemOfRecordAccess] value for the (dev-only) path where no System of Record
 * is configured.
 */
private fun createFakeSystemOfRecordAccess(parameters: Parameters): SystemOfRecordAccess {
    val givenName = parameters["given_name"]
    val familyName = parameters["family_name"]
    val birthDate = parameters["birth_date"]
    if (givenName.isNullOrBlank() || familyName.isNullOrBlank() || birthDate.isNullOrBlank()) {
        throw AuthorizationFailed()
    }
    return SystemOfRecordAccess(
        accessToken = "$givenName:$familyName:$birthDate",
        accessTokenExpiration = Instant.DISTANT_FUTURE,
        refreshToken = null
    )
}

private class AuthorizationFailed: Exception("Authorization failed")