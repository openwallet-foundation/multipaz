package org.multipaz.nfc

import kotlinx.coroutines.CancellationException
import org.multipaz.util.ByteDataReader
import org.multipaz.util.appendByteString
import org.multipaz.util.appendUInt32
import org.multipaz.util.appendUInt8
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString

/**
 * An immutable NDEF Record.
 *
 * @property tnf the 3-bit TNF (Type Name Field) that provides high level typing for the rest of the record.
 * @property type detailed typing for the payload.
 * @property id identifier meta-data, not commonly used.
 * @property payload the actual payload.
 */
data class NdefRecord(
    val tnf: Tnf,
    val type: ByteString = ByteString(),
    val id: ByteString = ByteString(),
    val payload: ByteString = ByteString()
) {
    /**
     * Type-Name-Field values.
     */
    enum class Tnf(val value: Int) {
        /**
         * Indicates the record is empty.
         */
        EMPTY(0x00),

        /**
         * Indicates the type field contains a well-known RTD type name.
         */
        WELL_KNOWN(0x01),

        /**
         * Indicates the type field contains a media-type BNF construct, defined by RFC 2046.
         */
        MIME_MEDIA(0x02),

        /**
         * Indicates the type field contains an absolute-URI BNF construct defined by RFC 3986.
         */
        ABSOLUTE_URI(0x03),

        /**
         * Indicates the type field contains an external type name.
         */
        EXTERNAL_TYPE(0x04),

        /**
         * Indicates the type field is unknown.
         */
        UNKNOWN(0x05),

        /**
         * Indicates the payload is an intermediate or final chunk of a chunked NDEF Record.
         */
        UNCHANGED(0x06),

        /**
         * Reserved TNF type.
         */
        RESERVED(0x07)
    }

    /**
     * Encodes the record into a [ByteStringBuilder].
     *
     * @param bsb the [ByteStringBuilder] to append the encoding of the record to other records.
     * @param isMessageBegin set to `true` if this is the first record of a message.
     * @param isMessageEnd set to `true` if this is the last record of a message.
     */
    internal fun encode(
        bsb: ByteStringBuilder,
        isMessageBegin: Boolean,
        isMessageEnd: Boolean
    ) {
        val isShortRecord = payload.size < 256
        val idLengthPresent = if (tnf == Tnf.EMPTY) true else (id.size > 0)
        var flags = tnf.value
        if (isMessageBegin) flags = flags or FLAG_MB
        if (isMessageEnd) flags = flags or FLAG_ME
        if (isShortRecord) flags = flags or FLAG_SR
        if (idLengthPresent) flags = flags or FLAG_IL

        bsb.appendUInt8(flags)
        bsb.appendUInt8(type.size.toUByte())
        if (isShortRecord) {
            bsb.appendUInt8(payload.size)
        } else {
            bsb.appendUInt32(payload.size)
        }
        if (idLengthPresent) {
            bsb.appendUInt8(id.size)
        }
        bsb.appendByteString(type)
        bsb.appendByteString(id)
        bsb.appendByteString(payload)
    }

    /**
     * The URI of record, if one can be found.
     *
     * The following records are supported:
     * - Records with [Tnf.ABSOLUTE_URI]
     * - [Tnf.WELL_KNOWN] with type [Nfc.RTD_URI]
     * - [Tnf.WELL_KNOWN] with type [Nfc.RTD_SMART_POSTER] and containing a URI record in the NDEF message nested in the
     * payload.
     *
     * If this is not a URI record by the above rules, `null` is returned.
     */
    val uri: String?
        get() = getUri(false)

    private fun getUri(inSmartPoster: Boolean): String? {
        return when (tnf) {
            Tnf.WELL_KNOWN -> {
                when (type) {
                    Nfc.RTD_SMART_POSTER -> {
                        try {
                            val nestedMessage = NdefMessage.fromEncoded(payload.toByteArray())
                            for (record in nestedMessage.records) {
                                val uri = record.getUri(true)
                                if (uri != null) {
                                    return uri
                                }
                            }
                            return null
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            return null
                        }
                    }
                    Nfc.RTD_URI -> return URI_PREFIX_MAP[payload[0].toInt()] + payload.toByteArray(1).decodeToString()
                    else -> return null
                }
            }
            Tnf.ABSOLUTE_URI -> return type.decodeToString()
            else -> null
        }
    }

    /**
     * The MIME type of the record, if it has one.
     *
     * This returns [type] as a string if [tnf] is [Tnf.MIME_MEDIA].
     * The payload of the record is returned in [payload].
     */
    val mimeType: String?
        get() {
            if (tnf == Tnf.MIME_MEDIA) {
                return type.decodeToString()
            }
            return null
        }

    companion object {
        private const val TAG = "NdefRecord"

        // Defined in NFC Fourm NFC Data Exchange Format Technical Specification section 2.6
        //
        private const val FLAG_MB = 0x80  // Message Begin
        private const val FLAG_ME = 0x40  // Message End
        private const val FLAG_CF = 0x20  // Chunk Flag
        private const val FLAG_SR = 0x10  // Short Record
        private const val FLAG_IL = 0x08  // ID_LENGTH Field is present

        /**
         * Decodes a NDEF message into records.
         *
         * @param encoded the encoded NDEF message
         * @return a list of records.
         * @throws IllegalArgumentException if the message is invalid.
         */
        internal fun fromEncoded(encoded: ByteArray): List<NdefRecord> {
            try {
                val records = mutableListOf<NdefRecord>()
                var chunkBsb: ByteStringBuilder? = null
                var chunkTnf: Tnf = Tnf.EMPTY
                var type = ByteString()
                var id = ByteString()

                val reader = ByteDataReader(encoded)
                while (!reader.exhausted()) {
                    val flags = reader.getUInt8().toInt()
                    val isMessageBegin = flags.and(FLAG_MB) != 0x00
                    val isMessageEnd = flags.and(FLAG_ME) != 0x00
                    val isChunkedFlag = flags.and(FLAG_CF) != 0x00
                    val isShortRecord = flags.and(FLAG_SR) != 0x00
                    val idLengthPresent = flags.and(FLAG_IL) != 0x00
                    var tnf = Tnf.entries[flags.and(0x07)]

                    if (records.size == 0 && chunkBsb == null) {
                        require(isMessageBegin) { "First record must be message begin" }
                    }

                    val typeLength = reader.getUInt8().toInt()
                    val payloadLength = if (isShortRecord) {
                        reader.getUInt8().toInt()
                    } else {
                        reader.getUInt32().toInt()
                    }
                    val idLength: Int = if (idLengthPresent) {
                        reader.getUInt8().toInt()
                    } else {
                        0
                    }
                    if (chunkBsb == null) {
                        type = reader.getByteString(typeLength)
                        id = reader.getByteString(idLength)
                    }

                    var payload = reader.getByteString(payloadLength)

                    if (reader.exhausted()) {
                        require(isMessageEnd) { "Last record must be message end" }
                    }

                    if (tnf == Tnf.EMPTY && id.size != 0) {
                        throw IllegalArgumentException("TNF_EMPTY must have empty id")
                    }

                    if (chunkBsb != null && tnf != Tnf.UNCHANGED) {
                        throw IllegalArgumentException("In-chunk but TNF isn't UNCHANGED")
                    }
                    if (chunkBsb == null && tnf == Tnf.UNCHANGED) {
                        throw IllegalArgumentException("Not in-chunk with TNF UNCHANGED")
                    }

                    if (isChunkedFlag) {
                        if (chunkBsb == null) {
                            // First chunk
                            chunkBsb = ByteStringBuilder()
                            chunkTnf = tnf
                        }
                        chunkBsb.append(payload)
                        continue
                    } else {
                        if (chunkBsb != null) {
                            // Last chunk, flatten
                            chunkBsb.append(payload)
                            payload = chunkBsb.toByteString()
                            tnf = chunkTnf
                            chunkBsb = null
                        }
                    }

                    records.add(
                        NdefRecord(
                            tnf = tnf,
                            type = type,
                            id = id,
                            payload = payload
                        )
                    )
                }
                if (records.size == 0) {
                    throw IllegalArgumentException("No records in message")
                }
                return records
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw IllegalArgumentException("Parsing NdefMessage failed", e)
            }
        }

        /*
         * NFC Forum "URI Record Type Definition"
         *
         * This is a mapping of "URI Identifier Codes" to URI string prefixes,
         * per section 3.2.2 of the NFC Forum URI Record Type Definition document.
         */
        private val URI_PREFIX_MAP = listOf(
            "", // 0x00
            "http://www.", // 0x01
            "https://www.", // 0x02
            "http://", // 0x03
            "https://", // 0x04
            "tel:", // 0x05
            "mailto:", // 0x06
            "ftp://anonymous:anonymous@", // 0x07
            "ftp://ftp.", // 0x08
            "ftps://", // 0x09
            "sftp://", // 0x0A
            "smb://", // 0x0B
            "nfs://", // 0x0C
            "ftp://", // 0x0D
            "dav://", // 0x0E
            "news:", // 0x0F
            "telnet://", // 0x10
            "imap:", // 0x11
            "rtsp://", // 0x12
            "urn:", // 0x13
            "pop:", // 0x14
            "sip:", // 0x15
            "sips:", // 0x16
            "tftp:", // 0x17
            "btspp://", // 0x18
            "btl2cap://", // 0x19
            "btgoep://", // 0x1A
            "tcpobex://", // 0x1B
            "irdaobex://", // 0x1C
            "file://", // 0x1D
            "urn:epc:id:", // 0x1E
            "urn:epc:tag:", // 0x1F
            "urn:epc:pat:", // 0x20
            "urn:epc:raw:", // 0x21
            "urn:epc:", // 0x22
            "urn:nfc:", // 0x23
        )

        /**
         * Create a new NDEF Record containing a URI.
         *
         * Use this method to encode a URI (or URL) into an NDEF Record.
         * Uses the well known URI type representation [Tnf.WELL_KNOWN] and [Nfc.RTD_URI].
         * This is the most efficient encoding of a URI into NDEF.
         *
         * @param uri the URI to create a record for, for example `www.google.com`.
         * @return a new record with the URI.
         */
        fun createUri(uri: String): NdefRecord {
            var uriPrefixNum = 0
            var uriStr = uri
            for (n in 1 until URI_PREFIX_MAP.size - 1) {
                if (uri.startsWith(URI_PREFIX_MAP[n])) {
                    uriPrefixNum = n
                    uriStr = uri.substring(URI_PREFIX_MAP[n].length)
                    break
                }
            }
            return NdefRecord(
                tnf = Tnf.WELL_KNOWN,
                type = Nfc.RTD_URI,
                id = ByteString(),
                payload = ByteString(byteArrayOf(uriPrefixNum.toByte()) + uriStr.encodeToByteArray())
            )
        }

        /**
         * Create a new NDEF Record containing MIME data.
         *
         * Use this method to encode MIME-typed data into an NDEF Record, such as "text/plain", or "image/jpeg".
         *
         * @param mimeType a MIME type.
         * @param mimeData the data.
         * @return a NDEF record containing the type and data.
         */
        fun createMime(mimeType: String, mimeData: ByteArray): NdefRecord {
            require(mimeType.length > 0) { "MIME type cannot be empty" }
            return NdefRecord(Tnf.MIME_MEDIA, type = mimeType.encodeToByteString(), payload = ByteString(mimeData))
        }
    }
}

