package org.multipaz.openid

import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority

/**
 * Creates a `.well-known` location of the type [name] that describes service running at [url].
 *
 * For authorization servers specifically RFC 8414 Section 3.1. "Authorization Server Metadata
 * Request" demands that the trailing slash must be dropped.
 *
 * @param url url of the service
 * @param name name of the `.well-known` file
 * @param dropTrailingSlash if the trailing slash must be dropped
 * @return url where `.well-known` file should be fetched from
 */
fun wellKnown(
    url: String,
    name: String,
    dropTrailingSlash: Boolean = false
): String {
    val parsedUrl = Url(url)
    val head = parsedUrl.protocolWithAuthority
    val path = parsedUrl.encodedPath
    val adjustedPath = if (dropTrailingSlash && path.endsWith("/")) path.dropLast(1) else path
    return "$head/.well-known/$name$adjustedPath"
}

