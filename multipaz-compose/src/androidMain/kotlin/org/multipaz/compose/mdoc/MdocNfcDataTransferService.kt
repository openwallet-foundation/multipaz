package org.multipaz.compose.mdoc

import android.content.Context
import kotlinx.coroutines.CancellationException
import android.os.Bundle
import org.multipaz.mdoc.transport.NfcTransportMdoc
import org.multipaz.util.Logger

/**
 * Base class for implementing NFC data transfer according to ISO/IEC 18013-5:2021.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 * for binding to the mdoc NFC data transfer AID (A0000002480400).
 *
 * @property applicationContext the [Context], passed by [CombinedNfcService].
 * @property sendResponse a function to send a response APDU via [CombinedNfcService].
 */
open class MdocNfcDataTransferService(
    applicationContext: Context,
    sendResponse: (ByteArray) -> Unit
) : NfcApduService(applicationContext, sendResponse) {
    companion object {
        private val TAG = "MdocNfcDataTransferService"
    }

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onCreate() {
        Logger.i(TAG, "onCreate")
        super.onCreate()
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        Logger.i(TAG, "processCommandApdu")
        try {
            NfcTransportMdoc.processCommandApdu(
                commandApdu = commandApdu,
                sendResponse = { responseApdu -> sendResponseApdu(responseApdu) }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "processCommandApdu", e)
            e.printStackTrace()
        }
        return null
    }

    override fun onDeactivated(reason: Int) {
        Logger.i(TAG, "onDeactivated $reason")
        NfcTransportMdoc.onDeactivated()
    }
}