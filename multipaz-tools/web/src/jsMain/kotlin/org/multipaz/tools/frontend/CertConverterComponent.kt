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
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.*
import kotlinx.browser.window
import org.multipaz.crypto.X509Cert
import org.multipaz.util.toBase64
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import org.multipaz.util.fromBase64
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex

val CertConverterComponent = FC {
    var certInput by useState("")
    var certPemOutput by useState("")
    var certHexOutput by useState("")
    var certBase64Output by useState("")
    var certBase64UrlOutput by useState("")
    var certError by useState("")
    var parsedCertInfo by useState<CertInfo?>(null)

    fun convertCert(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            parsedCertInfo = null
            certError = ""
            certPemOutput = ""
            certHexOutput = ""
            certBase64Output = ""
            certBase64UrlOutput = ""
            return
        }

        try {
            val bytes = if (trimmed.contains("-----BEGIN")) {
                val pemBody = trimmed.lines()
                    .filter { !it.startsWith("-----") }
                    .joinToString("")
                    .replace("\r", "")
                    .replace("\n", "")
                    .replace(" ", "")
                    .trim()
                pemBody.fromBase64()
            } else if (trimmed.all { it in "0123456789abcdefABCDEF \n\r:" }) {
                val hexClean = trimmed.replace(" ", "").replace(":", "").replace("\n", "").replace("\r", "")
                hexClean.fromHex()
            } else {
                val cleanB64 = trimmed.replace("\n", "").replace("\r", "").replace(" ", "")
                try {
                    cleanB64.fromBase64()
                } catch (e: Throwable) {
                    cleanB64.fromBase64Url()
                }
            }

            val cert = X509Cert(kotlinx.io.bytestring.ByteString(bytes))
            val subjectStr = cert.subject.name
            val issuerStr = cert.issuer.name
            val serialStr = cert.serialNumber.value.toHex()
            val validFromStr = cert.validityNotBefore.toString()
            val validToStr = cert.validityNotAfter.toString()
            val curveStr = try {
                cert.ecPublicKey.curve.name
            } catch (e: Throwable) {
                null
            }

            parsedCertInfo = CertInfo(
                subject = subjectStr,
                issuer = issuerStr,
                serial = serialStr,
                validFrom = validFromStr,
                validTo = validToStr,
                curve = curveStr
            )
            certError = ""

            val base64Str = bytes.toBase64()
            certHexOutput = bytes.toHex()
            certBase64Output = base64Str
            certBase64UrlOutput = bytes.toBase64Url()
            
            val chunks = base64Str.chunked(64).joinToString("\n")
            certPemOutput = "-----BEGIN CERTIFICATE-----\n$chunks\n-----END CERTIFICATE-----"
        } catch (e: Throwable) {
            parsedCertInfo = null
            certError = e.message ?: "Invalid certificate"
            certPemOutput = ""
            certHexOutput = ""
            certBase64Output = ""
            certBase64UrlOutput = ""
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
            +"Certificate Converter"
        }

        p {
            css {
                color = Color("#94a3b8")
                marginBottom = 24.px
            }
            +"Paste a certificate in PEM, Hex, Base64, or Base64Url format. It will be validated and converted to all formats simultaneously."
        }

        label {
            css {
                display = Display.block
                fontWeight = FontWeight.bold
                marginBottom = 8.px
                color = Color("#cbd5e1")
            }
            +"Paste Certificate:"
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
                marginBottom = 24.px
                focus {
                    outline = None.none
                    borderColor = Color("#3b82f6")
                }
            }
            value = certInput
            placeholder = "-----BEGIN CERTIFICATE-----\n...\nor hex (e.g. 3082...)\nor Base64..."
            onChange = { e ->
                certInput = e.target.value
                convertCert(e.target.value)
            }
        }

        if (certError.isNotEmpty()) {
            div {
                css {
                    color = Color("#ef4444")
                    fontWeight = FontWeight.bold
                    marginBottom = 24.px
                    padding = 12.px
                    background = Color("#7f1d1d")
                    border = Border(1.px, LineStyle.solid, Color("#fca5a5"))
                    borderRadius = 8.px
                }
                +certError
            }
        }

        if (parsedCertInfo != null) {
            // Info block
            div {
                css {
                    background = Color("#0f172a")
                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                    borderRadius = 12.px
                    padding = 20.px
                    marginBottom = 24.px
                }
                h3 {
                    css { margin = 0.px; fontSize = 1.2.rem; color = Color("#38bdf8"); marginBottom = 12.px }
                    +"Certificate Details"
                }
                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(auto-fit, minmax(280px, 1fr))".unsafeCast<GridTemplateColumns>()
                        gap = 16.px
                    }
                    div {
                        p { css { margin = 0.px; color = Color("#94a3b8"); fontSize = 12.px }; +"Subject" }
                        p { css { margin = Margin(4.px, 0.px, 0.px, 0.px); color = Color("#f1f5f9"); fontWeight = FontWeight.bold; fontSize = 13.px }; +parsedCertInfo!!.subject }
                    }
                    div {
                        p { css { margin = 0.px; color = Color("#94a3b8"); fontSize = 12.px }; +"Issuer" }
                        p { css { margin = Margin(4.px, 0.px, 0.px, 0.px); color = Color("#f1f5f9"); fontWeight = FontWeight.bold; fontSize = 13.px }; +parsedCertInfo!!.issuer }
                    }
                    div {
                        p { css { margin = 0.px; color = Color("#94a3b8"); fontSize = 12.px }; +"Serial Number (Hex)" }
                        p { css { margin = Margin(4.px, 0.px, 0.px, 0.px); color = Color("#34d399"); fontFamily = FontFamily.monospace; fontSize = 13.px }; +parsedCertInfo!!.serial }
                    }
                    div {
                        p { css { margin = 0.px; color = Color("#94a3b8"); fontSize = 12.px }; +"Validity" }
                        p { css { margin = Margin(4.px, 0.px, 0.px, 0.px); color = Color("#f1f5f9"); fontSize = 13.px }; +"${parsedCertInfo!!.validFrom} to ${parsedCertInfo!!.validTo}" }
                    }
                    parsedCertInfo!!.curve?.let { c ->
                        div {
                            p { css { margin = 0.px; color = Color("#94a3b8"); fontSize = 12.px }; +"Public Key Curve" }
                            p { css { margin = Margin(4.px, 0.px, 0.px, 0.px); color = Color("#f1f5f9"); fontSize = 13.px }; +c }
                        }
                    }
                }
            }

            // Four conversion output cards side by side
            div {
                css {
                    display = Display.grid
                    gridTemplateColumns = "repeat(auto-fit, minmax(280px, 1fr))".unsafeCast<GridTemplateColumns>()
                    gap = 20.px
                }

                listOf(
                    "PEM format" to certPemOutput,
                    "Hex format (DER)" to certHexOutput,
                    "Base64 format (DER)" to certBase64Output,
                    "Base64Url format (DER)" to certBase64UrlOutput
                ).forEach { (title, outputVal) ->
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 10.px
                            padding = 16.px
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            gap = 8.px
                        }
                        div {
                            css { display = Display.flex; justifyContent = JustifyContent.spaceBetween; alignItems = AlignItems.center }
                            span { css { color = Color("#94a3b8"); fontWeight = FontWeight.bold; fontSize = 13.px }; +title }
                            button {
                                css {
                                    background = Color("#1e293b")
                                    border = Border(1.px, LineStyle.solid, Color("#334155"))
                                    color = Color("#60a5fa")
                                    padding = Padding(4.px, 8.px)
                                    borderRadius = 6.px
                                    cursor = Cursor.pointer
                                    fontSize = 11.px
                                    fontWeight = FontWeight.bold
                                    hover { background = Color("#334155") }
                                }
                                onClick = {
                                    window.navigator.asDynamic().clipboard.writeText(outputVal)
                                }
                                +"Copy"
                            }
                        }
                        textarea {
                            css {
                                width = 100.pct; height = 120.px; background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#334155")); borderRadius = 6.px
                                color = Color("#38bdf8"); fontFamily = FontFamily.monospace; padding = 8.px; fontSize = 11.px
                                resize = "none".unsafeCast<Resize>()
                            }
                            readOnly = true
                            value = outputVal
                        }
                    }
                }
            }
        }
    }
}

data class CertInfo(
    val subject: String,
    val issuer: String,
    val serial: String,
    val validFrom: String,
    val validTo: String,
    val curve: String?
)
