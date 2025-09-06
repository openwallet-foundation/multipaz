package org.multipaz.mdoc.engagement

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod.Companion.fromDeviceEngagement
import org.multipaz.mdoc.origininfo.OriginInfo
import org.multipaz.util.Logger

/**
 * Device Engagement according to ISO 18013-5.
 *
 * To construct an instance use [buildDeviceEngagement].
 *
 * For an engagement received from a remote mdoc use the [DeviceEngagement.Companion.fromDataItem] method.
 *
 * @property version the version of the device engagement structure, must be `1.0` or `1.1`.
 * @property eDeviceKey the ephemeral session encryption key for the mdoc.
 * @property eDeviceKeyBytes the exact bytes of the [eDeviceKey] as encoded by the mdoc, including the #6.24 tag.
 * @property connectionMethods the Device Retrieval methods in the engagement, if any.
 * @property originInfos the origin infos in the engagement, if any.
 * @property capabilities the capabilities available, along with their values.
 */
@ConsistentCopyVisibility
data class DeviceEngagement private constructor(
    val version: String,
    val eDeviceKey: EcPublicKey,
    val eDeviceKeyBytes: ByteString,
    val connectionMethods: List<MdocConnectionMethod>,
    val originInfos: List<OriginInfo>,
    val capabilities: Map<Capability, DataItem>
) {

    init {
        if (originInfos.isEmpty() && capabilities.isEmpty()) {
            check(version == "1.0") {
                "DeviceEngagement version must be 1.0 when originInfos and capabilities are both empty"
            }
        }
        if (originInfos.isNotEmpty() || capabilities.isNotEmpty()) {
            check(version >= "1.1") {
                "DeviceEngagement version must be 1.1 or higher when originInfos or capabilities are non-empty"
            }
        }
    }

    /**
     * Generates CBOR compliant with the CDDL for `DeviceEngagement` according to ISO 18013-5.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        put(0, version)
        putCborArray(1) {
            add(1) // cipher suite
            addTaggedEncodedCbor(Cbor.encode(this@DeviceEngagement.eDeviceKey.toCoseKey().toDataItem()))
        }
        if (connectionMethods.isNotEmpty()) {
            putCborArray(2) {
                connectionMethods.forEach { connectionMethod ->
                    add(RawCbor(connectionMethod.toDeviceEngagement()))
                }
            }
        }
        if (version >= "1.1") {
            // OriginInfos MUST be present even if empty, for compatibility with ISO/IEC 18013-7:2024
            putCborArray(5) {
                originInfos.forEach { originInfo ->
                    add(originInfo.toDataItem())
                }
            }
        }
        if (capabilities.isNotEmpty()) {
            putCborMap(6) {
                capabilities.forEach { (capability, value) ->
                    put(capability.identifier.toDataItem(), value)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DeviceEngagement"

        /**
         * Parses CBOR compliant with the CDDL for `DeviceEngagement` according to ISO 18013-5.
         *
         * @param dataItem the `DeviceEngagement` CBOR.
         * @return the parsed [DeviceEngagement].
         */
        fun fromDataItem(dataItem: DataItem): DeviceEngagement {
            val version = dataItem[0].asTstr
            val security = dataItem[1]
            val cipherSuite = security[0].asNumber
            require(cipherSuite == 1L) { "Expected cipher suite 1, got $cipherSuite" }
            val eDeviceKey = security[1].asTaggedEncodedCbor.asCoseKey.ecPublicKey
            val eDeviceKeyBytes = ByteString(Cbor.encode(security[1]))
            val connectionMethodsArray = dataItem.getOrNull(2)
            val connectionMethods = mutableListOf<MdocConnectionMethod>()
            if (connectionMethodsArray != null) {
                for (cmDataItem in (connectionMethodsArray as CborArray).items) {
                    val connectionMethod = fromDeviceEngagement(
                        Cbor.encode(cmDataItem)
                    )
                    if (connectionMethod != null) {
                        connectionMethods.add(connectionMethod)
                    }
                }
            }
            val originInfos = mutableListOf<OriginInfo>()
            // 18013-7 defines key 5 as having origin info
            if (dataItem.hasKey(5)) {
                val originInfoItems: List<DataItem> = (dataItem[5] as CborArray?)!!.items
                for (oiDataItem in originInfoItems) {
                    try {
                        val originInfo = OriginInfo.fromDataItem(oiDataItem)
                        if (originInfo != null) {
                            originInfos.add(originInfo)
                        }
                    } catch (e: Throwable) {
                        Logger.w(TAG, "OriginInfo is incorrectly formatted", e)
                    }
                }
            }
            val capabilities = mutableMapOf<Capability, DataItem>()
            dataItem.getOrNull(6)?.asMap?.forEach { (key, value) ->
                val capabilityIdentifier = key.asNumber.toInt()
                when (capabilityIdentifier) {
                    Capability.HANDOVER_SESSION_ESTABLISHMENT_SUPPORT.identifier -> {
                        capabilities.put(Capability.HANDOVER_SESSION_ESTABLISHMENT_SUPPORT, value)
                    }
                    Capability.READER_AUTH_ALL_SUPPORT.identifier -> {
                        capabilities.put(Capability.READER_AUTH_ALL_SUPPORT, value)
                    }
                    Capability.EXTENDED_REQUEST_SUPPORT.identifier -> {
                        capabilities.put(Capability.EXTENDED_REQUEST_SUPPORT, value)
                    }
                    else -> {
                        Logger.w(TAG, "Ignoring capability with identifier $capabilityIdentifier")
                    }
                }
            }
            return DeviceEngagement(
                version = version,
                eDeviceKey = eDeviceKey,
                eDeviceKeyBytes = eDeviceKeyBytes,
                connectionMethods = connectionMethods,
                originInfos = originInfos,
                capabilities = capabilities
            )
        }
    }

    /**
     * A builder for [DeviceEngagement].
     *
     * @param eDeviceKey the ephemeral session encryption key for the mdoc.
     * @param version the version to use or `null` to automatically determine which version to use.
     */
    class Builder(
        val eDeviceKey: EcPublicKey,
        val version: String? = null
    ) {
        private val connectionMethods = mutableListOf<MdocConnectionMethod>()
        private val originInfos = mutableListOf<OriginInfo>()
        private val capabilities = mutableMapOf<Capability, DataItem>()

        /**
         * Adds a [MdocConnectionMethod] to the device engagement being built.
         *
         * @param connectionMethod a [MdocConnectionMethod].
         * @return the builder.
         */
        fun addConnectionMethod(connectionMethod: MdocConnectionMethod): Builder {
            connectionMethods.add(connectionMethod)
            return this
        }

        /**
         * Adds a [OriginInfo] to the device engagement being built.
         *
         * @param originInfo a [OriginInfo].
         * @return the builder.
         */
        fun addOriginInfo(originInfo: OriginInfo): Builder {
            originInfos.add(originInfo)
            return this
        }

        /**
         * Adds a [Capability] to the device engagement being built.
         *
         * @param capability a [Capability].
         * @param value the value of the capability.
         * @return the builder.
         */
        fun addCapability(capability: Capability, value: DataItem): Builder {
            capabilities.put(capability, value)
            return this
        }

        /**
         * Builds the [DeviceEngagement].
         *
         * @return the [DeviceEngagement].
         */
        fun build(): DeviceEngagement {
            val versionToUse = if (version != null) {
                version
            } else {
                if (originInfos.isNotEmpty() || capabilities.isNotEmpty()) {
                    "1.1"
                } else {
                    "1.0"
                }
            }
            return DeviceEngagement(
                version = versionToUse,
                eDeviceKey = eDeviceKey,
                eDeviceKeyBytes = ByteString(
                    Cbor.encode(Tagged(Tagged.ENCODED_CBOR,
                        Bstr(Cbor.encode(eDeviceKey.toCoseKey().toDataItem()))
                    ))
                ),
                connectionMethods = connectionMethods,
                originInfos = originInfos,
                capabilities = capabilities
            )
        }
    }
}

/**
 * Builds a [DeviceEngagement].
 *
 * @param eDeviceKey the ephemeral session encryption key for the mdoc.
 * @param version the version to use or `null` to automatically determine which version to use.
 * @param builderAction the builder action.
 * @return a [DeviceEngagement].
 */
fun buildDeviceEngagement(
    eDeviceKey: EcPublicKey,
    version: String? = null,
    builderAction: DeviceEngagement.Builder.() -> Unit
): DeviceEngagement {
    val builder = DeviceEngagement.Builder(
        eDeviceKey = eDeviceKey,
        version = version
    )
    builder.builderAction()
    return builder.build()
}
