@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffectOnce
import react.useState
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.InputType
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKeyOkp
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPrivateKeyOkp
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.toCoseLabel
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.util.toHex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ParsedKeyData(
    val privateKey: EcPrivateKey?,
    val publicKey: EcPublicKey,
    val jwkPub: String,
    val cosePub: String,
    val cdnPub: String,
    val pemPub: String,
    val jwkPriv: String,
    val cosePriv: String,
    val cdnPriv: String,
    val pemPriv: String
)

val KeyDecoderComponent = FC {
    var rawInput by useState("")
    var parsedKey by useState<ParsedKeyData?>(null)
    var parseError by useState("")
    var privateKeyTab by useState("jwk")
    var publicKeyTab by useState("jwk")
    var copyPrivateKeySuccess by useState(false)
    var copyPublicKeySuccess by useState(false)

    fun decodeKey(inputStr: String) {
        mainScope.launch {
            try {
                val cleanInput = inputStr.trim()
                if (cleanInput.isEmpty()) {
                    parsedKey = null
                    parseError = ""
                    return@launch
                }

                var privKey: EcPrivateKey? = null
                var pubKey: EcPublicKey? = null

                // 1. JWK JSON
                if (cleanInput.startsWith("{")) {
                    val jsonObj = Json.parseToJsonElement(cleanInput).jsonObject
                    if (jsonObj.containsKey("d")) {
                        privKey = EcPrivateKey.fromJwk(jsonObj)
                        pubKey = privKey.publicKey
                    } else {
                        pubKey = EcPublicKey.fromJwk(jsonObj)
                    }
                }
                // 2. PEM Format
                else if (cleanInput.contains("-----BEGIN")) {
                    if (cleanInput.contains("PUBLIC KEY")) {
                        pubKey = EcPublicKey.fromPem(cleanInput)
                    } else {
                        try {
                            privKey = parseEcPrivateKeyFromPem(cleanInput)
                            pubKey = privKey.publicKey
                        } catch (e: Throwable) {
                            throw IllegalArgumentException("Failed to decode Private Key PEM: " + (e.message ?: "Invalid PEM"))
                        }
                    }
                }
                // 3. COSE Key / CBOR / CDN / Hex / Base64
                else {
                    val dataItem = try {
                        Cdn.parse(cleanInput)
                    } catch (e: Throwable) {
                        val bytes = decodeInputToBytes(cleanInput)
                        Cbor.decode(bytes)
                    }
                    val coseKey = CoseKey.fromDataItem(dataItem)
                    if (coseKey.labels.containsKey(Cose.COSE_KEY_PARAM_D.toCoseLabel)) {
                        privKey = EcPrivateKey.fromCoseKey(coseKey)
                        pubKey = privKey.publicKey
                    } else {
                        pubKey = EcPublicKey.fromCoseKey(coseKey)
                    }
                }

                val targetPubKey = pubKey ?: error("Failed to parse Public Key")

                val jwkPub = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), targetPubKey.toJwk())
                val cosePub = Cbor.encode(targetPubKey.toCoseKey().toDataItem()).toHex()
                val cdnPub = Cdn.encode(targetPubKey.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty)
                val pemPub = targetPubKey.toPem()

                val jwkPriv = privKey?.let { Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), it.toJwk()) } ?: ""
                val cosePriv = privKey?.let { Cbor.encode(it.toCoseKey().toDataItem()).toHex() } ?: ""
                val cdnPriv = privKey?.let { Cdn.encode(it.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty) } ?: ""
                val pemPriv = privKey?.let { it.toPem() } ?: ""

                parsedKey = ParsedKeyData(
                    privateKey = privKey,
                    publicKey = targetPubKey,
                    jwkPub = jwkPub,
                    cosePub = cosePub,
                    cdnPub = cdnPub,
                    pemPub = pemPub,
                    jwkPriv = jwkPriv,
                    cosePriv = cosePriv,
                    cdnPriv = cdnPriv,
                    pemPriv = pemPriv
                )
                parseError = ""
                updateUrlHashPayload(cleanInput)
            } catch (e: Throwable) {
                parseError = "Error decoding key: " + (e.message ?: "Unknown error")
                parsedKey = null
            }
        }
    }

    useEffectOnce {
        val hashPayload = getUrlHashPayload()
        if (hashPayload.isNotEmpty()) {
            rawInput = hashPayload
            decodeKey(hashPayload)
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
            +"Key Decoder"
        }

        if (parsedKey != null || parseError.isNotEmpty()) {
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
                    parsedKey = null
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
                        background = Color("#7f1d1d")
                        padding = Padding(12.px, 16.px)
                        borderRadius = 8.px
                        border = Border(1.px, LineStyle.solid, Color("#fca5a5"))
                    }
                    +parseError
                }
            }

            parsedKey?.let { keyData ->
                val pubKey = keyData.publicKey
                val privKey = keyData.privateKey
                val curve = pubKey.curve

                div {
                    css {
                        marginTop = 24.px
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    // Card 1: Key Metadata & Capabilities
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

                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                                borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b"))
                                paddingBottom = 12.px
                            }
                            h3 {
                                css {
                                    margin = 0.px
                                    fontSize = 1.3.rem
                                    color = Color("#f1f5f9")
                                }
                                +"Key Information"
                            }
                            span {
                                css {
                                    fontSize = 12.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color(if (privKey != null) "#8b5cf6" else "#3b82f6")
                                    color = Color("#ffffff")
                                    padding = Padding(4.px, 10.px)
                                    borderRadius = 20.px
                                }
                                +(if (privKey != null) "Private Key" else "Public Key")
                            }
                        }

                        div {
                            css {
                                display = Display.grid
                                gridTemplateColumns = "repeat(auto-fit, minmax(200px, 1fr))".unsafeCast<GridTemplateColumns>()
                                gap = 16.px
                            }

                            div {
                                span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"CURVE NAME" }
                                span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 15.px }; +curve.name }
                            }

                            div {
                                span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY SIZE" }
                                span { css { color = Color("#f1f5f9"); fontSize = 15.px }; +"${curve.bitSize} bits" }
                            }

                            div {
                                span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"SIGNING" }
                                span { css { color = Color(if (curve.supportsSigning) "#10b981" else "#ef4444"); fontSize = 15.px; fontWeight = FontWeight.bold }; +(if (curve.supportsSigning) "Supported" else "Not Supported") }
                            }

                            div {
                                span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold }; +"KEY AGREEMENT" }
                                span { css { color = Color(if (curve.supportsKeyAgreement) "#10b981" else "#ef4444"); fontSize = 15.px; fontWeight = FontWeight.bold }; +(if (curve.supportsKeyAgreement) "Supported" else "Not Supported") }
                            }
                        }
                    }

                    // Card 2: Key Coordinates
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
                            +"Key Parameters & Coordinates"
                        }

                        val coordsString = when (pubKey) {
                            is EcPublicKeyDoubleCoordinate -> "X: ${pubKey.x.toHex()}\nY: ${pubKey.y.toHex()}"
                            is EcPublicKeyOkp -> "X: ${pubKey.x.toHex()}"
                        }

                        div {
                            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Public Key Coordinates" }
                            pre {
                                css {
                                    background = Color("#1e293b")
                                    padding = 16.px
                                    borderRadius = 8.px
                                    fontFamily = FontFamily.monospace
                                    fontSize = 12.px
                                    color = Color("#38bdf8")
                                    marginTop = 8.px
                                    overflowX = "auto".unsafeCast<Overflow>()
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                }
                                +coordsString
                            }
                        }

                        if (privKey != null) {
                            val dString = "d: ${privKey.d.toHex()}"
                            div {
                                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; fontSize = 12.px; textTransform = TextTransform.uppercase }; +"Private Key Parameter (d)" }
                                pre {
                                    css {
                                        background = Color("#1e293b")
                                        padding = 16.px
                                        borderRadius = 8.px
                                        fontFamily = FontFamily.monospace
                                        fontSize = 12.px
                                        color = Color("#34d399")
                                        marginTop = 8.px
                                        overflowX = "auto".unsafeCast<Overflow>()
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    }
                                    +dString
                                }
                            }
                        }
                    }

                    // Card 3 & 4: Encoded Representations (Public & Private Keys)
                    div {
                        css {
                            display = Display.grid
                            gridTemplateColumns = (if (privKey != null) "repeat(2, 1fr)" else "1fr").unsafeCast<GridTemplateColumns>()
                            gap = 24.px
                        }

                        // Public Key Representations
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
                                css { margin = 0.px; fontSize = 1.3.rem; color = Color("#f1f5f9") }
                                +"Public Key Representations"
                            }

                            div {
                                css {
                                    display = Display.flex
                                    gap = 8.px
                                    background = Color("#1e293b")
                                    padding = 4.px
                                    borderRadius = 8.px
                                }
                                listOf(
                                    "jwk" to "JWK (JSON)",
                                    "cose" to "COSE Hex",
                                    "diagnostic" to "CDN",
                                    "pem" to "PEM"
                                ).forEach { (tabId, tabTitle) ->
                                    button {
                                        css {
                                            padding = Padding(6.px, 12.px)
                                            border = None.none
                                            borderRadius = 6.px
                                            fontSize = 12.px
                                            fontWeight = FontWeight.bold
                                            cursor = Cursor.pointer
                                            if (publicKeyTab == tabId) {
                                                background = Color("#3b82f6")
                                                color = Color("#ffffff")
                                            } else {
                                                background = Color("transparent")
                                                color = Color("#94a3b8")
                                                hover { color = Color("#f1f5f9") }
                                            }
                                        }
                                        onClick = { publicKeyTab = tabId; copyPublicKeySuccess = false }
                                        +tabTitle
                                    }
                                }
                            }

                            val pubContent = when (publicKeyTab) {
                                "jwk" -> keyData.jwkPub
                                "cose" -> keyData.cosePub
                                "diagnostic" -> keyData.cdnPub
                                else -> keyData.pemPub
                            }

                            textarea {
                                css {
                                    width = 100.pct
                                    height = 240.px
                                    background = Color("#1e293b")
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    borderRadius = 8.px
                                    color = Color("#38bdf8")
                                    fontFamily = FontFamily.monospace
                                    fontSize = 12.px
                                    padding = 12.px
                                    resize = "none".unsafeCast<Resize>()
                                    focus { outline = None.none }
                                }
                                readOnly = true
                                value = pubContent
                            }

                            button {
                                css {
                                    padding = Padding(10.px, 20.px)
                                    fontSize = 14.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color(if (copyPublicKeySuccess) "#10b981" else "#334155")
                                    color = Color("#ffffff")
                                    border = None.none
                                    borderRadius = 8.px
                                    cursor = Cursor.pointer
                                    hover {
                                        backgroundColor = Color(if (copyPublicKeySuccess) "#10b981" else "#475569")
                                    }
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(pubContent)
                                    copyPublicKeySuccess = true
                                }
                                +(if (copyPublicKeySuccess) "Copied!" else "Copy Public Key")
                            }
                        }

                        // Private Key Representations (if present)
                        if (privKey != null) {
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
                                    css { margin = 0.px; fontSize = 1.3.rem; color = Color("#f1f5f9") }
                                    +"Private Key Representations"
                                }

                                div {
                                    css {
                                        display = Display.flex
                                        gap = 8.px
                                        background = Color("#1e293b")
                                        padding = 4.px
                                        borderRadius = 8.px
                                    }
                                    listOf(
                                        "jwk" to "JWK (JSON)",
                                        "cose" to "COSE Hex",
                                        "diagnostic" to "CDN",
                                        "pem" to "PEM"
                                    ).forEach { (tabId, tabTitle) ->
                                        button {
                                            css {
                                                padding = Padding(6.px, 12.px)
                                                border = None.none
                                                borderRadius = 6.px
                                                fontSize = 12.px
                                                fontWeight = FontWeight.bold
                                                cursor = Cursor.pointer
                                                if (privateKeyTab == tabId) {
                                                    background = Color("#3b82f6")
                                                    color = Color("#ffffff")
                                                } else {
                                                    background = Color("transparent")
                                                    color = Color("#94a3b8")
                                                    hover { color = Color("#f1f5f9") }
                                                }
                                            }
                                            onClick = { privateKeyTab = tabId; copyPrivateKeySuccess = false }
                                            +tabTitle
                                        }
                                    }
                                }

                                val privContent = when (privateKeyTab) {
                                    "jwk" -> keyData.jwkPriv
                                    "cose" -> keyData.cosePriv
                                    "diagnostic" -> keyData.cdnPriv
                                    else -> keyData.pemPriv
                                }

                                textarea {
                                    css {
                                        width = 100.pct
                                        height = 240.px
                                        background = Color("#1e293b")
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        borderRadius = 8.px
                                        color = Color("#34d399")
                                        fontFamily = FontFamily.monospace
                                        fontSize = 12.px
                                        padding = 12.px
                                        resize = "none".unsafeCast<Resize>()
                                        focus { outline = None.none }
                                    }
                                    readOnly = true
                                    value = privContent
                                }

                                button {
                                    css {
                                        padding = Padding(10.px, 20.px)
                                        fontSize = 14.px
                                        fontWeight = FontWeight.bold
                                        backgroundColor = Color(if (copyPrivateKeySuccess) "#10b981" else "#334155")
                                        color = Color("#ffffff")
                                        border = None.none
                                        borderRadius = 8.px
                                        cursor = Cursor.pointer
                                        hover {
                                            backgroundColor = Color(if (copyPrivateKeySuccess) "#10b981" else "#475569")
                                        }
                                    }
                                    onClick = {
                                        window.navigator.asDynamic().clipboard.writeText(privContent)
                                        copyPrivateKeySuccess = true
                                    }
                                    +(if (copyPrivateKeySuccess) "Copied!" else "Copy Private Key")
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
                +"Decode and inspect Elliptic Curve public and private keys. Supports JWK (JSON), COSE Key (CBOR Hex/Base64/CDN), and PEM formats."
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
                    +"Public or Private Key (JWK, COSE CBOR/CDN, or PEM):"
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
                                hover { background = Color("#475569") }
                            }
                            onClick = {
                                rawInput = ""
                                parsedKey = null
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
                            hover { background = Color("#475569") }
                        }
                        +"📁 Load data"
                        input {
                            type = "file".unsafeCast<InputType>()
                            accept = ".json,.pem,.cbor,.hex,.txt,*/*"
                            css { display = None.none }
                            onChange = { event ->
                                val fileList = event.target.asDynamic().files
                                if (fileList != null && fileList.length > 0) {
                                    val file = fileList[0].unsafeCast<File>()
                                    val reader = FileReader()
                                    reader.asDynamic().onload = {
                                        val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                        val bytes = Int8Array(arrayBuffer).toByteArray()
                                        val text = bytes.decodeToString()
                                        if (text.startsWith("{") || text.contains("-----BEGIN") || text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it.isWhitespace() }) {
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
                    height = 200.px
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
                placeholder = "Paste key here (JWK JSON, COSE Key CBOR/CDN, or PEM format)..."
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
                    hover { backgroundColor = Color("#2563eb") }
                    disabled {
                        backgroundColor = Color("#475569")
                        cursor = Cursor.notAllowed
                    }
                }
                disabled = rawInput.trim().isEmpty()
                onClick = { decodeKey(rawInput) }
                +"Decode Key"
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun parseEcPrivateKeyFromPem(pemStr: String): EcPrivateKey {
    val b64 = pemStr
        .lines()
        .filterNot { it.startsWith("-----") || it.startsWith("#") }
        .joinToString("")
        .replace(Regex("[\\s\\r\\n\\t]"), "")
    val bytes = Base64.Mime.decode(b64)
    val seq = ASN1.decode(bytes) as ASN1Sequence

    val algSeq = seq.elements[1] as ASN1Sequence
    val algOid = (algSeq.elements[0] as ASN1ObjectIdentifier).oid
    val curve = when (algOid) {
        OID.EC_PUBLIC_KEY.oid -> {
            val curveOid = (algSeq.elements[1] as ASN1ObjectIdentifier).oid
            when (curveOid) {
                "1.2.840.10045.3.1.7" -> EcCurve.P256
                "1.3.132.0.34" -> EcCurve.P384
                "1.3.132.0.35" -> EcCurve.P521
                "1.3.36.3.3.2.8.1.1.7" -> EcCurve.BRAINPOOLP256R1
                "1.3.36.3.3.2.8.1.1.9" -> EcCurve.BRAINPOOLP320R1
                "1.3.36.3.3.2.8.1.1.11" -> EcCurve.BRAINPOOLP384R1
                "1.3.36.3.3.2.8.1.1.13" -> EcCurve.BRAINPOOLP512R1
                else -> throw IllegalArgumentException("Unsupported curve OID $curveOid")
            }
        }
        "1.3.101.110" -> EcCurve.X25519
        "1.3.101.111" -> EcCurve.X448
        "1.3.101.112" -> EcCurve.ED25519
        "1.3.101.113" -> EcCurve.ED448
        else -> throw IllegalArgumentException("Unsupported algorithm OID $algOid")
    }

    val innerOctet = (seq.elements[2] as ASN1OctetString).value
    return when (curve) {
        EcCurve.P256, EcCurve.P384, EcCurve.P521,
        EcCurve.BRAINPOOLP256R1, EcCurve.BRAINPOOLP320R1, EcCurve.BRAINPOOLP384R1, EcCurve.BRAINPOOLP512R1 -> {
            val innerSeq = ASN1.decode(innerOctet) as ASN1Sequence
            val dBytes = (innerSeq.elements[1] as ASN1OctetString).value

            var pubBytes: ByteArray? = null
            for (elem in innerSeq.elements) {
                if (elem is ASN1TaggedObject && elem.tag == 1) {
                    val bitStr = ASN1.decode(elem.content) as ASN1BitString
                    pubBytes = bitStr.value
                }
            }

            if (pubBytes != null) {
                val pubKey = EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(curve, pubBytes)
                EcPrivateKeyDoubleCoordinate(curve, dBytes, pubKey.x, pubKey.y)
            } else {
                throw IllegalArgumentException("PEM private key does not contain public key coordinates")
            }
        }
        EcCurve.ED25519, EcCurve.X25519, EcCurve.ED448, EcCurve.X448 -> {
            throw IllegalArgumentException("OKP private key in PKCS#8 format requires public key coordinates to construct")
        }
    }
}
