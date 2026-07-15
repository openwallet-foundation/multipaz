package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.option
import react.useState
import web.cssom.*
import kotlinx.coroutines.launch
import org.multipaz.util.toBase64
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import org.multipaz.util.fromBase64
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex

val ConverterComponent = FC {
    var rawInput by useState("")
    var outputResult by useState("")
    var parseError by useState("")
    
    var inputFormat by useState("hex") // "hex", "base64", "base64url", "utf8"
    var outputFormat by useState("base64") // "hex", "base64", "base64url", "utf8"

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
            +"Format Converter"
        }

        if (outputResult.isNotEmpty() || parseError.isNotEmpty()) {
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
                    outputResult = ""
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

            if (outputResult.isNotEmpty()) {
                div {
                    css {
                        marginTop = 24.px
                    }

                    label {
                        css {
                            display = Display.block
                            fontWeight = FontWeight.bold
                            marginBottom = 8.px
                            color = Color("#cbd5e1")
                        }
                        +"Result (${outputFormat.uppercase()}):"
                    }

                    textarea {
                        css {
                            width = 100.pct
                            height = 200.px
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 8.px
                            color = Color("#34d399")
                            fontFamily = FontFamily.monospace
                            padding = 12.px
                            resize = "none".unsafeCast<Resize>()
                            marginBottom = 16.px
                            focus {
                                outline = None.none
                            }
                        }
                        readOnly = true
                        value = outputResult
                    }
                }
            }
        } else {
            p {
                css {
                    color = Color("#94a3b8")
                    marginBottom = 24.px
                }
                +"Convert binary payloads or plain text between Hex, Base64, Base64URL, and UTF-8 string encodings."
            }

            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +"Input Payload:"
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
                    marginBottom = 24.px
                    focus {
                        outline = None.none
                        borderColor = Color("#3b82f6")
                    }
                }
                value = rawInput
                placeholder = "Paste input data here..."
                onChange = { rawInput = it.target.value }
            }

            // Grid for settings
            div {
                css {
                    display = Display.grid
                    gridTemplateColumns = "repeat(2, 1fr)".unsafeCast<GridTemplateColumns>()
                    gap = 20.px
                    marginBottom = 32.px
                }

                div {
                    label {
                        css {
                            display = Display.block
                            fontWeight = FontWeight.bold
                            marginBottom = 6.px
                            color = Color("#94a3b8")
                            fontSize = 14.px
                        }
                        +"Source Format:"
                    }
                    select {
                        css {
                            width = 100.pct
                            background = Color("#1e293b")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            color = Color("#f1f5f9")
                            padding = 8.px
                            fontSize = 14.px
                        }
                        value = inputFormat
                        onChange = { inputFormat = it.target.value }
                        option { value = "hex"; +"Hex" }
                        option { value = "base64"; +"Base64" }
                        option { value = "base64url"; +"Base64URL" }
                        option { value = "utf8"; +"Plain Text (UTF-8)" }
                    }
                }

                div {
                    label {
                        css {
                            display = Display.block
                            fontWeight = FontWeight.bold
                            marginBottom = 6.px
                            color = Color("#94a3b8")
                            fontSize = 14.px
                        }
                        +"Target Format:"
                    }
                    select {
                        css {
                            width = 100.pct
                            background = Color("#1e293b")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            color = Color("#f1f5f9")
                            padding = 8.px
                            fontSize = 14.px
                        }
                        value = outputFormat
                        onChange = { outputFormat = it.target.value }
                        option { value = "hex"; +"Hex" }
                        option { value = "base64"; +"Base64" }
                        option { value = "base64url"; +"Base64URL" }
                        option { value = "utf8"; +"Plain Text (UTF-8)" }
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
                    mainScope.launch {
                        try {
                            val cleanInput = rawInput.trim()
                            val inputBytes = when (inputFormat) {
                                "utf8" -> cleanInput.encodeToByteArray()
                                "base64" -> cleanInput.fromBase64()
                                "base64url" -> cleanInput.fromBase64Url()
                                else -> {
                                    var hexCand = cleanInput
                                    if (hexCand.startsWith("h'") && hexCand.endsWith("'")) {
                                        hexCand = hexCand.substring(2, hexCand.length - 1)
                                    }
                                    hexCand.fromHex()
                                }
                            }

                            val resultStr = when (outputFormat) {
                                "utf8" -> inputBytes.decodeToString()
                                "base64" -> inputBytes.toBase64()
                                "base64url" -> inputBytes.toBase64Url()
                                else -> inputBytes.toHex()
                            }

                            outputResult = resultStr
                            parseError = ""
                        } catch (e: Throwable) {
                            parseError = "Conversion error: " + (e.message ?: "Unknown error")
                            outputResult = ""
                        }
                    }
                }
                +"Convert"
            }
        }
    }
}
