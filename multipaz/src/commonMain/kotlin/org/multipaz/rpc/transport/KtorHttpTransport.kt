package org.multipaz.rpc.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString

/**
 * [HttpTransport] implemented using ktor http client library.
 */
class KtorHttpTransport: HttpTransport {
    companion object {
        // TODO: make it possible to set the requestTimeout for each post call individually,
        //   so timeout for notification polling can be different from regular RPC calls.
        private const val REQUEST_TIMEOUT_SECONDS = 5*60
    }

    private val baseUrl: String
    private val httpClient: HttpClient

    /**
     * Creates [KtorHttpTransport] using HTTP engine (creating its own [HttpClient] internally.
     *
     * @param engine HTTP engine to use
     * @param baseUrl RPC server endpoint
     */
    constructor(
        engine: HttpClientEngineFactory<*>,
        baseUrl: String
    ) {
        httpClient = HttpClient(engine) {
            install(HttpTimeout)
        }
        this.baseUrl = baseUrl
    }

    /**
     * Creates [KtorHttpTransport] using existing HTTP client.
     *
     * Note that [HttpClient] must have timeout plug-in installed.
     *
     * @param httpClient HTTP client to use
     * @param baseUrl RPC server endpoint
     */
    constructor(
        httpClient: HttpClient,
        baseUrl: String
    ) {
        this.httpClient = httpClient
        this.baseUrl = baseUrl
    }

    override suspend fun post(
        url: String,
        data: ByteString
    ): ByteString {
        val response = try {
            httpClient.post("$baseUrl/$url") {
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
        HttpTransport.processStatus(url, response.status.value, response.status.description)
        return ByteString(response.readRawBytes())
    }
}