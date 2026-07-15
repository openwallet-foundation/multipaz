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
import react.dom.html.ReactHTML.pre
import react.useState
import web.cssom.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.multipaz.sdjwt.SdJwt
import org.multipaz.util.fromBase64Url
import org.multipaz.util.zlibInflate

val SdJwtInspectorComponent = FC {
    var rawInput by useState("")
    var parsedSdJwt by useState<SdJwt?>(null)
    var parseError by useState("")
    var showDisclosures by useState(true)

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
            +"SD-JWT Token Parser"
        }

        if (parsedSdJwt != null || parseError.isNotEmpty()) {
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
                    parsedSdJwt = null
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
        } else {
            p {
                css {
                    color = Color("#94a3b8")
                    marginBottom = 24.px
                }
                +"Decode and inspect an SD-JWT token (compact serialization). Splitting the token into the Issuer Signed JWT header/body, the individual claim Disclosures, and Key Binding signatures."
            }

            label {
                css {
                    display = Display.block
                    fontWeight = FontWeight.bold
                    marginBottom = 8.px
                    color = Color("#cbd5e1")
                }
                +"SD-JWT Token (Compact representation ending with '~' or carrying disclosures):"
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
                placeholder = "Paste SD-JWT (e.g. eyJhbGciOiJFUzI1NiIs...~WyJHcTJza...~)"
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
                            val inputStr = rawInput.trim()
                            val token = if (inputStr.contains(".") || inputStr.contains("~")) {
                                inputStr
                            } else {
                                val bytes = decodeInputToBytes(inputStr)
                                val decompressedBytes = try {
                                    bytes.zlibInflate()
                                } catch (e: Throwable) {
                                    bytes
                                }
                                decompressedBytes.decodeToString()
                            }
                            val sdjwt = SdJwt.fromCompactSerialization(token)
                            // Eagerly evaluate properties to catch exceptions here
                            sdjwt.issuer
                            sdjwt.credentialType
                            sdjwt.subject
                            sdjwt.issuedAt
                            sdjwt.validUntil
                            sdjwt.digestAlg
                            sdjwt.disclosures
                            parsedSdJwt = sdjwt
                            parseError = ""
                        } catch (e: Throwable) {
                            parseError = "Error parsing SD-JWT: " + (e.message ?: "Unknown error")
                            parsedSdJwt = null
                        }
                    }
                }
                +"Inspect SD-JWT"
            }
        }

        parsedSdJwt?.let { sdjwt ->
            div {
                css {
                    marginTop = 32.px
                }

                h3 {
                    css {
                        fontSize = 1.4.rem
                        fontWeight = FontWeight.bold
                        marginBottom = 16.px
                        color = Color("#f1f5f9")
                    }
                    +"Decoded Issuer-Signed JWT"
                }

                div {
                    css {
                        display = Display.grid
                        gridTemplateColumns = "repeat(auto-fit, minmax(300px, 1fr))".unsafeCast<GridTemplateColumns>()
                        gap = 24.px
                        marginBottom = 32.px
                    }

                    // Header Card
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 8.px
                            padding = 20.px
                        }
                        h4 {
                            css {
                                color = Color("#cbd5e1")
                                fontWeight = FontWeight.bold
                                marginBottom = 12.px
                            }
                            +"JWT Header"
                        }
                        pre {
                            css {
                                color = Color("#60a5fa")
                                fontSize = 13.px
                                fontFamily = FontFamily.monospace
                                overflowX = "auto".unsafeCast<Overflow>()
                            }
                            +Json { prettyPrint = true }.encodeToString(sdjwt.jwtHeader)
                        }
                    }

                    // Body Card
                    div {
                        css {
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 8.px
                            padding = 20.px
                        }
                        h4 {
                            css {
                                color = Color("#cbd5e1")
                                fontWeight = FontWeight.bold
                                marginBottom = 12.px
                            }
                            +"JWT Payload / Claims"
                        }
                        pre {
                            css {
                                color = Color("#a78bfa")
                                fontSize = 13.px
                                fontFamily = FontFamily.monospace
                                overflowX = "auto".unsafeCast<Overflow>()
                            }
                            +Json { prettyPrint = true }.encodeToString(sdjwt.jwtBody)
                        }
                    }
                }

                // Metadata Details
                div {
                    css {
                        background = Color("#0f172a")
                        border = Border(1.px, LineStyle.solid, Color("#334155"))
                        borderRadius = 8.px
                        padding = 20.px
                        marginBottom = 32.px
                        fontSize = 14.px
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 8.px
                    }
                    div {
                        span { css { color = Color("#64748b"); fontWeight = FontWeight.bold } }
                        +"Issuer (iss): "
                        span { css { color = Color("#f1f5f9") } }
                        +sdjwt.issuer
                    }
                    sdjwt.credentialType?.let { vct ->
                        div {
                            +"Credential Type (vct): "
                            span { css { color = Color("#38bdf8") } }
                            +vct
                        }
                    }
                    sdjwt.subject?.let { sub ->
                        div {
                            +"Subject (sub): "
                            span { css { color = Color("#cbd5e1") } }
                            +sub
                        }
                    }
                    sdjwt.issuedAt?.let { iat ->
                        div {
                            +"Issued At (iat): "
                            span { css { color = Color("#cbd5e1") } }
                            +iat.toString()
                        }
                    }
                    sdjwt.validUntil?.let { exp ->
                        div {
                            +"Expiration (exp): "
                            span { css { color = Color("#cbd5e1") } }
                            +exp.toString()
                        }
                    }
                    div {
                        +"Digest Algorithm (_sd_alg): "
                        span { css { color = Color("#34d399") } }
                        +sdjwt.digestAlg.hashAlgorithmName
                    }
                    div {
                        +"Disclosures Count: "
                        span { css { color = Color("#a78bfa") } }
                        +sdjwt.disclosures.size.toString()
                    }
                }

                // Disclosures Table
                if (sdjwt.disclosures.isNotEmpty()) {
                    h3 {
                        css {
                            fontSize = 1.4.rem
                            fontWeight = FontWeight.bold
                            marginBottom = 16.px
                            color = Color("#f1f5f9")
                        }
                        +"Disclosures"
                    }

                    table {
                        css {
                            width = 100.pct
                            borderCollapse = BorderCollapse.collapse
                            background = Color("#0f172a")
                            border = Border(1.px, LineStyle.solid, Color("#334155"))
                            borderRadius = 8.px
                            fontSize = 14.px
                            marginBottom = 24.px
                        }
                        thead {
                            tr {
                                th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Index" }
                                th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Salt" }
                                th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Claim Name" }
                                th { css { textAlign = TextAlign.left; padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#334155")); color = Color("#94a3b8") }; +"Claim Value" }
                            }
                        }
                        tbody {
                            sdjwt.disclosures.forEachIndexed { index, discStr ->
                                val decoded = try {
                                    val jsonArr = Json.decodeFromString<JsonArray>(discStr.fromBase64Url().decodeToString())
                                    if (jsonArr.size == 3) {
                                        // Standard claim: [salt, name, value]
                                        Triple(jsonArr[0].jsonPrimitive.content, jsonArr[1].jsonPrimitive.content, jsonArr[2].toString())
                                    } else if (jsonArr.size == 2) {
                                        // Array element: [salt, value]
                                        Triple(jsonArr[0].jsonPrimitive.content, "(Array Element)", jsonArr[1].toString())
                                    } else {
                                        Triple("Unknown", "Unknown", jsonArr.toString())
                                    }
                                } catch (e: Exception) {
                                    Triple("Error", "Error", "Failed to decode: " + e.message)
                                }

                                tr {
                                    td { css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); color = Color("#64748b") }; +(index + 1).toString() }
                                    td { css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; fontSize = 12.px; color = Color("#cbd5e1") }; +decoded.first }
                                    td { css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontWeight = FontWeight.bold; color = Color("#38bdf8") }; +decoded.second }
                                    td { css { padding = 12.px; borderBottom = Border(1.px, LineStyle.solid, Color("#1e293b")); fontFamily = FontFamily.monospace; color = Color("#34d399") }; +decoded.third }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
