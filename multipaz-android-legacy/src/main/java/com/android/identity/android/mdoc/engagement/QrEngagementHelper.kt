package com.android.identity.android.mdoc.engagement

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.android.identity.android.mdoc.engagement.QrEngagementHelper.Listener
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransport.Companion.fromConnectionMethod
import com.android.identity.android.mdoc.transport.DataTransportOptions
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.EcPublicKey
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod.Companion.disambiguate
import com.android.identity.mdoc.engagement.EngagementGenerator
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.Logger
import java.util.concurrent.Executor

/**
 * Helper used for QR engagement.
 *
 * This implements QR engagement as defined in ISO/IEC 18013-5:2021.
 *
 * Applications can instantiate a [QrEngagementHelper] using
 * [QrEngagementHelper.Builder], specifying which device retrieval methods
 * to support using [QrEngagementHelper.Builder.setConnectionMethods]
 * or [QrEngagementHelper.Builder.setTransports].
 *
 * When a remote mdoc reader connects to one of the advertised transports, the
 * [Listener.onDeviceConnected] is called and the application can use the passed-in
 * [DataTransport] for transacting with the mdoc reader.
 */
class QrEngagementHelper internal constructor(
    context: Context,
    eDeviceKey: EcPublicKey,
    connectionMethods: List<MdocConnectionMethod>?,
    transports: List<DataTransport>?,
    options: DataTransportOptions,
    private val listener: Listener?,
    private val executor: Executor?
) {
    private var inhibitCallbacks = false
    private var transports = mutableListOf<DataTransport>()

    /**
     * The bytes of the `DeviceEngagement` CBOR.
     *
     * This contains the bytes of the `DeviceEngagement` CBOR according to
     * ISO/IEC 18013-5:2021 section 8.2.1.1 with the device retrieval methods
     * specified using [QrEngagementHelper.Builder].
     */
    val deviceEngagement: ByteArray

    /**
     * The bytes of the `Handover` CBOR.
     *
     * This contains the bytes of the `Handover` CBOR according to
     * ISO/IEC 18013-5:2021 section 9.1.5.1. For QR Code the Handover is
     * always defined as CBOR with the `null` value.
     */
    val handover: ByteArray

    private var reportedDeviceConnecting = false

    init {
        val encodedEDeviceKeyBytes =
            Cbor.encode(Tagged(24, Bstr(Cbor.encode(eDeviceKey.toCoseKey().toDataItem()))))

        // Set EDeviceKey for transports we were given.
        if (transports != null) {
            for (transport in transports) {
                transport.setEDeviceKeyBytes(encodedEDeviceKeyBytes)
                this.transports.add(transport)
            }
        }
        if (connectionMethods != null) {
            // Need to disambiguate the connection methods here to get e.g. two ConnectionMethods
            // if both BLE modes are available at the same time.
            val disambiguatedMethods = disambiguate(connectionMethods, MdocRole.MDOC)
            for (cm in disambiguatedMethods) {
                val transport = fromConnectionMethod(
                    context, cm, DataTransport.Role.MDOC, options
                )
                transport.setEDeviceKeyBytes(encodedEDeviceKeyBytes)
                this.transports.add(transport)
                Logger.d(TAG, "Added transport for $cm")
            }
        }

        // Careful, we're using the user-provided Executor below so these callbacks might happen
        // in another thread than we're in right now. For example this happens if using
        // ThreadPoolExecutor.
        //
        for (transport in this.transports) {
            transport.setListener(object : DataTransport.Listener {
                override fun onConnecting() {
                    Logger.d(TAG, "onConnecting for $transport")
                    peerIsConnecting()
                }

                override fun onConnected() {
                    Logger.d(TAG, "onConnected for $transport")
                    peerHasConnected(transport)
                }

                override fun onDisconnected() {
                    Logger.d(TAG, "onDisconnected for $transport")
                    transport.close()
                }

                override fun onError(error: Throwable) {
                    transport.close()
                    reportError(error)
                }

                override fun onMessageReceived() {
                    Logger.d(TAG, "onMessageReceived for $transport")
                }

                override fun onTransportSpecificSessionTermination() {
                    Logger.d(TAG, "Received transport-specific session termination")
                    transport.close()
                }
            }, executor)
            Logger.d(TAG, "Connecting to transport $transport")
            transport.connect()
        }
        Logger.d(TAG, "All transports are now set up")
        val connectionMethodsSetup = ArrayList<MdocConnectionMethod>()
        for (transport in this.transports) {
            connectionMethodsSetup.add(transport.connectionMethodForTransport)
        }

        // Calculate DeviceEngagement and Handover for QR code...
        //
        val engagementGenerator =
            EngagementGenerator(eDeviceKey, EngagementGenerator.ENGAGEMENT_VERSION_1_0)
        engagementGenerator.addConnectionMethods(connectionMethodsSetup)
        deviceEngagement = engagementGenerator.generate()
        handover = Cbor.encode(Simple.NULL)
        Logger.dCbor(TAG, "QR DE", deviceEngagement)
        Logger.dCbor(TAG, "QR handover", handover)
    }

    /**
     * Close all transports currently being listened on.
     *
     * No callbacks will be done on a listener after calling this.
     *
     * This method is idempotent so it is safe to call multiple times.
     */
    fun close() {
        inhibitCallbacks = true
        if (!transports.isEmpty()) {
            for (transport in transports) {
                transport.close()
            }
            transports.clear()
        }
    }

    /**
     * `DeviceEngagement` CBOR as as a URI-encoded string.
     *
     * This is like [deviceEngagement] except that it encodes the `DeviceEngagement`
     * CBOR according to ISO/IEC 18013-5:2021 section 8.2.2.3, that is with "mdoc:"
     * as scheme and the bytes of the `DeviceEngagement` CBOR encoded using
     * base64url-without-padding, according to RFC 4648, as path.
     */
    val deviceEngagementUriEncoded: String
        get() {
            val base64EncodedDeviceEngagement = Base64.encodeToString(
                deviceEngagement,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            return Uri
                .Builder()
                .scheme("mdoc")
                .encodedOpaquePart(base64EncodedDeviceEngagement)
                .build()
                .toString()
        }

    private fun peerIsConnecting() {
        if (!reportedDeviceConnecting) {
            reportedDeviceConnecting = true
            reportDeviceConnecting()
        }
    }

    private fun peerHasConnected(transport: DataTransport) {
        // stop listening on other transports
        //
        Logger.d(TAG, "Peer has connected on transport $transport - shutting down other transports")
        for (t in transports) {
            if (t !== transport) {
                t.setListener(null, null)
                t.close()
            }
        }
        transports.clear()
        transport.setListener(null, null)
        reportDeviceConnected(transport)
    }

    // Note: The report*() methods are safe to call from any thread.
    private fun reportDeviceConnecting() {
        reportEvent("reportDeviceConnecting") { listener -> listener.onDeviceConnecting() }
    }

    private fun reportDeviceConnected(transport: DataTransport) {
        reportEvent("reportDeviceConnected") { listener -> listener.onDeviceConnected(transport) }
    }

    private fun reportError(error: Throwable) {
        reportEvent("reportError: error: ", error) { listener -> listener.onError(error) }
    }

    /** Common reporting code */
    private fun reportEvent(
        logMessage: String,
        logError: Throwable? = null,
        event: (Listener) -> Unit
    ) {
        if (logError != null) {
            Logger.d(TAG, logMessage, logError)
        } else {
            Logger.d(TAG, logMessage)
        }
        val currentListener: Listener? = listener
        val currentExecutor: Executor? = executor
        if (currentListener != null && currentExecutor != null) {
            currentExecutor.execute {
                if (!inhibitCallbacks) {
                    event(currentListener)
                }
            }
        }
    }

    /**
     * Listener interface for [QrEngagementHelper].
     */
    interface Listener {
        /**
         * Called when a remote mdoc reader is starting to connect.
         */
        fun onDeviceConnecting()

        /**
         * Called when a remote mdoc reader has connected.
         *
         *
         * The application should use the passed-in [DataTransport] with
         * [com.android.identity.android.mdoc.deviceretrieval.DeviceRetrievalHelper]
         * to start the transaction.
         *
         *
         * After this is called, no more callbacks will be done on listener and all other
         * listening transports will be closed. Calling [.close] will not close the
         * passed-in transport.
         *
         * @param transport a [DataTransport] for the connection to the remote mdoc reader.
         */
        fun onDeviceConnected(transport: DataTransport)

        /**
         * Called when an irrecoverable error has occurred.
         *
         * @param error details of what error has occurred.
         */
        fun onError(error: Throwable)
    }

    /**
     * A builder for [QrEngagementHelper].
     */
    /**
     * Creates a new builder for [QrEngagementHelper].
     *
     * @param context application context.
     * @param eDeviceKey the public part of `EDeviceKey` for *mdoc session
     * encryption* according to ISO/IEC 18013-5:2021 section 9.1.1.4.
     * @param options set of options for creating [DataTransport] instances.
     * @param listener the listener.
     * @param executor a [Executor] to use with the listener.
     */
    class Builder(
        private val context: Context,
        private val eDeviceKey: EcPublicKey,
        private val options: DataTransportOptions,
        private val listener: Listener,
        private val executor: Executor
    ) {
        private var connectionMethods: List<MdocConnectionMethod>? = null
        private var transports: List<DataTransport>? = null

        /**
         * Sets the connection methods to use.
         *
         * This is used to indicate which connection methods should be used for QR engagement.
         *
         * @param connectionMethods a list of [MdocConnectionMethod] instances.
         * @return the builder.
         */
        fun setConnectionMethods(connectionMethods: List<MdocConnectionMethod>) = apply {
            this.connectionMethods = connectionMethods
        }

        /**
         * Sets data transports to use.
         *
         * @param transports a list of [DataTransport] instances.
         * @return the builder.
         */
        fun setTransports(transports: List<DataTransport>) = apply {
            this.transports = transports
        }

        /**
         * Builds the [QrEngagementHelper] and starts listening for connections.
         *
         * @return the helper, ready to be used.
         */
        fun build(): QrEngagementHelper {
            return QrEngagementHelper(
                context,
                eDeviceKey,
                connectionMethods,
                transports,
                options,
                listener,
                executor
            )
        }
    }

    companion object {
        private const val TAG = "QrEngagementHelper"
    }
}