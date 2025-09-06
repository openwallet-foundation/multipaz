/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.mdoc.engagement

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod.Companion.fromDeviceEngagement
import org.multipaz.mdoc.origininfo.OriginInfo
import org.multipaz.util.Logger

/**
 * Helper for parsing `DeviceEngagement` or `ReaderEngagement` CBOR.
 *
 * @param encodedEngagement the bytes of the `Engagement` structure.
 */
@Deprecated(message = "This class is deprecated, use DeviceEngagement instead.")
class EngagementParser(private val encodedEngagement: ByteArray) {
    /**
     * Parses the given `Engagement` structure.
     *
     * @return A [Engagement] object with the parsed data.
     */
    fun parse(): Engagement {
        val engagement = Engagement()
        engagement.parse(encodedEngagement)
        return engagement
    }

    /**
     * An object used to represent data extracted from an `Engagement` structure.
     */
    class Engagement internal constructor() {
        /**
         * Gets the version string set in the `Engagement` CBOR, e.g. "1.0" or "1.1".
         */
        lateinit var version: String

        /**
         * The ephemeral key used by the other side.
         *
         * If used in an mdoc reader (when parsing `DeviceEngagement`) this will be eDeviceKey
         * and if used in an mdoc (when parsing `ReaderEngagement`) this will be eReaderKey.
         */
        lateinit var eSenderKey: EcPublicKey

        /**
         * The encoding of the key that was sent from the other side.
         *
         * The returned data are the bytes of `ESenderKeyBytes` which is defined
         * as `#6.24(bstr .cbor ESenderKey)` where `ESenderKey` is a
         * `COSE_Key`.
         */
        lateinit var eSenderKeyBytes: ByteArray

        /**
         * The connection methods in the engagement.
         */
        lateinit var connectionMethods: List<MdocConnectionMethod>

        /**
         * The origin infos in the engagement.
         */
        lateinit var originInfos: List<OriginInfo>

        fun parse(encodedEngagement: ByteArray) {
            val map = Cbor.decode(encodedEngagement)
            version = map[0].asTstr
            val security = map[1]
            val cipherSuite = security[0].asNumber
            require(cipherSuite == 1L) { "Expected cipher suite 1, got $cipherSuite" }
            eSenderKey = security[1].asTaggedEncodedCbor.asCoseKey.ecPublicKey
            this.eSenderKeyBytes = Cbor.encode(security[1])
            val connectionMethodsArray = map.getOrNull(2)
            val cms = mutableListOf<MdocConnectionMethod>()
            if (connectionMethodsArray != null) {
                for (cmDataItem in (connectionMethodsArray as CborArray).items) {
                    val connectionMethod = fromDeviceEngagement(
                        Cbor.encode(cmDataItem)
                    )
                    if (connectionMethod != null) {
                        cms.add(connectionMethod)
                    }
                }
            }
            connectionMethods = cms
            val ois = mutableListOf<OriginInfo>()
            if (version >= "1.1") {
                // 18013-7 defines key 5 as having origin info
                if (map.hasKey(5)) {
                    val originInfoItems: List<DataItem> = (map[5] as CborArray?)!!.items
                    for (oiDataItem in originInfoItems) {
                        try {
                            val originInfo = OriginInfo.fromDataItem(oiDataItem)
                            if (originInfo != null) {
                                ois.add(originInfo)
                            }
                        } catch (e: Exception) {
                            Logger.w(TAG, "OriginInfo is incorrectly formatted.", e)
                        }
                    }
                }
            } else {
                if (map.hasKey(5)) {
                    Logger.w(TAG,
                        "Ignoring key 5 in Engagement as version is set to $version. "
                                + "The mdoc application producing this DeviceEngagement is "
                                + "not compliant to ISO/IEC 18013-5:2021."
                    )
                }
            }
            originInfos = ois
        }

        companion object {
            private const val TAG = "Engagement"
        }
    }
}
