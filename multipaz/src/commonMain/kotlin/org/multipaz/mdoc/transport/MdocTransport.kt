package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import kotlinx.coroutines.flow.StateFlow
import org.multipaz.mdoc.role.MdocRole
import kotlin.time.Duration

/**
 * An abstraction of a ISO/IEC 18013-5:2021 device retrieval method.
 *
 * A [MdocTransport]'s state can be tracked in the [state] property which is [State.IDLE]
 * when constructed from the factory. This is a [StateFlow] and intended to be used by
 * the application to update its user interface.
 *
 * To open a connection to the other peer, call [open]. When [open] returns successfully
 * the state is [State.CONNECTED]. At this point, the application can use [sendMessage]
 * and [waitForMessage] to exchange messages with the remote peer.
 *
 * Each [MdocTransport] has a role, indicating whether the application is acting as
 * the _mdoc_ or _mdoc reader_. For forward engagement, if acting in the role [MdocRole.MDOC]
 * the application should create one or more [MdocConnectionMethod] instances, call [open] on
 * each of them, share the [MdocConnectionMethod]s (through QR or NFC in _Device Engagement_
 * according to ISO/EC 18013-5:2021). Similarly, the other peer - acting in the role
 * [MdocRole.MDOC_READER] - should obtain the _Device Engagement_, get one or more
 * [MdocConnectionMethod] objects and call [open] on one of them. For reverse engagement,
 * the process is the same but with the roles reversed.
 *
 * The transport can fail at any time, for example if the other peer sends invalid data
 * or actively disconnects. In this case the state is changed to [State.FAILED] and
 * any calls except for [close] will fail with the [MdocTransportException] exception.
 *
 * The connection can be closed at any time using the [close] method which will transition
 * the state to [State.CLOSED] except if it's already in [State.FAILED].
 *
 * [MdocTransport] instances are thread-safe and methods and properties can be called from
 * any thread or coroutine. To avoid resource leaks it's important that [close] is always
 * called with the application is done using the [MdocTransport], even if it fails.
 */
abstract class MdocTransport {

    /**
     * Possible states for a transport.
     */
    enum class State {
        /** The transport is idle. */
        IDLE,

        /** The transport is being advertised. */
        ADVERTISING,

        /** The transport is scanning. */
        SCANNING,

        /** A remote peer has been identified and the connection is being set up. */
        CONNECTING,

        /** The transport is connected to the remote peer. */
        CONNECTED,

        /** The transport was connected at one point but one of the sides closed the connection. */
        CLOSED,

        /** The connection to the remote peer failed. */
        FAILED
    }

    /**
     * The current state of the transport.
     */
    abstract val state: StateFlow<State>

    /**
     * The role which the transport is for.
     */
    abstract val role: MdocRole

    /**
     * A [MdocConnectionMethod] which can be sent to the other peer to connect to, or `null` if not applicable.
     */
    abstract val connectionMethod: MdocConnectionMethod?

    /**
     * The time spent scanning for the other peer.
     *
     * This is always `null` until [open] completes and it's only set for transports that actually perform active
     * scanning for the other peer. This includes _BLE mdoc central client mode_ for [MdocRole.MDOC] and
     * _BLE mdoc peripheral server mode_ for [MdocRole.MDOC_READER].
     */
    abstract val scanningTime: Duration?

    /**
     * Diagnostic and performance statistics for this transport instance.
     */
    open val stats: MdocTransportStats
        get() = MdocTransportStats(
            transportType = MdocTransportType.OTHER,
            role = role,
            scanningTime = runCatching { scanningTime }.getOrNull(),
        )

    /**
     * Starts advertising the connection.
     *
     * This is optional for transports to implement.
     */
    abstract suspend fun advertise()

    /**
     * Opens the connection to the other peer.
     *
     * @param eSenderKey This should be set to `EDeviceKey` if using forward engagement or
     * `EReaderKey` if using reverse engagement.
     */
    abstract suspend fun open(eSenderKey: EcPublicKey)

    /**
     * Sends a message to the other peer.
     *
     * This should be formatted as `SessionEstablishment` or `SessionData` according to
     * ISO/IEC 18013-5:2021.
     *
     * To signal transport-specific termination send an empty message. If the transport doesn't support
     * this, [MdocTransportTerminationException] is thrown.
     *
     * This blocks the calling coroutine until the message is sent.
     *
     * @param message the message to send.
     * @throws MdocTransportException if an unrecoverable error occurs.
     * @throws MdocTransportTerminationException if the transport doesn't support transport-specific termination.
     */
    abstract suspend fun sendMessage(message: ByteArray)

    /**
     * Waits for the other peer to send a message.
     *
     * This received message should be formatted as `SessionEstablishment` or `SessionData`
     * according to ISO/IEC 18013-5:2021. Transport-specific session termination is indicated
     * by the returned message being empty.
     *
     * @return the message that was received or empty if transport-specific session termination was used.
     * @throws MdocTransportClosedException if [close] was called from another coroutine while waiting.
     * @throws MdocTransportException if an unrecoverable error occurs.
     */
    abstract suspend fun waitForMessage(): ByteArray

    /**
     * Closes the connection.
     *
     * This is idempotent and can be called from any thread.
     */
    abstract suspend fun close()

    /**
     * Wraps this [Throwable] as an [MdocTransportException] unless it's a [CancellationException].
     */
    protected fun Throwable.wrapUnlessCancellationException(message: String): Throwable =
        if (this is CancellationException) this
        else MdocTransportException(message, this)
}
