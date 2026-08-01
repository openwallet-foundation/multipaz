package org.multipaz.tools.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ShortenRequest(val path: String)

@Serializable
data class ShortenResponse(val shortCode: String)

@Serializable
data class ErrorResponse(val error: String)

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("PORT")?.toInt() ?: 8012
        println("Starting multipaz-tools server on port $port...")
        val store = runBlocking { ShortLinkStore.createDefault() }

        val server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(CallLogging)
            routing {
                post("/api/shorten") {
                    try {
                        val bodyText = call.receiveText()
                        val json = Json.parseToJsonElement(bodyText).jsonObject
                        val path = json["path"]?.jsonPrimitive?.content
                        if (path.isNullOrEmpty()) {
                            call.respondText(
                                Json.encodeToString(ErrorResponse.serializer(), ErrorResponse("Missing 'path' parameter")),
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest
                            )
                            return@post
                        }

                        val clientIp = call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                            ?: call.request.origin.remoteHost

                        val code = store.createShortLink(path, clientIp)
                        call.respondText(
                            Json.encodeToString(ShortenResponse.serializer(), ShortenResponse(code)),
                            ContentType.Application.Json,
                            HttpStatusCode.OK
                        )
                    } catch (e: RateLimitExceededException) {
                        call.respondText(
                            Json.encodeToString(ErrorResponse.serializer(), ErrorResponse(e.message ?: "Rate limit exceeded")),
                            ContentType.Application.Json,
                            HttpStatusCode.TooManyRequests
                        )
                    } catch (e: IllegalArgumentException) {
                        call.respondText(
                            Json.encodeToString(ErrorResponse.serializer(), ErrorResponse(e.message ?: "Invalid request")),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        call.respondText(
                            Json.encodeToString(ErrorResponse.serializer(), ErrorResponse("Server error: ${e.message}")),
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError
                        )
                    }
                }

                get("/s/{code}") {
                    val code = call.parameters["code"]
                    val record = code?.let { store.getShortLink(it) }
                    if (record != null) {
                        call.respondRedirect(record.path)
                    } else {
                        call.respondRedirect("/")
                    }
                }

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
