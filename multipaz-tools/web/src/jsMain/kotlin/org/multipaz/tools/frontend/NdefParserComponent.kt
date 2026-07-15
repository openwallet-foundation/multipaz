@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.label
import react.useState
import web.cssom.*
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.decodeToString
import org.multipaz.nfc.NdefMessage
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.nfc.HandoverRequestRecord
import org.multipaz.nfc.HandoverSelectRecord
import org.multipaz.nfc.ServiceParameterRecord
import org.multipaz.nfc.ServiceSelectRecord
import org.multipaz.nfc.TnepStatusRecord
import org.multipaz.util.toHex

val NdefParserComponent = FC {
    var rawInput by useState("")
    var parsedMessage by useState<NdefMessage?>(null)
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
            +"NDEF Message Parser"
        }

        if (parsedMessage != null || parseError.isNotEmpty()) {
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
                    parsedMessage = null
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

            parsedMessage?.let { msg ->
                div {
                    css {
                        marginTop = 24.px
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    // Card 1: Overview
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 12.px
                            padding = 24.px
                            display = Display.grid
                            gridTemplateColumns = "repeat(2, 1fr)".unsafeCast<GridTemplateColumns>()
                            gap = 20.px
                        }

                        div {
                            css {
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 6.px
                            }
                            span {
                                css {
                                    color = Color("#64748b")
                                    fontWeight = FontWeight.bold
                                    fontSize = 12.px
                                    textTransform = TextTransform.uppercase
                                }
                                +"Total Records"
                            }
                            span {
                                css {
                                    color = Color("#38bdf8")
                                    fontSize = 16.px
                                    fontWeight = FontWeight.bold
                                }
                                +"${msg.records.size} record(s)"
                            }
                        }

                        div {
                            css {
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 6.px
                            }
                            span {
                                css {
                                    color = Color("#64748b")
                                    fontWeight = FontWeight.bold
                                    fontSize = 12.px
                                    textTransform = TextTransform.uppercase
                                }
                                +"Encoded Size"
                            }
                            span {
                                css {
                                    color = Color("#f1f5f9")
                                    fontSize = 16.px
                                    fontWeight = FontWeight.bold
                                }
                                try {
                                    +"${msg.encode().size} bytes"
                                } catch (e: Throwable) {
                                    +"Unknown"
                                }
                            }
                        }
                    }

                    // Card 2: Records List
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 12.px
                            padding = 24.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 16.px
                        }

                        h3 {
                            css {
                                margin = 0.px
                                fontSize = 1.3.rem
                                color = Color("#f1f5f9")
                                borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b"))
                                paddingBottom = 12.px
                            }
                            +"NDEF Records"
                        }

                        div {
                            css {
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 16.px
                                marginTop = 8.px
                                
                            }

                            msg.records.forEachIndexed { index, record ->
                                div {
                                    css {
                                        background = Color("#1e293b")
                                        borderRadius = 8.px
                                        padding = 16.px
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    }

                                    div {
                                        css {
                                            fontWeight = FontWeight.bold
                                            color = Color("#f1f5f9")
                                            display = Display.flex
                                            justifyContent = JustifyContent.spaceBetween
                                            alignItems = AlignItems.center
                                            marginBottom = 8.px
                                        }
                                        span { +"Record #${index + 1}" }
                                        span {
                                            css {
                                                fontSize = 11.px
                                                backgroundColor = Color("#0f172a")
                                                color = Color("#94a3b8")
                                                padding = Padding(2.px, 8.px)
                                                borderRadius = 4.px
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                            }
                                            +"TNF: ${record.tnf.name}"
                                        }
                                    }

                                    // Display Type and ID
                                    div {
                                        css {
                                            display = Display.flex
                                            flexDirection = FlexDirection.column
                                            gap = 4.px
                                            marginBottom = 12.px
                                            fontSize = 13.px
                                        }
                                        
                                        val typeStr = try {
                                            record.type.toByteArray().decodeToString()
                                        } catch (e: Throwable) {
                                            ""
                                        }
                                        val typeDisplay = if (typeStr.isNotEmpty()) "\"$typeStr\"" else "Empty"
                                        
                                        div {
                                            span { css { color = Color("#64748b"); marginRight = 6.px }; +"Type:" }
                                            span { css { color = Color("#f1f5f9"); fontFamily = FontFamily.monospace }; +"$typeDisplay (Hex: ${record.type.toByteArray().toHex()})" }
                                        }

                                        if (record.id.size > 0) {
                                            div {
                                                span { css { color = Color("#64748b"); marginRight = 6.px }; +"ID:" }
                                                span { css { color = Color("#f1f5f9"); fontFamily = FontFamily.monospace }; +record.id.toByteArray().toHex() }
                                            }
                                        }

                                        div {
                                            span { css { color = Color("#64748b"); marginRight = 6.px }; +"Payload Size:" }
                                            span { css { color = Color("#cbd5e1") }; +"${record.payload.size} bytes" }
                                        }
                                    }

                                    // Decoded Payload Value
                                    val formattedPayload = formatNdefRecordPayload(record)
                                    div {
                                        css {
                                            fontFamily = FontFamily.monospace
                                            fontSize = 12.px
                                            color = Color("#34d399")
                                            wordBreak = WordBreak.breakAll
                                            whiteSpace = WhiteSpace.preWrap
                                            background = Color("#0f172a")
                                            padding = 12.px
                                            borderRadius = 6.px
                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        }
                                        +formattedPayload
                                    }
                                }
                            }
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
                +"Decode raw NFC NDEF messages. Paste encoded bytes in Hex format or Base64 notation to unpack all records, TNFs, types, and embedded metadata."
            }

            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +"NDEF Encoded Payload (Hex or Base64):"
            }

            textarea {
                css {
                    width = 100.pct
                    height = 200.px
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
                placeholder = "Paste NDEF hex bytes (e.g. D1010A5504676f6f676c652e636f6d) or base64..."
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
                            val cleanInput = rawInput.trim()
                            val bytes = decodeInputToBytes(cleanInput)
                            parsedMessage = NdefMessage.fromEncoded(bytes)
                            parseError = ""
                        } catch (e: Throwable) {
                            parseError = "Error parsing NDEF message: " + (e.message ?: "Unknown error")
                            parsedMessage = null
                        }
                    }
                }
                +"Parse NDEF Message"
            }
        }
    }
}

private fun formatNdefRecordPayload(record: NdefRecord): String {
    // 1. Check for well-known connection handover and TNEP records
    try {
        val hr = HandoverRequestRecord.fromNdefRecord(record)
        if (hr != null) {
            val sb = StringBuilder("Handover Request:\n  Version: ${hr.version}\n  Embedded NDEF Message:\n")
            hr.embeddedMessage.records.forEachIndexed { i, r ->
                sb.append("    Record #${i + 1}:\n")
                sb.append("      TNF: ${r.tnf.name}\n")
                val typeStr = try { r.type.toByteArray().decodeToString() } catch(e: Throwable) { "" }
                sb.append("      Type: \"$typeStr\" (Hex: ${r.type.toByteArray().toHex()})\n")
                sb.append("      Payload size: ${r.payload.size} bytes\n")
                val formatted = formatNdefRecordPayload(r).replace("\n", "\n      ")
                sb.append("      ").append(formatted)
                sb.append("\n")
            }
            return sb.toString().trimEnd()
        }
    } catch (e: Throwable) {}

    try {
        val hs = HandoverSelectRecord.fromNdefRecord(record)
        if (hs != null) {
            val sb = StringBuilder("Handover Select:\n  Version: ${hs.version}\n  Embedded NDEF Message:\n")
            hs.embeddedMessage.records.forEachIndexed { i, r ->
                sb.append("    Record #${i + 1}:\n")
                sb.append("      TNF: ${r.tnf.name}\n")
                val typeStr = try { r.type.toByteArray().decodeToString() } catch(e: Throwable) { "" }
                sb.append("      Type: \"$typeStr\" (Hex: ${r.type.toByteArray().toHex()})\n")
                sb.append("      Payload size: ${r.payload.size} bytes\n")
                val formatted = formatNdefRecordPayload(r).replace("\n", "\n      ")
                sb.append("      ").append(formatted)
                sb.append("\n")
            }
            return sb.toString().trimEnd()
        }
    } catch (e: Throwable) {}

    try {
        val sp = ServiceParameterRecord.fromNdefRecord(record)
        if (sp != null) {
            return """
                Service Parameter (TNEP):
                  TNEP Version: ${sp.tnepVersion}
                  Service Name URI: "${sp.serviceNameUri}"
                  Communication Mode: ${sp.tnepCommunicationMode}
                  wtInt (Waiting Time Code): ${sp.wtInt}
                  nWait: ${sp.nWait}
                  Max NDEF Message Size: ${sp.maxNdefSize} bytes
            """.trimIndent()
        }
    } catch (e: Throwable) {}

    try {
        val ss = ServiceSelectRecord.fromNdefRecord(record)
        if (ss != null) {
            return "Service Select:\n  Service Name: \"${ss.serviceName}\""
        }
    } catch (e: Throwable) {}

    try {
        val ts = TnepStatusRecord.fromNdefRecord(record)
        if (ts != null) {
            return "TNEP Status:\n  Status Code: ${ts.status}"
        }
    } catch (e: Throwable) {}

    // 2. Check for URI
    val uri = record.uri
    if (uri != null) {
        return "URI: $uri"
    }

    // 3. Check for standard well-known Text RTD ("T")
    if (record.tnf == NdefRecord.Tnf.WELL_KNOWN && record.type == Nfc.RTD_TEXT) {
        try {
            val payload = record.payload.toByteArray()
            if (payload.isNotEmpty()) {
                val statusByte = payload[0].toInt()
                val isUtf16 = (statusByte and 0x80) != 0
                val langCodeLen = statusByte and 0x3F
                val langCode = payload.decodeToString(1, 1 + langCodeLen)
                val text = payload.decodeToString(1 + langCodeLen)
                return "Text Record:\n  Language: $langCode\n  Encoding: ${if (isUtf16) "UTF-16" else "UTF-8"}\n  Text: $text"
            }
        } catch (e: Exception) {
            // fallback
        }
    }

    // 4. Default formatting
    val bytes = record.payload.toByteArray()
    if (bytes.isEmpty()) {
        return "<Empty Payload>"
    }

    // Check if it's printable plain text
    val isPrintable = bytes.all { it >= 32 || it.toInt() == 10 || it.toInt() == 13 || it.toInt() == 9 }
    if (isPrintable) {
        try {
            return "UTF-8 Plain Text:\n${bytes.decodeToString()}"
        } catch (e: Exception) {
            // fallback
        }
    }

    // Fall back to hex dump
    return "Payload Hex:\n${bytes.toHex(byteDivider = " ")}"
}
