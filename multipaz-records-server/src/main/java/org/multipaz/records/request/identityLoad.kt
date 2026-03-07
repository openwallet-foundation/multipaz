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
import org.multipaz.records.data.recordTypes
import org.multipaz.records.data.toDataItem
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.common.getAdminPassword
import kotlin.collections.component1
import kotlin.collections.component2

suspend fun identityLoad(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    if (request["password"]?.jsonPrimitive?.content != getAdminPassword()) {
        throw InvalidRequestException("wrong password")
    }
    val identities = request["identities"]!!.jsonArray
    val coreAttributes = recordTypes["core"]!!.subAttributes
    for (identity in identities) {
        identity as JsonObject
        val common = identity["core"]!!.jsonObject.asIterable().associate { (key, value) ->
            Pair(key, value.toDataItem(coreAttributes[key]!!))
        }
        val records =
            identity["records"]!!.jsonObject.asIterable().associate { (recordTypeId, recordMap) ->
                val recordType = recordTypes[recordTypeId]!!
                Pair(
                    recordTypeId,
                    recordMap.jsonObject.asIterable().associate { (recordId, record) ->
                        Pair(recordId, record.toDataItem(recordType))
                    })
            }
        Identity.create(IdentityData(common, records))
    }
}
