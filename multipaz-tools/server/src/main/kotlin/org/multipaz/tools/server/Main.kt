package org.multipaz.tools.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respondText

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("PORT")?.toInt() ?: 8012
        println("Starting multipaz-tools server on port $port...")
        val server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(CallLogging)
            routing {
                singlePageApplication {
                    useResources = true
                    filesPath = "static"
                    defaultPage = "index.html"
                }
            }
        }
        server.start(wait = true)
    }
}
