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
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod

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
     * Generates CBOR compliant with the CDDL for `ReaderEngagement` according to ISO/IEC 18013-5.
     *
     * @return a [DataItem].
     */
    fun toDataItem(): DataItem = buildCborMap {
        put(0, version)
        if (eReaderKey != null) {
            putCborArray(1) {
                add(1) // cipher suite
                addTaggedEncodedCbor(Cbor.encode(this@ReaderEngagement.eReaderKey.toCoseKey().toDataItem()))
            }
        }
        if (connectionMethods.isNotEmpty()) {
            putCborArray(2) {
                connectionMethods.forEach { connectionMethod ->
                    add(RawCbor(connectionMethod.toDeviceEngagement()))
                }
            }
        }
    }

    companion object {
        /**
         * Parses CBOR compliant with the CDDL for `ReaderEngagement` according to ISO/IEC 18013-5.
         *
         * @param dataItem the `ReaderEngagement` CBOR.
         * @return the parsed [ReaderEngagement].
         */
        fun fromDataItem(dataItem: DataItem): ReaderEngagement {
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
            val connectionMethods = mutableListOf<MdocConnectionMethod>()
            if (connectionMethodsArray != null) {
                for (cmDataItem in (connectionMethodsArray as CborArray).items) {
                    val connectionMethod = MdocConnectionMethod.fromDeviceEngagement(
                        Cbor.encode(cmDataItem)
                    )
                    if (connectionMethod != null) {
                        connectionMethods.add(connectionMethod)
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
                connectionMethods = connectionMethods
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
