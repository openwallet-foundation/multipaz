package org.multipaz.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.toHexString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NdefRecord
import org.multipaz.util.Logger

/**
 * Connection method for Wifi Aware.
 *
 * @param passphraseInfoPassphrase  the passphrase or `null`.
 * @param channelInfoChannelNumber  the channel number or unset.
 * @param channelInfoOperatingClass the operating class or unset.
 * @param bandInfoSupportedBands    the supported bands or `null`.
 */
data class MdocConnectionMethodWifiAware(
    val passphraseInfoPassphrase: String?,
    val channelInfoChannelNumber: Long?,
    val channelInfoOperatingClass: Long?,
    val bandInfoSupportedBands: ByteString?
): MdocConnectionMethod() {
    @OptIn(ExperimentalStdlibApi::class)
    override fun toString(): String {
        val builder = StringBuilder("wifi_aware")
        if (passphraseInfoPassphrase != null) {
            builder.append(":passphrase=")
            builder.append(passphraseInfoPassphrase)
        }
        if (channelInfoChannelNumber != null) {
            builder.append(":channel_info_channel_number=")
            builder.append(channelInfoChannelNumber)
        }
        if (channelInfoOperatingClass != null) {
            builder.append(":channel_info_operating_class=")
            builder.append(channelInfoOperatingClass)
        }
        if (bandInfoSupportedBands != null) {
            builder.append(":base_info_supported_bands=")
            builder.append(bandInfoSupportedBands.toHexString())
        }
        return builder.toString()
    }

    override fun toDeviceEngagement(engagementType: MdocEngagementType): ByteArray {
        require(engagementType == MdocEngagementType.DEVICE_QR) {
            "Wi-Fi Aware connection method is not supported in $engagementType"
        }
        return Cbor.encode(
            buildCborArray {
                add(METHOD_TYPE)
                add(METHOD_MAX_VERSION)
                addCborMap {
                    if (passphraseInfoPassphrase != null) {
                        put(OPTION_KEY_PASSPHRASE_INFO_PASSPHRASE, passphraseInfoPassphrase)
                    }
                    if (channelInfoChannelNumber != null) {
                        put(OPTION_KEY_CHANNEL_INFO_CHANNEL_NUMBER, channelInfoChannelNumber)
                    }
                    if (channelInfoOperatingClass != null) {
                        put(OPTION_KEY_CHANNEL_INFO_OPERATING_CLASS, channelInfoOperatingClass)
                    }
                    if (bandInfoSupportedBands != null) {
                        put(OPTION_KEY_BAND_INFO_SUPPORTED_BANDS, bandInfoSupportedBands.toByteArray())
                    }
                }
            }
        )
    }

    override fun toNdefRecord(
        auxiliaryReferences: List<String>,
        role: MdocRole,
        skipUuids: Boolean,
        includeChBleOptions: Boolean,
    ): NdefRecords? {
        Logger.w(TAG, "toNdefRecord() not yet implemented")
        return null
    }

    companion object {
        private const val TAG = "MdocConnectionMethodWifiAware"

        /**
         * The device retrieval method type for Wifi Aware according to ISO/IEC 18013-5:2021 clause 8.2.1.1.
         */
        const val METHOD_TYPE = 3L

        /**
         * The supported version of the device retrieval method type for Wifi Aware.
         */
        const val METHOD_MAX_VERSION = 1L

        private const val OPTION_KEY_PASSPHRASE_INFO_PASSPHRASE = 0L
        private const val OPTION_KEY_CHANNEL_INFO_OPERATING_CLASS = 1L
        private const val OPTION_KEY_CHANNEL_INFO_CHANNEL_NUMBER = 2L
        private const val OPTION_KEY_BAND_INFO_SUPPORTED_BANDS = 3L

        internal fun fromDeviceEngagement(encodedDeviceRetrievalMethod: ByteArray): MdocConnectionMethodWifiAware? {
            val array = Cbor.decode(encodedDeviceRetrievalMethod)
            val type = array[0].asNumber
            val version = array[1].asNumber
            require(type == METHOD_TYPE)
            if (version > METHOD_MAX_VERSION) {
                return null
            }
            val map = array[2]
            val passphraseInfoPassphrase =
                map.getOrNull(OPTION_KEY_PASSPHRASE_INFO_PASSPHRASE)?.asTstr

            var channelInfoChannelNumber: Long? = null
            val cicn = map.getOrNull(OPTION_KEY_CHANNEL_INFO_CHANNEL_NUMBER)
            if (cicn != null) {
                channelInfoChannelNumber = cicn.asNumber
            }

            var channelInfoOperatingClass: Long? = null
            val cioc = map.getOrNull(OPTION_KEY_CHANNEL_INFO_OPERATING_CLASS)
            if (cioc != null) {
                channelInfoOperatingClass = cioc.asNumber
            }
            val bandInfoSupportedBands =
                    map.getOrNull(OPTION_KEY_BAND_INFO_SUPPORTED_BANDS)?.asBstr
            return MdocConnectionMethodWifiAware(
                passphraseInfoPassphrase,
                channelInfoChannelNumber,
                channelInfoOperatingClass,
                bandInfoSupportedBands?.let { ByteString(it) }
            )
        }
    }
}
