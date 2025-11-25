package org.multipaz.revocation

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.crypto.X509Cert

/**
 * Data that describes how validity/revocation status of a credential can be obtained.
 *
 * SD-JWT revocation status if present should be [RevocationStatus.StatusList], ISO mdoc
 * revocation status if present can be either [RevocationStatus.StatusList] or
 * [RevocationStatus.IdentifierList]. Also, if the revocation status cannot be parsed it will
 * be [RevocationStatus.Unknown]. This simply means that the current version of the library
 * does not know how to parse it, not that the credential is invalid. An application should
 * make its own determination in such cases.
 */
sealed class RevocationStatus {

    /**
     * Status list is a format defined
     * [Token Status List](https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/)
     * draft standard.
     *
     * [org.multipaz.revocation.StatusList] can be used to determine revocation status
     * of a credential.
     *
     * Theoretically, status lists can be used to manage a mixture of both ISO mdoc and SD-JWT
     * credentials, but this should not be done in most cases. The data format for status
     * list allows status codes with various number of bits, which may be required for SD-JWT.
     * However ISO mdoc is currently limited to one-bit status. This makes cross-format status
     * list sharing awkward.
     */
    data class StatusList(
        val idx: Int,
        val uri: String,
        val certificate: X509Cert?
    ): RevocationStatus() {
        override fun toDataItem(): DataItem = buildCborMap {
            putCborMap("status_list") {
                put("idx", idx)
                put("uri", uri)
                certificate?.let { put("certificate", certificate.toDataItem()) }
            }
        }

        override fun toJson() = buildJsonObject {
            putJsonObject("status_list") {
                put("idx", idx)
                put("uri", uri)
                if (certificate != null) {
                    throw IllegalArgumentException("certificate field is not supported in JSON")
                }
            }
        }
    }

    /**
     * Identifier list as defined in ISO/IEC 18013-5 Section 12.3.6.4 "Identifier list details".
     *
     * [org.multipaz.revocation.IdentifierList] can be used to determine revocation status of
     * a credential.
     */
    data class IdentifierList(
        val id: ByteString,
        val uri: String,
        val certificate: X509Cert?
    ): RevocationStatus() {
        override fun toDataItem(): DataItem = buildCborMap {
            putCborMap("identifier_list") {
                put("id", id.toByteArray())
                put("uri", uri)
                certificate?.let { put("certificate", certificate.toDataItem()) }
            }
        }

        /**
         * Must not be called, [RevocationStatus.IdentifierList] is ISO-mdoc-specific revocation
         * status format and it cannot be encoded as JSON.
         */
        override fun toJson() = throw NotImplementedError("IdentifierList.toJson")
    }

    /** Revocation status that is present in the credential, but cannot be parsed by the library. */
    sealed class Unknown: RevocationStatus()

    /** ISO mdoc revocation status that could not be parsed. */
    data class UnknownCbor(val dataItem: DataItem): Unknown() {
        override fun toDataItem() = dataItem
        override fun toJson() = throw NotImplementedError("UnknownCbor.toJson")
    }

    /** SD-JWT revocation status that cannot be parsed. */
    data class UnknownJson(val json: JsonElement): Unknown() {
        override fun toDataItem() = throw NotImplementedError("UnknownJson.toDataItem")
        override fun toJson() = json
    }

    /** Revocation status expressed as CBOR to use in ISO mdoc credentials. */
    abstract fun toDataItem(): DataItem

    /** Revocation status expressed as JSON to use in SD-JWT credentials */
    abstract fun toJson(): JsonElement

    companion object {
        /** Parses revocation status expressed as CBOR */
        fun fromDataItem(dataItem: DataItem): RevocationStatus {
            if (dataItem.hasKey("status_list")) {
                val map = dataItem["status_list"]
                if (map is CborMap && map.hasKey("idx") && map.hasKey("uri")) {
                    val idx = map["idx"]
                    val uri = map["uri"]
                    val certificate = if (map.hasKey("certificate") ) {
                        map["certificate"] as? Bstr
                    } else {
                        null
                    }
                    if (idx is Uint && uri is Tstr) {
                        return StatusList(idx.value.toInt(), uri.value, certificate?.asX509Cert)
                    }
                }
            } else if (dataItem.hasKey("identifier_list")) {
                val map = dataItem["identifier_list"]
                if (map is CborMap && map.hasKey("id") && map.hasKey("uri")) {
                    val id = map["id"]
                    val uri = map["uri"]
                    val certificate = if (map.hasKey("certificate") ) {
                        map["certificate"] as? Bstr
                    } else {
                        null
                    }
                    if (id is Bstr && uri is Tstr) {
                        return IdentifierList(ByteString(id.value), uri.value, certificate?.asX509Cert)
                    }
                }
            }
            return UnknownCbor(dataItem)
        }

        /** Parses revocation status expressed as JSON */
        fun fromJson(json: JsonElement): RevocationStatus {
            if (json is JsonObject) {
                val map = json["status_list"]
                if (map is JsonObject) {
                    val idx = map["idx"]
                    val uri = map["uri"]
                    if (idx is JsonPrimitive && idx.intOrNull != null && uri is JsonPrimitive) {
                        return StatusList(idx.int, uri.content, null)
                    }
                }
            }
            return UnknownJson(json)
        }
    }
}