package org.multipaz.server.common

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.cache

/**
 * Serves static resources from `www` resource folder.
 *
 * The following extensions are served using their appropriate content types: `html`, `js`,
 * `css`, `jpg`, `jpeg`, `png`.
 *
 * Additionally "/" is mapped to `www/index.html`.
 */
fun Routing.serveResources() {
    get("/") { fetchResource(call, "index.html") }
    get("/{path...}") { fetchResource(call, call.parameters["path"]!!) }
}

/**
 * Serves HTTP request by fetching a resource.
 *
 * Resources are cached in-memory, so they are not expected to be very large.
 */
private suspend fun fetchResource(call: ApplicationCall, path: String) {
    try {
        val resource = BackendEnvironment.cache(ResourceBytes::class, path) { _, resources ->
            val bytes = resources.getRawResource("www/$path")
                ?: throw ResourceNotFoundException()
            ResourceBytes(bytes)
        }
        call.respondBytes(
            contentType = when (path.substring(path.lastIndexOf('.') + 1)) {
                "html" -> ContentType.Text.Html
                "js" -> ContentType.Application.JavaScript
                "css" -> ContentType.Text.CSS
                "jpeg", "jpg" -> ContentType.Image.JPEG
                "png" -> ContentType.Image.PNG
                else -> ContentType.Application.OctetStream
            },
            provider = { resource.bytes.toByteArray() }
        )
    } catch (_: ResourceNotFoundException) {
        call.respondText(
            text = "Resource not found: $path",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.NotFound
        )
    }
}

data class ResourceBytes(val bytes: ByteString)

private class ResourceNotFoundException : Exception()