package org.multipaz.mpzpass

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.EcPrivateKey

/**
 * Represents the SD-JWT VC specific data within an MpzPass container.
 *
 * @property vct The verifiable credential type (VCT) string.
 * @property deviceKeyPrivate The private key used for key-binding, or null if the credential is not key-bound.
 * @property compactSerialization The compact serialization string of the SD-JWT VC according to RFC 9901.
 */
data class MpzPassSdJwtVc(
    val vct: String,
    val deviceKeyPrivate: EcPrivateKey?,
    val compactSerialization: String
) {
    /**
     * Serializes this [MpzPassSdJwtVc] instance into a CBOR map [DataItem].
     *
     * @return A CBOR map containing the SD-JWT VC data.
     */
    fun toDataItem() = buildCborMap {
        put("vct", vct)
        deviceKeyPrivate?.let { put("deviceKeyPrivate", it.toDataItem()) }
        put("compactSerialization", compactSerialization)
    }

    companion object {
        /**
         * Parses a CBOR map [DataItem] into an [MpzPassSdJwtVc] instance.
         *
         * @param dataItem The CBOR map containing the SD-JWT VC representation.
         * @return The parsed [MpzPassSdJwtVc].
         * @throws IllegalArgumentException if the expected fields are missing or incorrectly typed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): MpzPassSdJwtVc {
            val vct = dataItem["vct"].asTstr
            val deviceKeyPrivate = dataItem.getOrNull("deviceKeyPrivate")?.asCoseKey?.ecPrivateKey
            val compactSerialization = dataItem["compactSerialization"].asTstr
            return MpzPassSdJwtVc(
                vct = vct,
                deviceKeyPrivate = deviceKeyPrivate,
                compactSerialization = compactSerialization
            )
        }
    }
}