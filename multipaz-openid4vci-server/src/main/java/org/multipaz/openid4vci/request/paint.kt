package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.openid4vci.credential.CredentialDisplay
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * Paints sample card art using JSON configuration passed as a parameter.
 */
suspend fun paint(call: ApplicationCall) {
    val config = call.request.queryParameters["c"]
        ?: throw InvalidRequestException("'c' parameter required")
    val sampleSystemOfRecordData = buildCborMap {
        putCborMap("core") {
            put("given_name", "Benjamin")
            put("family_name", "Morgan")
        }
    }
    val canvas = CredentialDisplay.cardArt(
        systemOfRecordData = sampleSystemOfRecordData,
        cardArtConfig = Json.parseToJsonElement(config).jsonObject
    ) ?: throw InvalidRequestException("failed to render")
    call.respondBytes(ContentType.Image.PNG) {
        canvas.toPng()
    }
}
