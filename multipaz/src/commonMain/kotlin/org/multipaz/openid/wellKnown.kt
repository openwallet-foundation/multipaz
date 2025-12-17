package org.multipaz.openid

import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority

/**
 * Creates a `.well-known` location of the type [name] that describes service running at [url].
 *
 * @param url url of the service
 * @param name name of the `.well-known` file
 * @return url where `.well-known` file should be fetched from
 */
fun wellKnown(url: String, name: String): String {
    val parsedUrl = Url(url)
    val head = parsedUrl.protocolWithAuthority
    val path = parsedUrl.encodedPath
    return "$head/.well-known/$name$path"
}

