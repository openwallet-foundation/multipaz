package org.multipaz.provisioning

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * Describes a format of a credential.
 */
@CborSerializable
sealed class CredentialFormat {
    abstract val formatId: String
    data class Mdoc(val docType: String) : CredentialFormat() {
        override val formatId: String get() = "mso_mdoc"
    }

    data class SdJwt(val vct: String) : CredentialFormat() {
        override val formatId: String get() = "dc+sd-jwt"
    }

    companion object {
        fun fromJson(json: JsonObject): CredentialFormat? {
            return when (val format = json["format"]?.jsonPrimitive?.content) {
                "dc+sd-jwt" -> SdJwt(json["vct"]!!.jsonPrimitive.content)
                "mso_mdoc" -> Mdoc(json["doctype"]!!.jsonPrimitive.content)
                null -> null
                else -> throw InvalidRequestException("Unsupported format '$format'")
            }
        }
    }
}