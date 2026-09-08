@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
    kotlin.js.ExperimentalWasmJsInterop::class
)
package org.multipaz.tools.frontend

import emotion.react.css
import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Pkcs12
import org.multipaz.crypto.Pkcs12WrongPassphraseException
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.util.toHex
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffectOnce
import react.useState
import web.blob.Blob
import web.blob.BlobPropertyBag
import web.cssom.*
import web.file.File
import web.file.FileReader
import web.html.HTMLAnchorElement
import web.html.InputType
import web.url.URL

private data class DecodedPkcs12Data(
    val privateKey: EcPrivateKey,
    val certs: List<X509Cert>,
    val jwkPub: String,
    val cosePub: String,
    val cdnPub: String,
    val pemPub: String,
    val jwkPriv: String,
    val cosePriv: String,
    val cdnPriv: String,
    val pemPriv: String,
    val certChainPem: String
)

private fun downloadText(fileName: String, content: String, mimeType: String = "text/plain") {
    val blob = Blob(arrayOf(content), BlobPropertyBag(type = mimeType))
    val blobUrl = URL.createObjectURL(blob)
    val anchor = document.createElement("a").unsafeCast<HTMLAnchorElement>()
    anchor.href = blobUrl
    anchor.download = fileName
    anchor.click()
    URL.revokeObjectURL(blobUrl)
}

val Pkcs12DecoderComponent: FC<Props> = FC {
    var rawInput by useState("")
    var passphraseInput by useState("")
    var decodedData by useState<DecodedPkcs12Data?>(null)
    var parseError by useState("")
    var isDecoding by useState(false)

    // Tab states
    var privateKeyTab by useState("pem")
    var publicKeyTab by useState("pem")
    var activeCertIndex by useState(0)
    var activeCertTab by useState("details") // "details", "pem", "hex"

    // Copy states
    var copyPrivateKeySuccess by useState(false)
    var copyPublicKeySuccess by useState(false)
    var copyCertSuccess by useState(false)
    var copyAllCertsSuccess by useState(false)

    // Export Modal
    var isExportModalOpen by useState(false)

    // Passphrase Prompt Modal
    var pendingP12Bytes by useState<ByteArray?>(null)
    var isPassphraseModalOpen by useState(false)

    suspend fun processDecodedPkcs12(p12: Pkcs12) {
        val privKey = p12.privateKey
        val pubKey = privKey.publicKey
        val certs = p12.certChain.certificates

        val jwkPub = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), pubKey.toJwk())
        val cosePub = Cbor.encode(pubKey.toCoseKey().toDataItem()).toHex()
        val cdnPub = Cdn.encode(pubKey.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty)
        val pemPub = pubKey.toPem()

        val jwkPriv = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), privKey.toJwk())
        val cosePriv = Cbor.encode(privKey.toCoseKey().toDataItem()).toHex()
        val cdnPriv = Cdn.encode(privKey.toCoseKey().toDataItem(), CdnGeneratorOptions.Pretty)
        val pemPriv = privKey.toPem()

        val certChainPem = certs.joinToString("\n") { it.toPem() }

        decodedData = DecodedPkcs12Data(
            privateKey = privKey,
            certs = certs,
            jwkPub = jwkPub,
            cosePub = cosePub,
            cdnPub = cdnPub,
            pemPub = pemPub,
            jwkPriv = jwkPriv,
            cosePriv = cosePriv,
            cdnPriv = cdnPriv,
            pemPriv = pemPriv,
            certChainPem = certChainPem
        )
        parseError = ""
        activeCertIndex = 0
    }

    fun decodeBytes(bytes: ByteArray, passphrase: String?) {
        mainScope.launch {
            try {
                isDecoding = true
                val p12 = Pkcs12.fromDer(ByteString(bytes), passphrase = passphrase?.ifEmpty { null })
                processDecodedPkcs12(p12)
            } catch (e: Pkcs12WrongPassphraseException) {
                pendingP12Bytes = bytes
                isPassphraseModalOpen = true
            } catch (e: Throwable) {
                parseError = "Error decoding PKCS#12: ${e.message ?: e.toString()}"
                decodedData = null
            } finally {
                isDecoding = false
            }
        }
    }

    fun decodeFromInput() {
        val clean = rawInput.trim()
        if (clean.isEmpty()) {
            parseError = "Please select a .p12 file or paste Base64/Hex encoded PKCS#12 bytes"
            return
        }
        try {
            val bytes = decodeInputToBytes(clean)
            decodeBytes(bytes, passphraseInput.ifEmpty { null })
        } catch (e: Throwable) {
            parseError = "Failed to parse input bytes: ${e.message ?: e.toString()}"
        }
    }

    useEffectOnce {
        val hashPayload = getUrlHashPayload()
        if (hashPayload.isNotEmpty()) {
            rawInput = hashPayload
            try {
                val bytes = decodeInputToBytes(hashPayload.trim())
                decodeBytes(bytes, null)
            } catch (_: Throwable) {}
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
            +"PKCS#12 (.p12 / .pfx) Decoder"
        }

        p {
            css {
                color = Color("#94a3b8")
                fontSize = 14.px
                marginBottom = 24.px
                marginTop = 0.px
            }
            +"Inspect and extract private keys and certificate chains from PKCS#12 archives (.p12, .pfx)."
        }

        if (decodedData != null || parseError.isNotEmpty()) {
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
                    decodedData = null
                    parseError = ""
                    updateUrlHashPayload("")
                }
                +"← Back to Input"
            }

            if (parseError.isNotEmpty()) {
                div {
                    css {
                        padding = 16.px
                        borderRadius = 8.px
                        backgroundColor = Color("#450a0a")
                        border = Border(1.px, LineStyle.solid, Color("#991b1b"))
                        color = Color("#fca5a5")
                        fontWeight = FontWeight.bold
                        marginBottom = 24.px
                    }
                    +parseError
                }
            }

            decodedData?.let { data ->
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    // Action Bar
                    div {
                        css {
                            display = Display.flex
                            flexWrap = FlexWrap.wrap
                            gap = 12.px
                            justifyContent = JustifyContent.spaceBetween
                            alignItems = AlignItems.center
                            background = Color("#0f172a")
                            padding = 16.px
                            borderRadius = 12.px
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                        }

                        div {
                            css { display = Display.flex; alignItems = AlignItems.center; gap = 12.px }
                            span {
                                css {
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    color = Color("#a78bfa")
                                    backgroundColor = Color("#3b0764")
                                    padding = Padding(4.px, 10.px)
                                    borderRadius = 20.px
                                    border = Border(1.px, LineStyle.solid, Color("#6b21a8"))
                                }
                                +"Curve: ${data.privateKey.curve.name}"
                            }
                            span {
                                css {
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    color = Color("#38bdf8")
                                    backgroundColor = Color("#0c4a6e")
                                    padding = Padding(4.px, 10.px)
                                    borderRadius = 20.px
                                    border = Border(1.px, LineStyle.solid, Color("#0369a1"))
                                }
                                +"Certificates: ${data.certs.size}"
                            }
                        }

                        div {
                            css { display = Display.flex; gap = 8.px; flexWrap = FlexWrap.wrap }

                            button {
                                css {
                                    padding = Padding(8.px, 14.px)
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color("#2563eb")
                                    color = Color("#ffffff")
                                    border = None.none
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    hover { backgroundColor = Color("#1d4ed8") }
                                }
                                onClick = { isExportModalOpen = true }
                                +"💾 Export PKCS#12"
                            }

                            button {
                                css {
                                    padding = Padding(8.px, 14.px)
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color("#334155")
                                    color = Color("#f1f5f9")
                                    border = None.none
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    hover { backgroundColor = Color("#475569") }
                                }
                                onClick = { downloadText("private_key.pem", data.pemPriv) }
                                +"📥 Key (.pem)"
                            }

                            button {
                                css {
                                    padding = Padding(8.px, 14.px)
                                    fontSize = 13.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color("#334155")
                                    color = Color("#f1f5f9")
                                    border = None.none
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    hover { backgroundColor = Color("#475569") }
                                }
                                onClick = { downloadText("certificates.pem", data.certChainPem) }
                                +"📥 Certs (.pem)"
                            }
                        }
                    }

                    // Private Key Card
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
                                    color = Color("#38bdf8")
                                    display = Display.flex
                                    alignItems = AlignItems.center
                                    gap = 8.px
                                }
                                +"🔑 Private Key"
                            }

                            div {
                                css { display = Display.flex; gap = 8.px }
                                button {
                                    css {
                                        padding = Padding(6.px, 12.px)
                                        fontSize = 12.px
                                        fontWeight = FontWeight.bold
                                        backgroundColor = if (copyPrivateKeySuccess) Color("#10b981") else Color("#334155")
                                        color = Color("#ffffff")
                                        border = None.none
                                        borderRadius = 6.px
                                        cursor = Cursor.pointer
                                        hover { if (!copyPrivateKeySuccess) backgroundColor = Color("#475569") }
                                    }
                                    onClick = {
                                        val text = when (privateKeyTab) {
                                            "pem" -> data.pemPriv
                                            "jwk" -> data.jwkPriv
                                            "cose" -> data.cosePriv
                                            "cdn" -> data.cdnPriv
                                            else -> data.pemPriv
                                        }
                                        window.navigator.clipboard.writeText(text)
                                        copyPrivateKeySuccess = true
                                        window.setTimeout({ copyPrivateKeySuccess = false }, 2000)
                                    }
                                    +if (copyPrivateKeySuccess) "✓ Copied Key" else "📋 Copy Key"
                                }
                            }
                        }

                        // Format Tabs
                        div {
                            css { display = Display.flex; gap = 8.px }
                            listOf("pem" to "PEM", "jwk" to "JWK JSON", "cose" to "COSE Hex", "cdn" to "CDN").forEach { (tabKey, tabLabel) ->
                                button {
                                    css {
                                        padding = Padding(6.px, 14.px)
                                        fontSize = 12.px
                                        fontWeight = FontWeight.bold
                                        borderRadius = 6.px
                                        border = None.none
                                        cursor = Cursor.pointer
                                        if (privateKeyTab == tabKey) {
                                            backgroundColor = Color("#2563eb")
                                            color = Color("#ffffff")
                                        } else {
                                            backgroundColor = Color("#1e293b")
                                            color = Color("#94a3b8")
                                            hover { backgroundColor = Color("#334155"); color = Color("#f1f5f9") }
                                        }
                                    }
                                    onClick = { privateKeyTab = tabKey }
                                    +tabLabel
                                }
                            }
                        }

                        // Text Area Content
                        textarea {
                            css {
                                width = 100.pct
                                height = 140.px
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                color = Color("#38bdf8")
                                fontFamily = FontFamily.monospace
                                fontSize = 12.px
                                padding = 12.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            readOnly = true
                            value = when (privateKeyTab) {
                                "pem" -> data.pemPriv
                                "jwk" -> data.jwkPriv
                                "cose" -> data.cosePriv
                                "cdn" -> data.cdnPriv
                                else -> data.pemPriv
                            }
                        }
                    }

                    // Certificate Chain Section
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 12.px
                            padding = 24.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 20.px
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
                                    color = Color("#34d399")
                                    display = Display.flex
                                    alignItems = AlignItems.center
                                    gap = 8.px
                                }
                                +"📜 Certificate Chain (${data.certs.size})"
                            }

                            div {
                                css { display = Display.flex; gap = 8.px }
                                button {
                                    css {
                                        padding = Padding(6.px, 12.px)
                                        fontSize = 12.px
                                        fontWeight = FontWeight.bold
                                        backgroundColor = if (copyAllCertsSuccess) Color("#10b981") else Color("#334155")
                                        color = Color("#ffffff")
                                        border = None.none
                                        borderRadius = 6.px
                                        cursor = Cursor.pointer
                                        hover { if (!copyAllCertsSuccess) backgroundColor = Color("#475569") }
                                    }
                                    onClick = {
                                        window.navigator.clipboard.writeText(data.certChainPem)
                                        copyAllCertsSuccess = true
                                        window.setTimeout({ copyAllCertsSuccess = false }, 2000)
                                    }
                                    +if (copyAllCertsSuccess) "✓ Copied All" else "📋 Copy All Certs"
                                }
                            }
                        }

                        // Certificate Selector Tabs (if more than 1)
                        if (data.certs.size > 1) {
                            div {
                                css { display = Display.flex; gap = 8.px; flexWrap = FlexWrap.wrap }
                                data.certs.forEachIndexed { index, cert ->
                                    val isSelected = activeCertIndex == index
                                    val label = when (index) {
                                        0 -> "Leaf / End-Entity [#1]"
                                        data.certs.size - 1 -> "Root CA [#${index + 1}]"
                                        else -> "Intermediate [#${index + 1}]"
                                    }
                                    button {
                                        css {
                                            padding = Padding(6.px, 14.px)
                                            fontSize = 12.px
                                            fontWeight = FontWeight.bold
                                            borderRadius = 6.px
                                            border = None.none
                                            cursor = Cursor.pointer
                                            if (isSelected) {
                                                backgroundColor = Color("#059669")
                                                color = Color("#ffffff")
                                            } else {
                                                backgroundColor = Color("#1e293b")
                                                color = Color("#94a3b8")
                                                hover { backgroundColor = Color("#334155"); color = Color("#f1f5f9") }
                                            }
                                        }
                                        onClick = { activeCertIndex = index }
                                        +label
                                    }
                                }
                            }
                        }

                        // Selected Certificate Details
                        val currentCert = data.certs.getOrNull(activeCertIndex) ?: data.certs.first()
                        val now = Clock.System.now()
                        val status = when {
                            now < currentCert.validityNotBefore -> "Not Yet Active"
                            now > currentCert.validityNotAfter -> "Expired"
                            else -> "Active"
                        }
                        val statusColor = when (status) {
                            "Active" -> "#10b981"
                            "Expired" -> "#ef4444"
                            else -> "#f59e0b"
                        }

                        // Cert Subtabs
                        div {
                            css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center }
                            div {
                                css { display = Display.flex; gap = 8.px }
                                listOf("details" to "Details", "pem" to "PEM", "hex" to "DER Hex").forEach { (tabKey, tabLabel) ->
                                    button {
                                        css {
                                            padding = Padding(4.px, 12.px)
                                            fontSize = 12.px
                                            fontWeight = FontWeight.bold
                                            borderRadius = 6.px
                                            border = None.none
                                            cursor = Cursor.pointer
                                            if (activeCertTab == tabKey) {
                                                backgroundColor = Color("#334155")
                                                color = Color("#34d399")
                                            } else {
                                                backgroundColor = Color("transparent")
                                                color = Color("#64748b")
                                                hover { color = Color("#94a3b8") }
                                            }
                                        }
                                        onClick = { activeCertTab = tabKey }
                                        +tabLabel
                                    }
                                }
                            }

                            span {
                                css {
                                    fontSize = 11.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color(statusColor)
                                    color = Color("#ffffff")
                                    padding = Padding(3.px, 8.px)
                                    borderRadius = 12.px
                                }
                                +status
                            }
                        }

                        when (activeCertTab) {
                            "details" -> {
                                div {
                                    css { display = Display.flex; flexDirection = FlexDirection.column; gap = 16.px }

                                    // Subject & Issuer
                                    div {
                                        css {
                                            display = Display.grid
                                            gridTemplateColumns = "repeat(auto-fit, minmax(300px, 1fr))".unsafeCast<GridTemplateColumns>()
                                            gap = 16.px
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                        }
                                        div {
                                            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                            span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Subject DN" }
                                            span { css { color = Color("#34d399"); fontSize = 13.px; fontWeight = FontWeight.bold; wordBreak = WordBreak.breakAll }; +currentCert.subject.name }
                                        }
                                        div {
                                            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                            span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Issuer DN" }
                                            span { css { color = Color("#cbd5e1"); fontSize = 13.px; wordBreak = WordBreak.breakAll }; +currentCert.issuer.name }
                                        }
                                    }

                                    // Validity & Serial
                                    div {
                                        css {
                                            display = Display.grid
                                            gridTemplateColumns = "repeat(auto-fit, minmax(180px, 1fr))".unsafeCast<GridTemplateColumns>()
                                            gap = 16.px
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                        }
                                        div {
                                            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                            span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Valid From" }
                                            span { css { color = Color("#f1f5f9"); fontSize = 12.px; fontFamily = FontFamily.monospace }; +currentCert.validityNotBefore.toString() }
                                        }
                                        div {
                                            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                            span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Valid Until" }
                                            span { css { color = Color("#f1f5f9"); fontSize = 12.px; fontFamily = FontFamily.monospace }; +currentCert.validityNotAfter.toString() }
                                        }
                                        div {
                                            css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                            span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Serial Number" }
                                            span { css { color = Color("#f1f5f9"); fontSize = 12.px; fontFamily = FontFamily.monospace }; +currentCert.serialNumber.value.toHex() }
                                        }
                                    }

                                    // Key Usage / SKI / AKI
                                    div {
                                        css {
                                            display = Display.grid
                                            gridTemplateColumns = "repeat(auto-fit, minmax(240px, 1fr))".unsafeCast<GridTemplateColumns>()
                                            gap = 16.px
                                            background = Color("#1e293b")
                                            padding = 16.px
                                            borderRadius = 8.px
                                        }
                                        currentCert.keyUsage?.let { ku ->
                                            div {
                                                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                                span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Key Usage" }
                                                span { css { color = Color("#38bdf8"); fontSize = 12.px; fontWeight = FontWeight.bold }; +ku.joinToString(", ") { it.name } }
                                            }
                                        }
                                        currentCert.subjectKeyIdentifier?.let { ski ->
                                            div {
                                                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                                span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Subject Key ID" }
                                                span { css { color = Color("#f1f5f9"); fontSize = 11.px; fontFamily = FontFamily.monospace; wordBreak = WordBreak.breakAll }; +ski.toHex(byteDivider = " ") }
                                            }
                                        }
                                        currentCert.authorityKeyIdentifier?.let { aki ->
                                            div {
                                                css { display = Display.flex; flexDirection = FlexDirection.column; gap = 4.px }
                                                span { css { color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold; textTransform = TextTransform.uppercase }; +"Authority Key ID" }
                                                span { css { color = Color("#f1f5f9"); fontSize = 11.px; fontFamily = FontFamily.monospace; wordBreak = WordBreak.breakAll }; +aki.toHex(byteDivider = " ") }
                                            }
                                        }
                                    }
                                }
                            }
                            "pem" -> {
                                textarea {
                                    css {
                                        width = 100.pct
                                        height = 160.px
                                        background = Color("#1e293b")
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        borderRadius = 8.px
                                        color = Color("#34d399")
                                        fontFamily = FontFamily.monospace
                                        fontSize = 11.px
                                        padding = 12.px
                                        resize = "none".unsafeCast<Resize>()
                                    }
                                    readOnly = true
                                    value = currentCert.toPem()
                                }
                            }
                            "hex" -> {
                                textarea {
                                    css {
                                        width = 100.pct
                                        height = 160.px
                                        background = Color("#1e293b")
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        borderRadius = 8.px
                                        color = Color("#f8fafc")
                                        fontFamily = FontFamily.monospace
                                        fontSize = 11.px
                                        padding = 12.px
                                        resize = "none".unsafeCast<Resize>()
                                    }
                                    readOnly = true
                                    value = currentCert.encoded.toByteArray().toHex()
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Input Mode
            div {
                css {
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 20.px
                }

                // File Upload Button & Header
                div {
                    css {
                        display = Display.flex
                        justifyContent = JustifyContent.spaceBetween
                        alignItems = AlignItems.center
                    }

                    span {
                        css {
                            color = Color("#cbd5e1")
                            fontSize = 14.px
                            fontWeight = FontWeight.bold
                        }
                        +"Load PKCS#12 (.p12 / .pfx) File or Paste Raw Bytes"
                    }

                    label {
                        css {
                            background = Color("#334155")
                            color = Color("#f1f5f9")
                            padding = Padding(8.px, 16.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 13.px
                            fontWeight = FontWeight.bold
                            display = Display.inlineFlex
                            alignItems = AlignItems.center
                            gap = 6.px
                            hover { background = Color("#475569") }
                        }
                        +"📁 Choose .p12 / .pfx File"
                        input {
                            type = "file".unsafeCast<InputType>()
                            accept = ".p12,.pfx"
                            css { display = None.none }
                            onChange = { event ->
                                val fileList = event.target.asDynamic().files
                                if (fileList != null && fileList.length > 0) {
                                    val file = fileList[0].unsafeCast<File>()
                                    val reader = FileReader()
                                    reader.asDynamic().onload = {
                                        try {
                                            val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
                                            val bytes = Int8Array(arrayBuffer).toByteArray()
                                            rawInput = bytes.toHex()
                                            decodeBytes(bytes, passphraseInput.ifEmpty { null })
                                        } catch (e: Throwable) {
                                            parseError = "Failed to read file: ${e.message ?: e.toString()}"
                                        }
                                    }
                                    reader.readAsArrayBuffer(file)
                                }
                            }
                        }
                    }
                }

                textarea {
                    css {
                        width = 100.pct
                        height = 180.px
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
                    placeholder = "Select a .p12/.pfx file or paste Hex/Base64 encoded PKCS#12 DER bytes..."
                    onChange = { rawInput = it.target.value }
                }

                // Passphrase input
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 6.px
                        maxWidth = 400.px
                    }

                    label {
                        css {
                            color = Color("#cbd5e1")
                            fontSize = 13.px
                            fontWeight = FontWeight.bold
                        }
                        +"Passphrase (Optional — leave blank if passwordless):"
                    }

                    input {
                        type = "password".unsafeCast<InputType>()
                        value = passphraseInput
                        placeholder = "Passphrase..."
                        css {
                            padding = Padding(8.px, 12.px)
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            color = Color("#f8fafc")
                            fontSize = 13.px
                            outline = None.none
                            focus { borderColor = Color("#3b82f6") }
                        }
                        onChange = { passphraseInput = it.target.value }
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
                        width = 220.px
                        hover { backgroundColor = Color("#2563eb") }
                        disabled {
                            backgroundColor = Color("#475569")
                            cursor = Cursor.notAllowed
                        }
                    }
                    disabled = rawInput.trim().isEmpty() || isDecoding
                    onClick = { decodeFromInput() }
                    +if (isDecoding) "Decoding..." else "Decode PKCS#12"
                }
            }
        }

        // Export Modal
        decodedData?.let { data ->
            Pkcs12ExportModalComponent {
                isOpen = isExportModalOpen
                onClose = { isExportModalOpen = false }
                privateKey = data.privateKey
                certChain = org.multipaz.crypto.X509CertChain(data.certs)
                defaultFileName = "exported.p12"
            }
        }

        // Passphrase Prompt Modal
        Pkcs12PassphraseModalComponent {
            isOpen = isPassphraseModalOpen
            onClose = { isPassphraseModalOpen = false }
            rawBytes = pendingP12Bytes
            onDecoded = { p12 ->
                mainScope.launch {
                    processDecodedPkcs12(p12)
                }
            }
        }
    }
}
