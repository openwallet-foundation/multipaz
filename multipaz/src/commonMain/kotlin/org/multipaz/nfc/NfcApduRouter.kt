package org.multipaz.nfc

import kotlinx.io.bytestring.ByteString
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.concurrent.Volatile

private const val TAG = "NfcApduRouter"

/**
 * Backend interface for routing APDUs to and from an [NfcApduRouter] on platforms without
 * OS-level Host Card Emulation (such as Desktop/Linux JVM with an NCI controller).
 */
interface NfcApduRouterBackend {
    /**
     * Called when an [NfcApduRouter] is started via [NfcApduRouter.start].
     *
     * @param router the active [NfcApduRouter] instance.
     */
    fun onRouterStarted(router: NfcApduRouter)

    /**
     * Called when an [NfcApduRouter] is stopped via [NfcApduRouter.stop].
     *
     * @param router the [NfcApduRouter] instance being stopped.
     */
    fun onRouterStopped(router: NfcApduRouter)

    /**
     * Called when an [NfcApduRouter] (or one of its [NfcApduService]s) sends a [ResponseApdu]
     * asynchronously via [NfcApduRouter.sendResponseApdu].
     *
     * @param responseApdu the [ResponseApdu] to transmit to the remote reader.
     */
    fun sendResponseApdu(responseApdu: ResponseApdu)
}

/**
 * A router which routes incoming NFC [CommandApdu]s by ISO/IEC 7816-4 Application ID (AID) to
 * multiple [NfcApduService] instances.
 *
 * When a `SELECT APPLICATION` command APDU (`INS = 0xA4`, `P1 = 0x04`) is received, [NfcApduRouter]
 * inspects the AID in the command payload and routes that command and all subsequent commands (as
 * well as [deactivate] events) to the matching [NfcApduService] returned by [buildServices]. If no
 * service is registered for the selected AID, `6A82`
 * ([Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND]) is returned.
 *
 * ### Usage
 * Subclass [NfcApduRouter] and override [buildServices] to map each supported AID to its
 * corresponding [NfcApduService]:
 *
 * ```kotlin
 * class TestAppNfcApduRouter : NfcApduRouter() {
 *     override fun buildServices(): Map<ByteString, NfcApduService> {
 *         return mapOf(
 *             Nfc.NDEF_APPLICATION_ID to TestAppMdocNdefService(),
 *             Nfc.ISO_MDOC_NFC_CCE_APPLICATION_ID to TestAppMdocNfcCceService(),
 *             Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID to MdocNfcDataTransferService()
 *         )
 *     }
 * }
 * ```
 *
 * On Android, [NfcApduRouter] extends `android.nfc.cardemulation.HostApduService` (via
 * [NfcApduRouterBase]) so that a single OS `HostApduService` can handle multiple AIDs (which is
 * required because `CardEmulation.setPreferredService()` only supports a single service per
 * foreground activity). Register the subclass in `AndroidManifest.xml`:
 *
 * ```xml
 * <service
 *     android:name=".TestAppNfcApduRouter"
 *     android:exported="true"
 *     android:permission="android.permission.BIND_NFC_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.nfc.cardemulation.action.HOST_APDU_SERVICE" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="android.nfc.cardemulation.host_apdu_service"
 *         android:resource="@xml/nfc_apdu_router" />
 * </service>
 * ```
 *
 * And declare all routed AIDs in the corresponding `@xml/nfc_apdu_router` resource:
 *
 * ```xml
 * <host-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:requireDeviceUnlock="false"
 *     android:requireDeviceScreenOn="false">
 *     <aid-group android:category="other">
 *         <aid-filter android:name="D2760000850101" />
 *         <aid-filter android:name="A0000002480401" />
 *         <aid-filter android:name="A0000002480400" />
 *     </aid-group>
 * </host-apdu-service>
 * ```
 */
abstract class NfcApduRouter : NfcApduRouterBase() {

    protected lateinit var services: Map<ByteString, NfcApduService>

    /**
     * Returns a service mapping from AID to [NfcApduService].
     */
    abstract fun buildServices(): Map<ByteString, NfcApduService>

    override fun start() {
        super.start()

        services = buildServices()

        services.forEach { (_, service) ->
            service.sendResponseCallback = ::sendResponseApdu
            service.start()
        }
        notifyRouterStarted(this)
    }

    override fun stop() {
        notifyRouterStopped(this)
        super.stop()
        if (::services.isInitialized) {
            services.forEach { (_, service) ->
                service.stop()
            }
        }
    }

    companion object {
        @Volatile
        private var registeredBackend: NfcApduRouterBackend? = null

        @Volatile
        private var activeRouter: NfcApduRouter? = null

        /**
         * Registers an [NfcApduRouterBackend] to handle card emulation for [NfcApduRouter]
         * instances. If an [NfcApduRouter] has already been started,
         * [NfcApduRouterBackend.onRouterStarted] is invoked immediately.
         *
         * @param backend the [NfcApduRouterBackend] to register.
         */
        fun registerBackend(backend: NfcApduRouterBackend) {
            registeredBackend = backend
            activeRouter?.let { backend.onRouterStarted(it) }
        }

        /**
         * Unregisters a previously registered [NfcApduRouterBackend].
         *
         * @param backend the [NfcApduRouterBackend] to unregister.
         */
        fun unregisterBackend(backend: NfcApduRouterBackend) {
            if (registeredBackend === backend) {
                activeRouter?.let { backend.onRouterStopped(it) }
                registeredBackend = null
            }
        }

        internal fun notifyRouterStarted(router: NfcApduRouter) {
            activeRouter = router
            registeredBackend?.onRouterStarted(router)
        }

        internal fun notifyRouterStopped(router: NfcApduRouter) {
            if (activeRouter === router) {
                activeRouter = null
            }
            registeredBackend?.onRouterStopped(router)
        }

        internal fun forwardResponseApduIfHandled(responseApdu: ResponseApdu): Boolean {
            val backend = registeredBackend ?: return false
            backend.sendResponseApdu(responseApdu)
            return true
        }
    }

    // Track which AID the reader selected so subsequent APDUs are routed correctly
    private var activeAid: ByteString? = null

    override fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu? {
        if (commandApdu.isApplicationSelect) {
            val newAid = commandApdu.payload
            val previousAid = activeAid
            if (previousAid != null && previousAid != newAid) {
                services[previousAid]?.deactivate(NfcDeactivationReason.DESELECTED)
            }
            activeAid = newAid
            Logger.i(TAG, "AID ${newAid.toHex()} has been selected")
        }

        // Route the APDU to the appropriate handler based on the active session
        val currentAid = activeAid
        if (currentAid == null) {
            Logger.w(TAG, "No AID selected, returning 6a82")
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
        }
        services[currentAid]?.let { service ->
            service.receiveCommandApdu(commandApdu)
            return null
        }
        Logger.w(TAG, "No handler for AID ${currentAid.toHex()}, returning 6a82")
        return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND)
    }

    override fun deactivate(reason: NfcDeactivationReason) {
        Logger.d(TAG, "deactivate reason $reason")
        activeAid?.let { activeAid ->
            services[activeAid]?.deactivate(reason)
        }
        activeAid = null
    }
}

/**
 * Platform base class for [NfcApduRouter].
 *
 * On Android this extends `android.nfc.cardemulation.HostApduService`.
 */
expect abstract class NfcApduRouterBase() {
    /**
     * Starts the router and all registered [NfcApduService] handlers.
     */
    open fun start()

    /**
     * Stops the router and all registered [NfcApduService] handlers.
     */
    open fun stop()

    /**
     * Called when a [CommandApdu] has been received from a remote device.
     *
     * @param commandApdu the [CommandApdu] that was received.
     * @return a [ResponseApdu] to send back synchronously, or `null` if the response will be
     *   sent later via [sendResponseApdu].
     */
    abstract fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu?

    /**
     * Called when the NFC link has been deactivated or deselected.
     *
     * @param reason the [NfcDeactivationReason] for deactivation.
     */
    abstract fun deactivate(reason: NfcDeactivationReason)

    /**
     * Sends a [ResponseApdu] back to the remote device.
     *
     * @param responseApdu the [ResponseApdu] to send.
     */
    open fun sendResponseApdu(responseApdu: ResponseApdu)
}
