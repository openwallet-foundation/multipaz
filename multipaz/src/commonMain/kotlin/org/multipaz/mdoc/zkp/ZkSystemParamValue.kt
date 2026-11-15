package org.multipaz.mdoc.zkp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborDouble
import org.multipaz.cbor.CborInt
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem

/**
 * An interface for ZK system parameter values.
 */
sealed interface ZkSystemParamValue {
    /**
     * A string value.
     *
     * @property value the value.
     */
    data class StringValue(val value: String): ZkSystemParamValue

    /**
     * A number value.
     *
     * @property value the value.
     */
    data class LongValue(val value: Long): ZkSystemParamValue

    /**
     * A floating point value.
     *
     * @property value the value.
     */
    data class DoubleValue(val value: Double): ZkSystemParamValue

    /**
     * A boolean value.
     *
     * @property value the value.
     */
    data class BooleanValue(val value: Boolean): ZkSystemParamValue

    /**
     * A list value (`[+ ParamValue]`).
     *
     * @property value the value.
     */
    data class ListValue(val value: List<ZkSystemParamValue>): ZkSystemParamValue

    /**
     * A map value (`{+ tstr => ParamValue}`).
     *
     * @property value the value.
     */
    data class MapValue(val value: Map<String, ZkSystemParamValue>): ZkSystemParamValue

    /**
     * Encodes the value as Cbor.
     *
     * @return a [DataItem].
     */
    fun toDataItem(): DataItem = when (this) {
        is StringValue -> this.value.toDataItem()
        is DoubleValue -> this.value.toDataItem()
        is BooleanValue -> this.value.toDataItem()
        is LongValue -> this.value.toDataItem()
        is ListValue -> buildCborArray {
            value.forEach { add(it.toDataItem()) }
        }
        is MapValue -> buildCborMap {
            value.forEach { (k, v) -> put(k, v.toDataItem()) }
        }
    }

    /**
     * Encodes the value as Json.
     *
     * @return a [JsonElement].
     */
    fun toJson(): JsonElement = when (this) {
        is BooleanValue -> JsonPrimitive(value)
        is DoubleValue -> JsonPrimitive(value)
        is LongValue -> JsonPrimitive(value)
        is StringValue -> JsonPrimitive(value)
        is ListValue -> JsonArray(value.map { it.toJson() })
        is MapValue -> JsonObject(value.mapValues { (_, v) -> v.toJson() })
    }

    companion object {
        /**
         * Decodes a Cbor value.
         *
         * @param value the [DataItem] to decode.
         * @return a [ZkSystemParamValue].
         */
        fun fromDataItem(value: DataItem): ZkSystemParamValue {
            return when (value) {
                is Tstr -> StringValue(value.value)
                is CborInt -> LongValue(value.asNumber)
                is CborDouble -> DoubleValue(value.value)
                is Simple -> {
                    when (value.value) {
                        Simple.FALSE.value -> BooleanValue(false)
                        Simple.TRUE.value -> BooleanValue(true)
                        else -> throw IllegalArgumentException("Unexpected CBOR")
                    }
                }
                is CborArray -> {
                    require(value.items.isNotEmpty()) { "ParamValue list must not be empty" }
                    ListValue(value.items.map { fromDataItem(it) })
                }
                is CborMap -> {
                    require(value.items.isNotEmpty()) { "ParamValue map must not be empty" }
                    MapValue(value.items.entries.associate { (k, v) -> k.asTstr to fromDataItem(v) })
                }
                else -> throw IllegalArgumentException("Unexpected Cbor data item $value")
            }
        }

        /**
         * Decodes a Json value.
         *
         * @param value the [JsonElement] to decode.
         * @return a [ZkSystemParamValue].
         */
        fun fromJson(value: JsonElement): ZkSystemParamValue {
            return when (value) {
                is JsonPrimitive -> {
                    if (value.isString) {
                        StringValue(value.content)
                    } else if (value.booleanOrNull != null) {
                        BooleanValue(value.booleanOrNull!!)
                    } else if (value.longOrNull != null) {
                        LongValue(value.longOrNull!!)
                    } else if (value.doubleOrNull != null) {
                        DoubleValue(value.doubleOrNull!!)
                    } else {
                        throw IllegalArgumentException("Unexpected JsonElement $value")
                    }
                }
                is JsonArray -> {
                    require(value.isNotEmpty()) { "ParamValue list must not be empty" }
                    ListValue(value.map { fromJson(it) })
                }
                is JsonObject -> {
                    require(value.isNotEmpty()) { "ParamValue map must not be empty" }
                    MapValue(value.mapValues { (_, v) -> fromJson(v) })
                }
            }
        }
    }
}
