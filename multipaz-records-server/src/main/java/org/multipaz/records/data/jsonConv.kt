package org.multipaz.records.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Nint
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64
import org.multipaz.util.toBase64
import kotlin.time.Instant

private val UTOPIA_TIMEZONE = TimeZone.UTC

@OptIn(FormatStringsInDatetimeFormats::class)
private val LOCAL_FORMAT = LocalDateTime.Format {
    year()
    chars("-")
    monthNumber()
    chars("-")
    day()
    chars("T")
    hour()
    chars(":")
    minute()
}

/**
 * Converts [JsonElement] to [DataItem] based on the given record type.
 *
 * Note: pictures and blobs are base64 encoded! Base64 is chosen for ease of conversion to "data:"
 * URLs.
 */
fun JsonElement.toDataItem(recordType: RecordType): DataItem {
    return when (this) {
        is JsonPrimitive ->
            if (isString) {
                when (val type = recordType.attribute.type) {
                    DocumentAttributeType.Date ->
                        LocalDate.parse(content).toDataItemFullDate()
                    DocumentAttributeType.DateTime ->
                        LocalDateTime.parse(content, LOCAL_FORMAT)
                            .toInstant(UTOPIA_TIMEZONE)
                            .toDataItemDateTimeString()
                    DocumentAttributeType.Blob, DocumentAttributeType.Picture ->
                        Bstr(content.fromBase64())
                    DocumentAttributeType.String, is DocumentAttributeType.StringOptions ->
                        Tstr(content)
                    else -> throw IllegalArgumentException("Expected $type, got a string")
                }
            } else when (content) {
                "null" -> Simple.NULL
                "true" -> Simple.TRUE
                "false" -> Simple.FALSE
                else -> content.toLong().toDataItem()  // TODO: do we need real numbers?
            }
        is JsonArray -> buildCborArray {
            val itemType = recordType.subAttributes["*"]
                ?: throw IllegalArgumentException("Unexpected array")
            for (item in jsonArray) {
                add(item.toDataItem(itemType))
            }
        }
        is JsonObject -> buildCborMap {
            for ((itemKey, item) in jsonObject) {
                val itemType = recordType.subAttributes[itemKey]
                if (itemType != null) {
                    put(itemKey, item.toDataItem(itemType))
                } else {
                    if (itemKey == "instance_title") {
                        put("instance_title", item.jsonPrimitive.content)
                    } else {
                        throw IllegalArgumentException("Unexpected item: '$itemKey'")
                    }
                }
            }
        }
    }
}

/**
 * Converts [DataItem] to [JsonElement].
 *
 * NB: pictures are blobs use base64, not base64url
 */
fun DataItem.toJsonRecord(): JsonElement {
    return when (this) {
        Simple.NULL -> JsonNull
        Simple.TRUE -> JsonPrimitive(true)
        Simple.FALSE -> JsonPrimitive(false)
        is Tstr -> JsonPrimitive(asTstr)
        is Bstr -> JsonPrimitive(asBstr.toBase64())
        is Uint, is Nint -> JsonPrimitive(asNumber)
        is Tagged -> when (tagNumber) {
            Tagged.FULL_DATE_STRING ->
                JsonPrimitive(asDateString.toString())
            Tagged.DATE_TIME_STRING ->
                JsonPrimitive(asDateTimeString
                    .toLocalDateTime(UTOPIA_TIMEZONE)
                    .format(LOCAL_FORMAT))
            else -> throw IllegalArgumentException("Unsupported cbor tagged type")
        }
        is CborArray -> buildJsonArray {
                for (item in items) {
                    add(item.toJsonRecord())
                }
            }
        is CborMap -> buildJsonObject {
            for ((key, item) in asMap) {
                put(key.asTstr, item.toJsonRecord())
            }
        }
        else -> throw IllegalArgumentException("Unsupported cbor type")
    }
}