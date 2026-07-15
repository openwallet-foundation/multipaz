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
import web.html.InputType
import kotlinx.coroutines.launch
import org.multipaz.util.toBase64
import org.multipaz.util.toHex
import org.multipaz.util.fromBase64
import org.multipaz.util.zlibInflate
import org.multipaz.util.deflate
import org.multipaz.util.inflate
import org.multipaz.util.zlibDeflate

val CompressionComponent = FC {
    var rawInput by useState("")
    var outputResult by useState("")
    var parseError by useState("")
    
    var method by useState("zlib") // "zlib" or "deflate"
    var operation by useState("compress") // "compress" or "decompress"
    var inputFormat by useState("hex") // "hex" or "base64" or "utf8"
    var outputFormat by useState("hex") // "hex" or "base64" or "utf8"

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
            +"Compression & Decompression Tool"
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
                +"Compress or decompress binary payloads using DEFLATE (RFC 1951) or zlib (RFC 1950) wrappers. Input can be parsed from hex or base64, and output is generated in your preferred format."
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
                placeholder = "Paste payload here (e.g. Hex representation, Base64, or raw text if compressing)..."
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

                // Left column: Method & Operation
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 16.px
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
                            +"Algorithm / Format:"
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
                            value = method
                            onChange = { method = it.target.value }
                            option { value = "zlib"; +"zlib (RFC 1950)" }
                            option { value = "deflate"; +"DEFLATE (RFC 1951)" }
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
                            +"Operation:"
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
                            value = operation
                            onChange = { operation = it.target.value }
                            option { value = "compress"; +"Compress" }
                            option { value = "decompress"; +"Decompress" }
                        }
                    }
                }

                // Right column: Input & Output format
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 16.px
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
                            +"Input Format:"
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
                            option { value = "hex"; +"Hex / Auto" }
                            option { value = "base64"; +"Base64" }
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
                            +"Output Format:"
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
                            option { value = "utf8"; +"Plain Text (UTF-8)" }
                        }
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
                                else -> {
                                    try {
                                        decodeInputToBytes(cleanInput)
                                    } catch (e: Exception) {
                                        cleanInput.encodeToByteArray()
                                    }
                                }
                            }

                            if (inputBytes.isEmpty()) {
                                parseError = "Input data is empty"
                                outputResult = ""
                                return@launch
                            }

                            val processedBytes = if (operation == "compress") {
                                if (method == "zlib") {
                                    inputBytes.zlibDeflate()
                                } else {
                                    inputBytes.deflate()
                                }
                            } else {
                                if (method == "zlib") {
                                    inputBytes.zlibInflate()
                                } else {
                                    inputBytes.inflate()
                                }
                            }

                            val resultStr = when (outputFormat) {
                                "utf8" -> processedBytes.decodeToString()
                                "base64" -> processedBytes.toBase64()
                                else -> processedBytes.toHex()
                            }

                            outputResult = resultStr
                            parseError = ""
                        } catch (e: Throwable) {
                            parseError = "Processing error: " + (e.message ?: "Unknown error")
                            outputResult = ""
                        }
                    }
                }
                +(if (operation == "compress") "Compress Payload" else "Decompress Payload")
            }
        }
    }
}
