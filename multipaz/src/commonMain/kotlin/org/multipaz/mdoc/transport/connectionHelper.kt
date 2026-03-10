package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.util.Logger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.mdoc.role.MdocRole

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
 * @return the [MdocTransport] a remote peer connected to, will be in [MdocTransport.State.CONNECTING]
 *   or [MdocTransport.State.CONNECTED] state.
 */
suspend fun List<MdocTransport>.waitForConnection(
    eSenderKey: EcPublicKey
): MdocTransport {
    val resultingTransportLock = Mutex()
    var resultingTransport: MdocTransport? = null

    forEach { transport ->
        check(
            transport.state.value == MdocTransport.State.ADVERTISING ||
                    transport.state.value == MdocTransport.State.IDLE
        ) {
            "Expected state ADVERTISING or IDLE state for $transport, got ${transport.state.value}"
        }
    }

    // Essentially, for each transport:
    //   - call open() in separate coroutines... however open() won't return until state is CONNECTED and we
    //     want to return early when it switches to CONNECTING. Therefore
    //   - launch a coroutine to watch the state switching to CONNECTED, CONNECTING, FAILED, or CLOSED
    coroutineScope {
        forEach { transport ->
            // MdocTransport.open() doesn't return until state is CONNECTED which is much later than
            // when we're seeing a connection attempt (when state is CONNECTING)
            //
            // And we want to switch to PresentationScreen upon seeing CONNECTING .. so call open() in a subroutine
            // and just watch the state variable change.
            //
            launch {
                try {
                    Logger.i(TAG, "opening connection ${transport.connectionMethod}")
                    transport.open(eSenderKey)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Logger.e(TAG, "Caught exception while opening connection ${transport.connectionMethod}", error)
                }
            }

            launch {
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
                    resultingTransportLock.withLock {
                        if (resultingTransport == null) {
                            resultingTransport = transport
                            // Close the transports that didn't get connected
                            for (otherTransport in this@waitForConnection) {
                                if (otherTransport != transport) {
                                    Logger.i(TAG, "Closing other transport ${otherTransport.connectionMethod}")
                                    otherTransport.close()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (resultingTransport == null) {
        throw IllegalStateException("Unexpected resultingTransport is null")
    }
    return resultingTransport
}
