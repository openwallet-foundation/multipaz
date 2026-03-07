package org.multipaz.web.records

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.events.EventHandler
import web.file.FileReader
import web.html.HTMLInputElement
import web.html.InputType
import web.html.checkbox
import web.html.date
import web.html.datetimeLocal
import web.html.file
import web.html.number
import web.html.text
import kotlin.collections.iterator

// -- Path-based immutable update over JsonElement -----------------
// This is the single mutation point. All editors call onChange(path, newValue)
// and the root component applies it here.

typealias JsonPath = List<Any>  // String for object keys, Int for array indices

fun JsonElement.updateAt(path: JsonPath, value: JsonElement): JsonElement {
    if (path.isEmpty()) {
        return value
    }
    val key = path.first()
    if (value == JsonNull && key is String) {
        return removeAt(path)
    }
    val rest = path.drop(1)
    return when (key) {
        is String -> JsonObject(
            ((this as? JsonObject)?.toMutableMap() ?: mutableMapOf()).also { map ->
                map[key] = (map[key] ?: JsonNull).updateAt(rest, value)
            }
        )
        is Int -> JsonArray(
            jsonArray.toMutableList().also { list ->
                list[key] = list[key].updateAt(rest, value)
            }
        )
        else -> this  // can't descend into a primitive
    }
}

fun JsonElement.removeAt(path: JsonPath): JsonElement {
    val head = path.first()
    if (path.size == 1) {
        return when (this) {
            is JsonArray -> {
                val idx = head as Int
                JsonArray(this.toMutableList().also { it.removeAt(idx) })
            }
            is JsonObject -> JsonObject(
                this.toMutableMap().also { it.remove(head as String) }
            )
            else -> this
        }
    }
    val rest = path.drop(1)
    return when (this) {
        is JsonObject -> JsonObject(
            this.toMutableMap().also { map ->
                val key = head as String
                map[key] = map[key]!!.removeAt(rest)
            }
        )
        is JsonArray -> JsonArray(
            this.toMutableList().also { list ->
                val idx = head as Int
                list[idx] = list[idx].removeAt(rest)
            }
        )
        else -> this
    }
}

/** Default value for a given type. */
fun defaultValue(type: SchemaType): JsonElement = when (type) {
    is SchemaType.Compound -> buildJsonObject {  }
    is SchemaType.List -> buildJsonArray {  }
    is SchemaType.Options -> type.options.values.first()
    else -> JsonNull
}

/**
 * Parameters to [ItemEditor].
 */
external interface EditorProps : Props {
    /** Schema item that describes this data */
    var schema: SchemaItem
    /** Value being edited */
    var value: JsonElement
    /** Path to this data element in the overall data structure for this person */
    var path: JsonPath
    /** If removable, function to remove this data item */
    var onRemove: ((JsonPath) -> Unit)?
    /** Function to change this data item */
    var onChange: (JsonPath, JsonElement) -> Unit
}

/**
 * Editor component for a single data item.
 */
val ItemEditor: FC<EditorProps> = FC { props ->
    when (props.schema.type) {
        is SchemaType.String   -> StringEditor(copyFrom(props))
        is SchemaType.Date     -> DateEditor(copyFrom(props))
        is SchemaType.DateTime -> DateTimeEditor(copyFrom(props))
        is SchemaType.Number   -> NumberEditor(copyFrom(props))
        is SchemaType.Boolean  -> BooleanEditor(copyFrom(props))
        is SchemaType.Blob     -> FileEditor(copyFrom(props))
        is SchemaType.Picture  -> PictureEditor(copyFrom(props))
        is SchemaType.Compound -> CompoundEditor(copyFrom(props))
        is SchemaType.List     -> ListEditor(copyFrom(props))
        is SchemaType.Options  -> OptionsEditor(copyFrom(props))
    }
}

private fun copyFrom(src: EditorProps): EditorProps.() -> Unit = {
    schema = src.schema
    value = src.value
    path = src.path
    onChange = src.onChange
    onRemove = src.onRemove
}

// -- String -------------------------------------------------------

val StringEditor = FC<EditorProps> { props ->
    val text = (props.value as? JsonPrimitive)?.contentOrNull ?: ""

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        input {
            className = ClassName("records-input")
            type = InputType.text
            value = text
            placeholder = props.schema.displayName
            onChange = { e ->
                props.onChange(props.path, JsonPrimitive(e.target.value))
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- Date ---------------------------------------------------------

val DateEditor = FC<EditorProps> { props ->
    val text = (props.value as? JsonPrimitive)?.contentOrNull ?: ""

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        input {
            className = ClassName("records-date-input")
            type = InputType.date
            value = text
            onChange = { e ->
                val value = e.target.value
                props.onChange(props.path, if (value.isEmpty()) JsonNull else JsonPrimitive(value))
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- Date and Time --------------------------------------------------

val DateTimeEditor = FC<EditorProps> { props ->
    val text = (props.value as? JsonPrimitive)?.contentOrNull ?: ""

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        input {
            className = ClassName("records-date-input")
            type = InputType.datetimeLocal
            value = text
            onChange = { e ->
                val value = e.target.value
                props.onChange(props.path, if (value.isEmpty()) JsonNull else JsonPrimitive(value))
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- Number --------------------------------------------------------

val NumberEditor = FC<EditorProps> { props ->
    val num = (props.value as? JsonPrimitive)?.doubleOrNull ?: 0.0
    // We keep a local string state so the user can type freely
    // (e.g. clear the field, type a minus sign, etc.)
    var draft by useState(num.toString())

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        input {
            className = ClassName("records-input records-input--number")
            type = InputType.number
            value = draft
            onChange = { e ->
                draft = e.target.value
                val parsed = e.target.value.toDoubleOrNull()
                if (parsed != null) {
                    props.onChange(props.path, JsonPrimitive(parsed))
                }
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- Boolean ------------------------------------------------------

val BooleanEditor = FC<EditorProps> { props ->
    val checked = (props.value as? JsonPrimitive)?.booleanOrNull ?: false

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        div {
            className = ClassName("records-checkbox-row")
            input {
                className = ClassName("records-checkbox")
                type = InputType.checkbox
                this.checked = checked
                onChange = { e ->
                    props.onChange(props.path, JsonPrimitive(e.target.checked))
                }
            }
            span {
                className = ClassName("records-checkbox-label")
                +if (checked) "Yes" else "No"
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- File upload (Blob) -------------------------------------------
// Stores the file as a base64-encoded JSON string, or JsonNull if empty.

external interface FileEditorProps : EditorProps {
    var accept: String
}

@OptIn(ExperimentalWasmJsInterop::class)
val FileEditor = FC<FileEditorProps> { props ->
    val current = (props.value as? JsonPrimitive)?.contentOrNull

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        div {
            className = ClassName("records-file-row")
            if (current != null) {
                span {
                    className = ClassName("records-file-name")
                    +"(file attached)"
                }
                button {
                    className = ClassName("records-file-clear")
                    +"Clear"
                    onClick = { props.onChange(props.path, JsonNull) }
                }
            } else {
                input {
                    className = ClassName("records-file-input")
                    type = InputType.file
                    accept = props.accept
                    onChange = { e ->
                        val file = e.target.unsafeCast<HTMLInputElement>().files?.item(0)
                        if (file != null) {
                            val reader = FileReader()
                            reader.onload = EventHandler {
                                // strip data:...;base64, prefix
                                val base64 = (reader.result as String).substringAfter(',')
                                props.onChange(props.path, JsonPrimitive(base64))
                            }
                            reader.readAsDataURL(file)
                        }
                    }
                }
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- Picture upload -----------------------------------------------
// Same as Blob but with image preview and image/* accept filter.

@OptIn(ExperimentalWasmJsInterop::class)
val PictureEditor = FC<EditorProps> { props ->
    val current = (props.value as? JsonPrimitive)?.contentOrNull

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        div {
            className = ClassName("records-file-row")
            if (current != null) {
                img {
                    className = ClassName("records-picture-preview")
                    src = "data:image/*;base64,$current"
                }
                button {
                    className = ClassName("records-file-clear")
                    +"Clear"
                    onClick = { props.onChange(props.path, JsonNull) }
                }
            } else {
                input {
                    className = ClassName("records-file-input")
                    type = InputType.file
                    accept = "image/*"
                    onChange = { e ->
                        val file = e.target.unsafeCast<HTMLInputElement>().files?.item(0)
                        if (file != null) {
                            val reader = FileReader()
                            reader.onload = EventHandler {
                                // strip data:...;base64, prefix
                                val base64 = (reader.result as String).substringAfter(',')
                                props.onChange(props.path, JsonPrimitive(base64))
                            }
                            reader.readAsDataURL(file)
                        }
                    }
                }
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- Options (dropdown) -------------------------------------------

val OptionsEditor = FC<EditorProps> { props ->
    val type = props.schema.type as SchemaType.Options
    val currentValue = if (props.value == JsonNull) {
        ""
    } else if (type.base === SchemaType.String) {
        props.value.jsonPrimitive.content
    } else {
        props.value.toString()
    }

    div {
        className = ClassName("records-field")
        Label {
            displayName = props.schema.displayName
            icon = props.schema.icon
        }
        select {
            className = ClassName("records-select")
            value = currentValue
            onChange = { e ->
                val selected = e.target.value
                val newValue = if (selected == "") {
                    JsonNull
                } else if (type.base === SchemaType.String) {
                    JsonPrimitive(selected)
                } else {
                    Json.parseToJsonElement(selected)
                }
                props.onChange(props.path, newValue)
            }
            for ((optionLabel, optionValue) in type.options) {
                option {
                    value = if (optionValue == JsonNull) {
                        ""
                    } else if (type.base === SchemaType.String) {
                        optionValue.jsonPrimitive.content
                    } else {
                        optionValue
                    }
                    +optionLabel
                }
            }
        }
        props.onRemove?.let { onRemove ->
            button {
                className = ClassName("records-list-item-remove")
                +"Remove"
                onClick = { onRemove(props.path) }
            }
        }
    }
}

// -- Compound (nested object) -------------------------------------

val CompoundEditor = FC<EditorProps> { props ->
    val type = props.schema.type as SchemaType.Compound
    val obj = props.value as? JsonObject ?: buildJsonObject {}
    val isRoot = props.path.isEmpty()
    var collapsed by useState(true)

    val wrapperClass = if (isRoot) "records-compound" else "records-compound records-compound--nested"

    div {
        className = ClassName(wrapperClass)

        // Header (collapsible, except root is always open)
        if (!isRoot) {
            div {
                className = ClassName("records-compound-header")
                onClick = { collapsed = !collapsed }
                span {
                    className = ClassName(
                        if (collapsed) "records-chevron" else "records-chevron records-chevron--open"
                    )
                }
                span {
                    className = ClassName("records-compound-title")
                    +props.schema.displayName
                }
                props.onRemove?.let { onRemove ->
                    button {
                        className = ClassName("records-list-item-remove")
                        +"Remove"
                        onClick = { onRemove(props.path) }
                    }
                }
            }
        }

        // Body
        if (!collapsed || isRoot) {
            div {
                className = ClassName("records-compound-body")
                for ((key, childItem) in type.attributes) {
                    ItemEditor {
                        schema = childItem
                        value = obj[key] ?: JsonNull
                        path = props.path + key
                        onChange = props.onChange
                    }
                }
            }
        }
    }
}

// -- List (array) -------------------------------------------------

val ListEditor = FC<EditorProps> { props ->
    val type = props.schema.type as SchemaType.List
    val arr = props.value as? JsonArray ?: buildJsonArray {}
    val isPrimitive = type.element is SchemaType.String
            || type.element is SchemaType.Number
            || type.element is SchemaType.Date
            || type.element is SchemaType.Boolean

    val inlineClass = if (isPrimitive) "records-list records-list--inline" else "records-list"

    div {
        className = ClassName(inlineClass)

        div {
            className = ClassName("records-list-header")
            span {
                className = ClassName("records-list-title")
                +props.schema.displayName
            }
            span {
                className = ClassName("records-list-count")
                +"${arr.size}"
            }
            button {
                className = ClassName("records-list-add")
                +"+ Add"
                onClick = {
                    val newArr = JsonArray(arr + defaultValue(type.element))
                    props.onChange(props.path, newArr)
                }
            }
            // Removes this list from the parent; generally we do not expect schemas like this
            props.onRemove?.let { onRemove ->
                button {
                    className = ClassName("records-list-item-remove")
                    +"Remove"
                    onClick = { onRemove(props.path) }
                }
            }
        }

        div {
            className = ClassName("records-list-items")
            arr.forEachIndexed { idx, element ->
                div {
                    className = ClassName("records-list-item")
                    key = "$idx"

                    // Create a synthetic SchemaItem for the element
                    val elementItem = SchemaItem(
                        displayName = "#${idx + 1}",
                        icon = null,
                        lazyType = lazy { type.element }
                    )
                    ItemEditor {
                        schema = elementItem
                        value = element
                        path = props.path + idx
                        onRemove = {
                            val newArr = JsonArray(arr.toMutableList().also { it.removeAt(idx) })
                            props.onChange(props.path, newArr)
                        }
                        onChange = props.onChange
                    }
                }
            }
        }
    }
}

// -- Shared label component ---------------------------------------

external interface LabelProps : Props {
    var displayName: String
    var icon: String?
}

val Label = FC<LabelProps> { props ->
    span {
        className = ClassName("records-icon material-symbols-outlined")
        if (props.icon != null) {
            +props.icon
        }
    }
    span {
        className = ClassName("records-label")
        +props.displayName
    }
}

