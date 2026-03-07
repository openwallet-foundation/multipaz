package org.multipaz.web.records

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.web.common.jsonGet

data class RecordsNames(
    val state: String,
    val official: String,
    val subject: String
)

data class RecordsMetadata(
    val issuerUrl: String?,
    val names: RecordsNames
)

suspend fun fetchRecordsMetadata(serviceUrl: String): RecordsMetadata {
    val result = jsonGet("$serviceUrl/identity/metadata").jsonObject
    val names = result["names"]!!.jsonObject
    return RecordsMetadata(
        issuerUrl = result["issuer_url"]?.jsonPrimitive?.content,
        names = RecordsNames(
            state = names["state"]!!.jsonPrimitive.content,
            official = names["official"]!!.jsonPrimitive.content,
            subject = names["subject"]!!.jsonPrimitive.content,
        )
    )
}
