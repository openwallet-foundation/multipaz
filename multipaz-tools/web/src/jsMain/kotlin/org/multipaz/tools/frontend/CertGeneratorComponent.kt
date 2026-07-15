@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class
)
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
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1Boolean
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    var useCustomKey by useState(false)
    var customPrivateKeyPem by useState("")

    // Sub-tab Specific Inputs
    var issuerAltNameUrl by useState("http://iaca.example.com")
    var crlUrl by useState("http://example.com/crl")
    var dnsName by useState("reader.example.com")

    // Signing Key/Cert inputs (for DS & Reader certificates)
    var signingKeyPem by useState("")
    var signingCertPem by useState("")

    // Generic Tab State
    var genericIsCa by useState(false)
    var genericPathLen by useState("")
    var genericKeyUsageEnabled by useState(false)
    var genericKeyUsages by useState(setOf<X509KeyUsage>())
    var genericEkuEnabled by useState(false)
    var genericExtendedKeyUsages by useState(setOf<String>())
    var genericBasicConstraintsEnabled by useState(false)
    var genericSanDns by useState("")
    var genericSanUri by useState("")
    var genericIssuerAltName by useState("")
    var genericCrlUrl by useState("")
    var genericIncludeSubjectKeyIdentifier by useState(true)
    var genericIncludeAuthorityKeyIdentifier by useState(true)
    var genericUseSigningCert by useState(false)
    var genericSigningKeyPem by useState("")
    var genericSigningCertPem by useState("")
    var genericCustomExtensions by useState(listOf<CustomExtItem>())

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
                "reader" to "Reader Cert",
                "generic" to "Generic Certificate"
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
                            "generic" -> {
                                subjectDn = "CN=Generic Test Cert,O=Multipaz,C=US"
                                serialNumber = "1"
                                validityDays = "365"
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

            label {
                css {
                    display = Display.flex
                    alignItems = AlignItems.center
                    gap = 8.px
                    fontWeight = FontWeight.bold
                    color = Color("#cbd5e1")
                    cursor = Cursor.pointer
                    marginTop = 16.px
                    marginBottom = 16.px
                }
                input {
                    type = "checkbox".unsafeCast<InputType>()
                    checked = useCustomKey
                    onChange = {
                        useCustomKey = it.target.checked
                        resetOutputs()
                    }
                }
                +"Use custom private key (PEM or JWK)"
            }

            if (useCustomKey) {
                div {
                    css {
                        marginBottom = 24.px
                    }
                    label {
                        css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1") }
                        +"Custom Private Key (PEM or JWK format):"
                    }
                    textarea {
                        css {
                            width = 100.pct; height = 120.px; background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 8.px
                            color = Color("#34d399"); fontFamily = FontFamily.monospace; padding = 10.px; fontSize = 12.px
                            resize = "none".unsafeCast<Resize>()
                        }
                        value = customPrivateKeyPem
                        placeholder = "-----BEGIN PRIVATE KEY-----\n...\n\nor\n\n{\n  \"kty\": \"EC\",\n  \"crv\": \"P-256\",\n  \"x\": \"...\",\n  \"y\": \"...\",\n  \"d\": \"...\"\n}"
                        onChange = {
                            customPrivateKeyPem = it.target.value
                            resetOutputs()
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

        if (activeSubTab == "generic") {
            div {
                css {
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 20.px
                    marginBottom = 28.px
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                    borderRadius = 12.px
                    padding = 24.px
                }

                h3 {
                    css { margin = 0.px; fontSize = 1.3.rem; color = Color("#f1f5f9"); borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); paddingBottom = 8.px }
                    +"Generic Extensions & Configuration"
                }

                // 1. Basic Constraints & CA
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 12.px
                    }
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#f1f5f9"); fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                        input {
                            type = "checkbox".unsafeCast<InputType>()
                            checked = genericBasicConstraintsEnabled
                            onChange = { genericBasicConstraintsEnabled = it.target.checked }
                        }
                        +"Enable Basic Constraints Extension"
                    }
                    if (genericBasicConstraintsEnabled) {
                        div {
                            css { display = Display.flex; gap = 20.px; paddingLeft = 24.px }
                            label {
                                css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#cbd5e1"); cursor = Cursor.pointer }
                                input {
                                    type = "checkbox".unsafeCast<InputType>()
                                    checked = genericIsCa
                                    onChange = { genericIsCa = it.target.checked }
                                }
                                +"Is Certificate Authority (CA)"
                            }
                            if (genericIsCa) {
                                div {
                                    css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px }
                                    span { css { color = Color("#94a3b8"); fontSize = 13.px }; +"Path Length Constraint:" }
                                    input {
                                        type = "number".unsafeCast<InputType>()
                                        css {
                                            width = 80.px; padding = 6.px; background = Color("#1e293b")
                                            border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9")
                                        }
                                        value = genericPathLen
                                        placeholder = "None"
                                        onChange = { genericPathLen = it.target.value }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Key Usage
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 12.px
                    }
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#f1f5f9"); fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                        input {
                            type = "checkbox".unsafeCast<InputType>()
                            checked = genericKeyUsageEnabled
                            onChange = { genericKeyUsageEnabled = it.target.checked }
                        }
                        +"Enable Key Usage Extension"
                    }
                    if (genericKeyUsageEnabled) {
                        div {
                            css {
                                display = Display.grid
                                gridTemplateColumns = "repeat(auto-fit, minmax(180px, 1fr))".unsafeCast<GridTemplateColumns>()
                                gap = 10.px
                                paddingLeft = 24.px
                            }
                            X509KeyUsage.entries.forEach { usage ->
                                label {
                                    css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#cbd5e1"); cursor = Cursor.pointer; fontSize = 13.px }
                                    input {
                                        type = "checkbox".unsafeCast<InputType>()
                                        checked = genericKeyUsages.contains(usage)
                                        onChange = {
                                            genericKeyUsages = if (it.target.checked) {
                                                genericKeyUsages + usage
                                            } else {
                                                genericKeyUsages - usage
                                            }
                                        }
                                    }
                                    +usage.description
                                }
                            }
                        }
                    }
                }

                // 3. Extended Key Usage (EKU)
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 12.px
                    }
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#f1f5f9"); fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                        input {
                            type = "checkbox".unsafeCast<InputType>()
                            checked = genericEkuEnabled
                            onChange = { genericEkuEnabled = it.target.checked }
                        }
                        +"Enable Extended Key Usage (EKU) Extension"
                    }
                    if (genericEkuEnabled) {
                        div {
                            css {
                                display = Display.grid
                                gridTemplateColumns = "repeat(auto-fit, minmax(220px, 1fr))".unsafeCast<GridTemplateColumns>()
                                gap = 10.px
                                paddingLeft = 24.px
                            }
                            listOf(
                                "1.3.6.1.5.5.7.3.1" to "Server Authentication",
                                "1.3.6.1.5.5.7.3.2" to "Client Authentication",
                                "1.3.6.1.5.5.7.3.3" to "Code Signing",
                                "1.3.6.1.5.5.7.3.4" to "Email Protection",
                                "1.3.6.1.5.5.7.3.8" to "Time Stamping",
                                "1.3.6.1.5.5.7.3.9" to "OCSP Signing"
                            ).forEach { (oid, name) ->
                                label {
                                    css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#cbd5e1"); cursor = Cursor.pointer; fontSize = 13.px }
                                    input {
                                        type = "checkbox".unsafeCast<InputType>()
                                        checked = genericExtendedKeyUsages.contains(oid)
                                        onChange = {
                                            genericExtendedKeyUsages = if (it.target.checked) {
                                                genericExtendedKeyUsages + oid
                                            } else {
                                                genericExtendedKeyUsages - oid
                                            }
                                        }
                                    }
                                    +"$name ($oid)"
                                }
                            }
                        }
                    }
                }

                // 4. SAN DNS Names and URIs
                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(auto-fit, minmax(280px, 1fr))".unsafeCast<GridTemplateColumns>()
                        gap = 20.px
                    }
                    div {
                        label {
                            css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                            +"Subject Alternative Name - DNS Name:"
                        }
                        input {
                            type = "text".unsafeCast<InputType>()
                            css { width = 100.pct; padding = 10.px; background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9") }
                            value = genericSanDns
                            placeholder = "e.g. example.com"
                            onChange = { genericSanDns = it.target.value }
                        }
                    }
                    div {
                        label {
                            css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                            +"Subject Alternative Name - URI:"
                        }
                        input {
                            type = "text".unsafeCast<InputType>()
                            css { width = 100.pct; padding = 10.px; background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9") }
                            value = genericSanUri
                            placeholder = "e.g. urn:example:service"
                            onChange = { genericSanUri = it.target.value }
                        }
                    }
                }

                // 5. Issuer Alt Name and CRL Distribution Points
                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(auto-fit, minmax(280px, 1fr))".unsafeCast<GridTemplateColumns>()
                        gap = 20.px
                    }
                    div {
                        label {
                            css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                            +"Issuer Alternative Name (URL):"
                        }
                        input {
                            type = "text".unsafeCast<InputType>()
                            css { width = 100.pct; padding = 10.px; background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9") }
                            value = genericIssuerAltName
                            placeholder = "e.g. http://iaca.example.com"
                            onChange = { genericIssuerAltName = it.target.value }
                        }
                    }
                    div {
                        label {
                            css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                            +"CRL Distribution Point (URL):"
                        }
                        input {
                            type = "text".unsafeCast<InputType>()
                            css { width = 100.pct; padding = 10.px; background = Color("#1e293b"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9") }
                            value = genericCrlUrl
                            placeholder = "e.g. http://example.com/crl"
                            onChange = { genericCrlUrl = it.target.value }
                        }
                    }
                }

                // 6. Subject Key Identifier and Authority Key Identifier
                div {
                    css {
                        display = Display.flex
                        gap = 24.px
                    }
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#cbd5e1"); cursor = Cursor.pointer; fontSize = 13.px }
                        input {
                            type = "checkbox".unsafeCast<InputType>()
                            checked = genericIncludeSubjectKeyIdentifier
                            onChange = { genericIncludeSubjectKeyIdentifier = it.target.checked }
                        }
                        +"Include Subject Key Identifier"
                    }
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#cbd5e1"); cursor = Cursor.pointer; fontSize = 13.px }
                        input {
                            type = "checkbox".unsafeCast<InputType>()
                            checked = genericIncludeAuthorityKeyIdentifier
                            onChange = { genericIncludeAuthorityKeyIdentifier = it.target.checked }
                        }
                        +"Include Authority Key Identifier"
                    }
                }

                // 7. Custom Extensions Block
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 12.px
                    }
                    div {
                        css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center }
                        span { css { color = Color("#cbd5e1"); fontWeight = FontWeight.bold }; +"Custom Extensions" }
                        button {
                            css {
                                padding = Padding(4.px, 10.px); fontSize = 12.px; fontWeight = FontWeight.bold
                                backgroundColor = Color("#1e293b"); color = Color("#60a5fa")
                                border = Border(1.px, LineStyle.solid, Color("#334155")); borderRadius = 6.px; cursor = Cursor.pointer
                                hover { backgroundColor = Color("#334155") }
                            }
                            onClick = {
                                genericCustomExtensions = genericCustomExtensions + CustomExtItem("", false, "")
                            }
                            +"+ Add Custom Extension"
                        }
                    }
                    genericCustomExtensions.forEachIndexed { i, ext ->
                        div {
                            css {
                                display = Display.flex; gap = 12.px; alignItems = AlignItems.center; background = Color("#1e293b")
                                padding = 12.px; borderRadius = 8.px; border = Border(1.px, LineStyle.solid, Color("#334155"))
                            }
                            input {
                                type = "text".unsafeCast<InputType>()
                                css { width = 160.px; padding = 6.px; background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#f1f5f9"); fontSize = 12.px }
                                value = ext.oid
                                placeholder = "OID (e.g. 1.2.3.4)"
                                onChange = { e ->
                                    genericCustomExtensions = genericCustomExtensions.toMutableList().apply {
                                        this[i] = this[i].copy(oid = e.target.value)
                                    }
                                }
                            }
                            label {
                                css { display = Display.flex; alignItems = AlignItems.center; gap = 6.px; color = Color("#cbd5e1"); cursor = Cursor.pointer; fontSize = 12.px }
                                input {
                                    type = "checkbox".unsafeCast<InputType>()
                                    checked = ext.isCritical
                                    onChange = { e ->
                                        genericCustomExtensions = genericCustomExtensions.toMutableList().apply {
                                            this[i] = this[i].copy(isCritical = e.target.checked)
                                        }
                                    }
                                }
                                +"Critical"
                            }
                            input {
                                type = "text".unsafeCast<InputType>()
                                css { flexGrow = number(1.0); padding = 6.px; background = Color("#0f172a"); border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 6.px; color = Color("#34d399"); fontSize = 12.px; fontFamily = FontFamily.monospace }
                                value = ext.hexData
                                placeholder = "Hex Data Value (e.g. 020101)"
                                onChange = { e ->
                                    genericCustomExtensions = genericCustomExtensions.toMutableList().apply {
                                        this[i] = this[i].copy(hexData = e.target.value)
                                    }
                                }
                            }
                            button {
                                css {
                                    padding = Padding(6.px, 10.px); fontSize = 12.px; fontWeight = FontWeight.bold
                                    backgroundColor = Color("#7f1d1d"); color = Color("#fca5a5")
                                    border = None.none; borderRadius = 6.px; cursor = Cursor.pointer
                                    hover { backgroundColor = Color("#991b1b") }
                                }
                                onClick = {
                                    genericCustomExtensions = genericCustomExtensions.toMutableList().apply {
                                        removeAt(i)
                                    }
                                }
                                +"Remove"
                            }
                        }
                    }
                }

                // 8. Signing CA Certificate
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 12.px
                        borderTop = Border(1.px, LineStyle.solid, Color("#1e293b"))
                        paddingTop = 16.px
                    }
                    label {
                        css { display = Display.flex; alignItems = AlignItems.center; gap = 8.px; color = Color("#f1f5f9"); fontWeight = FontWeight.bold; cursor = Cursor.pointer }
                        input {
                            type = "checkbox".unsafeCast<InputType>()
                            checked = genericUseSigningCert
                            onChange = { genericUseSigningCert = it.target.checked }
                        }
                        +"Sign with another CA Certificate (otherwise self-signed)"
                    }
                    if (genericUseSigningCert) {
                        div {
                            css {
                                display = Display.grid
                                gridTemplateColumns = "repeat(auto-fit, minmax(300px, 1fr))".unsafeCast<GridTemplateColumns>()
                                gap = 20.px
                                paddingLeft = 24.px
                            }

                            div {
                                label {
                                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                                    +"Signing CA Private Key (PEM or JWK):"
                                }
                                textarea {
                                    css {
                                        width = 100.pct; height = 120.px; background = Color("#1e293b")
                                        border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 8.px
                                        color = Color("#38bdf8"); fontFamily = FontFamily.monospace; padding = 10.px; fontSize = 12.px
                                        resize = "none".unsafeCast<Resize>()
                                    }
                                    value = genericSigningKeyPem
                                    placeholder = "-----BEGIN PRIVATE KEY-----\n..."
                                    onChange = { genericSigningKeyPem = it.target.value }
                                }
                            }

                            div {
                                label {
                                    css { display = Display.block; fontWeight = FontWeight.bold; marginBottom = 6.px; color = Color("#cbd5e1"); fontSize = 13.px }
                                    +"Signing CA Certificate PEM:"
                                }
                                textarea {
                                    css {
                                        width = 100.pct; height = 120.px; background = Color("#1e293b")
                                        border = Border(1.px, LineStyle.solid, Color("#475569")); borderRadius = 8.px
                                        color = Color("#34d399"); fontFamily = FontFamily.monospace; padding = 10.px; fontSize = 12.px
                                        resize = "none".unsafeCast<Resize>()
                                    }
                                    value = genericSigningCertPem
                                    placeholder = "-----BEGIN CERTIFICATE-----\n..."
                                    onChange = { genericSigningCertPem = it.target.value }
                                }
                            }
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

                        // Generate or parse certificate's public/private key pair
                        val certKey = if (useCustomKey) {
                            if (customPrivateKeyPem.trim().isEmpty()) {
                                throw IllegalArgumentException("Custom Private Key PEM is empty.")
                            }
                            val parsedPair = parsePrivateKeyAndPublicKey(customPrivateKeyPem.trim())
                            generatedPrivateKeyPem = ""
                            parsedPair.first
                        } else {
                            val generatedKey = Crypto.createEcPrivateKey(curve)
                            generatedPrivateKeyPem = generatedKey.toPem()
                            generatedKey
                        }

                        val cert = when (activeSubTab) {
                            "iaca" -> {
                                MdocUtil.generateIacaCertificate(
                                    iacaKey = AsymmetricKey.AnonymousExplicit(certKey),
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
                                    readerRootKey = AsymmetricKey.AnonymousExplicit(certKey),
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
                                    dsKey = certKey.publicKey,
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
                                    readerKey = certKey.publicKey,
                                    subject = parsedSubject,
                                    dnsName = if (dnsName.trim().isNotEmpty()) dnsName.trim() else null,
                                    serial = parsedSerial,
                                    validFrom = now,
                                    validUntil = expiration
                                )
                            }
                            "generic" -> {
                                val sKey = if (genericUseSigningCert) {
                                    if (genericSigningKeyPem.trim().isEmpty() || genericSigningCertPem.trim().isEmpty()) {
                                        throw IllegalArgumentException("Signing CA Private Key and Certificate are required.")
                                    }
                                    val parentCert = X509Cert.fromPem(genericSigningCertPem.trim())
                                    val parentPrivKey = parsePrivateKeyAndPublicKey(genericSigningKeyPem.trim()).first
                                    AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(parentCert)), parentPrivKey)
                                } else {
                                    AsymmetricKey.AnonymousExplicit(certKey)
                                }

                                val issuerName = if (genericUseSigningCert) {
                                    X509Cert.fromPem(genericSigningCertPem.trim()).subject
                                } else {
                                    parsedSubject
                                }

                                val b = X509Cert.Builder(
                                    publicKey = certKey.publicKey,
                                    signingKey = sKey,
                                    serialNumber = parsedSerial,
                                    subject = parsedSubject,
                                    issuer = issuerName,
                                    validFrom = now,
                                    validUntil = expiration
                                )

                                if (genericBasicConstraintsEnabled) {
                                    b.setBasicConstraints(
                                        ca = genericIsCa,
                                        pathLenConstraint = if (genericIsCa && genericPathLen.trim().isNotEmpty()) genericPathLen.trim().toInt() else null
                                    )
                                }

                                if (genericKeyUsageEnabled && genericKeyUsages.isNotEmpty()) {
                                    b.setKeyUsage(genericKeyUsages)
                                }

                                if (genericEkuEnabled && genericExtendedKeyUsages.isNotEmpty()) {
                                    val ekuSeq = ASN1Sequence(
                                        genericExtendedKeyUsages.map { ASN1ObjectIdentifier(it) }
                                    )
                                    b.addExtension(
                                        OID.X509_EXTENSION_EXTENDED_KEY_USAGE.oid,
                                        false,
                                        ASN1.encode(ekuSeq)
                                    )
                                }

                                val sanList = mutableListOf<ASN1Object>()
                                if (genericSanDns.trim().isNotEmpty()) {
                                    sanList.add(
                                        ASN1TaggedObject(
                                            ASN1TagClass.CONTEXT_SPECIFIC,
                                            ASN1Encoding.PRIMITIVE,
                                            2, // dNSName
                                            genericSanDns.trim().encodeToByteArray()
                                        )
                                    )
                                }
                                if (genericSanUri.trim().isNotEmpty()) {
                                    sanList.add(
                                        ASN1TaggedObject(
                                            ASN1TagClass.CONTEXT_SPECIFIC,
                                            ASN1Encoding.PRIMITIVE,
                                            6, // uniformResourceIdentifier
                                            genericSanUri.trim().encodeToByteArray()
                                        )
                                    )
                                }
                                if (sanList.isNotEmpty()) {
                                    b.addExtension(
                                        OID.X509_EXTENSION_SUBJECT_ALT_NAME.oid,
                                        false,
                                        ASN1.encode(ASN1Sequence(sanList))
                                    )
                                }

                                if (genericIssuerAltName.trim().isNotEmpty()) {
                                    b.addExtension(
                                        OID.X509_EXTENSION_ISSUER_ALT_NAME.oid,
                                        false,
                                        ASN1.encode(
                                            ASN1Sequence(listOf(
                                                ASN1TaggedObject(
                                                    ASN1TagClass.CONTEXT_SPECIFIC,
                                                    ASN1Encoding.PRIMITIVE,
                                                    6, // URI
                                                    genericIssuerAltName.trim().encodeToByteArray()
                                                )
                                            ))
                                        )
                                    )
                                }

                                if (genericCrlUrl.trim().isNotEmpty()) {
                                    b.addExtension(
                                        OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid,
                                        false,
                                        ASN1.encode(
                                            ASN1Sequence(listOf(
                                                ASN1Sequence(listOf(
                                                    ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, 0, ASN1.encode(
                                                        ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, 0, ASN1.encode(
                                                            ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.PRIMITIVE, 6,
                                                                genericCrlUrl.trim().encodeToByteArray()
                                                            )
                                                        ))
                                                    ))
                                                ))
                                            ))
                                        )
                                    )
                                }

                                if (genericIncludeSubjectKeyIdentifier) {
                                    b.includeSubjectKeyIdentifier(true)
                                }

                                if (genericIncludeAuthorityKeyIdentifier) {
                                    if (genericUseSigningCert) {
                                        val parentCert = X509Cert.fromPem(genericSigningCertPem.trim())
                                        b.setAuthorityKeyIdentifierToCertificate(parentCert)
                                    } else {
                                        b.includeAuthorityKeyIdentifierAsSubjectKeyIdentifier(true)
                                    }
                                }

                                for (customExt in genericCustomExtensions) {
                                    if (customExt.oid.trim().isNotEmpty()) {
                                        val extBytes = decodeHex(customExt.hexData.trim().replace(" ", "").replace(":", ""))
                                        b.addExtension(
                                            customExt.oid.trim(),
                                            customExt.isCritical,
                                            extBytes
                                        )
                                    }
                                }

                                b.build()
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
                    if (!useCustomKey && generatedPrivateKeyPem.isNotEmpty()) {
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

private fun parsePrivateKeyAndPublicKey(input: String): Pair<EcPrivateKey, EcPublicKey> {
    val trimmed = input.trim()
    if (trimmed.startsWith("{")) {
        try {
            val json = Json.parseToJsonElement(trimmed).jsonObject
            val privKey = EcPrivateKey.fromJwk(json)
            val pubKey = privKey.publicKey
            return Pair(privKey, pubKey)
        } catch (e: Throwable) {
            throw IllegalArgumentException("Failed to parse private key as JWK: ${e.message}", e)
        }
    }

    // PEM parsing
    val encoded = Base64.Mime.decode(trimmed
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("-----BEGIN EC PRIVATE KEY-----", "")
        .replace("-----END EC PRIVATE KEY-----", "")
        .trim())
    
    val rootObj = ASN1.decode(encoded) as ASN1Sequence
    val privateKeySeq = if (rootObj.elements.size > 2 && rootObj.elements[2] is ASN1OctetString) {
        val octetString = rootObj.elements[2] as ASN1OctetString
        ASN1.decode(octetString.value) as ASN1Sequence
    } else {
        rootObj
    }

    val privateKeyInfo = rootObj
    val privateKeyAlgorithm = privateKeyInfo.elements[1] as ASN1Sequence
    val algorithm = privateKeyAlgorithm.elements[0] as ASN1ObjectIdentifier
    val curve = when (algorithm.oid) {
        OID.EC_PUBLIC_KEY.oid -> {
            val ecCurveString = privateKeyAlgorithm.elements[1] as ASN1ObjectIdentifier
            when (ecCurveString.oid) {
                "1.2.840.10045.3.1.7" -> EcCurve.P256
                "1.3.132.0.34" -> EcCurve.P384
                "1.3.132.0.35" -> EcCurve.P521
                "1.3.36.3.3.2.8.1.1.7" -> EcCurve.BRAINPOOLP256R1
                "1.3.36.3.3.2.8.1.1.9" -> EcCurve.BRAINPOOLP320R1
                "1.3.36.3.3.2.8.1.1.11" -> EcCurve.BRAINPOOLP384R1
                "1.3.36.3.3.2.8.1.1.13" -> EcCurve.BRAINPOOLP512R1
                else -> throw IllegalStateException("Unexpected curve OID ${ecCurveString.oid}")
            }
        }
        "1.3.101.110" -> EcCurve.X25519
        "1.3.101.111" -> EcCurve.X448
        "1.3.101.112" -> EcCurve.ED25519
        "1.3.101.113" -> EcCurve.ED448
        else -> throw IllegalStateException("Unexpected OID ${algorithm.oid}")
    }

    var publicKeyBitString: ByteArray? = null
    for (element in privateKeySeq.elements) {
        if (element is ASN1TaggedObject && element.tag == 1) {
            val decodedBitString = ASN1.decode(element.content)
            if (decodedBitString is ASN1BitString) {
                publicKeyBitString = decodedBitString.value
            } else if (decodedBitString is ASN1OctetString) {
                publicKeyBitString = decodedBitString.value
            } else {
                publicKeyBitString = element.content
            }
            break
        }
    }

    if (publicKeyBitString == null) {
        throw IllegalArgumentException("The private key PEM does not contain the optional public key structure. Please use a private key PEM containing the public key (SEC1/PKCS#8 with embedded public key).")
    }

    val pubKey = when (curve) {
        EcCurve.P256,
        EcCurve.P384,
        EcCurve.P521,
        EcCurve.BRAINPOOLP256R1,
        EcCurve.BRAINPOOLP320R1,
        EcCurve.BRAINPOOLP384R1,
        EcCurve.BRAINPOOLP512R1 -> {
            org.multipaz.crypto.EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(curve, publicKeyBitString)
        }
        EcCurve.ED25519,
        EcCurve.X25519,
        EcCurve.ED448,
        EcCurve.X448 -> {
            org.multipaz.crypto.EcPublicKeyOkp(curve, publicKeyBitString)
        }
    }

    val privKey = EcPrivateKey.fromPem(trimmed, pubKey)
    return Pair(privKey, pubKey)
}

data class CustomExtItem(val oid: String, val isCritical: Boolean, val hexData: String)
