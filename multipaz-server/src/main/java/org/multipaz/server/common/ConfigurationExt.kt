package org.multipaz.server.common

import org.multipaz.rpc.backend.Configuration

/** Host to bind the server to, from the `server_host` setting. `null` if not configured. */
val Configuration.serverHost: String? get() {
    return getValue("server_host")
}

/** Port to bind the server to, from the `server_port` setting. Must be set in configuration. */
val Configuration.serverPort: Int get() =
    getValue("server_port")!!.toInt()

/**
 * Base URL of the server. Uses the `base_url` setting if present, otherwise derived
 * from [serverHost] (defaulting to `localhost`) and [serverPort].
 */
val Configuration.baseUrl: String get() = getValue("base_url")
        ?: ("http://" + (serverHost ?: "localhost") + ":" + serverPort)

/**
 * URL of the enrollment server (CA) that this server uses for remote enrollment,
 * from the `enrollment_server_url` setting. `null` if not configured.
 */
val Configuration.enrollmentServerUrl: String? get() = getValue("enrollment_server_url")