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
import react.useState
import web.cssom.*
import web.html.InputType
import kotlinx.browser.window
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption

val CborDecoderComponent = FC {
    var rawInput by useState("")
    var outputDiagnostics by useState("")
    var prettyPrint by useState(true)
    var decodeEmbeddedCbor by useState(true)
    var copyStatus by useState("")

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
            +"CBOR Decoder"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Decode raw CBOR bytes (represented as Hex or Base64 / Base64Url) to standard CBOR Diagnostic notation."
        }

        label {
            css {
                display = Display.block
                fontWeight = FontWeight.normal
                marginBottom = 8.px
                color = Color("#cbd5e1")
            }
            +"CBOR Raw Data (Hex, Base64 or Base64Url):"
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
            placeholder = "Paste CBOR hex (e.g. A26776657273696F6E63312E30...) or Base64 here"
            onChange = { rawInput = it.target.value }
        }

        div {
            css {
                display = Display.flex
                gap = 24.px
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
                +"Decode embedded CBOR"
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
                try {
                    val bytes = decodeInputToBytes(rawInput)
                    if (bytes.isEmpty()) {
                        outputDiagnostics = "Input is empty"
                    } else {
                        val options = mutableSetOf<DiagnosticOption>()
                        if (prettyPrint) options.add(DiagnosticOption.PRETTY_PRINT)
                        if (decodeEmbeddedCbor) options.add(DiagnosticOption.EMBEDDED_CBOR)
                        
                        outputDiagnostics = Cbor.toDiagnostics(bytes, options)
                    }
                } catch (e: Exception) {
                    outputDiagnostics = "Error decoding: " + (e.message ?: "Unknown decoding error")
                }
            }
            +"Decode CBOR"
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
                        +"Diagnostic Output:"
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
    }
}
