package org.multipaz.revocation

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.EcPublicKey
import org.multipaz.webtoken.WebTokenCheck
import org.multipaz.webtoken.validateJwt
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.zlibDeflate
import org.multipaz.webtoken.validateCwt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Status list as defined in
 * [OAuth Status List](https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/)
 * in the uncompressed form.
 *
 * Conceptually status list is just a compact array of status codes, where each status code can
 * take at most [bitsPerItem] bits for its representation. Status lists use compression when
 * serialized and thus are relatively compact when most of the status codes are zero.
 *
 * Use this (uncompressed) form of the list to query credential's status.
 *
 * @param bitsPerItem number of bits per status code, must be 1, 2, 4, or 8
 * @param statusList uncompressed status values packed as an array as defined by the spec above
 */
class StatusList(
    val bitsPerItem: Int,
    private val statusList: ByteArray
) {
    private val itemsPerByte = 8 / bitsPerItem
    private val mask = (-1 shl bitsPerItem).inv()

    init {
        require(bitsPerItem == 1 || bitsPerItem == 2 || bitsPerItem == 4 || bitsPerItem == 8)
    }

    /**
     * Gets the status of the credential with the given index
     *
     * @param index index of the credential (from `idx` field)
     * @return status code of the credential, 0 typically means valid
     */
    operator fun get(index: Int): Int {
        val byteIndex = index / itemsPerByte
        return if (byteIndex >= statusList.size) {
            0
        } else {
            val shift = bitsPerItem * (index % itemsPerByte)
            (statusList[byteIndex].toInt() shr shift) and mask
        }
    }

    /**
     * Creates compressed form of the status list.
     */
    suspend fun compress(): CompressedStatusList =
        CompressedStatusList(bitsPerItem, statusList.zlibDeflate(9))

    /**
     * Builder class for [StatusList].
     *
     * This helps creating [StatusList] from a set of per-index status codes. Only non-zero
     * status values need to be supplied.
     *
     * @param bitsPerItem number of bits per status code, must be 1, 2, 4, or 8.
     */
    class Builder(val bitsPerItem: Int) {
        // TODO: if/once we have a Sink which can deflate as we write, we could switch to that
        private var buffer: Buffer = Buffer()
        private var lastIndex: Int = -1
        private var currentByte: Int = 0

        // Shift needed to transform bit index to byte index
        private val perByteShift: Int = when (bitsPerItem) {
            1 -> 3
            2 -> 2
            4 -> 1
            8 -> 0
            else -> throw IllegalArgumentException("bitsPerItem = $bitsPerItem")
        }

        // Mask to extract bit in byte index from bit index
        private val bitIndexMask: Int = (-1 shl perByteShift).inv()
        private val maxStatus: Int = (-1 shl bitsPerItem).inv()

        init {
            require(bitsPerItem == 1 || bitsPerItem == 2 || bitsPerItem == 4 || bitsPerItem == 8)
        }

        /**
         * Adds a new status code.
         *
         * @param index index of the credential to which the status code belongs; indices must
         *     be given without duplication and in increasing order
         * @param status status code for the credential with the given index, calls for status
         *     codes equal to zero may be omitted
         */
        fun addStatus(index: Int, status: Int) {
            require(status >= 0 && status <= maxStatus) { "Invalid status: $status" }
            if (index <= lastIndex) {
                throw IllegalArgumentException("non-increasing index: $index")
            }
            val bytesWritten = if (lastIndex < 0) { 0 } else { lastIndex shr perByteShift }
            val requiredByteIndex = index shr perByteShift
            repeat(requiredByteIndex - bytesWritten) {
                buffer.writeByte(currentByte.toByte())
                currentByte = 0
            }
            val shift = bitsPerItem * (index and bitIndexMask)
            currentByte = currentByte or (status shl shift)
            lastIndex = index
        }

        /**
         * Builds a new [StatusList] object for the data provided.
         */
        fun build(): StatusList {
            buffer.writeByte(currentByte.toByte())
            return StatusList(
                bitsPerItem = bitsPerItem,
                statusList = buffer.readByteArray()
            )
        }
    }

    companion object {
        /**
         * Parses and validates JWT that holds the status list.
         *
         * JWT signature can be validated either by passing [WebTokenCheck.TRUST] key in the [checks]
         * map or using non-null [publicKey] (see [validateJwt]).
         *
         * @param jwt status list JWT representation
         * @param publicKey public key of the issuance server signing key (optional)
         * @param checks additional checks for JWT validation
         * @return parsed [StatusList]
         * @throws IllegalArgumentException when [jwt] cannot be parsed as JWT status list
         * @throws InvalidRequestException when JWT validation fails
         */
        suspend fun fromJwt(
            jwt: String,
            publicKey: EcPublicKey? = null,
            checks: Map<WebTokenCheck, String> = mapOf()
        ): StatusList =
            CompressedStatusList.fromJwt(jwt, publicKey, checks).decompress()

        /**
         * Parses JSON as status list.
         *
         * This method is mostly useful for testing, as JSON is typically wrapped in JWT.
         *
         * @param json JSON status list representation
         * @return parsed [StatusList]
         * @throws IllegalArgumentException when [json] does not represent status list
         */
        suspend fun fromJson(json: JsonObject): StatusList =
            CompressedStatusList.fromJson(json).decompress()

        /**
         * Parses and validates CWT that holds the status list.
         *
         * CWT signature can be validated either by passing [WebTokenCheck.TRUST] key in
         * the [checks] map or using non-null [publicKey] (see [validateCwt]).
         *
         * @param cwt status list CWT representation
         * @param publicKey public key of the issuance server signing key (optional)
         * @param checks additional checks for JWT validation
         * @param maxValidity maximum CWT validity duration to accept
         * @return parsed [StatusList]
         * @throws IllegalArgumentException when [cwt] cannot be parsed as CWT status list
         * @throws InvalidRequestException when CWT validation fails
         */
        suspend fun fromCwt(
            cwt: ByteArray,
            publicKey: EcPublicKey? = null,
            checks: Map<WebTokenCheck, String> = mapOf(),
            maxValidity: Duration = 365.days
        ): StatusList =
            CompressedStatusList.fromCwt(cwt, publicKey, checks, maxValidity).decompress()

        /**
         * Parses CBOR as status list.
         *
         * This method is mostly useful for testing, as CBOR is typically wrapped in JWT.
         *
         * @param dataItem CBOR status list representation
         * @return parsed [CompressedStatusList]
         * @throws IllegalArgumentException when [dataItem] does not represent status list
         */
        suspend fun fromDataItem(dataItem: DataItem): StatusList =
            CompressedStatusList.fromDataItem(dataItem).decompress()
    }
}
