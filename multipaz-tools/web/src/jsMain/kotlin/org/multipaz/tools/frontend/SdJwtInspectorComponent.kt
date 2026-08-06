@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.multipaz.tools.frontend

import emotion.react.css
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.fromBase64
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibInflate
import react.FC
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.input
import react.useState
import react.useEffectOnce
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import org.multipaz.util.toHex
import web.file.File
import web.file.FileReader
import web.html.InputType
import web.cssom.*

val SdJwtInspectorComponent = FC {
    var rawInput by useState("")
    var parsedSdJwt by useState<SdJwt?>(null)
    var parsedSdJwtKb by useState<SdJwtKb?>(null)
    var processedPayload by useState<JsonObject?>(null)
    var parseError by useState("")

    suspend fun calculateProcessedPayload(sdjwt: SdJwt): JsonObject? {
        val issuerKey = sdjwt.x5c?.certificates?.firstOrNull()?.ecPublicKey
        return try {
            sdjwt.verify(issuerKey = issuerKey)
        } catch (e: Throwable) {
            try {
                sdjwt.verify(issuerKey = null)
            } catch (e2: Throwable) {
                null
            }
        }
    }

    fun parseInput(inputStr: String) {
        val cleanInput = inputStr.replace(Regex("[\\s\\r\\n\\t]"), "")
        if (cleanInput.isEmpty()) return
        mainScope.launch {
            try {
                val token = if (cleanInput.contains(".") || cleanInput.contains("~")) {
                    cleanInput
                } else {
                    val bytes = decodeInputToBytes(cleanInput)
                    val decompressedBytes = try {
                        bytes.zlibInflate()
                    } catch (e: Throwable) {
                        bytes
                    }
                    decompressedBytes.decodeToString().replace(Regex("[\\s\\r\\n\\t]"), "")
                }

                if (!token.endsWith("~")) {
                    // Attempt parsing as SD-JWT+KB first
                    try {
                        val sdJwtKb = SdJwtKb.fromCompactSerialization(token)
                        parsedSdJwtKb = sdJwtKb
                        parsedSdJwt = sdJwtKb.sdJwt
                        processedPayload = calculateProcessedPayload(sdJwtKb.sdJwt)
                        parseError = ""
                        updateUrlHashPayload(cleanInput)
                    } catch (e1: Throwable) {
                        // Fallback to standard SD-JWT with appended trailing tilde
                        try {
                            val sdjwt = SdJwt.fromCompactSerialization("$token~")
                            parsedSdJwtKb = null
                            parsedSdJwt = sdjwt
                            processedPayload = calculateProcessedPayload(sdjwt)
                            parseError = ""
                            updateUrlHashPayload(cleanInput)
                        } catch (e2: Throwable) {
                            parseError = "Error parsing SD-JWT / SD-JWT+KB: ${e1.message ?: e1.toString()}"
                            parsedSdJwtKb = null
                            parsedSdJwt = null
                            processedPayload = null
                        }
                    }
                } else {
                    // Standard SD-JWT ending in '~'
                    val sdjwt = SdJwt.fromCompactSerialization(token)
                    parsedSdJwtKb = null
                    parsedSdJwt = sdjwt
                    processedPayload = calculateProcessedPayload(sdjwt)
                    parseError = ""
                    updateUrlHashPayload(cleanInput)
                }
            } catch (e: Throwable) {
                parseError = "Error parsing SD-JWT: " + (e.message ?: e.toString())
                parsedSdJwtKb = null
                parsedSdJwt = null
                processedPayload = null
            }
        }
    }

    useEffectOnce {
        val hashPayload = getUrlHashPayload()
        if (hashPayload.isNotEmpty()) {
            rawInput = hashPayload
            parseInput(hashPayload)
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
            +"SD-JWT, SD-JWT+KB & JWT Token Parser"
        }

        if (parsedSdJwt != null || parseError.isNotEmpty()) {
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
                    parsedSdJwt = null
                    parsedSdJwtKb = null
                    parseError = ""
                    updateUrlHashPayload("")
                }
                +"← Back to Input"
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
                +"Decode and inspect SD-JWT, SD-JWT+KB, and standard JWT tokens. Parses Issuer-Signed JWT header/body, individual claim Disclosures, and Key Binding JWTs (KB-JWT)."
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
                        fontWeight = FontWeight.bold
                        color = Color("#cbd5e1")
                    }
                    +"SD-JWT / SD-JWT+KB / JWT Token:"
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
                                parsedSdJwt = null
                                parsedSdJwtKb = null
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
                                        val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                        val bytes = Int8Array(arrayBuffer).toByteArray()
                                        val text = bytes.decodeToString()
                                        if (text.contains(".") || text.contains("~") || text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it.isWhitespace() }) {
                                            rawInput = text.trim()
                                        } else {
                                            rawInput = bytes.toHex()
                                        }
                                    }
                                    reader.readAsArrayBuffer(file)
                                }
                            }
                        }
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
                    marginBottom = 4.px
                    focus {
                        outline = None.none
                        borderColor = Color("#3b82f6")
                    }
                }
                value = rawInput
                placeholder = "Paste SD-JWT, SD-JWT+KB, or standard JWT (e.g. eyJhbGciOiJFUzI1...)"
                onChange = { rawInput = it.target.value }
            }

            DetectedInputBadge {
                input = rawInput
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
                    parseInput(rawInput)
                }
                +"Inspect Token (SD-JWT / JWT)"
            }
        }

        parsedSdJwt?.let { sdjwt ->
            div {
                css {
                    marginTop = 32.px
                }
                renderSdJwtDetails(sdjwt, parsedSdJwtKb, processedPayload)
            }
        }
    }
}

fun react.ChildrenBuilder.renderSdJwtDetails(
    sdjwt: SdJwt,
    parsedSdJwtKb: SdJwtKb? = null,
    processedPayload: JsonObject? = null
) {
    div {
        css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center; marginBottom = 16.px }

        h3 {
            css {
                fontSize = 1.4.rem
                fontWeight = FontWeight.bold
                margin = 0.px
                color = Color("#f1f5f9")
            }
            +"Decoded Issuer-Signed JWT"
        }

        div {
            css { display = Display.flex; gap = 8.px; alignItems = AlignItems.center }

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
                    hover { backgroundColor = Color("#2563eb") }
                }
                onClick = {
                    kotlinx.browser.window.navigator.clipboard.writeText(sdjwt.compactSerialization)
                }
                +"📋 Copy Compact Serialization"
            }

            if (parsedSdJwtKb != null) {
                span {
                    css {
                        background = Color("#7c3aed")
                        color = Color("#ffffff")
                        fontWeight = FontWeight.bold
                        padding = Padding(6.px, 12.px)
                        borderRadius = 6.px
                        fontSize = 13.px
                    }
                    +"SD-JWT+KB (Key Binding Detected)"
                }
            } else {
                span {
                    css {
                        background = Color("#2563eb")
                        color = Color("#ffffff")
                        fontWeight = FontWeight.bold
                        padding = Padding(6.px, 12.px)
                        borderRadius = 6.px
                        fontSize = 13.px
                    }
                    +"SD-JWT (Issuer Signed)"
                }
            }
        }
    }

    div {
        css {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 24.px
            marginBottom = 32.px
        }

        // Header Card
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 8.px
                padding = 20.px
            }
            h4 {
                css {
                    color = Color("#cbd5e1")
                    fontWeight = FontWeight.bold
                    marginBottom = 12.px
                }
                +"JWT Header"
            }
            pre {
                css {
                    color = Color("#60a5fa")
                    fontSize = 13.px
                    fontFamily = FontFamily.monospace
                    overflowX = "auto".unsafeCast<Overflow>()
                }
                +Json { prettyPrint = true }.encodeToString(sdjwt.jwtHeader)
            }
        }

        // Body Card
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 8.px
                padding = 20.px
            }
            h4 {
                css {
                    color = Color("#cbd5e1")
                    fontWeight = FontWeight.bold
                    marginBottom = 12.px
                }
                +"Raw JWT Payload / Claims"
            }
            pre {
                css {
                    color = Color("#a78bfa")
                    fontSize = 13.px
                    fontFamily = FontFamily.monospace
                    overflowX = "auto".unsafeCast<Overflow>()
                }
                +Json { prettyPrint = true }.encodeToString(sdjwt.jwtBody)
            }
        }

        // Processed Payload Card (calculated via SdJwt.verify())
        processedPayload?.let { payload ->
            div {
                css {
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#059669"))
                    borderRadius = 8.px
                    padding = 20.px
                }
                h4 {
                    css {
                        color = Color("#34d399")
                        fontWeight = FontWeight.bold
                        marginBottom = 12.px
                    }
                    +"Processed SD-JWT Payload"
                }
                pre {
                    css {
                        color = Color("#34d399")
                        fontSize = 13.px
                        fontFamily = FontFamily.monospace
                        overflowX = "auto".unsafeCast<Overflow>()
                    }
                    +Json { prettyPrint = true }.encodeToString(payload)
                }
            }
        }
    }

    // Metadata Details
    div {
        css {
            background = Color("#0f172a")
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            borderRadius = 8.px
            padding = 20.px
            marginBottom = 32.px
            fontSize = 14.px
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 8.px
        }
        sdjwt.issuer?.let { iss ->
            div {
                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Issuer (iss / issuer): " }
                span { css { color = Color("#f1f5f9") }; +iss }
            }
        }
        sdjwt.credentialType?.let { vct ->
            div {
                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Credential Type (vct): " }
                span { css { color = Color("#38bdf8") }; +vct }
            }
        }
        sdjwt.subject?.let { sub ->
            div {
                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Subject (sub): " }
                span { css { color = Color("#cbd5e1") }; +sub }
            }
        }
        sdjwt.issuedAt?.let { iat ->
            div {
                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Issued At (iat): " }
                span { css { color = Color("#cbd5e1") }; +iat.toString() }
            }
        }
        sdjwt.validUntil?.let { exp ->
            div {
                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Expiration (exp): " }
                span { css { color = Color("#cbd5e1") }; +exp.toString() }
            }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Digest Algorithm (_sd_alg): " }
            span { css { color = Color("#34d399") }; +sdjwt.digestAlg.hashAlgorithmName }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Disclosures Count: " }
            span { css { color = Color("#a78bfa") }; +"${sdjwt.disclosures.size}" }
        }
    }

    // KB-JWT (Key Binding JWT) Section if SD-JWT+KB
    parsedSdJwtKb?.let { kb ->
        div {
            css {
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#7c3aed"))
                borderRadius = 12.px
                padding = 24.px
                marginBottom = 32.px
            }

            h3 {
                css { fontSize = 1.3.rem; color = Color("#a78bfa"); marginTop = 0.px; marginBottom = 12.px }
                +"🔐 Key Binding JWT (KB-JWT Payload)"
            }

            p {
                css { color = Color("#94a3b8"); fontSize = 13.px; marginBottom = 16.px }
                +"Claims signed by the device-bound key to bind the credential to the verifier session:"
            }

            pre {
                css {
                    background = Color("#1e293b")
                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                    borderRadius = 8.px
                    color = Color("#38bdf8")
                    fontSize = 13.px
                    fontFamily = FontFamily.monospace
                    padding = 14.px
                    overflowX = "auto".unsafeCast<Overflow>()
                    marginBottom = 16.px
                }
                +Json { prettyPrint = true }.encodeToString(kb.jwtBody)
            }

            div {
                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 8.px; fontSize = 13.px }

                kb.jwtBody["nonce"]?.jsonPrimitive?.content?.let { nonceVal ->
                    div {
                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Nonce: " }
                        span { css { color = Color("#4ade80"); fontFamily = FontFamily.monospace }; +nonceVal }
                    }
                }

                kb.jwtBody["aud"]?.jsonPrimitive?.content?.let { audVal ->
                    div {
                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Audience (aud): " }
                        span { css { color = Color("#f1f5f9") }; +audVal }
                    }
                }

                kb.jwtBody["iat"]?.jsonPrimitive?.content?.let { iatVal ->
                    div {
                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Issued At (iat): " }
                        span { css { color = Color("#cbd5e1") }; +iatVal }
                    }
                }

                kb.jwtBody["sd_hash"]?.jsonPrimitive?.content?.let { sdHashVal ->
                    div {
                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"SD Hash (sd_hash): " }
                        span { css { color = Color("#38bdf8"); fontFamily = FontFamily.monospace }; +sdHashVal }
                    }
                }
            }
        }
    }

    // Disclosures Table
    if (sdjwt.disclosures.isNotEmpty()) {
        h3 {
            css {
                fontSize = 1.4.rem
                fontWeight = FontWeight.bold
                marginBottom = 16.px
                color = Color("#f1f5f9")
            }
            +"Disclosures"
        }

        table {
            css {
                width = 100.pct
                borderCollapse = BorderCollapse.collapse
                background = Color("#0f172a")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 8.px
                fontSize = 14.px
                marginBottom = 24.px
            }
            thead {
                tr {
                    th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Index" }
                    th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Salt" }
                    th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Claim Name" }
                    th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Claim Value" }
                }
            }
            tbody {
                sdjwt.disclosures.forEachIndexed { index, discStr ->
                    val decoded = try {
                        val jsonArr = Json.decodeFromString<JsonArray>(discStr.fromBase64Url().decodeToString())
                        if (jsonArr.size == 3) {
                            // Standard claim: [salt, name, value]
                            Triple(jsonArr[0].jsonPrimitive.content, jsonArr[1].jsonPrimitive.content, jsonArr[2].toString())
                        } else if (jsonArr.size == 2) {
                            // Array element: [salt, value]
                            Triple(jsonArr[0].jsonPrimitive.content, "(Array Element)", jsonArr[1].toString())
                        } else {
                            Triple("Unknown", "Unknown", jsonArr.toString())
                        }
                    } catch (e: Exception) {
                        Triple("Error", "Error", "Failed to decode: " + e.message)
                    }

                    tr {
                        td { css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); color = Color("#64748b") }; +(index + 1).toString() }
                        td { css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; fontSize = 12.px; color = Color("#cbd5e1") }; +decoded.first }
                        td { css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontWeight = FontWeight.bold; color = Color("#38bdf8") }; +decoded.second }
                        td {
                            css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; color = Color("#34d399") }
                            
                            val claimName = decoded.second
                            val claimValRaw = decoded.third
                            val claimValueStr = claimValRaw.removeSurrounding("\"")

                            val imgUri = if (claimName.equals("picture", ignoreCase = true) && claimValueStr.length > 500) {
                                if (claimValueStr.startsWith("data:image/")) {
                                    claimValueStr
                                } else {
                                    try {
                                        val bytes = try {
                                            claimValueStr.fromBase64()
                                        } catch (e1: Throwable) {
                                            try {
                                                claimValueStr.fromBase64Url()
                                            } catch (e2: Throwable) {
                                                null
                                            }
                                        }

                                        if (bytes != null && bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 && (bytes[2].toInt() and 0xFF) == 0xFF) {
                                            "data:image/jpeg;base64,${bytes.toBase64()}"
                                        } else if (bytes != null && bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0x89 && (bytes[1].toInt() and 0xFF) == 0x50 && (bytes[2].toInt() and 0xFF) == 0x4E && (bytes[3].toInt() and 0xFF) == 0x47) {
                                            "data:image/png;base64,${bytes.toBase64()}"
                                        } else {
                                            null
                                        }
                                    } catch (e: Throwable) {
                                        null
                                    }
                                }
                            } else {
                                null
                            }

                            if (imgUri != null) {
                                img {
                                    src = imgUri
                                    css {
                                        maxWidth = 180.px
                                        maxHeight = 220.px
                                        borderRadius = 8.px
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        boxShadow = BoxShadow(0.px, 2.px, 6.px, Color("rgba(0,0,0,0.3)"))
                                        display = Display.block
                                        marginBottom = 8.px
                                    }
                                }
                                div {
                                    css { fontSize = 11.px; color = Color("#64748b"); fontFamily = FontFamily.monospace }
                                    +"${claimValueStr.take(64)}..."
                                }
                            } else {
                                +decoded.third
                            }
                        }
                    }
                }
            }
        }
    }
}
