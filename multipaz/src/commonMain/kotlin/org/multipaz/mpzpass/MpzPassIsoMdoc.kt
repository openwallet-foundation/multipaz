package org.multipaz.mpzpass

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.issuersigned.IssuerNamespaces

/**
 * Represents the ISO mDoc specific data within an MpzPass container.
 *
 * @property docType The ISO document type string (e.g., "org.iso.18013.5.1.mDL").
 * @property deviceKeyPrivate The private key corresponding to the DeviceKey mapped in `issuerSigned`.
 * @property issuerNamespaces The namespaces containing issuer-signed data items.
 * @property issuerAuth The `CoseSign1` structure authenticating the issuer namespaces.
 */
data class MpzPassIsoMdoc(
    val docType: String,
    val deviceKeyPrivate: EcPrivateKey,
    val issuerNamespaces: IssuerNamespaces,
    val issuerAuth: CoseSign1
) {
    /**
     * Serializes this [MpzPassIsoMdoc] instance into a CBOR map [DataItem].
     *
     * @return A CBOR map containing the ISO mDoc data.
     */
    fun toDataItem() = buildCborMap {
        put("docType", docType)
        put("deviceKeyPrivate", deviceKeyPrivate.toDataItem())
        putCborMap("issuerSigned") {
            put("nameSpaces", issuerNamespaces.toDataItem())
            put("issuerAuth", issuerAuth.toDataItem())
        }
    }

    companion object {
        /**
         * Parses a CBOR map [DataItem] into an [MpzPassIsoMdoc] instance.
         *
         * @param dataItem The CBOR map containing the ISO mDoc representation.
         * @return The parsed [MpzPassIsoMdoc].
         * @throws IllegalArgumentException if the expected fields are missing or incorrectly typed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): MpzPassIsoMdoc {
            val docType = dataItem["docType"].asTstr
            val deviceKeyPrivate = dataItem["deviceKeyPrivate"].asCoseKey.ecPrivateKey
            val issuerSigned = dataItem["issuerSigned"]
            val issuerNamespaces = IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])
            val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1
            return MpzPassIsoMdoc(
                docType = docType,
                deviceKeyPrivate = deviceKeyPrivate,
                issuerNamespaces = issuerNamespaces,
                issuerAuth = issuerAuth
            )
        }
    }
}