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

/**
 * HTML injected immediately before the closing `</head>` tag of every HTML page served by
 * [serveResources], from the `custom_head_html` setting. `null` if not configured.
 *
 * This allows a deployment to restyle or extend the built-in pages without forking their
 * markup, for example:
 *
 * ```json
 * "custom_head_html": "<link rel=\"stylesheet\" href=\"custom.css\">"
 * ```
 *
 * Any asset referenced this way is served like every other static resource, so it must be
 * present in the server's `www` resource folder.
 */
val Configuration.customHeadHtml: String? get() = getValue("custom_head_html")
