package org.multipaz.openid4vci.credential

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Nint
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.drawing.Canvas
import org.multipaz.util.Logger

/**
 * Represents display metadata for the issued credentials.
 *
 * @property title name for the document that credentials represent
 * @property cardArt card art as data url for the document that credentials represent
 */
class CredentialDisplay(
    val title: String,
    val cardArtUrl: String?
) {
    companion object {
        /**
         * Creates [CredentialDisplay] using credential data and the name of the
         * configuration parameter that describes how title and card art should be
         * generated.
         *
         * Server configuration is queried for the parameter given in [configName]. It must be a
         * JSON object. If the parameter is not given or JSON object does not contain value
         * "title", a [CredentialDisplay] with the title "Untitled" is returned.
         *
         * Otherwise the value of the `title` is used to create title string, dot-separated names
         * in curly brackets are substituted with the content of the fields in
         * [systemOfRecordData].
         *
         * If "cardArt" value is present and is an object, it is used to create card art.
         * The following values are used:
         *  - `blank`: name of the resource image that contains blank image of this document
         *  - if `blank` is not given, `width` (default 810) and `height` (default 510) define
         *   the size of the drawing area that will start up as fully transparent.
         *  - `content`: array of drawing commands
         *
         *  Drawing commands are identified by their operation name encoded in the field named
         *  `op`. The following operations and their parameters are supported:
         *  - `text` - draws text, parameters defined in [Canvas.drawText], and also:
         *     - field `text` holds the text to draw, dot-separated names in curly brackets
         *        are substituted with the content of the fields in [systemOfRecordData].
         *  - `rect` - draws rectangle, parameters defined in [Canvas.drawRect]
         *  - `image` - draws image, parameters defined in [Canvas.drawImage], and also
         *     - `field` - dot-separated names of the fields to find image data in [systemOfRecordData]
         *     - `image` - name of the image from the resources if image data is missing or `field`
         *       is not given
         *
         * @param systemOfRecordData data that was used to create the credential
         * @param configName name of the server configuration parameter that hold the definition
         *  of the card art drawing
         * @return new [CredentialDisplay]
         */
        suspend fun create(
            systemOfRecordData: DataItem,
            configName: String
        ): CredentialDisplay {
            val mdlConfig = BackendEnvironment.getInterface(Configuration::class)!!
                .getValue(configName)?.let { Json.parseToJsonElement(it).jsonObject }
            val titleConfig = mdlConfig?.get("title") as? JsonPrimitive
            val cardArtConfig = mdlConfig?.get("card_art") as? JsonObject
            return CredentialDisplay(
                title = titleConfig?.let {
                        injectFields(it.content, systemOfRecordData)
                    } ?: "Untitled",
                cardArtUrl = cardArtConfig?.let {
                    cardArt(systemOfRecordData, cardArtConfig)?.toDataUrl()
                }
            )
        }

        /**
         * Card art drawing interface, exposed for testing.
         *
         * @param systemOfRecordData data that was used to create the credential
         * @param cardArtConfig JSON configuration to draw card art
         * @return new [Canvas] if drawing is correctly specified
         */
        suspend fun cardArt(
            systemOfRecordData: DataItem,
            cardArtConfig: JsonObject
        ): Canvas? {
            val contentList = cardArtConfig["content"] as? JsonArray ?: return null
            val blank = cardArtConfig["blank"]?.jsonPrimitive?.content
            val canvas = if (blank != null) {
                Canvas.fromImage(blank)
            } else {
                val width = cardArtConfig["width"]?.jsonPrimitive?.intOrNull ?: 810
                val height = cardArtConfig["height"]?.jsonPrimitive?.intOrNull ?: 510
                Canvas.createBlank(width, height)
            }
            for (content in contentList) {
                val command = content as? JsonObject ?: continue
                val opName = command["op"] as? JsonPrimitive ?: continue
                when (opName.content) {
                    "text" -> {
                        val rawText = (command["text"] as? JsonPrimitive)?.content
                        if (rawText != null) {
                            val text = injectFields(rawText, systemOfRecordData)
                            if (command["uppercase"]?.jsonPrimitive?.content == "true") {
                                canvas.drawText(text.uppercase(), command)
                            } else {
                                canvas.drawText(text, command)
                            }
                        }
                    }
                    "rect" -> canvas.drawRect(command)
                    "image" -> {
                        val fieldValue = (command["field"] as? JsonPrimitive)?.let {
                            getField(systemOfRecordData, it.content)
                        }
                        if (fieldValue is Bstr) {
                            canvas.drawImage(ByteString(fieldValue.asBstr), command)
                        } else {
                            val image = (command["image"] as? JsonPrimitive)?.content
                            if (image != null) {
                                canvas.drawImage(image, command)
                            }
                        }
                    }
                    else -> Logger.e(TAG, "Unknown cardArt operation: '${opName.content}'")
                }
            }
            return canvas
        }

        private val fieldCallout = Regex("""\{([a-zA-Z0-9_-]+(\.[a-zA-Z0-9_-]+)*)\}""")

        private fun injectFields(
            text: String,
            systemOfRecordData: DataItem
        ): String =
            fieldCallout.replace(text) { match ->
                val field = match.groups[1]!!.value
                if (field == "brace-open") {
                    return@replace "{"
                }
                if (field == "brace-close") {
                    return@replace "}"
                }
                when (val value = getField(systemOfRecordData, field)) {
                    null -> ""
                    is Tstr -> value.asTstr
                    is Uint -> value.value.toString()
                    is Nint -> "-" + value.value.toString()
                    else -> "<${value::class.simpleName}>"
                }
            }

        private fun getField(
            systemOfRecordData: DataItem,
            field: String
        ): DataItem? {
            var data: DataItem = systemOfRecordData
            for (fieldName in field.split(".")) {
                if (data is CborMap && data.hasKey(fieldName)) {
                    data = data[fieldName]
                } else {
                    return null
                }
            }
            return data
        }

        private const val TAG = "CredentialDisplay"
    }
}