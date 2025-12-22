package org.multipaz.openid4vci.request

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.rpc.handler.InvalidRequestException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.verifier.Openid4VpVerifierModel
import org.multipaz.openid4vci.util.AUTHZ_REQ
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.server.common.getDomain
import org.multipaz.openid4vci.util.getReaderIdentity
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.rpc.backend.BackendEnvironment
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Generates request for Digital Credential for the browser-based authorization workflow.
 */
suspend fun credentialRequest(call: ApplicationCall) {
    // TODO: unify with AuthorizeServlet.getOpenid4Vp
    val timeout = 5.minutes
    val requestData = call.receiveText()
    val params = Json.parseToJsonElement(requestData) as JsonObject
    val code = params["code"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'code'")
    val id = codeToId(OpaqueIdType.PID_READING, code)
    val stateRef = idToCode(OpaqueIdType.OPENID4VP_STATE, id, timeout)
    val state = IssuanceState.getIssuanceState(id)
    val domain = BackendEnvironment.getDomain()
    val model = Openid4VpVerifierModel(
        clientId = "origin:${domain}",
        ephemeralPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
    )
    state.openid4VpVerifierModel = model
    val credentialRequest = model.makeRequest(
        state = stateRef,
        responseMode = "dc_api.jwt",
        expectedOrigins = listOf(domain),
        readerIdentity = getReaderIdentity(),
        requests = mapOf(
            "pid" to EUPersonalID.getDocumentType().cannedRequests.first { it.id == "mandatory" }
        )
    )
    IssuanceState.updateIssuanceState(id, state, Clock.System.now() + timeout)
    call.respondText(
        text = credentialRequest,
        contentType = AUTHZ_REQ
    )
}