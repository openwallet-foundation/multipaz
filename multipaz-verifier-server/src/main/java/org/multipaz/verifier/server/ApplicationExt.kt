package org.multipaz.verifier.server

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Deferred
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.request.push
import org.multipaz.server.common.serveResources
import org.multipaz.server.request.certificateAuthority
import org.multipaz.verifier.request.verifierGet
import org.multipaz.verifier.request.verifierPost

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(environment: Deferred<ServerEnvironment>) {
    routing {
        push(environment)
        certificateAuthority()
        serveResources()
        get("/verifier/{command}") {
            verifierGet(call, call.parameters["command"]!!)
        }
        post("/verifier/{command}") {
            verifierPost(call, call.parameters["command"]!!)
        }
    }
}

