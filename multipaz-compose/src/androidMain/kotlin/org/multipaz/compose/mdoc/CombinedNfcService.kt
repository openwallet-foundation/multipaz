package org.multipaz.compose.mdoc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.initializeApplication
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Logger
import org.multipaz.util.toHex

private const val TAG = "CombinedNfcService"

/**
 * A [HostApduService] which can route to multiple other services.
 *
 * A router like this is needed because CardEmulation.setPreferredService() can only
 * handle a single service.
 */
abstract class CombinedNfcService: HostApduService() {

    protected lateinit var services: Map<ByteString, NfcApduService>

    /**
     * Returns a service mapping from AID to [NfcApduService]
     */
    abstract fun buildServices(): Map<ByteString, NfcApduService>

    override fun onCreate() {
        super.onCreate()

        initializeApplication(applicationContext)

        services = buildServices()

        services.forEach { (_, service) ->
            service.onCreate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        services.forEach { (_, service) ->
            service.onDestroy()
        }
    }

    // Track which AID the reader selected so subsequent APDUs are routed correctly
    private var activeAid: ByteString? = null

    override fun processCommandApdu(encodedCommandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        if (encodedCommandApdu == null) return null

        // With Extended APDUs it's possible we get a partial APDU so gracefully handle if decoding fails. Simply
        // log and discard, we'll likely get hit with an onDeactivated call soon anyway.
        //
        val commandApdu = try {
            CommandApdu.decode(encodedCommandApdu)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Error decoding APDU", e)
            return null
        }
        if (commandApdu.ins == Nfc.INS_SELECT && commandApdu.p1 == Nfc.INS_SELECT_P1_APPLICATION) {
            activeAid = commandApdu.payload
            Logger.i(TAG, "AID ${activeAid!!.toHex()} has been selected")
        }

        // Route the APDU to the appropriate handler based on the active session
        activeAid?.let { activeAid ->
            services[activeAid]?.let { service ->
                return service.processCommandApdu(encodedCommandApdu, extras)
            }
            return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND).encode()
        }
        return null
    }

    override fun onDeactivated(reason: Int) {
        activeAid?.let { activeAid ->
            services[activeAid]?.let { service ->
                return service.onDeactivated(reason)
            }
        }
        activeAid = null
    }
}