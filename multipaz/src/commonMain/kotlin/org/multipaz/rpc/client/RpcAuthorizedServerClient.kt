package org.multipaz.rpc.client

import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.RpcAuthIssuerSignature
import org.multipaz.rpc.handler.RpcDispatcherAuth
import org.multipaz.rpc.handler.RpcDispatcherHttp
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.transport.KtorHttpTransport

/**
 * Helper object to create a server-to-server RPC connection.
 */
object RpcAuthorizedServerClient {
    /**
     * Connects to a secure RPC server from another server.
     *
     * @param exceptionMap contains exceptions that are used in this RPC connection
     * @return object that can be used to create stub objects for RPC interfaces
     */
    suspend fun connect(
        exceptionMap: RpcExceptionMap,
        rpcEndpointUrl: String,
        callingServerUrl: String,
        signingKey: AsymmetricKey,
    ): RpcDispatcherAuth {
        val backendEnvironment = BackendEnvironment.get(currentCoroutineContext())
        val httpClient = backendEnvironment.getInterface(HttpClient::class)!!
        val httpTransport = KtorHttpTransport(httpClient, rpcEndpointUrl)
        val dispatcher = RpcDispatcherHttp(httpTransport, exceptionMap)

        return RpcDispatcherAuth(
            base = dispatcher,
            rpcAuthIssuer = RpcAuthIssuerSignature(
                clientId = callingServerUrl,
                signingKey = signingKey
            )
        )
    }
}