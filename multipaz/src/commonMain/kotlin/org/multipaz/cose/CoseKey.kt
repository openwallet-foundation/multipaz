package org.multipaz.cose

import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Nint
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.RsaPrivateKey
import org.multipaz.crypto.RsaPublicKey

/**
 * COSE Key.
 *
 * @param labels the labels in the COSE Key.
 */
@CborSerializationImplemented(schemaId = "Hh5WQQNrvHYOgN9pGAVhxxgvVMe_Z-qCqovxRYXltQM")
class CoseKey(val labels: Map<CoseLabel, DataItem>) {

    init {
        require(labels[Cose.COSE_KEY_KTY.toCoseLabel] != null)
    }

    /**
     * Gets the value of the [Cose.COSE_KEY_KTY] label.
     */
    val keyType: DataItem
        get() = labels[Cose.COSE_KEY_KTY.toCoseLabel]!!

    /**
     * Encodes the COSE Key as a CBOR data item.
     */
    fun toDataItem(): DataItem {
        return buildCborMap {
            for ((key, value) in labels) {
                put(key.toDataItem(), value)
            }
        }
    }

    /**
     * Gets the public key in the COSE Key as a [PublicKey].
     */
    val publicKey: PublicKey
        get() = PublicKey.fromCoseKey(this)

    /**
     * Gets the private key in the COSE Key as a [PrivateKey].
     */
    val privateKey: PrivateKey
        get() = PrivateKey.fromCoseKey(this)

    /**
     * Gets the public key in the COSE Key as a [EcPublicKey].
     */
    val ecPublicKey: EcPublicKey
        get() = EcPublicKey.fromCoseKey(this)

    /**
     * Gets the private key in the COSE Key as a [EcPrivateKey].
     */
    val ecPrivateKey: EcPrivateKey
        get() = EcPrivateKey.fromCoseKey(this)

    /**
     * Gets the public key in the COSE Key as a [RsaPublicKey].
     */
    val rsaPublicKey: RsaPublicKey
        get() = RsaPublicKey.fromCoseKey(this)

    /**
     * Gets the private key in the COSE Key as a [RsaPrivateKey].
     */
    val rsaPrivateKey: RsaPrivateKey
        get() = RsaPrivateKey.fromCoseKey(this)

    companion object {
        /**
         * Gets a [CoseKey] from a CBOR data item.
         *
         * @param dataItem the CBOR data item.
         * @return the [CoseKey].
         */
        fun fromDataItem(dataItem: DataItem): CoseKey {
            require(dataItem is CborMap)
            val labels = mutableMapOf<CoseLabel, DataItem>()
            for ((item, value) in dataItem.items) {
                val label = when (item) {
                    is Nint -> CoseNumberLabel(item.asNumber)
                    is Uint -> CoseNumberLabel(item.asNumber)
                    is Tstr -> CoseTextLabel(item.value)
                    else -> throw IllegalStateException("Unexpected item $item in array")
                }
                labels[label] = value
            }
            return CoseKey(labels)
        }
    }
}