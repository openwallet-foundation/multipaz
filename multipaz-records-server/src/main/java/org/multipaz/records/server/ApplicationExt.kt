package org.multipaz.records.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.records.data.AdminCookieInvalid
import org.multipaz.records.data.IdentityNotFoundException
import org.multipaz.records.data.adminAuth
import org.multipaz.records.data.validateAdminCookie
import org.multipaz.records.request.authorizeGet
import org.multipaz.records.request.fetchResource
import org.multipaz.records.request.authorizePost
import org.multipaz.records.request.data
import org.multipaz.records.request.identityDelete
import org.multipaz.records.request.identitySchema
import org.multipaz.records.request.identityGet
import org.multipaz.records.request.identityList
import org.multipaz.records.request.identityOffer
import org.multipaz.records.request.identityPut
import org.multipaz.records.request.pushedAuthorizationRequest
import org.multipaz.records.request.token
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.ServerConfiguration
import org.multipaz.server.ServerEnvironment
import org.multipaz.server.getBaseUrl
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import kotlin.random.Random

private const val TAG = "ApplicationExt"

private typealias RequestWrapper =
        suspend PipelineContext<*,ApplicationCall>.(
            body: suspend PipelineContext<*,ApplicationCall>.() -> Unit
        ) -> Unit

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(configuration: ServerConfiguration) {
    // TODO: when https://youtrack.jetbrains.com/issue/KTOR-8088 is resolved, there
    //  may be a better way to inject our custom wrapper for all request handlers
    //  (instead of doing it for every request like we do today).
    val env = ServerEnvironment.create(configuration)
    val adminPassword = configuration.getValue("admin_password")
        ?: Random.nextBytes(15).toBase64Url().also {
            Logger.e(TAG, "No 'admin_password' in config, generated: '$it'")
        }
    val runRequest: RequestWrapper = { body ->
        val self = this
        withContext(env.await()) {
            try {
                body.invoke(self)
            } catch (err: CancellationException) {
                throw err
            } catch (_: AdminCookieInvalid) {
                call.respondText(
                    status = HttpStatusCode.Unauthorized,
                    text = buildJsonObject {
                        put("error", "unauthorized")
                        put("error_description", "admin login required")
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            } catch (err: InvalidRequestException) {
                Logger.e(TAG, "Error", err)
                err.printStackTrace()
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    text = buildJsonObject {
                        put("error", "invalid")
                        put("error_description", err.message ?: "")
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            } catch (_: IdentityNotFoundException) {
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    text = buildJsonObject {
                        put("error", "not_found")
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            } catch (err: Throwable) {
                Logger.e(TAG, "Error", err)
                err.printStackTrace()
                call.respondText(
                    status = HttpStatusCode.InternalServerError,
                    text = buildJsonObject {
                        put("error", "internal")
                        put("error_description", err::class.simpleName + ": " + err.message)
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            }
        }
    }
    routing {
        get("/") {
            runRequest {
                val baseUrl = BackendEnvironment.getBaseUrl()
                call.respondRedirect("$baseUrl/index.html")
            }
        }
        get("/login.html") {
            runRequest {
                fetchResource(call, "login.html")
            }
        }
        get("/{path...}") {
            val path = call.parameters["path"]!!
            runRequest {
                fetchResource(call, path)
            }
        }
        get("/identity/metadata") {
            runRequest {
                call.respondText(
                    text = buildJsonObject {
                        val issuerUrl = configuration.getValue("issuer_url")
                        if (issuerUrl != null) {
                            put("issuer_url", issuerUrl)
                        }
                        putJsonObject("names") {
                            put(
                                "state",
                                configuration.getValue("state_name") ?: "Demo Principality"
                            )
                            put(
                                "official",
                                configuration.getValue("official_name") ?: "Chief Administrator"
                            )
                            put("subject", configuration.getValue("subject_name") ?: "user")
                        }
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            }
        }
        post("/identity/auth") {
            runRequest { adminAuth(call, adminPassword) }
        }
        post("/identity/list") {
            runRequest { identityList(call) }
        }
        post("/identity/get") {
            runRequest { identityGet(call) }
        }
        post("/identity/create") {
            runRequest {
                validateAdminCookie(call)
                identityPut(call, create = true)
            }
        }
        post("/identity/update") {
            runRequest {
                validateAdminCookie(call)
                identityPut(call, create = false)
            }
        }
        post("/identity/delete") {
            runRequest {
                validateAdminCookie(call)
                identityDelete(call)
            }
        }
        post("/identity/offer") {
            runRequest {
                identityOffer(call)
            }
        }
        get("/identity/schema") {
            runRequest { identitySchema(call) }
        }
        post("/par") {
            runRequest { pushedAuthorizationRequest(call) }
        }
        get("/authorize") {
            runRequest { authorizeGet(call) }
        }
        post("/authorize") {
            runRequest { authorizePost(call) }
        }
        post("/token") {
            runRequest { token(call) }
        }
        get("/data") {
            runRequest { data(call) }
        }
    }
}
