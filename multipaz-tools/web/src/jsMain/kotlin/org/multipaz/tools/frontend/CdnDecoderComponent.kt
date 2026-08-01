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
import react.useEffectOnce
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.InputType
import kotlinx.browser.window
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.Cbor
import org.multipaz.util.toHex
import org.multipaz.util.toBase64Url

val CdnDecoderComponent = FC {
    var rawInput by useState("")
    var outputHex by useState("")
    var outputB64Url by useState("")
    var parseError by useState("")
    var copyHexStatus by useState("")
    var copyB64Status by useState("")

    fun processInput(inputStr: String) {
        val cleanInput = inputStr.trim()
        if (cleanInput.isEmpty()) {
            outputHex = ""
            outputB64Url = ""
            parseError = ""
            updateUrlHashPayload("")
            return
        }

        try {
            val item = Cdn.parse(cleanInput)
            val encodedBytes = Cbor.encode(item)
            outputHex = encodedBytes.toHex()
            outputB64Url = encodedBytes.toBase64Url()
            parseError = ""
            updateUrlHashPayload(cleanInput)
        } catch (e: Exception) {
            parseError = e.message ?: "Unknown CDN syntax error"
            outputHex = ""
            outputB64Url = ""
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
            +"CDN Decoder"
        }

        val hasResult = outputHex.isNotEmpty() || parseError.isNotEmpty()

        if (hasResult) {
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
                    outputHex = ""
                    outputB64Url = ""
                    parseError = ""
                    updateUrlHashPayload("")
                }
                +"← Back to Input"
            }

            if (parseError.isNotEmpty()) {
                pre {
                    css {
                        color = Color("#ef4444")
                        background = Color("#0f172a")
                        padding = 16.px
                        borderRadius = 8.px
                        border = Border(1.px, LineStyle.solid, Color("#991b1b"))
                        whiteSpace = WhiteSpace.preWrap
                    }
                    +parseError
                }
            } else {
                val byteCount = outputHex.length / 2
                div {
                    css {
                        fontSize = 13.px
                        color = Color("#94a3b8")
                        marginBottom = 16.px
                    }
                    val formattedSize = formatNumberWithCommas(byteCount)
                    val unit = if (byteCount == 1) "byte" else "bytes"
                    +"Encoded CBOR size: $formattedSize $unit"
                }

                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    div {
                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                                marginBottom = 8.px
                            }
                            label {
                                css {
                                    fontWeight = FontWeight.bold
                                    color = Color("#cbd5e1")
                                }
                                +"CBOR Hex:"
                            }
                            button {
                                css {
                                    padding = Padding(6.px, 12.px)
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color("#3b82f6")
                                    color = Color("#ffffff")
                                    border = None.none
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    hover {
                                        backgroundColor = Color("#2563eb")
                                    }
                                }
                                onClick = {
                                    window.navigator.clipboard.writeText(outputHex)
                                    copyHexStatus = "Copied!"
                                    window.setTimeout({ copyHexStatus = "" }, 2000)
                                }
                                +(copyHexStatus.ifEmpty { "📋 Copy Hex" })
                            }
                        }
                        textarea {
                            css {
                                width = 100.pct
                                height = 120.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                color = Color("#38bdf8")
                                fontFamily = FontFamily.monospace
                                padding = 12.px
                                resize = "none".unsafeCast<Resize>()
                                focus { outline = None.none }
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
                                marginBottom = 8.px
                            }
                            label {
                                css {
                                    fontWeight = FontWeight.bold
                                    color = Color("#cbd5e1")
                                }
                                +"CBOR Base64Url:"
                            }
                            button {
                                css {
                                    padding = Padding(6.px, 12.px)
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color("#3b82f6")
                                    color = Color("#ffffff")
                                    border = None.none
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    hover {
                                        backgroundColor = Color("#2563eb")
                                    }
                                }
                                onClick = {
                                    window.navigator.clipboard.writeText(outputB64Url)
                                    copyB64Status = "Copied!"
                                    window.setTimeout({ copyB64Status = "" }, 2000)
                                }
                                +(copyB64Status.ifEmpty { "📋 Copy Base64Url" })
                            }
                        }
                        textarea {
                            css {
                                width = 100.pct
                                height = 100.px
                                background = Color("#0f172a")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                color = Color("#38bdf8")
                                fontFamily = FontFamily.monospace
                                padding = 12.px
                                resize = "none".unsafeCast<Resize>()
                                focus { outline = None.none }
                            }
                            readOnly = true
                            value = outputB64Url
                        }
                    }
                }
            }
        } else {
            p {
                css {
                    color = Color("#94a3b8")
                    marginBottom = 24.px
                }
                +"Paste Concise Diagnostic Notation (CDN) text literal (e.g. [1, 2, \"hello\", dt'2026-07-27T16:00:00Z', << {\"ver\": \"1.0\"} >>]) to compile into CBOR bytes."
            }

            div {
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
                        +"CDN Text Literal:"
                    }

                    div {
                        css {
                            display = Display.flex
                            gap = 8.px
                            alignItems = AlignItems.center
                        }

                        if (rawInput.isNotEmpty()) {
                            button {
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
                                onClick = {
                                    rawInput = ""
                                    outputHex = ""
                                    outputB64Url = ""
                                    parseError = ""
                                    updateUrlHashPayload("")
                                }
                                +"🗑️ Clear"
                            }
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
                                        }
                                        reader.readAsText(file)
                                    }
                                }
                            }
                        }
                    }
                }

                textarea {
                    css {
                        width = 100.pct
                        height = 160.px
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 8.px
                        color = Color("#f1f5f9")
                        fontFamily = FontFamily.monospace
                        padding = 12.px
                        resize = "none".unsafeCast<Resize>()
                        marginBottom = 4.px
                        focus {
                            outline = None.none
                            borderColor = Color("#3b82f6")
                        }
                    }
                    value = rawInput
                    placeholder = "[1, 2, \"hello\", dt'2026-07-27T16:00:00Z', << {\"ver\": \"1.0\"} >>]"
                    onChange = { rawInput = it.target.value }
                }

                DetectedInputBadge {
                    input = rawInput
                    isCdnOnly = true
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
                    +"Encode to CBOR Bytes"
                }
            }
        }
    }
}
