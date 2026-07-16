@file:OptIn(kotlin.time.ExperimentalTime::class)
package org.multipaz.tools.frontend

import emotion.react.css
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import react.FC
import react.create
import react.dom.client.createRoot
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.footer
import react.useState
import react.useEffect
import react.useEffectOnce
import web.cssom.*
import web.dom.Element
import org.multipaz.util.Platform

val mainScope = CoroutineScope(Dispatchers.Main)

fun main() {
    js("require('./style.css')")
    window.onload = {
        val rootElement = document.getElementById("root") ?: error("No root element found")
        val root = createRoot(rootElement.unsafeCast<Element>())
        root.render(App.create())
    }
}

fun pathToTab(path: String): String {
    return when (path) {
        "/cbor" -> "cbor-decode"
        "/mdocDeviceResponse" -> "mdoc-view"
        "/msoNamespaces" -> "mso-namespaces-view"
        "/sdjwt" -> "sd-jwt-inspect"
        "/compress" -> "compress"
        "/converter" -> "converter"
        "/x509" -> "x509"
        "/cert-converter" -> "cert-converter"
        "/keygen" -> "key-generator"
        "/cert" -> "cert-generator"
        "/ndef" -> "ndef-parse"
        else -> "cbor-decode"
    }
}

fun tabToPath(tab: String): String {
    return when (tab) {
        "cbor-decode" -> "/cbor"
        "mdoc-view" -> "/mdocDeviceResponse"
        "mso-namespaces-view" -> "/msoNamespaces"
        "sd-jwt-inspect" -> "/sdjwt"
        "compress" -> "/compress"
        "converter" -> "/converter"
        "x509" -> "/x509"
        "cert-converter" -> "/cert-converter"
        "key-generator" -> "/keygen"
        "cert-generator" -> "/cert"
        "ndef-parse" -> "/ndef"
        else -> "/cbor"
    }
}

val App = FC {
    var activeTab by useState(pathToTab(window.location.pathname))

    useEffect(activeTab) {
        val currentPath = tabToPath(activeTab)
        if (window.location.pathname != currentPath) {
            window.history.pushState(null, "", currentPath)
        }
    }

    useEffectOnce {
        val handler: (org.w3c.dom.events.Event) -> Unit = {
            activeTab = pathToTab(window.location.pathname)
        }
        window.addEventListener("popstate", handler)
        this.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            window.removeEventListener("popstate", handler)
        }
    }

    div {
        css {
            minHeight = 100.vh
            display = Display.flex
            flexDirection = FlexDirection.column
            background = Color("#0f172a") // slate 900
            color = Color("#f1f5f9") // slate 100
        }

        // Header
        div {
            css {
                background = Color("linear-gradient(to bottom, #1e293b, #0f172a)")
                borderBottom = Border(1.px, LineStyle.solid, Color("#334155"))
                padding = Padding(32.px, 24.px)
                textAlign = TextAlign.center
            }

            h1 {
                css {
                    fontSize = 3.rem
                    fontWeight = FontWeight.bold
                    margin = Margin(0.px, 0.px, 8.px, 0.px)
                    background = Color("linear-gradient(to right, #60a5fa, #a78bfa)")
                    asDynamic().backgroundClip = "text"
                    asDynamic().WebkitBackgroundClip = "text"
                    color = Color("transparent")
                }
                +"Multipaz Developer Tools"
            }

            p {
                css {
                    color = Color("#94a3b8")
                    fontSize = 1.1.rem
                    margin = "0px auto".unsafeCast<Margin>()
                    maxWidth = 600.px
                }
                +"Secure, fully client-side tools for working with CBOR, X.509 certificates, EC keys, ISO mdocs, IETF SD-JWT VCs, and more."
            }
        }

        // Navigation Bar
        nav {
            css {
                display = Display.flex
                justifyContent = JustifyContent.center
                gap = 12.px
                padding = Padding(16.px, 24.px)
                background = Color("#1e293b") // slate 800
                borderBottom = Border(1.px, LineStyle.solid, Color("#334155"))
            }

            val tabs = listOf(
                "cbor-decode" to "CBOR Decoder",
                "mdoc-view" to "ISO mdoc DeviceResponse Parser",
                "mso-namespaces-view" to "ISO mdoc MSO and IssuerNameSpaces",
                "sd-jwt-inspect" to "SD-JWT Parser",
                "compress" to "Compression Tool",
                "converter" to "Format Converter",
                "x509" to "Certificate Parser",
                "cert-converter" to "Certificate Converter",
                "cert-generator" to "Certificate Generator",
                "key-generator" to "Key Generator",
                "ndef-parse" to "NDEF Parser"
            )

            for ((tabId, tabTitle) in tabs) {
                button {
                    css {
                        padding = Padding(10.px, 20.px)
                        border = None.none
                        borderRadius = 8.px
                        fontSize = 15.px
                        fontWeight = FontWeight.bold
                        cursor = Cursor.pointer
                        transition = "all 0.2s".unsafeCast<Transition>()
                        if (activeTab == tabId) {
                            background = Color("#3b82f6") // blue 500
                            color = Color("#ffffff")
                            boxShadow = BoxShadow(0.px, 4.px, 12.px, Color("rgba(59, 130, 246, 0.3)"))
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

        // Content Area
        div {
            css {
                flexGrow = number(1.0)
                padding = Padding(40.px, 24.px)
                maxWidth = 1200.px
                width = 100.pct
                margin = "0px auto".unsafeCast<Margin>()
            }

            when (activeTab) {
                "cbor-decode" -> CborDecoderComponent {}
                "mdoc-view" -> MdocViewerComponent {}
                "mso-namespaces-view" -> MsoNamespacesViewerComponent {}
                "sd-jwt-inspect" -> SdJwtInspectorComponent {}
                "compress" -> CompressionComponent {}
                "converter" -> ConverterComponent {}
                "x509" -> X509ParserComponent {}
                "cert-converter" -> CertConverterComponent {}
                "key-generator" -> KeyGeneratorComponent {}
                "cert-generator" -> CertGeneratorComponent {}
                "ndef-parse" -> NdefParserComponent {}
            }
        }

        // Footer
        footer {
            css {
                padding = Padding(24.px, 24.px)
                borderTop = Border(1.px, LineStyle.solid, Color("#334155"))
                background = Color("#020617")
                textAlign = TextAlign.center
                color = Color("#64748b")
                fontSize = 14.px
            }
            +"Multipaz ${Platform.version} — All computations occur locally in your browser, no data is sent to the server."
        }
    }
}
