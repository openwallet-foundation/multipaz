package org.multipaz.records.request

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.records.data.Identity
import org.multipaz.records.data.IdentityData
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.common.getAdminPassword

suspend fun identityLoad(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    if (request["password"]?.jsonPrimitive?.content != getAdminPassword()) {
        throw InvalidRequestException("wrong password")
    }
    for (identity in request["identities"]!!.jsonArray) {
        val data = IdentityData.fromJson(identity.jsonObject)
        val utopiaId = data.core["utopia_id_number"]!!.asTstr
        if (Identity.hasId(utopiaId)) {
            // Will assign a new id and new account numbers
            Identity.create(data)
        } else {
            Identity.restore(utopiaId, data)
        }
    }
}
