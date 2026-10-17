package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.Logger

private const val TAG = "connectionHelper"

/**
 * A helper for advertising a number of connections to a remote peer.
 *
 * For each [MdocConnectionMethod] this creates a [MdocTransport] which is advertised.
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
    try {
        for (connectionMethod in this) {
            val transport = transportFactory.createTransport(
                connectionMethod,
                role,
                options
            )
            transports.add(transport)
            transport.advertise()
        }
        return transports
    } catch (error: Exception) {
        withContext(NonCancellable) {
            for (transport in transports) {
                try {
                    transport.close()
                } catch (closeError: Exception) {
                    if (closeError is CancellationException) throw closeError
                    Logger.e(
                        TAG,
                        "Caught exception while closing connection ${transport.connectionMethod}",
                        closeError
                    )
                }
            }
        }
        throw error
    }
}

/**
 * A helper for waiting until someone connects to a transport.
 *
 * The list of transports must contain transports that are all in the state
 * [MdocTransport.State.ADVERTISING] or [MdocTransport.State.IDLE].
 *
 * The first connection which a remote peer connects to is returned and the other ones are closed.
 *
 * @param eSenderKey This should be set to `EDeviceKey` if using forward engagement or `EReaderKey`
 *   if using reverse engagement.
 * @return the [MdocTransport] a remote peer connected to, will be in [MdocTransport.State.CONNECTED] state.
 * @throws IllegalStateException if no transport connected or if a transport is not in
 *   [MdocTransport.State.ADVERTISING] or [MdocTransport.State.IDLE] state.
 */
@Throws(CancellationException::class, IllegalStateException::class)
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

    var completedNormally = false
    try {
        coroutineScope {
            lateinit var jobs: Map<MdocTransport, Job>
            // Deduplicate before creating lazy jobs so none are overwritten and left unstarted.
            jobs = distinct().associateWith { transport ->
                launch(start = CoroutineStart.LAZY) {
                    try {
                        Logger.i(TAG, "opening connection ${transport.connectionMethod}")
                        transport.open(eSenderKey)
                        // Most transports reach State.CONNECTED before open() returns, but
                        // NfcTransportMdoc.open() returns in State.IDLE and transitions to
                        // State.CONNECTED later when the SELECT APDU is received.
                        transport.state.first {
                            it == MdocTransport.State.CONNECTED ||
                                    it == MdocTransport.State.FAILED ||
                                    it == MdocTransport.State.CLOSED
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Logger.e(TAG, "Caught exception while opening connection ${transport.connectionMethod}", error)
                        return@launch
                    }
                    if (transport.state.value == MdocTransport.State.CONNECTED) {
                        resultingTransportLock.withLock {
                            if (resultingTransport == null) {
                                resultingTransport = transport
                                // Close the transports that didn't get connected
                                for (otherTransport in this@waitForConnection) {
                                    if (otherTransport != transport) {
                                        Logger.i(TAG, "Closing other transport ${otherTransport.connectionMethod}")
                                        jobs[otherTransport]?.cancel()
                                        try {
                                            otherTransport.close()
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            Logger.e(
                                                TAG,
                                                "Caught exception while closing connection ${otherTransport.connectionMethod}",
                                                error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            jobs.values.forEach { it.start() }
        }
        completedNormally = true
    } finally {
        withContext(NonCancellable) {
            resultingTransportLock.withLock {
                for (transport in this@waitForConnection) {
                    if (!completedNormally || transport != resultingTransport) {
                        try {
                            transport.close()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Logger.e(TAG, "Caught exception while closing connection ${transport.connectionMethod}", error)
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
