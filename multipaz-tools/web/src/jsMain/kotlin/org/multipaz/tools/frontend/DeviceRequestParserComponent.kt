@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.js.ExperimentalWasmJsInterop::class
)
package org.multipaz.tools.frontend

import emotion.react.css
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.buildCborArray
import org.multipaz.cose.Cose
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.util.toHex
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useState
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.InputType

private fun createSampleDeviceRequestHex(): String {
    return try {
        val devReq = buildDeviceRequest(
            sessionTranscript = buildCborArray {}
        ) {
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf(
                    "org.iso.18013.5.1" to mapOf(
                        "given_name" to false,
                        "family_name" to false,
                        "issue_date" to false,
                        "expiry_date" to false,
                        "document_number" to false,
                        "portrait" to false,
                        "driving_privileges" to false,
                        "age_over_18" to true,
                        "age_over_21" to true
                    )
                )
            )
        }
        Cbor.encode(devReq.toDataItem()).toHex()
    } catch (e: Throwable) {
        ""
    }
}

private class ParsedRequestResult(
    val deviceRequest: DeviceRequest,
    val rawDataItem: DataItem,
    val hexString: String
)

external interface DevReqCertCardProps : Props {
    var index: Int
    var cert: X509Cert
}

private val DevReqCertCard: FC<DevReqCertCardProps> = FC { props ->
    var copyPemStatus by useState("")
    val cert = props.cert

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 16.px
            marginBottom = 12.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 12.px
            }

            span {
                css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 14.px }
                +"Certificate #${props.index + 1}"
            }

            button {
                css {
                    background = Color("#3b82f6")
                    color = Color("#ffffff")
                    border = None.none
                    padding = Padding(6.px, 14.px)
                    borderRadius = 6.px
                    cursor = Cursor.pointer
                    fontSize = 13.px
                    fontWeight = FontWeight.bold
                    hover { background = Color("#2563eb") }
                }
                onClick = {
                    window.navigator.asDynamic().clipboard.writeText(cert.toPem())
                    copyPemStatus = "Copied PEM!"
                    window.setTimeout({ copyPemStatus = "" }, 2000)
                }
                +if (copyPemStatus.isNotEmpty()) copyPemStatus else "📋 Copy PEM"
            }
        }

        div {
            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px; fontSize = 13.px }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Subject: " }
                span { css { color = Color("#f1f5f9"); wordBreak = WordBreak.breakAll }; +cert.subject.name }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Issuer: " }
                span { css { color = Color("#f1f5f9"); wordBreak = WordBreak.breakAll }; +cert.issuer.name }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Serial Number: " }
                span { css { color = Color("#38bdf8"); fontFamily = FontFamily.monospace }; +cert.serialNumber.value.toHex() }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Validity: " }
                span { css { color = Color("#cbd5e1") }; +"${cert.validityNotBefore} to ${cert.validityNotAfter}" }
            }
        }
    }
}

external interface ItemsRequestViewerBlockProps : Props {
    var itemsReqItem: DataItem
}

private val ItemsRequestViewerBlock: FC<ItemsRequestViewerBlockProps> = FC { props ->
    var copyStatus by useState("")
    val itemsReqHex = Cbor.encode(props.itemsReqItem).toHex()
    val itemsReqDiag = Cbor.toDiagnostics(props.itemsReqItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 16.px
            marginTop = 12.px
            marginBottom = 12.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 8.px
            }

            span {
                css { color = Color("#cbd5e1"); fontWeight = FontWeight.bold; fontSize = 13.px }
                +"ItemsRequest CBOR"
            }

            button {
                css {
                    background = Color("#3b82f6")
                    color = Color("#ffffff")
                    border = None.none
                    padding = Padding(4.px, 12.px)
                    borderRadius = 6.px
                    cursor = Cursor.pointer
                    fontSize = 12.px
                    fontWeight = FontWeight.bold
                    hover { background = Color("#2563eb") }
                }
                onClick = {
                    window.navigator.asDynamic().clipboard.writeText(itemsReqHex)
                    copyStatus = "Copied Hex!"
                    window.setTimeout({ copyStatus = "" }, 2000)
                }
                +if (copyStatus.isNotEmpty()) copyStatus else "📋 Copy ItemsRequest Hex"
            }
        }

        CborDiagnosticViewer {
            diagText = itemsReqDiag
            maxHeight = 200.px
        }
    }
}

val DeviceRequestParserComponent: FC<Props> = FC {
    var rawInput by useState("")
    var parsedResult by useState<ParsedRequestResult?>(null)
    var parseError by useState("")
    var fileName by useState("")

    var copyHexStatus by useState("")
    var copyDiagStatus by useState("")

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
            +"ISO mdoc DeviceRequest Parser"
        }

        if (parsedResult != null || parseError.isNotEmpty()) {
            div {
                css {
                    display = Display.flex
                    gap = 12.px
                    marginBottom = 24.px
                }

                button {
                    css {
                        padding = Padding(10.px, 20.px)
                        fontSize = 14.px
                        fontWeight = FontWeight.bold
                        backgroundColor = Color("#334155")
                        color = Color("#f1f5f9")
                        border = None.none
                        borderRadius = 8.px
                        cursor = Cursor.pointer
                        hover {
                            backgroundColor = Color("#475569")
                        }
                    }
                    onClick = {
                        parsedResult = null
                        parseError = ""
                        fileName = ""
                    }
                    +"← Clear and Decode Another Request"
                }

                if (parsedResult != null) {
                    val result = parsedResult!!
                    button {
                        css {
                            padding = Padding(10.px, 20.px)
                            fontSize = 14.px
                            fontWeight = FontWeight.bold
                            backgroundColor = Color("#3b82f6")
                            color = Color("#ffffff")
                            border = None.none
                            borderRadius = 8.px
                            cursor = Cursor.pointer
                            hover {
                                backgroundColor = Color("#2563eb")
                            }
                        }
                        onClick = {
                            window.navigator.asDynamic().clipboard.writeText(result.hexString)
                            copyHexStatus = "Copied Hex!"
                            window.setTimeout({ copyHexStatus = "" }, 2000)
                        }
                        +if (copyHexStatus.isNotEmpty()) copyHexStatus else "📋 Copy DeviceRequest Hex"
                    }

                    button {
                        css {
                            padding = Padding(10.px, 20.px)
                            fontSize = 14.px
                            fontWeight = FontWeight.bold
                            backgroundColor = Color("#334155")
                            color = Color("#f1f5f9")
                            border = None.none
                            borderRadius = 8.px
                            cursor = Cursor.pointer
                            hover {
                                backgroundColor = Color("#475569")
                            }
                        }
                        onClick = {
                            val diag = Cbor.toDiagnostics(result.rawDataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                            window.navigator.asDynamic().clipboard.writeText(diag)
                            copyDiagStatus = "Copied Diagnostics!"
                            window.setTimeout({ copyDiagStatus = "" }, 2000)
                        }
                        +if (copyDiagStatus.isNotEmpty()) copyDiagStatus else "Copy CBOR Diagnostics"
                    }
                }
            }

            if (parseError.isNotEmpty()) {
                div {
                    css {
                        marginTop = 24.px
                        padding = 16.px
                        background = Color("#451a1a")
                        border = Border(1.px, LineStyle.solid, Color("#f87171"))
                        borderRadius = 8.px
                        color = Color("#fca5a5")
                        fontWeight = FontWeight.bold
                    }
                    +parseError
                }
            }
        } else {
            p {
                css {
                    color = Color("#94a3b8")
                    marginBottom = 24.px
                }
                +"Parse ISO/IEC 18013-5 `DeviceRequest` CBOR payloads. Inspect requested document types, namespaces, data elements, intent-to-retain, and reader authentication certificates."
            }

            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +"Upload binary/hex file or paste Hex / Base64 data:"
            }

            div {
                css {
                    display = Display.flex
                    gap = 16.px
                    alignItems = AlignItems.center
                    marginBottom = 16.px
                }

                button {
                    css {
                        padding = Padding(10.px, 20.px)
                        background = Color("#1e3a8a")
                        color = Color("#93c5fd")
                        border = Border(1.px, LineStyle.solid, Color("#3b82f6"))
                        borderRadius = 8.px
                        cursor = Cursor.pointer
                        fontWeight = FontWeight.bold
                        fontSize = 14.px
                        hover {
                            background = Color("#1d4ed8")
                            color = Color("#ffffff")
                        }
                    }
                    onClick = {
                        rawInput = createSampleDeviceRequestHex()
                        fileName = "Sample mDL DeviceRequest"
                    }
                    +"🧪 Load Sample DeviceRequest"
                }

                label {
                    css {
                        display = Display.inlineFlex
                        alignItems = AlignItems.center
                        gap = 8.px
                        padding = Padding(10.px, 20.px)
                        background = Color("#334155")
                        color = Color("#f1f5f9")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 8.px
                        cursor = Cursor.pointer
                        fontWeight = FontWeight.bold
                        fontSize = 14.px
                        hover {
                            background = Color("#475569")
                        }
                    }
                    +"📁 Choose File"
                    input {
                        type = "file".unsafeCast<InputType>()
                        accept = ".cbor,.bin,.hex,*/*"
                        css {
                            display = None.none
                        }
                        onChange = { event ->
                            val fileList = event.target.asDynamic().files
                            if (fileList != null && fileList.length > 0) {
                                val file = fileList[0].unsafeCast<File>()
                                val name = file.name
                                val reader = FileReader()
                                reader.asDynamic().onload = {
                                    val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                    val bytes = Int8Array(arrayBuffer).toByteArray()
                                    try {
                                        val dataItem = Cbor.decode(bytes)
                                        val devReq = DeviceRequest.fromDataItem(dataItem)
                                        parsedResult = ParsedRequestResult(devReq, dataItem, bytes.toHex())
                                        parseError = ""
                                        fileName = name
                                    } catch (e: Throwable) {
                                        val text = bytes.decodeToString()
                                        try {
                                            val decodedBytes = decodeInputToBytes(text)
                                            val dataItem = Cbor.decode(decodedBytes)
                                            val devReq = DeviceRequest.fromDataItem(dataItem)
                                            parsedResult = ParsedRequestResult(devReq, dataItem, decodedBytes.toHex())
                                            parseError = ""
                                            fileName = name
                                        } catch (err: Throwable) {
                                            parseError = "Error parsing DeviceRequest: " + (err.message ?: "Invalid CBOR payload")
                                            parsedResult = null
                                        }
                                    }
                                }
                                reader.readAsArrayBuffer(file)
                            }
                        }
                    }
                }

                if (fileName.isNotEmpty()) {
                    span {
                        css {
                            color = Color("#38bdf8")
                            fontSize = 14.px
                            fontWeight = FontWeight.bold
                        }
                        +"Loaded: $fileName"
                    }
                }
            }

            textarea {
                css {
                    width = 100.pct
                    height = 150.px
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
                placeholder = "Paste DeviceRequest Hex (e.g. A26776657273696F6E63312E30...) or Base64 string here..."
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
                    hover {
                        backgroundColor = Color("#2563eb")
                    }
                }
                disabled = rawInput.trim().isEmpty()
                onClick = {
                    mainScope.launch {
                        try {
                            val bytes = decodeInputToBytes(rawInput)
                            val dataItem = Cbor.decode(bytes)
                            val devReq = DeviceRequest.fromDataItem(dataItem)
                            parsedResult = ParsedRequestResult(devReq, dataItem, bytes.toHex())
                            parseError = ""
                            if (fileName.isEmpty()) fileName = "Pasted Input"
                        } catch (e: Throwable) {
                            parseError = "Error parsing DeviceRequest: " + (e.message ?: "Invalid structure")
                            parsedResult = null
                        }
                    }
                }
                +"Decode DeviceRequest"
            }
        }

        val res = parsedResult
        if (res != null) {
            val devReq = res.deviceRequest
            val rawDataItem = res.rawDataItem

            div {
                css { marginTop = 32.px }

                // Overview Header Card
                div {
                    css {
                        background = Color("#0f172a")
                        borderRadius = 12.px
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        padding = 24.px
                        marginBottom = 24.px
                    }

                    div {
                        css {
                            display = Display.flex
                            justifyContent = JustifyContent.spaceBetween
                            alignItems = AlignItems.center
                            marginBottom = 16.px
                        }

                        span {
                            css {
                                background = Color("#2563eb")
                                color = Color("#ffffff")
                                padding = Padding(4.px, 12.px)
                                borderRadius = 16.px
                                fontSize = 12.px
                                fontWeight = FontWeight.bold
                                textTransform = TextTransform.uppercase
                            }
                            +"ISO 18013-5 DeviceRequest"
                        }

                        span {
                            css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 14.px }
                            +"Version ${devReq.version}"
                        }
                    }

                    div {
                        css { display = Display.flex; gap = 32.px }

                        div {
                            span { css { color = Color("#64748b"); fontSize = 12.px; display = Display.block; fontWeight = FontWeight.bold }; +"DOCUMENT REQUESTS" }
                            span { css { color = Color("#f1f5f9"); fontSize = 16.px; fontWeight = FontWeight.bold }; +"${devReq.docRequests.size} request(s)" }
                        }

                        div {
                            span { css { color = Color("#64748b"); fontSize = 12.px; display = Display.block; fontWeight = FontWeight.bold }; +"DEVICE REQUEST INFO" }
                            span {
                                css { color = if (devReq.deviceRequestInfo != null) Color("#4ade80") else Color("#94a3b8"); fontSize = 16.px; fontWeight = FontWeight.bold }
                                +if (devReq.deviceRequestInfo != null) "Present" else "None"
                            }
                        }

                        div {
                            span { css { color = Color("#64748b"); fontSize = 12.px; display = Display.block; fontWeight = FontWeight.bold }; +"READER AUTH ALL" }
                            val readerAuthAllPresent = rawDataItem.getOrNull("readerAuthAll") != null
                            span {
                                css { color = if (readerAuthAllPresent) Color("#c084fc") else Color("#94a3b8"); fontSize = 16.px; fontWeight = FontWeight.bold }
                                +if (readerAuthAllPresent) "Present" else "None"
                            }
                        }
                    }
                }

                // Document Requests Section
                h3 {
                    css { color = Color("#e2e8f0"); fontSize = 1.2.rem; marginBottom = 16.px }
                    +"Requested Documents (${devReq.docRequests.size})"
                }

                val docReqDataItems = try {
                    rawDataItem["docRequests"].asArray
                } catch (e: Throwable) {
                    emptyList()
                }

                devReq.docRequests.forEachIndexed { index, docReq ->
                    val docReqDataItem = docReqDataItems.getOrNull(index)

                    div {
                        css {
                            background = Color("#0f172a")
                            borderRadius = 12.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            padding = 24.px
                            marginBottom = 24.px
                        }

                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                                marginBottom = 16.px
                            }

                            span {
                                css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 1.1.rem }
                                +"Document Request #${index + 1}"
                            }

                            span {
                                css {
                                    background = Color("#334155")
                                    color = Color("#f1f5f9")
                                    padding = Padding(4.px, 12.px)
                                    borderRadius = 6.px
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    fontFamily = FontFamily.monospace
                                }
                                +docReq.docType
                            }
                        }

                        // Requested Data Elements Table
                        if (docReq.nameSpaces.isNotEmpty()) {
                            table {
                                css { width = 100.pct; borderCollapse = BorderCollapse.collapse; marginBottom = 16.px }
                                thead {
                                    tr {
                                        th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Namespace" }
                                        th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Data Element" }
                                        th { css { color = Color("#94a3b8"); textAlign = TextAlign.center; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Intent to Retain" }
                                    }
                                }
                                tbody {
                                    docReq.nameSpaces.forEach { (nsName, elementsMap) ->
                                        elementsMap.forEach { (elementName, intentToRetain) ->
                                            tr {
                                                td { css { color = Color("#cbd5e1"); padding = 8.px; fontSize = 13.px; fontFamily = FontFamily.monospace }; +nsName }
                                                td { css { color = Color("#f1f5f9"); padding = 8.px; fontSize = 13.px; fontWeight = FontWeight.bold }; +elementName }
                                                td {
                                                    css { textAlign = TextAlign.center; padding = 8.px; fontSize = 13.px }
                                                    span {
                                                        css {
                                                            color = if (intentToRetain) Color("#f87171") else Color("#4ade80")
                                                            fontWeight = FontWeight.bold
                                                        }
                                                        +if (intentToRetain) "Yes" else "No"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ItemsRequest CBOR Diagnostic Preview
                        val itemsReqItem = try {
                            docReqDataItem?.getOrNull("itemsRequest")?.asTaggedEncodedCbor
                        } catch (e: Throwable) {
                            try { docReqDataItem?.getOrNull("itemsRequest") } catch (e2: Throwable) { null }
                        }

                        if (itemsReqItem != null) {
                            ItemsRequestViewerBlock {
                                this.itemsReqItem = itemsReqItem
                            }
                        }

                        // Reader Authentication Signature & Cert Chain
                        val readerAuthItem = docReqDataItem?.getOrNull("readerAuth")
                        if (readerAuthItem != null) {
                            val readerAuthCose = try {
                                readerAuthItem.asCoseSign1
                            } catch (e: Throwable) {
                                null
                            }

                            if (readerAuthCose != null) {
                                val certChainItem = readerAuthCose.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                                    ?: readerAuthCose.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                                val certChain = try { certChainItem?.asX509CertChain } catch (e: Throwable) { null }

                                div {
                                    css {
                                        background = Color("#1e293b")
                                        borderRadius = 8.px
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        padding = 16.px
                                        marginTop = 12.px
                                    }

                                    h3 {
                                        css { color = Color("#c084fc"); fontSize = 1.0.rem; marginTop = 0.px; marginBottom = 12.px }
                                        +"Reader Authentication Signature"
                                    }

                                    if (certChain != null) {
                                        certChain.certificates.forEachIndexed { certIndex, cert ->
                                            DevReqCertCard {
                                                this.index = certIndex
                                                this.cert = cert
                                            }
                                        }
                                    } else {
                                        div {
                                            css { color = Color("#94a3b8"); fontSize = 13.px }
                                            +"COSE_Sign1 signature present (no X.509 certificate chain found in headers)."
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // DeviceRequestInfo Section (if present)
                if (devReq.deviceRequestInfo != null) {
                    val info = devReq.deviceRequestInfo!!
                    div {
                        css {
                            background = Color("#0f172a")
                            borderRadius = 12.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            padding = 24.px
                            marginBottom = 24.px
                        }

                        h3 {
                            css { color = Color("#4ade80"); fontSize = 1.1.rem; marginTop = 0.px; marginBottom = 12.px }
                            +"DeviceRequestInfo"
                        }

                        CborDiagnosticViewer {
                            diagText = Cbor.toDiagnostics(info.dataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                            maxHeight = 250.px
                        }
                    }
                }

                // Full CBOR Diagnostic Viewer
                div {
                    css { marginTop = 24.px }

                    label {
                        css { display = Display.block; fontWeight = FontWeight.bold; color = Color("#cbd5e1"); marginBottom = 8.px }
                        +"Full DeviceRequest CBOR Diagnostic Notation:"
                    }

                    CborDiagnosticViewer {
                        diagText = Cbor.toDiagnostics(rawDataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                        maxHeight = 400.px
                    }
                }
            }
        }
    }
}
