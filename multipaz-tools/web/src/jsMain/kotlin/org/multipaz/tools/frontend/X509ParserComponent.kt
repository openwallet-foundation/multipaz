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
import react.dom.html.ReactHTML.pre
import react.useState
import web.cssom.*
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKeyOkp
import org.multipaz.util.toHex
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Boolean
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.certext.MultipazExtension
import org.multipaz.certext.fromCbor
import org.multipaz.util.AndroidAttestationExtensionParser

val X509ParserComponent = FC {
    var rawInput by useState("")
    var parsedCert by useState<X509Cert?>(null)
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
            +"Certificate Parser"
        }

        if (parsedCert != null || parseError.isNotEmpty()) {
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
                    parsedCert = null
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

            parsedCert?.let { cert ->
                div {
                    css {
                        marginTop = 24.px
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    // Render Status Pill
                    val now = Clock.System.now()
                    val status = when {
                        now < cert.validityNotBefore -> "Not Yet Active"
                        now > cert.validityNotAfter -> "Expired"
                        else -> "Active"
                    }
                    
                    val statusColor = when (status) {
                        "Active" -> "#10b981"
                        "Expired" -> "#ef4444"
                        else -> "#f59e0b"
                    }

                    // Card 1: Overview (Subject & Issuer)
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
                                +"Certificate Identity"
                            }
                            span {
                                css {
                                    fontSize = 12.px
                                    fontWeight = FontWeight.bold
                                    backgroundColor = Color(statusColor)
                                    color = Color("#ffffff")
                                    padding = Padding(4.px, 10.px)
                                    borderRadius = 20.px
                                }
                                +status
                            }
                        }

                        div {
                            css {
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 4.px
                            }
                            span { 
                                css { 
                                    color = Color("#64748b")
                                    fontWeight = FontWeight.bold
                                    fontSize = 12.px
                                    textTransform = TextTransform.uppercase 
                                }
                                +"Subject Distinguished Name"
                            }
                            span {
                                css {
                                    fontSize = 15.px
                                    color = Color("#38bdf8")
                                    fontWeight = FontWeight.bold
                                }
                                +cert.subject.name
                            }
                        }

                        div {
                            css {
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 4.px
                            }
                            span { 
                                css { 
                                    color = Color("#64748b")
                                    fontWeight = FontWeight.bold
                                    fontSize = 12.px
                                    textTransform = TextTransform.uppercase 
                                } 
                                +"Issuer Distinguished Name"
                            }
                            span {
                                css {
                                    fontSize = 15.px
                                    color = Color("#cbd5e1")
                                }
                                +cert.issuer.name
                            }
                        }
                    }

                    // Card 2: Validity & Metadata (Grid)
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
                                +"Validity Start"
                            }
                            span { 
                                css { 
                                    color = Color("#f1f5f9")
                                    fontFamily = FontFamily.monospace
                                    fontSize = 14.px 
                                }
                                +cert.validityNotBefore.toString()
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
                                +"Validity Expiration"
                            }
                            span { 
                                css { 
                                    color = Color("#f1f5f9")
                                    fontFamily = FontFamily.monospace
                                    fontSize = 14.px 
                                }
                                +cert.validityNotAfter.toString()
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
                                +"Serial Number (Hex)"
                            }
                            span { 
                                css { 
                                    color = Color("#34d399")
                                    fontFamily = FontFamily.monospace
                                    fontSize = 14.px 
                                }
                                +cert.serialNumber.value.toHex().uppercase()
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
                                +"Certificate Version"
                            }
                            span { 
                                css { 
                                    color = Color("#f1f5f9")
                                    fontSize = 14.px 
                                }
                                +"X.509 v${cert.version + 1}"
                            }
                        }
                    }

                    // Card 3: Cryptographic Public Key Info
                    val ecKey = try {
                        cert.ecPublicKey
                    } catch (e: Throwable) {
                        null
                    }

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
                            +"Subject Public Key Info"
                        }

                        if (ecKey != null) {
                            div {
                                css {
                                    display = Display.grid
                                    gridTemplateColumns = "repeat(2, 1fr)".unsafeCast<GridTemplateColumns>()
                                    gap = 20.px
                                }
                                div {
                                    css {
                                        display = Display.flex
                                        flexDirection = FlexDirection.column
                                        gap = 4.px
                                    }
                                    span { 
                                        css { 
                                            color = Color("#64748b")
                                            fontWeight = FontWeight.bold
                                            fontSize = 12.px
                                            textTransform = TextTransform.uppercase 
                                        }
                                        +"Key Type"
                                    }
                                    span { 
                                        css { 
                                            color = Color("#f1f5f9")
                                            fontSize = 14.px 
                                        }
                                        +"Elliptic Curve Public Key (EC)"
                                    }
                                }
                                div {
                                    css {
                                        display = Display.flex
                                        flexDirection = FlexDirection.column
                                        gap = 4.px
                                    }
                                    span { 
                                        css { 
                                            color = Color("#64748b")
                                            fontWeight = FontWeight.bold
                                            fontSize = 12.px
                                            textTransform = TextTransform.uppercase 
                                        }
                                        +"Curve Name"
                                    }
                                    span { 
                                        css { 
                                            color = Color("#38bdf8")
                                            fontSize = 14.px
                                            fontWeight = FontWeight.bold 
                                        }
                                        +ecKey.curve.name
                                    }
                                }
                            }
                            
                            val coordsString = when (ecKey) {
                                is EcPublicKeyDoubleCoordinate -> "X: ${ecKey.x.toHex()}\nY: ${ecKey.y.toHex()}"
                                is EcPublicKeyOkp -> "X: ${ecKey.x.toHex()}"
                            }

                            div {
                                css {
                                    display = Display.flex
                                    flexDirection = FlexDirection.column
                                    gap = 4.px
                                }
                                span { 
                                    css { 
                                        color = Color("#64748b")
                                        fontWeight = FontWeight.bold
                                        fontSize = 12.px
                                        textTransform = TextTransform.uppercase 
                                    }
                                    +"Public Key Coordinates"
                                }
                                pre {
                                    css {
                                        background = Color("#1e293b")
                                        padding = 16.px
                                        borderRadius = 8.px
                                        fontFamily = FontFamily.monospace
                                        fontSize = 12.px
                                        color = Color("#94a3b8")
                                        marginTop = 8.px
                                        overflowX = "auto".unsafeCast<Overflow>()
                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    }
                                    +coordsString
                                }
                            }
                        } else {
                            div {
                                css {
                                    display = Display.flex
                                    alignItems = AlignItems.center
                                    gap = 12.px
                                    color = Color("#ef4444")
                                    borderTop = Border(1.px, LineStyle.solid, Color("#334155"))
                                    paddingTop = 16.px
                                }
                                span {
                                    css {
                                        fontWeight = FontWeight.bold
                                        fontSize = 14.px
                                    }
                                    +"Non-EC key or unsupported Elliptic Curve format"
                                }
                            }
                        }
                    }

                    // Card 4: Extensions List
                    if (cert.extensions.isNotEmpty()) {
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
                                +"Certificate Extensions (${cert.extensions.size})"
                            }

                            div {
                                css {
                                    display = Display.flex
                                    flexDirection = FlexDirection.column
                                    gap = 16.px
                                    marginTop = 8.px
                                }
                                
                                cert.extensions.forEach { ext ->
                                    div {
                                        css {
                                            background = Color("#1e293b")
                                            borderRadius = 8.px
                                            padding = 16.px
                                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                                        }
                                        
                                        val oidEntry = OID.lookupByOid(ext.oid)
                                        val extName = oidEntry?.description ?: ext.oid
                                        
                                        div {
                                            css {
                                                fontWeight = FontWeight.bold
                                                color = Color("#f1f5f9")
                                                display = Display.flex
                                                justifyContent = JustifyContent.spaceBetween
                                                alignItems = AlignItems.center
                                                marginBottom = 8.px
                                            }
                                            span { 
                                                if (oidEntry != null) {
                                                    +"$extName (${ext.oid})"
                                                } else {
                                                    +ext.oid
                                                }
                                            }
                                            if (ext.isCritical) {
                                                span {
                                                    css {
                                                        fontSize = 11.px
                                                        backgroundColor = Color("#7f1d1d")
                                                        color = Color("#fca5a5")
                                                        padding = Padding(2.px, 8.px)
                                                        borderRadius = 4.px
                                                        fontWeight = FontWeight.bold
                                                    }
                                                    +"CRITICAL"
                                                }
                                            } else {
                                                span {
                                                    css {
                                                        fontSize = 11.px
                                                        backgroundColor = Color("#0f172a")
                                                        color = Color("#94a3b8")
                                                        padding = Padding(2.px, 8.px)
                                                        borderRadius = 4.px
                                                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                                                    }
                                                    +"NON-CRITICAL"
                                                }
                                            }
                                        }
                                        
                                        val displayValue = formatExtensionValue(cert, ext.oid, ext.data.toByteArray())
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
                                            +displayValue
                                        }
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
                +"Decode and view metadata of an X.509 Certificate. Supports PEM format (carrying BEGIN/END headers), raw base64 or hex encoded certificate bytes."
            }

            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +"X.509 Certificate (PEM, Base64, or Hex):"
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
                placeholder = "Paste certificate here (PEM format, Hex or Base64 bytes)..."
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
                            val cert = if (cleanInput.contains("-----BEGIN")) {
                                X509Cert.fromPem(cleanInput)
                            } else {
                                val bytes = decodeInputToBytes(cleanInput)
                                X509Cert(ByteString(bytes))
                            }
                            
                            // Eagerly evaluate core fields to check parsing success
                            cert.subject.name
                            cert.issuer.name
                            cert.serialNumber
                            cert.version
                            cert.validityNotBefore
                            cert.validityNotAfter
                            
                            parsedCert = cert
                            parseError = ""
                        } catch (e: Throwable) {
                            parseError = "Error parsing certificate: " + (e.message ?: "Unknown error")
                            parsedCert = null
                        }
                    }
                }
                +"Parse Certificate"
            }
        }
    }
}

private fun formatExtensionValue(cert: X509Cert, extOid: String, extData: ByteArray): String {
    return when (extOid) {
        OID.X509_EXTENSION_SUBJECT_KEY_IDENTIFIER.oid ->
            cert.subjectKeyIdentifier?.toHex(byteDivider = " ") ?: ""

        OID.X509_EXTENSION_KEY_USAGE.oid ->
            cert.keyUsage.joinToString(", ") { it.description }

        OID.X509_EXTENSION_BASIC_CONSTRAINTS.oid -> {
            try {
                val seq = ASN1.decode(extData) as ASN1Sequence
                val sb = StringBuilder("CA: ${(seq.elements[0] as ASN1Boolean).value}\n")
                if (seq.elements.size > 1) {
                    sb.append("pathLenConstraint: ${(seq.elements[1] as ASN1Integer).toLong()}\n")
                }
                sb.toString()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Error decoding: $e"
            }
        }

        OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid ->
            cert.authorityKeyIdentifier?.toHex(byteDivider = " ") ?: ""

        OID.X509_EXTENSION_ANDROID_KEYSTORE_ATTESTATION.oid ->
            AndroidAttestationExtensionParser(cert).prettyPrint()

        OID.X509_EXTENSION_ANDROID_KEYSTORE_PROVISIONING_INFORMATION.oid ->
            Cbor.toDiagnostics(
                extData,
                setOf(DiagnosticOption.PRETTY_PRINT),
            )

        OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid ->
            MultipazExtension.fromCbor(extData).prettyPrint()

        else -> {
            try {
                ASN1.print(ASN1.decode(extData)!!)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                extData.toHex(byteDivider = " ", decodeAsString = true)
            }
        }
    }
}
