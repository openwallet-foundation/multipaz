package org.multipaz.mdoc.transport

import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.util.Logger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.mdoc.role.MdocRole
import kotlin.coroutines.CoroutineContext

private const val TAG = "connectionHelper"

/**
 * A helper for advertising a number of connections to a remote peer.
 *
 * For each [MdocConnectionMethod] this creates a [MdocTransport] which is advertised and opened.
 *
 * @param role the role to use when creating connections.
 * @param transportFactory the [MdocTransportFactory] used to create [MdocTransport] instances.
 * @param options the [MdocTransportOptions] to use when creating [MdocTransport] instances.
 * @return a list of [MdocTransport] methods that are being advertised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun List<MdocConnectionMethod>.advertise(
    role: MdocRole,
    transportFactory: MdocTransportFactory,
    options: MdocTransportOptions
): List<MdocTransport> {
    val transports = mutableListOf<MdocTransport>()
    for (connectionMethod in this) {
        val transport = transportFactory.createTransport(
            connectionMethod,
            role,
            options
        )
        transport.advertise()
        transports.add(transport)
    }
    return transports
}

/**
 * A helper for waiting until someone connects to a transport.
 *
 * The list of transports must contain transports that all all in the state [MdocTransport.State.ADVERTISING].
 *
 * The first connection which a remote peer connects to is returned and the other ones are closed.
 *
 * @param eSenderKey This should be set to `EDeviceKey` if using forward engagement or `EReaderKey`
 *   if using reverse engagement.
 * @param coroutineScope the [CoroutineScope] used for waiting for the connections to be made.
 * @return the [MdocTransport] a remote peer connected to, will be in [MdocTransport.State.CONNECTING]
 * or [MdocTransport.State.CONNECTED] state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun List<MdocTransport>.waitForConnection(
    eSenderKey: EcPublicKey,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
): MdocTransport {
    lateinit var continuation: CancellableContinuation<MdocTransport>
    forEach { transport ->
        // MdocTransport.open() doesn't return until state is CONNECTED which is much later than
        // when we're seeing a connection attempt (when state is CONNECTING)
        //
        // And we want to switch to PresentationScreen upon seeing CONNECTING .. so call open() in a subroutine
        // and just watch the state variable change.
        //
        coroutineScope.launch {
            try {
                Logger.i(TAG, "opening connection ${transport.connectionMethod}")
                transport.open(eSenderKey)
            } catch (error: Throwable) {
                Logger.e(TAG, "Caught exception while opening connection ${transport.connectionMethod}", error)
                error.printStackTrace()
            }
        }

        coroutineScope.launch {
            // Wait until state changes to CONNECTED, CONNECTING, FAILED, or CLOSED
            transport.state.first {
                it == MdocTransport.State.CONNECTED ||
                        it == MdocTransport.State.CONNECTING ||
                        it == MdocTransport.State.FAILED ||
                        it == MdocTransport.State.CLOSED
            }
            if (transport.state.value == MdocTransport.State.CONNECTING ||
                transport.state.value == MdocTransport.State.CONNECTED
            ) {
                // Close the transports that didn't get connected
                for (otherTransport in this@waitForConnection) {
                    if (otherTransport != transport) {
                        Logger.i(TAG, "Closing other transport ${otherTransport.connectionMethod}")
                        otherTransport.close()
                    }
                }
                continuation.resume(transport, null)
            }
        }
    }
    return suspendCancellableCoroutine<MdocTransport> { continuation = it }
}
