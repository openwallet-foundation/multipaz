@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.js.ExperimentalWasmJsInterop::class
)
package org.multipaz.tools.frontend

import emotion.react.css
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MajorType
import org.multipaz.cbor.Tagged
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mpzpass.MpzPass
import org.multipaz.mpzpass.MpzPassIsoMdoc
import org.multipaz.mpzpass.MpzPassSdJwtVc
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.inflate
import org.multipaz.util.toBase64
import org.multipaz.util.toHex
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
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
import react.useEffectOnce
import react.useState
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.InputType

external interface KeyViewProps : Props {
    var title: String
    var privateKey: EcPrivateKey
}

val KeyViewComponent = FC<KeyViewProps> { props ->
    var keyFormat by useState("pem") // "pem" or "jwk"
    var copyStatus by useState("")

    val pemText = try {
        props.privateKey.toPem()
    } catch (e: Throwable) {
        props.privateKey.toDataItem().toString()
    }

    val jwkText = try {
        val json = Json { prettyPrint = true }
        json.encodeToString(JsonObject.serializer(), props.privateKey.toJwk())
    } catch (e: Throwable) {
        "Could not convert key to JWK: ${e.message}"
    }

    val currentText = if (keyFormat == "pem") pemText else jwkText

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            padding = 16.px
            marginBottom = 16.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 8.px
            }

            div {
                css { fontWeight = FontWeight.bold; color = Color("#f1f5f9") }
                +props.title
            }

            div {
                css { display = Display.flex; gap = 6.px; alignItems = AlignItems.center }

                button {
                    css {
                        padding = Padding(4.px, 10.px)
                        fontSize = 12.px
                        fontWeight = FontWeight.bold
                        borderRadius = 6.px
                        border = None.none
                        cursor = Cursor.pointer
                        if (keyFormat == "pem") {
                            background = Color("#2563eb")
                            color = Color("#ffffff")
                        } else {
                            background = Color("#0f172a")
                            color = Color("#94a3b8")
                        }
                    }
                    onClick = { keyFormat = "pem" }
                    +"PEM"
                }

                button {
                    css {
                        padding = Padding(4.px, 10.px)
                        fontSize = 12.px
                        fontWeight = FontWeight.bold
                        borderRadius = 6.px
                        border = None.none
                        cursor = Cursor.pointer
                        if (keyFormat == "jwk") {
                            background = Color("#2563eb")
                            color = Color("#ffffff")
                        } else {
                            background = Color("#0f172a")
                            color = Color("#94a3b8")
                        }
                    }
                    onClick = { keyFormat = "jwk" }
                    +"JWK"
                }

                button {
                    css {
                        padding = Padding(4.px, 10.px)
                        fontSize = 12.px
                        fontWeight = FontWeight.bold
                        background = Color("#3b82f6")
                        color = Color("#ffffff")
                        border = None.none
                        borderRadius = 6.px
                        cursor = Cursor.pointer
                        hover { background = Color("#2563eb") }
                    }
                    onClick = {
                        window.navigator.clipboard.writeText(currentText)
                        copyStatus = "Copied!"
                        window.setTimeout({ copyStatus = "" }, 2000)
                    }
                    +(copyStatus.ifEmpty { "📋 Copy ${keyFormat.uppercase()}" })
                }
            }
        }

        div {
            css { display = Display.flex; gap = 16.px; fontSize = 13.px; flexWrap = FlexWrap.wrap; marginBottom = 8.px }
            div {
                span { css { color = Color("#64748b") }; +"Curve: " }
                span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold }; +props.privateKey.curve.name }
            }
            div {
                span { css { color = Color("#64748b") }; +"Type: " }
                span { css { color = Color("#cbd5e1") }; +"EC Private Key (${keyFormat.uppercase()})" }
            }
        }

        pre {
            css {
                marginTop = 4.px
                background = Color("#0f172a")
                padding = 10.px
                borderRadius = 6.px
                fontSize = 11.px
                color = Color("#a78bfa")
                whiteSpace = WhiteSpace.preWrap
                wordBreak = WordBreak.breakAll
                maxHeight = 160.px
                asDynamic().overflow = "auto"
            }
            +currentText
        }
    }
}

data class ParsedSdJwtVcItem(
    val sdJwtVc: MpzPassSdJwtVc,
    val parsedSdJwt: SdJwt?,
    val parsedSdJwtKb: SdJwtKb?,
    val processedPayload: JsonObject?
)

val MpzPassDecoderComponent = FC {
    var rawInput by useState("")
    var parsedPass by useState<MpzPass?>(null)
    var parsedDataItem by useState<DataItem?>(null)
    var decompressedDataItem by useState<DataItem?>(null)
    var parsedSdJwtItems by useState<List<ParsedSdJwtVcItem>>(emptyList())
    var parseError by useState("")
    var isLoading by useState(false)
    var activeTab by useState("overview")
    var copyTopLevelCdnStatus by useState("")
    var copyDecompressedCdnStatus by useState("")
    var rawInputSize by useState(0)
    var copiedCertIndex by useState<Int?>(null)

    fun decodeMpzPassBytes(bytes: ByteArray, inputSourceForUrl: String = "") {
        if (bytes.isEmpty()) return
        isLoading = true
        parseError = ""
        rawInputSize = bytes.size
        mainScope.launch {
            try {
                val dataItem = Cbor.decode(bytes)
                val pass = MpzPass.fromDataItem(dataItem)
                val decompressed = try {
                    if (dataItem is CborArray && dataItem.items.size >= 2) {
                        val secondElement = dataItem[1]
                        val compressedBytes = when {
                            secondElement is Bstr -> secondElement.asBstr
                            secondElement is Tagged && secondElement.tagNumber == Tagged.COSE_SIGN1 -> {
                                val cose = CoseSign1.fromDataItem(secondElement.taggedItem)
                                cose.payload ?: error("No payload in COSE_Sign1")
                            }
                            else -> null
                        }
                        compressedBytes?.inflate()?.let { Cbor.decode(it) }
                    } else {
                        null
                    }
                } catch (e: Throwable) {
                    null
                }
                parsedDataItem = dataItem
                decompressedDataItem = decompressed
                parsedPass = pass
                activeTab = "overview"

                // Pre-parse SD-JWT VCs using async/suspend functions
                parsedSdJwtItems = pass.sdJwtVc.map { vc ->
                    val sdjwt = try {
                        SdJwt.fromCompactSerialization(vc.compactSerialization)
                    } catch (e: Throwable) {
                        null
                    }
                    val kb = try {
                        SdJwtKb.fromCompactSerialization(vc.compactSerialization)
                    } catch (e: Throwable) {
                        null
                    }
                    val processed = try {
                        sdjwt?.verify(issuerKey = null)
                    } catch (e: Throwable) {
                        null
                    }
                    ParsedSdJwtVcItem(vc, sdjwt, kb, processed)
                }

                parseError = ""
                if (inputSourceForUrl.isNotEmpty()) {
                    updateUrlHashPayload(inputSourceForUrl)
                }
            } catch (e: Throwable) {
                parseError = "Error decoding MpzPass: " + (e.message ?: e.toString())
                parsedPass = null
                parsedDataItem = null
                decompressedDataItem = null
                parsedSdJwtItems = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    fun parseInput(inputStr: String) {
        val cleanInput = inputStr.trim()
        if (cleanInput.isEmpty()) return
        try {
            val bytes = decodeInputToBytes(cleanInput)
            decodeMpzPassBytes(bytes, cleanInput)
        } catch (e: Throwable) {
            parseError = "Could not decode input as Hex, Base64Url, or Base64 binary: ${e.message}"
            parsedPass = null
            parsedDataItem = null
            decompressedDataItem = null
            parsedSdJwtItems = emptyList()
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
                margin = Margin(0.px, 0.px, 12.px, 0.px)
                color = Color("#f8fafc")
            }
            +"MpzPass Decoder"
        }

        val hasResult = parsedPass != null || parseError.isNotEmpty()

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
                    parsedPass = null
                    parsedDataItem = null
                    parsedSdJwtItems = emptyList()
                    parseError = ""
                    activeTab = "overview"
                    updateUrlHashPayload("")
                }
                +"← Back to Input"
            }

            if (parseError.isNotEmpty()) {
                div {
                    css {
                        padding = 20.px
                        borderRadius = 12.px
                        background = Color("#450a0a")
                        border = Border(1.px, LineStyle.solid, Color("#991b1b"))
                        color = Color("#fca5a5")
                        fontWeight = FontWeight.bold
                        whiteSpace = WhiteSpace.preWrap
                    }
                    +parseError
                }
            } else {
                parsedPass?.let { pass ->
                    // Top Pass Header Card
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 16.px
                            padding = 24.px
                            marginBottom = 24.px
                            display = Display.flex
                            justifyContent = JustifyContent.spaceBetween
                            alignItems = AlignItems.center
                            gap = 24.px
                        }

                        div {
                            css {
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 8.px
                                flexGrow = number(1.0)
                            }

                            div {
                                css {
                                    display = Display.flex
                                    alignItems = AlignItems.center
                                    gap = 12.px
                                }
                                h3 {
                                    css {
                                        fontSize = 1.5.rem
                                        fontWeight = FontWeight.bold
                                        color = Color("#f8fafc")
                                        margin = Margin(0.px, 0.px, 0.px, 0.px)
                                    }
                                    +(pass.name ?: "Untitled Pass")
                                }
                                span {
                                    css {
                                        background = Color("#2563eb")
                                        color = Color("#ffffff")
                                        fontSize = 12.px
                                        fontWeight = FontWeight.bold
                                        padding = Padding(4.px, 10.px)
                                        borderRadius = 12.px
                                    }
                                    +"v${pass.version}"
                                }
                            }

                            p {
                                css {
                                    color = Color("#94a3b8")
                                    fontSize = 14.px
                                    margin = Margin(0.px, 0.px, 0.px, 0.px)
                                }
                                +(pass.typeName ?: "Multipaz Credential Container (.mpzpass)")
                            }

                            div {
                                css {
                                    display = Display.flex
                                    flexWrap = FlexWrap.wrap
                                    gap = 16.px
                                    marginTop = 8.px
                                    fontSize = 13.px
                                }
                                div {
                                    span { css { color = Color("#64748b"); marginRight = 6.px }; +"ID:" }
                                    code { css { color = Color("#38bdf8") }; +pass.uniqueId }
                                }
                                pass.updateUrl?.let { url ->
                                    div {
                                        span { css { color = Color("#64748b"); marginRight = 6.px }; +"Update URL:" }
                                        code { css { color = Color("#a78bfa") }; +url }
                                    }
                                }
                                div {
                                    span { css { color = Color("#64748b"); marginRight = 6.px }; +"Size:" }
                                    span { css { color = Color("#cbd5e1") }; +"$rawInputSize bytes" }
                                }
                            }
                        }

                        pass.cardArt?.let { art ->
                            div {
                                css {
                                    display = Display.flex
                                    flexDirection = FlexDirection.column
                                    alignItems = AlignItems.center
                                    gap = 6.px
                                }
                                img {
                                    src = "data:image/png;base64,${art.toByteArray().toBase64()}"
                                    alt = "Pass Card Art"
                                    css {
                                        width = 160.px
                                        height = 100.px
                                        borderRadius = 10.px
                                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                                        objectFit = ObjectFit.cover
                                        boxShadow = BoxShadow(0.px, 4.px, 12.px, Color("rgba(0, 0, 0, 0.4)"))
                                    }
                                }
                                span {
                                    css {
                                        fontSize = 11.px
                                        color = Color("#64748b")
                                        fontWeight = FontWeight.bold
                                    }
                                    +"CARD ART"
                                }
                            }
                        }
                    }

                    // Navigation Tabs
                    div {
                        css {
                            display = Display.flex
                            gap = 8.px
                            borderBottom = Border(1.px, LineStyle.solid, Color("#334155"))
                            marginBottom = 24.px
                            paddingBottom = 4.px
                        }

                        val tabs = listOf(
                            "overview" to "🪪 Overview & Payload",
                            "isoMdoc" to "📄 ISO mDocs (${pass.isoMdoc.size})",
                            "sdJwtVc" to "🔐 SD-JWT VCs (${pass.sdJwtVc.size})",
                            "cborCdn" to "🌳 CBOR / CDN Structure"
                        )

                        for ((tabId, tabTitle) in tabs) {
                            val isActive = activeTab == tabId
                            button {
                                css {
                                    padding = Padding(10.px, 18.px)
                                    fontSize = 14.px
                                    fontWeight = FontWeight.bold
                                    border = None.none
                                    borderRadius = 8.px
                                    cursor = Cursor.pointer
                                    transition = "all 0.2s".unsafeCast<Transition>()
                                    if (isActive) {
                                        background = Color("#2563eb")
                                        color = Color("#ffffff")
                                    } else {
                                        background = Color("transparent")
                                        color = Color("#94a3b8")
                                        hover {
                                            background = Color("#334155")
                                            color = Color("#f1f5f9")
                                        }
                                    }
                                }
                                onClick = { activeTab = tabId }
                                +tabTitle
                            }
                        }
                    }

                    // Tab 1: Overview & Payload
                    if (activeTab == "overview") {
                        div {
                            css {
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 20.px
                            }

                            div {
                                css {
                                    background = Color("#0f172a")
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    borderRadius = 12.px
                                    padding = 20.px
                                }
                                h4 {
                                    css {
                                        fontSize = 1.1.rem
                                        color = Color("#cbd5e1")
                                        margin = Margin(0.px, 0.px, 12.px, 0.px)
                                    }
                                    +"Pass Metadata Summary"
                                }
                                table {
                                    css {
                                        width = 100.pct
                                        borderCollapse = BorderCollapse.collapse
                                        fontSize = 14.px
                                    }
                                    tbody {
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold; width = 180.px }; +"Name" }
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#f1f5f9") }; +(pass.name ?: "N/A") }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Type Name" }
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#f1f5f9") }; +(pass.typeName ?: "N/A") }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Unique Identifier" }
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#38bdf8") }; +pass.uniqueId }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Version" }
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#f1f5f9") }; +"${pass.version}" }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Update URL" }
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#a78bfa") }; +(pass.updateUrl ?: "None") }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"User Auth Required" }
                                            td { css { padding = Padding(8.px, 12.px); color = if (pass.userAuthenticationRequired) Color("#ef4444") else Color("#94a3b8") }; +"${pass.userAuthenticationRequired}" }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Reader Identifiers" }
                                            td {
                                                css { padding = Padding(8.px, 12.px) }
                                                if (pass.readerIdentifiers.isEmpty()) {
                                                    span { css { color = Color("#94a3b8") }; +"None (accessible to all readers)" }
                                                } else {
                                                    div {
                                                        css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                                        pass.readerIdentifiers.forEach { aki ->
                                                            val hex = aki.toHex()
                                                            val known = KNOWN_READERS.find { it.akiHex.equals(hex, ignoreCase = true) }
                                                            div {
                                                                if (known != null) {
                                                                    span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; marginRight = 6.px }; +"${known.name}: " }
                                                                }
                                                                code { css { color = Color("#a78bfa"); fontSize = 12.px }; +hex }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Shareable" }
                                            td {
                                                css { padding = Padding(8.px, 12.px) }
                                                if (pass.shareable) {
                                                    span { css { color = Color("#22c55e"); fontWeight = FontWeight.bold }; +"Yes (Sharing allowed)" }
                                                } else {
                                                    span { css { color = Color("#94a3b8") }; +"No (Sharing not allowed)" }
                                                }
                                            }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Pass Signature" }
                                            td {
                                                css { padding = Padding(8.px, 12.px) }
                                                val certChain = pass.issuerCertificateChain
                                                if (certChain == null) {
                                                    span { css { color = Color("#94a3b8") }; +"Unsigned (No container signature)" }
                                                } else {
                                                    val leaf = certChain.certificates.first()
                                                    div {
                                                        css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                                        div {
                                                            span { css { color = Color("#22c55e"); fontWeight = FontWeight.bold; marginRight = 6.px }; +"✓ Signed & Verified" }
                                                        }
                                                        div {
                                                            span { css { color = Color("#64748b"); marginRight = 4.px }; +"Subject DN:" }
                                                            span { css { color = Color("#f1f5f9"); fontWeight = FontWeight.bold }; +leaf.subject.name }
                                                        }
                                                        div {
                                                            span { css { color = Color("#64748b"); marginRight = 4.px }; +"Issuer DN:" }
                                                            span { css { color = Color("#cbd5e1") }; +leaf.issuer.name }
                                                        }
                                                        div {
                                                            span { css { color = Color("#64748b"); marginRight = 4.px }; +"Certificate Count:" }
                                                            span { css { color = Color("#38bdf8") }; +"${certChain.certificates.size}" }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"ISO mDoc Count" }
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#38bdf8") }; +"${pass.isoMdoc.size}" }
                                        }
                                        tr {
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#64748b"); fontWeight = FontWeight.bold }; +"SD-JWT VC Count" }
                                            td { css { padding = Padding(8.px, 12.px); color = Color("#38bdf8") }; +"${pass.sdJwtVc.size}" }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tab 2: ISO mDocs
                    if (activeTab == "isoMdoc") {
                        if (pass.isoMdoc.isEmpty()) {
                            div {
                                css { color = Color("#94a3b8"); padding = 24.px; textAlign = TextAlign.center }
                                +"No ISO mDoc credentials found in this MpzPass."
                            }
                        } else {
                            div {
                                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 24.px }
                                pass.isoMdoc.forEachIndexed { index, mDoc ->
                                    renderIsoMdocSection(index, mDoc, copiedCertIndex) { idx, pem ->
                                        window.navigator.clipboard.writeText(pem)
                                        copiedCertIndex = idx
                                        window.setTimeout({ copiedCertIndex = null }, 1500)
                                    }
                                }
                            }
                        }
                    }

                    // Tab 3: SD-JWT VCs
                    if (activeTab == "sdJwtVc") {
                        if (parsedSdJwtItems.isEmpty()) {
                            div {
                                css { color = Color("#94a3b8"); padding = 24.px; textAlign = TextAlign.center }
                                +"No SD-JWT VC credentials found in this MpzPass."
                            }
                        } else {
                            div {
                                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 24.px }
                                parsedSdJwtItems.forEachIndexed { index, item ->
                                    renderSdJwtVcSection(index, item)
                                }
                            }
                        }
                    }

                    // Tab 4: CBOR / CDN
                    if (activeTab == "cborCdn") {
                        div {
                            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 32.px }

                            // Section 1: Top-Level Container CBOR
                            div {
                                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 12.px }

                                div {
                                    css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center }
                                    div {
                                        label {
                                            css { fontWeight = FontWeight.bold; color = Color("#cbd5e1"); fontSize = 1.1.rem }
                                            +"Top-Level Pass Container (MpzPass):"
                                        }
                                        p {
                                            css { color = Color("#94a3b8"); fontSize = 12.px; margin = Margin(4.px, 0.px, 0.px, 0.px) }
                                            +"Outer CBOR container with compressed payload and optional signature."
                                        }
                                    }
                                    parsedDataItem?.let { item ->
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
                                                val cdnText = Cdn.encode(item, CdnGeneratorOptions(prettyPrint = true))
                                                window.navigator.clipboard.writeText(cdnText)
                                                copyTopLevelCdnStatus = "Copied!"
                                                window.setTimeout({ copyTopLevelCdnStatus = "" }, 2000)
                                            }
                                            +(copyTopLevelCdnStatus.ifEmpty { "📋 Copy Container CDN" })
                                        }
                                    }
                                }

                                parsedDataItem?.let { item ->
                                    val cdnText = try {
                                        Cdn.encode(item, CdnGeneratorOptions(prettyPrint = true))
                                    } catch (e: Throwable) {
                                        "Error generating CDN representation: ${e.message}"
                                    }
                                    CborDiagnosticViewer {
                                        diagText = cdnText
                                        maxHeight = 400.px
                                    }
                                }
                            }

                            // Section 2: Decompressed Credential Data CBOR
                            div {
                                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 12.px }

                                div {
                                    css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center }
                                    div {
                                        label {
                                            css { fontWeight = FontWeight.bold; color = Color("#cbd5e1"); fontSize = 1.1.rem }
                                            +"Decompressed Credential Data (CredentialData):"
                                        }
                                        p {
                                            css { color = Color("#94a3b8"); fontSize = 12.px; margin = Margin(4.px, 0.px, 0.px, 0.px) }
                                            +"Decoded from the DEFLATE-decompressed payload bytes."
                                        }
                                    }
                                    decompressedDataItem?.let { item ->
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
                                                val cdnText = Cdn.encode(item, CdnGeneratorOptions(prettyPrint = true))
                                                window.navigator.clipboard.writeText(cdnText)
                                                copyDecompressedCdnStatus = "Copied!"
                                                window.setTimeout({ copyDecompressedCdnStatus = "" }, 2000)
                                            }
                                            +(copyDecompressedCdnStatus.ifEmpty { "📋 Copy Decompressed CDN" })
                                        }
                                    }
                                }

                                decompressedDataItem?.let { item ->
                                    val cdnText = try {
                                        Cdn.encode(item, CdnGeneratorOptions(prettyPrint = true))
                                    } catch (e: Throwable) {
                                        "Error generating CDN representation: ${e.message}"
                                    }
                                    CborDiagnosticViewer {
                                        diagText = cdnText
                                        maxHeight = 600.px
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
                +"Paste binary hex/base64 data or upload a `.mpzpass` file to decode and inspect credentials."
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
                        +"MpzPass Data (Hex, Base64 or Base64Url):"
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
                                    parsedPass = null
                                    parsedDataItem = null
                                    parsedSdJwtItems = emptyList()
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
                            +"📁 Load .mpzpass file"
                            input {
                                type = "file".unsafeCast<InputType>()
                                accept = ".mpzpass,application/vnd.multipaz.mpzpass"
                                css { display = None.none }
                                onChange = { event ->
                                    val fileList = event.target.asDynamic().files
                                    if (fileList != null && fileList.length > 0) {
                                        val file = fileList[0].unsafeCast<File>()
                                        val reader = FileReader()
                                        reader.asDynamic().onload = {
                                            val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                            val bytes = Int8Array(arrayBuffer).toByteArray()
                                            rawInput = bytes.toHex()
                                            decodeMpzPassBytes(bytes, bytes.toHex())
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
                        padding = 12.px
                        borderRadius = 8.px
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        backgroundColor = Color("#0f172a")
                        color = Color("#f8fafc")
                        fontFamily = "monospace".unsafeCast<FontFamily>()
                        fontSize = 13.px
                        resize = Resize.vertical
                        boxSizing = BoxSizing.borderBox
                        focus {
                            outline = None.none
                            borderColor = Color("#3b82f6")
                        }
                    }
                    value = rawInput
                    placeholder = "Paste raw .mpzpass hex or base64 string here..."
                    onChange = { event ->
                        rawInput = event.target.asDynamic().value.unsafeCast<String>()
                    }
                }

                DetectedInputBadge { input = rawInput }

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
                        marginTop = 16.px
                        transition = "all 0.2s".unsafeCast<Transition>()
                        hover {
                            backgroundColor = Color("#2563eb")
                        }
                        disabled {
                            backgroundColor = Color("#475569")
                            cursor = Cursor.notAllowed
                        }
                    }
                    disabled = rawInput.trim().isEmpty() || isLoading
                    onClick = {
                        parseInput(rawInput)
                    }
                    if (isLoading) +"Decoding..." else +"Decode MpzPass"
                }
            }
        }
    }
}

private fun ChildrenBuilder.renderIsoMdocSection(
    index: Int,
    mDoc: MpzPassIsoMdoc,
    copiedCertIndex: Int?,
    onCopyCert: (Int, String) -> Unit
) {
    div {
        css {
            background = Color("#0f172a")
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            borderRadius = 12.px
            padding = 24.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 16.px
            }
            h4 {
                css { fontSize = 1.2.rem; color = Color("#38bdf8"); margin = Margin(0.px, 0.px, 0.px, 0.px) }
                +"ISO mDoc #${index + 1}: ${mDoc.docType}"
            }
            span {
                css { background = Color("#1e293b"); padding = Padding(4.px, 12.px); borderRadius = 6.px; color = Color("#94a3b8"); fontSize = 12.px }
                +"docType: ${mDoc.docType}"
            }
        }

        // Private Key Card with PEM / JWK tabs
        KeyViewComponent {
            title = "🔑 Device Private Key"
            privateKey = mDoc.deviceKeyPrivate
        }

        // Shared IssuerSigned Details (IssuerAuth cert chain + IssuerNamespaces table + MSO details)
        val mso = try {
            val payload = mDoc.issuerAuth.payload ?: error("No payload in issuerAuth")
            val decodedPayload = Cbor.decode(payload)
            val msoBytes = if (decodedPayload.majorType == MajorType.TAG) {
                decodedPayload.asTagged.asBstr
            } else {
                decodedPayload.asBstr
            }
            MobileSecurityObject.fromDataItem(Cbor.decode(msoBytes))
        } catch (e: Throwable) {
            null
        }

        val issuerSignedData = ParsedResult.IssuerSignedData(
            namespaces = mDoc.issuerNamespaces,
            mso = mso,
            issuerAuth = mDoc.issuerAuth
        )

        renderIssuerSignedDetails(
            result = issuerSignedData,
            copiedCertIndex = copiedCertIndex,
            onCopyCert = onCopyCert
        )
    }
}

private fun ChildrenBuilder.renderSdJwtVcSection(index: Int, item: ParsedSdJwtVcItem) {
    div {
        css {
            background = Color("#0f172a")
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            borderRadius = 12.px
            padding = 24.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 16.px
            }
            h4 {
                css { fontSize = 1.2.rem; color = Color("#8b5cf6"); margin = Margin(0.px, 0.px, 0.px, 0.px) }
                +"SD-JWT VC #${index + 1}: ${item.sdJwtVc.vct}"
            }
            span {
                css {
                    background = if (item.sdJwtVc.deviceKeyPrivate != null) Color("#1e3a8a") else Color("#334155")
                    color = if (item.sdJwtVc.deviceKeyPrivate != null) Color("#93c5fd") else Color("#cbd5e1")
                    padding = Padding(4.px, 10.px)
                    borderRadius = 6.px
                    fontSize = 12.px
                    fontWeight = FontWeight.bold
                }
                +if (item.sdJwtVc.deviceKeyPrivate != null) "🔒 Key-Bound" else "🔓 Keyless"
            }
        }

        // Private Key Card with PEM / JWK tabs if key-bound
        item.sdJwtVc.deviceKeyPrivate?.let { privKey ->
            KeyViewComponent {
                title = "🔑 Key-Binding Private Key"
                privateKey = privKey
            }
        }

        // Shared SD-JWT Details (JWT Header, Claims, Processed Payload, Disclosures Table with Images, KB-JWT)
        if (item.parsedSdJwt != null) {
            renderSdJwtDetails(
                sdjwt = item.parsedSdJwt,
                parsedSdJwtKb = item.parsedSdJwtKb,
                processedPayload = item.processedPayload
            )
        } else {
            // Fallback preview of compact string if parsing fails
            div {
                css {
                    background = Color("#1e293b")
                    borderRadius = 8.px
                    padding = 16.px
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                }
                div {
                    css { fontWeight = FontWeight.bold; color = Color("#f1f5f9"); marginBottom = 8.px }
                    +"📜 Compact Serialization String"
                }
                pre {
                    css {
                        background = Color("#0f172a")
                        padding = 12.px
                        borderRadius = 6.px
                        fontSize = 12.px
                        color = Color("#cbd5e1")
                        whiteSpace = WhiteSpace.preWrap
                        wordBreak = WordBreak.breakAll
                        maxHeight = 200.px
                        asDynamic().overflow = "auto"
                    }
                    +item.sdJwtVc.compactSerialization
                }
            }
        }
    }
}
