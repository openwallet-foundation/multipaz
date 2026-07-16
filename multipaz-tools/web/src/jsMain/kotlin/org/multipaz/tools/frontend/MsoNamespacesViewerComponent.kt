@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.code
import react.useState
import web.cssom.*
import kotlinx.coroutines.launch
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.Cose
import org.multipaz.util.toHex
import org.multipaz.util.toBase64

sealed class ParsedResult {
    data class Mso(val mso: MobileSecurityObject) : ParsedResult()
    data class Namespaces(val namespaces: IssuerNamespaces) : ParsedResult()
    data class IssuerSignedData(
        val namespaces: IssuerNamespaces?,
        val mso: MobileSecurityObject?,
        val issuerAuth: CoseSign1
    ) : ParsedResult()
}

val MsoNamespacesViewerComponent = FC {
    var rawInput by useState("")
    var selectedType by useState("auto")
    var parsedResult by useState<ParsedResult?>(null)
    var parseError by useState("")
    var copiedCertIndex by useState<Int?>(null)

    fun parseMso(dataItem: DataItem): MobileSecurityObject {
        var item = dataItem
        if (item.majorType == org.multipaz.cbor.MajorType.TAG) {
            item = item.asTaggedEncodedCbor
        }
        return MobileSecurityObject.fromDataItem(item)
    }

    fun parseIssuerSigned(dataItem: DataItem): ParsedResult.IssuerSignedData {
        val namespaces = dataItem.getOrNull("nameSpaces")?.let { IssuerNamespaces.fromDataItem(it) }
        val issuerAuth = dataItem["issuerAuth"].asCoseSign1
        val mso = try {
            val payload = issuerAuth.payload ?: error("No payload in issuerAuth")
            val decodedPayload = Cbor.decode(payload)
            val msoBytes = if (decodedPayload.majorType == org.multipaz.cbor.MajorType.TAG) {
                decodedPayload.asTagged.asBstr
            } else {
                decodedPayload.asBstr
            }
            MobileSecurityObject.fromDataItem(Cbor.decode(msoBytes))
        } catch (e: Throwable) {
            null
        }
        return ParsedResult.IssuerSignedData(namespaces, mso, issuerAuth)
    }

    fun parseInput(bytes: ByteArray, type: String): ParsedResult {
        val dataItem = Cbor.decode(bytes)

        if (type == "mso") {
            return ParsedResult.Mso(parseMso(dataItem))
        }
        if (type == "namespaces") {
            return ParsedResult.Namespaces(IssuerNamespaces.fromDataItem(dataItem))
        }
        if (type == "issuer_signed") {
            return parseIssuerSigned(dataItem)
        }

        // Auto-detect logic
        if (dataItem.majorType == org.multipaz.cbor.MajorType.MAP && dataItem.hasKey("issuerAuth")) {
            try {
                return parseIssuerSigned(dataItem)
            } catch (e: Throwable) {}
        }

        if (dataItem.majorType == org.multipaz.cbor.MajorType.MAP && dataItem.hasKey("digestAlgorithm") && dataItem.hasKey("validityInfo")) {
            try {
                return ParsedResult.Mso(parseMso(dataItem))
            } catch (e: Throwable) {}
        }

        try {
            val namespaces = IssuerNamespaces.fromDataItem(dataItem)
            if (namespaces.data.isNotEmpty()) {
                return ParsedResult.Namespaces(namespaces)
            }
        } catch (e: Throwable) {}

        if (dataItem.majorType == org.multipaz.cbor.MajorType.TAG) {
            try {
                return ParsedResult.Mso(parseMso(dataItem))
            } catch (e: Throwable) {}
        }

        throw IllegalArgumentException("Could not identify or parse the input data structure. Try selecting the format explicitly.")
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
            +"ISO mdoc MSO and IssuerNameSpaces"
        }

        if (parsedResult != null || parseError.isNotEmpty()) {
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
                    parsedResult = null
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

            parsedResult?.let { result ->
                div {
                    css {
                        marginTop = 24.px
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    when (result) {
                        is ParsedResult.Mso -> {
                            h3 {
                                css {
                                    fontSize = 1.4.rem
                                    fontWeight = FontWeight.bold
                                    marginBottom = 16.px
                                    color = Color("#38bdf8")
                                }
                                +"Mobile Security Object (MSO)"
                            }
                            renderMsoDetails(result.mso)
                        }
                        is ParsedResult.Namespaces -> {
                            h3 {
                                css {
                                    fontSize = 1.4.rem
                                    fontWeight = FontWeight.bold
                                    marginBottom = 16.px
                                    color = Color("#38bdf8")
                                }
                                +"Issuer Namespaces"
                            }
                            renderNamespacesDetails(result.namespaces)
                        }
                        is ParsedResult.IssuerSignedData -> {
                            h3 {
                                css {
                                    fontSize = 1.4.rem
                                    fontWeight = FontWeight.bold
                                    marginBottom = 16.px
                                    color = Color("#38bdf8")
                                }
                                +"IssuerSigned Structure"
                            }
                            renderIssuerSignedDetails(
                                result = result,
                                copiedCertIndex = copiedCertIndex,
                                onCopyCert = { index, pem ->
                                    kotlinx.browser.window.navigator.asDynamic().clipboard.writeText(pem)
                                    copiedCertIndex = index
                                    kotlinx.browser.window.setTimeout({
                                        copiedCertIndex = null
                                    }, 1500)
                                }
                            )
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
                +"Inspect a raw MobileSecurityObject (MSO), IssuerNamespaces, or IssuerSigned CBOR structure (Hex or Base64 format)."
            }

            div {
                css {
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 16.px
                    marginBottom = 24.px
                }

                div {
                    label {
                        css {
                            display = Display.block
                            fontWeight = FontWeight.bold
                            marginBottom = 6.px
                            color = Color("#cbd5e1")
                            fontSize = 14.px
                        }
                        +"Structure Type:"
                    }
                    select {
                        css {
                            width = 100.pct
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 8.px
                            color = Color("#f1f5f9")
                            padding = 10.px
                            fontSize = 14.px
                        }
                        value = selectedType
                        onChange = { selectedType = it.target.value }
                        option { value = "auto"; +"Auto-detect" }
                        option { value = "mso"; +"Mobile Security Object (MSO)" }
                        option { value = "namespaces"; +"IssuerNamespaces" }
                        option { value = "issuer_signed"; +"IssuerSigned" }
                    }
                }

                div {
                    label {
                        css {
                            display = Display.block
                            fontWeight = FontWeight.bold
                            marginBottom = 6.px
                            color = Color("#cbd5e1")
                            fontSize = 14.px
                        }
                        +"Input Data (Hex or Base64):"
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
                            focus {
                                outline = None.none
                                borderColor = Color("#3b82f6")
                            }
                        }
                        value = rawInput
                        placeholder = "Paste data here..."
                        onChange = { rawInput = it.target.value }
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
                            val bytes = decodeInputToBytes(rawInput)
                            if (bytes.isEmpty()) {
                                parseError = "Input is empty"
                                parsedResult = null
                            } else {
                                parsedResult = parseInput(bytes, selectedType)
                                parseError = ""
                            }
                        } catch (e: Throwable) {
                            parseError = "Error parsing: " + (e.message ?: "Unknown error")
                            parsedResult = null
                        }
                    }
                }
                +"Parse"
            }
        }
    }
}

private fun react.ChildrenBuilder.renderMsoDetails(mso: MobileSecurityObject) {
    div {
        css {
            background = Color("#0f172a")
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            borderRadius = 12.px
            padding = 24.px
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 12.px
            fontSize = 14.px
        }

        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Version: " }
            span { css { color = Color("#f1f5f9") }; +mso.version }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Document Type: " }
            span { css { color = Color("#f1f5f9") }; +mso.docType }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Signed At: " }
            span { css { color = Color("#cbd5e1") }; +mso.signedAt.toString() }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Valid From: " }
            span { css { color = Color("#cbd5e1") }; +mso.validFrom.toString() }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Valid Until: " }
            span { css { color = Color("#cbd5e1") }; +mso.validUntil.toString() }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Expected Update: " }
            span { css { color = Color("#cbd5e1") }; +mso.expectedUpdate?.toString().orEmpty().ifEmpty { "None" } }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Digest Algorithm: " }
            span { css { color = Color("#f1f5f9") }; +mso.digestAlgorithm.hashAlgorithmName }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 6.px }; +"Device Key:" }
            pre {
                css {
                    background = Color("#1e293b")
                    padding = 12.px
                    borderRadius = 6.px
                    overflowX = "auto".unsafeCast<Overflow>()
                    fontFamily = FontFamily.monospace
                    fontSize = 13.px
                    color = Color("#34d399")
                    margin = 0.px
                }
                code {
                    +try {
                        Cbor.toDiagnostics(mso.deviceKey.toCoseKey().toDataItem(), setOf(DiagnosticOption.EMBEDDED_CBOR))
                    } catch (e: Throwable) {
                        "Error formatting device key"
                    }
                }
            }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Authorized Namespaces: " }
            span { css { color = Color("#cbd5e1") }; +if (mso.deviceKeyAuthorizedNamespaces.isNotEmpty()) mso.deviceKeyAuthorizedNamespaces.joinToString(", ") else "None" }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 6.px }; +"Authorized Data Elements:" }
            if (mso.deviceKeyAuthorizedDataElements.isNotEmpty()) {
                pre {
                    css {
                        background = Color("#1e293b")
                        padding = 12.px
                        borderRadius = 6.px
                        overflowX = "auto".unsafeCast<Overflow>()
                        fontFamily = FontFamily.monospace
                        fontSize = 13.px
                        color = Color("#cbd5e1")
                        margin = 0.px
                    }
                    code {
                        +mso.deviceKeyAuthorizedDataElements.toString()
                    }
                }
            } else {
                span { css { color = Color("#cbd5e1") }; +"None" }
            }
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 6.px }; +"Revocation Status:" }
            val revocationStatus = mso.revocationStatus
            if (revocationStatus != null) {
                pre {
                    css {
                        background = Color("#1e293b")
                        padding = 12.px
                        borderRadius = 6.px
                        overflowX = "auto".unsafeCast<Overflow>()
                        fontFamily = FontFamily.monospace
                        fontSize = 13.px
                        color = Color("#cbd5e1")
                        margin = 0.px
                    }
                    code {
                        +try {
                            Cbor.toDiagnostics(revocationStatus.toDataItem(), setOf(DiagnosticOption.EMBEDDED_CBOR))
                        } catch (e: Throwable) {
                            "Error formatting revocation status"
                        }
                    }
                }
            } else {
                span { css { color = Color("#cbd5e1") }; +"None" }
            }
        }
    }

    h4 {
        css {
            fontSize = 1.2.rem
            fontWeight = FontWeight.bold
            marginTop = 24.px
            marginBottom = 16.px
            color = Color("#cbd5e1")
        }
        +"Value Digests"
    }

    if (mso.valueDigests.isEmpty()) {
        p {
            css { color = Color("#64748b"); fontSize = 14.px }
            +"No value digests present in MSO."
        }
    } else {
        mso.valueDigests.forEach { (namespace, digests) ->
            div {
                css {
                    marginBottom = 20.px
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                    borderRadius = 8.px
                    padding = 16.px
                }
                div {
                    css {
                        fontWeight = FontWeight.bold
                        fontSize = 14.px
                        color = Color("#38bdf8")
                        marginBottom = 12.px
                    }
                    +namespace
                }
                table {
                    css {
                        width = 100.pct
                        borderCollapse = BorderCollapse.collapse
                        fontSize = 13.px
                    }
                    thead {
                        tr {
                            th { css { textAlign = TextAlign.left; padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Digest ID" }
                            th { css { textAlign = TextAlign.left; padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Digest (Hex)" }
                        }
                    }
                    tbody {
                        digests.entries.sortedBy { it.key }.forEach { (digestId, digest) ->
                            tr {
                                td { css { padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; width = 120.px }; +digestId.toString() }
                                td { css { padding = 6.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; color = Color("#34d399"); wordBreak = WordBreak.breakAll }; +digest.toByteArray().toHex() }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun react.ChildrenBuilder.renderNamespacesDetails(namespaces: IssuerNamespaces) {
    if (namespaces.data.isEmpty()) {
        p {
            css { color = Color("#64748b"); fontSize = 14.px }
            +"No namespaces present."
        }
    } else {
        namespaces.data.forEach { (nsName, nsItems) ->
            div {
                css {
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                    borderRadius = 12.px
                    padding = 24.px
                    marginBottom = 24.px
                }
                div {
                    css {
                        background = Color("#334155")
                        padding = Padding(6.px, 12.px)
                        borderRadius = 6.px
                        fontSize = 14.px
                        fontWeight = FontWeight.bold
                        display = Display.inlineBlock
                        marginBottom = 16.px
                        color = Color("#ffffff")
                    }
                    +nsName
                }

                table {
                    css {
                        width = 100.pct
                        borderCollapse = BorderCollapse.collapse
                        fontSize = 14.px
                    }
                    thead {
                        tr {
                            th { css { textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8"); width = 100.px }; +"Digest ID" }
                            th { css { textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8"); width = 200.px }; +"Element ID" }
                            th { css { textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8"); width = 200.px }; +"Random (Hex)" }
                            th { css { textAlign = TextAlign.left; padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Value" }
                        }
                    }
                    tbody {
                        nsItems.values.sortedBy { it.digestId }.forEach { item ->
                            tr {
                                td { css { padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace }; +item.digestId.toString() }
                                td { css { padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontWeight = FontWeight.bold; color = Color("#38bdf8") }; +item.dataElementIdentifier }
                                td { css { padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; fontSize = 12.px; wordBreak = WordBreak.breakAll }; +item.random.toByteArray().toHex() }
                                td {
                                    css { padding = 8.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; color = Color("#34d399") }
                                    
                                    val isImage = if (item.dataElementIdentifier.lowercase() in setOf("portrait", "picture", "photo")) {
                                        try {
                                            val bytes = item.dataElementValue.asBstr
                                            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                                                bytes
                                            } else {
                                                null
                                            }
                                        } catch (e: Throwable) {
                                            null
                                        }
                                    } else {
                                        null
                                    }

                                    if (isImage != null) {
                                        img {
                                            src = "data:image/jpeg;base64,${isImage.toBase64()}"
                                            css {
                                                maxWidth = 150.px
                                                maxHeight = 200.px
                                                borderRadius = 8.px
                                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                                boxShadow = BoxShadow(0.px, 2.px, 6.px, Color("rgba(0,0,0,0.3)"))
                                            }
                                        }
                                    } else {
                                        +try {
                                            Cbor.toDiagnostics(item.dataElementValue, setOf(DiagnosticOption.EMBEDDED_CBOR))
                                        } catch (e: Throwable) {
                                            "Error formatting item"
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
}

private fun react.ChildrenBuilder.renderIssuerSignedDetails(
    result: ParsedResult.IssuerSignedData,
    copiedCertIndex: Int?,
    onCopyCert: (Int, String) -> Unit
) {
    div {
        css {
            background = Color("#0f172a")
            border = Border(1.px, LineStyle.solid, Color("#334155"))
            borderRadius = 12.px
            padding = 24.px
            marginBottom = 24.px
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 12.px
            fontSize = 14.px
        }

        h4 {
            css {
                fontSize = 1.1.rem
                fontWeight = FontWeight.bold
                color = Color("#cbd5e1")
                margin = Margin(0.px, 0.px, 8.px, 0.px)
            }
            +"Issuer Signature (IssuerAuth)"
        }

        val alg = result.issuerAuth.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_ALG)]?.asNumber
        val algName = when (alg) {
            -7L -> "ES256"
            -35L -> "ES384"
            -36L -> "ES512"
            -8L -> "EdDSA"
            else -> alg?.toString() ?: "Unknown"
        }
        div {
            span { css { color = Color("#64748b"); fontWeight = FontWeight.bold }; +"Algorithm: " }
            span { css { color = Color("#f1f5f9") }; +algName }
        }

        val certChain = try {
            result.issuerAuth.unprotectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
                ?: result.issuerAuth.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
        } catch (e: Throwable) {
            null
        }

        if (certChain != null) {
            div {
                span { css { color = Color("#64748b"); fontWeight = FontWeight.bold; display = Display.block; marginBottom = 6.px }; +"Certificate Chain:" }
                certChain.certificates.forEachIndexed { index, cert ->
                    div {
                        css {
                            background = Color("#1e293b")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 8.px
                            padding = 12.px
                            marginBottom = 8.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 4.px
                            fontSize = 13.px
                        }
                        div {
                            span { css { color = Color("#64748b") }; +"Subject: " }
                            span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold }; +cert.subject.name }
                        }
                        div {
                            span { css { color = Color("#64748b") }; +"Issuer: " }
                            span { css { color = Color("#cbd5e1") }; +cert.issuer.name }
                        }
                        div {
                            span { css { color = Color("#64748b") }; +"Validity: " }
                            span { css { color = Color("#94a3b8") }; +"${cert.validityNotBefore} to ${cert.validityNotAfter}" }
                        }
                        button {
                            css {
                                alignSelf = "flex-start".unsafeCast<AlignSelf>()
                                padding = Padding(6.px, 12.px)
                                fontSize = 12.px
                                fontWeight = FontWeight.bold
                                backgroundColor = Color("#3b82f6")
                                color = Color("#ffffff")
                                border = None.none
                                borderRadius = 6.px
                                cursor = Cursor.pointer
                                marginTop = 8.px
                                hover {
                                    backgroundColor = Color("#2563eb")
                                }
                            }
                            onClick = {
                                onCopyCert(index, cert.toPem())
                            }
                            +if (copiedCertIndex == index) "Copied!" else "Copy PEM"
                        }
                    }
                }
            }
        }
    }

    if (result.namespaces != null) {
        h4 {
            css {
                fontSize = 1.2.rem
                fontWeight = FontWeight.bold
                marginTop = 24.px
                marginBottom = 16.px
                color = Color("#cbd5e1")
            }
            +"Issuer Namespaces"
        }
        renderNamespacesDetails(result.namespaces)
    }

    if (result.mso != null) {
        h4 {
            css {
                fontSize = 1.2.rem
                fontWeight = FontWeight.bold
                marginTop = 24.px
                marginBottom = 16.px
                color = Color("#cbd5e1")
            }
            +"Mobile Security Object (MSO)"
        }
        renderMsoDetails(result.mso)
    }
}
