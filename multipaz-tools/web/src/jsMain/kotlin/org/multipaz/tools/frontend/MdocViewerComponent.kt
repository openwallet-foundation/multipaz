@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.img
import react.useState
import web.cssom.*
import kotlinx.coroutines.launch
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.util.toBase64

val MdocViewerComponent = FC {
    var rawInput by useState("")
    var parsedResponse by useState<DeviceResponse?>(null)
    var parseError by useState("")

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
            +"ISO mdoc DeviceResponse Parser"
        }

        if (parsedResponse != null || parseError.isNotEmpty()) {
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
                    marginBottom = 24.px
                    hover {
                        backgroundColor = Color("#475569")
                    }
                }
                onClick = {
                    parsedResponse = null
                    parseError = ""
                }
                +"← Clear and Go Back"
            }

            if (parseError.isNotEmpty()) {
                div {
                    css {
                        marginTop = 24.px
                        color = Color("#ef4444")
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
                +"Inspect a raw DeviceResponse CBOR structure (Hex or Base64 format). Decodes version, status, documents, validity info, namespaces, and elements."
            }

            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +"DeviceResponse (Hex or Base64):"
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
                placeholder = "Paste DeviceResponse hex or base64 data..."
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
                    mainScope.launch {
                        try {
                            val bytes = decodeInputToBytes(rawInput)
                            if (bytes.isEmpty()) {
                                parseError = "Input is empty"
                                parsedResponse = null
                            } else {
                                val dataItem = Cbor.decode(bytes)
                                val response = DeviceResponse.fromDataItem(dataItem)
                                try {
                                    response.verify(Cbor.decode(byteArrayOf(0x80.toByte())))
                                } catch (e: Throwable) {
                                    // Bypassing verification errors (due to lack of sessionTranscript)
                                    // to unlock the documents property.
                                }
                                // Eagerly evaluate lazy properties to catch any exceptions here
                                for (doc in response.documents) {
                                    doc.mso.digestAlgorithm
                                    doc.mso.signedAt
                                    doc.mso.validFrom
                                    doc.mso.validUntil
                                }
                                parsedResponse = response
                                parseError = ""
                            }
                        } catch (e: Throwable) {
                            parseError = "Error parsing DeviceResponse: " + (e.message ?: "Unknown error")
                            parsedResponse = null
                        }
                    }
                }
                +"Parse DeviceResponse"
            }
        }

        parsedResponse?.let { res ->
            div {
                css {
                    marginTop = 32.px
                }

                h3 {
                    css {
                        fontSize = 1.4.rem
                        fontWeight = FontWeight.bold
                        marginBottom = 16.px
                        color = Color("#f1f5f9")
                    }
                    +"Parsed Response Metadata"
                }

                div {
                    css {
                        display = Display.flex
                        gap = 16.px
                        marginBottom = 24.px
                    }
                    div {
                        css {
                            background = Color("#0f172a")
                            padding = Padding(12.px, 20.px)
                            borderRadius = 8.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                        }
                        span {
                            css {
                                color = Color("#64748b")
                                fontSize = 12.px
                                display = Display.block
                                fontWeight = FontWeight.bold
                            }
                            +"VERSION"
                        }
                        span {
                            css {
                                fontSize = 18.px
                                fontWeight = FontWeight.bold
                                color = Color("#38bdf8")
                            }
                            +res.version
                        }
                    }

                    div {
                        css {
                            background = Color("#0f172a")
                            padding = Padding(12.px, 20.px)
                            borderRadius = 8.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                        }
                        span {
                            css {
                                color = Color("#64748b")
                                fontSize = 12.px
                                display = Display.block
                                fontWeight = FontWeight.bold
                            }
                            +"STATUS"
                        }
                        span {
                            css {
                                fontSize = 18.px
                                fontWeight = FontWeight.bold
                                color = if (res.status == 0) Color("#34d399") else Color("#f87171")
                            }
                            +res.status.toString()
                        }
                    }

                    div {
                        css {
                            background = Color("#0f172a")
                            padding = Padding(12.px, 20.px)
                            borderRadius = 8.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                        }
                        span {
                            css {
                                color = Color("#64748b")
                                fontSize = 12.px
                                display = Display.block
                                fontWeight = FontWeight.bold
                            }
                            +"DOCUMENTS COUNT"
                        }
                        span {
                            css {
                                fontSize = 18.px
                                fontWeight = FontWeight.bold
                                color = Color("#a78bfa")
                            }
                            +res.documents.size.toString()
                        }
                    }
                }

                // Render Documents
                res.documents.forEachIndexed { docIdx, doc ->
                    div {
                        css {
                            background = Color("#0f172a")
                            borderRadius = 12.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            padding = 24.px
                            marginBottom = 24.px
                        }

                        h3 {
                            css {
                                fontSize = 1.3.rem
                                color = Color("#38bdf8")
                                margin = Margin(0.px, 0.px, 8.px, 0.px)
                            }
                            +"Document #${docIdx + 1}: ${doc.docType}"
                        }

                        // MSO info
                        div {
                            css {
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                padding = 16.px
                                marginBottom = 20.px
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 6.px
                                fontSize = 14.px
                            }
                            div {
                                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold } }
                                +"MSO Signature Algorithm: "
                                span { css { color = Color("#f1f5f9"); fontWeight = FontWeight.normal } }
                                +doc.mso.digestAlgorithm.hashAlgorithmName
                            }
                            div {
                                +"Signed: "
                                span { css { color = Color("#cbd5e1") } }
                                +doc.mso.signedAt.toString()
                            }
                            div {
                                +"Valid From: "
                                span { css { color = Color("#cbd5e1") } }
                                +doc.mso.validFrom.toString()
                            }
                            div {
                                +"Valid Until: "
                                span { css { color = Color("#cbd5e1") } }
                                +doc.mso.validUntil.toString()
                            }
                        }

                        // Namespaces
                        h4 {
                            css {
                                fontSize = 1.1.rem
                                fontWeight = FontWeight.bold
                                marginBottom = 12.px
                                color = Color("#f1f5f9")
                            }
                            +"Issuer Signed Namespaces"
                        }

                        if (doc.issuerNamespaces.data.isEmpty()) {
                            p {
                                css { color = Color("#64748b"); fontSize = 14.px }
                                +"No issuer signed namespaces returned."
                            }
                        } else {
                            doc.issuerNamespaces.data.forEach { (nsName, nsItems) ->
                                div {
                                    css {
                                        marginBottom = 20.px
                                    }
                                    div {
                                        css {
                                            background = Color("#334155")
                                            padding = Padding(6.px, 12.px)
                                            borderRadius = 6.px
                                            fontSize = 14.px
                                            fontWeight = FontWeight.bold
                                            display = Display.inlineBlock
                                            marginBottom = 8.px
                                        }
                                        +nsName
                                    }

                                    table {
                                        css {
                                            width = 100.pct
                                            borderCollapse = BorderCollapse.collapse
                                            fontSize = 14.px
                                        }
                                        thead {
                                            tr {
                                                th { css { textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Digest ID" }
                                                th { css { textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Element ID" }
                                                th { css { textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Value" }
                                            }
                                        }
                                        tbody {
                                            nsItems.values.sortedBy { it.digestId }.forEach { item ->
                                                tr {
                                                    td { css { padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace }; +item.digestId.toString() }
                                                    td { css { padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontWeight = FontWeight.bold; color = Color("#38bdf8") }; +item.dataElementIdentifier }
                                                    td {
                                                        css { padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; color = Color("#34d399") }
                                                        
                                                        val isImage = if (item.dataElementIdentifier.lowercase() in setOf("portrait", "picture", "photo")) {
                                                            try {
                                                                val parsedItem = item.dataElementValue
                                                                val bytes = parsedItem.asBstr
                                                                if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                                                                    bytes
                                                                } else {
                                                                    null
                                                                }
                                                            } catch (e: Throwable) {
                                                                null
                                                            }
                                                        } else {
                                                            null
                                                        }

                                                        if (isImage != null) {
                                                            img {
                                                                src = "data:image/jpeg;base64,${isImage.toBase64()}"
                                                                css {
                                                                    maxWidth = 150.px
                                                                    maxHeight = 200.px
                                                                    borderRadius = 8.px
                                                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                                                    boxShadow = BoxShadow(0.px, 2.px, 6.px, Color("rgba(0,0,0,0.3)"))
                                                                }
                                                            }
                                                        } else {
                                                            +try {
                                                                Cbor.toDiagnostics(item.dataElementValue, setOf(DiagnosticOption.EMBEDDED_CBOR))
                                                            } catch (e: Throwable) {
                                                                "Error formatting item"
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
