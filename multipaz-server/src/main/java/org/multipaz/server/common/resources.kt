package org.multipaz.server.common

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.cache

/**
 * Serves static resources from `www` resource folder.
 *
 * The following extensions are served using their appropriate content types: `html`, `js`,
 * `css`, `jpg`, `jpeg`, `png`.
 *
 * Additionally "/" is mapped to `www/index.html`.
 *
 * HTML pages have the `custom_head_html` setting (see [Configuration.customHeadHtml]) injected
 * into their `<head>`, which lets a deployment restyle them without forking their markup.
 */
fun Routing.serveResources() {
    get("/") { fetchResource(call, "index.html") }
    get("/{path...}") { fetchResource(call, call.parameters.getAll("path")!!.joinToString("/")) }
}

/**
 * Serves HTTP request by fetching a resource.
 *
 * Resources are cached in-memory, so they are not expected to be very large.
 */
private suspend fun fetchResource(call: ApplicationCall, path: String) {
    try {
        val resource = BackendEnvironment.cache(ResourceBytes::class, path) { configuration, resources ->
            val bytes = resources.getRawResource("www/$path")
                ?: throw ResourceNotFoundException()
            ResourceBytes(
                if (path.endsWith(".html", ignoreCase = true)) {
                    injectCustomHeadHtml(bytes, configuration.customHeadHtml)
                } else {
                    bytes
                }
            )
        }
        call.respondBytes(
            contentType = when (path.substring(path.lastIndexOf('.') + 1)) {
                "html" -> ContentType.Text.Html
                "js" -> ContentType.Application.JavaScript
                "css" -> ContentType.Text.CSS
                "jpeg", "jpg" -> ContentType.Image.JPEG
                "png" -> ContentType.Image.PNG
		        "webp" -> ContentType.Image.WEBP
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

/**
 * Returns [content] with [headHtml] inserted immediately before its first `</head>` tag.
 *
 * [content] is returned unchanged when [headHtml] is `null` or blank, and when the content has
 * no `</head>` tag to insert before. The tag is located by a case-insensitive ASCII scan of the
 * raw bytes rather than by decoding the content, so pages in any encoding pass through
 * unharmed.
 */
internal fun injectCustomHeadHtml(content: ByteString, headHtml: String?): ByteString {
    if (headHtml.isNullOrBlank()) {
        return content
    }
    val bytes = content.toByteArray()
    val index = indexOfHeadCloseTag(bytes)
    if (index < 0) {
        return content
    }
    val insert = headHtml.encodeToByteArray()
    val result = ByteArray(bytes.size + insert.size)
    bytes.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = index)
    insert.copyInto(result, destinationOffset = index)
    bytes.copyInto(result, destinationOffset = index + insert.size, startIndex = index)
    return ByteString(result)
}

/** Index of the first case-insensitive `</head>` in [bytes], or -1 if there is none. */
private fun indexOfHeadCloseTag(bytes: ByteArray): Int {
    for (start in 0..bytes.size - HEAD_CLOSE_TAG.size) {
        var matched = true
        for (offset in HEAD_CLOSE_TAG.indices) {
            if (bytes[start + offset].asciiLowercase() != HEAD_CLOSE_TAG[offset]) {
                matched = false
                break
            }
        }
        if (matched) {
            return start
        }
    }
    return -1
}

/** Lowercases an ASCII letter, leaving every other byte (including UTF-8 continuations) alone. */
private fun Byte.asciiLowercase(): Byte {
    val value = toInt()
    return if (value >= 'A'.code && value <= 'Z'.code) {
        (value + ('a'.code - 'A'.code)).toByte()
    } else {
        this
    }
}

private val HEAD_CLOSE_TAG = "</head>".encodeToByteArray()

/** Wrapper for cached static resource content, used as a cache key type by [serveResources]. */
data class ResourceBytes(val bytes: ByteString)

private class ResourceNotFoundException : Exception()