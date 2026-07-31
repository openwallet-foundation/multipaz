package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.option
import react.useState
import react.useEffectOnce
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.InputType
import kotlinx.browser.window
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import org.multipaz.cbor.ByteStringFormat
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.cbor.Cbor
import org.multipaz.util.toHex
import org.multipaz.util.toBase64Url

val CborDecoderComponent = FC {
    var mode by useState("decode") // "decode" (CBOR -> CDN) or "encode" (CDN -> CBOR)
    var rawInput by useState("")
    var outputDiagnostics by useState("")
    var outputHex by useState("")
    var outputB64Url by useState("")
    var prettyPrint by useState(true)
    var decodeEmbeddedCbor by useState(true)
    var useEmbeddedCborOpportunistically by useState(true)
    var useEmbeddedCertsOpportunistically by useState(true)
    var annotateCoseOpportunistically by useState(true)
    var useAppExtensions by useState(true)
    var sortKeys by useState(false)
    var bstrFormat by useState(ByteStringFormat.HEX)
    var copyStatus by useState("")
    var copyHexStatus by useState("")
    var copyB64Status by useState("")

    fun processInput(inputStr: String) {
        val cleanInput = inputStr.trim()
        if (cleanInput.isEmpty()) return
        if (mode == "decode") {
            try {
                val bytes = decodeInputToBytes(cleanInput)
                if (bytes.isEmpty()) {
                    outputDiagnostics = "Input is empty"
                } else {
                    val options = CdnGeneratorOptions(
                        prettyPrint = prettyPrint,
                        useEmbeddedCborShorthand = decodeEmbeddedCbor,
                        useEmbeddedCborOpportunistically = useEmbeddedCborOpportunistically,
                        useEmbeddedCertsOpportunistically = useEmbeddedCertsOpportunistically,
                        annotateCoseOpportunistically = annotateCoseOpportunistically,
                        useApplicationExtensions = useAppExtensions,
                        sortMapKeys = sortKeys,
                        byteStringFormat = bstrFormat
                    )
                    outputDiagnostics = Cdn.encode(bytes, options)
                    updateUrlHashPayload(cleanInput)
                }
            } catch (e: Exception) {
                outputDiagnostics = "Error decoding: " + (e.message ?: "Unknown decoding error")
            }
        } else {
            try {
                val item = Cdn.parse(cleanInput)
                val encodedBytes = Cbor.encode(item)
                outputHex = encodedBytes.toHex()
                outputB64Url = encodedBytes.toBase64Url()
                outputDiagnostics = ""
                updateUrlHashPayload(cleanInput)
            } catch (e: Exception) {
                outputDiagnostics = "Error parsing CDN: " + (e.message ?: "Unknown syntax error")
                outputHex = ""
                outputB64Url = ""
            }
        }
    }

    useEffectOnce {
        val hashPayload = getUrlHashPayload()
        if (hashPayload.isNotEmpty()) {
            rawInput = hashPayload
            processInput(hashPayload)
        }
    }

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 16.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 32.px
        }

        h2 {
            css {
                fontSize = 1.8.rem
                fontWeight = FontWeight.bold
                margin = Margin(0.px, 0.px, 16.px, 0.px)
                color = Color("#f8fafc")
            }
            +"CBOR & Concise Diagnostic Notation (CDN) Tool"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Convert raw CBOR bytes (Hex or Base64) to Concise Diagnostic Notation (CDN), or compile CDN text literals to CBOR bytes."
        }

        // Mode switch tabs
        div {
            css {
                display = Display.flex
                gap = 12.px
                marginBottom = 24.px
            }

            button {
                css {
                    padding = Padding(8.px, 16.px)
                    borderRadius = 8.px
                    border = None.none
                    fontWeight = FontWeight.bold
                    fontSize = 14.px
                    cursor = Cursor.pointer
                    if (mode == "decode") {
                        backgroundColor = Color("#3b82f6")
                        color = Color("#ffffff")
                    } else {
                        backgroundColor = Color("#334155")
                        color = Color("#94a3b8")
                    }
                }
                onClick = {
                    mode = "decode"
                    rawInput = ""
                    outputDiagnostics = ""
                    outputHex = ""
                    outputB64Url = ""
                }
                +"Decode (CBOR Bytes → CDN)"
            }

            button {
                css {
                    padding = Padding(8.px, 16.px)
                    borderRadius = 8.px
                    border = None.none
                    fontWeight = FontWeight.bold
                    fontSize = 14.px
                    cursor = Cursor.pointer
                    if (mode == "encode") {
                        backgroundColor = Color("#3b82f6")
                        color = Color("#ffffff")
                    } else {
                        backgroundColor = Color("#334155")
                        color = Color("#94a3b8")
                    }
                }
                onClick = {
                    mode = "encode"
                    rawInput = ""
                    outputDiagnostics = ""
                    outputHex = ""
                    outputB64Url = ""
                }
                +"Encode (CDN Text → CBOR Bytes)"
            }
        }

        if (mode == "decode") {
            div {
                css {
                    display = Display.flex
                    justifyContent = JustifyContent.spaceBetween
                    alignItems = AlignItems.center
                    marginBottom = 8.px
                }

                label {
                    css {
                        fontWeight = FontWeight.normal
                        color = Color("#cbd5e1")
                    }
                    +"CBOR Raw Data (Hex, Base64 or Base64Url):"
                }

                label {
                    css {
                        background = Color("#334155")
                        border = None.none
                        color = Color("#f1f5f9")
                        padding = Padding(4.px, 12.px)
                        borderRadius = 6.px
                        cursor = Cursor.pointer
                        fontSize = 13.px
                        fontWeight = FontWeight.normal
                        hover {
                            background = Color("#475569")
                        }
                    }
                    +"📁 Load data"
                    input {
                        type = "file".unsafeCast<InputType>()
                        accept = ".cbor,.bin,.hex,.txt,*/*"
                        css {
                            display = None.none
                        }
                        onChange = { event ->
                            val fileList = event.target.asDynamic().files
                            if (fileList != null && fileList.length > 0) {
                                val file = fileList[0].unsafeCast<File>()
                                val reader = FileReader()
                                reader.asDynamic().onload = {
                                    val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                    val bytes = Int8Array(arrayBuffer).toByteArray()
                                    val text = bytes.decodeToString()
                                    if (text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it.isWhitespace() }) {
                                        rawInput = text.trim()
                                    } else {
                                        rawInput = bytes.toHex()
                                    }
                                    outputDiagnostics = ""
                                }
                                reader.readAsArrayBuffer(file)
                            }
                        }
                    }
                }
            }

            textarea {
                css {
                    width = 100.pct
                    height = 140.px
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                    borderRadius = 8.px
                    color = Color("#f1f5f9")
                    fontFamily = FontFamily.monospace
                    padding = 12.px
                    resize = "none".unsafeCast<Resize>()
                    marginBottom = 16.px
                    focus {
                        outline = None.none
                        borderColor = Color("#3b82f6")
                    }
                }
                value = rawInput
                placeholder = "Paste CBOR hex (e.g. A26776657273696F6E63312E30...) or Base64 here"
                onChange = { rawInput = it.target.value }
            }

            // Formatting options
            div {
                css {
                    display = Display.flex
                    flexWrap = FlexWrap.wrap
                    gap = 20.px
                    alignItems = AlignItems.center
                    marginBottom = 24.px
                }

                label {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        cursor = Cursor.pointer
                        color = Color("#cbd5e1")
                        fontWeight = FontWeight.normal
                    }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = prettyPrint
                        onChange = { prettyPrint = it.target.checked }
                    }
                    +"Pretty print"
                }

                label {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        cursor = Cursor.pointer
                        color = Color("#cbd5e1")
                        fontWeight = FontWeight.normal
                    }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = decodeEmbeddedCbor
                        onChange = { decodeEmbeddedCbor = it.target.checked }
                    }
                    +"Tag 24 embedded CBOR (<< ... >>)"
                }

                label {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        cursor = Cursor.pointer
                        color = Color("#cbd5e1")
                        fontWeight = FontWeight.normal
                    }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = useEmbeddedCborOpportunistically
                        onChange = { useEmbeddedCborOpportunistically = it.target.checked }
                    }
                    +"Opportunistic embedded CBOR (<< ... >>)"
                }

                label {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        cursor = Cursor.pointer
                        color = Color("#cbd5e1")
                        fontWeight = FontWeight.normal
                    }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = useEmbeddedCertsOpportunistically
                        onChange = { useEmbeddedCertsOpportunistically = it.target.checked }
                    }
                    +"Opportunistic X.509 certs (cert'''...''')"
                }

                label {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        cursor = Cursor.pointer
                        color = Color("#cbd5e1")
                        fontWeight = FontWeight.normal
                    }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = annotateCoseOpportunistically
                        onChange = { annotateCoseOpportunistically = it.target.checked }
                    }
                    +"Opportunistic COSE (/label/, # alg)"
                }

                label {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        cursor = Cursor.pointer
                        color = Color("#cbd5e1")
                        fontWeight = FontWeight.normal
                    }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = useAppExtensions
                        onChange = { useAppExtensions = it.target.checked }
                    }
                    +"Application extensions (dt'...', ip'...')"
                }

                label {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        cursor = Cursor.pointer
                        color = Color("#cbd5e1")
                        fontWeight = FontWeight.normal
                    }
                    input {
                        type = "checkbox".unsafeCast<InputType>()
                        checked = sortKeys
                        onChange = { sortKeys = it.target.checked }
                    }
                    +"Sort map keys"
                }

                div {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                    }
                    label {
                        css {
                            color = Color("#cbd5e1")
                            fontWeight = FontWeight.normal
                        }
                        +"Byte format:"
                    }
                    select {
                        css {
                            background = Color("#0f172a")
                            color = Color("#f1f5f9")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            padding = Padding(4.px, 8.px)
                        }
                        value = bstrFormat.name
                        onChange = { e ->
                            bstrFormat = ByteStringFormat.valueOf(e.target.asDynamic().value as String)
                        }
                        option { value = ByteStringFormat.HEX.name; +"Hex (h'...')" }
                        option { value = ByteStringFormat.BASE64.name; +"Base64 (b64'...')" }
                        option { value = ByteStringFormat.ASCII_SINGLE_QUOTE.name; +"ASCII ('...')" }
                        option { value = ByteStringFormat.LENGTH_ONLY.name; +"Length Summary" }
                    }
                }
            }

            button {
                css {
                    padding = Padding(12.px, 24.px)
                    fontSize = 16.px
                    fontWeight = FontWeight.bold
                    backgroundColor = Color("#3b82f6")
                    color = Color("#ffffff")
                    border = None.none
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                    transition = "all 0.2s".unsafeCast<Transition>()
                    hover {
                        backgroundColor = Color("#2563eb")
                    }
                    disabled {
                        backgroundColor = Color("#475569")
                        cursor = Cursor.notAllowed
                    }
                }
                disabled = rawInput.trim().isEmpty()
                onClick = {
                    processInput(rawInput)
                }
                +"Decode to CDN"
            }

            if (outputDiagnostics.isNotEmpty()) {
                div {
                    css {
                        marginTop = 32.px
                    }
                    div {
                        css {
                            display = Display.flex
                            justifyContent = JustifyContent.spaceBetween
                            alignItems = AlignItems.center
                            marginBottom = 8.px
                        }
                        label {
                            css {
                                fontWeight = FontWeight.normal
                                color = Color("#cbd5e1")
                            }
                            +"CDN Output:"
                        }
                        button {
                            css {
                                background = Color("#334155")
                                border = None.none
                                color = Color("#f1f5f9")
                                padding = Padding(6.px, 12.px)
                                borderRadius = 6.px
                                cursor = Cursor.pointer
                                fontSize = 13.px
                                fontWeight = FontWeight.normal
                                hover {
                                    background = Color("#475569")
                                }
                            }
                            onClick = {
                                window.navigator.asDynamic().clipboard.writeText(outputDiagnostics)
                                copyStatus = "Copied!"
                                window.setTimeout({ copyStatus = "" }, 2000)
                            }
                            if (copyStatus.isNotEmpty()) +copyStatus else +"Copy to Clipboard"
                        }
                    }
                    if (outputDiagnostics.startsWith("Error")) {
                        pre {
                            css {
                                background = Color("#020617")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                padding = 16.px
                                overflow = "auto".unsafeCast<Overflow>()
                                maxHeight = 500.px
                            }
                            code {
                                css {
                                    fontFamily = FontFamily.monospace
                                    color = Color("#ef4444")
                                    fontSize = 14.px
                                }
                                +outputDiagnostics
                            }
                        }
                    } else {
                        CborDiagnosticViewer {
                            diagText = outputDiagnostics
                            maxHeight = 500.px
                        }
                    }
                }
            }
        } else {
            // Encode Mode (CDN -> CBOR Bytes)
            div {
                css {
                    display = Display.flex
                    justifyContent = JustifyContent.spaceBetween
                    alignItems = AlignItems.center
                    marginBottom = 8.px
                }

                label {
                    css {
                        fontWeight = FontWeight.normal
                        color = Color("#cbd5e1")
                    }
                    +"CDN Text Literal (e.g. [1, 2, \"hello\", dt'2026-07-27T16:00:00Z', << {\"ver\": \"1.0\"} >>]):"
                }

                label {
                    css {
                        background = Color("#334155")
                        border = None.none
                        color = Color("#f1f5f9")
                        padding = Padding(4.px, 12.px)
                        borderRadius = 6.px
                        cursor = Cursor.pointer
                        fontSize = 13.px
                        fontWeight = FontWeight.normal
                        hover {
                            background = Color("#475569")
                        }
                    }
                    +"📁 Load data"
                    input {
                        type = "file".unsafeCast<InputType>()
                        accept = ".cdn,.cbor-diag,.txt,*/*"
                        css {
                            display = None.none
                        }
                        onChange = { event ->
                            val fileList = event.target.asDynamic().files
                            if (fileList != null && fileList.length > 0) {
                                val file = fileList[0].unsafeCast<File>()
                                val reader = FileReader()
                                reader.asDynamic().onload = {
                                    val text = reader.result.toString()
                                    rawInput = text
                                    outputHex = ""
                                    outputB64Url = ""
                                    outputDiagnostics = ""
                                }
                                reader.readAsText(file)
                            }
                        }
                    }
                }
            }

            textarea {
                css {
                    width = 100.pct
                    height = 140.px
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                    borderRadius = 8.px
                    color = Color("#f1f5f9")
                    fontFamily = FontFamily.monospace
                    padding = 12.px
                    resize = "none".unsafeCast<Resize>()
                    marginBottom = 16.px
                    focus {
                        outline = None.none
                        borderColor = Color("#3b82f6")
                    }
                }
                value = rawInput
                placeholder = "Paste Concise Diagnostic Notation (CDN) text here..."
                onChange = { rawInput = it.target.value }
            }

            button {
                css {
                    padding = Padding(12.px, 24.px)
                    fontSize = 16.px
                    fontWeight = FontWeight.bold
                    backgroundColor = Color("#3b82f6")
                    color = Color("#ffffff")
                    border = None.none
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                    transition = "all 0.2s".unsafeCast<Transition>()
                    hover {
                        backgroundColor = Color("#2563eb")
                    }
                    disabled {
                        backgroundColor = Color("#475569")
                        cursor = Cursor.notAllowed
                    }
                }
                disabled = rawInput.trim().isEmpty()
                onClick = {
                    processInput(rawInput)
                }
                +"Encode to CBOR"
            }

            if (outputDiagnostics.isNotEmpty()) {
                pre {
                    css {
                        marginTop = 24.px
                        background = Color("#020617")
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        borderRadius = 8.px
                        padding = 16.px
                    }
                    code {
                        css {
                            fontFamily = FontFamily.monospace
                            color = Color("#ef4444")
                            fontSize = 14.px
                        }
                        +outputDiagnostics
                    }
                }
            }

            if (outputHex.isNotEmpty()) {
                div {
                    css {
                        marginTop = 24.px
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 16.px
                    }

                    div {
                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                                marginBottom = 6.px
                            }
                            label {
                                css { color = Color("#cbd5e1"); fontWeight = FontWeight.normal }
                                +"CBOR Hex:"
                            }
                            button {
                                css {
                                    background = Color("#334155")
                                    border = None.none
                                    color = Color("#f1f5f9")
                                    padding = Padding(4.px, 10.px)
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    fontSize = 12.px
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(outputHex)
                                    copyHexStatus = "Copied!"
                                    window.setTimeout({ copyHexStatus = "" }, 2000)
                                }
                                if (copyHexStatus.isNotEmpty()) +copyHexStatus else +"Copy Hex"
                            }
                        }
                        textarea {
                            css {
                                width = 100.pct
                                height = 80.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 8.px
                                color = Color("#38bdf8")
                                fontFamily = FontFamily.monospace
                                padding = 8.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            readOnly = true
                            value = outputHex
                        }
                    }

                    div {
                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                                marginBottom = 6.px
                            }
                            label {
                                css { color = Color("#cbd5e1"); fontWeight = FontWeight.normal }
                                +"CBOR Base64Url:"
                            }
                            button {
                                css {
                                    background = Color("#334155")
                                    border = None.none
                                    color = Color("#f1f5f9")
                                    padding = Padding(4.px, 10.px)
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    fontSize = 12.px
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(outputB64Url)
                                    copyB64Status = "Copied!"
                                    window.setTimeout({ copyB64Status = "" }, 2000)
                                }
                                if (copyB64Status.isNotEmpty()) +copyB64Status else +"Copy Base64Url"
                            }
                        }
                        textarea {
                            css {
                                width = 100.pct
                                height = 80.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#475569"))
                                borderRadius = 8.px
                                color = Color("#38bdf8")
                                fontFamily = FontFamily.monospace
                                padding = 8.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            readOnly = true
                            value = outputB64Url
                        }
                    }
                }
            }
        }
    }
}
