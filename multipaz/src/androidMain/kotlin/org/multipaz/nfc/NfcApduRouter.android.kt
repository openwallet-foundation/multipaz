package org.multipaz.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.CancellationException
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.util.Logger

private const val TAG = "NfcApduRouterBase"

// HostApduService's no-arg constructor initializes a Messenger(new MsgHandler()), which creates
// an android.os.Handler and throws if the current thread does not have a Looper prepared. In
// production Android instantiates the Service on the main thread (where the main Looper is
// already prepared), but in instrumented tests (e.g. commonTest running under
// connectedDebugAndroidTest) NfcApduRouter may be instantiated on a coroutine worker thread.
// Delegating actual constructor() -> this(ensureLooper()) ensures Looper.prepare() runs on the
// calling thread before HostApduService.<init>() is invoked.
private fun ensureLooper() {
    try {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
    } catch (_: Throwable) {
    }
}

/**
 * Android actual for [NfcApduRouterBase] extending [HostApduService].
 */
actual abstract class NfcApduRouterBase private constructor(
    @Suppress("UNUSED_PARAMETER") looperPrepared: Unit
) : HostApduService() {
    actual constructor() : this(ensureLooper())

    final override fun onCreate() {
        super.onCreate()
        applicationContext?.let {
            initializeApplication(it)
        }
        start()
    }

    final override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    actual open fun start() {}

    actual open fun stop() {}

    actual abstract fun processCommandApdu(commandApdu: CommandApdu): ResponseApdu?

    final override fun processCommandApdu(encodedCommandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        if (encodedCommandApdu == null) return null
        Logger.dHex(TAG, "Handling Command APDU", encodedCommandApdu)
        // With Extended APDUs it's possible we get a partial APDU so gracefully handle if decoding
        // fails. Simply log and discard, we'll likely get hit with an onDeactivated call soon anyway.
        val commandApdu = try {
            CommandApdu.decode(encodedCommandApdu)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Error decoding APDU", e)
            return null
        }
        return processCommandApdu(commandApdu)?.encode()
    }

    actual abstract fun deactivate(reason: NfcDeactivationReason)

    final override fun onDeactivated(reason: Int) {
        val deactivationReason = when (reason) {
            DEACTIVATION_DESELECTED -> NfcDeactivationReason.DESELECTED
            else -> NfcDeactivationReason.LINK_LOSS
        }
        deactivate(deactivationReason)
    }

    actual open fun sendResponseApdu(responseApdu: ResponseApdu) {
        if (!NfcApduRouter.forwardResponseApduIfHandled(responseApdu)) {
            sendResponseApdu(responseApdu.encode())
        }
    }
}
