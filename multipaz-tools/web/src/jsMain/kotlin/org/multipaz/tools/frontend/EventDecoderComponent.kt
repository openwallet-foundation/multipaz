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
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventPresentment
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsMdocApi
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsOpenID4VP
import org.multipaz.eventlogger.EventPresentmentIso18013AnnexA
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.eventlogger.EventPresentmentUriSchemeOpenID4VP
import org.multipaz.eventlogger.EventProvisioning
import org.multipaz.eventlogger.EventProvisioningCredentialData
import org.multipaz.eventlogger.EventProvisioningIssuerDataOpenID4VCI
import org.multipaz.eventlogger.EventSimple
import org.multipaz.eventlogger.EventVerification
import org.multipaz.eventlogger.fromDataItem
import org.multipaz.eventlogger.toDataItem
import org.multipaz.provisioning.Display as ProvisioningDisplay
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.toBase64
import org.multipaz.util.toHex
import org.multipaz.verification.Iso18013PresentmentRecord
import org.multipaz.verification.OpenID4VPPresentmentRecord
import org.multipaz.verification.VerifiedPresentation
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
import react.useState
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.InputType

private class EventHolder(val event: Event)

private fun parseEventFromBytes(bytes: ByteArray): Pair<Event?, String> {
    if (bytes.isEmpty()) return Pair(null, "Input is empty")
    return try {
        val dataItem = try {
            Cbor.decode(bytes)
        } catch (e: Throwable) {
            val text = bytes.decodeToString()
            val decodedBytes = decodeInputToBytes(text)
            Cbor.decode(decodedBytes)
        }
        Pair(Event.fromDataItem(dataItem), "")
    } catch (e: Throwable) {
        Pair(null, "Error decoding Multipaz Event: " + (e.message ?: "Invalid event structure"))
    }
}

val EventDecoderComponent: FC<Props> = FC {
    var rawInput by useState("")
    var parsedEventHolder by useState<EventHolder?>(null)
    var parseError by useState("")
    var fileName by useState("")
    var copyEventHexStatus by useState("")

    val parsedEvent = parsedEventHolder?.event

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
            +"Multipaz Event Decoder"
        }

        if (parsedEvent != null || parseError.isNotEmpty()) {
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
                        backgroundColor = Color("#334155")
                        color = Color("#f1f5f9")
                        border = None.none
                        borderRadius = 8.px
                        cursor = Cursor.pointer
                        hover {
                            backgroundColor = Color("#475569")
                        }
                    }
                    onClick = {
                        parsedEventHolder = null
                        parseError = ""
                        fileName = ""
                    }
                    +"← Clear and Decode Another Event"
                }

                if (parsedEvent != null) {
                    button {
                        css {
                            padding = Padding(10.px, 20.px)
                            fontSize = 14.px
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
                        onClick = {
                            val hex = Cbor.encode(parsedEvent.toDataItem()).toHex()
                            window.navigator.asDynamic().clipboard.writeText(hex)
                            copyEventHexStatus = "Copied Hex!"
                            window.setTimeout({ copyEventHexStatus = "" }, 2000)
                        }
                        +if (copyEventHexStatus.isNotEmpty()) copyEventHexStatus else "📋 Copy Event Hex"
                    }
                }
            }

            if (parseError.isNotEmpty()) {
                div {
                    css {
                        marginTop = 24.px
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
            p {
                css {
                    color = Color("#94a3b8")
                    marginBottom = 24.px
                }
                +"Decode Multipaz Event structures (`.mpzevent` files or Hex / Base64 encoded CBOR payloads). Supports Presentment, Provisioning, Verification, and Simple events."
            }

            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +"Upload .mpzevent file or paste Hex / Base64 data:"
            }

            div {
                css {
                    display = Display.flex
                    gap = 16.px
                    alignItems = AlignItems.center
                    marginBottom = 16.px
                }

                label {
                    css {
                        display = Display.inlineFlex
                        alignItems = AlignItems.center
                        gap = 8.px
                        padding = Padding(10.px, 20.px)
                        background = Color("#334155")
                        color = Color("#f1f5f9")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 8.px
                        cursor = Cursor.pointer
                        fontWeight = FontWeight.bold
                        fontSize = 14.px
                        hover {
                            background = Color("#475569")
                        }
                    }
                    +"📁 Choose File (.mpzevent)"
                    input {
                        type = "file".unsafeCast<InputType>()
                        accept = ".mpzevent,*/*"
                        css {
                            display = None.none
                        }
                        onChange = { event ->
                            val fileList = event.target.asDynamic().files
                            if (fileList != null && fileList.length > 0) {
                                val file = fileList[0].unsafeCast<File>()
                                val name = file.name
                                val reader = FileReader()
                                reader.asDynamic().onload = {
                                    val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                    val bytes = Int8Array(arrayBuffer).toByteArray()
                                    val res = parseEventFromBytes(bytes)
                                    parsedEventHolder = res.first?.let { EventHolder(it) }
                                    parseError = res.second
                                    fileName = name
                                }
                                reader.readAsArrayBuffer(file)
                            }
                        }
                    }
                }

                if (fileName.isNotEmpty()) {
                    span {
                        css {
                            color = Color("#38bdf8")
                            fontSize = 14.px
                            fontWeight = FontWeight.bold
                        }
                        +"Loaded: $fileName"
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
                    marginBottom = 16.px
                    focus {
                        outline = None.none
                        borderColor = Color("#3b82f6")
                    }
                }
                value = rawInput
                placeholder = "Paste Event Hex (e.g. A36A...) or Base64 / Base64Url string here..."
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
                            val bytes = decodeInputToBytes(rawInput)
                            val res = parseEventFromBytes(bytes)
                            parsedEventHolder = res.first?.let { EventHolder(it) }
                            parseError = res.second
                            fileName = "Pasted Input"
                        } catch (e: Throwable) {
                            parseError = "Error reading input: " + (e.message ?: "Invalid format")
                            parsedEventHolder = null
                        }
                    }
                }
                +"Decode Event"
            }
        }

        val ev = parsedEvent
        if (ev != null) {
            div {
                css {
                    marginTop = 32.px
                }

                renderEventHeaderSection(ev, fileName)

                when (ev) {
                    is EventPresentment -> renderEventPresentmentSection(ev)
                    is EventProvisioning -> renderEventProvisioningSection(ev)
                    is EventVerification -> renderEventVerificationSection(ev)
                    is EventSimple -> renderEventSimpleSection(ev)
                }

                if (ev.appData.isNotEmpty()) {
                    renderEventAppDataSection(ev.appData)
                }
            }
        }
    }
}

private fun react.ChildrenBuilder.renderEventHeaderSection(ev: Event, fileName: String) {
    val eventTypeColor = when (ev) {
        is EventPresentment -> Color("#0284c7")
        is EventProvisioning -> Color("#16a34a")
        is EventVerification -> Color("#9333ea")
        is EventSimple -> Color("#d97706")
    }
    val eventTypeName = when (ev) {
        is EventPresentmentDigitalCredentialsMdocApi -> "Presentment (W3C DC API mdoc)"
        is EventPresentmentDigitalCredentialsOpenID4VP -> "Presentment (W3C DC API OpenID4VP)"
        is EventPresentmentUriSchemeOpenID4VP -> "Presentment (URI OpenID4VP)"
        is EventPresentmentIso18013AnnexA -> "Presentment (ISO 18013-7 Annex A)"
        is EventPresentmentIso18013Proximity -> "Presentment (ISO 18013-5 Proximity)"
        is EventPresentment -> "Presentment Event"
        is EventProvisioning -> "Provisioning Event"
        is EventVerification -> "Verification Event"
        is EventSimple -> "Simple Event"
    }
    val idText = if (ev.identifier.isEmpty()) "N/A" else ev.identifier

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

            span {
                css {
                    background = eventTypeColor
                    color = Color("#ffffff")
                    padding = Padding(4.px, 12.px)
                    borderRadius = 16.px
                    fontSize = 12.px
                    fontWeight = FontWeight.bold
                    textTransform = TextTransform.uppercase
                }
                +eventTypeName
            }

            span {
                css {
                    color = Color("#94a3b8")
                    fontSize = 14.px
                }
                +"ID: $idText"
            }
        }

        div {
            css {
                display = Display.flex
                gap = 32.px
            }

            div {
                span { css { color = Color("#64748b"); fontSize = 12.px; display = Display.block; fontWeight = FontWeight.bold }; +"TIMESTAMP" }
                span { css { color = Color("#f1f5f9"); fontSize = 15.px; fontWeight = FontWeight.bold }; +ev.timestamp.toString() }
            }

            if (fileName.isNotEmpty()) {
                div {
                    span { css { color = Color("#64748b"); fontSize = 12.px; display = Display.block; fontWeight = FontWeight.bold }; +"SOURCE" }
                    span { css { color = Color("#38bdf8"); fontSize = 15.px; fontWeight = FontWeight.bold }; +fileName }
                }
            }
        }
    }
}

external interface CertCardProps : Props {
    var index: Int
    var cert: X509Cert
}

private val CertCard: FC<CertCardProps> = FC { props ->
    var copyPemStatus by useState("")
    val cert = props.cert

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 16.px
            marginBottom = 12.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 12.px
            }

            span {
                css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 14.px }
                +"Certificate #${props.index + 1}"
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
                    window.navigator.asDynamic().clipboard.writeText(cert.toPem())
                    copyPemStatus = "Copied PEM!"
                    window.setTimeout({ copyPemStatus = "" }, 2000)
                }
                +if (copyPemStatus.isNotEmpty()) copyPemStatus else "📋 Copy PEM"
            }
        }

        div {
            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px; fontSize = 13.px }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Subject: " }
                span { css { color = Color("#f1f5f9"); wordBreak = WordBreak.breakAll }; +cert.subject.name }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Issuer: " }
                span { css { color = Color("#f1f5f9"); wordBreak = WordBreak.breakAll }; +cert.issuer.name }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Serial Number: " }
                span { css { color = Color("#38bdf8"); fontFamily = FontFamily.monospace }; +cert.serialNumber.value.toHex() }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Validity: " }
                span { css { color = Color("#cbd5e1") }; +"${cert.validityNotBefore} to ${cert.validityNotAfter}" }
            }
        }
    }
}

external interface CborDataBlockProps : Props {
    var title: String
    var dataItem: DataItem
}

private val CborDataBlock: FC<CborDataBlockProps> = FC { props ->
    var copyHexStatus by useState("")
    val encodedBytes = Cbor.encode(props.dataItem)
    val hexString = encodedBytes.toHex()
    val diagText = Cbor.toDiagnostics(props.dataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 16.px
            marginBottom = 16.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 10.px
            }

            span {
                css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 14.px }
                +"${props.title} (${encodedBytes.size} bytes)"
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
                    window.navigator.asDynamic().clipboard.writeText(hexString)
                    copyHexStatus = "Copied Hex!"
                    window.setTimeout({ copyHexStatus = "" }, 2000)
                }
                +if (copyHexStatus.isNotEmpty()) copyHexStatus else "📋 Copy Hex"
            }
        }

        CborDiagnosticViewer {
            this.diagText = diagText
            this.maxHeight = 250.px
        }
    }
}

external interface TextDataBlockProps : Props {
    var title: String
    var content: String
    var copyLabel: String?
}

private val TextDataBlock: FC<TextDataBlockProps> = FC { props ->
    var copyStatus by useState("")

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 16.px
            marginBottom = 16.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 10.px
            }

            span {
                css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 14.px }
                +props.title
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
                    window.navigator.asDynamic().clipboard.writeText(props.content)
                    copyStatus = "Copied!"
                    window.setTimeout({ copyStatus = "" }, 2000)
                }
                +if (copyStatus.isNotEmpty()) copyStatus else (props.copyLabel ?: "📋 Copy Text")
            }
        }

        pre {
            css {
                background = Color("#020617")
                border = Border(1.px, LineStyle.solid, Color("#334155"))
                borderRadius = 6.px
                padding = 12.px
                overflow = "auto".unsafeCast<Overflow>()
                maxHeight = 250.px
                margin = 0.px
            }
            code {
                css {
                    fontFamily = FontFamily.monospace
                    fontSize = 13.px
                    color = Color("#cbd5e1")
                    display = Display.block
                    width = "max-content".unsafeCast<Width>()
                    minWidth = 100.pct
                    whiteSpace = "pre".unsafeCast<WhiteSpace>()
                }
                +props.content
            }
        }
    }
}

private fun react.ChildrenBuilder.renderTrustMetadataCard(trustMetadata: TrustMetadata) {
    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 16.px
            marginBottom = 16.px
        }

        h3 {
            css { color = Color("#38bdf8"); fontSize = 1.0.rem; marginTop = 0.px; marginBottom = 12.px }
            +"Trust Metadata"
        }

        div {
            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 8.px; fontSize = 13.px }

            if (trustMetadata.displayName != null) {
                div {
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Display Name: " }
                    span { css { color = Color("#f1f5f9"); fontWeight = FontWeight.bold }; +trustMetadata.displayName!! }
                }
            }

            if (trustMetadata.displayIconUrl != null) {
                div {
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Display Icon URL: " }
                    span { css { color = Color("#38bdf8"); fontFamily = FontFamily.monospace; wordBreak = WordBreak.breakAll }; +trustMetadata.displayIconUrl!! }
                }
            }

            if (trustMetadata.privacyPolicyUrl != null) {
                div {
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Privacy Policy URL: " }
                    span { css { color = Color("#38bdf8"); fontFamily = FontFamily.monospace; wordBreak = WordBreak.breakAll }; +trustMetadata.privacyPolicyUrl!! }
                }
            }

            if (trustMetadata.disclaimer != null) {
                div {
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Disclaimer: " }
                    span { css { color = Color("#cbd5e1") }; +trustMetadata.disclaimer!! }
                }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Test Only: " }
                span {
                    css { color = if (trustMetadata.testOnly) Color("#fbbf24") else Color("#4ade80"); fontWeight = FontWeight.bold }
                    +if (trustMetadata.testOnly) "Yes (Test Entity / VICAL)" else "No (Production Entity)"
                }
            }

            if (trustMetadata.extensions.isNotEmpty()) {
                div {
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 4.px }; +"Extensions:" }
                    trustMetadata.extensions.forEach { (extKey, extValue) ->
                        div {
                            css { marginLeft = 12.px }
                            span { css { color = Color("#cbd5e1"); fontFamily = FontFamily.monospace }; +"$extKey: $extValue" }
                        }
                    }
                }
            }
        }
    }
}

private fun react.ChildrenBuilder.renderEventPresentmentSection(ev: EventPresentment) {
    val presentmentData = ev.presentmentData
    val protocolName = when (ev) {
        is EventPresentmentDigitalCredentialsMdocApi -> "W3C Digital Credentials API (ISO 18013-7 Annex C)"
        is EventPresentmentDigitalCredentialsOpenID4VP -> "W3C Digital Credentials API (OpenID4VP)"
        is EventPresentmentUriSchemeOpenID4VP -> "URI Scheme (OpenID4VP)"
        is EventPresentmentIso18013AnnexA -> "URI Scheme (ISO 18013-7 Annex A)"
        is EventPresentmentIso18013Proximity -> "Proximity (ISO 18013-5)"
    }

    div {
        css {
            background = Color("#0f172a")
            borderRadius = 12.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 24.px
            marginBottom = 24.px
        }

        h3 {
            css { color = Color("#38bdf8"); fontSize = 1.2.rem; marginTop = 0.px; marginBottom = 16.px }
            +"Presentment Details"
        }

        div {
            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 12.px; marginBottom = 16.px }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Protocol: " }
                span { css { color = Color("#f1f5f9"); fontWeight = FontWeight.bold }; +protocolName }
            }

            if (presentmentData.requesterName != null) {
                div {
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Requester Name: " }
                    span { css { color = Color("#f1f5f9") }; +presentmentData.requesterName!! }
                }
            }
        }

        // Render TrustMetadata if present
        if (presentmentData.trustMetadata != null) {
            renderTrustMetadataCard(presentmentData.trustMetadata!!)
        }

        // Render Requester Certificate Chain with Copy PEM buttons
        if (presentmentData.requesterCertChain != null) {
            val certs = presentmentData.requesterCertChain!!.certificates
            h3 {
                css { color = Color("#e2e8f0"); fontSize = 1.0.rem; marginTop = 20.px; marginBottom = 12.px }
                +"Requester Certificate Chain (${certs.size} certs)"
            }

            certs.forEachIndexed { index, cert ->
                CertCard {
                    this.index = index
                    this.cert = cert
                }
            }
        }

        // Render Requested Documents
        if (presentmentData.requestedDocuments.isNotEmpty()) {
            h3 {
                css { color = Color("#e2e8f0"); fontSize = 1.0.rem; marginTop = 20.px; marginBottom = 12.px }
                +"Requested Documents (${presentmentData.requestedDocuments.size})"
            }

            presentmentData.requestedDocuments.forEach { docReq ->
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
                        +"Document: ${docReq.documentName ?: docReq.documentId}"
                    }

                    if (docReq.claims.isNotEmpty()) {
                        table {
                            css { width = 100.pct; borderCollapse = BorderCollapse.collapse }
                            thead {
                                tr {
                                    th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Namespace / ID" }
                                    th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Data Element" }
                                    th { css { color = Color("#94a3b8"); textAlign = TextAlign.center; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Intent to Retain" }
                                }
                            }
                            tbody {
                                docReq.claims.forEach { (requestedClaim, claim) ->
                                    val ns = if (requestedClaim is MdocRequestedClaim) requestedClaim.namespaceName else (requestedClaim.id ?: "")
                                    val element = if (requestedClaim is MdocRequestedClaim) requestedClaim.dataElementName else claim.displayName
                                    val retain = if (requestedClaim is MdocRequestedClaim) requestedClaim.intentToRetain else false

                                    tr {
                                        td { css { color = Color("#cbd5e1"); padding = 8.px; fontSize = 13.px }; +ns }
                                        td { css { color = Color("#f1f5f9"); padding = 8.px; fontSize = 13.px; fontWeight = FontWeight.bold }; +element }
                                        td {
                                            css { textAlign = TextAlign.center; padding = 8.px; fontSize = 13.px }
                                            span {
                                                css {
                                                    color = if (retain) Color("#f87171") else Color("#4ade80")
                                                    fontWeight = FontWeight.bold
                                                }
                                                +if (retain) "Yes" else "No"
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

        // Render Subclass Specific Raw Data with Copy buttons
        h3 {
            css { color = Color("#e2e8f0"); fontSize = 1.0.rem; marginTop = 24.px; marginBottom = 16.px }
            +"Raw Presentment Payloads & Data"
        }

        when (ev) {
            is EventPresentmentDigitalCredentialsMdocApi -> {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px; marginBottom = 12.px; fontSize = 13.px }
                    if (ev.appId != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"App ID: " }; span { css { color = Color("#f1f5f9") }; +ev.appId!! } }
                    }
                    div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Origin: " }; span { css { color = Color("#38bdf8") }; +ev.origin } }
                    div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Protocol: " }; span { css { color = Color("#f1f5f9") }; +ev.protocol } }
                }

                TextDataBlock {
                    title = "Request JSON"
                    content = ev.requestJson
                    copyLabel = "📋 Copy Request JSON"
                }

                TextDataBlock {
                    title = "Response JSON"
                    content = ev.responseJson
                    copyLabel = "📋 Copy Response JSON"
                }

                CborDataBlock {
                    title = "Device Response (CBOR)"
                    dataItem = ev.deviceResponse
                }
            }

            is EventPresentmentDigitalCredentialsOpenID4VP -> {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px; marginBottom = 12.px; fontSize = 13.px }
                    if (ev.appId != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"App ID: " }; span { css { color = Color("#f1f5f9") }; +ev.appId!! } }
                    }
                    div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Origin: " }; span { css { color = Color("#38bdf8") }; +ev.origin } }
                    div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Protocol: " }; span { css { color = Color("#f1f5f9") }; +ev.protocol } }
                    if (ev.state != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"State: " }; span { css { color = Color("#f1f5f9"); fontFamily = FontFamily.monospace }; +ev.state!! } }
                    }
                }

                TextDataBlock {
                    title = "Request JSON"
                    content = ev.requestJson
                    copyLabel = "📋 Copy Request JSON"
                }

                TextDataBlock {
                    title = "Response JSON"
                    content = ev.responseJson
                    copyLabel = "📋 Copy Response JSON"
                }

                TextDataBlock {
                    title = "VP Token"
                    content = ev.vpToken
                    copyLabel = "📋 Copy VP Token"
                }
            }

            is EventPresentmentUriSchemeOpenID4VP -> {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px; marginBottom = 12.px; fontSize = 13.px }
                    if (ev.appId != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"App ID: " }; span { css { color = Color("#f1f5f9") }; +ev.appId!! } }
                    }
                    if (ev.origin != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Origin: " }; span { css { color = Color("#38bdf8") }; +ev.origin!! } }
                    }
                    if (ev.redirectUri != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Redirect URI: " }; span { css { color = Color("#cbd5e1"); fontFamily = FontFamily.monospace }; +ev.redirectUri!! } }
                    }
                    if (ev.state != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"State: " }; span { css { color = Color("#f1f5f9"); fontFamily = FontFamily.monospace }; +ev.state!! } }
                    }
                }

                TextDataBlock {
                    title = "Invocation URI"
                    content = ev.uri
                    copyLabel = "📋 Copy URI"
                }

                TextDataBlock {
                    title = "Request JWT"
                    content = ev.requestJwt
                    copyLabel = "📋 Copy Request JWT"
                }

                TextDataBlock {
                    title = "VP Token"
                    content = ev.vpToken
                    copyLabel = "📋 Copy VP Token"
                }
            }

            is EventPresentmentIso18013AnnexA -> {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px; marginBottom = 12.px; fontSize = 13.px }
                    if (ev.appId != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"App ID: " }; span { css { color = Color("#f1f5f9") }; +ev.appId!! } }
                    }
                    if (ev.origin != null) {
                        div { span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Origin: " }; span { css { color = Color("#38bdf8") }; +ev.origin!! } }
                    }
                }

                TextDataBlock {
                    title = "Invocation URI"
                    content = ev.uri
                    copyLabel = "📋 Copy URI"
                }

                CborDataBlock {
                    title = "Request (CBOR)"
                    dataItem = ev.request
                }

                CborDataBlock {
                    title = "Response (CBOR)"
                    dataItem = ev.response
                }

                CborDataBlock {
                    title = "Session Transcript (CBOR)"
                    dataItem = ev.sessionTranscript
                }

                CborDataBlock {
                    title = "Reader Engagement (CBOR)"
                    dataItem = ev.readerEngagement
                }
            }

            is EventPresentmentIso18013Proximity -> {
                CborDataBlock {
                    title = "Request (CBOR)"
                    dataItem = ev.request
                }

                CborDataBlock {
                    title = "Response (CBOR)"
                    dataItem = ev.response
                }

                CborDataBlock {
                    title = "Session Transcript (CBOR)"
                    dataItem = ev.sessionTranscript
                }
            }
        }
    }
}

external interface CredentialCardProps : Props {
    var domain: String
    var index: Int
    var credentialData: EventProvisioningCredentialData
}

private val CredentialCard: FC<CredentialCardProps> = FC { props ->
    var copyHexStatus by useState("")

    val bytes = props.credentialData.issuerProvidedData.toByteArray()
    val hexString = bytes.toHex()

    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 16.px
            marginBottom = 12.px
        }

        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.spaceBetween
                alignItems = AlignItems.center
                marginBottom = 12.px
            }

            div {
                css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px }

                span {
                    css {
                        background = Color("#334155")
                        color = Color("#38bdf8")
                        padding = Padding(4.px, 10.px)
                        borderRadius = 6.px
                        fontSize = 12.px
                        fontWeight = FontWeight.bold
                        fontFamily = FontFamily.monospace
                    }
                    +props.domain
                }

                span {
                    css { color = Color("#f1f5f9"); fontWeight = FontWeight.bold; fontSize = 14.px }
                    +"Credential #${props.index + 1} (${bytes.size} bytes)"
                }
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
                    window.navigator.asDynamic().clipboard.writeText(hexString)
                    copyHexStatus = "Copied Hex!"
                    window.setTimeout({ copyHexStatus = "" }, 2000)
                }
                +if (copyHexStatus.isNotEmpty()) copyHexStatus else "📋 Copy Hex"
            }
        }

        // CBOR diagnostic preview if data is valid CBOR
        val diagPreview = try {
            val dataItem = Cbor.decode(bytes)
            Cbor.toDiagnostics(dataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        } catch (e: Throwable) {
            null
        }

        if (diagPreview != null) {
            CborDiagnosticViewer {
                diagText = diagPreview
                maxHeight = 220.px
            }
        } else {
            div {
                css {
                    background = Color("#020617")
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                    borderRadius = 6.px
                    padding = 12.px
                    fontFamily = FontFamily.monospace
                    fontSize = 12.px
                    color = Color("#94a3b8")
                    wordBreak = WordBreak.breakAll
                }
                +(if (hexString.length > 200) hexString.substring(0, 200) + "..." else hexString)
            }
        }
    }
}

private fun react.ChildrenBuilder.renderDisplayCard(title: String, disp: ProvisioningDisplay) {
    div {
        css {
            background = Color("#1e293b")
            borderRadius = 8.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 14.px
            marginTop = 8.px
            marginBottom = 12.px
        }

        div {
            css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 13.px; marginBottom = 8.px }
            +title
        }

        div {
            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 6.px }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Name: " }
                span { css { color = Color("#f1f5f9"); fontWeight = FontWeight.bold }; +disp.text }
            }

            if (disp.description != null) {
                div {
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Description: " }
                    span { css { color = Color("#cbd5e1") }; +disp.description!! }
                }
            }

            if (disp.logo != null) {
                val logoB64 = disp.logo!!.toByteArray().toBase64()
                div {
                    css { marginTop = 4.px }
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 4.px }; +"Logo Image:" }
                    img {
                        src = "data:image/png;base64,$logoB64"
                        alt = "Display Logo"
                        css {
                            maxHeight = 60.px
                            maxWidth = 200.px
                            borderRadius = 6.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            background = Color("#0f172a")
                            padding = 4.px
                        }
                    }
                }
            }

            if (disp.backgroundImage != null) {
                val bgB64 = disp.backgroundImage!!.toByteArray().toBase64()
                div {
                    css { marginTop = 4.px }
                    span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 4.px }; +"Background Image:" }
                    img {
                        src = "data:image/png;base64,$bgB64"
                        alt = "Display Background Image"
                        css {
                            maxHeight = 120.px
                            maxWidth = 280.px
                            borderRadius = 8.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            objectFit = ObjectFit.cover
                        }
                    }
                }
            }

            if (disp.textColor != null || disp.backgroundColor != null) {
                div {
                    css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px; marginTop = 4.px }

                    if (disp.backgroundColor != null) {
                        div {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px }
                            span { css { color = Color("#94a3b8"); fontSize = 12.px }; +"Background Color: " }
                            span {
                                css {
                                    display = Display.inlineBlock
                                    width = 16.px
                                    height = 16.px
                                    borderRadius = 4.px
                                    background = Color(disp.backgroundColor!!)
                                    border = Border(1.px, LineStyle.solid, Color("#64748b"))
                                }
                            }
                            span { css { color = Color("#f1f5f9"); fontFamily = FontFamily.monospace; fontSize = 12.px }; +disp.backgroundColor!! }
                        }
                    }

                    if (disp.textColor != null) {
                        div {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px }
                            span { css { color = Color("#94a3b8"); fontSize = 12.px }; +"Text Color: " }
                            span {
                                css {
                                    display = Display.inlineBlock
                                    width = 16.px
                                    height = 16.px
                                    borderRadius = 4.px
                                    background = Color(disp.textColor!!)
                                    border = Border(1.px, LineStyle.solid, Color("#64748b"))
                                }
                            }
                            span { css { color = Color("#f1f5f9"); fontFamily = FontFamily.monospace; fontSize = 12.px }; +disp.textColor!! }
                        }
                    }
                }
            }
        }
    }
}

private fun react.ChildrenBuilder.renderEventProvisioningSection(ev: EventProvisioning) {
    var numCredentials = 0
    ev.credentialsFetched.forEach { (_, credentials) -> numCredentials += credentials.size }

    div {
        css {
            background = Color("#0f172a")
            borderRadius = 12.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 24.px
            marginBottom = 24.px
        }

        h3 {
            css { color = Color("#4ade80"); fontSize = 1.2.rem; marginTop = 0.px; marginBottom = 16.px }
            +"Provisioning Details"
        }

        div {
            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 12.px }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Document Name: " }
                span { css { color = Color("#f1f5f9") }; +(ev.documentName ?: ev.documentId) }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Document ID: " }
                span { css { color = Color("#38bdf8"); fontFamily = FontFamily.monospace }; +ev.documentId }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Issuer Name: " }
                span { css { color = Color("#f1f5f9") }; +ev.issuerData.display.text }
            }

            when (val issuer = ev.issuerData) {
                is EventProvisioningIssuerDataOpenID4VCI -> {
                    div {
                        span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"OpenID4VCI Server: " }
                        span { css { color = Color("#38bdf8") }; +issuer.url }
                    }
                }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Provisioning Type: " }
                span { css { color = Color("#f1f5f9") }; +if (ev.initialProvisioning) "Initial provisioning" else "Credential refresh" }
            }

            div {
                span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Total Credentials Fetched: " }
                span { css { color = Color("#4ade80"); fontWeight = FontWeight.bold }; +"$numCredentials credential(s)" }
            }
        }

        // Render Display Information for Document and Issuer
        if (ev.display != null) {
            renderDisplayCard("Document Display Configuration", ev.display!!)
        }

        renderDisplayCard("Issuer Display Configuration", ev.issuerData.display)

        // Render Fetched Credentials List with Copy Hex buttons
        if (ev.credentialsFetched.isNotEmpty()) {
            h3 {
                css { color = Color("#e2e8f0"); fontSize = 1.0.rem; marginTop = 20.px; marginBottom = 12.px }
                +"Fetched Credentials ($numCredentials)"
            }

            ev.credentialsFetched.forEach { (domain, credentialsList) ->
                credentialsList.forEachIndexed { index, credData ->
                    CredentialCard {
                        this.domain = domain
                        this.index = index
                        this.credentialData = credData
                    }
                }
            }
        }
    }
}

external interface EventVerificationBlockProps : Props {
    var event: EventVerification
}

private val EventVerificationBlock: FC<EventVerificationBlockProps> = FC { props ->
    val ev = props.event
    var verifiedPresentations by useState<List<VerifiedPresentation>?>(null)
    var verificationError by useState("")
    var isVerifying by useState(false)

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
                css { color = Color("#c084fc"); fontSize = 1.2.rem; margin = 0.px }
                +"Verification Details (${ev.presentmentRecord::class.simpleName})"
            }

            button {
                css {
                    background = Color("#9333ea")
                    color = Color("#ffffff")
                    border = None.none
                    padding = Padding(8.px, 16.px)
                    borderRadius = 6.px
                    cursor = Cursor.pointer
                    fontSize = 13.px
                    fontWeight = FontWeight.bold
                    hover { background = Color("#7e22ce") }
                }
                onClick = {
                    isVerifying = true
                    mainScope.launch {
                        try {
                            val results = ev.presentmentRecord.verify()
                            verifiedPresentations = results
                            verificationError = ""
                        } catch (e: Throwable) {
                            verificationError = "Verification failed: " + (e.message ?: e.toString())
                            verifiedPresentations = null
                        } finally {
                            isVerifying = false
                        }
                    }
                }
                +if (isVerifying) "Verifying..." else "⚡ Verify Presentment Record"
            }
        }

        val vps = verifiedPresentations
        if (vps != null) {
            div {
                css {
                    background = Color("#14532d")
                    border = Border(1.px, LineStyle.solid, Color("#22c55e"))
                    borderRadius = 8.px
                    padding = 16.px
                    marginBottom = 20.px
                }

                div {
                    css { color = Color("#86efac"); fontWeight = FontWeight.bold; fontSize = 15.px; marginBottom = 8.px }
                    +"✅ Verification Successful (${vps.size} verified presentation(s))"
                }

                vps.forEachIndexed { index, vp ->
                    div {
                        css {
                            background = Color("#0f172a")
                            borderRadius = 8.px
                            border = Border(1.px, LineStyle.solid, Color("#166534"))
                            padding = 16.px
                            marginTop = 12.px
                        }

                        div {
                            css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; marginBottom = 8.px }
                            span { css { color = Color("#4ade80"); fontWeight = FontWeight.bold }; +"Presentation #${index + 1}" }
                            if (vp.vpTokenIdentifier != null) {
                                span { css { color = Color("#94a3b8"); fontSize = 12.px }; +"ID: ${vp.vpTokenIdentifier}" }
                            }
                        }

                        div {
                            css { display = Display.flex; gap = 20.px; fontSize = 13.px; marginBottom = 12.px }
                            span { css { color = Color("#cbd5e1") }; +"ZKP Used: "; span { css { color = if (vp.zkpUsed) Color("#4ade80") else Color("#94a3b8"); fontWeight = FontWeight.bold }; +if (vp.zkpUsed) "Yes" else "No" } }
                            if (vp.validFrom != null) span { css { color = Color("#cbd5e1") }; +"Valid From: ${vp.validFrom}" }
                            if (vp.validUntil != null) span { css { color = Color("#cbd5e1") }; +"Valid Until: ${vp.validUntil}" }
                        }

                        if (vp.issuerSignedClaims.isNotEmpty()) {
                            h4 {
                                css { color = Color("#f1f5f9"); fontSize = 13.px; marginBottom = 6.px; marginTop = 12.px }
                                +"Issuer-Signed Claims (${vp.issuerSignedClaims.size})"
                            }

                            table {
                                css { width = 100.pct; borderCollapse = BorderCollapse.collapse }
                                thead {
                                    tr {
                                        th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Claim" }
                                        th { css { color = Color("#94a3b8"); textAlign = TextAlign.left; padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")) }; +"Value" }
                                    }
                                }
                                tbody {
                                    vp.issuerSignedClaims.forEach { claim ->
                                        val claimId = when (claim) {
                                            is org.multipaz.claim.MdocClaim -> "${claim.namespaceName} / ${claim.dataElementName}"
                                            is org.multipaz.claim.JsonClaim -> claim.displayName
                                            else -> claim.displayName
                                        }
                                        tr {
                                            td { css { color = Color("#cbd5e1"); padding = 6.px; fontSize = 13.px; fontFamily = FontFamily.monospace }; +claimId }
                                            td { css { color = Color("#f1f5f9"); padding = 6.px; fontSize = 13.px; fontWeight = FontWeight.bold }; +claim.render() }
                                        }
                                    }
                                }
                            }
                        }

                        val certChain = vp.documentSignerCertChain
                        if (certChain != null && certChain.certificates.isNotEmpty()) {
                            h4 {
                                css { color = Color("#38bdf8"); fontSize = 13.px; marginBottom = 8.px; marginTop = 16.px }
                                +"Document Signer Certificate Chain"
                            }
                            certChain.certificates.forEachIndexed { certIdx, cert ->
                                CertCard {
                                    this.index = certIdx
                                    this.cert = cert
                                }
                            }
                        }
                    }
                }
            }
        }

        if (verificationError.isNotEmpty()) {
            div {
                css {
                    background = Color("#451a1a")
                    border = Border(1.px, LineStyle.solid, Color("#f87171"))
                    borderRadius = 8.px
                    padding = 16.px
                    marginBottom = 20.px
                    color = Color("#fca5a5")
                    fontWeight = FontWeight.bold
                }
                +verificationError
            }
        }

        val record = ev.presentmentRecord
        when (record) {
            is Iso18013PresentmentRecord -> {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 16.px }

                    CborDataBlock {
                        title = "ISO 18013-5 DeviceResponse"
                        dataItem = record.response
                    }

                    CborDataBlock {
                        title = "ISO 18013-5 SessionTranscript"
                        dataItem = record.sessionTranscript
                    }

                    CborDataBlock {
                        title = "ISO 18013-5 DeviceRequest"
                        dataItem = record.request
                    }

                    val encInfo = record.encryptionInfo
                    if (encInfo != null) {
                        try {
                            val encInfoItem = Cbor.decode(encInfo.toByteArray())
                            CborDataBlock {
                                title = "EncryptionInfo (DC API)"
                                dataItem = encInfoItem
                            }
                        } catch (e: Throwable) {
                            TextDataBlock {
                                title = "EncryptionInfo"
                                content = encInfo.toByteArray().toHex()
                                copyLabel = "📋 Copy Hex"
                            }
                        }
                    }

                    val orig = record.origin
                    if (orig != null) {
                        div {
                            span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold }; +"Requester Web Origin: " }
                            span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold }; +orig }
                        }
                    }
                }
            }
            is OpenID4VPPresentmentRecord -> {
                div {
                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 16.px }

                    TextDataBlock {
                        title = "OpenID4VP VP Token"
                        content = record.vpToken
                        copyLabel = "📋 Copy VP Token"
                    }

                    TextDataBlock {
                        title = "OpenID4VP Request"
                        content = record.vpRequest
                        copyLabel = "📋 Copy VP Request"
                    }

                    val mdocTranscript = record.mdocSessionTranscript
                    if (mdocTranscript != null) {
                        CborDataBlock {
                            title = "mdoc SessionTranscript"
                            dataItem = mdocTranscript
                        }
                    }
                }
            }
            else -> {
                div {
                    css { color = Color("#94a3b8"); fontSize = 13.px }
                    +"Generic PresentmentRecord: ${record::class.simpleName}"
                }
            }
        }
    }
}

private fun react.ChildrenBuilder.renderEventVerificationSection(ev: EventVerification) {
    EventVerificationBlock {
        this.event = ev
    }
}

private fun react.ChildrenBuilder.renderEventSimpleSection(ev: EventSimple) {
    div {
        css {
            background = Color("#0f172a")
            borderRadius = 12.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 24.px
            marginBottom = 24.px
        }

        h3 {
            css { color = Color("#fbbf24"); fontSize = 1.2.rem; marginTop = 0.px; marginBottom = 16.px }
            +"Simple Event Data"
        }

        div {
            css { color = Color("#cbd5e1"); fontSize = 14.px; fontFamily = FontFamily.monospace }
            +"Payload Size: ${ev.data.size} bytes"
        }
    }
}

private fun react.ChildrenBuilder.renderEventAppDataSection(appData: Map<String, org.multipaz.cbor.DataItem>) {
    div {
        css {
            background = Color("#0f172a")
            borderRadius = 12.px
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            padding = 24.px
            marginBottom = 24.px
        }

        h3 {
            css { color = Color("#e2e8f0"); fontSize = 1.1.rem; marginTop = 0.px; marginBottom = 16.px }
            +"Application Data (${appData.size} items)"
        }

        appData.forEach { (key, dataItem) ->
            div {
                css {
                    background = Color("#1e293b")
                    borderRadius = 8.px
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                    padding = 12.px
                    marginBottom = 8.px
                }

                div {
                    css { color = Color("#38bdf8"); fontWeight = FontWeight.bold; fontSize = 14.px; marginBottom = 4.px }
                    +key
                }

                CborDiagnosticViewer {
                    diagText = Cbor.toDiagnostics(dataItem, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
                    maxHeight = 200.px
                }
            }
        }
    }
}
