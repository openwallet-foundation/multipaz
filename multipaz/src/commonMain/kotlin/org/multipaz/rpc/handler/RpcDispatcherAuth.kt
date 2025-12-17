package org.multipaz.rpc.handler

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.util.Logger

/**
 * [RpcDispatcher] implementation that dispatches RPC call to a [base] dispatcher
 * after adding RPC method authorization to it.
 */
class RpcDispatcherAuth(
    val base: RpcDispatcher,
    private val rpcAuthIssuer: RpcAuthIssuer
): RpcDispatcher {
    override val exceptionMap: RpcExceptionMap
        get() = base.exceptionMap

    override suspend fun dispatch(target: String, method: String, args: DataItem): List<DataItem> {
        var retried = false
        while (true) {
            val payload = Bstr(Cbor.encode(args))
            val message = rpcAuthIssuer.auth(target, method, payload).also {
                check(it["payload"] === payload) {
                    "Authenticated message 'payload' field must contain raw RPC message"
                }
            }
            val result = base.dispatch(target, method, message)
            val nonce = result[1]
            if (nonce != Simple.NULL) {
                val sessionContext = currentCoroutineContext()[RpcAuthClientSession.Key]
                    ?: throw IllegalStateException("RpcAuthClientSession must be provided")
                sessionContext.nonce = ByteString(nonce.asBstr)
                if (result[2].asNumber == RpcReturnCode.NONCE_RETRY.ordinal.toLong()) {
                    if (retried) {
                        throw RpcAuthException("Nonce error", RpcAuthError.FAILED)
                    }
                    Logger.i(TAG, "Nonce rejected, retrying with a fresh nonce")
                    retried = true
                    continue
                }
            }
            return result
        }
    }

    companion object {
        private const val TAG = "RpcDispatchAuth"
    }

}