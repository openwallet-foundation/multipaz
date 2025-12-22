package org.multipaz.mdoc.issuersigned

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.buildCborMap

/**
 * A data structure representing `IssuerSignedItem` in ISO/IEC 18013-5:2021.
 */
data class IssuerSignedItem(
    val digestId: Long,
    val random: ByteString,
    val dataElementIdentifier: String,
    val dataElementValue: DataItem
) {

    /**
     * Calculates the digest of `IssuerSignedItemBytes`.
     *
     * @param algorithm the digest algorithm to use, e.g. [Algorithm.SHA256].
     */
    suspend fun calculateDigest(algorithm: Algorithm): ByteString {
        val encodeIssuerSignedItemBytes =
            Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(toDataItem()))))
        return ByteString(Crypto.digest(algorithm, encodeIssuerSignedItemBytes))
    }

    /**
     * Generate `IssuerSignedItem` CBOR.
     *
     * @return a [DataItem] for `IssuerSignedItem` CBOR.
     */
    fun toDataItem(): DataItem {
        return buildCborMap {
            put("digestID", digestId)
            put("random", random.toByteArray())
            put("elementIdentifier", dataElementIdentifier)
            put("elementValue", dataElementValue)
        }
    }
    
    companion object {
        /**
         * Parse `IssuerSignedItem` CBOR.
         *
         * @param issuerSignedItem a [DataItem] for `IssuerSignedItem` CBOR.
         * @return the parsed representation.
         */
        fun fromDataItem(issuerSignedItem: DataItem): IssuerSignedItem {
            return IssuerSignedItem(
                digestId = issuerSignedItem["digestID"].asNumber,
                random = ByteString(issuerSignedItem["random"].asBstr),
                dataElementIdentifier = issuerSignedItem["elementIdentifier"].asTstr,
                dataElementValue = issuerSignedItem["elementValue"]
            )
        }
    }
}
