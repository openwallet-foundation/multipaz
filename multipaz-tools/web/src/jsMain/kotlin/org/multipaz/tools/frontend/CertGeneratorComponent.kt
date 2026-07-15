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
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.*
import web.html.InputType
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Clock
import kotlinx.datetime.Instant
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.asn1.ASN1Integer
import org.multipaz.util.toHex

val CertGeneratorComponent = FC {
    var activeSubTab by useState("iaca") // "iaca", "ds", "reader-root", "reader"
    var isGenerating by useState(false)
    var errorMsg by useState("")
    var randomBits by useState(128)

    // Common Inputs
    var subjectDn by useState("CN=Test Identity,O=Multipaz,C=ZZ")
    var serialNumber by useState("1")
    var validityDays by useState("1200")
    var curve by useState(EcCurve.P256)

    // Sub-tab Specific Inputs
    var issuerAltNameUrl by useState("http://iaca.example.com")
    var crlUrl by useState("http://example.com/crl")
    var dnsName by useState("reader.example.com")

    // Signing Key/Cert inputs (for DS & Reader certificates)
    var signingKeyPem by useState("")
    var signingCertPem by useState("")

    // Outputs
    var generatedCertPem by useState("")
    var generatedPrivateKeyPem by useState("")
    var copyCertSuccess by useState(false)
    var copyKeySuccess by useState(false)

    // Helpers to reset outputs
    fun resetOutputs() {
        generatedCertPem = ""
        generatedPrivateKeyPem = ""
        copyCertSuccess = false
        copyKeySuccess = false
        errorMsg = ""
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
            +"Certificate Generator"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Generate mDoc standard-compliant certificates directly in your browser. All private keys and signatures are generated securely client-side."
        }

        // Sub-tabs navigation
        div {
            css {
                display = Display.flex
                gap = 8.px
                background = Color("#0f172a")
                padding = 6.px
                borderRadius = 10.px
                marginBottom = 28.px
                maxWidth = 700.px
            }

            listOf(
                "iaca" to "IACA Certificate",
                "ds" to "DS Certificate",
                "reader-root" to "Reader Root Cert",
                "reader" to "Reader Cert"
            ).forEach { (subTabId, title) ->
                button {
                    css {
                        padding = Padding(8.px, 16.px)
                        border = None.none
                        borderRadius = 6.px
                        fontSize = 13.px
                        fontWeight = FontWeight.bold
                        cursor = Cursor.pointer
                        transition = "all 0.2s".unsafeCast<Transition>()
                        if (activeSubTab == subTabId) {
                            background = Color("#3b82f6")
                            color = Color("#ffffff")
                        } else {
                            background = Color("transparent")
                            color = Color("#94a3b8")
                            hover {
                                background = Color("#1e293b")
                                color = Color("#f1f5f9")
                            }
                        }
                    }
                    onClick = {
                        activeSubTab = subTabId
                        resetOutputs()
                        // Set sensible defaults for each tab
                        when (subTabId) {
                            "iaca" -> {
                                subjectDn = "CN=Test IACA Root CA,O=Multipaz,C=US"
                                serialNumber = "1"
                                validityDays = "1200"
                            }
                            "ds" -> {
                                subjectDn = "CN=Test Document Signer,O=Multipaz,C=US"
                                serialNumber = "1"
                                validityDays = "365"
                            }
                            "reader-root" -> {
                                subjectDn = "CN=Test Reader Root CA,O=Multipaz,C=US"
                                serialNumber = "1"
                                validityDays = "1200"
                            }
                            "reader" -> {
                                subjectDn = "CN=Test Reader Client,O=Multipaz,C=US"
                                serialNumber = "1"
                                validityDays = "180"
                            }
                        }
                    }
                    +title
                }
            }
        }

        // Grid for general options
        div {
            css {
                display = Display.grid
                gridTemplateColumns = "repeat(auto-fit, minmax(280px, 1fr))".unsafeCast<GridTemplateColumns>()
                gap = 20.px
                marginBottom = 24.px
            }

            div {
                label {
                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                    +"Subject Distinguished Name (DN):"
                }
                input {
                    type = "text".unsafeCast<InputType>()
                    css {
                        width = 100.pct
                        padding = 10.px
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 6.px
                        color = Color("#f1f5f9")
                    }
                    value = subjectDn
                    onChange = { subjectDn = it.target.value }
                }
            }

            div {
                label {
                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                    +"Serial Number:"
                }
                div {
                    css {
                        display = Display.flex
                        gap = 8.px
                    }
                    input {
                        type = "text".unsafeCast<InputType>()
                        css {
                            flexGrow = number(1.0)
                            padding = 10.px
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            color = Color("#f1f5f9")
                        }
                        value = serialNumber
                        onChange = { serialNumber = it.target.value }
                    }
                    select {
                        css {
                            padding = 10.px
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569"))
                            borderRadius = 6.px
                            color = Color("#f1f5f9")
                        }
                        value = randomBits.toString()
                        onChange = { randomBits = it.target.value.toInt() }
                        listOf(64, 128, 160, 256).forEach { bits ->
                            option {
                                value = bits.toString()
                                +"$bits-bit"
                            }
                        }
                    }
                    button {
                        css {
                            padding = Padding(10.px, 16.px)
                            background = Color("#334155")
                            border = None.none
                            borderRadius = 6.px
                            color = Color("#60a5fa")
                            fontWeight = FontWeight.bold
                            cursor = Cursor.pointer
                            hover {
                                background = Color("#475569")
                            }
                        }
                        onClick = {
                            val randInt = ASN1Integer.fromRandom(randomBits)
                            serialNumber = asn1IntegerToString(randInt)
                        }
                        +"Random"
                    }
                }
            }

            div {
                label {
                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                    +"Validity Period (Days):"
                }
                input {
                    type = "number".unsafeCast<InputType>()
                    css {
                        width = 100.pct
                        padding = 10.px
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 6.px
                        color = Color("#f1f5f9")
                    }
                    value = validityDays
                    onChange = { validityDays = it.target.value }
                }
                span {
                    css {
                        display = Display.block
                        fontSize = 11.px
                        marginTop = 6.px
                        color = Color("#94a3b8")
                    }
                    when (activeSubTab) {
                        "iaca" -> +"Recommended period: 3 to 5 years (1095 to 1825 days)."
                        "ds" -> +"Maximum allowed period: 457 days."
                        "reader" -> +"Maximum allowed period: 187 days."
                        "reader-root" -> +"No validity requirements for Reader Root CA."
                    }
                }
            }

            div {
                label {
                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                    +"Cryptographic Curve:"
                }
                select {
                    css {
                        width = 100.pct
                        padding = 10.px
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#475569"))
                        borderRadius = 6.px
                        color = Color("#f1f5f9")
                    }
                    value = curve.name
                    onChange = { curve = EcCurve.valueOf(it.target.value) }
                    Crypto.supportedCurves.sortedBy { it.name }.forEach { c ->
                        option {
                            value = c.name
                            +c.name
                        }
                    }
                }
            }
        }

        // Sub-tab specific configuration sections
        if (activeSubTab == "iaca") {
            div {
                css {
                    display = Display.grid
                    gridTemplateColumns = "repeat(auto-fit, minmax(280px, 1fr))".unsafeCast<GridTemplateColumns>()
                    gap = 20.px
                    marginBottom = 24.px
                }
                div {
                    label {
                        css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                        +"Issuer Alt Name (URL):"
                    }
                    input {
                        type = "text".unsafeCast<InputType>()
                        css {
                            width = 100.pct; padding = 10.px; background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9")
                        }
                        value = issuerAltNameUrl
                        onChange = { issuerAltNameUrl = it.target.value }
                    }
                }
                div {
                    label {
                        css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                        +"CRL Distribution Point (URL):"
                    }
                    input {
                        type = "text".unsafeCast<InputType>()
                        css {
                            width = 100.pct; padding = 10.px; background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9")
                        }
                        value = crlUrl
                        onChange = { crlUrl = it.target.value }
                    }
                }
            }
        }

        if (activeSubTab == "reader-root") {
            div {
                css {
                    maxWidth = 400.px
                    marginBottom = 24.px
                }
                label {
                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                    +"CRL Distribution Point (URL):"
                }
                input {
                    type = "text".unsafeCast<InputType>()
                    css {
                        width = 100.pct; padding = 10.px; background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9")
                    }
                    value = crlUrl
                    onChange = { crlUrl = it.target.value }
                }
            }
        }

        if (activeSubTab == "reader") {
            div {
                css {
                    maxWidth = 400.px
                    marginBottom = 24.px
                }
                label {
                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                    +"DNS Name (Optional):"
                }
                input {
                    type = "text".unsafeCast<InputType>()
                    css {
                        width = 100.pct; padding = 10.px; background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9")
                    }
                    value = dnsName
                    onChange = { dnsName = it.target.value }
                }
            }
        }

        // Signing credentials input for DS & Reader Certs
        if (activeSubTab == "ds" || activeSubTab == "reader") {
            div {
                css {
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                    borderRadius = 12.px
                    padding = 24.px
                    marginBottom = 28.px
                }

                div {
                    css {
                        display = Display.flex
                        justifyContent = JustifyContent.spaceBetween
                        alignItems = AlignItems.center
                        marginBottom = 16.px
                    }
                    h3 {
                        css { margin = 0.px; fontSize = 1.2.rem; color = Color("#f1f5f9") }
                        +(if (activeSubTab == "ds") "Signing Authority (IACA CA)" else "Signing Authority (Reader Root CA)")
                    }
                    button {
                        css {
                            background = Color("#334155")
                            border = None.none
                            color = Color("#60a5fa")
                            padding = Padding(6.px, 12.px)
                            borderRadius = 6.px
                            cursor = Cursor.pointer
                            fontSize = 12.px
                            fontWeight = FontWeight.bold
                            hover {
                                background = Color("#1e293b")
                            }
                        }
                        onClick = {
                            mainScope.launch {
                                try {
                                    val key = Crypto.createEcPrivateKey(EcCurve.P256)
                                    val cert = if (activeSubTab == "ds") {
                                        MdocUtil.generateIacaCertificate(
                                            iacaKey = AsymmetricKey.AnonymousExplicit(key),
                                            subject = X500Name.fromName("CN=Demo IACA Root,O=Multipaz,C=US"),
                                            serial = ASN1Integer(999L),
                                            validFrom = Clock.System.now(),
                                            validUntil = Clock.System.now() + 365.days,
                                            issuerAltNameUrl = "http://iaca.example.com",
                                            crlUrl = "http://iaca.example.com/crl"
                                        )
                                    } else {
                                        MdocUtil.generateReaderRootCertificate(
                                            readerRootKey = AsymmetricKey.AnonymousExplicit(key),
                                            subject = X500Name.fromName("CN=Demo Reader Root,O=Multipaz,C=US"),
                                            serial = ASN1Integer(999L),
                                            validFrom = Clock.System.now(),
                                            validUntil = Clock.System.now() + 365.days,
                                            crlUrl = "http://reader.example.com/crl"
                                        )
                                    }
                                    signingKeyPem = key.toPem()
                                    signingCertPem = cert.toPem()
                                    errorMsg = ""
                                } catch (e: Throwable) {
                                    errorMsg = "Failed to generate demo authority: " + e.message
                                }
                            }
                        }
                        +"Auto-generate Demo CA Authority"
                    }
                }

                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(auto-fit, minmax(300px, 1fr))".unsafeCast<GridTemplateColumns>()
                        gap = 20.px
                    }

                    div {
                        label {
                            css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                            +"CA Private Key PEM:"
                        }
                        textarea {
                            css {
                                width = 100.pct; height = 140.px; background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 8.px
                                color = Color("#38bdf8"); fontFamily = FontFamily.monospace; padding = 10.px; fontSize = 12.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            value = signingKeyPem
                            placeholder = "-----BEGIN PRIVATE KEY-----"
                            onChange = { signingKeyPem = it.target.value }
                        }
                    }

                    div {
                        label {
                            css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                            +"CA Certificate PEM:"
                        }
                        textarea {
                            css {
                                width = 100.pct; height = 140.px; background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 8.px
                                color = Color("#34d399"); fontFamily = FontFamily.monospace; padding = 10.px; fontSize = 12.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            value = signingCertPem
                            placeholder = "-----BEGIN CERTIFICATE-----"
                            onChange = { signingCertPem = it.target.value }
                        }
                    }
                }
            }
        }

        // Generate Action Button
        button {
            css {
                padding = Padding(12.px, 28.px)
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
            disabled = isGenerating || subjectDn.trim().isEmpty() || serialNumber.trim().isEmpty() || validityDays.trim().isEmpty()
            onClick = {
                mainScope.launch {
                    isGenerating = true
                    errorMsg = ""
                    try {
                        val parsedSubject = X500Name.fromName(subjectDn.trim())
                        val parsedSerial = stringToAsn1Integer(serialNumber.trim())
                        val now = Clock.System.now()
                        val expiration = now + validityDays.trim().toInt().days

                        // Generate certificate's public/private key pair
                        val generatedKey = Crypto.createEcPrivateKey(curve)
                        generatedPrivateKeyPem = generatedKey.toPem()

                        val cert = when (activeSubTab) {
                            "iaca" -> {
                                MdocUtil.generateIacaCertificate(
                                    iacaKey = AsymmetricKey.AnonymousExplicit(generatedKey),
                                    subject = parsedSubject,
                                    serial = parsedSerial,
                                    validFrom = now,
                                    validUntil = expiration,
                                    issuerAltNameUrl = issuerAltNameUrl.trim(),
                                    crlUrl = crlUrl.trim()
                                )
                            }
                            "reader-root" -> {
                                MdocUtil.generateReaderRootCertificate(
                                    readerRootKey = AsymmetricKey.AnonymousExplicit(generatedKey),
                                    subject = parsedSubject,
                                    serial = parsedSerial,
                                    validFrom = now,
                                    validUntil = expiration,
                                    crlUrl = crlUrl.trim()
                                )
                            }
                            "ds" -> {
                                if (signingKeyPem.trim().isEmpty() || signingCertPem.trim().isEmpty()) {
                                    throw IllegalArgumentException("Signing IACA Private Key and Certificate are required.")
                                }
                                val iacaCert = X509Cert.fromPem(signingCertPem.trim())
                                val iacaPriv = EcPrivateKey.fromPem(signingKeyPem.trim(), iacaCert.ecPublicKey)
                                val certifiedKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaPriv)
                                MdocUtil.generateDsCertificate(
                                    iacaKey = certifiedKey,
                                    dsKey = generatedKey.publicKey,
                                    subject = parsedSubject,
                                    serial = parsedSerial,
                                    validFrom = now,
                                    validUntil = expiration
                                )
                            }
                            "reader" -> {
                                if (signingKeyPem.trim().isEmpty() || signingCertPem.trim().isEmpty()) {
                                    throw IllegalArgumentException("Signing Reader Root Private Key and Certificate are required.")
                                }
                                val rootCert = X509Cert.fromPem(signingCertPem.trim())
                                val rootPriv = EcPrivateKey.fromPem(signingKeyPem.trim(), rootCert.ecPublicKey)
                                val certifiedKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootPriv)
                                MdocUtil.generateReaderCertificate(
                                    readerRootKey = certifiedKey,
                                    readerKey = generatedKey.publicKey,
                                    subject = parsedSubject,
                                    dnsName = if (dnsName.trim().isNotEmpty()) dnsName.trim() else null,
                                    serial = parsedSerial,
                                    validFrom = now,
                                    validUntil = expiration
                                )
                            }
                            else -> throw IllegalStateException("Unknown sub-tab type")
                        }

                        generatedCertPem = cert.toPem()
                        copyCertSuccess = false
                        copyKeySuccess = false
                    } catch (e: Throwable) {
                        errorMsg = "Generation Error: " + (e.message ?: "Failed to generate certificate")
                    } finally {
                        isGenerating = false
                    }
                }
            }
            if (isGenerating) +"Generating..." else +"Generate Certificate"
        }

        // Error display
        if (errorMsg.isNotEmpty()) {
            div {
                css {
                    marginTop = 20.px
                    background = Color("#7f1d1d")
                    border = Border(1.px, LineStyle.solid, Color("#fca5a5"))
                    borderRadius = 8.px
                    padding = Padding(10.px, 16.px)
                    color = Color("#fca5a5")
                    fontWeight = FontWeight.bold
                }
                +errorMsg
            }
        }

        // Result outputs
        if (generatedCertPem.isNotEmpty()) {
            div {
                css {
                    marginTop = 32.px
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 24.px
                }

                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(auto-fit, minmax(320px, 1fr))".unsafeCast<GridTemplateColumns>()
                        gap = 24.px
                    }

                    // Certificate Output Card
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 12.px
                            padding = 24.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 12.px
                        }
                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                            }
                            h3 {
                                css { margin = 0.px; fontSize = 1.2.rem; color = Color("#f1f5f9") }
                                +"Generated Certificate"
                            }
                            button {
                                css {
                                    background = Color(if (copyCertSuccess) "#10b981" else "#334155")
                                    border = None.none
                                    color = Color("#ffffff")
                                    padding = Padding(6.px, 12.px)
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    fontSize = 12.px
                                    fontWeight = FontWeight.bold
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(generatedCertPem)
                                    copyCertSuccess = true
                                }
                                +(if (copyCertSuccess) "Copied!" else "Copy")
                            }
                        }
                        textarea {
                            css {
                                width = 100.pct; height = 240.px; background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#334155")); borderRadius = 8.px
                                color = Color("#34d399"); fontFamily = FontFamily.monospace; padding = 12.px; fontSize = 12.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            readOnly = true
                            value = generatedCertPem
                        }
                    }

                    // Private Key Output Card
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 12.px
                            padding = 24.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 12.px
                        }
                        div {
                            css {
                                display = Display.flex
                                justifyContent = JustifyContent.spaceBetween
                                alignItems = AlignItems.center
                            }
                            h3 {
                                css { margin = 0.px; fontSize = 1.2.rem; color = Color("#f1f5f9") }
                                +"Private Key"
                            }
                            button {
                                css {
                                    background = Color(if (copyKeySuccess) "#10b981" else "#334155")
                                    border = None.none
                                    color = Color("#ffffff")
                                    padding = Padding(6.px, 12.px)
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    fontSize = 12.px
                                    fontWeight = FontWeight.bold
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(generatedPrivateKeyPem)
                                    copyKeySuccess = true
                                }
                                +(if (copyKeySuccess) "Copied!" else "Copy")
                            }
                        }
                        textarea {
                            css {
                                width = 100.pct; height = 240.px; background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#334155")); borderRadius = 8.px
                                color = Color("#38bdf8"); fontFamily = FontFamily.monospace; padding = 12.px; fontSize = 12.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            readOnly = true
                            value = generatedPrivateKeyPem
                        }
                    }
                }
            }
        }
    }
}

private fun asn1IntegerToString(asn1Int: ASN1Integer): String {
    val hex = asn1Int.value.toHex()
    return try {
        val bigInt = js("BigInt('0x' + hex)")
        bigInt.toString(10).toString()
    } catch (e: Throwable) {
        "1"
    }
}

private fun stringToAsn1Integer(str: String): ASN1Integer {
    val trimmed = str.trim()
    try {
        return ASN1Integer(trimmed.toLong())
    } catch (e: Throwable) {
        // Fall back to BigInt for large numbers
    }

    try {
        val bigInt = js("BigInt(trimmed)")
        var hex = bigInt.toString(16).toString()
        if (hex.length % 2 != 0) {
            hex = "0$hex"
        }
        val bytes = decodeHex(hex)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) {
            return ASN1Integer(byteArrayOf(0x00.toByte()) + bytes)
        }
        return ASN1Integer(bytes)
    } catch (e: Throwable) {
        throw IllegalArgumentException("Invalid serial number format: $str")
    }
}

private fun decodeHex(hex: String): ByteArray {
    val result = ByteArray(hex.length / 2)
    for (i in result.indices) {
        val index = i * 2
        val octet = hex.substring(index, index + 2).toInt(16)
        result[i] = octet.toByte()
    }
    return result
}
