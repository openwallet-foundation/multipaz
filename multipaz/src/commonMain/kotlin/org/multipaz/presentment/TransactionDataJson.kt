package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.TransactionType
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.fromBase64Url
import org.multipaz.mdoc.request.DocRequestInfo

/**
 * [TransactionData] in JSON format as used in OpenID4VP.
 *
 * @param type transaction type
 * @param base64UrlEncodedJson transaction data as JSON which is then Base64Url-encoded; this is
 *  what is used to compute transaction data hash in OpenID4VP
 * @param data JSON transaction data; must be the same data as in [base64UrlEncodedJson]
 */
class TransactionDataJson(
    type: TransactionType,
    val base64UrlEncodedJson: String,
    data: JsonObject = Json.parseToJsonElement(base64UrlEncodedJson.fromBase64Url().decodeToString()).jsonObject
): TransactionData(type) {
    override val attributes = AttributesJson(data)

    override fun getHashAlgorithm(): Algorithm? =
        attributes.data["transaction_data_hashes_alg"]?.let { algs ->
            algs.jsonArray.firstNotNullOfOrNull { alg ->
                (alg as? JsonPrimitive)?.let {
                    try {
                        Algorithm.fromHashAlgorithmIdentifier(it.content)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            } ?: throw IllegalArgumentException(
                "No supported algorithms in transaction_data_hashes_alg")
        }

    override suspend fun getHash(algorithm: Algorithm) =
        ByteString(Crypto.digest(algorithm, base64UrlEncodedJson.encodeToByteArray()))

    /**
     * Implementation of [TransactionData.Attributes] for JSON-formatted transactions.
     *
     * @param data transaction data as JSON object.
     */
    class AttributesJson(val data: JsonObject): Attributes {
        override fun getString(name: String): String? =
            data[name]?.jsonPrimitive?.content

        override fun getLong(name: String): Long? =
            data[name]?.jsonPrimitive?.long

        override fun getDouble(name: String): Double? =
            data[name]?.jsonPrimitive?.double

        override fun getBoolean(name: String): Boolean? =
            data[name]?.jsonPrimitive?.boolean

        override fun getBlob(name: String): ByteString? =
            data[name]?.let { ByteString(it.jsonPrimitive.content.fromBase64Url()) }

        override fun getCompound(name: String): Attributes? =
            data[name]?.let { AttributesJson(it.jsonObject) }
    }

    /**
     * Converts this transaction data to CBOR representation.
     *
     * Transaction data in CBOR is passed as [Tagged] that holds serialized CBOR map. Each
     * attribute in the map is encoded according to the data schema defined by [TransactionType].
     *
     * @return CBOR-encoded transaction data
     */
    fun convertDataToCbor(): Tagged =
        Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Cbor.encode(buildCborMap {
                attributes.data["transaction_data_hashes_alg"]?.jsonArray?.mapNotNull {
                    Algorithm.fromHashAlgorithmIdentifier(it.jsonPrimitive.content)
                        .coseAlgorithmIdentifier?.toDataItem()
                }?.let { hashAlgorithms ->
                    if (hashAlgorithms.isEmpty()) {
                        throw IllegalArgumentException("no supported hash algorithms")
                    }
                    put("transaction_data_hashes_alg", CborArray(hashAlgorithms.toMutableList()))
                }
                for (element in type.dataElements.values) {
                    val value = attributes.data[element.attribute.identifier]
                    if (value == null) {
                        if (element.mandatory) {
                            throw InvalidRequestException(
                                "missing mandatory '${element.attribute.identifier}' in transaction '${type.identifier}'"
                            )
                        }
                        continue
                    }
                    put(element.attribute.identifier, convertToCbor(
                        transactionTypeIdentifier = type.identifier,
                        attribute = element.attribute,
                        value = value
                    ))
                }
            }).toDataItem()
        )


    companion object {
        /**
         * Parses OpenID4VP JSON-encoded transaction data.
         *
         * @param transactionData encoded transaction data (array of base64url-encoded JSON items)
         * @param documentTypeRepository [DocumentTypeRepository] to look up transaction types
         * @return map of credential id to the list of applicable transaction data items
         */
        fun parse(
            transactionData: JsonElement,
            documentTypeRepository: DocumentTypeRepository
        ): Map<String, List<TransactionDataJson>> {
            transactionData as? JsonArray
                ?: throw IllegalArgumentException("Invalid transaction_data")
            return parse(
                base64UrlEncodedJson = transactionData.map { it.jsonPrimitive.content },
                documentTypeRepository = documentTypeRepository
            )
        }

        /**
         * Parses OpenID4VP JSON-encoded transaction data.
         *
         * @param base64UrlEncodedJson encoded transaction data (array of base64url-encoded items)
         * @param documentTypeRepository [DocumentTypeRepository] to look up transaction types
         * @return map of credential id to the list of applicable transaction data items
         */
        fun parse(
            base64UrlEncodedJson: List<String>,
            documentTypeRepository: DocumentTypeRepository
        ): Map<String, List<TransactionDataJson>> {
            val map = mutableMapOf<String, MutableList<TransactionDataJson>>()
            for (base64UrlText in base64UrlEncodedJson) {
                val data = Json.parseToJsonElement(
                    base64UrlText.fromBase64Url().decodeToString()
                ).jsonObject
                val credentialIds = (data["credential_ids"] as? JsonArray)
                    ?: throw IllegalArgumentException("Missing 'credential_ids' in transaction data")
                val typeId = (data["type"] as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("Missing or invalid 'type' in transaction data")
                val type = documentTypeRepository.getTransactionTypeByIdentifier(typeId)
                    ?: throw IllegalArgumentException("Unknown transaction type '$typeId'")
                val parsed = TransactionDataJson(type, base64UrlText, data)
                for (id in credentialIds) {
                    map.getOrPut(id.jsonPrimitive.content) { mutableListOf() }.add(parsed)
                }
            }
            return map.mapValues { (_, list) -> list.toList() }
        }

        /**
         * Converts the list of transaction data in JSON format that all reference a particular
         * credential to the map that is appropriate to pass as [DocRequestInfo.otherInfo] property
         * when building the request.
         *
         * @receiver list of parsed transaction data in JSON format
         * @return map that represents transaction data in ISO 18013 context
         */
        fun List<TransactionDataJson>.convertToDocRequestOtherInfo(): Map<String, DataItem> =
            associate {
                it.type.mdocRequestInfoKeyName to it.convertDataToCbor()
            }

        private fun convertToCbor(
            transactionTypeIdentifier: String,
            attribute: DocumentAttribute,
            value: JsonElement
        ): DataItem =
            when (attribute.type) {
                DocumentAttributeType.String, is DocumentAttributeType.StringOptions ->
                    if (value is JsonPrimitive && value.isString) {
                        value.content.toDataItem()
                    } else {
                        throw InvalidRequestException("'${attribute.identifier}' in '$transactionTypeIdentifier' is not a string")
                    }

                DocumentAttributeType.Number, is DocumentAttributeType.IntegerOptions ->
                    convertToNumber(value)
                        ?: throw InvalidRequestException("'${attribute.identifier}' in '$transactionTypeIdentifier': not a number")

                DocumentAttributeType.Blob -> if (value is JsonPrimitive && value.isString) {
                    value.content.fromBase64Url().toDataItem()
                } else {
                    throw InvalidRequestException("'${attribute.identifier}' in '$transactionTypeIdentifier': not a number")
                }

                DocumentAttributeType.Boolean -> (value as? JsonPrimitive)?.booleanOrNull?.toDataItem()
                    ?: throw InvalidRequestException("'${attribute.identifier}' in '$transactionTypeIdentifier': not a boolean")

                DocumentAttributeType.ComplexType -> {
                    if (value !is JsonObject) {
                        throw InvalidRequestException("'${attribute.identifier}' in '$transactionTypeIdentifier': not an object")
                    }
                    buildCborMap {
                        for (attr in attribute.embeddedAttributes) {
                            val attrValue = value[attr.identifier]
                            if (attrValue != null) {
                                put(
                                    attr.identifier,
                                    convertToCbor(transactionTypeIdentifier, attr, attrValue)
                                )
                            }
                        }
                    }
                }

                else -> throw InvalidRequestException("'${attribute.identifier}' in '$transactionTypeIdentifier': unsupported type")
            }

        private fun convertToNumber(value: JsonElement): DataItem? =
            if (value is JsonPrimitive && !value.isString) {
                value.longOrNull?.toDataItem() ?: value.doubleOrNull?.toDataItem()
            } else {
                null
            }
    }
}