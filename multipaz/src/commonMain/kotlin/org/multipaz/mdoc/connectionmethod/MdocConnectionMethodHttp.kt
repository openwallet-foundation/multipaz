package org.multipaz.mdoc.connectionmethod

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NdefRecord
import org.multipaz.util.Logger

/**
 * Connection method for HTTP connections.
 *
 * @param uri the URI.
 */
data class MdocConnectionMethodHttp(
    val uri: String
): MdocConnectionMethod() {
    override fun toString(): String = "http:uri=$uri"

    override fun toDeviceEngagement(): ByteArray {
        return Cbor.encode(
            buildCborArray {
                add(METHOD_TYPE)
                add(METHOD_MAX_VERSION)
                addCborMap {
                    put(OPTION_KEY_URI, uri)
                }
            }
        )
    }

    override fun toNdefRecord(
        auxiliaryReferences: List<String>,
        role: MdocRole,
        skipUuids: Boolean
    ): Pair<NdefRecord, NdefRecord>? {
        Logger.w(TAG, "toNdefRecord() not yet implemented")
        return null
    }

    companion object {
        private const val TAG = "MdocConnectionMethodHttp"

        /**
         * The device retrieval method type for HTTP according to ISO/IEC TS 18013-7:2024 annex A.
         */
        const val METHOD_TYPE = 4L

        /**
         * The supported version of the device retrieval method type for HTTP.
         */
        const val METHOD_MAX_VERSION = 1L

        private const val OPTION_KEY_URI = 0L

        internal fun fromDeviceEngagement(encodedDeviceRetrievalMethod: ByteArray): MdocConnectionMethodHttp? {
            val array = Cbor.decode(encodedDeviceRetrievalMethod)
            val type = array[0].asNumber
            val version = array[1].asNumber
            require(type == METHOD_TYPE)
            if (version > METHOD_MAX_VERSION) {
                return null
            }
            val map = array[2]
            return MdocConnectionMethodHttp(map[OPTION_KEY_URI].asTstr)
        }
    }
}
