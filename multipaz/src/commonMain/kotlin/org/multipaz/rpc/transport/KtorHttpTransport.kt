package org.multipaz.rpc.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString

/**
 * [HttpTransport] implemented using ktor http client library.
 *
 * @param engine HTTP engine to use
 * @param baseUrl RPC server endpoint
 */
class KtorHttpTransport(
    engine: HttpClientEngineFactory<*>,
    private val baseUrl: String
): HttpTransport {
    companion object Companion {
        // TODO: make it possible to set the requestTimeout for each post call individually,
        //   so timeout for notification polling can be different from regular RPC calls.
        private const val REQUEST_TIMEOUT_SECONDS = 5*60
    }

    val client = HttpClient(engine) {
        install(HttpTimeout.Plugin)
    }

    override suspend fun post(
        url: String,
        data: ByteString
    ): ByteString {
        val response = try {
            client.post("$baseUrl/rpc/$url") {
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_SECONDS.toLong()*1000
                }
                setBody(data.toByteArray())
            }
        } catch (e: HttpRequestTimeoutException) {
            throw HttpTransport.TimeoutException("Timed out", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw HttpTransport.ConnectionException("Error", e)
        }
        HttpTransport.Companion.processStatus(url, response.status.value, response.status.description)
        return ByteString(response.readBytes())
    }
}