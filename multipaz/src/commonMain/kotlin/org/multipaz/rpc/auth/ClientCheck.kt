package org.multipaz.rpc.auth

import org.multipaz.rpc.annotation.RpcInterface
import org.multipaz.rpc.annotation.RpcMethod
import org.multipaz.rpc.handler.RpcAuthError
import org.multipaz.rpc.handler.RpcAuthException

/**
 * This interface provides a way to verify that RPC client record is active and works end-to-end
 * by making a simple call.
 *
 * If the client record is removed on the server, RPC call will fail in with [RpcAuthException]
 * with [RpcAuthException.rpcAuthError] set to [RpcAuthError.UNKNOWN_CLIENT_ID]. Tn most cases
 * client would need to re-register to recover from this error.
 */
@RpcInterface
interface ClientCheck {
    @RpcMethod
    suspend fun ping(message: String): String
}