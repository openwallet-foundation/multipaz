package org.multipaz.rpc.auth

import kotlinx.io.bytestring.ByteString
import org.multipaz.device.DeviceAttestation
import org.multipaz.rpc.annotation.RpcInterface
import org.multipaz.rpc.annotation.RpcMethod

/**
 * Registers an RPC client with the server.
 *
 * Client creates [DeviceAttestation] object with the challenge provided by the server.
 * The server then validates that [DeviceAttestation] comes from a valid client, stores it,
 * and issues an identifier (clientId) that the client can use for
 */
@RpcInterface
interface ClientRegistration {
    @RpcMethod
    suspend fun challenge(): ByteString

    @RpcMethod
    suspend fun register(deviceAttestation: DeviceAttestation): String
}