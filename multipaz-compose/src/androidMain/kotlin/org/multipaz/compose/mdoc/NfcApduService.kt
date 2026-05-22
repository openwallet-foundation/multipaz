package org.multipaz.compose.mdoc

import android.content.Context
import android.os.Bundle

/**
 * Interface for NFC APDU services orchestrated by [CombinedNfcService].
 *
 * @property applicationContext the [Context], passed by [CombinedNfcService].
 * @property sendResponse a function to send a response APDU via [CombinedNfcService].
 */
abstract class NfcApduService(
    protected val applicationContext: Context,
    private val sendResponse: (ByteArray) -> Unit
) {
    /**
     * Called when [CombinedNfcService] is created by the OS.
     */
    open fun onCreate() {}

    /**
     * Called when [CombinedNfcService] is destroyed by the OS.
     */
    open fun onDestroy() {}

    /**
     * Called when APDUs arrive
     *
     * @param commandApdu the bytes of the command APDU.
     * @param extras extras.
     * @return the Response APDU or `null`.
     */
    open fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? { return null }

    /**
     * Called when the service is deactivated.
     *
     * @param reason the reason for deactivation.
     */
    open fun onDeactivated(reason: Int) {}

    /**
     * Sends a response APDU.
     *
     * @param apdu the bytes of the Response APDU.
     */
    protected fun sendResponseApdu(apdu: ByteArray) {
        sendResponse(apdu)
    }
}