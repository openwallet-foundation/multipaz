package org.multipaz.rpc.server

import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.rpc.auth.ClientCheck
import org.multipaz.rpc.backend.RpcAuthBackendDelegate
import org.multipaz.rpc.handler.RpcAuthInspector

@RpcState(
    endpoint = "client_check",
    creatable = true
)
@CborSerializable
class ClientCheckImpl: ClientCheck, RpcAuthInspector by RpcAuthBackendDelegate {
    override suspend fun ping(message: String): String {
        return message
    }

    companion object
}