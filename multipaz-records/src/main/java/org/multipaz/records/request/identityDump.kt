package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.records.data.Identity
import org.multipaz.records.data.recordTypes

suspend fun identityDump(call: ApplicationCall) {
    val fields = recordTypes["core"]!!.subAttributes.keys.toList()
    val records = recordTypes.keys.filter { it != "core" }.associateWith { listOf<String>() }
    call.respondText (
        contentType = ContentType.Application.Json,
        text = buildJsonArray {
            for (id in Identity.listAll()) {
                val identity = Identity.findById(id)
                add(identityToJson(identity, fields, records))
            }
        }.toString()
    )
}