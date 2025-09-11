package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.records.data.Identity
import org.multipaz.records.data.TokenType
import org.multipaz.records.data.tokenToId

/**
 * Handles `data` GET request that comes from openid4vci server to access System-of-Record
 * data to create a credential.
 *
 * This request is protected by OAuth bearer token that can be obtained using the sequence
 * of `par`, `authorize`, and `token` requests in standard OAuth fashion.
 */
suspend fun data(call: ApplicationCall) {
    val authorization = call.request.header("Authorization")
    if (authorization == null) {
        call.respondText(
            status = HttpStatusCode.Unauthorized,
            contentType = ContentType.Text.Plain,
            text = "'Authorization' header is missing"
        )
        return
    }
    if (authorization.substring(0, 7).lowercase() != "bearer ") {
        call.respondText(
            status = HttpStatusCode.Unauthorized,
            contentType = ContentType.Text.Plain,
            text = "'Bearer' authorization required"
        )
        return
    }
    val (scope, recordId, id) = tokenToId(
        type = TokenType.ACCESS_TOKEN,
        code = authorization.substring(7)
    ).split(":")
    val identity = Identity.findById(id)
    call.respondBytes(
        status = HttpStatusCode.OK,
        contentType = ContentType.Application.Cbor
    ) {
        Cbor.encode(buildCborMap {
            putCborMap("core") {
                for ((name, value) in identity.data.core) {
                    put(name, value)
                }
            }
            putCborMap(key = "records") {
                val records = identity.data.records[scope]
                if (records != null) {
                    putCborMap(scope) {
                        val record = records[recordId]
                        if (record != null) {
                            put(
                                recordId,
                                CborMap(record.asMap.filter { (key, _) ->
                                    key.asTstr != "instance_title"
                                }.toMutableMap())
                            )
                        }
                    }
                }
            }
        })
    }
}