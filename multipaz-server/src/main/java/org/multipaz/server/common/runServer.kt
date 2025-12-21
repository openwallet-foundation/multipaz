package org.multipaz.server.common

import com.mysql.cj.jdbc.Driver
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.Storage
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

/**
 * Initializes [ServerEnvironment] and performs common initialization, then runs server-specific
 * set-up (like request routing) and launches the server.
 *
 * @param args command-line arguments
 * @param needAdminPassword if the server requires admin passwords for certain requests
 * @param checkConfiguration routine to validate server configuration
 * @param applicationConfigurationAction routine to perform server-specific set-up
 * @return this function does not return
 */
fun runServer(
    args: Array<String>,
    needAdminPassword: Boolean = false,
    checkConfiguration: (ServerConfiguration) -> Unit = {},
    applicationConfigurationAction: Application.(env: Deferred<ServerEnvironment>) -> Unit
) {
    val configuration = ServerConfiguration(args)
    checkConfiguration.invoke(configuration)
    val jdbc = configuration.getValue("database_connection")
    if (jdbc != null) {
        if (jdbc.startsWith("jdbc:mysql:")) {
            Logger.i("Main", "SQL driver: ${Driver()}")
        } else if (jdbc.startsWith("jdbc:postgresql:")) {
            Logger.i("Main", "SQL driver: ${org.postgresql.Driver()}")
        }
    }
    if (needAdminPassword) {
        adminPassword = configuration.getValue("admin_password")
            ?: Random.nextBytes(15).toBase64Url().also {
                Logger.e(TAG, "No 'admin_password' in config, generated: '$it'")
            }
    }
    val host = configuration.serverHost ?: "0.0.0.0"
    val serverEnvironment = ServerEnvironment.create(configuration)
    launchBackgroundJob(serverEnvironment)
    embeddedServer(
        factory = Netty,
        port = configuration.serverPort,
        host = host,
        module = {
            install(CallLogging)
            traceCalls(configuration)
            installServerEnvironment(serverEnvironment)
            applicationConfigurationAction(serverEnvironment)
        }
    ).start(wait = true)
}

/**
 * Installs an interceptor that injects [ServerEnvironment] in the coroutine context for
 * all other request handlers.
 *
 * This method is public mostly for use in testing.
 */
fun Application.installServerEnvironment(
    serverEnvironment: Deferred<ServerEnvironment>
) {
    intercept(ApplicationCallPipeline.Plugins) {
        // Inject server environment
        withContext(serverEnvironment.await()) {
            // Standard error handling; if this is not desired, individual handlers
            // must do their own.
            try {
                proceed()
            } catch (err: CancellationException) {
                throw err
            } catch (err: InvalidRequestException) {
                Logger.e(TAG, "Error", err)
                err.printStackTrace()
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    text = buildJsonObject {
                        put("error", "invalid_request")
                        put("error_description", err.message ?: "")
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            } catch (err: Throwable) {
                Logger.e(TAG, "Error", err)
                err.printStackTrace()
                call.respondText(
                    status = HttpStatusCode.InternalServerError,
                    text = buildJsonObject {
                        put("error", "internal_server_error")
                        put("error_description", err::class.simpleName + ": " + err.message)
                    }.toString(),
                    contentType = ContentType.Application.Json
                )
            }
        }
    }
}

/**
 * Returns admin password.
 *
 * Admin password can be specified using `admin_password` setting in the server configuration.
 * If this setting is missing, a random ephemeral password will be generated and printed to the
 * log. This password will remain valid for the duration of this server run, it is not
 * persisted in the storage. (This simplifies running the server locally for development).
 *
 * This method must be called only if [runServer] was called with `needAdminPassword` set to
 * `true`.
 *
 * @return admin password
 * @throws NullPointerException when [runServer] was called with `needAdminPassword` set to false.
 */
fun getAdminPassword() = adminPassword!!

private fun launchBackgroundJob(env: Deferred<ServerEnvironment>) {
    CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            Logger.i(TAG, "purging expired...")
            env.await().getInterface(Storage::class)!!.purgeExpired()
            Logger.i(TAG, "purging complete")
            delay(1.hours)
        }
    }
}

// record requests and responses, if configured.

private val RESPONSE_COPY_KEY = AttributeKey<String>("RESPONSE_COPY_KEY")

private fun Application.traceCalls(configuration: ServerConfiguration) {
    val traceFile = configuration.getValue("server_trace_file") ?: return
    install(DoubleReceive)
    val traceStream = if (traceFile == "-") {
        OutputStreamWriter(System.out)
    } else {
        FileWriter(traceFile, true)
    }
    val before = PipelinePhase("before")
    insertPhaseBefore(ApplicationCallPipeline.Call, before)
    intercept(before) {
        val attributes = call.attributes
        val traceResponse = PipelinePhase("traceResponse")
        call.response.pipeline.insertPhaseAfter(ApplicationSendPipeline.Engine, traceResponse)
        call.response.pipeline.intercept(traceResponse) { response ->
            when (response) {
                is OutgoingContent.ByteArrayContent -> {
                    val type = response.contentType
                    if (type == ContentType.Application.Json ||
                        (type != null && type.contentType == "application" &&
                                type.contentSubtype.endsWith("+jwt"))
                    ) {
                        attributes.put(RESPONSE_COPY_KEY,
                            response.bytes().decodeToString())
                    }
                }
                else -> {}
            }
        }
    }
    insertPhaseAfter(ApplicationCallPipeline.Call, before)
    val after = PipelinePhase("after")
    insertPhaseAfter(ApplicationCallPipeline.Call, after)
    intercept(after) {
        val buffer = StringWriter()
        val trace = PrintWriter(buffer)
        trace.println("============================")
        trace.println("${call.request.httpMethod.value} ${call.request.uri}")
        for (name in call.request.headers.names()) {
            trace.println("$name: ${call.request.headers[name]}")
        }
        if (call.request.httpMethod == HttpMethod.Post || call.request.httpMethod == HttpMethod.Put) {
            val contentType = call.request.contentType()
            if (contentType == ContentType.Application.Json ||
                contentType == ContentType.Application.FormUrlEncoded ||
                contentType == ContentType.Application.FormUrlEncoded.withParameter("charset", "UTF-8")) {
                trace.println()
                trace.println(call.receiveText())
            } else {
                trace.println("*** body not logged ***")
            }
        }

        trace.println("----------------------------")
        val response = call.response
        val status = response.status() ?: HttpStatusCode.OK
        trace.println("${status.value} ${status.description}")
        for (name in response.headers.allValues().names()) {
            for (value in response.headers.values(name)) {
                trace.println("$name: $value")
            }
        }

        if (call.attributes.contains(RESPONSE_COPY_KEY)) {
            val body = call.attributes[RESPONSE_COPY_KEY]
            trace.println()
            if (body.endsWith('\n')) {
                trace.print(body)
            } else {
                trace.println(body)
            }
        } else {
            trace.println("*** body not logged ***")
        }
        trace.flush()
        traceStream.write(buffer.toString())
        traceStream.flush()
    }
}

private var adminPassword: String? = null

private const val TAG = "runServer"