package org.multipaz.mdoc.connectionmethod

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NdefRecord
import org.multipaz.util.Logger

/**
 * Connection method for NFCv2.
 */
class MdocConnectionMethodNfcV2 : MdocConnectionMethod() {
    override fun equals(other: Any?): Boolean = other is MdocConnectionMethodNfcV2

    override fun hashCode(): Int = MdocConnectionMethodNfcV2::class.hashCode()

    override fun toString(): String = "nfcv2"

    override fun toDeviceEngagement(): ByteArray {
        return Cbor.encode(
            buildCborArray {
                add(METHOD_TYPE)
                add(METHOD_MAX_VERSION)
                addCborMap {
                }
            }
        )
    }

    override fun toNdefRecord(
        auxiliaryReferences: List<String>,
        role: MdocRole,
        skipUuids: Boolean
    ): Pair<NdefRecord, NdefRecord>? {
        Logger.w(TAG, "MdocConnectionMethodNfcV2 should never appear in a NDEF record")
        return null
    }

    companion object {
        private const val TAG = "MdocConnectionMethodNfcV2"

        /**
         * The device retrieval method type for NFC according to ISO/IEC 18013-5 Second Edition
         */
        const val METHOD_TYPE = 5L

        /**
         * The supported version of the device retrieval method type for NFC.
         */
        const val METHOD_MAX_VERSION = 1L

        private const val OPTION_KEY_APDU_RESPONSE_MAX_LENGTH = 0L

        internal fun fromDeviceEngagement(encodedDeviceRetrievalMethod: ByteArray): MdocConnectionMethodNfcV2? {
            val array = Cbor.decode(encodedDeviceRetrievalMethod)
            val type = array[0].asNumber
            val version = array[1].asNumber
            require(type == METHOD_TYPE)
            if (version > METHOD_MAX_VERSION) {
                return null
            }
            val map = array[2]
            if (map.asMap.isNotEmpty()) {
                Logger.w(TAG, "Unexpected non-empty map in DeviceEngagement for NFCv2")
            }
            return MdocConnectionMethodNfcV2()
        }
    }
}