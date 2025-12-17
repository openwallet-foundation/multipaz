package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import org.multipaz.cbor.Cbor
import org.multipaz.document.NameSpacedData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.verifier.Openid4VpVerifierModel
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Handles presentation-during-issuance OpenId4VP response from the wallet/client.
 */
suspend fun openid4VpResponse(call: ApplicationCall) {
    val parameters = call.receiveParameters()

    val stateCode = parameters["state"]!!
    val id = codeToId(OpaqueIdType.OPENID4VP_STATE, stateCode)
    val state = IssuanceState.getIssuanceState(id)

    val baseUrl = BackendEnvironment.getBaseUrl()
    val credMap = state.openid4VpVerifierModel!!.processResponse(
        "$baseUrl/openid4vp-response",
        parameters["response"]!!
    )

    val presentation = credMap["pid"]!!
    val data = NameSpacedData.Builder()

    when (presentation) {
        is Openid4VpVerifierModel.MdocPresentation -> {
            for (document in presentation.deviceResponse.documents) {
                document.issuerNamespaces.data.forEach { (namespaceName, issuerSignedItemsMap) ->
                    issuerSignedItemsMap.forEach { (dataElementName, issuerSignedItem) ->
                        data.putEntry(
                            namespaceName,
                            dataElementName,
                            Cbor.encode(issuerSignedItem.dataElementValue)
                        )
                    }
                }
            }
        }
        is Openid4VpVerifierModel.SdJwtPresentation -> {
            TODO()
        }
    }

    // TODO
    //state.credentialData = data.build()

    val timeout = 5.minutes
    IssuanceState.updateIssuanceState(id, state, Clock.System.now() + timeout)

    val presentationCode = idToCode(OpaqueIdType.OPENID4VP_PRESENTATION, id, timeout)
    call.respondText(
        text = buildJsonObject {
                put("presentation_during_issuance_session", presentationCode)
            }.toString(),
        contentType = ContentType.Application.Json
    )
}
