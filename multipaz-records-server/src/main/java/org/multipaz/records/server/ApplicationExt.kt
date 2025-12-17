package org.multipaz.records.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.records.data.AdminCookieInvalid
import org.multipaz.records.data.IdentityNotFoundException
import org.multipaz.records.data.adminAuth
import org.multipaz.records.data.validateAdminCookie
import org.multipaz.records.request.authorizeGet
import org.multipaz.records.request.authorizePost
import org.multipaz.records.request.data
import org.multipaz.records.request.identityDelete
import org.multipaz.records.request.identitySchema
import org.multipaz.records.request.identityGet
import org.multipaz.records.request.identityList
import org.multipaz.records.request.identityOffer
import org.multipaz.records.request.identityPut
import org.multipaz.records.request.pushedAuthorizationRequest
import org.multipaz.records.request.enroll
import org.multipaz.records.request.token
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.common.serveResources
import org.multipaz.server.request.certificateAuthority

private const val TAG = "ApplicationExt"

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(serverEnvironment: Deferred<ServerEnvironment>) {
    intercept(ApplicationCallPipeline.Plugins) {
        // Customize error handling for records-server-specific exceptions
        try {
            proceed()
        } catch (_: AdminCookieInvalid) {
            call.respondText(
                status = HttpStatusCode.Unauthorized,
                text = buildJsonObject {
                    put("error", "unauthorized")
                    put("error_description", "admin login required")
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
        }
    }
    routing {
        serveResources()
        certificateAuthority(createOnRequest = true)
        post("/enroll") {
            enroll(call)
        }
        get("/identity/metadata") {
            val configuration = serverEnvironment.await().getInterface(Configuration::class)!!
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
        post("/identity/auth") {
            adminAuth(call)
        }
        post("/identity/list") {
            identityList(call)
        }
        post("/identity/get") {
            identityGet(call)
        }
        post("/identity/create") {
            validateAdminCookie(call)
            identityPut(call, create = true)
        }
        post("/identity/update") {
            validateAdminCookie(call)
            identityPut(call, create = false)
        }
        post("/identity/delete") {
            validateAdminCookie(call)
            identityDelete(call)
        }
        post("/identity/offer") {
            identityOffer(call)
        }
        get("/identity/schema") {
            identitySchema(call)
        }
        post("/par") {
            pushedAuthorizationRequest(call)
        }
        get("/authorize") {
            authorizeGet(call)
        }
        post("/authorize") {
            authorizePost(call)
        }
        post("/token") {
            token(call)
        }
        get("/data") {
            data(call)
        }
    }
}
