package org.multipaz.server.common

import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration

/**
 * Returns the base URL of the server from the current [Configuration].
 *
 * The base URL is either explicitly configured via `base_url` setting, or derived
 * from `server_host` and `server_port`.
 */
suspend fun BackendEnvironment.Companion.getBaseUrl(): String =
    getInterface(Configuration::class)!!.baseUrl

/**
 * Returns the protocol and authority part of the server's base URL (e.g. `https://example.com:8000`),
 * stripping the path.
 */
suspend fun BackendEnvironment.Companion.getDomain(): String =
    Url(getBaseUrl()).protocolWithAuthority

