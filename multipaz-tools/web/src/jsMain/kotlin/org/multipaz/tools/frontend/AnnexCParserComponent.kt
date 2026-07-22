@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.js.ExperimentalWasmJsInterop::class
)
package org.multipaz.tools.frontend

import emotion.react.css
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
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
import kotlin.random.Random

private suspend fun createSampleRequestJson(): String {
    return try {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val nonce = Random.nextBytes(16)
        val encInfo = buildCborArray {
            add("dcapi")
            addCborMap {
                put("nonce", nonce)
                put("recipientPublicKey", key.publicKey.toDataItem())
            }
        }
        val devReq = buildDeviceRequest(sessionTranscript = buildCborArray {}) {
            addDocRequest(
                docType = "org.iso.18013.5.1.mDL",
                nameSpaces = mapOf(
                    "org.iso.18013.5.1" to mapOf(
                        "given_name" to false,
                        "family_name" to false,
                        "portrait" to false,
                        "age_over_18" to true
                    )
                )
            )
        }
        val b64DevReq = Cbor.encode(devReq.toDataItem()).toBase64Url()
        val b64EncInfo = Cbor.encode(encInfo).toBase64Url()
        "{\n  \"deviceRequest\": \"$b64DevReq\",\n  \"encryptionInfo\": \"$b64EncInfo\"\n}"
    } catch (e: Throwable) {
        ""
    }
}

private fun createSampleResponseJson(): String {
    return try {
        val encBytes = Random.nextBytes(64)
        val encResponse = buildCborArray {
            add("dcapi")
            addCborMap {
                put("enc", encBytes)
            }
        }
        val b64Response = Cbor.encode(encResponse).toBase64Url()
        "{\n  \"response\": \"$b64Response\"\n}"
    } catch (e: Throwable) {
        ""
    }
}

private class ParsedRequestData(
    val deviceRequest: DeviceRequest,
    val devReqHex: String,
    val devReqDataItem: DataItem,
    val encryptionInfoDataItem: DataItem,
    val encInfoHex: String,
    val nonceHex: String?,
    val recipientPublicKeyPem: String?,
    val recipientPublicKeyCurve: String?
)

private class ParsedResponseData(
    val responseDataItem: DataItem,
    val responseHex: String,
    val encryptedPayloadHex: String?
)

val AnnexCParserComponent: FC<Props> = FC {
    var mode by useState("request") // "request" or "response"
    var rawInput by useState("")

    var parsedRequest by useState<ParsedRequestData?>(null)
    var parsedResponse by useState<ParsedResponseData?>(null)
    var parseError by useState("")

    var copyDevReqHexStatus by useState("")
    var copyEncInfoHexStatus by useState("")
    var copyNonceHexStatus by useState("")
    var copyPubKeyPemStatus by useState("")

    var copyRespHexStatus by useState("")
    var copyEncPayloadHexStatus by useState("")

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
            +"ISO 18013-7 Annex C Parser"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Parse W3C Digital Credentials API (ISO 18013-7 Annex C) Request JSON and Response JSON objects used for mdoc credential presentment."
        }

        // Mode Switcher Tabs
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
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                    border = None.none
                    if (mode == "request") {
                        background = Color("#3b82f6")
                        color = Color("#ffffff")
                    } else {
                        background = Color("#334155")
                        color = Color("#94a3b8")
                        hover { background = Color("#475569"); color = Color("#f1f5f9") }
                    }
                }
                onClick = {
                    mode = "request"
                    rawInput = ""
                    parsedRequest = null
                    parsedResponse = null
                    parseError = ""
                }
                +"Request JSON Parser"
            }

            button {
                css {
                    padding = Padding(10.px, 20.px)
                    fontSize = 14.px
                    fontWeight = FontWeight.bold
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                    border = None.none
                    if (mode == "response") {
                        background = Color("#3b82f6")
                        color = Color("#ffffff")
                    } else {
                        background = Color("#334155")
                        color = Color("#94a3b8")
                        hover { background = Color("#475569"); color = Color("#f1f5f9") }
                    }
                }
                onClick = {
                    mode = "response"
                    rawInput = ""
                    parsedRequest = null
                    parsedResponse = null
                    parseError = ""
                }
                +"Response JSON Parser"
            }
        }

        if (parsedRequest != null || parsedResponse != null || parseError.isNotEmpty()) {
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
                    hover { backgroundColor = Color("#475569") }
                }
                onClick = {
                    parsedRequest = null
                    parsedResponse = null
                    parseError = ""
                }
                +"← Clear and Decode Another"
            }

            if (parseError.isNotEmpty()) {
                div {
                    css {
                        marginTop = 16.px
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
            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +if (mode == "request") "Paste W3C Digital Credentials Request JSON:" else "Paste W3C Digital Credentials Response JSON:"
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
                        mainScope.launch {
                            if (mode == "request") {
                                rawInput = createSampleRequestJson()
                            } else {
                                rawInput = createSampleResponseJson()
                            }
                        }
                    }
                    +if (mode == "request") "🧪 Load Sample Request JSON" else "🧪 Load Sample Response JSON"
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
                    marginBottom = 16.px
                    focus {
                        outline = None.none
                        borderColor = Color("#3b82f6")
                    }
                }
                value = rawInput
                placeholder = if (mode == "request") "Paste {\"deviceRequest\": \"...\", \"encryptionInfo\": \"...\"}..." else "Paste {\"response\": \"...\"}..."
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
                            val jsonObj = Json.parseToJsonElement(rawInput.trim()).jsonObject
                            if (mode == "request") {
                                val devReqB64 = jsonObj["deviceRequest"]?.jsonPrimitive?.content
                                    ?: error("Missing 'deviceRequest' field in JSON")
                                val encInfoB64 = jsonObj["encryptionInfo"]?.jsonPrimitive?.content
                                    ?: error("Missing 'encryptionInfo' field in JSON")

                                val devReqBytes = devReqB64.fromBase64Url()
                                val devReqDataItem = Cbor.decode(devReqBytes)
                                val devReq = DeviceRequest.fromDataItem(devReqDataItem)

                                val encInfoBytes = encInfoB64.fromBase64Url()
                                val encInfoDataItem = Cbor.decode(encInfoBytes)

                                var nonceHex: String? = null
                                var recipientPublicKeyPem: String? = null
                                var recipientPublicKeyCurve: String? = null

                                try {
                                    val paramsMap = encInfoDataItem.asArray[1].asMap
                                    paramsMap[Tstr("nonce")]?.let {
                                        nonceHex = it.asBstr.toHex()
                                    }
                                    paramsMap[Tstr("recipientPublicKey")]?.let { coseKeyItem ->
                                        val ecPublicKey = coseKeyItem.asCoseKey.ecPublicKey
                                        recipientPublicKeyPem = ecPublicKey.toPem()
                                        recipientPublicKeyCurve = ecPublicKey.curve.name
                                    }
                                } catch (e: Throwable) {
                                    // optional field extraction fallback
                                }

                                parsedRequest = ParsedRequestData(
                                    deviceRequest = devReq,
                                    devReqHex = devReqBytes.toHex(),
                                    devReqDataItem = devReqDataItem,
                                    encryptionInfoDataItem = encInfoDataItem,
                                    encInfoHex = encInfoBytes.toHex(),
                                    nonceHex = nonceHex,
                                    recipientPublicKeyPem = recipientPublicKeyPem,
                                    recipientPublicKeyCurve = recipientPublicKeyCurve
                                )
                                parseError = ""
                            } else {
                                val respB64 = jsonObj["response"]?.jsonPrimitive?.content
                                    ?: error("Missing 'response' field in JSON")

                                val respBytes = respB64.fromBase64Url()
                                val respDataItem = Cbor.decode(respBytes)

                                var encryptedPayloadHex: String? = null
                                try {
                                    val paramsMap = respDataItem.asArray[1].asMap
                                    paramsMap[Tstr("enc")]?.let {
                                        encryptedPayloadHex = it.asBstr.toHex()
                                    }
                                } catch (e: Throwable) {
                                    // optional payload extraction fallback
                                }

                                parsedResponse = ParsedResponseData(
                                    responseDataItem = respDataItem,
                                    responseHex = respBytes.toHex(),
                                    encryptedPayloadHex = encryptedPayloadHex
                                )
                                parseError = ""
                            }
                        } catch (e: Throwable) {
                            parseError = "Error parsing W3C Digital Credentials JSON: " + (e.message ?: "Invalid JSON / Base64Url / CBOR structure")
                            parsedRequest = null
                            parsedResponse = null
                        }
                    }
                }
                +if (mode == "request") "Decode Request JSON" else "Decode Response JSON"
            }
        }

        // Render Parsed Request View
        val req = parsedRequest
        if (req != null) {
            div {
                css { marginTop = 32.px }

                // EncryptionInfo Section
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

                        h3 {
                            css { color = Color("#38bdf8"); fontSize = 1.2.rem; margin = 0.px }
                            +"EncryptionInfo (Handover & Encryption Setup)"
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
                                window.navigator.asDynamic().clipboard.writeText(req.encInfoHex)
                                copyEncInfoHexStatus = "Copied Hex!"
                                window.setTimeout({ copyEncInfoHexStatus = "" }, 2000)
                            }
                            +if (copyEncInfoHexStatus.isNotEmpty()) copyEncInfoHexStatus else "📋 Copy EncryptionInfo Hex"
                        }
                    }

                    div {
                        css { display = Display.flex; flexDirection = FlexDirection.column; gap = 12.px; fontSize = 13.px; marginBottom = 16.px }

                        if (req.recipientPublicKeyCurve != null) {
                            div {
                                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Recipient Public Key Curve: " }
                                span { css { color = Color("#4ade80"); fontWeight = FontWeight.bold }; +req.recipientPublicKeyCurve!! }
                            }
                        }

                        if (req.recipientPublicKeyPem != null) {
                            div {
                                css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px }
                                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Recipient Public Key PEM: " }
                                button {
                                    css {
                                        background = Color("#334155")
                                        color = Color("#f1f5f9")
                                        border = None.none
                                        padding = Padding(4.px, 12.px)
                                        borderRadius = 6.px
                                        cursor = Cursor.pointer
                                        fontSize = 12.px
                                        hover { background = Color("#475569") }
                                    }
                                    onClick = {
                                        window.navigator.asDynamic().clipboard.writeText(req.recipientPublicKeyPem!!)
                                        copyPubKeyPemStatus = "Copied Public Key!"
                                        window.setTimeout({ copyPubKeyPemStatus = "" }, 2000)
                                    }
                                    +if (copyPubKeyPemStatus.isNotEmpty()) copyPubKeyPemStatus else "📋 Copy Public Key PEM"
                                }
                            }
                        }

                        if (req.nonceHex != null) {
                            div {
                                css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px }
                                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Nonce (${req.nonceHex!!.length / 2} bytes): " }
                                span { css { color = Color("#38bdf8"); fontFamily = FontFamily.monospace }; +req.nonceHex!! }
                                button {
                                    css {
                                        background = Color("#334155")
                                        color = Color("#f1f5f9")
                                        border = None.none
                                        padding = Padding(4.px, 12.px)
                                        borderRadius = 6.px
                                        cursor = Cursor.pointer
                                        fontSize = 12.px
                                        hover { background = Color("#475569") }
                                    }
                                    onClick = {
                                        window.navigator.asDynamic().clipboard.writeText(req.nonceHex!!)
                                        copyNonceHexStatus = "Copied Nonce!"
                                        window.setTimeout({ copyNonceHexStatus = "" }, 2000)
                                    }
                                    +if (copyNonceHexStatus.isNotEmpty()) copyNonceHexStatus else "📋 Copy Nonce Hex"
                                }
                            }
                        }
                    }

                    CborDiagnosticViewer {
                        diagText = Cbor.toDiagnostics(req.encryptionInfoDataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                        maxHeight = 250.px
                    }
                }

                // DeviceRequest Overview Section
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

                        h3 {
                            css { color = Color("#38bdf8"); fontSize = 1.2.rem; margin = 0.px }
                            +"DeviceRequest (Decoded Payload)"
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
                                window.navigator.asDynamic().clipboard.writeText(req.devReqHex)
                                copyDevReqHexStatus = "Copied Hex!"
                                window.setTimeout({ copyDevReqHexStatus = "" }, 2000)
                            }
                            +if (copyDevReqHexStatus.isNotEmpty()) copyDevReqHexStatus else "📋 Copy DeviceRequest Hex"
                        }
                    }

                    val devReq = req.deviceRequest
                    devReq.docRequests.forEachIndexed { index, docReq ->
                        div {
                            css {
                                background = Color("#1e293b")
                                borderRadius = 8.px
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                padding = 16.px
                                marginBottom = 12.px
                            }

                            div {
                                css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; marginBottom = 8.px }
                                +"Requested Document Type: ${docReq.docType}"
                            }

                            if (docReq.nameSpaces.isNotEmpty()) {
                                table {
                                    css { width = 100.pct; borderCollapse = BorderCollapse.collapse }
                                    thead {
                                        tr {
                                            th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Namespace" }
                                            th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Data Element" }
                                            th { css { color = Color("#94a3b8"); textAlign = TextAlign.center; padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Intent to Retain" }
                                        }
                                    }
                                    tbody {
                                        docReq.nameSpaces.forEach { (nsName, elementsMap) ->
                                            elementsMap.forEach { (elementName, intentToRetain) ->
                                                tr {
                                                    td { css { color = Color("#cbd5e1"); padding = 6.px; fontSize = 13.px; fontFamily = FontFamily.monospace }; +nsName }
                                                    td { css { color = Color("#f1f5f9"); padding = 6.px; fontSize = 13.px; fontWeight = FontWeight.bold }; +elementName }
                                                    td {
                                                        css { textAlign = TextAlign.center; padding = 6.px; fontSize = 13.px }
                                                        span {
                                                            css { color = if (intentToRetain) Color("#f87171") else Color("#4ade80"); fontWeight = FontWeight.bold }
                                                            +if (intentToRetain) "Yes" else "No"
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

                    CborDiagnosticViewer {
                        diagText = Cbor.toDiagnostics(req.devReqDataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                        maxHeight = 350.px
                    }
                }
            }
        }

        // Render Parsed Response View
        val resp = parsedResponse
        if (resp != null) {
            div {
                css { marginTop = 32.px }

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

                        h3 {
                            css { color = Color("#4ade80"); fontSize = 1.2.rem; margin = 0.px }
                            +"EncryptedResponse (W3C Digital Credentials Response)"
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
                                window.navigator.asDynamic().clipboard.writeText(resp.responseHex)
                                copyRespHexStatus = "Copied Hex!"
                                window.setTimeout({ copyRespHexStatus = "" }, 2000)
                            }
                            +if (copyRespHexStatus.isNotEmpty()) copyRespHexStatus else "📋 Copy EncryptedResponse Hex"
                        }
                    }

                    if (resp.encryptedPayloadHex != null) {
                        div {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px; marginBottom = 16.px; fontSize = 13.px }

                            span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"HPKE Encrypted Payload (${resp.encryptedPayloadHex!!.length / 2} bytes): " }

                            button {
                                css {
                                    background = Color("#334155")
                                    color = Color("#f1f5f9")
                                    border = None.none
                                    padding = Padding(4.px, 12.px)
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    fontSize = 12.px
                                    hover { background = Color("#475569") }
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(resp.encryptedPayloadHex!!)
                                    copyEncPayloadHexStatus = "Copied Payload Hex!"
                                    window.setTimeout({ copyEncPayloadHexStatus = "" }, 2000)
                                }
                                +if (copyEncPayloadHexStatus.isNotEmpty()) copyEncPayloadHexStatus else "📋 Copy Encrypted Payload Hex"
                            }
                        }
                    }

                    CborDiagnosticViewer {
                        diagText = Cbor.toDiagnostics(resp.responseDataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                        maxHeight = 350.px
                    }
                }
            }
        }
    }
}
