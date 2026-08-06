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
import react.dom.html.ReactHTML.footer
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
import react.useState
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
        "/cdn" -> "cdn-decode"
        "/mdocDeviceResponse" -> "mdoc-view"
        "/mdocDeviceRequest" -> "device-request-parse"
        "/deviceRequest" -> "device-request-parse"
        "/iso18013-7-annex-c" -> "annex-c-parse"
        "/annexC" -> "annex-c-parse"
        "/iso18013-7-verifier" -> "iso18013-7-verifier"
        "/verifier" -> "iso18013-7-verifier"
        "/msoNamespaces" -> "mso-namespaces-view"
        "/sdjwt" -> "sd-jwt-inspect"
        "/compress" -> "compress"
        "/converter" -> "converter"
        "/x509" -> "x509"
        "/asn1" -> "asn1"
        "/cert-converter" -> "cert-converter"
        "/keygen" -> "key-generator"
        "/cert" -> "cert-generator"
        "/ndef" -> "ndef-parse"
        "/event" -> "event-decode"
        "/events" -> "event-decode"
        "/mpzpass" -> "mpzpass-decode"
        "/mpzpass-decode" -> "mpzpass-decode"
        "/mpzpass-create" -> "mpzpass-create"
        "/mpzpass-creator" -> "mpzpass-create"
        else -> "cbor-decode"
    }
}

fun tabToPath(tab: String): String {
    return when (tab) {
        "cbor-decode" -> "/cbor"
        "cdn-decode" -> "/cdn"
        "mdoc-view" -> "/mdocDeviceResponse"
        "device-request-parse" -> "/mdocDeviceRequest"
        "annex-c-parse" -> "/iso18013-7-annex-c"
        "iso18013-7-verifier" -> "/iso18013-7-verifier"
        "mso-namespaces-view" -> "/msoNamespaces"
        "sd-jwt-inspect" -> "/sdjwt"
        "compress" -> "/compress"
        "converter" -> "/converter"
        "x509" -> "/x509"
        "asn1" -> "/asn1"
        "cert-converter" -> "/cert-converter"
        "key-generator" -> "/keygen"
        "cert-generator" -> "/cert"
        "ndef-parse" -> "/ndef"
        "event-decode" -> "/event"
        "mpzpass-decode" -> "/mpzpass"
        "mpzpass-create" -> "/mpzpass-create"
        else -> "/cbor"
    }
}

val App = FC {
    var activeTab by useState(pathToTab(window.location.pathname))
    var activeDropdown by useState<String?>(null)
    var isShortLinkModalOpen by useState(false)
    val (_, setTick) = useState(0)

    useEffect(activeTab) {
        val currentPath = tabToPath(activeTab)
        if (window.location.pathname != currentPath) {
            window.history.pushState(null, "", currentPath)
            setTick { it + 1 }
        }
    }

    useEffectOnce {
        val listener = {
            setTick { it + 1 }
        }

        val handler: (org.w3c.dom.events.Event) -> Unit = {
            activeTab = pathToTab(window.location.pathname)
            listener()
        }

        onHashChangeListeners.add(listener)
        window.addEventListener("popstate", handler)
        window.addEventListener("hashchange", handler)
        window.setInterval(listener, 100)
    }

    val currentHash = window.location.hash

    div {
        onClick = { activeDropdown = null }
        css {
            minHeight = 100.vh
            display = Display.flex
            flexDirection = FlexDirection.column
            background = Color("#0f172a") // slate 900
            color = Color("#f1f5f9") // slate 100
        }

        div {
            key = "short-link-container"
            if (currentHash.startsWith("#") && currentHash.length > 1) {
                button {
                    css {
                        position = Position.fixed
                        top = 20.px
                        right = 20.px
                        zIndex = integer(1000)
                        padding = Padding(10.px, 18.px)
                        fontSize = 14.px
                        fontWeight = FontWeight.bold
                        backgroundColor = Color("#2563eb")
                        color = Color("#ffffff")
                        border = None.none
                        borderRadius = 8.px
                        cursor = Cursor.pointer
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 8.px
                        boxShadow = BoxShadow(0.px, 4.px, 16.px, Color("rgba(0, 0, 0, 0.5)"))
                        hover {
                            backgroundColor = Color("#1d4ed8")
                        }
                    }
                    onClick = { event ->
                        event.stopPropagation()
                        isShortLinkModalOpen = true
                    }
                    +"🔗 Create Short Link"
                }
            }
        }

        // Header
        div {
            key = "header"
            css {
                position = Position.relative
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

        ShortLinkModalComponent {
            isOpen = isShortLinkModalOpen
            onClose = { isShortLinkModalOpen = false }
            targetPath = window.location.pathname + window.location.hash
        }

        // Navigation Bar
        nav {
            key = "nav"
            css {
                display = Display.flex
                justifyContent = JustifyContent.center
                gap = 24.px
                padding = Padding(16.px, 24.px)
                background = Color("#1e293b") // slate 800
                borderBottom = Border(1.px, LineStyle.solid, Color("#334155"))
            }

            data class Category(
                val id: String,
                val title: String,
                val tabs: List<Pair<String, String>>
            )

            val categories = listOf(
                Category("decoders", "Decoders & Parsers", listOf(
                    "cbor-decode" to "CBOR Decoder",
                    "cdn-decode" to "CDN Decoder",
                    "asn1" to "ASN.1 Decoder",
                    "ndef-parse" to "NDEF Decoder"
                )),
                Category("identity", "ISO mdoc & SD-JWT", listOf(
                    "iso18013-7-verifier" to "ISO 18013-7 Verifier",
                    "mdoc-view" to "ISO mdoc DeviceResponse Parser",
                    "device-request-parse" to "ISO mdoc DeviceRequest Parser",
                    "annex-c-parse" to "ISO 18013-7 Annex C Parser",
                    "mso-namespaces-view" to "ISO mdoc MSO & IssuerNameSpaces",
                    "sd-jwt-inspect" to "SD-JWT Parser"
                )),
                Category("certs", "Certificates & Keys", listOf(
                    "x509" to "Certificate Parser",
                    "cert-converter" to "Certificate Converter",
                    "cert-generator" to "Certificate Generator",
                    "key-generator" to "Key Generator"
                )),
                Category("utilities", "Utilities", listOf(
                    "compress" to "Compression Tool",
                    "converter" to "Format Converter",
                    "event-decode" to "Multipaz Event Decoder",
                    "mpzpass-decode" to "MpzPass Decoder",
                    "mpzpass-create" to "MpzPass Creator"
                ))
            )

            for (category in categories) {
                val isCategoryActive = category.tabs.any { it.first == activeTab }
                val isOpen = activeDropdown == category.id

                div {
                    css {
                        position = Position.relative
                    }

                    button {
                        css {
                            padding = Padding(10.px, 20.px)
                            border = None.none
                            borderRadius = 8.px
                            fontSize = 15.px
                            fontWeight = FontWeight.bold
                            cursor = Cursor.pointer
                            transition = "all 0.2s".unsafeCast<Transition>()
                            if (isCategoryActive) {
                                background = Color("#3b82f6") // blue 500
                                color = Color("#ffffff")
                                boxShadow = BoxShadow(0.px, 4.px, 12.px, Color("rgba(59, 130, 246, 0.3)"))
                            } else {
                                background = if (isOpen) Color("#334155") else Color("transparent")
                                color = if (isOpen) Color("#f1f5f9") else Color("#94a3b8")
                                hover {
                                    background = Color("#334155")
                                    color = Color("#f1f5f9")
                                }
                            }
                        }
                        onClick = { event ->
                            event.stopPropagation()
                            activeDropdown = if (isOpen) null else category.id
                        }
                        +category.title
                        +" ▾"
                    }

                    if (isOpen) {
                        div {
                            css {
                                position = Position.absolute
                                top = 100.pct
                                marginTop = 8.px
                                left = 0.px
                                zIndex = integer(50)
                                background = Color("#1e293b")
                                border = Border(1.px, LineStyle.solid, Color("#334155"))
                                borderRadius = 8.px
                                padding = 8.px
                                display = Display.flex
                                flexDirection = FlexDirection.column
                                gap = 4.px
                                minWidth = 260.px
                                boxShadow = BoxShadow(0.px, 10.px, 15.px, Color("rgba(0, 0, 0, 0.5)"))
                            }

                            for ((tabId, tabTitle) in category.tabs) {
                                val isTabActive = activeTab == tabId
                                button {
                                    css {
                                        padding = Padding(8.px, 16.px)
                                        textAlign = TextAlign.left
                                        border = None.none
                                        borderRadius = 6.px
                                        cursor = Cursor.pointer
                                        fontSize = 14.px
                                        fontWeight = FontWeight.bold
                                        transition = "all 0.2s".unsafeCast<Transition>()
                                        if (isTabActive) {
                                            background = Color("#2563eb") // blue 600
                                            color = Color("#ffffff")
                                        } else {
                                            background = Color("transparent")
                                            color = Color("#94a3b8")
                                            hover {
                                                background = Color("#334155")
                                                color = Color("#f1f5f9")
                                            }
                                        }
                                    }
                                    onClick = {
                                        val targetPath = tabToPath(tabId)
                                        if (window.location.pathname != targetPath || window.location.hash.isNotEmpty()) {
                                            window.history.pushState(null, "", targetPath)
                                        }
                                        activeTab = tabId
                                        activeDropdown = null
                                    }
                                    +tabTitle
                                }
                            }
                        }
                    }
                }
            }
        }

        // Content Area
        div {
            key = "content-area"
            css {
                flexGrow = number(1.0)
                padding = Padding(40.px, 24.px)
                maxWidth = 1200.px
                width = 100.pct
                margin = "0px auto".unsafeCast<Margin>()
            }

            when (activeTab) {
                "iso18013-7-verifier" -> Iso180137VerifierComponent { key = "iso18013-7-verifier" }
                "cbor-decode" -> CborDecoderComponent { key = "cbor-decode" }
                "cdn-decode" -> CdnDecoderComponent { key = "cdn-decode" }
                "mdoc-view" -> MdocViewerComponent { key = "mdoc-view" }
                "device-request-parse" -> DeviceRequestParserComponent { key = "device-request-parse" }
                "annex-c-parse" -> AnnexCParserComponent { key = "annex-c-parse" }
                "mso-namespaces-view" -> MsoNamespacesViewerComponent { key = "mso-namespaces-view" }
                "sd-jwt-inspect" -> SdJwtInspectorComponent { key = "sd-jwt-inspect" }
                "compress" -> CompressionComponent { key = "compress" }
                "converter" -> ConverterComponent { key = "converter" }
                "x509" -> X509ParserComponent { key = "x509" }
                "asn1" -> Asn1DecoderComponent { key = "asn1" }
                "cert-converter" -> CertConverterComponent { key = "cert-converter" }
                "key-generator" -> KeyGeneratorComponent { key = "key-generator" }
                "cert-generator" -> CertGeneratorComponent { key = "cert-generator" }
                "ndef-parse" -> NdefParserComponent { key = "ndef-parse" }
                "event-decode" -> EventDecoderComponent { key = "event-decode" }
                "mpzpass-decode" -> MpzPassDecoderComponent { key = "mpzpass-decode" }
                "mpzpass-create" -> MpzPassCreatorComponent { key = "mpzpass-create" }
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
