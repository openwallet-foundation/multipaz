package org.multipaz.legacytestapp

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.android.identity.android.mdoc.transport.DataTransportNfc
import com.android.identity.android.util.NfcUtil
import org.multipaz.context.initializeApplication
import org.multipaz.util.Logger

/**
 * [HostApduService] handling both NFC Type 4 Tag NDEF engagement (`D2760000850101`) via
 * [com.android.identity.android.mdoc.engagement.NfcEngagementHelper] and ISO/IEC 18013-5
 * NFC data transfer (`A0000002480400`) via [DataTransportNfc].
 */
class LegacyNfcApduService : HostApduService() {

    private enum class ActiveAid {
        NDEF_ENGAGEMENT,
        MDL_DATA_TRANSFER
    }

    private var activeAid: ActiveAid? = null

    override fun onCreate() {
        super.onCreate()
        initializeApplication(applicationContext)
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        val commandType = NfcUtil.nfcGetCommandType(commandApdu)
        if (commandType == NfcUtil.COMMAND_TYPE_SELECT_BY_AID && commandApdu.size >= 12) {
            val aid = commandApdu.copyOfRange(5, 12)
            if (aid.contentEquals(NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION)) {
                Logger.i(TAG, "NDEF Engagement AID selected")
                activeAid = ActiveAid.NDEF_ENGAGEMENT
                return LegacyPresentmentManager.nfcProcessCommandApdu(this, commandApdu)
            } else if (aid.contentEquals(NfcUtil.AID_FOR_MDL_DATA_TRANSFER)) {
                Logger.i(TAG, "ISO 18013-5 NFC Data Transfer AID selected")
                activeAid = ActiveAid.MDL_DATA_TRANSFER
                return DataTransportNfc.processCommandApdu(this, commandApdu)
            }
        }

        return when (activeAid) {
            ActiveAid.NDEF_ENGAGEMENT -> {
                LegacyPresentmentManager.nfcProcessCommandApdu(this, commandApdu)
            }
            ActiveAid.MDL_DATA_TRANSFER -> {
                DataTransportNfc.processCommandApdu(this, commandApdu)
            }
            null -> {
                Logger.w(TAG, "processCommandApdu called with no active AID selected")
                NfcUtil.STATUS_WORD_FILE_NOT_FOUND
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        Logger.i(TAG, "onDeactivated: reason=$reason activeAid=$activeAid")
        when (activeAid) {
            ActiveAid.NDEF_ENGAGEMENT -> {
                LegacyPresentmentManager.nfcOnDeactivated(reason)
            }
            ActiveAid.MDL_DATA_TRANSFER -> {
                DataTransportNfc.onDeactivated(reason)
            }
            null -> {}
        }
        activeAid = null
    }

    companion object {
        private const val TAG = "LegacyNfcApduService"
    }
}
