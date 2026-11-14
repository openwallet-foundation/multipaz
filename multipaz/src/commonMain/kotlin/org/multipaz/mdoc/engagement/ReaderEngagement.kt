package org.multipaz.mdoc.engagement

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocEngagementType
import org.multipaz.mdoc.role.MdocRole

/**
 * Reader Engagement according to ISO/IEC 18013-5.
 *
 * To construct an instance use [buildReaderEngagement].
 *
 * For an engagement received from a remote mdoc reader use the [ReaderEngagement.Companion.fromDataItem] method.
 *
 * @property version the version of the reader engagement structure, e.g. `"1.0"` or `"1.1"`.
 * @property eReaderKey the ephemeral session encryption key for the mdoc reader, if present.
 * @property eReaderKeyBytes the exact bytes of the [eReaderKey] as encoded by the mdoc reader, including the #6.24 tag, if present.
 * @property connectionMethods the Device Retrieval methods in the engagement, if any.
 */
@ConsistentCopyVisibility
data class ReaderEngagement private constructor(
    val version: String,
    val eReaderKey: EcPublicKey?,
    val eReaderKeyBytes: ByteString?,
    val connectionMethods: List<MdocConnectionMethod>,
) {
    /**
     * Generates CBOR compliant with the CDDL for `ReaderEngagement` according to ISO/IEC 18013-5
     * using [MdocEngagementType.READER_QR].
     *
     * @return a [DataItem].
     */
    fun toDataItem(): DataItem = toDataItem(MdocEngagementType.READER_QR)

    /**
     * Generates CBOR compliant with the CDDL for `ReaderEngagement` according to ISO/IEC 18013-5.
     *
     * @param engagementType the engagement context ([MdocEngagementType.READER_QR] or
     *   [MdocEngagementType.CCE_REQUEST]).
     * @return a [DataItem].
     */
    fun toDataItem(
        engagementType: MdocEngagementType,
    ): DataItem {
        require(
            engagementType == MdocEngagementType.READER_QR ||
                engagementType == MdocEngagementType.CCE_REQUEST
        ) {
            "Invalid engagementType $engagementType for ReaderEngagement"
        }
        val encodedMethods = MdocConnectionMethod.encodeDeviceRetrievalMethods(
            methods = connectionMethods,
            engagementType = engagementType,
        )
        return buildCborMap {
            put(0, version)
            if (eReaderKey != null) {
                putCborArray(1) {
                    add(1) // cipher suite
                    addTaggedEncodedCbor(Cbor.encode(this@ReaderEngagement.eReaderKey.toCoseKey().toDataItem()))
                }
            }
            if (encodedMethods.isNotEmpty()) {
                putCborArray(2) {
                    encodedMethods.forEach { add(it) }
                }
            }
        }
    }

    companion object {
        /**
         * Computes the `ReaderQrHandover` (`bstr` SHA-256 digest of `ReaderEngagementBytes`, i.e.,
         * `#6.24(bstr .cbor ReaderEngagement)`) according to ISO/IEC 18013-5 Second Edition
         * clause 12.7.1.
         *
         * @param encodedReaderEngagement the exact raw CBOR-encoded `ReaderEngagement` bytes.
         * @return the `ReaderQrHandover` [Bstr] [DataItem].
         */
        suspend fun computeHandover(encodedReaderEngagement: ByteArray): DataItem =
            Bstr(
                Crypto.digest(
                    Algorithm.SHA256,
                    Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedReaderEngagement)))
                )
            )

        /**
         * Parses CBOR compliant with the CDDL for `ReaderEngagement` according to ISO/IEC 18013-5.
         *
         * @param dataItem the `ReaderEngagement` CBOR.
         * @return the parsed [ReaderEngagement].
         */
        fun fromDataItem(dataItem: DataItem): ReaderEngagement =
            fromDataItem(dataItem = dataItem, engagementType = null)

        /**
         * Parses CBOR compliant with the CDDL for `ReaderEngagement` according to ISO/IEC 18013-5,
         * optionally enforcing context-specific constraints for [engagementType].
         *
         * @param dataItem the `ReaderEngagement` CBOR.
         * @param engagementType optional engagement context ([MdocEngagementType.READER_QR] or
         *   [MdocEngagementType.CCE_REQUEST]) to validate against.
         * @return the parsed [ReaderEngagement].
         */
        fun fromDataItem(
            dataItem: DataItem,
            engagementType: MdocEngagementType?,
        ): ReaderEngagement {
            val version = dataItem[0].asTstr
            val security = dataItem.getOrNull(1)
            val eReaderKey: EcPublicKey?
            val eReaderKeyBytes: ByteString?
            if (security != null) {
                val cipherSuite = security[0].asNumber
                require(cipherSuite == 1L) { "Expected cipher suite 1, got $cipherSuite" }
                eReaderKey = security[1].asTaggedEncodedCbor.asCoseKey.ecPublicKey
                eReaderKeyBytes = ByteString(Cbor.encode(security[1]))
            } else {
                eReaderKey = null
                eReaderKeyBytes = null
            }
            val connectionMethodsArray = dataItem.getOrNull(2)
            if (engagementType == MdocEngagementType.READER_QR) {
                require(version >= "1.1") {
                    "ReaderEngagement version must be at least 1.1 for READER_QR, got $version"
                }
                require(eReaderKey != null) {
                    "Security (key 1) with EReaderKey is required in ReaderEngagement for READER_QR"
                }
                require(connectionMethodsArray != null) {
                    "DeviceRetrievalMethods (key 2) is required in ReaderEngagement for READER_QR"
                }
                for (item in (connectionMethodsArray as CborArray).items) {
                    val methodType = item[0].asNumber
                    require(methodType == MdocConnectionMethod.Ble.METHOD_TYPE) {
                        "Only BLE DeviceRetrievalMethod (type 2) is allowed in READER_QR, got type $methodType"
                    }
                }
            } else if (engagementType == MdocEngagementType.CCE_REQUEST) {
                require(version >= "1.1") {
                    "ReaderEngagement version must be at least 1.1 for CCE_REQUEST, got $version"
                }
                require(eReaderKey != null) {
                    "Security (key 1) with EReaderKey is required in ReaderEngagement for CCE_REQUEST"
                }
            }
            val connectionMethods = connectionMethodsArray?.let {
                MdocConnectionMethod.decodeDeviceRetrievalMethods(
                    items = (it as CborArray).items,
                    senderRole = MdocRole.MDOC_READER,
                )
            } ?: emptyList()
            if (engagementType == MdocEngagementType.READER_QR) {
                require(connectionMethods.isNotEmpty()) {
                    "At least one BLE connection method is required in ReaderEngagement for READER_QR"
                }
                for (method in connectionMethods) {
                    require(method is MdocConnectionMethod.Ble) {
                        "Only BLE connection methods are allowed in ReaderEngagement for READER_QR, got $method"
                    }
                    require(method !is MdocConnectionMethod.Ble.DirectL2capPeripheral) {
                        "DirectL2capPeripheral cannot be used in ReaderEngagement for READER_QR"
                    }
                    require(method.uuid != null) {
                        "uuid is required for BLE connection method in ReaderEngagement for READER_QR: $method"
                    }
                    if (method is MdocConnectionMethod.Ble.DirectL2capCentral) {
                        require(method.psm != null) {
                            "psm is required for DirectL2capCentral in ReaderEngagement for READER_QR"
                        }
                    }
                }
            }
            return ReaderEngagement(
                version = version,
                eReaderKey = eReaderKey,
                eReaderKeyBytes = eReaderKeyBytes,
                connectionMethods = connectionMethods
            )
        }
    }

    /**
     * A builder for [ReaderEngagement].
     *
     * @param eReaderKey the ephemeral session encryption key for the mdoc reader, or `null`.
     * @param version the version to use or `null` to automatically determine which version to use.
     */
    class Builder(
        val eReaderKey: EcPublicKey? = null,
        val version: String? = null
    ) {
        private val connectionMethods = mutableListOf<MdocConnectionMethod>()

        /**
         * Adds a [MdocConnectionMethod] to the reader engagement being built.
         *
         * @param connectionMethod a [MdocConnectionMethod].
         * @return the builder.
         */
        fun addConnectionMethod(connectionMethod: MdocConnectionMethod): Builder {
            connectionMethods.add(connectionMethod)
            return this
        }

        /**
         * Builds the [ReaderEngagement].
         *
         * @return the [ReaderEngagement].
         */
        fun build(): ReaderEngagement {
            val versionToUse = version ?: if (eReaderKey != null || connectionMethods.isNotEmpty()) {
                "1.1"
            } else {
                "1.0"
            }
            val eReaderKeyBytes = eReaderKey?.let {
                ByteString(
                    Cbor.encode(
                        Tagged(
                            Tagged.ENCODED_CBOR,
                            Bstr(Cbor.encode(it.toCoseKey().toDataItem()))
                        )
                    )
                )
            }
            return ReaderEngagement(
                version = versionToUse,
                eReaderKey = eReaderKey,
                eReaderKeyBytes = eReaderKeyBytes,
                connectionMethods = connectionMethods.toList()
            )
        }
    }
}

/**
 * Builds a [ReaderEngagement].
 *
 * @param eReaderKey the ephemeral session encryption key for the mdoc reader, or `null`.
 * @param version the version to use or `null` to automatically determine which version to use.
 * @param builderAction the builder action.
 * @return a [ReaderEngagement].
 */
inline fun buildReaderEngagement(
    eReaderKey: EcPublicKey? = null,
    version: String? = null,
    builderAction: ReaderEngagement.Builder.() -> Unit = {}
): ReaderEngagement {
    val builder = ReaderEngagement.Builder(
        eReaderKey = eReaderKey,
        version = version
    )
    builder.builderAction()
    return builder.build()
}
