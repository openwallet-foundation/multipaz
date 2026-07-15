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
import react.useState
import web.cssom.*
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.util.toHex

val KeyGeneratorComponent = FC {
    var selectedCurve by useState(EcCurve.P256)
    var generatedPrivateKey by useState<EcPrivateKey?>(null)
    var isGenerating by useState(false)
    var privateKeyTab by useState("jwk")
    var publicKeyTab by useState("jwk")
    var copyPrivateKeySuccess by useState(false)
    var copyPublicKeySuccess by useState(false)
    var generateError by useState("")

    // Precomputed formats
    var jwkPrivateText by useState("")
    var jwkPublicText by useState("")
    var cosePrivateText by useState("")
    var cosePublicText by useState("")
    var diagPrivateText by useState("")
    var diagPublicText by useState("")
    var pemPrivateText by useState("")
    var pemPublicText by useState("")

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
            +"EC Key Pair Generator"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Generate secure Elliptic Curve public/private key pairs directly in your browser. Select your preferred curve below."
        }

        div {
            css {
                display = Display.flex
                flexDirection = FlexDirection.column
                gap = 8.px
                marginBottom = 24.px
                maxWidth = 400.px
            }

            label {
                css {
                    fontWeight = FontWeight.bold
                    color = Color("#cbd5e1")
                }
                +"Select Elliptic Curve:"
            }

            select {
                css {
                    padding = 12.px
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#475569"))
                    borderRadius = 8.px
                    color = Color("#f1f5f9")
                    fontSize = 15.px
                }
                value = selectedCurve.name
                onChange = {
                    val curveVal = EcCurve.valueOf(it.target.value)
                    selectedCurve = curveVal
                }
                Crypto.supportedCurves.sortedBy { it.name }.forEach { curve ->
                    option {
                        value = curve.name
                        val labelText = when (curve) {
                            EcCurve.P256 -> "P-256 (secp256r1)"
                            EcCurve.P384 -> "P-384 (secp384r1)"
                            EcCurve.P521 -> "P-521 (secp521r1)"
                            EcCurve.ED25519 -> "Ed25519 (EdDSA)"
                            EcCurve.X25519 -> "X25519 (ECDH)"
                            EcCurve.ED448 -> "Ed448 (EdDSA)"
                            EcCurve.X448 -> "X448 (ECDH)"
                            else -> curve.name
                        }
                        +labelText
                    }
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
            disabled = isGenerating
            onClick = {
                mainScope.launch {
                    isGenerating = true
                    try {
                        generateError = ""
                        generatedPrivateKey = null
                        val key = Crypto.createEcPrivateKey(selectedCurve)
                        generatedPrivateKey = key
                        
                        val jwkPrivate = key.toJwk()
                        jwkPrivateText = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), jwkPrivate)

                        val jwkPublic = key.publicKey.toJwk()
                        jwkPublicText = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), jwkPublic)

                        cosePrivateText = Cbor.encode(key.toCoseKey().toDataItem()).toHex()
                        cosePublicText = Cbor.encode(key.publicKey.toCoseKey().toDataItem()).toHex()

                        diagPrivateText = Cbor.toDiagnostics(key.toCoseKey().toDataItem(), setOf(DiagnosticOption.PRETTY_PRINT))
                        diagPublicText = Cbor.toDiagnostics(key.publicKey.toCoseKey().toDataItem(), setOf(DiagnosticOption.PRETTY_PRINT))

                        pemPrivateText = key.toPem()
                        pemPublicText = key.publicKey.toPem()

                        copyPrivateKeySuccess = false
                        copyPublicKeySuccess = false
                    } catch (e: Throwable) {
                        generateError = "Error generating key for curve ${selectedCurve.name}: " + (e.message ?: "Unsupported by current browser")
                    } finally {
                        isGenerating = false
                    }
                }
            }
            if (isGenerating) {
                +"Generating..."
            } else {
                +"Generate Key Pair"
            }
        }

        if (generateError.isNotEmpty()) {
            div {
                css {
                    marginTop = 16.px
                    color = Color("#ef4444")
                    fontWeight = FontWeight.bold
                    background = Color("#7f1d1d")
                    padding = Padding(10.px, 16.px)
                    borderRadius = 8.px
                    border = Border(1.px, LineStyle.solid, Color("#fca5a5"))
                }
                +generateError
            }
        }

        generatedPrivateKey?.let { privateKey ->
            div {
                css {
                    marginTop = 32.px
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    gap = 24.px
                }

                // Curve Details Card
                div {
                    css {
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        borderRadius = 12.px
                        padding = 20.px
                    }
                    h3 {
                        css {
                            margin = Margin(0.px, 0.px, 12.px, 0.px)
                            fontSize = 1.2.rem
                            color = Color("#f1f5f9")
                        }
                        +"Curve Information"
                    }
                    div {
                        css {
                            display = Display.grid
                            gridTemplateColumns = "repeat(4, 1fr)".unsafeCast<GridTemplateColumns>()
                            gap = 16.px
                        }
                        div {
                            span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"CURVE NAME" }
                            span { css { color = Color("#38bdf8"); fontWeight = FontWeight.bold } ; +selectedCurve.name }
                        }
                        div {
                            span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"KEY SIZE" }
                            span { css { color = Color("#f1f5f9") } ; +"${selectedCurve.bitSize} bits" }
                        }
                        div {
                            span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"SIGNING" }
                            span { css { color = Color(if (selectedCurve.supportsSigning) "#10b981" else "#ef4444") } ; +(if (selectedCurve.supportsSigning) "Supported" else "Not Supported") }
                        }
                        div {
                            span { css { display = Display.block; color = Color("#64748b"); fontSize = 11.px; fontWeight = FontWeight.bold } ; +"KEY AGREEMENT" }
                            span { css { color = Color(if (selectedCurve.supportsKeyAgreement) "#10b981" else "#ef4444") } ; +(if (selectedCurve.supportsKeyAgreement) "Supported" else "Not Supported") }
                        }
                    }
                }

                // Grid layout for Private and Public keys side-by-side
                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(2, 1fr)".unsafeCast<GridTemplateColumns>()
                        gap = 24.px
                    }

                    // Private Key Section
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
                            }
                            +"Private Key"
                        }

                        // Formats Navbar
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
                                "cose" to "COSE Hex (CBOR)",
                                "diagnostic" to "Diagnostic",
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

                        val privateKeyContent = when (privateKeyTab) {
                            "jwk" -> jwkPrivateText
                            "cose" -> cosePrivateText
                            "diagnostic" -> diagPrivateText
                            else -> pemPrivateText
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
                            value = privateKeyContent
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
                                window.navigator.asDynamic().clipboard.writeText(privateKeyContent)
                                copyPrivateKeySuccess = true
                            }
                            +(if (copyPrivateKeySuccess) "Copied!" else "Copy Private Key")
                        }
                    }

                    // Public Key Section
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
                            }
                            +"Public Key"
                        }

                        // Formats Navbar
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
                                "cose" to "COSE Hex (CBOR)",
                                "diagnostic" to "Diagnostic",
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

                        val publicKeyContent = when (publicKeyTab) {
                            "jwk" -> jwkPublicText
                            "cose" -> cosePublicText
                            "diagnostic" -> diagPublicText
                            else -> pemPublicText
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
                            value = publicKeyContent
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
                                window.navigator.asDynamic().clipboard.writeText(publicKeyContent)
                                copyPublicKeySuccess = true
                            }
                            +(if (copyPublicKeySuccess) "Copied!" else "Copy Public Key")
                        }
                    }
                }
            }
        }
    }
}
