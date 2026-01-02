package org.multipaz.backend.server

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.rpc.server.ClientCheckImpl
import org.multipaz.rpc.server.ClientRegistrationImpl
import org.multipaz.backend.openid4vci.OpenID4VCIBackendImpl
import org.multipaz.backend.openid4vci.register
import org.multipaz.rpc.handler.HttpHandler
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.server.register
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.request.certificateAuthority
import org.multipaz.server.request.push
import org.multipaz.server.request.rpc
import java.util.Locale

private const val TAG = "ApplicationExt"

/**
 * Defines server entry points for HTTP GET and POST.
 */
fun Application.configureRouting(serverEnvironment: Deferred<ServerEnvironment>) {
    val httpHandler = initAndCreateHttpHandler(serverEnvironment)
    routing {
        push(serverEnvironment)
        certificateAuthority()
        get ("/") {
            call.respondText("Multipaz back-end server is running")
        }
        get("/.well-known/assetlinks.json") {
            call.respondText(
                contentType = ContentType.Application.Json,
                text = generateAssetLinksJson().toString()
            )
        }
        get("/.well-known/apple-app-site-association") {
            call.respondText(
                contentType = ContentType.Application.Json,
                text = generateAppleAppSiteAssociationJson().toString()
            )
        }
        rpc("/rpc", httpHandler)
    }
}

private fun initAndCreateHttpHandler(
    environment: Deferred<ServerEnvironment>
): Deferred<HttpHandler> {
    return CoroutineScope(Dispatchers.Default).async {
        val env = environment.await()
        withContext(env) {
            OpenID4VCIBackendImpl.init()
        }
        val exceptionMap = buildExceptionMap()
        val dispatcherBuilder = buildDispatcher()
        val notifications = env.notifications
        val localDispatcher = dispatcherBuilder.build(
            env,
            env.cipher,
            exceptionMap
        )
        HttpHandler(localDispatcher, notifications)
    }
}

private fun buildExceptionMap(): RpcExceptionMap {
    return RpcExceptionMap.Builder().build()
}

private fun buildDispatcher(): RpcDispatcherLocal.Builder {
    val dispatcherBuilder = RpcDispatcherLocal.Builder()
    ClientRegistrationImpl.register(dispatcherBuilder)
    ClientCheckImpl.register(dispatcherBuilder)
    OpenID4VCIBackendImpl.register(dispatcherBuilder)
    return dispatcherBuilder
}

private suspend fun generateAssetLinksJson(): JsonElement =
    buildJsonArray {
        val clientRequirements = ClientRegistrationImpl.getClientRequirements()
        // We only support assetlink generation for apps with a single signature
        for (digest in clientRequirements.androidAppSignatureCertificateDigests) {
            val digestString = digestToString(digest)
            for (packageName in clientRequirements.androidAppPackageNames) {
                addJsonObject {
                    putJsonArray("relation") {
                        add("delegate_permission/common.handle_all_urls")
                    }
                    putJsonObject("target") {
                        put("namespace", "android_app")
                        put("package_name", packageName)
                        putJsonArray("sha256_cert_fingerprints") {
                            add(digestString)
                        }
                    }
                }
            }
        }
    }

private fun digestToString(digest: ByteString): String =
    digest.toByteArray().joinToString(":") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }.uppercase(Locale.ROOT)

private suspend fun generateAppleAppSiteAssociationJson(): JsonElement {
    val clientRequirements = ClientRegistrationImpl.getClientRequirements()
    val appIds = buildJsonArray {
        clientRequirements.iosAppIdentifiers.forEach { add(it) }
    }
    return buildJsonObject {
        putJsonObject("applinks") {
            putJsonArray("details") {
                addJsonObject {
                    put("appIDs", appIds)
                    putJsonArray("components") {
                        addJsonObject {
                            put("/", "/redirect/")
                        }
                        addJsonObject {
                            put("/", "/landing/")  // legacy name
                        }
                    }
                }
            }
        }
        putJsonObject("webcredentials") {
            put("appIDs", appIds)
        }
    }
}