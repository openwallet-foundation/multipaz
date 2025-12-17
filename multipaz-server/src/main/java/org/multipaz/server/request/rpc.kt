package org.multipaz.server.request

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.rpc.transport.HttpTransport
import org.multipaz.util.Logger

/**
 * Serves an RPC endpoint.
 *
 * @param path url path where RPC will be served
 * @param httpHandler handler for the incoming RPC calls
 */
fun Routing.rpc(path: String, httpHandler: Deferred<HttpTransport>) {
    post("$path/{endpoint}/{method}") {
        val endpoint = call.parameters["endpoint"]!!
        val method = call.parameters["method"]!!
        val request = call.receive<ByteArray>()
        val handler = httpHandler.await()
        try {
            val response = handler.post("$endpoint/$method", ByteString(request))
            Logger.i(TAG, "POST $path/$endpoint/$method status 200")
            call.respond(response.toByteArray())
        } catch (e: CancellationException) {
            Logger.e(TAG, "POST $path/$endpoint/$method, request cancelled", e)
            throw e
        } catch (e: UnsupportedOperationException) {
            Logger.e(TAG, "POST $path/$endpoint/$method status 404", e)
            call.respond(HttpStatusCode.NotFound, e.message ?: "")
        } catch (e: SimpleCipher.DataTamperedException) {
            Logger.e(TAG, "POST $path/$endpoint/$method status 405", e)
            call.respond(HttpStatusCode.MethodNotAllowed, "State tampered")
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "POST $path/$endpoint/$method status 405", e)
            call.respond(HttpStatusCode.MethodNotAllowed, "IllegalStateException")
        } catch (_: HttpTransport.TimeoutException) {
            Logger.e(TAG, "POST $path/$endpoint/$method status 500 (TimeoutException)")
            call.respond(HttpStatusCode.InternalServerError, "TimeoutException")
        } catch (e: Throwable) {
            Logger.e(TAG, "POST $path/$endpoint/$method status 500", e)
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "")
        }
    }
}

private const val TAG = "RPC"