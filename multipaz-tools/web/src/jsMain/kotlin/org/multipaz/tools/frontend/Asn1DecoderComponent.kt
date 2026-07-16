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
import react.useState
import web.cssom.*
import kotlinx.browser.window
import org.multipaz.asn1.ASN1

val Asn1DecoderComponent = FC {
    var rawInput by useState("")
    var outputText by useState("")
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
            +"ASN.1 Decoder"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Decode raw ASN.1 DER bytes (represented as Hex or Base64 / Base64Url) to a human-readable structure tree."
        }

        label {
            css {
                display = Display.block
                fontWeight = FontWeight.normal
                marginBottom = 8.px
                color = Color("#cbd5e1")
            }
            +"ASN.1 Raw Data (Hex, Base64 or Base64Url):"
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
            placeholder = "Paste ASN.1 DER hex (e.g. 308201a0...) or Base64 here"
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
                try {
                    val bytes = decodeInputToBytes(rawInput)
                    if (bytes.isEmpty()) {
                        outputText = "Input is empty"
                    } else {
                        val objects = ASN1.decodeMultiple(bytes)
                        if (objects.isEmpty()) {
                            outputText = "No ASN.1 objects decoded."
                        } else {
                            outputText = objects.joinToString("\n") { ASN1.print(it) }
                        }
                    }
                } catch (e: Exception) {
                    outputText = "Error decoding: " + (e.message ?: "Unknown decoding error")
                }
            }
            +"Decode ASN.1"
        }

        if (outputText.isNotEmpty()) {
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
                        +"Pretty Printed ASN.1 Structure:"
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
                            window.navigator.asDynamic().clipboard.writeText(outputText)
                            copyStatus = "Copied!"
                            window.setTimeout({ copyStatus = "" }, 2000)
                        }
                        if (copyStatus.isNotEmpty()) +copyStatus else +"Copy to Clipboard"
                    }
                }
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
                            color = if (outputText.startsWith("Error")) Color("#ef4444") else Color("#38bdf8")
                            fontSize = 14.px
                        }
                        +outputText
                    }
                }
            }
        }
    }
}
