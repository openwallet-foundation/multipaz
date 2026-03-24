package org.multipaz.mpzpass

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.util.Logger
import org.multipaz.util.deflate
import org.multipaz.util.inflate

/**
 * Represents a Multipaz `.mpzpass` credential container.
 *
 * This format provides a highly portable, lightweight mechanism to exchange low-assurance
 * verifiable credentials (such as transit passes or movie tickets) where strict
 * hardware device-binding is unnecessary.
 *
 * See [this page](https://github.com/openwallet-foundation/multipaz/tree/main/mpzpass/README.md)
 * for the definition of the Multipaz Pass file format.
 *
 * @property name The display name of the credential (e.g., "Erika's Driving License").
 * @property typeName The display type of the credential (e.g., "Utopia Driving License").
 * @property cardArt The card art for the pass as a PNG ByteString.
 * @property isoMdoc The ISO mDoc credentials in the payload.
 * @property sdJwtVc The SD-JWT VC credentials in the payload.
 * @throws IllegalArgumentException if both [isoMdoc] and [sdJwtVc] are empty.
 */
data class MpzPass(
    val name: String?,
    val typeName: String?,
    val cardArt: ByteString?,
    val isoMdoc: List<MpzPassIsoMdoc> = emptyList(),
    val sdJwtVc: List<MpzPassSdJwtVc> = emptyList()
) {

    init {
        if (isoMdoc.isEmpty() && sdJwtVc.isEmpty()) {
            throw IllegalArgumentException("Both isoMdoc and sdJwtVc cannot be empty")
        }
    }

    /**
     * Serializes and compresses this [MpzPass] into a [DataItem].
     *
     * The credential data payload is encoded to CBOR and then compressed using the DEFLATE algorithm.
     *
     * @param compressionLevel The DEFLATE compression level to use (0-9). Defaults to 5.
     * @return A [DataItem].
     * @throws IllegalArgumentException if the compression level is out of range.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun toDataItem(compressionLevel: Int = 5) = buildCborArray {
        add("MpzPass")
        val credentialData = buildCborMap {
            putCborMap("credential") {
                if (isoMdoc.isNotEmpty()) {
                    putCborArray("isoMdoc") {
                        isoMdoc.forEach { add(it.toDataItem()) }
                    }
                }
                if (sdJwtVc.isNotEmpty()) {
                    putCborArray("sdJwtVc") {
                        sdJwtVc.forEach { add(it.toDataItem()) }
                    }
                }
            }
            putCborMap("display") {
                name?.let { put("name", it) }
                typeName?.let { put("typeName", it) }
                cardArt?.let { put("cardArt", it.toByteArray()) }
            }
        }
        val credentialDataBytes = Cbor.encode(credentialData)
        val compressedCredentialDataBytes = credentialDataBytes.deflate(compressionLevel)
        add(compressedCredentialDataBytes)
    }

    companion object {
        private const val TAG = "MpzPass"

        /**
         * Parses a CBOR array [DataItem] into an [MpzPass].
         *
         * This decompresses the embedded DEFLATE payload and reconstructs the pass details.
         *
         * @param dataItem The top-level CBOR array containing the MpzPass string tag and compressed bytes.
         * @return The parsed [MpzPass].
         * @throws IllegalArgumentException if CBOR decoding or decompression fails.
         */
        @Throws(IllegalArgumentException::class, CancellationException::class)
        suspend fun fromDataItem(dataItem: DataItem): MpzPass {
            check(dataItem is CborArray) { "Expected an array" }
            require(dataItem[0].asTstr == "MpzPass") { "Wrong string at start" }

            val compressedCredentialDataBytes = dataItem[1].asBstr
            val credentialDataBytes = compressedCredentialDataBytes.inflate()
            val credentialData = Cbor.decode(credentialDataBytes)

            val display = credentialData["display"]
            val name = display.getOrNull("name")?.asTstr
            val typeName = display.getOrNull("typeName")?.asTstr
            val cardArt = display.getOrNull("cardArt")?.asBstr?.let { ByteString(it) }

            val credential = credentialData["credential"]
            val isoMdoc = credential.getOrNull("isoMdoc")?.asArray?.map {
                MpzPassIsoMdoc.fromDataItem(it)
            } ?: emptyList()
            val sdJwtVc = credential.getOrNull("sdJwtVc")?.asArray?.map {
                MpzPassSdJwtVc.fromDataItem(it)
            } ?: emptyList()

            return MpzPass(
                name = name,
                typeName = typeName,
                cardArt = cardArt,
                isoMdoc = isoMdoc,
                sdJwtVc = sdJwtVc
            )
        }
    }
}
