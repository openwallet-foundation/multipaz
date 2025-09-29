package org.multipaz.rpc.handler

import kotlinx.io.bytestring.ByteString
import org.multipaz.device.DeviceAttestation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * An object added to the current coroutine context based on the successful processing of RPC
 * call authorization.
 *
 * @param [clientId] client instance identifier; could be device, user, or authorization context
 *     identifier, depending on the specific authorization method.
 * @param [sessionId] session identifier for the RPC call sequence, empty string indicates
 *     that the authorization method does not maintain a session.
 * @param [nextNonce] nonce to be used by the client in next call in the session (if any).
 *
 * See [RpcAuthInspector].
 */
class RpcAuthContext(
    private val clientId: String,
    private val sessionId: String,
    internal val nextNonce: ByteString? = null
): CoroutineContext.Element {
    object Key: CoroutineContext.Key<RpcAuthContext>

    override val key: CoroutineContext.Key<RpcAuthContext>
        get() = Key

    companion object {
        suspend fun getClientId(): String {
            return coroutineContext[Key]!!.clientId
        }

        suspend fun getClientDeviceAttestation(): DeviceAttestation? =
            RpcAuthInspectorAssertion.getClientDeviceAttestation(getClientId())

        suspend fun getSessionId(): String {
            return coroutineContext[Key]!!.sessionId
        }
    }
}