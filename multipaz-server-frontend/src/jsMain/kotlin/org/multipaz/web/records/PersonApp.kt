package org.multipaz.web.records

import io.ktor.http.Url
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.util.toBase64Url
import org.multipaz.web.common.MultipazProps
import org.multipaz.web.common.jsonPost
import org.multipaz.web.common.mainScope
import org.multipaz.web.common.resolveUrl
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.FC
import react.dom.html.ReactHTML.button
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.window.window
import kotlin.collections.iterator
import kotlin.random.Random

/**
 * Renders the screen with the single individual's data in the System of Record.
 */
val PersonApp = FC<MultipazProps> { props ->
    val serviceUrl = resolveUrl(props.definition.getAttribute("data-service-url") ?: "records")
    val token = Url(window.location.href).parameters["token"]
    var schema by useState<Map<String, SchemaItem>>(emptyMap())
    var data by useState(buildJsonObject {})
    var loggedIn by useState(false)
    var metadata by useState<RecordsMetadata?>(null)

    useEffect(true) {
        val fetchedSchema = fetchSchema(serviceUrl)
        schema = fetchedSchema
        data = token?.let { token ->
            fetchPersonData(serviceUrl, token, fetchedSchema)
        } ?: buildJsonObject {
            putJsonObject("core") {}
            putJsonObject("records") {}
        }
    }

    useEffect(true) {
        metadata = fetchRecordsMetadata(serviceUrl)
    }

    Login {
        value = loggedIn
        this.serviceUrl = serviceUrl
        onChange = { loggedIn = it }
    }

    metadata?.names?.let { names ->
        h1 {
            +names.state
        }
        h2 {
            + (data["core"]?.jsonObject?.get("given_name")?.jsonPrimitive?.content
                ?: "Individual")
            + "'s Record"
        }
        p {
            +"""
                You are playing the role of ${names.official} of ${names.state}.
                This page contains all the records for the given individual that
                ${names.state} maintains. The first section contains data that is
                applicable to everyone and always exists. Additional sections are
                optional and can be added or removed.
            """
            +if (loggedIn) {
                """
                   You are logged in and thus have power to alter
                   the information, be sure to save it using button at the bottom of this
                   page. 
                """
            } else {
                """
                    As you are not logged in, you can play with the data, but it won't be saved.
                    Use "Login" button at the top of the page to log in.
                """
            }
        }
    }

    if (!data.containsKey("core")) {
        span { +"Loading..." }
    } else {
        val coreSchema = schema["core"]!!
        val coreValue = data["core"]!!
        val records = data["records"]!!.jsonObject
        val handleChange: (JsonPath, JsonElement) -> Unit = { path, newValue ->
            data = data.updateAt(path, newValue).jsonObject
        }
        val handleRemove: (JsonPath) -> Unit = { path ->
            data = data.removeAt(path).jsonObject
        }

        div {
            key = "core"
            div {
                className = ClassName("records-record-header")
                +"Core Information"
            }
            className = ClassName("records-editor")
            ItemEditor {
                this.schema = coreSchema
                this.value = coreValue
                path = listOf("core")
                onChange = handleChange
            }
        }
        for ((recordId, recordSchema) in schema) {
            if (recordId == "core") {
                continue
            }
            div {
                key = recordId
                div {
                    className = ClassName("records-record-header")
                    +recordSchema.displayName
                    +" "
                    button {
                        className = ClassName("records-list-add")
                        +"+ Add"
                        onClick = {
                            // This is executed when the user actually clicks, so need a fresh value
                            // for records!
                            val records = data["records"]?.jsonObject?.get(recordId)?.jsonObject
                                ?: buildJsonObject {  }
                            val newId = makeUniqueId(records)
                            val newValue = if (recordSchema.type is SchemaType.Compound) {
                                buildJsonObject {
                                    put("instance_title",
                                        makeUniqueTitle(recordSchema.displayName, records))
                                }
                            } else {
                                defaultValue(recordSchema.type)
                            }
                            data = data.updateAt(
                                path = listOf("records", recordId, newId),
                                value = newValue
                            ).jsonObject
                        }
                    }
                }
                for ((instanceId, instance) in records[recordId]?.jsonObject ?: emptyMap()) {
                    div {
                        key = instanceId
                        className = ClassName("records-editor")
                        ItemEditor {
                            this.schema = SchemaItem(
                                displayName = (instance as? JsonObject)?.get("instance_title")?.jsonPrimitive?.content ?: "",
                                icon = recordSchema.icon,
                                lazyType = lazy { recordSchema.type }
                            )
                            this.value = instance
                            path = listOf("records", recordId, instanceId)
                            onRemove = handleRemove
                            onChange = handleChange
                        }
                    }
                }
            }
        }
        div {
            className = ClassName("records-person-buttons")
            button {
                className = ClassName("records-person-save")
                +"Save"
                onClick = {
                    mainScope.launch {
                        val command = if (token == null) "create" else "update"
                        val result = jsonPost("$serviceUrl/identity/$command") {
                            token?.let {
                                put("token", it)
                            }
                            put("core", data["core"]!!)
                            put("records", data["records"]!!)
                        }.jsonObject
                        if (result.containsKey("error")) {
                            println(result)
                        } else {
                            if (token == null) {
                                val src = window.location.href
                                val newToken = result["token"]!!.jsonPrimitive.content
                                window.location.replace("$src?token=$newToken")
                            }
                        }
                    }
                }
            }
            if (token != null) {
                +" "
                button {
                    className = ClassName("records-person-delete")
                    +"Delete"
                    onClick = {
                        mainScope.launch {

                        }
                    }
                }
            }
        }
    }
}

private fun makeUniqueId(records: JsonObject): String {
    while (true) {
        val newId = Random.nextBytes(6).toBase64Url()
        if (!records.containsKey(newId)) {
            return newId
        }
    }
}

private fun makeUniqueTitle(name: String, records: JsonObject): String {
    var num = records.size + 1
    loop@while (true) {
        val newTitle = "$name #$num"
        for (record in records.values) {
            println("trying '$newTitle' : '${record.jsonObject["instance_title"]}'")
            if (record is JsonObject &&
                record["instance_title"]?.jsonPrimitive?.content == newTitle) {
                num++
                continue@loop
            }
        }
        return newTitle
    }
}

private suspend fun fetchPersonData(
    baseUrl: String,
    token: String,
    schema: Map<String, SchemaItem>
): JsonObject =
    jsonPost("$baseUrl/identity/get", "identity") {
        put("token", token)
        putJsonArray("core") {
            val coreAttributes = (schema["core"]!!.type as SchemaType.Compound).attributes
            for (identifier in coreAttributes.keys) {
                add(identifier)
            }
        }
        putJsonObject("records") {
            for (identifier in schema.keys) {
                if (identifier != "core") {
                    putJsonArray(identifier) {}
                }
            }
        }
    }.jsonObject
